package logisticspipes.modules;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import net.minecraft.client.renderer.texture.IIconRegister;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.ISidedInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.IIcon;
import net.minecraft.world.World;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidTankInfo;
import net.minecraftforge.fluids.IFluidHandler;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import logisticspipes.LogisticsPipes;
import logisticspipes.config.Configs;
import logisticspipes.interfaces.IInventoryUtil;
import logisticspipes.interfaces.ILPPositionProvider;
import logisticspipes.interfaces.routing.IAdditionalTargetInformation;
import logisticspipes.interfaces.routing.ICraftFluids;
import logisticspipes.interfaces.routing.IFluidContainerReceiver;
import logisticspipes.interfaces.routing.IGatedItemSink;
import logisticspipes.interfaces.routing.IRequestFluid;
import logisticspipes.interfaces.routing.IRequestItems;
import logisticspipes.logisticspipes.IRoutedItem;
import logisticspipes.logisticspipes.IRoutedItem.TransportMode;
import logisticspipes.network.NewGuiHandler;
import logisticspipes.network.PacketHandler;
import logisticspipes.network.abstractguis.ModuleCoordinatesGuiProvider;
import logisticspipes.network.abstractguis.ModuleInHandGuiProvider;
import logisticspipes.network.abstractpackets.ModernPacket;
import logisticspipes.network.guis.module.inhand.SmartCrafterInHand;
import logisticspipes.network.guis.module.inpipe.SmartCrafterModuleSlot;
import logisticspipes.network.packets.cpipe.SmartCrafterSetting;
import logisticspipes.network.packets.pipe.SmartCrafterUpdatePacket;
import logisticspipes.pipes.PipeFluidSatellite;
import logisticspipes.pipes.PipeSmartSatellite;
import logisticspipes.proxy.MainProxy;
import logisticspipes.proxy.SimpleServiceLocator;
import logisticspipes.request.FluidCraftingTemplate;
import logisticspipes.request.IReqCraftingTemplate;
import logisticspipes.request.RequestTree;
import logisticspipes.request.resources.DictResource;
import logisticspipes.request.resources.FluidResource;
import logisticspipes.request.resources.IResource;
import logisticspipes.request.resources.ItemResource;
import logisticspipes.routing.ExitRoute;
import logisticspipes.routing.FluidLogisticsPromise;
import logisticspipes.routing.IRouter;
import logisticspipes.routing.LogisticsPromise;
import logisticspipes.routing.order.CraftingJobs;
import logisticspipes.routing.order.IOrderInfoProvider;
import logisticspipes.routing.order.IOrderInfoProvider.ResourceType;
import logisticspipes.routing.order.LogisticsFluidOrder;
import logisticspipes.routing.order.LogisticsFluidOrderManager;
import logisticspipes.routing.order.LogisticsItemOrder;
import logisticspipes.routing.order.LogisticsOrderManager;
import logisticspipes.utils.AdjacentTile;
import logisticspipes.utils.CacheHolder.CacheTypes;
import logisticspipes.utils.FluidDisplayUtil;
import logisticspipes.utils.FluidIdentifier;
import logisticspipes.utils.OutputPushUtil;
import logisticspipes.utils.SidedInventoryMinecraftAdapter;
import logisticspipes.utils.item.ItemIdentifier;
import logisticspipes.utils.item.ItemIdentifierInventory;
import logisticspipes.utils.item.ItemIdentifierStack;
import logisticspipes.utils.string.StringUtils;
import logisticspipes.utils.tuples.Pair;
import lombok.Getter;

/**
 * A crafting module that only lets ingredients be sent once the machine has room for whole recipe sets.
 * <p>
 * When orders arrive it releases as many sets as fit in the machine. Each time a set's result is pulled out, room is
 * checked again and more sets are released. Providers ask {@link #getGatedAllowance} before sending, so held
 * ingredients stay reserved in storage instead of bouncing off a full machine.
 * <p>
 * Only ingredients delivered to this module's own machine are gated. Ingredients sent to satellites are not.
 * <p>
 * Sets are only released while this module holds the machine's claim ({@link MachineClaims}), so two Smart Crafters on
 * one machine take turns instead of mixing their ingredients. When another crafter is waiting, the owner stops
 * releasing new sets, lets the ones in the machine finish and hands the machine over.
 */
public class ModuleSmartCrafter extends ModuleCrafter
        implements IGatedItemSink, IRequestFluid, IFluidContainerReceiver, ICraftFluids {

    private static final int INGREDIENT_SLOTS = 9;
    /** Result slots, each with its own {@link OutputRole}. They follow the ingredient slots in the inventory. */
    public static final int OUTPUT_SLOTS = 3;
    private static final int INVENTORY_SIZE = INGREDIENT_SLOTS + OUTPUT_SLOTS;
    /** Chance values are whole percent. */
    private static final int GUARANTEED = 100;
    /** Upper limit on sets released but not yet finished, so a huge inventory can't make the room check loop long. */
    private static final int MAX_SETS_IN_FLIGHT = 128;
    /** Slow re-check for results the module never saw (taken by a player, auto-output, failed chanced craft). */
    private static final int SAFETY_NET_TICKS = 100;
    /** How often a held claim is refreshed. Must be well below {@link MachineClaims#CLAIM_TIMEOUT_TICKS}. */
    private static final int CLAIM_REFRESH_TICKS = 20;
    /**
     * Released sets are given up after this long with no progress, once everything was delivered and none of the
     * ingredients are left in the machine. Covers results that never come back to this module.
     */
    private static final int STUCK_TICKS = 400;
    /**
     * How long to leave a machine alone while waiting for an ingredient another crafter has to make, before assuming
     * the guess was wrong and going ahead.
     */
    private static final int INTERMEDIATE_WAIT_TICKS = 200;
    /** Upper limit on stacks pulled out in one sweep, so a huge leftover pile can't flood the network at once. */
    private static final int MAX_SWEEP_STACKS = 64;
    /** How often results no order is waiting for are cleared out of the machine. */
    private static final int UNORDERED_OUTPUT_TICKS = 20;

    /** Items per ingredient slot that providers may still send for the released sets. */
    private final int[] allowance = new int[INGREDIENT_SLOTS];
    /** Items per ingredient slot that were sent but haven't arrived yet. */
    private final int[] inFlight = new int[INGREDIENT_SLOTS];
    private int setsReleased = 0;
    /** Results pulled out since the gate was last reset, per output slot. */
    private final int[] extractedPerOutput = new int[OUTPUT_SLOTS];
    /** Sets already taken off {@link #setsReleased} by {@link #onResultExtracted}, so none is counted twice. */
    private int creditedSets = 0;
    private boolean gateDirty = true; // run the load sweep on the first tick
    private long lastProgressTick = 0;

    /** Ticks since the last "no fluid sink" warning, so a stuck output doesn't spam the player every tick. */
    private long lastNoFluidSinkWarn = 0;
    private static final long FLUID_SINK_WARN_COOLDOWN = 400L; // ~20 s between repeat warnings

    /** The machine this module currently holds the claim for, or null. */
    private MachineClaims.Key claimedMachine = null;
    /** The machine this module is waiting to claim, or null. */
    private MachineClaims.Key awaitedMachine = null;
    /** Whether sets were released since the claim was taken, so a new owner always gets at least one turn. */
    private boolean releasedThisTurn = false;
    private long claimTakenTick = 0;

    /** Sweep leftovers once the module is loaded: orders aren't saved, so anything of this recipe there is stale. */
    private boolean pendingLoadSweep = true;
    /** Whether this crafter had orders last time the gate was updated, to notice when its last order finishes. */
    private boolean hadOrders = false;
    /** Sweep leftovers before releasing the first sets after taking a machine. */
    private boolean sweepBeforeRelease = false;
    /** Sets asked for through the gui's "Request set" button, which have no order behind them. */
    private int pendingManualSets = 0;

    /**
     * Litres per slot, ingredients and results alike, in the unit GT recipes and NEI use. Above zero means the slot
     * holds a fluid rather than an item, which is the only thing that tells the two apart: a filled cell is a real item
     * a recipe may want as one.
     */
    private final int[] fluidAmount = new int[INVENTORY_SIZE];
    /** Used when a fluid is dropped in without an amount of its own. One bucket, as GT recipes are written. */
    private static final int DEFAULT_FLUID_AMOUNT = 1000;
    /** Master switch for the built-in sweeps. Off leaves whatever is in the machine alone. */
    private boolean cleanupEnabled = true;

    /** True when not even one recipe set fits in an empty machine, so the gate can never open. */
    @Getter
    private boolean setTooLarge = false;

    /**
     * What an output slot means for planning. All three are pulled out of the machine; they differ in what the planner
     * may do with them.
     */
    public enum OutputRole {

        /** The module advertises itself as a way to craft this item ({@link #canCraft}). */
        PRODUCT,
        /** Made on the side. Never craftable, never promised; goes to storage. */
        BYPRODUCT,
        /**
         * Made on the side, and registered as an extra once the craft runs, so a <b>later</b> request can spend it
         * instead of sourcing that item elsewhere. It cannot help the request that produced it:
         * {@code RequestTreeNode.checkForExtras} walks the tree's {@code extrapromises}, never its {@code byproducts},
         * which are only handed to {@code registerExtras} in {@code fullFill}, after planning is done.
         */
        BYPRODUCT_COUNTED;

        public OutputRole next(boolean allowCounted) {
            switch (this) {
                case PRODUCT:
                    return BYPRODUCT;
                case BYPRODUCT:
                    return allowCounted ? BYPRODUCT_COUNTED : PRODUCT;
                default:
                    return PRODUCT;
            }
        }
    }

    private final OutputRole[] outputRole = new OutputRole[OUTPUT_SLOTS];
    /** Percent chance of getting this output from one set. {@link #GUARANTEED} means every set yields it. */
    private final int[] outputChance = new int[OUTPUT_SLOTS];
    /**
     * Which satellite an output comes out of, for a recipe whose results appear somewhere other than the block this
     * module faces (a multiblock's output bus). <b>Stored and edited, but nothing reads it yet:</b> extracting through
     * a satellite needs the Smart Satellite, which doesn't exist. Until then every output is taken from this module's
     * own machine, whatever is set here.
     */
    private final int[] outputSatelliteId = new int[OUTPUT_SLOTS];

    public ModuleSmartCrafter() {
        _dummyInventory = new ItemIdentifierInventory(
                INVENTORY_SIZE,
                StringUtils.translate("gui.module.requestedItems"),
                127);
        // Needed to catch a fluid dropped into an ingredient slot; the base only registers this for the crafting pipe.
        _dummyInventory.addListener(this);
        for (int i = 0; i < OUTPUT_SLOTS; i++) {
            outputRole[i] = i == 0 ? OutputRole.PRODUCT : OutputRole.BYPRODUCT;
            outputChance[i] = GUARANTEED;
        }
    }

    /* Buffers */

    /** Every loaded Smart Crafter, so a buffer can ask what is worth holding without walking the routing table. */
    public static final Set<ModuleSmartCrafter> AllCrafters = new HashSet<>();

    /** Called on server shutdown, as the satellite registry is. */
    public static void cleanupCrafters() {
        ModuleSmartCrafter.AllCrafters.clear();
    }

    /**
     * A job was cancelled: every Smart Crafter that held one of its orders, or was the destination of one, updates its
     * gate on its next tick instead of at the next safety-net check, so it stops and cleans up straight away.
     */
    public static void wakeAfterCancel(Set<LogisticsOrderManager<?, ?>> managers, Set<IRouter> destinations) {
        for (ModuleSmartCrafter crafter : ModuleSmartCrafter.AllCrafters) {
            if (crafter._service == null) {
                continue;
            }
            if (managers.contains(crafter._service.getItemOrderManager())
                    || (crafter.fluidOrders != null && managers.contains(crafter.fluidOrders))
                    || destinations.contains(crafter._service.getRouter())) {
                crafter.gateDirty = true;
            }
        }
    }

    /**
     * The ingredients this module would take from a buffer: its gated item slots, i.e. the ones delivered to its own
     * machine. Fluids and satellite-routed slots go their own way and never sit in a buffer.
     */
    public Set<ItemIdentifier> getIntermediates() {
        Set<ItemIdentifier> items = new HashSet<>();
        for (int slot = 0; slot < INGREDIENT_SLOTS; slot++) {
            if (isGatedSlot(slot)) {
                items.add(getMaterials(slot).getItem());
            }
        }
        return items;
    }

    /**
     * A buffer has taken in something; look again next tick if this module uses it. The gate still decides whether
     * anything can be done about it - this only saves waiting for the safety net to notice.
     */
    public void onBufferStockArrived(ItemIdentifier item) {
        if (gateDirty) {
            return;
        }
        for (int slot = 0; slot < INGREDIENT_SLOTS; slot++) {
            if (isGatedSlot(slot) && getMaterials(slot).getItem().equals(item)) {
                gateDirty = true;
                return;
            }
        }
    }

    /**
     * Sends a result to a buffer when the crafter that ordered it can't take it yet, and holds it in the machine when
     * no buffer will have it. Pushing it at a machine that isn't ready is what makes items bounce, and bouncing is what
     * this module exists to stop.
     */
    @Override
    protected ResultRoute routeForResult(LogisticsItemOrder order) {
        ResultRoute route = chooseResultRoute(order);
        resultHeld = route == ResultRoute.HOLD;
        LogisticsPipes.log.info(
                "[Buffer DEBUG] result " + order.getResource()
                        .getItem() + " -> " + route + " (buffers=" + ModuleCraftingBuffer.AllBuffers.size() + ")");
        return route;
    }

    /**
     * A result is going to a buffer for the crafter that ordered it: give that crafter credit for it, so it takes this
     * stock from the buffer and nothing else.
     */
    @Override
    protected void onResultBuffered(LogisticsItemOrder order, int amount) {
        if (order.getDestination() == null) {
            return;
        }
        IGatedItemSink target = IGatedItemSink.findTarget(order.getDestination().getRouter(), order.getInformation());
        if (target instanceof ModuleSmartCrafter) {
            ((ModuleSmartCrafter) target).creditBuffered(order.getInformation(), amount);
        }
    }

    private ResultRoute chooseResultRoute(LogisticsItemOrder order) {
        if (order.getDestination() == null || destinationCanTake(order)) {
            return ResultRoute.TO_ORDER;
        }
        ItemIdentifier result = order.getResource().getItem();
        return bufferWithRoomFor(result) != null ? ResultRoute.TO_BUFFER : ResultRoute.HOLD;
    }

    /** Whether the crafter this result is for has its gate open. Anything that isn't gated is assumed ready. */
    private boolean destinationCanTake(LogisticsItemOrder order) {
        IRouter router = order.getDestination().getRouter();
        if (router == null) {
            return true;
        }
        IGatedItemSink gate = IGatedItemSink.findTarget(router, order.getInformation());
        return gate == null || gate.getGatedAllowance(order.getResource().getItem(), order.getInformation()) > 0;
    }

    private ModuleCraftingBuffer bufferWithRoomFor(ItemIdentifier item) {
        for (ModuleCraftingBuffer buffer : ModuleCraftingBuffer.AllBuffers) {
            if (buffer.hasRoomFor(item, 1)) {
                return buffer;
            }
        }
        return null;
    }

    /**
     * Per ingredient slot, how much sub-crafters have sent to buffers for this module and it hasn't pulled yet.
     * <p>
     * This module only ever takes that much from the buffers. Taking any matching stock (an orphan left by a cancelled
     * job, say) looked harmless because intermediates are fungible, but a request that planned the item from storage
     * also has a provider order for it: the buffer stock used up the gate's allowance, the provider was never allowed
     * to send, and its order stayed open for ever. Kept in memory only, like the orders it stands for.
     */
    private final int[] buffered = new int[INGREDIENT_SLOTS];

    /** A sub-crafter sent a result meant for this module to a buffer, because this module's gate was shut. */
    public void creditBuffered(IAdditionalTargetInformation info, int amount) {
        int slot = ingredientSlot(info);
        if (slot < 0 || amount <= 0) {
            return;
        }
        buffered[slot] += amount;
        gateDirty = true;
    }

    /** What this module may take from the buffers for a slot right now: its credit, as far as the stock goes. */
    private int bufferedFor(int slot) {
        if (buffered[slot] <= 0) {
            return 0;
        }
        return Math.min(buffered[slot], bufferStock(getMaterials(slot).getItem()));
    }

    /** How much of an item this module has credit for across its slots, for a buffer working out its orphans. */
    public int getBufferedCredit(ItemIdentifier item) {
        int total = 0;
        for (int slot = 0; slot < INGREDIENT_SLOTS; slot++) {
            if (buffered[slot] > 0 && getMaterials(slot) != null && getMaterials(slot).getItem().equals(item)) {
                total += buffered[slot];
            }
        }
        return total;
    }

    /**
     * Drops this module's buffer credit, when it has nothing left to craft or is removed. What it no longer has credit
     * for is an orphan, so the buffers are told to return it now instead of holding it until something else happens.
     */
    private void clearBufferedCredit() {
        boolean had = false;
        for (int slot = 0; slot < INGREDIENT_SLOTS; slot++) {
            had |= buffered[slot] > 0;
            buffered[slot] = 0;
        }
        if (had) {
            ModuleCraftingBuffer.returnAllOrphans();
        }
    }

    /**
     * Takes what the buffers are holding for this set, up to this module's credit. Buffered intermediates have no
     * provider to ask, so unlike raw materials they have to be pulled: the gate opening alone would never move them.
     */
    private void pullFromBuffers(int slot, int wanted) {
        wanted = Math.min(wanted, buffered[slot]);
        if (wanted <= 0 || ModuleCraftingBuffer.AllBuffers.isEmpty()) {
            return;
        }
        ItemIdentifier item = getMaterials(slot).getItem();
        int left = wanted;
        for (ModuleCraftingBuffer buffer : ModuleCraftingBuffer.AllBuffers) {
            if (left <= 0) {
                break;
            }
            LogisticsPipes.log.info(
                    "[Buffer DEBUG] pull slot " + slot
                            + " want "
                            + left
                            + " of "
                            + item
                            + ", buffer holds "
                            + buffer.getAvailable(item));
            int sent = buffer.sendTo(
                    item,
                    left,
                    getRouter().getSimpleID(),
                    new CraftingChassieInformation(slot, getPositionInt()));
            LogisticsPipes.log.info("[Buffer DEBUG] pull sent " + sent);
            if (sent > 0) {
                left -= sent;
                buffered[slot] = Math.max(0, buffered[slot] - sent);
                allowance[slot] = Math.max(0, allowance[slot] - sent);
                inFlight[slot] += sent;
                lastProgressTick = now();
            }
        }
    }

    /* Fluid ingredients */

    /**
     * Takes fluid addressed to this module and pours it into the machine it faces, so a fluid ingredient needs no
     * satellite. Possible because LP moves fluid as an ordinary routed item holding a container: only the receiving end
     * is particular to fluid pipes, and this is that end.
     *
     * @return true if this module wanted the fluid. Whatever the machine couldn't take is sent back into the network,
     *         where LP routes a stray container to the nearest fluid sink.
     */
    @Override
    public boolean receiveFluidContainer(FluidStack fluid) {
        if (fluid == null || fluid.amount <= 0 || !wantsFluid(fluid)) {
            return false;
        }
        IFluidHandler tank = facedTank();
        int filled = tank == null ? 0 : tank.fill(insertionSide(), fluid, true);
        if (filled < fluid.amount) {
            FluidStack rejected = fluid.copy();
            rejected.amount = fluid.amount - filled;
            sendFluidBack(rejected);
        }
        lastProgressTick = now();
        return true;
    }

    /** Whether a fluid slot asks for this fluid and has no satellite, i.e. it is delivered to this module's machine. */
    @Override
    public boolean wantsFluid(FluidStack fluid) {
        FluidIdentifier arriving = FluidIdentifier.get(fluid);
        if (arriving == null) {
            return false;
        }
        for (int slot = 0; slot < INGREDIENT_SLOTS; slot++) {
            if (isFluidSlot(slot) && advancedSatelliteIdArray[slot] == 0 && arriving.equals(getFluidIngredient(slot))) {
                return true;
            }
        }
        return false;
    }

    /** Puts fluid back on the network with no destination, which sends it to the nearest fluid sink. */
    private void sendFluidBack(FluidStack fluid) {
        sendFluidBack(fluid, null);
    }

    /**
     * Puts a leftover container back on the network with no destination, so it ends in the nearest fluid sink. When
     * {@code sat} is set the container enters through that satellite — out of the face its machine is on, jamming the
     * satellite so it cannot loop back into the same machine; otherwise it goes up from the chassis as before.
     */
    private void sendFluidBack(FluidStack fluid, PipeSmartSatellite sat) {
        ItemIdentifierStack container = SimpleServiceLocator.logisticsFluidManager.getFluidContainer(fluid);
        if (container == null) {
            return;
        }
        IRoutedItem item = SimpleServiceLocator.routedItemHelper.createNewTravelItem(container.makeNormalStack());
        item.setDestination(-1);
        item.setTransportMode(TransportMode.Active);
        ForgeDirection out = sat == null ? null : sat.getMachineFacing();
        if (out != null) {
            item.getJamList().add(sat.getRouter().getSimpleID());
            sat.queueRoutedItem(item, out);
        } else {
            _service.queueRoutedItem(item, ForgeDirection.UP);
        }
    }

    /* Crafting a fluid */

    /**
     * Nothing is held here: this module makes fluid to order rather than storing it, and {@code LogisticsFluidManager}
     * only asks pipes anyway, never a module.
     */
    @Override
    public Map<FluidIdentifier, Integer> getAvailableFluids() {
        return Collections.emptyMap();
    }

    /**
     * Orders for fluid this module crafts. A module can own one of these: the manager only wants a position, not a
     * fluid pipe, which is why a crafting module can answer for a fluid at all.
     */
    private LogisticsFluidOrderManager fluidOrders = null;

    private LogisticsFluidOrderManager getFluidOrders() {
        if (fluidOrders == null) {
            fluidOrders = new LogisticsFluidOrderManager(
                    _service instanceof ILPPositionProvider ? (ILPPositionProvider) _service : null);
        }
        return fluidOrders;
    }

    /**
     * Fluid this module still has to craft, as display stacks. Kept apart from the pipe's item orders, so anything
     * listing a pipe's crafting orders has to ask for these separately.
     */
    public List<ItemIdentifierStack> getFluidCraftingContent() {
        List<ItemIdentifierStack> list = new ArrayList<>();
        if (fluidOrders == null) {
            return list;
        }
        for (LogisticsFluidOrder order : fluidOrders) {
            if (order.getType() == ResourceType.CRAFTING) {
                list.add(order.getAsDisplayItem().clone());
            }
        }
        return list;
    }

    @Override
    public IOrderInfoProvider fullFill(FluidLogisticsPromise promise, IRequestFluid destination, ResourceType type,
            IAdditionalTargetInformation info) {
        LogisticsFluidOrder order = getFluidOrders().addOrder(promise, destination, type, info);
        gateDirty = true; // the ingredients for it still have to be pulled in
        return order;
    }

    /**
     * Sends crafted fluid to whoever ordered it, mirroring {@code PipeFluidProvider}: drain the machine, wrap what came
     * out in a container and route it to the order's router (the requester), Active.
     * <p>
     * For an output addressed to a satellite the fluid is drained from, and routed out through, that satellite's machine
     * instead of the block this module faces. There is deliberately <b>no</b> {@code getBestReply} gate here: that check
     * only sees {@code IFluidSink} storage, but a crafted fluid's destination is the order's requester — often another
     * crafter's machine, which is not a sink — so the gate would hold fluid that has a perfectly good consumer (and
     * {@code PipeFluidProvider} does exactly this: it sends to the order's router without a sink check). The requester is
     * a real destination that decides for itself what to do with any overflow; the backpressure gate lives on the
     * leftover that {@code sendRemainingFluid} default-routes to storage.
     */
    private void sendCraftedFluid() {
        if (fluidOrders == null || !getFluidOrders().hasOrders(ResourceType.CRAFTING)) {
            return;
        }
        LogisticsFluidOrder order = getFluidOrders().peekAtTopRequest(ResourceType.CRAFTING);

        PipeSmartSatellite sat = satelliteForOutput(order.getFluid());
        IFluidHandler satTank = null;
        ForgeDirection satFacing = null;
        if (sat != null) {
            ForgeDirection facing = sat.getMachineFacing();
            if (facing != null && sat.getMachineTile() instanceof IFluidHandler) {
                satFacing = facing;
                satTank = (IFluidHandler) sat.getMachineTile();
            }
        }
        boolean viaSatellite = satTank != null;
        IFluidHandler tank = viaSatellite ? satTank : facedTank();
        if (tank == null) {
            return;
        }
        ForgeDirection drainSide = viaSatellite ? satFacing.getOpposite() : insertionSide();

        // Send straight to the requester, Active, exactly as PipeFluidProvider does for a tank: the order's router is a
        // real destination (a crafter's machine, a provider, a tank) that decides for itself what to do with overflow,
        // so no "is there a sink" gate here. Drains only what is still owed, capped per send like the provider.
        int wanted = Math.min(order.getAmount(), Configs.MAX_LOGISTICS_FLUID_TRANSPORT_INNER_CAPACITY / 2);
        FluidStack drained = drainFromTank(tank, order.getFluid(), wanted, drainSide);
        if (drained == null || drained.amount <= 0) {
            return;
        }
        ItemIdentifierStack container = SimpleServiceLocator.logisticsFluidManager.getFluidContainer(drained);
        IRoutedItem item = SimpleServiceLocator.routedItemHelper.createNewTravelItem(container);
        item.setDestination(order.getRouter().getSimpleID());
        item.setTransportMode(TransportMode.Active);
        if (viaSatellite) {
            // Enter the network through the satellite, out of the face its machine is on, like the item outputs do.
            sat.queueRoutedItem(item, satFacing);
        } else {
            _service.queueRoutedItem(item, _service.inventoryOrientation());
        }
        getFluidOrders().sendSuccessfull(drained.amount, false, item);
        onFluidResultSent(drained);
        if (outstandingFluid(order.getFluid()) <= 0) {
            // That was the last of what was ordered, so anything still in the machine is left over from a set that
            // made more than the request needed. Send it on now, while this module is the one acting on the machine.
            sendRemainingFluid(tank, order.getFluid(), drainSide, viaSatellite ? sat : null);
        }
    }

    /** The Smart Satellite a fluid output is addressed to, or null when the output goes to the block this module faces. */
    private PipeSmartSatellite satelliteForOutput(FluidIdentifier fluid) {
        int out = outputSlotForFluid(fluid);
        if (out < 0 || outputSatelliteId[out] == 0) {
            return null;
        }
        return smartSatelliteForId(outputSatelliteId[out]);
    }

    /** The output slot that produces {@code fluid}, or -1 if none of this recipe's fluid outputs match. */
    private int outputSlotForFluid(FluidIdentifier fluid) {
        if (fluid == null) {
            return -1;
        }
        for (int out = 0; out < OUTPUT_SLOTS; out++) {
            int slot = outputInventorySlot(out);
            if (isFluidSlot(slot) && fluid.equals(getFluidIngredient(slot))) {
                return out;
            }
        }
        return -1;
    }

    /**
     * Rate-limited notice that a crafted fluid has nowhere to go. Sent to players near the module and to the server
     * log so the player sees it while testing; the machine keeps holding meanwhile.
     */
    private void warnNoFluidSink(FluidIdentifier fluid) {
        long t = now();
        if (t - lastNoFluidSinkWarn < FLUID_SINK_WARN_COOLDOWN) {
            return;
        }
        lastNoFluidSinkWarn = t;
        String name = fluid == null ? "a fluid" : fluid.toString();
        String msg = "Smart Satellite: no fluid sink can take " + name
                + " to drain this output, so it is held in the machine.";
        World world = getWorld();
        if (world != null) {
            for (EntityPlayer player : world.playerEntities) {
                if (player.getDistanceSq(getX() + 0.5, getY() + 0.5, getZ() + 0.5) < 32 * 32) {
                    player.addChatComponentMessage(new ChatComponentText("§e" + msg));
                }
            }
        }
        if (LogisticsPipes.log != null) {
            LogisticsPipes.log.warn("[LogisticsPipes] " + msg + " at " + getX() + ", " + getY() + ", " + getZ());
        }
    }

    /**
     * Pushes what the machine still holds of a finished result into the network, where it ends up in the nearest fluid
     * sink. Otherwise a crafter making more per set than was asked for slowly fills its own tank and stops.
     * <p>
     * Like {@link #sendCraftedFluid()} it drains nothing unless a reachable sink has room ({@code getBestReply}), so a
     * leftover that has nowhere to go is held in the machine rather than voided, and the player is warned at a rate
     * limit. When the output is a satellite's the leftover goes out through that satellite, {@code sat}, instead of
     * the block this module faces.
     */
    private void sendRemainingFluid(IFluidHandler tank, FluidIdentifier fluid, ForgeDirection drainSide,
            PipeSmartSatellite sat) {
        int left = heldInTank(tank, fluid, drainSide);
        if (left <= 0) {
            return;
        }
        IRouter senderRouter = sat != null ? sat.getRouter() : getRouter();
        // Probe with the whole leftover, not 1 mB: PipeFluidBasic.sinkAmount caps its answer at the stack's amount, so a
        // 1-mB probe reports it can take only 1 and would strand the rest of the set's excess in the machine.
        Pair<Integer, Integer> reply = SimpleServiceLocator.logisticsFluidManager
                .getBestReply(fluid.makeFluidStack(left), senderRouter, new ArrayList<Integer>());
        if (reply.getValue1() == 0) {
            warnNoFluidSink(fluid);
            return;
        }
        int wanted = Math.min(left, reply.getValue2());
        FluidStack drained = drainFromTank(tank, fluid, wanted, drainSide);
        if (drained != null && drained.amount > 0) {
            sendFluidBack(drained, sat);
        }
    }

    /** Whether this module has a job of its own, of either kind. */
    /**
     * Deliberately iterates rather than calling {@code hasOrders}: that goes through {@code peekAtTopRequest}, which
     * marks an order in progress and rotates the queue looking for a matching type. Harmless on this module's own tick,
     * but this is also reached from a buffer deciding whether to accept an item, i.e. from the routing path of a
     * completely different pipe, where quietly reordering someone's order queue is the last thing we want.
     */
    private boolean hasWork() {
        for (LogisticsItemOrder order : _service.getItemOrderManager()) {
            if (order.getType() == ResourceType.CRAFTING || order.getType() == ResourceType.EXTRA) {
                return true;
            }
        }
        if (fluidOrders != null) {
            for (LogisticsFluidOrder order : getFluidOrders()) {
                if (order.getType() == ResourceType.CRAFTING || order.getType() == ResourceType.EXTRA) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Drains from {@code side} if the machine allows it, else without a side. GT restricts which faces a fluid may be
     * pulled from, much as it does for item slots, so the face we insert through often isn't one of them.
     */
    private FluidStack drainFromTank(IFluidHandler tank, FluidIdentifier fluid, int amount, ForgeDirection side) {
        FluidStack wanted = fluid.makeFluidStack(amount);
        FluidStack drained = tank.drain(side, wanted, true);
        if (drained == null || drained.amount <= 0) {
            drained = tank.drain(ForgeDirection.UNKNOWN, wanted, true);
        }
        return drained;
    }

    private int heldInTank(IFluidHandler tank, FluidIdentifier fluid, ForgeDirection side) {
        FluidTankInfo[] tanks = tank.getTankInfo(side);
        if (tanks == null || tanks.length == 0) {
            tanks = tank.getTankInfo(ForgeDirection.UNKNOWN);
        }
        if (tanks == null) {
            return 0;
        }
        int held = 0;
        for (FluidTankInfo info : tanks) {
            if (info != null && info.fluid != null && fluid.equals(FluidIdentifier.get(info.fluid))) {
                held += info.fluid.amount;
            }
        }
        return held;
    }

    /** A fluid result leaving counts as set progress, the same as an item result being extracted. */
    private void onFluidResultSent(FluidStack sent) {
        FluidIdentifier fluid = FluidIdentifier.get(sent);
        for (int out = 0; out < OUTPUT_SLOTS; out++) {
            int slot = outputInventorySlot(out);
            if (isFluidSlot(slot) && fluid != null && fluid.equals(getFluidIngredient(slot))) {
                creditSets(out, sent.amount);
                return;
            }
        }
    }

    @Override
    public void sendFailed(FluidIdentifier value1, Integer value2) {
        // A delivery that never arrived. Nothing is reserved for fluids, so the next gate update simply asks again.
        gateDirty = true;
    }

    /** Whether this ingredient slot holds a fluid. Fluids are delivered to a fluid satellite, never to the chassis. */
    public boolean isFluidSlot(int slot) {
        return fluidAmount[slot] > 0 && getFluidIngredient(slot) != null;
    }

    public FluidIdentifier getFluidIngredient(int slot) {
        ItemIdentifierStack stack = getMaterials(slot);
        return stack == null ? null : FluidIdentifier.get(stack.getItem());
    }

    public int getFluidAmount(int slot) {
        return fluidAmount[slot];
    }

    public void setFluidAmount(int slot, int litres) {
        fluidAmount[slot] = Math.max(0, litres);
        gateDirty = true;
    }

    /**
     * Turns a dropped fluid display stack into the fluid it stands for. A real item, a filled cell included, is left as
     * an item: plenty of GT recipes take a cell as an ingredient, and second-guessing that would make those
     * unconfigurable.
     */
    @Override
    public void InventoryChanged(IInventory inventory) {
        super.InventoryChanged(inventory);
        if (inventory != _dummyInventory || _world == null || !MainProxy.isServer(getWorld())) {
            return;
        }
        refreshFluidSlots();
    }

    /**
     * Re-reads which slots hold a fluid. Needed after a load as well as after an edit: the listener only fires on a
     * change, so a module restored from NBT would never mark its fluid slots, leaving a fluid result looking like an
     * ordinary item and keeping it out of the interests a crafter is found by.
     */
    private void refreshFluidSlots() {
        if (normalising) {
            return;
        }
        normalising = true; // rewriting a slot below calls back in here
        try {
            normaliseFluidSlots();
        } finally {
            normalising = false;
        }
    }

    private boolean normalising = false;

    private void normaliseFluidSlots() {
        for (int slot = 0; slot < INVENTORY_SIZE; slot++) {
            ItemIdentifierStack stack = getMaterials(slot);
            if (stack == null) {
                fluidAmount[slot] = 0;
                continue;
            }
            FluidStack dropped = FluidDisplayUtil.getDisplayedFluid(stack.getItem().unsafeMakeNormalStack(1));
            if (dropped == null) {
                // A plain item. Any amount left over from a fluid that used to be here no longer applies.
                fluidAmount[slot] = 0;
                continue;
            }
            FluidIdentifier fluid = FluidIdentifier.get(dropped);
            if (fluid == null) {
                fluidAmount[slot] = 0;
                continue;
            }
            // Store it the way LP represents a fluid, so the slot renders and saves like any other.
            _dummyInventory.setInventorySlotContents(slot, fluid.getItemIdentifier().unsafeMakeNormalStack(1));
            if (fluidAmount[slot] <= 0) {
                fluidAmount[slot] = dropped.amount > 0 ? dropped.amount : DEFAULT_FLUID_AMOUNT;
            }
        }
    }

    @Override
    protected boolean addIngredientForSlot(IReqCraftingTemplate template, int slot) {
        if (!isFluidSlot(slot)) {
            return false;
        }
        // With no satellite set the fluid comes to this module, which pours it into the machine it faces.
        IRequestFluid target = advancedSatelliteIdArray[slot] == 0 ? this : fluidSatelliteFor(slot);
        if (target != null) {
            template.addIngredient(new FluidResource(getFluidIngredient(slot), fluidAmount[slot], target), null);
        }
        // Either way the item path must not also add it. An unreachable satellite is caught by isSatelliteConnected,
        // which stops the template being offered at all, rather than quietly crafting without the fluid.
        return true;
    }

    /**
     * The item satellite an ingredient slot is routed to, or null if its id is unset or can't be reached.
     * <p>
     * A normal item satellite is preferred; otherwise a Smart Satellite is matched too, so a plain (non-fluid)
     * ingredient can be addressed to it the same way as to a normal item satellite. The Smart Satellite is a fluid
     * type, but it carries a plain item to the machine it faces (see {@code PipeSmartSatellite#endReached}, which only
     * handles fluid containers and lets everything else fall through to the machine inventory).
     */
    @Override
    public IRouter getSatelliteRouter(int x) {
        IRouter router = super.getSatelliteRouter(x);
        if (router != null) {
            return router;
        }
        int id = x == -1 ? satelliteId : advancedSatelliteIdArray[x];
        if (id == 0) {
            return null;
        }
        for (final PipeSmartSatellite satellite : PipeSmartSatellite.AllSatellites) {
            if (satellite.satelliteId == id && !satellite.stillNeedReplace() && satellite.getRouter() != null) {
                return satellite.getRouter();
            }
        }
        return null;
    }

    /**
     * The fluid satellite an ingredient slot is routed to, or null if its id is unset or can't be reached. A normal
     * fluid satellite is preferred; otherwise a Smart Satellite is matched too, so a fluid ingredient can be addressed
     * to it the same way. The Smart Satellite pours the fluid into the machine it faces via the shared
     * {@code FluidRoutedPipe} behaviour, exactly like a fluid satellite.
     */
    private IRequestFluid fluidSatelliteFor(int slot) {
        int id = advancedSatelliteIdArray[slot];
        if (id == 0) {
            return null;
        }
        for (PipeFluidSatellite satellite : PipeFluidSatellite.AllSatellites) {
            if (satellite.satelliteId != id || satellite.stillNeedReplace() || satellite.getRouter() == null) {
                continue;
            }
            if (isReachable(satellite.getRouter())) {
                return satellite;
            }
        }
        for (PipeSmartSatellite satellite : PipeSmartSatellite.AllSatellites) {
            if (satellite.satelliteId != id || satellite.stillNeedReplace() || satellite.getRouter() == null) {
                continue;
            }
            if (isReachable(satellite.getRouter())) {
                return satellite;
            }
        }
        return null;
    }

    private boolean isReachable(IRouter router) {
        for (ExitRoute route : getRouter().getIRoutersByCost()) {
            if (route.destination == router) {
                return true;
            }
        }
        return false;
    }

    /**
     * A fluid can't be delivered to the chassis, so every fluid slot needs a satellite that can be reached. Without one
     * the module stops offering the recipe, instead of crafting without the fluid.
     */
    @Override
    public boolean isSatelliteConnected() {
        for (int slot = 0; slot < INGREDIENT_SLOTS; slot++) {
            if (getMaterials(slot) == null) {
                continue;
            }
            if (isFluidSlot(slot)) {
                // No id means this module takes the fluid itself, so there is nothing to reach.
                if (advancedSatelliteIdArray[slot] != 0 && fluidSatelliteFor(slot) == null) {
                    return false;
                }
            } else if (advancedSatelliteIdArray[slot] != 0) {
                IRouter router = getSatelliteRouter(slot);
                if (router == null || !isReachable(router)) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * The first fluid slot that has nowhere to go: either it names a satellite that can't be reached, or it names none
     * and the block this module faces holds no fluid. -1 when every fluid slot is fine. Drives the gui warning.
     */
    public int fluidSlotWithNowhereToGo() {
        boolean facesTank = facedTank() != null;
        for (int slot = 0; slot < INGREDIENT_SLOTS; slot++) {
            if (!isFluidSlot(slot)) {
                continue;
            }
            if (advancedSatelliteIdArray[slot] == 0 ? !facesTank : fluidSatelliteFor(slot) == null) {
                return slot;
            }
        }
        return -1;
    }

    /* Outputs */

    public static int outputInventorySlot(int output) {
        return INGREDIENT_SLOTS + output;
    }

    public ItemIdentifierStack getOutput(int output) {
        return _dummyInventory.getIDStackInSlot(outputInventorySlot(output));
    }

    public OutputRole getOutputRole(int output) {
        return outputRole[output];
    }

    public void setOutputRole(int output, OutputRole role) {
        outputRole[output] = role == OutputRole.BYPRODUCT_COUNTED && isChanced(output) ? OutputRole.BYPRODUCT : role;
    }

    public int getOutputChance(int output) {
        return outputChance[output];
    }

    public void setOutputChance(int output, int chance) {
        outputChance[output] = Math.max(1, Math.min(GUARANTEED, chance));
        // A chanced output may not be promised: the set it was planned into can produce nothing.
        if (isChanced(output) && outputRole[output] == OutputRole.BYPRODUCT_COUNTED) {
            outputRole[output] = OutputRole.BYPRODUCT;
        }
    }

    public boolean isChanced(int output) {
        return outputChance[output] < GUARANTEED;
    }

    /** See {@link #outputSatelliteId}: read by {@link #resultSourcesFor} to pull this output from a satellite's machine. */
    public int getOutputSatelliteId(int output) {
        return outputSatelliteId[output];
    }

    public void setOutputSatelliteId(int output, int satelliteId) {
        outputSatelliteId[output] = Math.max(0, satelliteId);
    }

    /**
     * Which machine the extraction loop pulls a finished result from. For an output addressed to a satellite, the result
     * comes out of the machine that satellite faces and enters the network through the satellite; otherwise it comes
     * from the machines the chassis faces, as always. The order and set accounting (see {@link #onResultExtracted})
     * stays in the crafter either way.
     */
    @Override
    protected List<AdjacentTile> resultSourcesFor(LogisticsItemOrder order, List<AdjacentTile> faced) {
        int out = outputIndexFor(order.getResource().getItem());
        if (out < 0 || outputSatelliteId[out] == 0) {
            return faced;
        }
        PipeSmartSatellite sat = smartSatelliteForId(outputSatelliteId[out]);
        if (sat == null) {
            return faced;
        }
        ForgeDirection facing = sat.getMachineFacing();
        if (facing == null) {
            return faced;
        }
        TileEntity machine = sat.getMachineTile();
        if (machine == null) {
            return faced;
        }
        AdjacentTile source = new AdjacentTile(machine, facing);
        // The result enters the network through the satellite, out of the face its machine is on.
        source.sender = sat;
        return Collections.singletonList(source);
    }

    /** The Smart Satellite whose id an output is addressed to, or null if it isn't placed and reachable yet. */
    private PipeSmartSatellite smartSatelliteForId(int id) {
        for (PipeSmartSatellite satellite : PipeSmartSatellite.AllSatellites) {
            if (satellite.satelliteId == id && !satellite.stillNeedReplace() && satellite.getRouter() != null
                    && isReachable(satellite.getRouter())) {
                return satellite;
            }
        }
        return null;
    }

    /** The output whose arrivals count finished sets. */
    private int primaryOutput() {
        for (int i = 0; i < OUTPUT_SLOTS; i++) {
            if (outputRole[i] == OutputRole.PRODUCT && getOutput(i) != null && !isFluidSlot(outputInventorySlot(i))) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Adds the fluid results to what the base declares for its item ones.
     * <p>
     * This is what makes a fluid result findable at all: {@code RequestTreeNode.checkCrafting} only asks routers that
     * declared interest, and {@code ServerRouter.getRoutersInterestedIn} keys a {@code FluidResource} by
     * {@code getFluid().getItemIdentifier()} - LP's container item for that fluid. Without this the recipe is craftable
     * in principle and invisible in practice.
     */
    @Override
    public Set<ItemIdentifier> getSpecificInterests() {
        Set<ItemIdentifier> interests = super.getSpecificInterests();
        if (interests == null) {
            interests = new TreeSet<>();
        }
        StringBuilder debug = new StringBuilder();
        for (int out = 0; out < OUTPUT_SLOTS; out++) {
            int slot = outputInventorySlot(out);
            debug.append(" [").append(out).append(" role=").append(outputRole[out]).append(" item=")
                    .append(getOutput(out)).append(" isFluid=").append(isFluidSlot(slot)).append(" litres=")
                    .append(fluidAmount[slot]).append(" fluid=")
                    .append(getFluidIngredient(slot) == null ? "-" : getFluidIngredient(slot).getName()).append("]");
            if (outputRole[out] == OutputRole.PRODUCT && isFluidSlot(slot)) {
                FluidIdentifier fluid = getFluidIngredient(slot);
                if (fluid != null) {
                    interests.add(fluid.getItemIdentifier());
                }
            }
        }
        String summary = debug.toString();
        if (!summary.equals(loggedInterests)) {
            loggedInterests = summary;
            LogisticsPipes.log.info("[SmartCrafter DEBUG] results:" + summary + " -> interests=" + interests);
        }
        return interests;
    }

    private String loggedInterests = null;

    /** True when some result is configured at all, fluid or item. The gate has nothing to do without one. */
    private boolean hasAnyResult() {
        for (int i = 0; i < OUTPUT_SLOTS; i++) {
            if (getOutput(i) != null) {
                return true;
            }
        }
        return false;
    }

    /** Litres of this fluid result that orders are still waiting for. */
    private int outstandingFluid(FluidIdentifier fluid) {
        if (fluidOrders == null || fluid == null) {
            return 0;
        }
        int total = 0;
        for (LogisticsFluidOrder order : getFluidOrders()) {
            if (order.getType() == ResourceType.CRAFTING && fluid.equals(order.getFluid())) {
                total += order.getAmount();
            }
        }
        return total;
    }

    /** True while no output can be requested, so nothing will ever start this recipe. */
    public boolean hasNoCraftableOutput() {
        if (primaryOutput() >= 0) {
            return false;
        }
        for (int i = 0; i < OUTPUT_SLOTS; i++) {
            if (outputRole[i] == OutputRole.PRODUCT && isFluidSlot(outputInventorySlot(i))) {
                return false;
            }
        }
        return true;
    }

    @Override
    public ItemIdentifierStack getConfiguredCraftResult() {
        int primary = primaryOutput();
        return primary < 0 ? null : getOutput(primary);
    }

    @Override
    public List<ItemIdentifierStack> getConfiguredCraftResults() {
        List<ItemIdentifierStack> list = new ArrayList<>(OUTPUT_SLOTS);
        for (int i = 0; i < OUTPUT_SLOTS; i++) {
            if (outputRole[i] != OutputRole.PRODUCT || isFluidSlot(outputInventorySlot(i))) {
                continue;
            }
            ItemIdentifierStack output = getOutput(i);
            if (output != null) {
                list.add(output);
            }
        }
        return list;
    }

    @Override
    public boolean canCraft(IResource toCraft) {
        if (toCraft instanceof FluidResource) {
            return fluidResultSlot(((FluidResource) toCraft).getFluid()) >= 0;
        }
        if (!(toCraft instanceof ItemResource) && !(toCraft instanceof DictResource)) {
            return false;
        }
        for (ItemIdentifierStack result : getConfiguredCraftResults()) {
            if (toCraft.matches(result.getItem(), IResource.MatchSettings.NORMAL)) {
                return true;
            }
        }
        return false;
    }

    /** The result slot producing this fluid as a Product, or -1. Only a Product is offered to the planner. */
    private int fluidResultSlot(FluidIdentifier fluid) {
        if (fluid == null) {
            return -1;
        }
        for (int out = 0; out < OUTPUT_SLOTS; out++) {
            int slot = outputInventorySlot(out);
            LogisticsPipes.log.info(
                    "[SmartCrafter DEBUG] result " + out
                            + " role="
                            + outputRole[out]
                            + " isFluid="
                            + isFluidSlot(slot)
                            + " litres="
                            + fluidAmount[slot]
                            + " has="
                            + getFluidIngredient(slot)
                            + " wanted="
                            + fluid);
            if (outputRole[out] == OutputRole.PRODUCT && isFluidSlot(slot) && fluid.equals(getFluidIngredient(slot))) {
                return out;
            }
        }
        return -1;
    }

    /**
     * A fluid result is offered to the planner the same way an item one is: the request tree and the chassis are both
     * generic over {@link IResource}, so only the template differs.
     */
    @Override
    protected IReqCraftingTemplate createTemplateFor(IResource toCraft) {
        if (!(toCraft instanceof FluidResource)) {
            return super.createTemplateFor(toCraft);
        }
        int out = fluidResultSlot(((FluidResource) toCraft).getFluid());
        if (out < 0) {
            return null;
        }
        int slot = outputInventorySlot(out);
        return new FluidCraftingTemplate(
                new FluidResource(getFluidIngredient(slot), fluidAmount[slot], ((FluidResource) toCraft).getTarget()),
                this,
                priority);
    }

    @Override
    protected void addTemplateByproducts(IReqCraftingTemplate template) {
        for (int i = 0; i < OUTPUT_SLOTS; i++) {
            ItemIdentifierStack output = getOutput(i);
            if (outputRole[i] == OutputRole.BYPRODUCT_COUNTED && output != null) {
                template.addByproduct(output);
            }
        }
    }

    /** The byproduct upgrade's extra slot doesn't exist here; roles do the same job. */
    @Override
    public ItemIdentifierStack getByproductItem() {
        return null;
    }

    /** Built in, no Advanced Satellite upgrade needed. */
    @Override
    protected boolean usesPerSlotSatellites() {
        return true;
    }

    /* Settings sync */

    @Override
    public ModernPacket getCPipePacket() {
        SmartCrafterUpdatePacket packet = PacketHandler.getPacket(SmartCrafterUpdatePacket.class);
        int[] roles = new int[OUTPUT_SLOTS];
        int[] chances = new int[OUTPUT_SLOTS];
        for (int i = 0; i < OUTPUT_SLOTS; i++) {
            roles[i] = outputRole[i].ordinal();
            chances[i] = outputChance[i];
        }
        packet.setOutputRole(roles).setOutputChance(chances).setOutputSatelliteId(outputSatelliteId.clone())
                .setFluidAmount(fluidAmount.clone()).setCleanupEnabled(cleanupEnabled)
                .setStatus(MainProxy.isServer(getWorld()) ? computeStatus() : lastStatus).setSetsReleased(setsReleased);
        packet.setSatelliteId(satelliteId).setAdvancedSatelliteIdArray(advancedSatelliteIdArray).setPriority(priority)
                .setAmount(amount).setLiquidSatelliteIdArray(liquidSatelliteIdArray)
                .setLiquidSatelliteId(liquidSatelliteId);
        packet.setModulePos(this);
        return packet;
    }

    public void handleSmartUpdatePacket(SmartCrafterUpdatePacket packet) {
        int[] roles = packet.getOutputRole();
        int[] chances = packet.getOutputChance();
        int[] satellites = packet.getOutputSatelliteId();
        int[] fluids = packet.getFluidAmount();
        for (int i = 0; i < INVENTORY_SIZE && i < fluids.length; i++) {
            fluidAmount[i] = Math.max(0, fluids[i]);
        }
        for (int i = 0; i < OUTPUT_SLOTS; i++) {
            if (i < roles.length && roles[i] >= 0 && roles[i] < OutputRole.values().length) {
                outputRole[i] = OutputRole.values()[roles[i]];
            }
            if (i < chances.length && chances[i] > 0) {
                outputChance[i] = Math.min(GUARANTEED, chances[i]);
            }
            if (i < satellites.length) {
                outputSatelliteId[i] = Math.max(0, satellites[i]);
            }
        }
        cleanupEnabled = packet.isCleanupEnabled();
        lastStatus = packet.getStatus();
        setsReleased = packet.getSetsReleased();
    }

    /** Client-side copy of the last status the server sent, for the gui's warning line. */
    private int lastStatus = STATUS_IDLE;

    public int getLastStatus() {
        return lastStatus;
    }

    /** Applies one setting from {@link SmartCrafterSetting}, on either side. */
    public void handleSettingPacket(int setting, int index, int value) {
        switch (setting) {
            case SmartCrafterSetting.SATELLITE:
                if (index >= 0 && index < INGREDIENT_SLOTS) {
                    advancedSatelliteIdArray[index] = Math.max(0, value);
                    gateDirty = true;
                }
                return;
            case SmartCrafterSetting.FLUID_AMOUNT:
                if (index >= 0 && index < INGREDIENT_SLOTS) {
                    setFluidAmount(index, value);
                }
                return;
            case SmartCrafterSetting.OUTPUT_SATELLITE:
                if (index >= 0 && index < OUTPUT_SLOTS) {
                    setOutputSatelliteId(index, value);
                }
                return;
            case SmartCrafterSetting.OUTPUT_ROLE:
                if (index >= 0 && index < OUTPUT_SLOTS && value >= 0 && value < OutputRole.values().length) {
                    setOutputRole(index, OutputRole.values()[value]);
                }
                return;
            case SmartCrafterSetting.OUTPUT_CHANCE:
                if (index >= 0 && index < OUTPUT_SLOTS) {
                    setOutputChance(index, value);
                }
                return;
            case SmartCrafterSetting.CLEANUP:
                cleanupEnabled = value != 0;
                return;
            default:
        }
    }

    public boolean isCleanupEnabled() {
        return cleanupEnabled;
    }

    @Override
    protected ModuleCoordinatesGuiProvider getPipeGuiProvider() {
        return NewGuiHandler.getGui(SmartCrafterModuleSlot.class);
    }

    @Override
    protected ModuleInHandGuiProvider getInHandGuiProvider() {
        return NewGuiHandler.getGui(SmartCrafterInHand.class);
    }

    /* Status, for the gui's warning line */

    public static final int STATUS_RUNNING = 0;
    public static final int STATUS_IDLE = 1;
    public static final int STATUS_NO_PRODUCT = 2;
    public static final int STATUS_NO_MACHINE = 3;
    public static final int STATUS_SET_TOO_LARGE = 4;
    public static final int STATUS_WAITING_CLAIM = 5;
    public static final int STATUS_HOLDING = 6;
    public static final int STATUS_FLUID_NO_SATELLITE = 7;
    /** A GregTech output next to this module empties itself, so results would bypass the crafter. */
    public static final int STATUS_OUTPUT_PUSHES = 8;
    /** Same as {@link #STATUS_OUTPUT_PUSHES}, next to one of this recipe's output satellites. */
    public static final int STATUS_SATELLITE_OUTPUT_PUSHES = 9;

    public int computeStatus() {
        if (hasNoCraftableOutput()) {
            return STATUS_NO_PRODUCT;
        }
        if (fluidSlotWithNowhereToGo() >= 0) {
            return STATUS_FLUID_NO_SATELLITE;
        }
        if (_service == null || _service.getRealInventory() == null) {
            return STATUS_NO_MACHINE;
        }
        if (setTooLarge) {
            return STATUS_SET_TOO_LARGE;
        }
        // Checked before idle, so it shows while the player is still setting up, before a result goes missing.
        if (OutputPushUtil.findPushingNeighbour(getWorld(), getX(), getY(), getZ()) != null) {
            return STATUS_OUTPUT_PUSHES;
        }
        if (outputSatelliteMachinePushes()) {
            return STATUS_SATELLITE_OUTPUT_PUSHES;
        }
        if (!_service.getItemOrderManager().hasOrders(ResourceType.CRAFTING, ResourceType.EXTRA)) {
            return STATUS_IDLE;
        }
        MachineClaims.Key machine = machineKey();
        if (machine != null && MachineClaims.isHeldByOther(machine, this, now())) {
            return STATUS_WAITING_CLAIM;
        }
        return setsReleased > 0 ? STATUS_RUNNING : STATUS_HOLDING;
    }

    /** Whether a machine output next to any of this recipe's output satellites empties itself. */
    private boolean outputSatelliteMachinePushes() {
        for (int i = 0; i < OUTPUT_SLOTS; i++) {
            if (getOutput(i) == null || outputSatelliteId[i] == 0) {
                continue;
            }
            PipeSmartSatellite sat = smartSatelliteForId(outputSatelliteId[i]);
            if (sat != null && sat.findPushingOutput() != null) {
                return true;
            }
        }
        return false;
    }

    /** Sets released into the machine and not yet finished, shown next to the status. */
    public int getSetsReleased() {
        return setsReleased;
    }

    /**
     * Requests one more set of ingredients. For sets a machine consumed without producing anything (GTNH cleanrooms
     * void ingredients), where nothing is reported lost and the order would otherwise wait forever.
     */
    public void requestOneSet(EntityPlayer player) {
        int slots = 0;
        int satisfied = 0;
        for (int slot = 0; slot < INGREDIENT_SLOTS; slot++) {
            ItemIdentifierStack material = getMaterials(slot);
            if (material == null || material.getStackSize() <= 0) {
                continue;
            }
            slots++;
            IRequestItems target = this;
            if (advancedSatelliteIdArray[slot] != 0) {
                IRouter router = getSatelliteRouter(slot);
                if (router != null) {
                    target = (IRequestItems) router.getPipe();
                }
            }
            int got = RequestTree.requestPartial(
                    new ItemIdentifierStack(material.getItem(), material.getStackSize()),
                    target,
                    new CraftingChassieInformation(slot, getPositionInt()));
            if (got >= material.getStackSize()) {
                satisfied++;
            }
        }
        if (slots == 0) {
            say(player, "no ingredients configured");
            return;
        }
        // One more set of work for the gate, which then grants the allowance through the normal release path (room
        // check and machine claim included). Raising the allowance here instead would be undone by the next
        // updateGate: with no order behind this set it takes the "nothing to do" branch and resets the gate.
        pendingManualSets++;
        int waitingBefore = pendingManualSets;
        // Run the gate now rather than on the next tick, so the answer below is what actually happened.
        updateGate();
        String outcome = pendingManualSets < waitingBefore ? "on its way" : whyNotReleased();
        say(player, satisfied + "/" + slots + " ingredients found, " + outcome);
    }

    /** Why the gate didn't hand a set over, for the "Request set" reply. */
    private String whyNotReleased() {
        MachineClaims.Key machine = machineKey();
        if (machine == null || _service.getRealInventory() == null) {
            return "no machine in front of this module";
        }
        if (MachineClaims.isHeldByOther(machine, this, now())) {
            return "another crafter is using the machine";
        }
        if (countSetsThatFit(1, false) == 0) {
            return countSetsThatFit(1, true) == 0 ? "a set does not fit this machine at all"
                    : "no room in the machine right now";
        }
        return "queued, waiting for ingredients";
    }

    private void say(EntityPlayer player, String message) {
        if (player != null) {
            player.addChatMessage(new ChatComponentText("Smart Crafter: " + message));
        }
    }

    /** Sends one setting to the server, from the gui. */
    public void sendSetting(int setting, int index, int value) {
        handleSettingPacket(setting, index, value);
        MainProxy.sendPacketToServer(
                PacketHandler.getPacket(SmartCrafterSetting.class).setSetting(setting).setIndex(index).setValue(value)
                        .setModulePos(this));
    }

    @Override
    public void readFromNBT(NBTTagCompound nbttagcompound) {
        super.readFromNBT(nbttagcompound);
        cleanupEnabled = !nbttagcompound.hasKey("SmartCleanup") || nbttagcompound.getBoolean("SmartCleanup");
        int[] roles = nbttagcompound.getIntArray("SmartOutputRole");
        int[] chances = nbttagcompound.getIntArray("SmartOutputChance");
        int[] satellites = nbttagcompound.getIntArray("SmartOutputSatellite");
        int[] fluids = nbttagcompound.getIntArray("SmartFluidAmount");
        for (int i = 0; i < INVENTORY_SIZE && i < fluids.length; i++) {
            fluidAmount[i] = Math.max(0, fluids[i]);
        }
        for (int i = 0; i < OUTPUT_SLOTS; i++) {
            if (i < roles.length && roles[i] >= 0 && roles[i] < OutputRole.values().length) {
                outputRole[i] = OutputRole.values()[roles[i]];
            }
            if (i < chances.length && chances[i] > 0) {
                outputChance[i] = Math.min(GUARANTEED, chances[i]);
            }
            if (i < satellites.length) {
                outputSatelliteId[i] = Math.max(0, satellites[i]);
            }
        }
        // The listener never fires for a load, so mark the fluid slots here.
        refreshFluidSlots();
    }

    @Override
    public void writeToNBT(NBTTagCompound nbttagcompound) {
        super.writeToNBT(nbttagcompound);
        int[] roles = new int[OUTPUT_SLOTS];
        int[] chances = new int[OUTPUT_SLOTS];
        for (int i = 0; i < OUTPUT_SLOTS; i++) {
            roles[i] = outputRole[i].ordinal();
            chances[i] = outputChance[i];
        }
        nbttagcompound.setIntArray("SmartOutputRole", roles);
        nbttagcompound.setIntArray("SmartOutputChance", chances);
        nbttagcompound.setIntArray("SmartOutputSatellite", outputSatelliteId);
        nbttagcompound.setIntArray("SmartFluidAmount", fluidAmount);
        nbttagcompound.setBoolean("SmartCleanup", cleanupEnabled);
    }

    @Override
    public void handleAdvancedNEIRecipePacket(List<ItemStack> inputs, List<ItemStack> outputs,
            List<FluidStack> fluidInputs, EntityPlayer player) {
        super.handleAdvancedNEIRecipePacket(inputs, outputs, fluidInputs, player);
        // The base puts fluids in the upgrade's separate fluid inventory, which this module doesn't have: here a fluid
        // is an ingredient slot like any other, so they follow the items into the grid.
        for (int slot = 0; slot < INVENTORY_SIZE; slot++) {
            fluidAmount[slot] = 0;
        }
        int slot = inputs.size();
        for (FluidStack fluid : fluidInputs) {
            if (slot >= INGREDIENT_SLOTS) {
                break;
            }
            FluidIdentifier ident = fluid == null ? null : FluidIdentifier.get(fluid);
            if (ident == null) {
                continue;
            }
            _dummyInventory.setInventorySlotContents(slot, ident.getItemIdentifier().unsafeMakeNormalStack(1));
            fluidAmount[slot] = fluid.amount > 0 ? fluid.amount : DEFAULT_FLUID_AMOUNT;
            slot++;
        }
        // The base only fills two result slots, and the second one as the upgrade's byproduct slot.
        for (int i = 0; i < OUTPUT_SLOTS; i++) {
            _dummyInventory
                    .setInventorySlotContents(outputInventorySlot(i), i < outputs.size() ? outputs.get(i) : null);
            outputRole[i] = i == 0 ? OutputRole.PRODUCT : OutputRole.BYPRODUCT;
            outputChance[i] = GUARANTEED;
        }
        if (player != null) {
            MainProxy.sendPacketToPlayer(getCPipePacket(), player);
        }
    }

    @Override
    @SideOnly(Side.CLIENT)
    public IIcon getIconTexture(IIconRegister register) {
        return register.registerIcon("logisticspipes:itemModule/ModuleSmartCrafter");
    }

    @Override
    protected int neededEnergy() {
        return 20;
    }

    @Override
    protected int itemsToExtract() {
        return 128;
    }

    @Override
    protected int stacksToExtract() {
        return 8;
    }

    @Override
    public void tick() {
        super.tick();
        if (_service == null || !MainProxy.isServer(getWorld())) {
            return;
        }
        if (claimedMachine != null && _service.isNthTick(CLAIM_REFRESH_TICKS)) {
            if (claimedMachine.equals(machineKey())) {
                MachineClaims.refresh(claimedMachine, this, now());
            } else {
                // The chassis was turned to face another block.
                releaseClaim();
                gateDirty = true;
            }
        }
        if (!registered) {
            ModuleSmartCrafter.AllCrafters.add(this);
            registered = true;
        }
        if (gateDirty || _service.isNthTick(SAFETY_NET_TICKS)) {
            updateGate();
        }
        if (_service.isNthTick(UNORDERED_OUTPUT_TICKS)) {
            drainUnorderedOutputs();
        }
        if (_service.isNthTick(6)) {
            sendCraftedFluid();
        }
        publishCrafting();
    }

    /* Crafting totals and state, for the job list, the active crafts view and computers */

    /** What this module last told {@link CraftingJobs} it has in its machine, per output. */
    private final ItemIdentifier[] publishedItem = new ItemIdentifier[OUTPUT_SLOTS];
    private final int[] publishedAmount = new int[OUTPUT_SLOTS];

    /**
     * Keeps the network-wide "being crafted" totals in step with the sets this module has released. Called every tick,
     * but only a comparison of three numbers unless something changed, so the totals never need a walk over crafters.
     */
    private void publishCrafting() {
        for (int i = 0; i < OUTPUT_SLOTS; i++) {
            ItemIdentifier item = null;
            int amount = 0;
            if (outputRole[i] == OutputRole.PRODUCT && !isFluidSlot(outputInventorySlot(i))) {
                ItemIdentifierStack output = getOutput(i);
                if (output != null && output.getStackSize() > 0) {
                    item = output.getItem();
                    amount = setsReleased * output.getStackSize();
                }
            }
            if (amount <= 0) {
                item = null;
                amount = 0;
            }
            if (amount == publishedAmount[i] && Objects.equals(item, publishedItem[i])) {
                continue;
            }
            if (publishedItem[i] != null) {
                CraftingJobs.addCrafting(publishedItem[i], -publishedAmount[i]);
            }
            if (item != null) {
                CraftingJobs.addCrafting(item, amount);
            }
            publishedItem[i] = item;
            publishedAmount[i] = amount;
        }
    }

    private void withdrawCrafting() {
        for (int i = 0; i < OUTPUT_SLOTS; i++) {
            if (publishedItem[i] != null) {
                CraftingJobs.addCrafting(publishedItem[i], -publishedAmount[i]);
            }
            publishedItem[i] = null;
            publishedAmount[i] = 0;
        }
    }

    /** Adds what this module has published to a recount. */
    public void addPublishedCrafting(Map<ItemIdentifier, Integer> totals) {
        for (int i = 0; i < OUTPUT_SLOTS; i++) {
            if (publishedItem[i] != null && publishedAmount[i] > 0) {
                totals.merge(publishedItem[i], publishedAmount[i], Integer::sum);
            }
        }
    }

    /** What this module is doing, for the active crafts view. */
    public enum CrafterState {
        /** No orders. */
        IDLE,
        /** Holds the machine and has sets in it. */
        CRAFTING,
        /** In line behind another crafter's claim on the machine. */
        WAITING_FOR_MACHINE,
        /** Holding back until an ingredient another crafter makes is on hand. */
        WAITING_FOR_INTERMEDIATE,
        /** Sets released, but nothing is arriving. */
        WAITING_FOR_INGREDIENTS,
        /** No machine, a set that doesn't fit, no room, or a result with nowhere to go. */
        BLOCKED
    }

    /** Worked out when asked; nothing is kept up to date for it. Server side. */
    public CrafterState getCrafterState() {
        if (_service == null || !recipeHasWork()) {
            return CrafterState.IDLE;
        }
        if (_service.getRealInventory() == null || setTooLarge || resultHeld) {
            return CrafterState.BLOCKED;
        }
        if (awaitedMachine != null) {
            return CrafterState.WAITING_FOR_MACHINE;
        }
        if (waitingForIntermediatesSince != 0) {
            return CrafterState.WAITING_FOR_INTERMEDIATE;
        }
        if (isBlockedWaitingForIngredients()) {
            return CrafterState.WAITING_FOR_INGREDIENTS;
        }
        // Holding the machine with nothing released means no room for even one set right now.
        return setsReleased > 0 ? CrafterState.CRAFTING : CrafterState.BLOCKED;
    }

    /** The last result this module tried to send had nowhere to go and was left in the machine. */
    private boolean resultHeld = false;

    /** Ticks since this module last saw progress: an ingredient arriving or a result coming out. */
    public long getIdleTicks() {
        return Math.max(0, now() - lastProgressTick);
    }

    /** How many other crafters are in line for the machine this module holds or waits for. */
    public int getMachineQueueLength() {
        MachineClaims.Key machine = claimedMachine != null ? claimedMachine : awaitedMachine;
        return machine == null ? 0 : MachineClaims.waitingCount(machine);
    }

    /**
     * Takes results nothing asked for out of the machine and sends them to storage. The crafter's normal extraction
     * loop only takes what an order wants, so anything else piles up until it blocks the machine: a byproduct, but
     * equally a second <b>product</b> of a recipe whose other product was the one requested (one iron into a lathe
     * gives a rod and two dust; ask for rods and the dust fills its output slot after 32 crafts).
     * <p>
     * Role has no bearing on this. What matters is only whether an order is waiting for the item, in which case it is
     * left for that order.
     */
    private void drainUnorderedOutputs() {
        // Only while this module has a job of its own. With nothing ordered there is no craft of ours to keep running,
        // so anything in the machine is the player's (a set handed over by "Request set", something they put there) and
        // is left alone. Clearing an idle machine is the Cleanup checkbox's job, not this.
        if (!hasWork()) {
            return;
        }
        IInventory inv = null;
        int stacks = 0;
        for (int i = 0; i < OUTPUT_SLOTS && stacks < MAX_SWEEP_STACKS; i++) {
            ItemIdentifierStack output = getOutput(i);
            if (output == null || outstandingResults(output) > 0) {
                continue;
            }
            if (inv == null) {
                MachineClaims.Key machine = machineKey();
                inv = _service.getRealInventory();
                if (inv == null || machine == null || MachineClaims.isHeldByOther(machine, this, now())) {
                    return;
                }
            }
            stacks += takeOut(inv, output.getItem(), MAX_SWEEP_STACKS - stacks);
        }
        if (stacks > 0) {
            _service.getCacheHolder().trigger(CacheTypes.Inventory);
        }
    }

    @Override
    public void onAllowedRemoval() {
        super.onAllowedRemoval();
        ModuleSmartCrafter.AllCrafters.remove(this);
        registered = false;
        releaseClaim();
        stopWaiting();
        withdrawCrafting();
        clearBufferedCredit();
    }

    private boolean registered = false;

    /** Called by {@link MachineClaims} when the machine this module waited for is handed to it. */
    void onClaimGranted(MachineClaims.Key machine) {
        claimedMachine = machine;
        awaitedMachine = null;
        releasedThisTurn = false;
        claimTakenTick = now();
        sweepBeforeRelease = true;
        gateDirty = true;
    }

    @Override
    public LogisticsItemOrder fullFill(LogisticsPromise promise, IRequestItems destination,
            IAdditionalTargetInformation info) {
        LogisticsItemOrder order = super.fullFill(promise, destination, info);
        gateDirty = true;
        return order;
    }

    /**
     * Counts a set as finished from whichever output turns up.
     * <p>
     * A set yields every output at once, so the outputs are alternative views of the same progress, not separate
     * progress to add up: the set count is the <b>largest</b> any one output implies. Taking the largest also keeps a
     * chanced output from holding the count back, since a guaranteed one overtakes it.
     */
    @Override
    protected void onResultExtracted(ItemIdentifier item, int amount) {
        ModuleSmartCrafter owner = resultOwner(item);
        if (owner != null) {
            owner.creditSets(owner.outputIndexFor(item), amount);
        }
    }

    /**
     * The Smart Crafter whose recipe made a result this module just pulled out, which is often not this one.
     * <p>
     * All modules in a chassis face the same machine and share one order queue, so whichever module's tick reaches an
     * order first extracts its result. Credit has to go to the recipe that made it: otherwise that recipe never sees
     * its sets finish, its progress clock stands still, and after {@link #STUCK_TICKS} it thinks it is blocked and
     * hands the machine over mid-job. Among recipes making the same item, the one holding the machine or with sets out
     * wins.
     */
    private ModuleSmartCrafter resultOwner(ItemIdentifier item) {
        boolean ours = outputIndexFor(item) >= 0;
        if (ours && (claimedMachine != null || setsReleased > 0)) {
            return this;
        }
        ModuleSmartCrafter fallback = ours ? this : null;
        MachineClaims.Key machine = _service == null ? null : machineKey();
        if (machine == null) {
            return fallback;
        }
        for (ModuleSmartCrafter other : ModuleSmartCrafter.AllCrafters) {
            if (other == this || other._service == null
                    || other.getWorld() == null
                    || other.outputIndexFor(item) < 0
                    || !machine.equals(other.machineKey())) {
                continue;
            }
            if (other.claimedMachine != null || other.setsReleased > 0) {
                return other;
            }
            if (fallback == null) {
                fallback = other;
            }
        }
        return fallback;
    }

    /**
     * Whether this module's own recipe still has something to make: sets out, orders for its outputs, or fluid orders
     * of its own. {@link #hasWork} can't tell modules apart, since a chassis's item order queue is shared by all of its
     * modules and any module's order makes every one of them look busy.
     */
    private boolean recipeHasWork() {
        if (setsReleased > 0 || pendingManualSets > 0) {
            return true;
        }
        for (int i = 0; i < OUTPUT_SLOTS; i++) {
            ItemIdentifierStack output = getOutput(i);
            if (output != null && outstandingResults(output) > 0) {
                return true;
            }
        }
        if (fluidOrders != null) {
            for (LogisticsFluidOrder order : getFluidOrders()) {
                if (order.getType() == ResourceType.CRAFTING || order.getType() == ResourceType.EXTRA) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Counts what came out of one result slot towards finished sets. Shared by the item path and the fluid one, since a
     * set is finished whether its result left as an item or as fluid.
     */
    private void creditSets(int output, int amount) {
        extractedPerOutput[output] += amount;
        int finished = 0;
        for (int i = 0; i < OUTPUT_SLOTS; i++) {
            int perSet = resultPerSet(i);
            if (perSet > 0) {
                finished = Math.max(finished, extractedPerOutput[i] / perSet);
            }
        }
        if (finished > creditedSets) {
            setsReleased = Math.max(0, setsReleased - (finished - creditedSets));
            creditedSets = finished;
            gateDirty = true;
        }
        lastProgressTick = now();
    }

    /** How much of a result slot one set makes: a stack size for an item, litres for a fluid. */
    private int resultPerSet(int output) {
        int slot = outputInventorySlot(output);
        if (isFluidSlot(slot)) {
            return fluidAmount[slot];
        }
        ItemIdentifierStack stack = getOutput(output);
        return stack == null ? 0 : stack.getStackSize();
    }

    private void clearResultProgress() {
        for (int i = 0; i < OUTPUT_SLOTS; i++) {
            extractedPerOutput[i] = 0;
        }
        creditedSets = 0;
    }

    /** Which output slot an extracted item belongs to, or -1 if it isn't one of this recipe's results. */
    private int outputIndexFor(ItemIdentifier item) {
        for (int i = 0; i < OUTPUT_SLOTS; i++) {
            ItemIdentifierStack output = getOutput(i);
            if (output != null && output.getStackSize() > 0 && isOurResult(item, output)) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public void itemArrived(ItemIdentifierStack item, IAdditionalTargetInformation info) {
        super.itemArrived(item, info);
        int slot = ingredientSlot(info);
        if (slot >= 0) {
            inFlight[slot] = Math.max(0, inFlight[slot] - item.getStackSize());
            lastProgressTick = now();
        }
    }

    @Override
    public void itemLost(ItemIdentifierStack item, IAdditionalTargetInformation info) {
        super.itemLost(item, info);
        int slot = ingredientSlot(info);
        if (slot >= 0) {
            // The crafter re-requests lost items, so let the replacement through.
            inFlight[slot] = Math.max(0, inFlight[slot] - item.getStackSize());
            allowance[slot] += item.getStackSize();
        }
    }

    /* IGatedItemSink */

    @Override
    public int getGatedAllowance(ItemIdentifier item, IAdditionalTargetInformation info) {
        int slot = ingredientSlot(info);
        if (slot < 0 || !isGatedSlot(slot)) {
            return Integer.MAX_VALUE;
        }
        return allowance[slot];
    }

    @Override
    public void onGatedSend(ItemIdentifier item, int amount, IAdditionalTargetInformation info) {
        int slot = ingredientSlot(info);
        if (slot < 0 || !isGatedSlot(slot)) {
            return;
        }
        allowance[slot] = Math.max(0, allowance[slot] - amount);
        inFlight[slot] += amount;
        lastProgressTick = now();
    }

    @Override
    public boolean declinesUntrackedItem(ItemIdentifier item) {
        for (int slot = 0; slot < INGREDIENT_SLOTS; slot++) {
            if (isGatedSlot(slot) && getMaterials(slot).getItem().equals(item)) {
                return true;
            }
        }
        return false;
    }

    /* Smart Satellite fluid input (reported by the satellite pipe) */

    /**
     * First input slot that holds this fluid. Fluid ingredients carry no slot information, so the satellite reports
     * fluid arrivals and not-inserted events by fluid identity only.
     *
     * @return the slot index, or -1 if no input slot holds this fluid.
     */
    public int fluidSlotFor(FluidIdentifier fluid) {
        if (fluid == null) {
            return -1;
        }
        for (int slot = 0; slot < INGREDIENT_SLOTS; slot++) {
            if (fluidAmount[slot] > 0 && fluid.equals(getFluidIngredient(slot))) {
                return slot;
            }
        }
        return -1;
    }

    /** A fluid delivered to one of this crafter's satellite endpoints was inserted into the machine's tank. */
    public void fluidArrived(FluidIdentifier fluid, int amount) {
        if (fluidSlotFor(fluid) >= 0) {
            lastProgressTick = now();
        }
    }

    /**
     * A fluid delivered to a satellite endpoint did not fit the machine's tank; the satellite re-queues it. Refund the
     * slot's in-flight amount so the crafter may re-request it (mirrors {@link #itemLost}).
     */
    public void fluidNotInserted(FluidIdentifier fluid, int amount) {
        int slot = fluidSlotFor(fluid);
        if (slot < 0) {
            return;
        }
        // The satellite re-queues the unfit fluid, so let the replacement through.
        inFlight[slot] = Math.max(0, inFlight[slot] - amount);
        allowance[slot] += amount;
    }

    /* Gate */

    private void updateGate() {
        gateDirty = false;
        if (!hasAnyResult()) {
            resetGate();
            return;
        }
        // Sets needed for whichever product is furthest behind: one set yields every output at once, so ordering two
        // of this recipe's products needs the larger of the two set counts, not their sum. A fluid result counts the
        // same way, from its own orders, so a recipe that only makes fluid still pulls its ingredients in.
        int setsNeeded = 0;
        for (int i = 0; i < OUTPUT_SLOTS; i++) {
            if (outputRole[i] != OutputRole.PRODUCT) {
                continue;
            }
            int perSet = resultPerSet(i);
            if (perSet <= 0) {
                continue;
            }
            int slot = outputInventorySlot(i);
            int outstandingForOutput = isFluidSlot(slot) ? outstandingFluid(getFluidIngredient(slot))
                    : outstandingResults(getOutput(i));
            if (outstandingForOutput > 0) {
                setsNeeded = Math.max(setsNeeded, (outstandingForOutput + perSet - 1) / perSet);
            }
        }
        // Sets asked for by the "Request set" button have no order behind them, so they are counted here instead.
        int wanted = setsNeeded + pendingManualSets;
        if (wanted <= 0) {
            if (deliveryInProgress()) {
                // A set is still on its way (a manual one, or the tail of a finished job). Resetting now would zero
                // the allowance mid-delivery and strand it in storage.
                return;
            }
            if (pendingLoadSweep) {
                // Retried on the next update if the machine isn't reachable yet or another crafter is using it.
                pendingLoadSweep = !sweepMachine();
            } else if (hadOrders) {
                // The last order finished. Usually nothing is left, but a request that failed partway can leave part
                // of a set behind, a lost item can turn up after its replacement did, and results beyond what was
                // ordered stay in the output. Clearing them now, instead of at this crafter's next job, keeps the
                // machine usable for other recipes, players or other automation in the meantime.
                sweepMachine();
            }
            hadOrders = false;
            resetGate();
            return;
        }
        // Only an ordered job counts, so the end-of-job sweep above never fires for a set handed over by hand.
        hadOrders |= setsNeeded > 0;
        pendingLoadSweep = false; // the pre-craft sweep below covers it
        giveUpStuckSets();

        int toRelease = Math.min(wanted, MAX_SETS_IN_FLIGHT) - setsReleased;
        if (toRelease > 0 && waitedLongEnoughForIntermediates() && mayReleaseSets()) {
            if (sweepBeforeRelease) {
                // First release since taking the machine: anything of a Smart Crafter recipe still in it is stale (left
                // over from before a restart, a cancelled job, a set that never finished, or items that arrived after
                // their crafter's own end-of-job sweep). Clear it so the room check sees the machine's real free space
                // and the new sets don't mix with old ones. Sweeping when the machine is next used is what makes a
                // retrying sweep unnecessary.
                sweepAllRecipesOnMachine();
                sweepBeforeRelease = false;
            }
            int fits = countSetsThatFit(toRelease, false);
            if (fits > 0) {
                releaseSets(fits);
                setTooLarge = false;
            } else if (setsReleased == 0) {
                setTooLarge = countSetsThatFit(1, true) == 0;
            }
        }

        // Take anything of ours out of the buffers, but only while this module holds the machine. A set that was
        // already released can still be short - its ingredient may have reached a buffer afterwards, and nothing else
        // would ever move it - so this can't wait for the next release. It must still respect the claim: pulling into
        // a machine another crafter is working in is exactly the ingredient mixing the claim exists to prevent.
        for (int slot = 0; claimedMachine != null && !ModuleCraftingBuffer.AllBuffers.isEmpty()
                && slot < INGREDIENT_SLOTS; slot++) {
            if (!isGatedSlot(slot)) {
                continue;
            }
            ItemIdentifierStack material = getMaterials(slot);
            // Buffer credit first: it is the cheaper question, and asking it avoids scanning the machine for every
            // ingredient slot only to find there was nothing to fetch.
            if (bufferedFor(slot) <= 0) {
                continue;
            }
            int needed = wanted * material.getStackSize() - inFlight[slot] - machineStock(material.getItem());
            if (needed > 0) {
                pullFromBuffers(slot, needed);
            }
        }

        // Hand the machine over once the sets in it are done, if someone else is waiting for it. An owner that
        // couldn't release anything (machine blocked) keeps it for a while first, so two blocked crafters don't pass it
        // back and forth every tick.
        boolean hadTurn = releasedThisTurn || now() - claimTakenTick >= SAFETY_NET_TICKS;
        if (claimedMachine != null && hadTurn
                && setsReleased == 0
                && nothingInFlight()
                && MachineClaims.hasOthersWaiting(claimedMachine, this)) {
            releaseClaim();
            gateDirty = true; // queue up again behind the crafter that is waiting
        } else if (claimedMachine != null && isBlockedWaitingForIngredients()
                && MachineClaims.hasOthersWaiting(claimedMachine, this)) {
                    // Holding the machine and getting nothing, while someone else wants it. The allowance goes back
                    // too, so
                    // providers stop sending into a machine this module no longer owns. Without this a crafter whose
                    // ingredient can only come from the very crafter it is blocking would hold on for ever.
                    for (int slot = 0; slot < INGREDIENT_SLOTS; slot++) {
                        allowance[slot] = 0;
                    }
                    setsReleased = 0;
                    clearResultProgress();
                    releaseClaim();
                    gateDirty = true;
                }
    }

    /**
     * Whether every ingredient that <b>another crafter has to make</b> is already on hand: in a buffer, on its way
     * here, or sitting in the machine. Raw materials are taken on trust, because the planner already found a source for
     * them and a provider only sends once the gate opens - waiting for those would deadlock against ourselves.
     * <p>
     * This is what stops a crafter seizing a machine on the strength of having an order. Two recipes on one machine,
     * where one makes the other's ingredient, used to deadlock outright: the second crafter claimed the machine,
     * released a set, and waited forever for an ingredient the first could no longer make, because it could never get
     * the machine. Its allowance stayed outstanding, so the stuck-set rule never fired either.
     */
    private boolean intermediatesReady() {
        for (int slot = 0; slot < INGREDIENT_SLOTS; slot++) {
            if (!isGatedSlot(slot)) {
                continue;
            }
            ItemIdentifierStack material = getMaterials(slot);
            ItemIdentifier item = material.getItem();
            if (!isCraftedByAnother(item)) {
                continue;
            }
            int onHand = inFlight[slot] + bufferedFor(slot) + machineStock(item);
            if (onHand < material.getStackSize()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Yields the machine while an intermediate is missing, but not for ever.
     * <p>
     * The readiness test can be wrong in one direction: an item another crafter makes may *also* be sitting in storage,
     * in which case the planner sourced it from a provider and it will only move once the gate opens. Waiting on it
     * would stall a craft that was never blocked. So after {@link #INTERMEDIATE_WAIT_TICKS} the crafter goes ahead
     * anyway, and the handover rule below takes over if that turns out to be the wrong guess.
     */
    private boolean waitedLongEnoughForIntermediates() {
        if (intermediatesReady()) {
            waitingForIntermediatesSince = 0;
            return true;
        }
        if (waitingForIntermediatesSince == 0) {
            waitingForIntermediatesSince = now();
        }
        return now() - waitingForIntermediatesSince >= INTERMEDIATE_WAIT_TICKS;
    }

    private long waitingForIntermediatesSince = 0;

    /**
     * Whether some other loaded Smart Crafter makes this item, i.e. it is an intermediate rather than a raw material.
     */
    private boolean isCraftedByAnother(ItemIdentifier item) {
        for (ModuleSmartCrafter crafter : ModuleSmartCrafter.AllCrafters) {
            if (crafter == this) {
                continue;
            }
            for (ItemIdentifierStack result : crafter.getConfiguredCraftResults()) {
                if (result.getItem().equals(item)) {
                    return true;
                }
            }
        }
        return false;
    }

    private int bufferStock(ItemIdentifier item) {
        int total = 0;
        for (ModuleCraftingBuffer buffer : ModuleCraftingBuffer.AllBuffers) {
            total += buffer.getAvailable(item);
        }
        return total;
    }

    private int machineStock(ItemIdentifier item) {
        IInventory inv = _service.getRealInventory();
        if (inv == null) {
            return 0;
        }
        IInventoryUtil util = SimpleServiceLocator.inventoryUtilFactory
                .getInventoryUtil(inv, _service.inventoryOrientation());
        return util == null ? 0 : util.itemCount(item);
    }

    /**
     * Takes the machine's claim if needed. An owner stops releasing new sets once another crafter waits, but only after
     * it released at least once in its turn, so two crafters can't hand the machine back and forth without crafting
     * anything.
     */
    private boolean mayReleaseSets() {
        MachineClaims.Key machine = machineKey();
        if (machine == null) {
            return false;
        }
        if (claimedMachine == null) {
            if (!MachineClaims.tryClaim(machine, this, now())) {
                awaitedMachine = machine;
                return false;
            }
            claimedMachine = machine;
            awaitedMachine = null;
            releasedThisTurn = false;
            claimTakenTick = now();
            sweepBeforeRelease = true;
        }
        return !releasedThisTurn || !MachineClaims.hasOthersWaiting(claimedMachine, this);
    }

    private void releaseSets(int sets) {
        int manual = Math.min(sets, pendingManualSets);
        pendingManualSets -= manual;
        for (int slot = 0; slot < INGREDIENT_SLOTS; slot++) {
            if (isGatedSlot(slot)) {
                allowance[slot] += sets * getMaterials(slot).getStackSize();
            }
        }
        // A set from "Request set" is finished once its ingredients are delivered: it exists to hand the machine one
        // more set, so no result is expected and none is taken back out. Only ordered sets are counted as in flight.
        setsReleased += sets - manual;
        // Anything the buffers are already holding for this recipe comes now; the rest is left to the providers.
        for (int slot = 0; slot < INGREDIENT_SLOTS; slot++) {
            if (isGatedSlot(slot)) {
                pullFromBuffers(slot, sets * getMaterials(slot).getStackSize());
            }
        }
        releasedThisTurn = true;
        lastProgressTick = now();
    }

    /**
     * Gives up released sets whose results never came back to this module (taken by a player, auto-output, a chanced
     * output that produced nothing). Only when everything was delivered, none of the ingredients are left in the
     * machine and nothing happened for a while. A slow recipe that is still running is harmless to give up: its result
     * is still counted when it comes out, and no extra ingredients arrive because providers only send what was ordered.
     */
    private void giveUpStuckSets() {
        if (setsReleased == 0 || !nothingInFlight() || now() - lastProgressTick < STUCK_TICKS) {
            return;
        }
        for (int slot = 0; slot < INGREDIENT_SLOTS; slot++) {
            if (allowance[slot] > 0) {
                return;
            }
        }
        if (machineHoldsIngredients()) {
            return;
        }
        setsReleased = 0;
        clearResultProgress();
    }

    /** Sets are out, nothing is on its way, and nothing has happened for a while: the ingredients are not coming. */
    private boolean isBlockedWaitingForIngredients() {
        return setsReleased > 0 && nothingInFlight() && now() - lastProgressTick >= STUCK_TICKS;
    }

    /**
     * Whether ingredients are still granted but unsent, or sent and not yet arrived. Bounded by {@link #STUCK_TICKS} so
     * a provider that never delivers can't hold the gate open for good.
     */
    private boolean deliveryInProgress() {
        if (now() - lastProgressTick >= STUCK_TICKS) {
            return false;
        }
        for (int slot = 0; slot < INGREDIENT_SLOTS; slot++) {
            if (allowance[slot] > 0 || inFlight[slot] > 0) {
                return true;
            }
        }
        return false;
    }

    private boolean nothingInFlight() {
        for (int slot = 0; slot < INGREDIENT_SLOTS; slot++) {
            if (inFlight[slot] > 0) {
                return false;
            }
        }
        return true;
    }

    private void resetGate() {
        for (int slot = 0; slot < INGREDIENT_SLOTS; slot++) {
            allowance[slot] = 0;
            inFlight[slot] = 0;
        }
        setsReleased = 0;
        pendingManualSets = 0;
        clearResultProgress();
        setTooLarge = false;
        releaseClaim();
        stopWaiting();
        clearBufferedCredit();
    }

    private void releaseClaim() {
        if (claimedMachine != null) {
            MachineClaims.Key machine = claimedMachine;
            claimedMachine = null;
            MachineClaims.release(machine, this, now());
        }
        // Leftover permission from finished sets must not carry over into the next turn.
        for (int slot = 0; slot < INGREDIENT_SLOTS; slot++) {
            allowance[slot] = 0;
        }
    }

    private void stopWaiting() {
        if (awaitedMachine != null) {
            MachineClaims.stopWaiting(awaitedMachine, this);
            awaitedMachine = null;
        }
    }

    /** The block the chassis faces, which is where this module's ingredients go. */
    /** The tank of the block this module faces, or null when it doesn't hold fluid. */
    private IFluidHandler facedTank() {
        ForgeDirection dir = _service == null ? null : _service.inventoryOrientation();
        if (dir == null || dir == ForgeDirection.UNKNOWN || getWorld() == null) {
            return null;
        }
        TileEntity tile = getWorld().getTileEntity(getX() + dir.offsetX, getY() + dir.offsetY, getZ() + dir.offsetZ);
        return tile instanceof IFluidHandler ? (IFluidHandler) tile : null;
    }

    private MachineClaims.Key machineKey() {
        ForgeDirection dir = _service.inventoryOrientation();
        if (dir == null || dir == ForgeDirection.UNKNOWN || getWorld() == null) {
            return null;
        }
        return new MachineClaims.Key(
                getWorld().provider.dimensionId,
                getX() + dir.offsetX,
                getY() + dir.offsetY,
                getZ() + dir.offsetZ);
    }

    private long now() {
        return getWorld() == null ? 0 : getWorld().getTotalWorldTime();
    }

    /**
     * Results this crafter still has to produce. Orders in a chassis are shared by all its modules, so only orders for
     * this module's result are counted. Extras (leftovers of a partial set) are included, since the machine makes them
     * too.
     */
    private int outstandingResults(ItemIdentifierStack result) {
        int total = 0;
        for (LogisticsItemOrder order : _service.getItemOrderManager()) {
            if (order.getType() != ResourceType.CRAFTING && order.getType() != ResourceType.EXTRA) {
                continue;
            }
            if (order.getResource().matches(result.getItem(), IResource.MatchSettings.NORMAL)) {
                total += order.getAmount();
            }
        }
        return total;
    }

    private boolean isOurResult(ItemIdentifier item, ItemIdentifierStack result) {
        if (item.equals(result.getItem())) {
            return true;
        }
        if (getUpgradeManager().isFuzzyUpgrade() && outputFuzzyFlags.getBitSet().nextSetBit(0) != -1) {
            DictResource dict = new DictResource(result, null);
            dict.loadFromBitSet(outputFuzzyFlags.getBitSet());
            return dict.matches(item, IResource.MatchSettings.NORMAL);
        }
        return false;
    }

    /** Ingredient slots delivered to this module's own machine. Slots sent to a satellite aren't gated. */
    private boolean isGatedSlot(int slot) {
        ItemIdentifierStack material = getMaterials(slot);
        if (material == null || material.getStackSize() <= 0) {
            return false;
        }
        if (isFluidSlot(slot)) {
            return false; // goes to a fluid satellite, never into this module's machine
        }
        // Gate by the same per-slot decision the router uses, not the physical Advanced Satellite upgrade: this
        // crafter is always per-slot (see usesPerSlotSatellites), so a slot bound to a satellite (id != 0) is
        // delivered there and never occupies this machine's input slots - it must not count against the room check
        // or hold its release. Gating by the upgrade flag instead left every item slot gated whenever the upgrade
        // was absent, so the room sim tripped and nothing was ever released.
        if (usesPerSlotSatellites()) {
            return advancedSatelliteIdArray[slot] == 0;
        }
        return satelliteId == 0 || slot < 6;
    }

    private static int ingredientSlot(IAdditionalTargetInformation info) {
        if (!(info instanceof CraftingChassieInformation)) {
            return -1;
        }
        int slot = ((CraftingChassieInformation) info).getCraftingSlot();
        return slot >= 0 && slot < INGREDIENT_SLOTS ? slot : -1;
    }

    /* Room simulation */

    /**
     * Simulates inserting whole recipe sets into the machine, on top of what it already holds and what is already on
     * its way to it.
     *
     * @param emptyMachine ignore the machine's current contents (used to tell "full right now" from "never fits")
     * @return how many sets fit, up to maxSets
     */
    private int countSetsThatFit(int maxSets, boolean emptyMachine) {
        IInventory inv = _service.getRealInventory();
        if (inv == null) {
            return 0;
        }
        ForgeDirection side = insertionSide();
        int[] slots = insertableSlots(inv, side);
        ItemStack[] simulated = new ItemStack[slots.length];
        if (!emptyMachine) {
            for (int i = 0; i < slots.length; i++) {
                ItemStack stack = inv.getStackInSlot(slots[i]);
                simulated[i] = stack == null ? null : stack.copy();
            }
        }

        int[] perSet = new int[INGREDIENT_SLOTS];
        for (int slot = 0; slot < INGREDIENT_SLOTS; slot++) {
            if (isGatedSlot(slot)) {
                perSet[slot] = amountPerSet(getMaterials(slot).getItem());
            }
        }

        for (int slot = 0; slot < INGREDIENT_SLOTS; slot++) {
            int pending = allowance[slot] + inFlight[slot];
            if (pending > 0 && isGatedSlot(slot)
                    && !place(inv, side, slots, simulated, getMaterials(slot), pending, perSet[slot])) {
                return 0;
            }
        }

        int sets = 0;
        while (sets < maxSets) {
            for (int slot = 0; slot < INGREDIENT_SLOTS; slot++) {
                if (isGatedSlot(slot)) {
                    ItemIdentifierStack material = getMaterials(slot);
                    if (!place(inv, side, slots, simulated, material, material.getStackSize(), perSet[slot])) {
                        return sets;
                    }
                }
            }
            sets++;
        }
        return sets;
    }

    /** Total of this item one recipe set needs, across all gated slots that hold it. */
    private int amountPerSet(ItemIdentifier item) {
        int total = 0;
        for (int slot = 0; slot < INGREDIENT_SLOTS; slot++) {
            if (isGatedSlot(slot) && getMaterials(slot).getItem().equals(item)) {
                total += getMaterials(slot).getStackSize();
            }
        }
        return total;
    }

    /**
     * Pulls this recipe's own leftover items out of the machine the chassis faces and sends them into the network,
     * usually to storage. Other recipes' items are left alone here ({@link #sweepAllRecipesOnMachine} handles those, at
     * claim time). Never runs while another Smart Crafter holds the machine, since two recipes can share an ingredient.
     * <p>
     * Results and ingredients are taken out differently:
     * <ul>
     * <li>The result only through the machine's normal extraction rules, i.e. from its output slots. Some machines keep
     * a player-set copy of the result elsewhere (e.g. the auto-chisel's target slot), which must stay.</li>
     * <li>Ingredients directly from the slot, bypassing the extraction rules: GT machines don't let anything pull from
     * their input slots, so leftover ingredients could otherwise never be cleared. To keep this from touching template
     * or config slots (molds, circuits, targets), only input slots are emptied: slots a pipe could insert into from
     * some side ({@link #isInputSlot}).</li>
     * </ul>
     *
     * @return false if the machine couldn't be swept right now (not reachable, or in use by another crafter)
     */
    private boolean sweepMachine() {
        if (!cleanupEnabled) {
            return true; // nothing to retry
        }
        IInventory inv = _service.getRealInventory();
        MachineClaims.Key machine = machineKey();
        if (inv == null || machine == null || MachineClaims.isHeldByOther(machine, this, now())) {
            return false;
        }
        int stacks = sweepResult(inv);
        stacks += sweepIngredients(inv, MAX_SWEEP_STACKS - stacks);
        if (stacks > 0) {
            _service.getCacheHolder().trigger(CacheTypes.Inventory);
        }
        return true;
    }

    /**
     * Clears what every Smart Crafter recipe on this machine left in it, this module's included. Called once per claim,
     * before this module releases anything, so none of the new sets' ingredients are in the machine yet and a shared
     * ingredient can't be taken by mistake.
     * <p>
     * Each recipe is swept by its own module, so the items leave through that module's chassis and follow that recipe's
     * Cleanup setting. Only Smart Crafter recipes are touched: an ordinary crafting module takes no claim, so it could
     * be mid-craft on this machine. A result some order is still waiting for is left in place: its crafter extracts it
     * for that order, and sending it to storage would strand the order.
     * <p>
     * A recipe that still has work (this module's included, since it only claimed because it has a job) keeps its
     * ingredients: it handed the machine over or is taking it back mid-job, those ingredients were delivered for that
     * job, and no provider would send them again. Only a recipe with nothing left to make (a cancelled or finished job)
     * has its ingredients cleared.
     */
    private void sweepAllRecipesOnMachine() {
        MachineClaims.Key machine = claimedMachine;
        if (machine == null) {
            return;
        }
        sweepLeftovers(!recipeHasWork());
        for (ModuleSmartCrafter other : ModuleSmartCrafter.AllCrafters) {
            if (other != this && other._service != null
                    && other.getWorld() != null
                    && machine.equals(other.machineKey())) {
                other.sweepLeftovers(!other.recipeHasWork());
            }
        }
    }

    /**
     * This recipe's part of {@link #sweepAllRecipesOnMachine}: results no order is waiting for, and its ingredients too
     * if asked.
     */
    private void sweepLeftovers(boolean ingredientsToo) {
        IInventory inv = _service.getRealInventory();
        if (!cleanupEnabled || inv == null) {
            return;
        }
        int stacks = 0;
        for (int i = 0; i < OUTPUT_SLOTS && stacks < MAX_SWEEP_STACKS; i++) {
            ItemIdentifierStack output = getOutput(i);
            if (output != null && outstandingResults(output) <= 0) {
                stacks += takeOut(inv, output.getItem(), MAX_SWEEP_STACKS - stacks);
            }
        }
        if (ingredientsToo) {
            stacks += sweepIngredients(inv, MAX_SWEEP_STACKS - stacks);
        }
        if (stacks > 0) {
            _service.getCacheHolder().trigger(CacheTypes.Inventory);
        }
    }

    /** @return how many stacks were taken out */
    private int sweepResult(IInventory inv) {
        int stacks = 0;
        for (int i = 0; i < OUTPUT_SLOTS && stacks < MAX_SWEEP_STACKS; i++) {
            ItemIdentifierStack output = getOutput(i);
            if (output != null) {
                stacks += takeOut(inv, output.getItem(), MAX_SWEEP_STACKS - stacks);
            }
        }
        return stacks;
    }

    /**
     * Pulls an item out of the machine through its normal extraction rules and sends it to storage. Only the machine's
     * output slots are touched: some machines keep a player-set copy of the result elsewhere (e.g. the auto-chisel's
     * target slot), which must stay.
     *
     * @return how many stacks were taken out
     */
    private int takeOut(IInventory inv, ItemIdentifier item, int maxStacks) {
        IInventory extractable = inv instanceof ISidedInventory
                ? new SidedInventoryMinecraftAdapter((ISidedInventory) inv, ForgeDirection.UNKNOWN, true)
                : inv;
        IInventoryUtil util = SimpleServiceLocator.inventoryUtilFactory
                .getInventoryUtil(extractable, _service.inventoryOrientation());
        int stacks = 0;
        int left = util.itemCount(item);
        while (left > 0 && stacks < maxStacks) {
            ItemStack taken = util.getMultipleItems(item, Math.min(left, item.getMaxStackSize()));
            if (taken == null || taken.stackSize <= 0) {
                break;
            }
            left -= taken.stackSize;
            stacks++;
            sendSwept(taken);
        }
        return stacks;
    }

    /** @return how many stacks were taken out */
    private int sweepIngredients(IInventory inv, int maxStacks) {
        ForgeDirection ourSide = insertionSide();
        int stacks = 0;
        for (int machineSlot = 0; machineSlot < inv.getSizeInventory(); machineSlot++) {
            if (stacks >= maxStacks) {
                break;
            }
            ItemStack stack = inv.getStackInSlot(machineSlot);
            if (stack == null || stack.stackSize <= 0) {
                continue;
            }
            if (!isIngredient(stack) || !isInputSlot(inv, machineSlot, stack, ourSide)) {
                continue;
            }
            ItemStack taken = inv.decrStackSize(machineSlot, stack.stackSize);
            if (taken != null && taken.stackSize > 0) {
                stacks++;
                sendSwept(taken);
            }
        }
        return stacks;
    }

    /**
     * Whether a pipe could insert this stack into the slot from any side, i.e. it's an input slot rather than a
     * template, config or circuit slot. Not just from our side: GT machines refuse input on their output face (unless
     * "allow input from output side" is on), on their main face and on faces with a blocking cover, and the chassis
     * usually sits on the output face to pull results. Our side is tried first since it usually answers yes.
     */
    private static boolean isInputSlot(IInventory inv, int slot, ItemStack stack, ForgeDirection ourSide) {
        if (!(inv instanceof ISidedInventory)) {
            return inv.isItemValidForSlot(slot, stack);
        }
        if (isInsertableFrom(inv, ourSide, slot, stack)) {
            return true;
        }
        for (ForgeDirection side : ForgeDirection.VALID_DIRECTIONS) {
            if (side != ourSide && isInsertableFrom(inv, side, slot, stack)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isInsertableFrom(IInventory inv, ForgeDirection side, int slot, ItemStack stack) {
        for (int accessible : insertableSlots(inv, side)) {
            if (accessible == slot) {
                return canInsert(inv, side, slot, stack);
            }
        }
        return false;
    }

    private boolean isIngredient(ItemStack stack) {
        for (int slot = 0; slot < INGREDIENT_SLOTS; slot++) {
            if (isGatedSlot(slot) && isSameItem(stack, getMaterials(slot).getItem().makeNormalStack(1))) {
                return true;
            }
        }
        return false;
    }

    private void sendSwept(ItemStack stack) {
        _service.queueRoutedItem(SimpleServiceLocator.routedItemHelper.createNewTravelItem(stack), ForgeDirection.UP);
    }

    /** Whether any of this recipe's gated ingredients are still sitting in the machine's input slots. */
    private boolean machineHoldsIngredients() {
        IInventory inv = _service.getRealInventory();
        if (inv == null) {
            return false;
        }
        int[] slots = insertableSlots(inv, insertionSide());
        for (int slot = 0; slot < INGREDIENT_SLOTS; slot++) {
            if (!isGatedSlot(slot)) {
                continue;
            }
            ItemStack proto = getMaterials(slot).getItem().makeNormalStack(1);
            for (int machineSlot : slots) {
                if (isSameItem(inv.getStackInSlot(machineSlot), proto)) {
                    return true;
                }
            }
        }
        return false;
    }

    private ForgeDirection insertionSide() {
        return getUpgradeManager().hasSneakyUpgrade() ? getUpgradeManager().getSneakyOrientation()
                : _service.inventoryOrientation().getOpposite();
    }

    private static int[] insertableSlots(IInventory inv, ForgeDirection side) {
        if (inv instanceof ISidedInventory) {
            int[] slots = ((ISidedInventory) inv).getAccessibleSlotsFromSide(side.ordinal());
            return slots == null ? new int[0] : slots;
        }
        int[] slots = new int[inv.getSizeInventory()];
        for (int i = 0; i < slots.length; i++) {
            slots[i] = i;
        }
        return slots;
    }

    /**
     * Places count of the material into the simulated slots, filling matching stacks before empty slots.
     * <p>
     * Many GT machines won't hold more than one stack of an item type even when they have free slots, so the machine is
     * never given more than one stack of an item in total, or one set's worth if the recipe needs more than that.
     *
     * @param perSet how many of this item one recipe set needs
     * @return false if it doesn't all fit
     */
    private static boolean place(IInventory inv, ForgeDirection side, int[] slots, ItemStack[] simulated,
            ItemIdentifierStack material, int count, int perSet) {
        ItemStack proto = material.getItem().makeNormalStack(1);
        int limit = Math.min(inv.getInventoryStackLimit(), proto.getMaxStackSize());
        int held = 0;
        for (ItemStack stack : simulated) {
            if (isSameItem(stack, proto)) {
                held += stack.stackSize;
            }
        }
        if (held + count > Math.max(limit, perSet)) {
            return false;
        }
        for (int i = 0; i < slots.length && count > 0; i++) {
            ItemStack existing = simulated[i];
            if (isSameItem(existing, proto) && canInsert(inv, side, slots[i], proto)) {
                int added = Math.min(count, limit - existing.stackSize);
                if (added > 0) {
                    existing.stackSize += added;
                    count -= added;
                }
            }
        }
        for (int i = 0; i < slots.length && count > 0; i++) {
            if (simulated[i] == null && canInsert(inv, side, slots[i], proto)) {
                int added = Math.min(count, limit);
                simulated[i] = proto.copy();
                simulated[i].stackSize = added;
                count -= added;
            }
        }
        return count <= 0;
    }

    private static boolean isSameItem(ItemStack stack, ItemStack proto) {
        return stack != null && stack.isItemEqual(proto) && ItemStack.areItemStackTagsEqual(stack, proto);
    }

    private static boolean canInsert(IInventory inv, ForgeDirection side, int slot, ItemStack stack) {
        if (!inv.isItemValidForSlot(slot, stack)) {
            return false;
        }
        return !(inv instanceof ISidedInventory) || ((ISidedInventory) inv).canInsertItem(slot, stack, side.ordinal());
    }
}
