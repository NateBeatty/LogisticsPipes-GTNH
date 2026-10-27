package logisticspipes.modules;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import net.minecraft.client.renderer.texture.IIconRegister;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.ISidedInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.IIcon;
import net.minecraft.world.World;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidTankInfo;
import net.minecraftforge.fluids.IFluidHandler;

import cpw.mods.fml.common.Loader;
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
import logisticspipes.pipes.PipeSmartSatellite;
import logisticspipes.proxy.MainProxy;
import logisticspipes.proxy.SimpleServiceLocator;
import logisticspipes.proxy.gregtech.GregTechInputTank;
import logisticspipes.proxy.gregtech.GregTechMultiStack;
import logisticspipes.proxy.gregtech.GregTechNonConsumed;
import logisticspipes.request.FluidCraftingTemplate;
import logisticspipes.request.IReqCraftingTemplate;
import logisticspipes.request.RequestTree;
import logisticspipes.request.resources.DictResource;
import logisticspipes.request.resources.FluidResource;
import logisticspipes.request.resources.IResource;
import logisticspipes.request.resources.ItemResource;
import logisticspipes.routing.FluidLogisticsPromise;
import logisticspipes.routing.IRouter;
import logisticspipes.routing.LogisticsPromise;
import logisticspipes.routing.order.CraftingJob;
import logisticspipes.routing.order.CraftingJobs;
import logisticspipes.routing.order.IOrderInfoProvider;
import logisticspipes.routing.order.IOrderInfoProvider.ResourceType;
import logisticspipes.routing.order.LogisticsFluidOrder;
import logisticspipes.routing.order.LogisticsFluidOrderManager;
import logisticspipes.routing.order.LogisticsItemOrder;
import logisticspipes.routing.order.LogisticsOrderManager;
import logisticspipes.utils.AdjacentTile;
import logisticspipes.utils.CacheHolder.CacheTypes;
import logisticspipes.utils.DelayedGeneric;
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
 * Every ingredient goes through the gate, whether it is delivered to the machine this module faces or to the machine of
 * a Smart Satellite: the room check is run for each of those machines and a set is only released when all of them have
 * room for it.
 * <p>
 * Sets are only released while this module holds the claim ({@link MachineClaims}) on every one of those machines, so
 * two Smart Crafters sharing a machine take turns instead of mixing their ingredients. When another crafter is waiting,
 * the owner stops releasing new sets, lets the ones in the machines finish and hands them over.
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

    /**
     * The machines this module currently holds the claim for: the one it faces and the machine of every Smart Satellite
     * its ingredients go to. Empty when it holds none; it never holds only some of them.
     */
    private List<MachineClaims.Key> claimedMachines = Collections.emptyList();
    /**
     * Where other Smart Crafter recipes' results can turn up in the machines this module holds, worked out once per
     * claim ({@link #collectForeignSources}). Empty when it holds none.
     */
    private List<ForeignSource> foreignSources = Collections.emptyList();
    /** Whether this module is in line for its machines. */
    private boolean awaitingMachines = false;
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
     * A "Request set" set was handed over and every one of its ingredients was found, so its allowance is still owed
     * deliveries even when no order is left. Without this the gate would reset under it and strand them.
     */
    private boolean manualSetOut = false;
    /** Whether the last "Request set" found every ingredient; a set missing one can't run and isn't waited for. */
    private boolean manualSetComplete = false;

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
    /**
     * Ingredients that wear down or hold charge match their item whatever its damage and NBT, so a Lapotron Crystal
     * from storage, one fresh out of its machine and the one the recipe was set up with all count as the same thing.
     * Off by default: until it is ticked, ingredients match exactly, as they always have.
     */
    private boolean ignoreDurability = false;
    /**
     * Any result addressed to a Smart Satellite may come out of any of this recipe's output satellites, not only its
     * own. For machines that pick the output themselves, such as a Large Chemical Reactor whose output hatches aren't
     * locked to a fluid: each fluid lands in whichever hatch has room. Results addressed to no satellite still come out
     * of the faced machine. Off by default: until it is ticked, each result comes out of its own satellite only.
     */
    private boolean sharedOutputSatellites = false;

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
     * Which Smart Satellite an output comes out of, for a recipe whose results appear somewhere other than the block
     * this module faces (a multiblock's output bus). Read by {@link #resultSourcesFor} and the cleanup sweeps.
     */
    private final int[] outputSatelliteId = new int[OUTPUT_SLOTS];

    /**
     * Ingredients the NEI-imported recipe needs in the machine but doesn't consume (molds, lenses, shapes, programmed
     * circuits). They are not requested; they are only looked for, for the gui's warning line.
     */
    private final List<ItemIdentifier> nonConsumed = new ArrayList<>();
    /**
     * The first result when {@link #nonConsumed} was imported. Once the player sets up a different recipe by hand the
     * list no longer belongs to it, and the warning stays quiet.
     */
    private ItemIdentifier nonConsumedFor = null;

    private static final boolean GREGTECH = Loader.isModLoaded("gregtech");

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
     * The item ingredients this module would take from a buffer: all of its gated item slots, whether they go to its
     * own machine or to a Smart Satellite's. Fluids have buffers of their own.
     */
    public Set<ItemIdentifier> getIntermediates() {
        Set<ItemIdentifier> items = new HashSet<>();
        for (int slot = 0; slot < INGREDIENT_SLOTS; slot++) {
            if (isGatedSlot(slot)) {
                ItemIdentifier material = getMaterials(slot).getItem();
                items.add(material);
                if (loosens(material)) {
                    // The router also looks interests up by this key, so it catches every damage and NBT of the item.
                    items.add(material.getUndamaged().getIgnoringNBT());
                }
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
            if (isGatedSlot(slot) && sameIngredient(getMaterials(slot).getItem(), item)) {
                gateDirty = true;
                return;
            }
        }
    }

    /**
     * The routing itself lives in {@link ModuleCrafter#routeForResult}, which normal crafters share so their results
     * can't slip into a Smart Crafter's machine early either. This only remembers a held result for the status view.
     */
    @Override
    protected ResultRoute routeForResult(LogisticsItemOrder order) {
        ResultRoute route = super.routeForResult(order);
        resultHeld = route == ResultRoute.HOLD;
        return route;
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

    /**
     * Items sent to a buffer for this module never got there (lost, or didn't fit). Takes up to {@code max} of this
     * module's credit for the item back, since no stock stands behind it, and asks for that many again through the
     * crafter's lost-item queue, tagged with the slot so the gate tracks them as usual.
     *
     * @return how much credit was taken back
     */
    public int itemBufferDeliveryLost(ItemIdentifier item, int max) {
        int taken = 0;
        for (int slot = 0; slot < INGREDIENT_SLOTS && taken < max; slot++) {
            ItemIdentifierStack material = getMaterials(slot);
            if (buffered[slot] <= 0 || material == null || !sameIngredient(material.getItem(), item)) {
                continue;
            }
            int take = Math.min(max - taken, buffered[slot]);
            buffered[slot] -= take;
            taken += take;
            // TEMP [ItemGate DIAG]
            itemGateDiag("BUFFER DELIVERY LOST " + take + "x" + diagItemName(item) + " for s" + slot + ", re-requested",
                    false);
            // Which request it was for is not known here; the re-request runs as a job of its own.
            _lostItems.add(
                    new DelayedGeneric<>(new LostItem(item.makeStack(take), ingredientInformation(slot), null), 5000));
        }
        if (taken > 0) {
            gateDirty = true;
        }
        return taken;
    }

    /**
     * For a provider about to send an ingredient: the crafting buffer to park it in because the Smart Crafter it is for
     * has its gate shut, or null to send it as usual (not a gated Smart Crafter slot, gate open, or no buffer has
     * room). The buffer chosen is the one nearest the machine the slot feeds. The provider credits the crafter with
     * {@link #creditBuffered} for what it sends there.
     */
    public static ModuleCraftingBuffer bufferForShutGate(IGatedItemSink gate, ItemIdentifier item,
            IAdditionalTargetInformation info) {
        ModuleSmartCrafter crafter = gatedCrafter(gate, info);
        if (crafter == null) {
            return null;
        }
        int slot = ingredientSlot(info);
        // Open means what getGatedAllowance says: a grant, and the claim to use it.
        if (slot < 0 || !crafter.isGatedSlot(slot) || (crafter.allowance[slot] > 0 && crafter.holdsClaim())) {
            return null;
        }
        IRouter target = crafter.slotRouter(slot);
        return target == null ? null : ModuleCraftingBuffer.withRoomFor(item, target);
    }

    /**
     * The Smart Crafter behind a gate an order is addressed to: the gate itself when the order goes to a Smart
     * Crafter's own machine, or the crafter that asked for it when it goes to a Smart Satellite (which several crafters
     * can share). Null for anything else, which isn't gated.
     */
    public static ModuleSmartCrafter gatedCrafter(IGatedItemSink gate, IAdditionalTargetInformation info) {
        if (gate instanceof ModuleSmartCrafter) {
            return (ModuleSmartCrafter) gate;
        }
        if (gate instanceof PipeSmartSatellite) {
            return ((PipeSmartSatellite) gate).crafterFor(info);
        }
        return null;
    }

    /* Ingredient target information */

    /**
     * Ingredient slot information that also names the Smart Crafter that asked for the ingredient. The slot alone isn't
     * enough once a Smart Satellite is involved: several crafters can send to one satellite, and the satellite has to
     * pass gate questions, arrivals and losses to the right one. It is still a {@link CraftingChassieInformation}, so
     * everything that reads the slot reads it as before.
     */
    public static final class SmartSlotInformation extends CraftingChassieInformation {

        /** Weak, so an order that outlives its module doesn't keep it alive. */
        private final WeakReference<ModuleSmartCrafter> crafter;

        public SmartSlotInformation(int craftingSlot, int moduleSlot, ModuleSmartCrafter crafter) {
            super(craftingSlot, moduleSlot);
            this.crafter = new WeakReference<>(crafter);
        }

        public ModuleSmartCrafter getCrafter() {
            return crafter.get();
        }
    }

    /** The crafter an ingredient's target information names, or null if it names none. */
    public static ModuleSmartCrafter crafterOf(IAdditionalTargetInformation info) {
        return info instanceof SmartSlotInformation ? ((SmartSlotInformation) info).getCrafter() : null;
    }

    @Override
    protected IAdditionalTargetInformation ingredientInformation(int slot) {
        return new SmartSlotInformation(slot, getPositionInt(), this);
    }

    /**
     * A lost or bounced ingredient is asked for again where it was meant to go: the slot's Smart Satellite, or this
     * module's own machine.
     */
    @Override
    protected IRequestItems lostItemTarget(IAdditionalTargetInformation info) {
        int slot = ingredientSlot(info);
        if (slot >= 0 && advancedSatelliteIdArray[slot] != 0) {
            PipeSmartSatellite sat = smartSatelliteForId(advancedSatelliteIdArray[slot]);
            if (sat != null) {
                return sat;
            }
        }
        return super.lostItemTarget(info);
    }

    /** What this module may take from the buffers for a slot right now: its credit, as far as the stock goes. */
    private int bufferedFor(int slot) {
        if (buffered[slot] <= 0) {
            return 0;
        }
        int stock = 0;
        for (ModuleCraftingBuffer buffer : ModuleCraftingBuffer.AllBuffers) {
            for (ItemIdentifier kind : bufferedKinds(buffer, slot)) {
                stock += buffer.getAvailable(kind);
            }
        }
        return Math.min(buffered[slot], stock);
    }

    /**
     * The item kinds in a buffer that fill this slot: just the slot's item, or with Ignore durability/charge every
     * damage and NBT of it the buffer holds.
     */
    private List<ItemIdentifier> bufferedKinds(ModuleCraftingBuffer buffer, int slot) {
        ItemIdentifier material = getMaterials(slot).getItem();
        if (!loosens(material)) {
            return Collections.singletonList(material);
        }
        List<ItemIdentifier> kinds = new ArrayList<>();
        for (ItemIdentifier held : buffer.getStock().keySet()) {
            if (sameIngredient(material, held)) {
                kinds.add(held);
            }
        }
        return kinds;
    }

    /** How much of an item this module has credit for across its slots, for a buffer working out its orphans. */
    public int getBufferedCredit(ItemIdentifier item) {
        int total = 0;
        for (int slot = 0; slot < INGREDIENT_SLOTS; slot++) {
            if (buffered[slot] > 0 && getMaterials(slot) != null
                    && sameIngredient(getMaterials(slot).getItem(), item)) {
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
     * Takes what the buffers are holding for this set, up to this module's credit, and sends it where the slot's
     * ingredients go: this module's machine, or the slot's Smart Satellite. Buffered intermediates have no provider to
     * ask, so unlike raw materials they have to be pulled: the gate opening alone would never move them.
     */
    private void pullFromBuffers(int slot, int wanted) {
        wanted = Math.min(wanted, buffered[slot]);
        if (wanted <= 0 || ModuleCraftingBuffer.AllBuffers.isEmpty()) {
            return;
        }
        IRouter target = slotRouter(slot);
        if (target == null) {
            return;
        }
        int left = wanted;
        // Nearest first, so stock spread over several buffers comes from the closest one before the others.
        for (ModuleCraftingBuffer buffer : ModuleCraftingBuffer.nearestTo(target)) {
            for (ItemIdentifier item : bufferedKinds(buffer, slot)) {
                if (left <= 0) {
                    return;
                }
                int sent = buffer.sendTo(item, left, target.getSimpleID(), ingredientInformation(slot));
                if (sent > 0) {
                    left -= sent;
                    buffered[slot] = Math.max(0, buffered[slot] - sent);
                    allowance[slot] = Math.max(0, allowance[slot] - sent);
                    inFlight[slot] += sent;
                    lastProgressTick = now();
                    // TEMP [ItemGate DIAG]
                    itemGateDiag("PULLED " + sent + "x" + diagItemName(item) + " from buffer for s" + slot, false);
                }
            }
        }
    }

    /**
     * Per ingredient slot, how much of a fluid sub-crafters have sent to the fluid buffers for this module and it
     * hasn't pulled yet, in millibuckets. The fluid twin of {@code buffered}: the credit is kept here, in the crafter,
     * never in the buffer. A pull pours it into the machine the slot's fluid goes to: this module's own, or the slot's
     * Smart Satellite's.
     */
    private final int[] fluidBuffered = new int[INGREDIENT_SLOTS];

    /** Whether this fluid slot goes through the gate. Every fluid slot does, wherever it pours. */
    private boolean isGatedFluidSlot(int slot) {
        return isFluidSlot(slot);
    }

    /** A sub-crafter sent a fluid meant for this module's own machine to a buffer, because the gate was shut. */
    public void creditFluidBuffered(FluidIdentifier fluid, int amount) {
        creditFluidBuffered(fluid, amount, null);
    }

    /**
     * {@link #creditFluidBuffered(FluidIdentifier, int)} for the slot pouring into {@code sat}'s machine (null: own).
     */
    private void creditFluidBuffered(FluidIdentifier fluid, int amount, PipeSmartSatellite sat) {
        int slot = fluidSlotFor(fluid, sat);
        if (slot < 0 || amount <= 0) {
            return;
        }
        fluidBuffered[slot] += amount;
        gateDirty = true;
    }

    /** How much of a fluid this module has credit for across its slots, for a buffer working out its orphans. */
    public int getBufferFluidCredit(FluidIdentifier fluid) {
        int total = 0;
        for (int slot = 0; slot < INGREDIENT_SLOTS; slot++) {
            if (fluidBuffered[slot] > 0 && isGatedFluidSlot(slot) && getFluidIngredient(slot).equals(fluid)) {
                total += fluidBuffered[slot];
            }
        }
        return total;
    }

    /** What this module may take from the fluid buffers for a slot now: its credit, as far as the stock goes. */
    private int fluidBufferedFor(int slot) {
        if (fluidBuffered[slot] <= 0) {
            return 0;
        }
        return Math.min(fluidBuffered[slot], fluidBufferStock(getFluidIngredient(slot)));
    }

    /** How much of a fluid all loaded fluid buffers together hold. */
    private int fluidBufferStock(FluidIdentifier fluid) {
        int total = 0;
        for (ModuleFluidCraftingBuffer buffer : ModuleFluidCraftingBuffer.AllBuffers) {
            total += buffer.getBufferFluidAvailable(fluid);
        }
        return total;
    }

    /**
     * Drops this module's fluid buffer credit, when it has nothing left to craft or is removed. What it no longer has
     * credit for is an orphan, so the buffers are told to return it now.
     */
    private void clearFluidBufferedCredit() {
        boolean had = false;
        for (int slot = 0; slot < INGREDIENT_SLOTS; slot++) {
            had |= fluidBuffered[slot] > 0;
            fluidBuffered[slot] = 0;
        }
        if (had) {
            ModuleFluidCraftingBuffer.returnAllOrphans();
        }
    }

    /**
     * Takes what the fluid buffers hold for a slot, up to this module's credit and the room the slot's tank still has,
     * and pours it into that tank: this module's machine, or the slot's Smart Satellite's. Like
     * {@link #pullFromBuffers}, buffered fluid has no provider to ask, so it has to be pulled once the gate opens; and
     * like the gate, it is capped by the tank's room so the delivery never bounces.
     */
    private void pullFluidFromBuffers(int slot, int wanted) {
        wanted = Math.min(wanted, fluidBuffered[slot]);
        if (wanted <= 0 || ModuleFluidCraftingBuffer.AllBuffers.isEmpty()) {
            return;
        }
        FluidIdentifier fluid = getFluidIngredient(slot);
        IRouter target = slotRouter(slot);
        if (fluid == null || target == null) {
            return;
        }
        wanted = Math.min(wanted, roomNotSpokenFor(endpointOf(slot), fluid, wanted));
        if (wanted <= 0) {
            return;
        }
        int left = wanted;
        for (ModuleFluidCraftingBuffer buffer : ModuleFluidCraftingBuffer.nearestTo(target)) {
            if (left <= 0) {
                break;
            }
            int sent = buffer.sendToFluid(fluid, left, target.getSimpleID());
            if (sent > 0) {
                left -= sent;
                fluidBuffered[slot] = Math.max(0, fluidBuffered[slot] - sent);
                allowance[slot] = Math.max(0, allowance[slot] - sent);
                inFlight[slot] += sent;
                lastProgressTick = now();
            }
        }
    }

    /**
     * How much of this fluid the gate allows into this module's own machine right now, in millibuckets: the
     * released-set credit ({@code allowance}) capped by the room the machine's tank actually has, so a full machine
     * reads zero and a producer buffers instead of flooding it. Integer.MAX_VALUE when no slot pouring into this
     * module's machine wants the fluid. Mirrors {@link #getGatedAllowance} on the item side, plus the tank-room check
     * that items get from the release-time sim.
     */
    public int getFluidGatedAllowance(FluidIdentifier fluid) {
        return fluidAllowance(fluid, null);
    }

    /** {@link #getFluidGatedAllowance} for the slot pouring into {@code sat}'s machine (null: this module's own). */
    private int fluidAllowance(FluidIdentifier fluid, PipeSmartSatellite sat) {
        int slot = fluidSlotFor(fluid, sat);
        if (slot < 0) {
            return Integer.MAX_VALUE;
        }
        // Shut while this module doesn't hold its machines, as for items (getGatedAllowance).
        int grant = holdsClaim() ? this.allowance[slot] : 0;
        if (grant <= 0) {
            return 0;
        }
        return Math.max(0, Math.min(grant, roomNotSpokenFor(sat, fluid, grant)));
    }

    /**
     * {@link #tankRoom} less what this module already has on its way to that tank. The tank only shows fluid once it
     * arrives, so without this every send before the first arrival saw the same room, and together they overfilled it:
     * the overflow bounced off the machine and was sent on to storage.
     */
    private int roomNotSpokenFor(PipeSmartSatellite sat, FluidIdentifier fluid, int wanted) {
        int coming = fluidInFlightTo(sat, fluid);
        int room = tankRoom(sat, fluid, (int) Math.min(Integer.MAX_VALUE, (long) wanted + coming));
        return Math.max(0, room - coming);
    }

    /** This fluid on its way to the machine {@code sat} pours into (null: this module's own), over all its slots. */
    private int fluidInFlightTo(PipeSmartSatellite sat, FluidIdentifier fluid) {
        int total = 0;
        for (int slot = 0; slot < INGREDIENT_SLOTS; slot++) {
            if (isGatedFluidSlot(slot) && goesTo(slot, sat) && fluid.equals(getFluidIngredient(slot))) {
                total += inFlight[slot];
            }
        }
        return total;
    }

    /**
     * A producer has checked {@link #getFluidGatedAllowance} and is sending fluid for this slot now, so that much stops
     * being grantable and starts being on its way.
     */
    public void onFluidGatedSend(FluidIdentifier fluid, int amount) {
        onFluidGatedSend(fluid, amount, null);
    }

    private void onFluidGatedSend(FluidIdentifier fluid, int amount, PipeSmartSatellite sat) {
        int slot = fluidSlotFor(fluid, sat);
        if (slot < 0 || amount <= 0) {
            return;
        }
        allowance[slot] = Math.max(0, allowance[slot] - amount);
        inFlight[slot] += amount;
        lastProgressTick = now();
    }

    /**
     * The fluid gate an order is subject to, or null when it has none: its consumer is a Smart Crafter's own machine,
     * or a Smart Satellite whose crafter is named by the order (or, failing that, the one crafter bound to the
     * satellite that wants this fluid). Lets a fluid producer gate, buffer and report a send without knowing which of
     * the two it is talking to.
     */
    public static FluidGate fluidGateFor(IRequestFluid consumer, IAdditionalTargetInformation info,
            FluidIdentifier fluid) {
        if (consumer instanceof ModuleSmartCrafter) {
            return new FluidGate((ModuleSmartCrafter) consumer, null);
        }
        if (!(consumer instanceof PipeSmartSatellite)) {
            return null;
        }
        PipeSmartSatellite sat = (PipeSmartSatellite) consumer;
        ModuleSmartCrafter crafter = crafterOf(info);
        if (crafter == null) {
            crafter = sat.crafterWantingFluid(fluid);
        }
        return crafter == null ? null : new FluidGate(crafter, sat);
    }

    /** One crafter's fluid gate for one machine: its own (no satellite) or a Smart Satellite's. */
    public static final class FluidGate {

        private final ModuleSmartCrafter crafter;
        private final PipeSmartSatellite sat;

        private FluidGate(ModuleSmartCrafter crafter, PipeSmartSatellite sat) {
            this.crafter = crafter;
            this.sat = sat;
        }

        /** See {@link ModuleSmartCrafter#getFluidGatedAllowance}. */
        public int allowance(FluidIdentifier fluid) {
            return crafter.fluidAllowance(fluid, sat);
        }

        /** See {@link ModuleSmartCrafter#onFluidGatedSend}. */
        public void onSend(FluidIdentifier fluid, int amount) {
            crafter.onFluidGatedSend(fluid, amount, sat);
        }

        /** See {@link ModuleSmartCrafter#creditFluidBuffered}. */
        public void creditBuffered(FluidIdentifier fluid, int amount) {
            crafter.creditFluidBuffered(fluid, amount, sat);
        }
    }

    /**
     * Room a machine's tank has for this fluid, up to {@code wanted}: this module's own (sat null) or a Smart
     * Satellite's, filled through the face the satellite sits on.
     */
    private int tankRoom(PipeSmartSatellite sat, FluidIdentifier fluid, int wanted) {
        if (fluid == null || wanted <= 0) {
            return 0;
        }
        if (sat == null) {
            IFluidHandler tank = facedTank();
            return tank == null ? 0 : Math.max(0, tank.fill(insertionSide(), fluid.makeFluidStack(wanted), false));
        }
        ForgeDirection facing = sat.getMachineFacing();
        TileEntity machine = sat.getMachineTile();
        if (facing == null || !(machine instanceof IFluidHandler)) {
            return 0;
        }
        return Math.max(0, ((IFluidHandler) machine).fill(facing.getOpposite(), fluid.makeFluidStack(wanted), false));
    }

    /** A fluid buffer now holds stock this module may pull; a buffer only takes what is credited, so wake the gate. */
    public void onBufferFluidStockArrived(FluidIdentifier fluid) {
        if (fluidSlotFor(fluid) >= 0) {
            gateDirty = true;
        }
    }

    /**
     * A fluid addressed to this module landed in the machine: whatever was on its way for the slot is now resolved (in
     * the machine or bounced), so it is no longer in flight either way. Called on arrival.
     */
    private void onFluidDelivered(FluidIdentifier fluid, int amount) {
        // A container carries no word of which module it was for, so the chassis hands it to the first module that
        // wants the fluid. All of them pour into the same machine, so that part doesn't matter, but the in-flight count
        // belongs to whichever one sent for it: take the amount off this module first, then off the other modules in
        // this chassis that are waiting for it. Otherwise the sender's count never comes down, it never hands the
        // machine over, and its later sets think the fluid is still coming.
        int left = takeFluidInFlight(fluid, amount, null);
        for (ModuleSmartCrafter other : ModuleSmartCrafter.AllCrafters) {
            if (left <= 0) {
                return;
            }
            if (other != this && other._service != null && other._service == _service) {
                left = other.takeFluidInFlight(fluid, left, null);
            }
        }
    }

    /**
     * A fluid container reached a Smart Satellite and was poured into its machine. Fluid carries no word of which
     * crafter sent for it, so the amount is taken off the crafters bound to that satellite that have it on its way: the
     * one holding the machine's claim first (once claims cover satellite machines it is the only one that can have
     * fluid on its way there), then any other.
     */
    public static void fluidArrivedAtSatellite(PipeSmartSatellite sat, FluidIdentifier fluid, int amount) {
        int left = amount;
        for (ModuleSmartCrafter crafter : crafterOrderFor(sat)) {
            if (left <= 0) {
                return;
            }
            left = crafter.takeFluidInFlight(fluid, left, sat);
        }
    }

    /**
     * Part of a fluid delivered to a Smart Satellite didn't fit its machine; the satellite sends it on to storage. The
     * slot that was waiting for it gets the amount back, so the replacement is let through (mirrors {@link #itemLost}).
     */
    public static void fluidNotInsertedAtSatellite(PipeSmartSatellite sat, FluidIdentifier fluid, int amount) {
        int left = amount;
        for (ModuleSmartCrafter crafter : crafterOrderFor(sat)) {
            if (left <= 0) {
                return;
            }
            left = crafter.refundFluidInFlight(fluid, left, sat);
        }
    }

    /**
     * How much of this fluid the crafters bound to {@code sat} have on its way to its machine. A satellite pours in no
     * more than that: fluid nobody sent for (a delivery saved mid-pipe and loaded again after a restart, its order
     * gone, or one that turns up after its job ended) would sit in the machine outside any set, and in a machine with a
     * single fluid input it keeps every other recipe's fluid out.
     */
    public static int fluidExpectedAtSatellite(PipeSmartSatellite sat, FluidIdentifier fluid) {
        int expected = 0;
        for (ModuleSmartCrafter crafter : crafterOrderFor(sat)) {
            expected += crafter.fluidInFlightTo(sat, fluid);
        }
        return expected;
    }

    /**
     * How much of this fluid the modules in this chassis have on its way to the machine it faces. A container carries
     * no word of which module sent for it, so all of them count, as in {@link #onFluidDelivered}.
     */
    private int fluidExpectedInChassis(FluidIdentifier fluid) {
        int expected = fluidInFlightTo(null, fluid);
        for (ModuleSmartCrafter other : ModuleSmartCrafter.AllCrafters) {
            if (other != this && other._service != null && other._service == _service) {
                expected += other.fluidInFlightTo(null, fluid);
            }
        }
        return expected;
    }

    /**
     * Fluid on its way to the machine {@code sat} pours into (null: this module's own) didn't go in: up to
     * {@code amount} of it stops being on its way and becomes grantable again, so it is sent once more.
     *
     * @return the part this module wasn't waiting for
     */
    private int refundFluidInFlight(FluidIdentifier fluid, int amount, PipeSmartSatellite sat) {
        if (fluid == null || amount <= 0) {
            return amount;
        }
        int slot = fluidSlotFor(fluid, sat);
        if (slot < 0 || inFlight[slot] <= 0) {
            return amount;
        }
        int take = Math.min(amount, inFlight[slot]);
        inFlight[slot] -= take;
        allowance[slot] += take;
        gateDirty = true;
        return amount - take;
    }

    /** The crafters bound to a satellite, the one holding the claim on its machine first. */
    private static List<ModuleSmartCrafter> crafterOrderFor(PipeSmartSatellite sat) {
        List<ModuleSmartCrafter> order = new ArrayList<>();
        MachineClaims.Key key = machineKeyOf(sat);
        ModuleSmartCrafter holder = key == null ? null : MachineClaims.holderOf(key);
        if (holder != null && holder.usesSatellite(sat)) {
            order.add(holder);
        }
        for (ModuleSmartCrafter crafter : ModuleSmartCrafter.AllCrafters) {
            if (crafter != holder && crafter.usesSatellite(sat)) {
                order.add(crafter);
            }
        }
        return order;
    }

    /**
     * Whether any ingredient slot of this recipe goes to {@code sat}: its id matches, and it is the satellite with that
     * id this module actually reaches (ids only need to be unique per network).
     */
    public boolean usesSatellite(PipeSmartSatellite sat) {
        if (sat.satelliteId == 0 || _service == null) {
            return false;
        }
        for (int slot = 0; slot < INGREDIENT_SLOTS; slot++) {
            if (advancedSatelliteIdArray[slot] == sat.satelliteId && (isGatedSlot(slot) || isGatedFluidSlot(slot))) {
                return smartSatelliteForId(sat.satelliteId) == sat;
            }
        }
        return false;
    }

    /**
     * Counts up to {@code amount} of this fluid as arrived at the machine {@code sat} pours into (null: this module's
     * own). @return the part this module wasn't waiting for
     */
    private int takeFluidInFlight(FluidIdentifier fluid, int amount, PipeSmartSatellite sat) {
        if (fluid == null || amount <= 0) {
            return 0;
        }
        int slot = fluidSlotFor(fluid, sat);
        if (slot < 0 || inFlight[slot] <= 0) {
            return amount;
        }
        int take = Math.min(amount, inFlight[slot]);
        inFlight[slot] -= take;
        lastProgressTick = now();
        gateDirty = true;
        return amount - take;
    }

    /**
     * The fluid buffer a result parked for {@code target} goes to (see {@link ModuleFluidCraftingBuffer#chooseFor}), or
     * null when none of them can take at least this much of this fluid.
     */
    private ModuleFluidCraftingBuffer fluidBufferWithRoomFor(FluidIdentifier fluid, int amount, IRouter target) {
        return target == null ? null : ModuleFluidCraftingBuffer.chooseFor(fluid, amount, target);
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
        // Only what a released set is waiting for goes in, the fluid twin of turning away items that lost their slot
        // information. The rest goes on to storage: a container saved mid-pipe and loaded again after a restart has no
        // order behind it, and poured in it sat outside any set, where in a single-fluid-input machine it kept the next
        // recipe's fluid out for good.
        FluidIdentifier arriving = FluidIdentifier.get(fluid);
        int expected = fluidExpectedInChassis(arriving);
        if (fluid.amount > expected) {
            FluidStack unexpected = fluid.copy();
            unexpected.amount = fluid.amount - expected;
            // TEMP [ItemGate DIAG]
            itemGateDiag("FLUID DECLINED " + unexpected.amount + "mB " + arriving + " (expected " + expected + "mB)");
            sendFluidBack(unexpected);
            if (expected <= 0) {
                return true;
            }
            fluid = fluid.copy();
            fluid.amount = expected;
        }
        // TEMP [ItemGate DIAG]
        itemGateDiag("FLUID ARRIVED " + fluid.amount + "mB " + arriving + " (expected " + expected + "mB)", false);
        IFluidHandler tank = facedTank();
        int filled = tank == null ? 0 : tank.fill(insertionSide(), fluid, true);
        // Only what went in is delivered. What the machine turned away goes back on the network and its slot gets the
        // amount back, so it is sent again once there is room; counted as delivered, the crafter would wait for good
        // on sets whose fluid never reached the machine.
        onFluidDelivered(FluidIdentifier.get(fluid), filled);
        if (filled < fluid.amount) {
            FluidStack rejected = fluid.copy();
            rejected.amount = fluid.amount - filled;
            sendFluidBack(rejected);
            int left = refundFluidInFlight(FluidIdentifier.get(fluid), rejected.amount, null);
            for (ModuleSmartCrafter other : ModuleSmartCrafter.AllCrafters) {
                if (left <= 0) {
                    break;
                }
                if (other != this && other._service != null && other._service == _service) {
                    left = other.refundFluidInFlight(FluidIdentifier.get(fluid), left, null);
                }
            }
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
     * For an output addressed to a satellite the fluid is drained from, and routed out through, that satellite's
     * machine instead of the block this module faces. There is deliberately <b>no</b> {@code getBestReply} gate here:
     * that check only sees {@code IFluidSink} storage, but a crafted fluid's destination is the order's requester —
     * often another crafter's machine, which is not a sink — so the gate would hold fluid that has a perfectly good
     * consumer (and {@code PipeFluidProvider} does exactly this: it sends to the order's router without a sink check).
     * The requester is a real destination that decides for itself what to do with any overflow; the backpressure gate
     * lives on the leftover that {@code sendRemainingFluid} default-routes to storage.
     */
    private void sendCraftedFluid() {
        if (fluidOrders == null || !getFluidOrders().hasOrders(ResourceType.CRAFTING)) {
            return;
        }
        LogisticsFluidOrder order = getFluidOrders().peekAtTopRequest(ResourceType.CRAFTING);
        FluidIdentifier fluid = order.getFluid();

        PipeSmartSatellite sat = satelliteForOutput(fluid);
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

        int maxSend = Math.min(order.getAmount(), Configs.MAX_LOGISTICS_FLUID_TRANSPORT_INNER_CAPACITY / 2);

        // The gate: how much the requester's machine can actually take right now. A Smart Crafter consumer, or a Smart
        // Satellite one of them sends to, caps it by the room its tank has (see getFluidGatedAllowance); anything else
        // is ungated, so the full amount goes. This is what stops a full machine being flooded with an intermediate it
        // cannot accept yet.
        FluidGate gate = fluidGateFor(order.getDestination(), order.getInformation(), fluid);
        int gateAllowance = gate == null ? Integer.MAX_VALUE : gate.allowance(fluid);
        int wanted = Math.min(maxSend, gateAllowance);

        // Gate shut (machine full, or nothing released yet): park what we can in a fluid buffer and credit the consumer
        // for it, so it is owed exactly what we send and pulls it back once the machine has room. Only a gated consumer
        // is owed - it alone will pull it. No buffer with room means we hold the fluid in the machine and move on to
        // the next order, so one consumer that can't take anything doesn't hold up the others.
        if (wanted <= 0 && gate != null) {
            // Room for any of it is enough, the rest follows on later passes: waiting for room for the whole chunk
            // could wait for ever on a small or part-filled tank, while the consumer waits for this very fluid.
            ModuleFluidCraftingBuffer buffer = fluidBufferWithRoomFor(fluid, 1, order.getRouter());
            if (buffer == null) {
                getFluidOrders().deferSend();
                return;
            }
            FluidStack drained = drainFromTank(tank, fluid, buffer.roomFor(fluid, maxSend), drainSide);
            if (drained == null || drained.amount <= 0) {
                return;
            }
            buffer.reserve(fluid, drained.amount);
            ItemIdentifierStack container = SimpleServiceLocator.logisticsFluidManager.getFluidContainer(drained);
            IRoutedItem item = SimpleServiceLocator.routedItemHelper.createNewTravelItem(container);
            item.setDestination(buffer.getBufferRouter());
            item.setTransportMode(TransportMode.Active);
            if (viaSatellite) {
                sat.queueRoutedItem(item, satFacing);
            } else {
                _service.queueRoutedItem(item, _service.inventoryOrientation());
            }
            gate.creditBuffered(fluid, drained.amount);
            getFluidOrders().sendSuccessfull(drained.amount, false, item);
            onFluidResultSent(drained);
            if (outstandingFluid(fluid) <= 0) {
                sendSetLeftover(tank, fluid, drainSide, viaSatellite ? sat : null);
            }
            return;
        }

        // Send straight to the requester, Active, exactly as PipeFluidProvider does for a tank: the order's router is a
        // real destination that decides for itself what to do with overflow. Drains only what the gate allows, capped
        // per send like the provider.
        FluidStack drained = drainFromTank(tank, fluid, wanted, drainSide);
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
        // Tell the consumer it may count this as on the way, so its gate (allowance / in-flight) stays honest.
        if (gate != null) {
            gate.onSend(fluid, drained.amount);
        }
        onFluidResultSent(drained);
        if (outstandingFluid(fluid) <= 0) {
            // That was the last of what was ordered, so anything still in the machine is left over from a set that
            // made more than the request needed. Send it on now, while this module is the one acting on the machine.
            sendSetLeftover(tank, fluid, drainSide, viaSatellite ? sat : null);
        }
    }

    /**
     * {@link #sendRemainingFluid} for what a finished set made beyond the order, counted towards that set like the
     * part that filled the order. An order smaller than a set (a Fluid Supplier topping up a tank) otherwise only
     * credits its own share, the set never reads as finished, and while new orders keep arriving the gate never
     * resets: the module holds a released set that no longer exists and never hands its machine over.
     */
    private void sendSetLeftover(IFluidHandler tank, FluidIdentifier fluid, ForgeDirection drainSide,
            PipeSmartSatellite sat) {
        int sent = sendRemainingFluid(tank, fluid, drainSide, sat, false);
        if (sent > 0) {
            onFluidResultSent(fluid.makeFluidStack(sent));
        }
    }

    /**
     * The Smart Satellite a fluid output is drained through, or null when the output goes to the block this module
     * faces. With {@link #sharedOutputSatellites} that is the first of the recipe's output satellites whose machine
     * holds the fluid; its own satellite when none does.
     */
    private PipeSmartSatellite satelliteForOutput(FluidIdentifier fluid) {
        int out = outputSlotForFluid(fluid);
        if (out < 0 || outputSatelliteId[out] == 0) {
            return null;
        }
        if (sharedOutputSatellites) {
            for (PipeSmartSatellite sat : satellitesForOutput(out)) {
                IFluidHandler tank = satelliteTank(sat);
                if (tank != null && heldInTank(tank, fluid, sat.getMachineFacing().getOpposite()) > 0) {
                    return sat;
                }
            }
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
     * Rate-limited notice that a crafted fluid has nowhere to go. Sent to players near the module and to the server log
     * so the player sees it while testing; the machine keeps holding meanwhile.
     */
    private void warnNoFluidSink(FluidIdentifier fluid) {
        long t = now();
        if (t - lastNoFluidSinkWarn < FLUID_SINK_WARN_COOLDOWN) {
            return;
        }
        lastNoFluidSinkWarn = t;
        String name = fluid == null ? "a fluid" : fluid.toString();
        String msg = "Smart Crafter: no fluid sink can take " + name
                + " to drain it from the machine, so it is held there.";
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
     * limit. When the output is a satellite's the leftover goes out through that satellite, {@code sat}, instead of the
     * block this module faces.
     */
    private void sendRemainingFluid(IFluidHandler tank, FluidIdentifier fluid, ForgeDirection drainSide,
            PipeSmartSatellite sat) {
        sendRemainingFluid(tank, fluid, drainSide, sat, false);
    }

    /**
     * @param ingredient the fluid is a leftover ingredient, so it may sit in an input tank the machine's fluid handler
     *                   never drains (a GT single-block's); it is then taken from that tank directly, the same way
     *                   leftover item ingredients are taken straight from GT input slots
     * @return the litres sent
     */
    private int sendRemainingFluid(IFluidHandler tank, FluidIdentifier fluid, ForgeDirection drainSide,
            PipeSmartSatellite sat, boolean ingredient) {
        int left = heldInTank(tank, fluid, drainSide);
        if (left <= 0) {
            return 0;
        }
        IRouter senderRouter = sat != null ? sat.getRouter() : getRouter();
        // Probe with the whole leftover, not 1 mB: PipeFluidBasic.sinkAmount caps its answer at the stack's amount, so
        // a
        // 1-mB probe reports it can take only 1 and would strand the rest of the set's excess in the machine.
        Pair<Integer, Integer> reply = SimpleServiceLocator.logisticsFluidManager
                .getBestReply(fluid.makeFluidStack(left), senderRouter, new ArrayList<Integer>());
        if (reply.getValue1() == 0) {
            warnNoFluidSink(fluid);
            return 0;
        }
        int wanted = Math.min(left, reply.getValue2());
        FluidStack drained = drainFromTank(tank, fluid, wanted, drainSide);
        if ((drained == null || drained.amount <= 0) && ingredient && GREGTECH && tank instanceof TileEntity) {
            drained = GregTechInputTank.drainInput((TileEntity) tank, fluid.makeFluidStack(wanted));
        }
        if (drained == null || drained.amount <= 0) {
            return 0;
        }
        sendFluidBack(drained, sat);
        return drained.amount;
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
        // A provider couldn't send it (its tanks ran dry). Nothing else would ever ask again, and the set would wait
        // for
        // ever with the rest of its ingredients delivered, so it is asked for again shortly, like a lost item.
        fluidSendFailedAt(null, value1, value2 == null ? 0 : value2);
    }

    /**
     * A provider couldn't send fluid ordered for the machine {@code sat} pours into (null: this module's own), because
     * its tanks ran dry. Nothing else would ever ask again, and the set would wait for ever with the rest of its
     * ingredients delivered, so it is asked for again shortly, like a lost item.
     */
    public void fluidSendFailedAt(PipeSmartSatellite sat, FluidIdentifier fluid, int amount) {
        gateDirty = true;
        if (CraftingJobs.currentIsCancelled()) {
            return;
        }
        queueLostFluid(fluidSlotFor(fluid, sat), fluid, amount, CraftingJobs.current());
    }

    /**
     * Part of a fluid delivery to a buffer didn't fit and went back to storage. Takes up to {@code max} of this
     * module's credit for that fluid back, since no stock stands behind it, and asks for that much again.
     *
     * @return how much credit was taken back
     */
    public int fluidBufferDeliveryBounced(FluidIdentifier fluid, int max) {
        int taken = 0;
        for (int slot = 0; slot < INGREDIENT_SLOTS && taken < max; slot++) {
            if (fluidBuffered[slot] <= 0 || !isGatedFluidSlot(slot) || !fluid.equals(getFluidIngredient(slot))) {
                continue;
            }
            int take = Math.min(max - taken, fluidBuffered[slot]);
            fluidBuffered[slot] -= take;
            taken += take;
            // Which request it was for is not known here; the re-request runs as a job of its own.
            queueLostFluid(slot, fluid, take, null);
        }
        if (taken > 0) {
            gateDirty = true;
        }
        return taken;
    }

    /** How long a lost fluid delivery waits before it is asked for again, giving its source time to refill. */
    private static final int LOST_FLUID_RETRY_TICKS = 100;

    /** Fluid deliveries that never arrived and are still to be asked for again. In memory only, like the orders. */
    private final List<LostFluid> lostFluids = new ArrayList<>();

    private static final class LostFluid {

        /** The ingredient slot it was for, which says where it is asked to: the slot's Smart Satellite, or here. */
        final int slot;
        final FluidIdentifier fluid;
        int amount;
        /** The request it was for, which the re-request joins; null if it isn't known. */
        final CraftingJob job;
        long retryAt;

        LostFluid(int slot, FluidIdentifier fluid, int amount, CraftingJob job, long retryAt) {
            this.slot = slot;
            this.fluid = fluid;
            this.amount = amount;
            this.job = job;
            this.retryAt = retryAt;
        }
    }

    /** Queues a re-request for a fluid slot. A slot of -1 (the recipe changed since) has nowhere to go and is dropped. */
    private void queueLostFluid(int slot, FluidIdentifier fluid, int amount, CraftingJob job) {
        if (slot >= 0 && fluid != null && amount > 0) {
            lostFluids.add(new LostFluid(slot, fluid, amount, job, now() + LOST_FLUID_RETRY_TICKS));
        }
    }

    /**
     * Where a lost fluid is asked to: the slot's Smart Satellite, or this module for a slot without one. Asked here,
     * a satellite slot's fluid would be turned away by this module and go to storage. Null while the satellite can't
     * be reached.
     */
    private IRequestFluid lostFluidTarget(int slot) {
        return advancedSatelliteIdArray[slot] == 0 ? this : fluidSatelliteFor(slot);
    }

    /**
     * Asks again for the fluid deliveries that never arrived. Dropped for a cancelled job; a finished one is asked for
     * under a job of its own, since the job may have finished on another job's ingredients and left this set short.
     */
    private void retryLostFluids() {
        if (lostFluids.isEmpty()) {
            return;
        }
        long t = now();
        Iterator<LostFluid> it = lostFluids.iterator();
        while (it.hasNext()) {
            LostFluid lost = it.next();
            if (t < lost.retryAt) {
                continue;
            }
            if (lost.job != null && lost.job.isCancelled()) {
                it.remove();
                continue;
            }
            CraftingJob job = lost.job != null && lost.job.isDone() ? null : lost.job;
            if ((job == null && !recipeHasWork()) || !lost.fluid.equals(getFluidIngredient(lost.slot))) {
                // Nothing left here to use it for (or the slot no longer takes it): it would only come back as an
                // orphan.
                it.remove();
                continue;
            }
            IRequestFluid target = lostFluidTarget(lost.slot);
            if (target == null) {
                lost.retryAt = t + LOST_FLUID_RETRY_TICKS * 4;
                continue;
            }
            int received;
            CraftingJob previous = CraftingJobs.enter(job);
            try {
                received = RequestTree.requestFluidPartial(
                        lost.fluid,
                        lost.amount,
                        target,
                        null,
                        ingredientInformation(lost.slot));
            } finally {
                CraftingJobs.exit(previous);
            }
            if (received >= lost.amount) {
                it.remove();
            } else {
                lost.amount -= Math.max(0, received);
                lost.retryAt = t + LOST_FLUID_RETRY_TICKS * 4;
            }
        }
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
            dropEmptyTags();
            normaliseFluidSlots();
        } finally {
            normalising = false;
        }
    }

    private boolean normalising = false;

    /**
     * Strips an empty NBT tag from every ingredient and result. An item with an empty tag is the same item as one with
     * none, but LP tells them apart: NEI's IC2 crystals carry an empty tag, the ones a machine makes carry none, so a
     * recipe imported with the empty one asked its machine for an item it never makes and waited for ever.
     */
    private void dropEmptyTags() {
        for (int slot = 0; slot < INVENTORY_SIZE; slot++) {
            ItemIdentifierStack stack = getMaterials(slot);
            if (stack != null && stack.getItem().tag != null && stack.getItem().tag.hasNoTags()) {
                _dummyInventory.setInventorySlotContents(
                        slot,
                        stack.getItem().getIgnoringNBT().unsafeMakeNormalStack(stack.getStackSize()));
            }
        }
    }

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
            // Tagged like an item ingredient, so a provider sending to a shared Smart Satellite asks the right
            // crafter's gate.
            template.addIngredient(
                    new FluidResource(getFluidIngredient(slot), fluidAmount[slot], target),
                    ingredientInformation(slot));
        }
        // Either way the item path must not also add it. An unreachable satellite is caught by isSatelliteConnected,
        // which stops the template being offered at all, rather than quietly crafting without the fluid.
        return true;
    }

    /**
     * The Smart Satellite an item ingredient slot is routed to, or null if its id is unset or no reachable Smart
     * Satellite has it.
     * <p>
     * Only Smart Satellites are matched: normal item and fluid satellites have their own id numbers, so the same id can
     * name several pipes, and only a Smart Satellite faces a single machine this module can watch. The Smart Satellite
     * is a fluid type, but it carries a plain item to the machine it faces (see {@code PipeSmartSatellite#endReached},
     * which only handles fluid containers and lets everything else fall through to the machine inventory).
     */
    @Override
    public IRouter getSatelliteRouter(int x) {
        PipeSmartSatellite satellite = smartSatelliteForId(x == -1 ? satelliteId : advancedSatelliteIdArray[x]);
        return satellite == null ? null : satellite.getRouter();
    }

    /**
     * The Smart Satellite a fluid ingredient slot is routed to, or null if its id is unset or no reachable Smart
     * Satellite has it. It pours the fluid into the machine it faces via the shared {@code FluidRoutedPipe} behaviour.
     */
    private PipeSmartSatellite fluidSatelliteFor(int slot) {
        return smartSatelliteForId(advancedSatelliteIdArray[slot]);
    }

    /** Whether this module's router has a route to the given one: a direct lookup in its route table by router id. */
    private boolean isReachable(IRouter router) {
        return !getRouter().getDistanceTo(router).isEmpty();
    }

    /**
     * Every ingredient slot that names a satellite needs a reachable Smart Satellite with that id. Without one the
     * module stops offering the recipe, instead of crafting without that ingredient. A slot with no id is delivered to
     * this module itself (a fluid is poured into the machine it faces), so there is nothing to reach.
     */
    @Override
    public boolean isSatelliteConnected() {
        for (int slot = 0; slot < INGREDIENT_SLOTS; slot++) {
            if (getMaterials(slot) == null) {
                continue;
            }
            if (advancedSatelliteIdArray[slot] != 0 && smartSatelliteForId(advancedSatelliteIdArray[slot]) == null) {
                return false;
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

    /**
     * See {@link #outputSatelliteId}: read by {@link #resultSourcesFor} to pull this output from a satellite's machine.
     */
    public int getOutputSatelliteId(int output) {
        return outputSatelliteId[output];
    }

    public void setOutputSatelliteId(int output, int satelliteId) {
        outputSatelliteId[output] = Math.max(0, satelliteId);
    }

    /**
     * The reachable Smart Satellites output {@code out} may come out of: its own, or with
     * {@link #sharedOutputSatellites} any of this recipe's output satellites. Empty when it comes out of the faced
     * machine, or its satellite can't be reached.
     */
    private List<PipeSmartSatellite> satellitesForOutput(int out) {
        if (out < 0 || outputSatelliteId[out] == 0) {
            return Collections.emptyList();
        }
        if (sharedOutputSatellites) {
            return outputSatellites();
        }
        PipeSmartSatellite sat = smartSatelliteForId(outputSatelliteId[out]);
        return sat == null ? Collections.<PipeSmartSatellite>emptyList() : Collections.singletonList(sat);
    }

    /** Whether output {@code out} may come out of the satellite with this id (see {@link #satellitesForOutput}). */
    private boolean comesOutOf(int out, int satelliteId) {
        if (satelliteId == 0 || outputSatelliteId[out] == 0) {
            return false;
        }
        if (outputSatelliteId[out] == satelliteId) {
            return true;
        }
        if (!sharedOutputSatellites) {
            return false;
        }
        for (int other = 0; other < OUTPUT_SLOTS; other++) {
            if (getOutput(other) != null && outputSatelliteId[other] == satelliteId) {
                return true;
            }
        }
        return false;
    }

    /**
     * Which machine the extraction loop pulls a finished result from. For an output addressed to a satellite, the
     * result comes out of the machine that satellite faces and enters the network through the satellite; otherwise it
     * comes from the machines the chassis faces, as always. The order and set accounting (see
     * {@link #onResultExtracted}) stays in the crafter either way.
     * <p>
     * The modules of a chassis share one order queue, and the order at the top is looked at by whichever module's turn
     * it is, often one whose recipe doesn't make it. That module must look where the recipe that does make it sends its
     * result. Looking only in the faced machine, it finds nothing and defers the order; since every module that finds
     * nothing turns the queue by one, the turns and the queue can stay in step, and the result's own module may then
     * never see its order at all while its result sits in the satellite's machine.
     */
    @Override
    protected List<AdjacentTile> resultSourcesFor(LogisticsItemOrder order, List<AdjacentTile> faced) {
        ItemIdentifier item = order.getResource().getItem();
        List<AdjacentTile> sources = new ArrayList<>();
        List<PipeSmartSatellite> seen = new ArrayList<>();
        boolean fromFaced = false;
        for (ModuleSmartCrafter maker : chassisMakersOf(item)) {
            boolean viaSatellite = false;
            // One satellite, or with shared outputs every output satellite of the maker's recipe.
            for (PipeSmartSatellite sat : maker.satellitesForOutput(maker.outputIndexFor(item))) {
                AdjacentTile source = satelliteResultSource(sat);
                if (source == null) {
                    continue;
                }
                viaSatellite = true;
                if (!seen.contains(sat)) {
                    seen.add(sat);
                    sources.add(source);
                }
            }
            if (!viaSatellite) {
                fromFaced = true;
            }
        }
        if (sources.isEmpty()) {
            return faced;
        }
        if (fromFaced) {
            sources.addAll(faced);
        }
        return sources;
    }

    /** The Smart Crafters in this chassis whose recipe makes {@code item}, this one first. */
    private List<ModuleSmartCrafter> chassisMakersOf(ItemIdentifier item) {
        List<ModuleSmartCrafter> makers = new ArrayList<>();
        if (outputIndexFor(item) >= 0) {
            makers.add(this);
        }
        for (ModuleSmartCrafter other : AllCrafters) {
            if (other != this && other._service == _service && other.outputIndexFor(item) >= 0) {
                makers.add(other);
            }
        }
        return makers;
    }

    /**
     * The machine a Smart Satellite faces as a result source that sends through that satellite, or null when there is
     * no satellite or it faces no machine right now (the result is then looked for in the faced machine).
     */
    private static AdjacentTile satelliteResultSource(PipeSmartSatellite sat) {
        if (sat == null) {
            return null;
        }
        ForgeDirection facing = sat.getMachineFacing();
        TileEntity machine = sat.getMachineTile();
        if (facing == null || machine == null) {
            return null;
        }
        AdjacentTile source = new AdjacentTile(machine, facing);
        // The result enters the network through the satellite, out of the face its machine is on.
        source.sender = sat;
        return source;
    }

    /**
     * The Smart Satellite with this id that this module can reach, or null if the id is unset or no such satellite is
     * placed and reachable yet. The one lookup behind every satellite id this module holds, input or output.
     */
    private PipeSmartSatellite smartSatelliteForId(int id) {
        if (id == 0) {
            return null;
        }
        for (PipeSmartSatellite satellite : PipeSmartSatellite.AllSatellites) {
            if (satellite.satelliteId == id && !satellite.stillNeedReplace()
                    && satellite.getRouter() != null
                    && isReachable(satellite.getRouter())) {
                return satellite;
            }
        }
        return null;
    }

    /* Where each ingredient goes */

    /**
     * The Smart Satellite a slot's ingredient goes to, or null when it goes to the machine this module faces (or names
     * a satellite that can't be reached, which {@link #isSatelliteConnected} already keeps from being crafted).
     */
    private PipeSmartSatellite endpointOf(int slot) {
        return advancedSatelliteIdArray[slot] == 0 ? null : smartSatelliteForId(advancedSatelliteIdArray[slot]);
    }

    /**
     * The router a slot's ingredient is sent to: this module's, or its Smart Satellite's. Null if that can't be found.
     */
    private IRouter slotRouter(int slot) {
        if (advancedSatelliteIdArray[slot] == 0) {
            return getRouter();
        }
        PipeSmartSatellite sat = endpointOf(slot);
        return sat == null ? null : sat.getRouter();
    }

    /** Whether a slot's ingredient goes to {@code sat}'s machine, or to this module's own when {@code sat} is null. */
    private boolean goesTo(int slot, PipeSmartSatellite sat) {
        return sat == null ? advancedSatelliteIdArray[slot] == 0 : advancedSatelliteIdArray[slot] == sat.satelliteId;
    }

    /** Gated item slots delivered to the machine this module faces. */
    private boolean isOwnMachineSlot(int slot) {
        return isGatedSlot(slot) && advancedSatelliteIdArray[slot] == 0;
    }

    /** Gated item slots delivered to {@code sat}'s machine (or this module's own when null). */
    private boolean isGatedSlotAt(int slot, PipeSmartSatellite sat) {
        return isGatedSlot(slot) && goesTo(slot, sat);
    }

    /** The distinct reachable Smart Satellites this recipe's ingredients, items or fluids, go to. */
    private List<PipeSmartSatellite> inputSatellites() {
        List<PipeSmartSatellite> sats = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        for (int slot = 0; slot < INGREDIENT_SLOTS; slot++) {
            int id = advancedSatelliteIdArray[slot];
            if (id == 0 || !(isGatedSlot(slot) || isGatedFluidSlot(slot)) || !seen.add(id)) {
                continue;
            }
            PipeSmartSatellite sat = smartSatelliteForId(id);
            if (sat != null) {
                sats.add(sat);
            }
        }
        return sats;
    }

    /** The distinct reachable Smart Satellites this recipe's outputs come out of. */
    private List<PipeSmartSatellite> outputSatellites() {
        List<PipeSmartSatellite> sats = new ArrayList<>();
        for (int out = 0; out < OUTPUT_SLOTS; out++) {
            if (getOutput(out) == null || outputSatelliteId[out] == 0) {
                continue;
            }
            PipeSmartSatellite sat = smartSatelliteForId(outputSatelliteId[out]);
            if (sat != null && !sats.contains(sat)) {
                sats.add(sat);
            }
        }
        return sats;
    }

    /** The claim key of the machine a Smart Satellite faces, or null when it faces none. */
    static MachineClaims.Key machineKeyOf(PipeSmartSatellite sat) {
        TileEntity machine = sat == null ? null : sat.getMachineTile();
        if (machine == null || machine.getWorldObj() == null) {
            return null;
        }
        return new MachineClaims.Key(
                machine.getWorldObj().provider.dimensionId,
                machine.xCoord,
                machine.yCoord,
                machine.zCoord);
    }

    /**
     * Every machine this recipe puts ingredients into, which is what it claims: the one it faces first, then each Smart
     * Satellite's (a satellite on the faced machine adds nothing new). Empty when this module faces no machine.
     */
    private List<MachineClaims.Key> machineKeys() {
        MachineClaims.Key faced = machineKey();
        if (faced == null) {
            return Collections.emptyList();
        }
        List<MachineClaims.Key> keys = new ArrayList<>();
        keys.add(faced);
        for (PipeSmartSatellite sat : inputSatellites()) {
            MachineClaims.Key key = machineKeyOf(sat);
            if (key != null && !keys.contains(key)) {
                keys.add(key);
            }
        }
        return keys;
    }

    /** Whether this module holds the claim on its machines. */
    private boolean holdsClaim() {
        return !claimedMachines.isEmpty();
    }

    /** Whether another crafter holds any of these machines. */
    private boolean anyHeldByOther(List<MachineClaims.Key> keys) {
        for (MachineClaims.Key key : keys) {
            if (MachineClaims.isHeldByOther(key, this)) {
                return true;
            }
        }
        return false;
    }

    /** The world clock claims are aged by, this module's own. */
    long claimClock() {
        return now();
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
        if (Configs.DEBUG_SMART_CRAFTER_RECIPE && !summary.equals(loggedInterests)) {
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

    /**
     * The fluids this module offers to the planner, for the request table's fluid list: the fluid twin of
     * {@link #getConfiguredCraftResults()}, which leaves fluids out.
     */
    public List<FluidIdentifier> getCraftedFluids() {
        List<FluidIdentifier> list = new ArrayList<>(OUTPUT_SLOTS);
        for (int out = 0; out < OUTPUT_SLOTS; out++) {
            int slot = outputInventorySlot(out);
            if (outputRole[out] != OutputRole.PRODUCT || !isFluidSlot(slot)) {
                continue;
            }
            FluidIdentifier fluid = getFluidIngredient(slot);
            if (fluid != null) {
                list.add(fluid);
            }
        }
        return list;
    }

    /** The result slot producing this fluid as a Product, or -1. Only a Product is offered to the planner. */
    private int fluidResultSlot(FluidIdentifier fluid) {
        if (fluid == null) {
            return -1;
        }
        for (int out = 0; out < OUTPUT_SLOTS; out++) {
            int slot = outputInventorySlot(out);
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
        int status = MainProxy.isServer(getWorld()) ? computeStatus() : lastStatus;
        ItemIdentifierStack statusItem = null;
        if (status == STATUS_NON_CONSUMED_MISSING) {
            ItemIdentifier missing = MainProxy.isServer(getWorld()) ? missingNonConsumed() : null;
            statusItem = missing != null ? missing.makeStack(1) : lastStatusItem;
        }
        packet.setOutputRole(roles).setOutputChance(chances).setOutputSatelliteId(outputSatelliteId.clone())
                .setFluidAmount(fluidAmount.clone()).setCleanupEnabled(cleanupEnabled)
                .setIgnoreDurability(ignoreDurability).setSharedOutputSatellites(sharedOutputSatellites)
                .setStatus(status).setStatusItem(statusItem)
                .setSetsReleased(setsReleased);
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
        ignoreDurability = packet.isIgnoreDurability();
        sharedOutputSatellites = packet.isSharedOutputSatellites();
        lastStatus = packet.getStatus();
        lastStatusItem = packet.getStatusItem();
        setsReleased = packet.getSetsReleased();
    }

    /** Client-side copy of the last status the server sent, for the gui's warning line. */
    private int lastStatus = STATUS_IDLE;
    /** The item the last status is about, if any (the missing non-consumed item). */
    private ItemIdentifierStack lastStatusItem = null;

    public int getLastStatus() {
        return lastStatus;
    }

    public ItemIdentifierStack getLastStatusItem() {
        return lastStatusItem;
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
                // Result slots too: the gui sends their inventory slot, and a fluid result's litres are per set as
                // well.
                if (index >= 0 && index < INVENTORY_SIZE) {
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
            case SmartCrafterSetting.IGNORE_DURABILITY:
                ignoreDurability = value != 0;
                gateDirty = true;
                return;
            case SmartCrafterSetting.SHARED_OUTPUT_SATELLITES:
                sharedOutputSatellites = value != 0;
                return;
            default:
        }
    }

    public boolean isCleanupEnabled() {
        return cleanupEnabled;
    }

    public boolean isIgnoreDurability() {
        return ignoreDurability;
    }

    public boolean isSharedOutputSatellites() {
        return sharedOutputSatellites;
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
    /**
     * An item the recipe needs in the machine but doesn't consume isn't there; {@link #missingNonConsumed} names it.
     */
    public static final int STATUS_NON_CONSUMED_MISSING = 10;

    /**
     * Worked out only when a gui asks for it, never on the module's tick, so a check that reads the machine's slots
     * costs nothing while nobody is looking.
     */
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
        // Before idle too, so the player sees it while setting the machine up. Only a warning: the craft is not held
        // back for it, since the check can't see every place a machine may take the item from.
        if (missingNonConsumed() != null) {
            return STATUS_NON_CONSUMED_MISSING;
        }
        // Checked before idle, so it shows while the player is still setting up, before a result goes missing.
        if (OutputPushUtil.findPushingNeighbour(getWorld(), getX(), getY(), getZ()) != null) {
            return STATUS_OUTPUT_PUSHES;
        }
        if (outputSatelliteMachinePushes()) {
            return STATUS_SATELLITE_OUTPUT_PUSHES;
        }
        if (!recipeHasWork()) {
            return STATUS_IDLE;
        }
        if (anyHeldByOther(machineKeys())) {
            return STATUS_WAITING_CLAIM;
        }
        return setsReleased > 0 ? STATUS_RUNNING : STATUS_HOLDING;
    }

    /**
     * The first of the imported recipe's non-consumed items the machine doesn't hold, or null when all are there, when
     * there are none, or when this machine's slots can't settle it (see {@link GregTechNonConsumed#missingFrom}).
     */
    public ItemIdentifier missingNonConsumed() {
        if (!GREGTECH || nonConsumed.isEmpty() || _service == null || getWorld() == null) {
            return null;
        }
        ItemIdentifierStack first = getOutput(0);
        if (first == null || !first.getItem().equals(nonConsumedFor)) {
            return null;
        }
        ForgeDirection dir = _service.inventoryOrientation();
        if (dir == null || dir == ForgeDirection.UNKNOWN) {
            return null;
        }
        TileEntity machine = getWorld().getTileEntity(getX() + dir.offsetX, getY() + dir.offsetY, getZ() + dir.offsetZ);
        return GregTechNonConsumed.missingFrom(machine, nonConsumed);
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
                    ingredientInformation(slot));
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
        manualSetComplete = satisfied == slots;
        int waitingBefore = pendingManualSets;
        // Run the gate now rather than on the next tick, so the answer below is what actually happened.
        updateGate();
        String outcome = pendingManualSets < waitingBefore ? "on its way" : whyNotReleased();
        say(player, satisfied + "/" + slots + " ingredients found, " + outcome);
    }

    /** Why the gate didn't hand a set over, for the "Request set" reply. */
    private String whyNotReleased() {
        List<MachineClaims.Key> machines = machineKeys();
        if (machines.isEmpty() || _service.getRealInventory() == null) {
            return "no machine in front of this module";
        }
        if (anyHeldByOther(machines)) {
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
        ignoreDurability = nbttagcompound.getBoolean("SmartIgnoreDurability");
        sharedOutputSatellites = nbttagcompound.getBoolean("SmartSharedOutputSatellites");
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
        nonConsumed.clear();
        NBTTagList list = nbttagcompound.getTagList("SmartNonConsumed", 10);
        for (int i = 0; i < list.tagCount(); i++) {
            ItemStack stack = ItemStack.loadItemStackFromNBT(list.getCompoundTagAt(i));
            if (stack != null && stack.getItem() != null) {
                nonConsumed.add(ItemIdentifier.get(stack));
            }
        }
        nonConsumedFor = null;
        if (nbttagcompound.hasKey("SmartNonConsumedFor")) {
            ItemStack stack = ItemStack.loadItemStackFromNBT(nbttagcompound.getCompoundTag("SmartNonConsumedFor"));
            if (stack != null && stack.getItem() != null) {
                nonConsumedFor = ItemIdentifier.get(stack);
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
        nbttagcompound.setBoolean("SmartIgnoreDurability", ignoreDurability);
        nbttagcompound.setBoolean("SmartSharedOutputSatellites", sharedOutputSatellites);
        if (!nonConsumed.isEmpty() && nonConsumedFor != null) {
            NBTTagList list = new NBTTagList();
            for (ItemIdentifier item : nonConsumed) {
                list.appendTag(item.makeNormalStack(1).writeToNBT(new NBTTagCompound()));
            }
            nbttagcompound.setTag("SmartNonConsumed", list);
            nbttagcompound
                    .setTag("SmartNonConsumedFor", nonConsumedFor.makeNormalStack(1).writeToNBT(new NBTTagCompound()));
        }
    }

    @Override
    public void handleAdvancedNEIRecipePacket(List<ItemStack> inputs, List<ItemStack> outputs,
            List<FluidStack> fluidInputs, EntityPlayer player) {
        handleSmartNEIRecipe(
                inputs,
                outputs,
                Collections.<Integer>emptyList(),
                Collections.<ItemStack>emptyList(),
                fluidInputs,
                player);
    }

    /**
     * An NEI import. Outputs are laid out guaranteed first, in NEI's order, then chanced ones from the most likely
     * down, so when a recipe has more outputs than there are slots it is the least likely ones that are left out. The
     * first slot is the Product and the rest Byproducts, which with exactly one guaranteed output makes that one the
     * Product and every chanced output a Byproduct.
     *
     * @param chances     whole percent per output; a missing entry counts as guaranteed
     * @param nonConsumed ingredients the recipe needs in the machine but doesn't use up, already left out of
     *                    {@code inputs}
     */
    public void handleSmartNEIRecipe(List<ItemStack> inputs, List<ItemStack> outputs, List<Integer> chances,
            List<ItemStack> nonConsumed, List<FluidStack> fluidInputs, EntityPlayer player) {
        // The base fills the grid; its packet back to the player is sent below, once this module's part is done too.
        super.handleAdvancedNEIRecipePacket(inputs, outputs, fluidInputs, null);
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
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < outputs.size(); i++) {
            if (outputs.get(i) != null) {
                order.add(i);
            }
        }
        // Stable, so outputs of equal chance keep NEI's order.
        order.sort((a, b) -> Integer.compare(importedChance(chances, b), importedChance(chances, a)));
        for (int i = 0; i < OUTPUT_SLOTS; i++) {
            int from = i < order.size() ? order.get(i) : -1;
            _dummyInventory.setInventorySlotContents(outputInventorySlot(i), from >= 0 ? outputs.get(from) : null);
            outputRole[i] = i == 0 ? OutputRole.PRODUCT : OutputRole.BYPRODUCT;
            outputChance[i] = from >= 0 ? importedChance(chances, from) : GUARANTEED;
        }
        // Setting slots directly fires no change listener, so a GT fluid display in a result slot would stay an item
        // and the crafter would advertise "GT fluid display" instead of the fluid its consumers ask for.
        refreshFluidSlots();
        this.nonConsumed.clear();
        for (ItemStack stack : nonConsumed) {
            if (stack != null && stack.getItem() != null) {
                ItemIdentifier item = ItemIdentifier.get(stack);
                if (!this.nonConsumed.contains(item)) {
                    this.nonConsumed.add(item);
                }
            }
        }
        ItemIdentifierStack first = getOutput(0);
        nonConsumedFor = this.nonConsumed.isEmpty() || first == null ? null : first.getItem();
        if (player != null) {
            MainProxy.sendPacketToPlayer(getCPipePacket(), player);
        }
    }

    private static int importedChance(List<Integer> chances, int output) {
        if (output >= chances.size() || chances.get(output) == null) {
            return GUARANTEED;
        }
        return Math.max(1, Math.min(GUARANTEED, chances.get(output)));
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
        retryLostFluids();
        if (holdsClaim() && _service.isNthTick(CLAIM_REFRESH_TICKS)) {
            if (claimedMachines.equals(machineKeys())) {
                MachineClaims.refresh(this);
            } else {
                // The chassis was turned to face another block, or a satellite was moved or rebound.
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
        if (awaitingMachines) {
            return CrafterState.WAITING_FOR_MACHINE;
        }
        // With sets out the crafter is working; waiting for the intermediates of the next sets isn't news yet.
        if (waitingForIntermediatesSince != 0 && setsReleased == 0) {
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

    /** How many other crafters are in line for a machine this module holds or waits for. */
    public int getMachineQueueLength() {
        return MachineClaims.waitingCount(this);
    }

    /**
     * Takes results nothing asked for out of the machine and sends them to storage. The crafter's normal extraction
     * loop only takes what an order wants, so anything else piles up until it blocks the machine: a byproduct, but
     * equally a second <b>product</b> of a recipe whose other product was the one requested (one iron into a lathe
     * gives a rod and two dust; ask for rods and the dust fills its output slot after 32 crafts).
     * <p>
     * Role has no bearing on this. What matters is only whether an order is waiting for the item, in which case it is
     * left for that order. Results that come out of an output satellite's machine are drained the same way, unless a
     * Smart Crafter sharing that machine is waiting for the item. Fluid results too, by the same rules.
     */
    private void drainUnorderedOutputs() {
        // Only while this module has a job of its own. With nothing ordered there is no craft of ours to keep running,
        // so anything in the machine is the player's (a set handed over by "Request set", something they put there) and
        // is left alone. Clearing an idle machine is the Cleanup checkbox's job, not this.
        if (!hasWork()) {
            return;
        }
        SweepTarget own = null;
        int stacks = 0;
        for (int i = 0; i < OUTPUT_SLOTS && stacks < MAX_SWEEP_STACKS; i++) {
            ItemIdentifierStack output = getOutput(i);
            if (output == null || ordersWaitingFor(output) > 0) {
                continue;
            }
            if (own == null) {
                own = sweepTarget(null);
                if (own == null || MachineClaims.isHeldByOther(own.key, this)) {
                    break;
                }
            }
            stacks += takeOut(own, output.getItem(), MAX_SWEEP_STACKS - stacks);
        }
        for (PipeSmartSatellite sat : outputSatellites()) {
            SweepTarget target = sweepTarget(sat);
            if (target != null && !MachineClaims.isHeldByOther(target.key, this)) {
                stacks += sweepUnwantedResults(target, MAX_SWEEP_STACKS);
            }
        }
        // Fluid results by the same rule. Without this a fluid byproduct was only drained by the sweep once the job was
        // done, so a job longer than the output hatch holds (16 sets of 1000L into a 16000L hatch) filled it and the
        // machine stopped mid-job.
        MachineClaims.Key ownKey = machineKey();
        if (ownKey != null && !MachineClaims.isHeldByOther(ownKey, this)) {
            sweepOwnFluidResults(true);
        }
        sweepSatelliteFluidResults(null);
        if (stacks > 0) {
            _service.getCacheHolder().trigger(CacheTypes.Inventory);
        }
        drainForeignResults();
    }

    /**
     * One place another Smart Crafter recipe's results can come out of in a machine this module holds: the machine that
     * recipe's chassis faces ({@code sat} null), or one of its output satellites' machines.
     */
    private static final class ForeignSource {

        final ModuleSmartCrafter owner;
        final PipeSmartSatellite sat;
        final MachineClaims.Key key;

        ForeignSource(ModuleSmartCrafter owner, PipeSmartSatellite sat, MachineClaims.Key key) {
            this.owner = owner;
            this.sat = sat;
            this.key = key;
        }
    }

    /**
     * Finds every other Smart Crafter recipe whose results can come out of a machine this module just claimed. Done
     * once per claim, so the drain doesn't look through every crafter each time: the claimed machines can't change
     * without the claim being released.
     */
    private void collectForeignSources() {
        List<ForeignSource> sources = new ArrayList<>();
        for (ModuleSmartCrafter other : ModuleSmartCrafter.AllCrafters) {
            if (other == this || other._service == null || other.getWorld() == null) {
                continue;
            }
            MachineClaims.Key faced = other.machineKey();
            if (faced != null && claimedMachines.contains(faced)) {
                sources.add(new ForeignSource(other, null, faced));
            }
            for (PipeSmartSatellite sat : other.outputSatellites()) {
                MachineClaims.Key key = machineKeyOf(sat);
                if (key != null && claimedMachines.contains(key)) {
                    sources.add(new ForeignSource(other, sat, key));
                }
            }
        }
        foreignSources = sources;
    }

    /**
     * Takes out results of other Smart Crafter recipes that turned up in the machines this module holds after their
     * crafter handed them over, when no order is waiting for them. A job can end while its last set is still in the
     * machine (the order was filled early by a chanced extra output, so the last set's results weren't wanted), and
     * those results come out once the next recipe already holds the machine. Nobody else takes them out: their crafter
     * has no work, and this one only drains its own. Left there they fill the output slot and the machine stops.
     * <p>
     * Each machine's output slots are read once; only an item found there is checked against the recipes and orders.
     * The results leave through their own recipe's chassis or satellite, as in its sweep. Ignores the owner's Cleanup
     * checkbox, as {@link #drainUnorderedOutputs} does, since what's left blocks this module's job.
     */
    private void drainForeignResults() {
        if (foreignSources.isEmpty()) {
            return;
        }
        Map<MachineClaims.Key, Set<ItemIdentifier>> present = new HashMap<>();
        for (ForeignSource source : foreignSources) {
            ModuleSmartCrafter owner = source.owner;
            if (owner._service == null || owner.getWorld() == null) {
                continue;
            }
            SweepTarget target = owner.sweepTarget(source.sat);
            if (target == null || !target.key.equals(source.key)) {
                continue;
            }
            Set<ItemIdentifier> items = present.get(source.key);
            if (items == null) {
                items = new HashSet<>(extractor(target).getItems());
                present.put(source.key, items);
            }
            if (items.isEmpty()) {
                continue;
            }
            int stacks = 0;
            for (int out = 0; out < OUTPUT_SLOTS && stacks < MAX_SWEEP_STACKS; out++) {
                ItemIdentifierStack output = owner.getOutput(out);
                if (output == null || output.getStackSize() <= 0
                        || owner.isFluidSlot(outputInventorySlot(out))
                        || (source.sat != null && !owner.comesOutOf(out, source.sat.satelliteId))
                        || owner.ordersWaitingFor(output) > 0) {
                    continue;
                }
                for (Iterator<ItemIdentifier> it = items.iterator(); it.hasNext() && stacks < MAX_SWEEP_STACKS;) {
                    ItemIdentifier item = it.next();
                    // One of this recipe's own results is left to this module's own extraction and drain. An item some
                    // crafter with sets out (this one included) loads into this machine is never touched: a GT input bus
                    // lets everything be pulled from it, so another recipe's "leftover result" can be a live ingredient.
                    if (!owner.isOurResult(item, output) || outputIndexFor(item) >= 0
                            || (source.sat != null && anyCrafterWaitsFor(source.sat, item))
                            || itemStillNeededAt(source.key, item, null)) {
                        continue;
                    }
                    stacks += owner.takeOut(target, item, MAX_SWEEP_STACKS - stacks);
                    it.remove();
                }
            }
            if (stacks > 0) {
                owner._service.getCacheHolder().trigger(CacheTypes.Inventory);
            }
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

    /** Called by {@link MachineClaims} when the machines this module waited for are handed to it. */
    void onClaimGranted(List<MachineClaims.Key> machines) {
        claimedMachines = new ArrayList<>(machines);
        awaitingMachines = false;
        releasedThisTurn = false;
        claimTakenTick = now();
        sweepBeforeRelease = true;
        gateDirty = true;
        collectForeignSources();
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
     * its sets finish and it never hands the machine back. Among recipes making the same item, the one holding the
     * machine or with sets out wins.
     */
    private ModuleSmartCrafter resultOwner(ItemIdentifier item) {
        boolean ours = outputIndexFor(item) >= 0;
        if (ours && (holdsClaim() || setsReleased > 0)) {
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
            if (other.holdsClaim() || other.setsReleased > 0) {
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
     * Whether something this recipe let into its machines is still waiting to be crafted: released sets not finished,
     * a "Request set" set handed out, or deliveries still on their way. Only then does what of its ingredients is in a
     * machine belong to it. Having orders isn't enough: with no set out nothing of its was let in, so anything there is
     * a stray (a restart's leftover, a lost item's replacement), and keeping it for an order made it permanent wherever
     * a supplier keeps the orders coming.
     */
    private boolean hasSetsOut() {
        return setsReleased > 0 || manualSetOut || !nothingInFlight();
    }

    /**
     * Counts what came out of one result slot towards finished sets. Shared by the item path and the fluid one, since a
     * set is finished whether its result left as an item or as fluid.
     */
    private void creditSets(int output, int amount) {
        extractedPerOutput[output] += amount;
        int finished = 0;
        for (int i = 0; i < OUTPUT_SLOTS; i++) {
            int perSet = creditPerSet(i);
            if (perSet > 0) {
                finished = Math.max(finished, extractedPerOutput[i] / perSet);
            }
        }
        if (finished > creditedSets) {
            int newlyFinished = finished - creditedSets;
            setsReleased = Math.max(0, setsReleased - newlyFinished);
            creditedSets = finished;
            gateDirty = true;
            // TEMP [ItemGate DIAG]
            itemGateDiag("FINISHED " + newlyFinished + " set(s): output " + output + " +" + amount);
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

    /**
     * How much of this slot's result one set puts into orders, counting every slot that makes the same thing: a recipe
     * may make one item twice, and both arrive as the same item credited to the first slot.
     * <p>
     * Only the slots the planner promises count: Products and counted Byproducts. A plain Byproduct of the same item (6
     * Wood Pulp plus 1 more at 80%) is never promised, so no order takes its share; it goes to storage uncounted, or
     * fills an order early in place of a promised one. Orders take exactly the promised amount per set, so dividing by
     * the 7 left every finished set short of a full count. While new orders kept coming the gate never reset, and the
     * module held a released set that no longer existed, never releasing the next. Counting a set finished early, when
     * a byproduct filled the orders before the last set came out, is harmless: those results aren't wanted, and
     * {@link #drainForeignResults} clears them if the machine has been handed over by then.
     * <p>
     * Only when no slot of the result is promised is the most a set can yield used instead.
     */
    private int creditPerSet(int output) {
        int perSet = resultPerSet(output);
        if (perSet <= 0) {
            return perSet;
        }
        int promised = 0;
        int most = 0;
        for (int i = 0; i < OUTPUT_SLOTS; i++) {
            if (i == output || sameResult(i, output)) {
                int amount = Math.max(0, resultPerSet(i));
                most += amount;
                if (outputRole[i] != OutputRole.BYPRODUCT) {
                    promised += amount;
                }
            }
        }
        return promised > 0 ? promised : most;
    }

    /** Whether two result slots make the same item, or the same fluid. */
    private boolean sameResult(int a, int b) {
        ItemIdentifierStack first = getOutput(a);
        ItemIdentifierStack second = getOutput(b);
        if (first == null || second == null) {
            return false;
        }
        boolean fluid = isFluidSlot(outputInventorySlot(a));
        if (fluid != isFluidSlot(outputInventorySlot(b))) {
            return false;
        }
        if (fluid) {
            return Objects
                    .equals(getFluidIngredient(outputInventorySlot(a)), getFluidIngredient(outputInventorySlot(b)));
        }
        return first.getItem().equals(second.getItem());
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
        // TEMP [ItemGate DIAG]: before the count changes, so an arrival nobody was waiting for shows as inFlight=0.
        itemGateDiag("ARRIVED " + item.getStackSize() + "x" + diagItemName(item.getItem()) + " for s" + slot);
        if (slot >= 0) {
            inFlight[slot] = Math.max(0, inFlight[slot] - item.getStackSize());
            lastProgressTick = now();
            if (wearsOrCharges(item.getItem())) {
                diag(
                        "arrived slot " + slot
                                + ": "
                                + describe(item.getItem())
                                + " | recipe wants "
                                + describe(getMaterials(slot) == null ? null : getMaterials(slot).getItem()));
            }
        }
    }

    @Override
    public void itemCouldNotBeSend(ItemIdentifierStack item, IAdditionalTargetInformation info) {
        whileReportingUnsent(() -> super.itemCouldNotBeSend(item, info));
    }

    /** Set while an order that never left its provider is reported lost, so {@link #itemLost} leaves the gate alone. */
    private static boolean reportingUnsent;

    /**
     * Runs a report of an order that could not be sent. It reaches {@link #itemLost} like a delivery lost on the way,
     * through the chassis or a Smart Satellite, so the gate needs telling which one it is.
     */
    public static void whileReportingUnsent(Runnable report) {
        boolean was = reportingUnsent;
        reportingUnsent = true;
        try {
            report.run();
        } finally {
            reportingUnsent = was;
        }
    }

    @Override
    public void itemLost(ItemIdentifierStack item, IAdditionalTargetInformation info) {
        super.itemLost(item, info);
        int slot = ingredientSlot(info);
        // TEMP [ItemGate DIAG]
        itemGateDiag("LOST " + item.getStackSize() + "x" + diagItemName(item.getItem()) + " for s" + slot
                + " cancelledJob=" + CraftingJobs.currentIsCancelled() + (reportingUnsent ? " (never sent)" : ""));
        // A provider that found its stock gone (another provider on the same inventory sent it first) reports the order
        // as lost, but nothing left: it was never counted on its way, and the allowance it was waiting for is still
        // there for the re-request. Taking it off inFlight would forget a different delivery still in the pipes.
        if (slot >= 0 && !reportingUnsent) {
            int wasInFlight = inFlight[slot];
            inFlight[slot] = Math.max(0, wasInFlight - item.getStackSize());
            // The crafter re-requests lost items, so let the replacement through. Not for a cancelled job, which gets
            // no replacement. Only as much as the loss took off what was on its way for a released set: a loss nobody
            // was waiting for (one turning up after its job, or one from no set at all) granting its full amount let
            // the replacement into the machine with no set behind it, often while another recipe held the machine.
            // The replacement still comes; without a grant it waits in a buffer until a set of this recipe needs it.
            if (!CraftingJobs.currentIsCancelled()) {
                allowance[slot] += wasInFlight - inFlight[slot];
            }
        }
    }

    /* IGatedItemSink */

    @Override
    public int getGatedAllowance(ItemIdentifier item, IAdditionalTargetInformation info) {
        int slot = ingredientSlot(info);
        if (slot < 0 || !isGatedSlot(slot)) {
            return Integer.MAX_VALUE;
        }
        // Nothing goes in while another recipe may be using the machine: a grant is only ever good while this module
        // holds it. Handing it over clears the grants, but one made after that (a loss's replacement) slipped through.
        return holdsClaim() ? allowance[slot] : 0;
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
        itemGateDiag("SENT " + amount + "x" + diagItemName(item) + " for s" + slot, false); // TEMP [ItemGate DIAG]
    }

    /** For the chassis: an item restored without its slot information is turned away if it is one for this machine. */
    @Override
    public boolean declinesUntrackedItem(ItemIdentifier item) {
        return declinesUntrackedAt(null, item);
    }

    /**
     * Whether an item that lost its slot information (restored after a restart or chunk reload, its order gone) is one
     * of this recipe's gated ingredients for {@code sat}'s machine, or this module's own when null. Letting it in would
     * leave a partial set behind, so it is sent on to storage instead.
     */
    public boolean declinesUntrackedAt(PipeSmartSatellite sat, ItemIdentifier item) {
        for (int slot = 0; slot < INGREDIENT_SLOTS; slot++) {
            if (isGatedSlotAt(slot, sat) && sameIngredient(getMaterials(slot).getItem(), item)) {
                return true;
            }
        }
        return false;
    }

    /* Fluid slots */

    /**
     * First input slot that holds this fluid, whichever machine it pours into.
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

    /** Whether one of this recipe's fluid slots pours this fluid into {@code sat}'s machine. */
    public boolean poursFluidInto(PipeSmartSatellite sat, FluidIdentifier fluid) {
        return fluidSlotFor(fluid, sat) >= 0 && usesSatellite(sat);
    }

    /**
     * First input slot that holds this fluid and pours into {@code sat}'s machine, or this module's own when null.
     * Fluid carries no slot information, so the machine it goes to is what tells two slots of the same fluid apart.
     *
     * @return the slot index, or -1 if none does.
     */
    private int fluidSlotFor(FluidIdentifier fluid, PipeSmartSatellite sat) {
        if (fluid == null) {
            return -1;
        }
        for (int slot = 0; slot < INGREDIENT_SLOTS; slot++) {
            if (isGatedFluidSlot(slot) && goesTo(slot, sat) && fluid.equals(getFluidIngredient(slot))) {
                return slot;
            }
        }
        return -1;
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
            // No more sets than the intermediates on hand cover. A set released without its intermediate holds the
            // machine for a set that can only finish once the intermediate is made - often in this very machine - so
            // the rest is released once those sets are done and the intermediate has been made.
            int fits = countSetsThatFit(Math.min(toRelease, setsCoveredByIntermediates()), false);
            if (fits > 0) {
                releaseSets(fits);
                setTooLarge = false;
            } else if (setsReleased == 0) {
                setTooLarge = countSetsThatFit(1, true) == 0;
            }
        }

        // Take what the buffers hold for the released sets, but only while this module holds its machines. A released
        // set can still be short - its ingredient may have reached a buffer after the release, and nothing else would
        // ever move it - so this can't wait for the next release. What a released set still needs delivered is exactly
        // its unsent allowance; pulling more would load sets that aren't released yet, and those would still be in
        // the machine when it is handed to the next recipe. It must respect the claim too: pulling into a machine
        // another crafter is working in is exactly the ingredient mixing the claim exists to prevent.
        for (int slot = 0; holdsClaim() && !ModuleCraftingBuffer.AllBuffers.isEmpty()
                && slot < INGREDIENT_SLOTS; slot++) {
            if (isGatedSlot(slot) && allowance[slot] > 0 && bufferedFor(slot) > 0) {
                pullFromBuffers(slot, allowance[slot]);
            }
        }

        // [FluidGate DIAG]: why buffered fluid is or isn't pulled.
        for (int slot = 0; Configs.DEBUG_FLUID_GATE && slot < INGREDIENT_SLOTS; slot++) {
            if (isFluidSlot(slot) && (fluidBuffered[slot] > 0 || allowance[slot] > 0)) {
                logFluidGateDiag(slot, wanted, setsNeeded);
            }
        }

        // The fluid twin of the loop above, for intermediates and raw fluid parked in a fluid buffer. The pull is
        // itself capped by the room the machine's tank has.
        for (int slot = 0; holdsClaim() && !ModuleFluidCraftingBuffer.AllBuffers.isEmpty()
                && slot < INGREDIENT_SLOTS; slot++) {
            if (isGatedFluidSlot(slot) && allowance[slot] > 0 && fluidBufferedFor(slot) > 0) {
                pullFluidFromBuffers(slot, allowance[slot]);
            }
        }

        // Hand the machines over once the sets in them are done, if someone else is waiting for one. An owner that
        // couldn't release anything (machine blocked) keeps them for a while first, so two blocked crafters don't pass
        // them back and forth every tick. This is the only way a crafter gives its machines up mid-job: with releases
        // capped at the intermediates on hand, a crafter never holds sets that need the machines back to finish.
        boolean hadTurn = releasedThisTurn || now() - claimTakenTick >= SAFETY_NET_TICKS;
        if (holdsClaim() && hadTurn && setsReleased == 0 && nothingInFlight() && MachineClaims.hasOthersWaiting(this)) {
            releaseClaim();
            gateDirty = true; // queue up again behind the crafter that is waiting
        }
    }

    /*
     * [ItemGate DIAG], with the itemGate debug config: every change to the item side of the gate (claims, releases,
     * sends, arrivals, losses, finished sets, sweeps), with the gate's counts and what the machines hold. For tracing a
     * stall or a stray ingredient in a shared machine.
     */

    private void itemGateDiag(String event) {
        itemGateDiag(event, true);
    }

    private void itemGateDiag(String event, boolean withMachine) {
        if (!Configs.DEBUG_ITEM_GATE || _service == null || getWorld() == null) {
            return;
        }
        StringBuilder line = new StringBuilder("[ItemGate DIAG] ").append(getX())
                .append(",")
                .append(getY())
                .append(",")
                .append(getZ())
                .append(" m")
                .append(getPositionInt())
                .append(" [")
                .append(diagRecipeName())
                .append("] ")
                .append(event)
                .append(" | sets=")
                .append(setsReleased)
                .append(" claimed=")
                .append(holdsClaim())
                .append(" credited=")
                .append(creditedSets);
        for (int slot = 0; slot < INGREDIENT_SLOTS; slot++) {
            if (!isGatedSlot(slot)) {
                continue;
            }
            ItemIdentifierStack material = getMaterials(slot);
            line.append(" | s")
                    .append(slot)
                    .append(" ")
                    .append(material.getStackSize())
                    .append("x")
                    .append(diagItemName(material.getItem()))
                    .append(" sat=")
                    .append(advancedSatelliteIdArray[slot])
                    .append(" allow=")
                    .append(allowance[slot])
                    .append(" inFlight=")
                    .append(inFlight[slot])
                    .append(" buffered=")
                    .append(buffered[slot]);
        }
        if (withMachine) {
            line.append(" | machine: ").append(diagContents(sweepTarget(null)));
            for (PipeSmartSatellite sat : inputSatellites()) {
                line.append(" | sat ").append(sat.satelliteId).append(": ").append(diagContents(sweepTarget(sat)));
            }
        }
        LogisticsPipes.log.info(line.toString());
    }

    /** The recipe's first result, so two recipes in one chassis (or two items with one name) can be told apart. */
    private String diagRecipeName() {
        for (int i = 0; i < OUTPUT_SLOTS; i++) {
            ItemIdentifierStack output = getOutput(i);
            if (output != null) {
                return output.getStackSize() + "x" + diagItemName(output.getItem());
            }
        }
        return "no result";
    }

    private static String diagItemName(ItemIdentifier item) {
        if (item == null) {
            return "nothing";
        }
        return item.getFriendlyName() + "(" + Item.getIdFromItem(item.item) + ":" + item.itemDamage + ")";
    }

    /** Every non-empty slot of a machine, as slot:countxitem. */
    private static String diagContents(SweepTarget target) {
        if (target == null) {
            return "none";
        }
        StringBuilder contents = new StringBuilder();
        for (int i = 0; i < target.inv.getSizeInventory(); i++) {
            ItemStack stack = target.inv.getStackInSlot(i);
            if (stack != null && stack.stackSize > 0) {
                contents.append(contents.length() == 0 ? "" : ", ")
                        .append(i)
                        .append(":")
                        .append(stack.stackSize)
                        .append("x")
                        .append(diagItemName(ItemIdentifier.get(stack)));
            }
        }
        return contents.length() == 0 ? "empty" : contents.toString();
    }

    /** [FluidGate DIAG] rate limit. */
    private long lastFluidDiagTick = 0;

    /**
     * [FluidGate DIAG], with the fluidGate debug config: one line per fluid slot with buffer credit or allowance, at
     * most once a second.
     */
    private void logFluidGateDiag(int slot, int wanted, int setsNeeded) {
        if (!Configs.DEBUG_FLUID_GATE || now() - lastFluidDiagTick < 20) {
            return;
        }
        lastFluidDiagTick = now();
        FluidIdentifier fluid = getFluidIngredient(slot);
        // The tank the slot's fluid is poured into: the Smart Satellite's machine, or the one this module faces.
        IFluidHandler tank;
        ForgeDirection side;
        if (advancedSatelliteIdArray[slot] == 0) {
            tank = facedTank();
            side = insertionSide();
        } else {
            PipeSmartSatellite sat = fluidSatelliteFor(slot);
            tank = sat == null ? null : satelliteTank(sat);
            side = tank == null ? null : sat.getMachineFacing().getOpposite();
        }
        int held = tank == null ? -1 : heldInTank(tank, fluid, side);
        int room = tank == null ? -1 : tank.fill(side, fluid.makeFluidStack(1000000), false);
        LogisticsPipes.log.info(
                "[FluidGate DIAG] " + getX()
                        + ","
                        + getY()
                        + ","
                        + getZ()
                        + " slot "
                        + slot
                        + " "
                        + fluid
                        + " satellite="
                        + advancedSatelliteIdArray[slot]
                        + " credit="
                        + fluidBuffered[slot]
                        + " stock="
                        + fluidBufferStock(fluid)
                        + " allowance="
                        + allowance[slot]
                        + " inFlight="
                        + inFlight[slot]
                        + " held="
                        + held
                        + " room="
                        + room
                        + " perSet="
                        + fluidAmount[slot]
                        + " wanted="
                        + wanted
                        + " setsNeeded="
                        + setsNeeded
                        + " setsReleased="
                        + setsReleased
                        + " claimed="
                        + holdsClaim()
                        + " covered="
                        + setsCoveredByIntermediates());
    }

    /**
     * Whether every ingredient that <b>another crafter is still making</b> is already on hand: in a buffer, on its way
     * here, or sitting in the machine. Raw materials are held to the same rule whenever a buffer has room for them,
     * because then a provider parks them there while the gate is shut ({@link #bufferForShutGate}), so they do turn up
     * without the gate opening. Only when no buffer can take them are they taken on trust, as before buffers existed:
     * the planner already found a source, and a provider that has nowhere to park them only sends once the gate opens,
     * so waiting for them would deadlock against ourselves.
     * <p>
     * Waiting for the whole set before claiming is what keeps a crafter from holding the machine, part-loaded, while
     * one ingredient is still stuck at its source - which is how two recipes ended up in one machine: after a while the
     * crafter gave the machine up mid-job, with its delivered ingredients still inside.
     * <p>
     * This is what stops a crafter seizing a machine on the strength of having an order. Two recipes on one machine,
     * where one makes the other's ingredient, used to deadlock outright: the second crafter claimed the machine,
     * released a set, and waited forever for an ingredient the first could no longer make, because it could never get
     * the machine. Its allowance stayed outstanding, so the stuck-set rule never fired either.
     */
    private boolean intermediatesReady() {
        return setsCoveredByIntermediates() > 0;
    }

    /**
     * How many more sets the ingredients that are held back (see {@link #intermediatesReady}) cover, beyond the sets
     * already released: those sets' share is already spoken for, and counting it again would release a set whose
     * intermediate doesn't exist yet. Integer.MAX_VALUE when no ingredient is held back.
     * <p>
     * A released set whose intermediate the machine has already taken in no longer shows on hand, so this can read low
     * while sets are running. That only delays the next release until they finish; it never releases too many.
     */
    private int setsCoveredByIntermediates() {
        int sets = setsOfIntermediatesOnHand();
        return sets == Integer.MAX_VALUE ? sets : Math.max(0, sets - setsReleased);
    }

    /** How many sets the held-back ingredients on hand make up, slot by slot; Integer.MAX_VALUE when none is. */
    private int setsOfIntermediatesOnHand() {
        int sets = Integer.MAX_VALUE;
        for (int slot = 0; slot < INGREDIENT_SLOTS; slot++) {
            if (!isGatedSlot(slot)) {
                continue;
            }
            ItemIdentifierStack material = getMaterials(slot);
            ItemIdentifier item = material.getItem();
            if (!isBeingCraftedByAnother(item) && !rawWillBeBuffered(item, slot)) {
                continue;
            }
            // On hand: on its way, parked in a buffer for this module, or already in the machine the slot goes to -
            // this
            // module's own, or its Smart Satellite's.
            int inMachine = advancedSatelliteIdArray[slot] == 0 ? machineStock(item)
                    : Math.max(0, itemsInSatelliteMachine(slot, item));
            int onHand = inFlight[slot] + bufferedFor(slot) + inMachine;
            sets = Math.min(sets, onHand / material.getStackSize());
        }
        // The fluid twin of the loop above. Without it a recipe whose only intermediate is a fluid is always "ready",
        // so its raw materials are released the moment the order lands - before the fluid that gates them exists.
        // A fluid's on-hand total is what is in flight, parked in a fluid buffer, and already in the tank it pours
        // into:
        // this module's own machine, or the slot's Smart Satellite's.
        for (int slot = 0; slot < INGREDIENT_SLOTS; slot++) {
            if (!isGatedFluidSlot(slot)) {
                continue;
            }
            FluidIdentifier fluid = getFluidIngredient(slot);
            if (!isFluidBeingCraftedByAnother(fluid) && !rawFluidWillBeBuffered(fluid, slot)) {
                continue;
            }
            int inTank;
            if (advancedSatelliteIdArray[slot] == 0) {
                IFluidHandler tank = facedTank();
                inTank = tank == null ? 0 : heldInTank(tank, fluid, insertionSide());
            } else {
                inTank = Math.max(0, heldInSatelliteMachine(slot, fluid));
            }
            int onHand = inFlight[slot] + fluidBufferedFor(slot) + inTank;
            sets = Math.min(sets, onHand / fluidAmount[slot]);
        }
        return sets;
    }

    /**
     * How many of this item are already in the machine a satellite-routed item slot delivers into, or -1 when the slot
     * can't be observed that way: it names no satellite, no reachable Smart Satellite has its id, or that satellite's
     * machine is not (yet) an inventory.
     */
    private int itemsInSatelliteMachine(int slot, ItemIdentifier item) {
        if (advancedSatelliteIdArray[slot] == 0 || item == null) {
            return -1;
        }
        PipeSmartSatellite sat = smartSatelliteForId(advancedSatelliteIdArray[slot]);
        if (sat == null) {
            return -1;
        }
        TileEntity machine = sat.getMachineTile();
        if (!(machine instanceof IInventory)) {
            return -1;
        }
        IInventoryUtil util = SimpleServiceLocator.inventoryUtilFactory
                .getInventoryUtil((IInventory) machine, ForgeDirection.UNKNOWN);
        return util == null ? 0 : countOf(util, item);
    }

    /**
     * How much of this fluid is already in the machine a satellite-routed fluid slot pours into, or -1 when the slot
     * can't be observed that way: it names no satellite, no reachable Smart Satellite has its id, or that satellite's
     * machine is not (yet) a fluid handler.
     */
    private int heldInSatelliteMachine(int slot, FluidIdentifier fluid) {
        if (advancedSatelliteIdArray[slot] == 0 || fluid == null) {
            return -1;
        }
        PipeSmartSatellite smart = fluidSatelliteFor(slot);
        if (smart == null) {
            return -1;
        }
        ForgeDirection facing = smart.getMachineFacing();
        if (facing == null || !(smart.getMachineTile() instanceof IFluidHandler)) {
            return -1;
        }
        // The satellite sits on the machine's opposite face; query the tank from the face it pours through (mirrors the
        // drain side sendCraftedFluid uses). heldInTank falls back to UNKNOWN when a machine gives per-side no answer.
        return heldInTank((IFluidHandler) smart.getMachineTile(), fluid, facing.getOpposite());
    }

    /**
     * Yields the machine while an intermediate is still being made. The wait has no time limit: a deep job on one
     * machine can keep its last step waiting for minutes, and going ahead early is exactly what puts that step's raw
     * materials into the machine before its sub-crafts have run there. An intermediate that is actually coming out of
     * storage is not waited on in the first place (see {@link #isBeingCraftedByAnother}), so this cannot stall a craft
     * that was never blocked. Waiting never holds a claim, so it cannot block the crafter it waits on either.
     */
    private boolean waitedLongEnoughForIntermediates() {
        if (intermediatesReady()) {
            waitingForIntermediatesSince = 0;
            return true;
        }
        if (waitingForIntermediatesSince == 0) {
            waitingForIntermediatesSince = now();
        }
        return false;
    }

    private long waitingForIntermediatesSince = 0;

    /**
     * Whether some other loaded Smart Crafter is making this item right now, i.e. it has orders for it outstanding.
     * <p>
     * Asking only "could another crafter make it" is not enough: an item a crafter can make may also be in storage, in
     * which case the planner sourced it from a provider, no crafter has an order for it, and it only moves once this
     * gate opens - waiting on it would never end. With outstanding orders as the test, that case is ready at once, and
     * a real intermediate is waited on until its crafter has delivered it (from then on it is counted as on hand).
     */
    private boolean isBeingCraftedByAnother(ItemIdentifier item) {
        for (ModuleSmartCrafter crafter : ModuleSmartCrafter.AllCrafters) {
            if (crafter == this || crafter._service == null) {
                continue;
            }
            for (ItemIdentifierStack result : crafter.getConfiguredCraftResults()) {
                if (sameIngredient(item, result.getItem()) && crafter.outstandingResults(result) > 0) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * The fluid twin of {@link #isBeingCraftedByAnother}: some other loaded Smart Crafter makes this fluid as a Product
     * and still has orders for it. Fluid results are not in {@link #getConfiguredCraftResults()} (items only), so this
     * walks the crafters' fluid results and fluid orders instead.
     */
    private boolean isFluidBeingCraftedByAnother(FluidIdentifier fluid) {
        if (fluid == null) {
            return false;
        }
        for (ModuleSmartCrafter crafter : ModuleSmartCrafter.AllCrafters) {
            if (crafter == this) {
                continue;
            }
            if (crafter.fluidResultSlot(fluid) >= 0 && crafter.outstandingFluid(fluid) > 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a raw item ingredient will be parked in a crafting buffer while the gate is shut, i.e. some buffer has
     * room for it and can reach the slot's machine. Mirrors the provider's side of {@link #bufferForShutGate}, so the
     * two agree on when to wait.
     */
    private boolean rawWillBeBuffered(ItemIdentifier item, int slot) {
        IRouter target = slotRouter(slot);
        return target != null && ModuleCraftingBuffer.withRoomFor(item, target) != null;
    }

    /** The fluid twin of {@link #rawWillBeBuffered}, matching {@code PipeFluidProvider}'s choice of buffer. */
    private boolean rawFluidWillBeBuffered(FluidIdentifier fluid, int slot) {
        IRouter target = slotRouter(slot);
        return fluid != null && target != null && ModuleFluidCraftingBuffer.withRoomFor(fluid, 1, target) != null;
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
        return util == null ? 0 : countOf(util, item);
    }

    /** How many of an ingredient an inventory holds, counting every damage and NBT of it when the match is loosened. */
    private int countOf(IInventoryUtil util, ItemIdentifier material) {
        if (!loosens(material)) {
            return util.itemCount(material);
        }
        int total = 0;
        for (Map.Entry<ItemIdentifier, Integer> entry : util.getItemsAndCount().entrySet()) {
            if (sameIngredient(material, entry.getKey())) {
                total += entry.getValue();
            }
        }
        return total;
    }

    /* Ignore durability/charge */

    // TEMPORARY [LapotronDiag]: shows the exact damage and NBT of worn or charged ingredients and results, to see what
    // separates a recipe's crystal from the ones the network has. Remove once that is known.

    /** Machine contents already reported as not matching a result, so a stuck item isn't logged every tick. */
    private final Set<ItemIdentifier> diagLogged = new HashSet<>();

    private void diag(String message) {
        LogisticsPipes.log.info("[LapotronDiag] " + getX() + "," + getY() + "," + getZ() + " " + message);
    }

    private static String describe(ItemIdentifier item) {
        if (item == null) {
            return "nothing";
        }
        return item.getFriendlyName() + " id="
                + Item.getIdFromItem(item.item)
                + " dmg="
                + item.itemDamage
                + " nbt="
                + (item.tag == null ? "none" : item.tag.toString());
    }

    /**
     * Whether an item's damage and NBT only say how worn or charged it is: something that wears down, or an electric
     * item that stores charge (IC2 crystals and batteries, GT batteries).
     */
    private static boolean wearsOrCharges(ItemIdentifier item) {
        ItemStack stack = item.unsafeMakeNormalStack(1);
        return stack.isItemStackDamageable() || SimpleServiceLocator.IC2Proxy.holdsCharge(stack);
    }

    /** Whether this ingredient's match is loosened by Ignore durability/charge. */
    private boolean loosens(ItemIdentifier material) {
        return ignoreDurability && material != null && wearsOrCharges(material);
    }

    /**
     * Whether {@code item} fills an ingredient slot holding {@code material}. Exact unless Ignore durability/charge
     * loosens it; even then an item that uses its damage to tell sub-items apart (GT plates, circuits) must still have
     * the same damage, since only its NBT is charge or wear.
     */
    private boolean sameIngredient(ItemIdentifier material, ItemIdentifier item) {
        if (material.equals(item)) {
            return true;
        }
        return loosens(material) && sameItemAnyState(material, item);
    }

    /**
     * The machine may make a result in another state than the recipe was set up with (an IC2 crystal with a different
     * damage or a charge tag), so with Ignore durability/charge the result is taken out whatever its damage and NBT.
     */
    @Override
    protected IResource resultToExtract(LogisticsItemOrder order) {
        ItemIdentifierStack ordered = order.getResource().getItemStack();
        if (!loosensResult(ordered.getItem())) {
            return super.resultToExtract(order);
        }
        DictResource dict = new DictResource(ordered, null);
        dict.ignore_dmg = true;
        dict.ignore_nbt = true;
        return dict;
    }

    @Override
    protected boolean extractedFillsOrder(LogisticsItemOrder order, ItemIdentifier extracted) {
        ItemIdentifier ordered = order.getResource().getItem();
        boolean fills = loosensResult(ordered) && sameItemAnyState(ordered, extracted);
        if (fills && diagLogged.add(extracted)) {
            diag("took result " + describe(extracted) + " for an order of " + describe(ordered));
        }
        return fills;
    }

    /**
     * Whether a result is taken out in any state: some Smart Crafter in this chassis (orders are shared by all of its
     * modules) makes it and has Ignore durability/charge on.
     */
    private boolean loosensResult(ItemIdentifier result) {
        if (outputIndexFor(result) >= 0) {
            return loosens(result);
        }
        for (ModuleSmartCrafter other : AllCrafters) {
            if (other != this && other._service == _service && other.outputIndexFor(result) >= 0) {
                return other.loosens(result);
            }
        }
        return false;
    }

    /** {@link #sameIngredient}'s loosened rule on its own: same item, damage only where it isn't a sub-item. */
    private static boolean sameItemAnyState(ItemIdentifier a, ItemIdentifier b) {
        return b != null && a.item == b.item && (a.itemDamage == b.itemDamage || !a.item.getHasSubtypes());
    }

    /** With Ignore durability/charge, the planner asks for the item in any state, from storage or from a crafter. */
    @Override
    protected IResource ingredientResource(int slot, ItemIdentifierStack resourceStack, IRequestItems target) {
        if (wearsOrCharges(resourceStack.getItem())) {
            diag(
                    "planning slot " + slot
                            + ": recipe wants "
                            + describe(resourceStack.getItem())
                            + (loosens(resourceStack.getItem()) ? " (any durability/charge)" : " (exact)"));
            for (ModuleSmartCrafter crafter : AllCrafters) {
                for (ItemIdentifierStack result : crafter.getConfiguredCraftResults()) {
                    if (result.getItem().item == resourceStack.getItem().item) {
                        diag(
                                "  a Smart Crafter at " + crafter.getX()
                                        + ","
                                        + crafter.getY()
                                        + ","
                                        + crafter.getZ()
                                        + " makes "
                                        + describe(result.getItem()));
                    }
                }
            }
        }
        if (!loosens(resourceStack.getItem())) {
            return super.ingredientResource(slot, resourceStack, target);
        }
        DictResource dict = new DictResource(resourceStack, target);
        dict.ignore_dmg = true;
        dict.ignore_nbt = true;
        return dict;
    }

    /**
     * Takes the claim on every machine this recipe puts ingredients into, if it doesn't hold them yet: all of them or
     * none. An owner stops releasing new sets once another crafter waits for one of them, but only after it released at
     * least once in its turn, so two crafters can't hand the machines back and forth without crafting anything.
     */
    private boolean mayReleaseSets() {
        List<MachineClaims.Key> machines = machineKeys();
        if (machines.isEmpty()) {
            return false;
        }
        if (holdsClaim() && !claimedMachines.equals(machines)) {
            // A satellite moved or was rebound: what this module holds no longer covers where its ingredients go.
            releaseClaim();
        }
        if (!holdsClaim()) {
            if (!MachineClaims.tryClaimAll(machines, this)) {
                awaitingMachines = true;
                return false;
            }
            claimedMachines = machines;
            awaitingMachines = false;
            releasedThisTurn = false;
            claimTakenTick = now();
            sweepBeforeRelease = true;
            collectForeignSources();
            itemGateDiag("CLAIM taken"); // TEMP [ItemGate DIAG]
        }
        return !releasedThisTurn || !MachineClaims.hasOthersWaiting(this);
    }

    private void releaseSets(int sets) {
        int manual = Math.min(sets, pendingManualSets);
        pendingManualSets -= manual;
        for (int slot = 0; slot < INGREDIENT_SLOTS; slot++) {
            if (isGatedSlot(slot)) {
                allowance[slot] += sets * getMaterials(slot).getStackSize();
            } else if (isGatedFluidSlot(slot)) {
                allowance[slot] += sets * fluidAmount[slot];
            }
        }
        // A set from "Request set" is finished once its ingredients are delivered: it exists to hand the machine one
        // more set, so no result is expected and none is taken back out. Only ordered sets are counted as in flight.
        setsReleased += sets - manual;
        manualSetOut |= manual > 0 && manualSetComplete;
        // Anything the buffers are already holding for this recipe comes now; the rest is left to the providers.
        for (int slot = 0; slot < INGREDIENT_SLOTS; slot++) {
            if (isGatedSlot(slot)) {
                pullFromBuffers(slot, sets * getMaterials(slot).getStackSize());
            } else if (isGatedFluidSlot(slot)) {
                pullFluidFromBuffers(slot, sets * fluidAmount[slot]);
            }
        }
        releasedThisTurn = true;
        lastProgressTick = now();
        itemGateDiag("RELEASE " + sets + " set(s), " + manual + " manual"); // TEMP [ItemGate DIAG]
    }

    /** Sets are out and nothing is on its way, but ingredients the providers may send still haven't come. */
    private boolean isBlockedWaitingForIngredients() {
        if (setsReleased == 0 || !nothingInFlight()) {
            return false;
        }
        for (int slot = 0; slot < INGREDIENT_SLOTS; slot++) {
            if (allowance[slot] > 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether ingredients are still on their way here after the last order finished, so the gate must not reset under
     * them. Sent ones always count. Granted but unsent ones only count for a "Request set" set, which has no order
     * behind it and is the one case where a provider still sends after the work is done; any other leftover allowance
     * has nothing to send against, and waiting on it would keep the machine claimed for good.
     */
    private boolean deliveryInProgress() {
        for (int slot = 0; slot < INGREDIENT_SLOTS; slot++) {
            if (inFlight[slot] > 0 || (manualSetOut && allowance[slot] > 0)) {
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
        // [ItemGate DIAG]: only when there was something to reset, since this runs on every idle update.
        boolean diagWorthIt = Configs.DEBUG_ITEM_GATE && (setsReleased > 0 || holdsClaim());
        for (int slot = 0; slot < INGREDIENT_SLOTS && !diagWorthIt; slot++) {
            diagWorthIt = isGatedSlot(slot) && (allowance[slot] > 0 || inFlight[slot] > 0);
        }
        if (diagWorthIt) {
            itemGateDiag("RESET gate (no orders left)");
        }
        for (int slot = 0; slot < INGREDIENT_SLOTS; slot++) {
            allowance[slot] = 0;
            inFlight[slot] = 0;
        }
        setsReleased = 0;
        pendingManualSets = 0;
        manualSetOut = false;
        clearResultProgress();
        setTooLarge = false;
        releaseClaim();
        stopWaiting();
        clearBufferedCredit();
        clearFluidBufferedCredit();
    }

    private void releaseClaim() {
        if (holdsClaim()) {
            itemGateDiag("CLAIM released"); // TEMP [ItemGate DIAG]
            claimedMachines = Collections.emptyList();
            foreignSources = Collections.emptyList();
            MachineClaims.releaseAll(this);
        }
        // Leftover permission from finished sets must not carry over into the next turn.
        for (int slot = 0; slot < INGREDIENT_SLOTS; slot++) {
            allowance[slot] = 0;
        }
        manualSetOut = false;
    }

    private void stopWaiting() {
        if (awaitingMachines) {
            awaitingMachines = false;
            MachineClaims.stopWaiting(this);
        }
    }

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
     * Results this crafter still has to produce. Orders in a chassis are shared by all its modules, so only orders this
     * module's recipe took are counted: two recipes for the same item in one chassis would otherwise both see every
     * order, and the one the planner didn't pick would take the machine and wait for ever for ingredients nobody sent.
     * Extras (leftovers of a partial set) are included, since the machine makes them too.
     */
    private int outstandingResults(ItemIdentifierStack result) {
        return ordersFor(result, true);
    }

    /**
     * Results any order in this chassis is waiting for, whichever module's recipe took it. For deciding whether a
     * result may be drained: another module's order for the same item still wants it, and any module may extract it.
     */
    private int ordersWaitingFor(ItemIdentifierStack result) {
        return ordersFor(result, false);
    }

    private int ordersFor(ItemIdentifierStack result, boolean ownOnly) {
        int total = 0;
        for (LogisticsItemOrder order : _service.getItemOrderManager()) {
            if (order.getType() != ResourceType.CRAFTING && order.getType() != ResourceType.EXTRA) {
                continue;
            }
            if (ownOnly && order.getMaker() != null && order.getMaker() != this) {
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
        if (loosens(result.getItem()) && sameItemAnyState(result.getItem(), item)) {
            return true;
        }
        if (sameItemAnyState(result.getItem(), item) && wearsOrCharges(item) && diagLogged.add(item)) {
            diag("machine holds " + describe(item) + " | recipe result is " + describe(result.getItem()));
        }
        if (getUpgradeManager().isFuzzyUpgrade() && outputFuzzyFlags.getBitSet().nextSetBit(0) != -1) {
            DictResource dict = new DictResource(result, null);
            dict.loadFromBitSet(outputFuzzyFlags.getBitSet());
            return dict.matches(item, IResource.MatchSettings.NORMAL);
        }
        return false;
    }

    /**
     * Item ingredient slots, which all go through the gate: delivered either to this module's own machine or to a Smart
     * Satellite's. Fluid slots have their own gate ({@link #isGatedFluidSlot}). Which machine a slot's room is checked
     * in is {@link #isGatedSlotAt}: a satellite-delivered item never occupies this machine's input slots, so it is
     * simulated in the satellite's machine instead.
     */
    private boolean isGatedSlot(int slot) {
        ItemIdentifierStack material = getMaterials(slot);
        return material != null && material.getStackSize() > 0 && !isFluidSlot(slot);
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
     * Simulates inserting whole recipe sets into every machine the recipe's items go to - the one this module faces and
     * each Smart Satellite's - on top of what each already holds and what is already on its way to it. A set only fits
     * when its part fits in each of them, so the answer is the smallest of theirs. Fluids are checked against their
     * tank's room when they are sent instead ({@link #getFluidGatedAllowance}).
     *
     * @param emptyMachine ignore the machines' current contents (used to tell "full right now" from "never fits")
     * @return how many sets fit, up to maxSets
     */
    private int countSetsThatFit(int maxSets, boolean emptyMachine) {
        int sets = countSetsThatFitIn(null, maxSets, emptyMachine);
        for (PipeSmartSatellite sat : inputSatellites()) {
            if (sets <= 0) {
                break;
            }
            sets = Math.min(sets, countSetsThatFitIn(sat, sets, emptyMachine));
        }
        return sets;
    }

    /**
     * {@link #countSetsThatFit} for one machine: this module's own (sat null), or the one {@code sat} faces, filled
     * through the face the satellite sits on. A satellite that only carries fluid has no item part to fit.
     */
    private int countSetsThatFitIn(PipeSmartSatellite sat, int maxSets, boolean emptyMachine) {
        IInventory inv;
        ForgeDirection side;
        if (sat == null) {
            inv = _service.getRealInventory();
            side = insertionSide();
        } else {
            boolean carriesItems = false;
            for (int slot = 0; slot < INGREDIENT_SLOTS && !carriesItems; slot++) {
                carriesItems = isGatedSlotAt(slot, sat);
            }
            if (!carriesItems) {
                return maxSets;
            }
            TileEntity machine = sat.getMachineTile();
            ForgeDirection facing = sat.getMachineFacing();
            inv = machine instanceof IInventory && facing != null ? (IInventory) machine : null;
            side = facing == null ? ForgeDirection.UNKNOWN : facing.getOpposite();
        }
        if (inv == null) {
            return 0;
        }
        int[] slots = insertableSlots(inv, side);
        Boolean multiStack = GREGTECH && inv instanceof TileEntity
                ? GregTechMultiStack.takesMultipleStacks((TileEntity) inv)
                : null;
        ItemStack[] simulated = new ItemStack[slots.length];
        if (!emptyMachine) {
            for (int i = 0; i < slots.length; i++) {
                ItemStack stack = inv.getStackInSlot(slots[i]);
                simulated[i] = stack == null ? null : stack.copy();
            }
        }

        int[] perSet = new int[INGREDIENT_SLOTS];
        for (int slot = 0; slot < INGREDIENT_SLOTS; slot++) {
            if (isGatedSlotAt(slot, sat)) {
                perSet[slot] = amountPerSet(getMaterials(slot).getItem(), sat);
            }
        }

        for (int slot = 0; slot < INGREDIENT_SLOTS; slot++) {
            int pending = allowance[slot] + inFlight[slot];
            if (pending > 0 && isGatedSlotAt(slot, sat)
                    && !place(inv, side, slots, simulated, getMaterials(slot), pending, perSet[slot], multiStack)) {
                return 0;
            }
        }

        int sets = 0;
        while (sets < maxSets) {
            for (int slot = 0; slot < INGREDIENT_SLOTS; slot++) {
                if (isGatedSlotAt(slot, sat)) {
                    ItemIdentifierStack material = getMaterials(slot);
                    if (!place(inv, side, slots, simulated, material, material.getStackSize(), perSet[slot],
                            multiStack)) {
                        return sets;
                    }
                }
            }
            sets++;
        }
        return sets;
    }

    /** Total of this item one recipe set puts into {@code sat}'s machine (or this module's own when null). */
    private int amountPerSet(ItemIdentifier item, PipeSmartSatellite sat) {
        int total = 0;
        for (int slot = 0; slot < INGREDIENT_SLOTS; slot++) {
            if (isGatedSlotAt(slot, sat) && getMaterials(slot).getItem().equals(item)) {
                total += getMaterials(slot).getStackSize();
            }
        }
        return total;
    }

    /**
     * One machine a sweep works on: the one this module faces ({@code sat} null), or the one a Smart Satellite faces.
     * Swept items leave through whichever pipe is attached to it.
     */
    private static final class SweepTarget {

        final IInventory inv;
        /** The direction from the pipe to the machine. */
        final ForgeDirection access;
        /** The face of the machine the pipe inserts through. */
        final ForgeDirection insertSide;
        final PipeSmartSatellite sat;
        final MachineClaims.Key key;

        SweepTarget(IInventory inv, ForgeDirection access, ForgeDirection insertSide, PipeSmartSatellite sat,
                MachineClaims.Key key) {
            this.inv = inv;
            this.access = access;
            this.insertSide = insertSide;
            this.sat = sat;
            this.key = key;
        }
    }

    /** The sweep target for this module's own machine (sat null) or {@code sat}'s, or null if it has no inventory. */
    private SweepTarget sweepTarget(PipeSmartSatellite sat) {
        if (sat == null) {
            IInventory inv = _service.getRealInventory();
            MachineClaims.Key key = machineKey();
            return inv == null || key == null ? null
                    : new SweepTarget(inv, _service.inventoryOrientation(), insertionSide(), null, key);
        }
        TileEntity machine = sat.getMachineTile();
        ForgeDirection facing = sat.getMachineFacing();
        MachineClaims.Key key = machineKeyOf(sat);
        if (!(machine instanceof IInventory) || facing == null || key == null) {
            return null;
        }
        return new SweepTarget((IInventory) machine, facing, facing.getOpposite(), sat, key);
    }

    /**
     * Pulls this recipe's own leftover items out of the machines it uses and sends them into the network, usually to
     * storage: all of its results and ingredients from the machine the chassis faces, its ingredients from each Smart
     * Satellite input machine, and results no Smart Crafter is waiting for from each output satellite's machine. The
     * recipe's fluid ingredients and fluid results are drained from the same machines' tanks by the same rules. Other
     * recipes' items are left alone here ({@link #sweepAllRecipesOnMachine} handles those, at claim time). A machine
     * another Smart Crafter holds is skipped, since two recipes can share an ingredient.
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
     * @return false if the machine this module faces couldn't be swept right now (not reachable, or in use by another
     *         crafter), so the sweep is tried again
     */
    private boolean sweepMachine() {
        if (!cleanupEnabled) {
            return true; // nothing to retry
        }
        SweepTarget own = sweepTarget(null);
        MachineClaims.Key ownKey = machineKey();
        if ((own == null && facedTank() == null) || ownKey == null || MachineClaims.isHeldByOther(ownKey, this)) {
            return false;
        }
        int stacks = 0;
        if (own != null) {
            stacks += sweepResult(own);
            stacks += sweepIngredients(own, MAX_SWEEP_STACKS - stacks);
        }
        sweepOwnFluidResults(false);
        sweepFluidIngredients(null);
        for (PipeSmartSatellite sat : inputSatellites()) {
            MachineClaims.Key key = machineKeyOf(sat);
            if (key == null || MachineClaims.isHeldByOther(key, this)) {
                continue;
            }
            SweepTarget target = sweepTarget(sat);
            if (target != null) {
                stacks += sweepIngredients(target, MAX_SWEEP_STACKS);
            }
            sweepFluidIngredients(sat);
        }
        for (PipeSmartSatellite sat : outputSatellites()) {
            SweepTarget target = sweepTarget(sat);
            if (target != null && !MachineClaims.isHeldByOther(target.key, this)) {
                stacks += sweepUnwantedResults(target, MAX_SWEEP_STACKS);
            }
        }
        sweepSatelliteFluidResults(null);
        if (stacks > 0) {
            _service.getCacheHolder().trigger(CacheTypes.Inventory);
        }
        return true;
    }

    /**
     * Clears what every Smart Crafter recipe left in the machines this module just claimed, this module's included.
     * Called once per claim, before this module releases anything, so none of the new sets' ingredients are in the
     * machines yet and a shared ingredient can't be taken by mistake.
     * <p>
     * Each recipe is swept by its own module, so the items leave through that module's chassis or satellite and follow
     * that recipe's Cleanup setting. Only Smart Crafter recipes are touched: an ordinary crafting module takes no
     * claim, so it could be mid-craft on this machine. A result some order is still waiting for is left in place: its
     * crafter extracts it for that order, and sending it to storage would strand the order.
     * <p>
     * A recipe with sets out ({@link #hasSetsOut}) keeps its ingredients: they were let in for those sets, and no
     * provider would send them again. That covers this module taking the machine back mid-job. Every other recipe's
     * ingredients are cleared, this module's included, whether it has orders or not: with no set out, nothing of it was
     * let in, so what is there is left over (a restart, a lost item's replacement, a cancelled job).
     */
    private void sweepAllRecipesOnMachine() {
        if (!holdsClaim()) {
            return;
        }
        for (MachineClaims.Key machine : claimedMachines) {
            sweepLeftoversAt(machine, !hasSetsOut());
            for (ModuleSmartCrafter other : ModuleSmartCrafter.AllCrafters) {
                if (other != this && other._service != null && other.getWorld() != null) {
                    other.sweepLeftoversAt(machine, !other.hasSetsOut());
                }
            }
        }
    }

    /**
     * This recipe's part of {@link #sweepAllRecipesOnMachine} for one machine, if it uses that machine: results no
     * order is waiting for, and its ingredients too if asked.
     */
    private void sweepLeftoversAt(MachineClaims.Key machine, boolean ingredientsToo) {
        if (!cleanupEnabled) {
            return;
        }
        int stacks = 0;
        if (machine.equals(machineKey())) {
            SweepTarget own = sweepTarget(null);
            if (own != null) {
                for (int i = 0; i < OUTPUT_SLOTS && stacks < MAX_SWEEP_STACKS; i++) {
                    ItemIdentifierStack output = getOutput(i);
                    if (output != null && ordersWaitingFor(output) <= 0) {
                        stacks += takeOut(own, output.getItem(), MAX_SWEEP_STACKS - stacks);
                    }
                }
                if (ingredientsToo) {
                    stacks += sweepIngredients(own, MAX_SWEEP_STACKS - stacks);
                }
            }
            sweepOwnFluidResults(true);
            if (ingredientsToo) {
                sweepFluidIngredients(null);
            }
        }
        List<PipeSmartSatellite> sats = new ArrayList<>(inputSatellites());
        for (PipeSmartSatellite sat : outputSatellites()) {
            if (!sats.contains(sat)) {
                sats.add(sat);
            }
        }
        for (PipeSmartSatellite sat : sats) {
            if (!machine.equals(machineKeyOf(sat))) {
                continue;
            }
            SweepTarget target = sweepTarget(sat);
            if (target != null) {
                stacks += sweepUnwantedResults(target, MAX_SWEEP_STACKS);
                if (ingredientsToo) {
                    stacks += sweepIngredients(target, MAX_SWEEP_STACKS);
                }
            }
            if (ingredientsToo) {
                sweepFluidIngredients(sat);
            }
        }
        sweepSatelliteFluidResults(machine);
        if (stacks > 0) {
            _service.getCacheHolder().trigger(CacheTypes.Inventory);
        }
    }

    /** All of this recipe's results in the machine this module faces. @return how many stacks were taken out */
    private int sweepResult(SweepTarget target) {
        int stacks = 0;
        for (int i = 0; i < OUTPUT_SLOTS && stacks < MAX_SWEEP_STACKS; i++) {
            ItemIdentifierStack output = getOutput(i);
            if (output != null) {
                stacks += takeOut(target, output.getItem(), MAX_SWEEP_STACKS - stacks);
            }
        }
        return stacks;
    }

    /**
     * This recipe's results that come out of a Smart Satellite's machine and that no Smart Crafter using that machine
     * for the same result is waiting for. Another recipe may deliver the same item into a shared output bus, so this
     * recipe having no order for it isn't enough. @return how many stacks were taken out
     */
    private int sweepUnwantedResults(SweepTarget target, int maxStacks) {
        int stacks = 0;
        for (int out = 0; out < OUTPUT_SLOTS && stacks < maxStacks; out++) {
            ItemIdentifierStack output = getOutput(out);
            if (output == null || target.sat == null
                    || !comesOutOf(out, target.sat.satelliteId)
                    || anyCrafterWaitsFor(target.sat, output.getItem())) {
                continue;
            }
            stacks += takeOut(target, output.getItem(), maxStacks - stacks);
        }
        return stacks;
    }

    /** Whether some Smart Crafter whose output comes out of {@code sat}'s machine still has orders for this item. */
    private static boolean anyCrafterWaitsFor(PipeSmartSatellite sat, ItemIdentifier item) {
        for (ModuleSmartCrafter crafter : ModuleSmartCrafter.AllCrafters) {
            if (crafter._service == null) {
                continue;
            }
            for (int out = 0; out < OUTPUT_SLOTS; out++) {
                ItemIdentifierStack output = crafter.getOutput(out);
                if (output != null && crafter.comesOutOf(out, sat.satelliteId)
                        && crafter.isOurResult(item, output)
                        && crafter.ordersWaitingFor(output) > 0
                        && crafter.smartSatelliteForId(sat.satelliteId) == sat) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Pulls an item out of the machine through its normal extraction rules and sends it to storage. Only the machine's
     * output slots are touched: some machines keep a player-set copy of the result elsewhere (e.g. the auto-chisel's
     * target slot), which must stay.
     *
     * @return how many stacks were taken out
     */
    private int takeOut(SweepTarget target, ItemIdentifier item, int maxStacks) {
        IInventoryUtil util = extractor(target);
        int stacks = 0;
        int left = util.itemCount(item);
        while (left > 0 && stacks < maxStacks) {
            ItemStack taken = util.getMultipleItems(item, Math.min(left, item.getMaxStackSize()));
            if (taken == null || taken.stackSize <= 0) {
                break;
            }
            left -= taken.stackSize;
            stacks++;
            sendSwept(target, taken);
        }
        return stacks;
    }

    /** The target machine seen through its normal extraction rules, i.e. only its output slots. */
    private static IInventoryUtil extractor(SweepTarget target) {
        IInventory inv = target.inv;
        IInventory extractable = inv instanceof ISidedInventory
                ? new SidedInventoryMinecraftAdapter((ISidedInventory) inv, ForgeDirection.UNKNOWN, true)
                : inv;
        return SimpleServiceLocator.inventoryUtilFactory.getInventoryUtil(extractable, target.access);
    }

    /** This recipe's ingredients for the target machine. @return how many stacks were taken out */
    private int sweepIngredients(SweepTarget target, int maxStacks) {
        IInventory inv = target.inv;
        int stacks = 0;
        for (int machineSlot = 0; machineSlot < inv.getSizeInventory(); machineSlot++) {
            if (stacks >= maxStacks) {
                break;
            }
            ItemStack stack = inv.getStackInSlot(machineSlot);
            if (stack == null || stack.stackSize <= 0) {
                continue;
            }
            if (!isIngredientFor(stack, target.sat) || !isInputSlot(inv, machineSlot, stack, target.insertSide)
                    || itemStillNeededAt(target.key, ItemIdentifier.get(stack), this)) {
                continue;
            }
            ItemStack taken = inv.decrStackSize(machineSlot, stack.stackSize);
            if (taken != null && taken.stackSize > 0) {
                stacks++;
                sendSwept(target, taken);
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

    /** Whether the stack is one of this recipe's item ingredients for {@code sat}'s machine (own when null). */
    private boolean isIngredientFor(ItemStack stack, PipeSmartSatellite sat) {
        for (int slot = 0; slot < INGREDIENT_SLOTS; slot++) {
            if (isGatedSlotAt(slot, sat) && sameIngredient(getMaterials(slot).getItem(), ItemIdentifier.get(stack))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Puts a swept stack into the network with no destination, so it goes to storage: from the chassis, or out through
     * the target's Smart Satellite. A satellite is jammed as a destination, since it sinks into the very machine the
     * stack was just taken from.
     */
    private void sendSwept(SweepTarget target, ItemStack stack) {
        // TEMP [ItemGate DIAG]
        itemGateDiag("SWEPT " + stack.stackSize + "x" + diagItemName(ItemIdentifier.get(stack)) + " out of "
                + (target.sat == null ? "machine" : "sat " + target.sat.satelliteId));
        if (target.sat == null) {
            _service.queueRoutedItem(
                    SimpleServiceLocator.routedItemHelper.createNewTravelItem(stack),
                    ForgeDirection.UP);
            return;
        }
        IRoutedItem item = SimpleServiceLocator.routedItemHelper.createNewTravelItem(stack);
        item.getJamList().add(target.sat.getRouter().getSimpleID());
        item.setDestination(-1);
        item.setTransportMode(TransportMode.Active);
        target.sat.queueRoutedItem(item, target.access);
    }

    /* Sweeping fluid: the same rules as items, for the recipe's fluid slots */

    /**
     * This recipe's fluid ingredients in the tank of the machine {@code sat} pours into (null: this module's own), sent
     * to storage. A fluid some Smart Crafter with work still pours into that machine is left: unlike item slots, a tank
     * can't tell whose fluid is whose, and that crafter's was delivered for its job, so no provider would send it
     * again.
     */
    private void sweepFluidIngredients(PipeSmartSatellite sat) {
        IFluidHandler tank = sat == null ? facedTank() : satelliteTank(sat);
        MachineClaims.Key key = sat == null ? machineKey() : machineKeyOf(sat);
        if (tank == null || key == null) {
            return;
        }
        ForgeDirection side = sat == null ? insertionSide() : sat.getMachineFacing().getOpposite();
        for (int slot = 0; slot < INGREDIENT_SLOTS; slot++) {
            if (!isGatedFluidSlot(slot) || !goesTo(slot, sat)) {
                continue;
            }
            FluidIdentifier fluid = getFluidIngredient(slot);
            if (fluid != null && !fluidStillNeededAt(key, fluid)) {
                sendRemainingFluid(tank, fluid, side, sat, true);
            }
        }
    }

    /**
     * This recipe's fluid results in the machine this module faces, sent to storage: all of them, or with
     * {@code onlyUnordered} only those no order of this module is waiting for. Results addressed to a satellite come
     * out of that satellite's machine instead ({@link #sweepSatelliteFluidResults}).
     */
    private void sweepOwnFluidResults(boolean onlyUnordered) {
        IFluidHandler tank = facedTank();
        if (tank == null) {
            return;
        }
        for (int out = 0; out < OUTPUT_SLOTS; out++) {
            int slot = outputInventorySlot(out);
            if (!isFluidSlot(slot) || outputSatelliteId[out] != 0) {
                continue;
            }
            FluidIdentifier fluid = getFluidIngredient(slot);
            if (fluid != null && (!onlyUnordered || outstandingFluid(fluid) <= 0)) {
                sendRemainingFluid(tank, fluid, insertionSide(), null);
            }
        }
    }

    /**
     * This recipe's fluid results that come out of a Smart Satellite's machine and that no Smart Crafter using that
     * machine for the same fluid is waiting for, sent to storage through the satellite. Only in {@code onlyAt} when
     * set; otherwise in every such machine no other crafter holds.
     */
    private void sweepSatelliteFluidResults(MachineClaims.Key onlyAt) {
        for (int out = 0; out < OUTPUT_SLOTS; out++) {
            int slot = outputInventorySlot(out);
            FluidIdentifier fluid = isFluidSlot(slot) ? getFluidIngredient(slot) : null;
            if (fluid == null) {
                continue;
            }
            for (PipeSmartSatellite sat : satellitesForOutput(out)) {
                MachineClaims.Key key = machineKeyOf(sat);
                IFluidHandler tank = satelliteTank(sat);
                if (key == null || tank == null
                        || (onlyAt != null ? !onlyAt.equals(key) : MachineClaims.isHeldByOther(key, this))) {
                    continue;
                }
                if (!anyCrafterWaitsForFluid(sat, fluid)) {
                    sendRemainingFluid(tank, fluid, sat.getMachineFacing().getOpposite(), sat);
                }
            }
        }
    }

    /** Whether some Smart Crafter whose fluid output comes out of {@code sat}'s machine still has orders for it. */
    private static boolean anyCrafterWaitsForFluid(PipeSmartSatellite sat, FluidIdentifier fluid) {
        for (ModuleSmartCrafter crafter : ModuleSmartCrafter.AllCrafters) {
            if (crafter._service == null) {
                continue;
            }
            for (int out = 0; out < OUTPUT_SLOTS; out++) {
                int slot = outputInventorySlot(out);
                if (crafter.isFluidSlot(slot) && crafter.comesOutOf(out, sat.satelliteId)
                        && fluid.equals(crafter.getFluidIngredient(slot))
                        && crafter.outstandingFluid(fluid) > 0
                        && crafter.smartSatelliteForId(sat.satelliteId) == sat) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Whether a Smart Crafter with sets out ({@link #hasSetsOut}) pours this fluid into the machine at {@code key}. One
     * that merely has orders doesn't count: none of its fluid was let in, so what is there is a stray.
     */
    private static boolean fluidStillNeededAt(MachineClaims.Key key, FluidIdentifier fluid) {
        for (ModuleSmartCrafter crafter : ModuleSmartCrafter.AllCrafters) {
            if (crafter._service == null || crafter.getWorld() == null || !crafter.hasSetsOut()) {
                continue;
            }
            for (int slot = 0; slot < INGREDIENT_SLOTS; slot++) {
                if (crafter.isGatedFluidSlot(slot) && fluid.equals(crafter.getFluidIngredient(slot))
                        && key.equals(crafter.slotMachineKey(slot))) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * The item twin of {@link #fluidStillNeededAt}: whether a Smart Crafter other than {@code except} with sets out
     * puts this item into the machine at {@code key}. A shared machine can't tell whose copy of an item is whose, and
     * that crafter's was delivered for its sets, so no provider would send it again.
     */
    private static boolean itemStillNeededAt(MachineClaims.Key key, ItemIdentifier item, ModuleSmartCrafter except) {
        for (ModuleSmartCrafter crafter : ModuleSmartCrafter.AllCrafters) {
            if (crafter == except || crafter._service == null
                    || crafter.getWorld() == null
                    || !crafter.hasSetsOut()) {
                continue;
            }
            for (int slot = 0; slot < INGREDIENT_SLOTS; slot++) {
                if (crafter.isGatedSlot(slot) && crafter.sameIngredient(crafter.getMaterials(slot).getItem(), item)
                        && key.equals(crafter.slotMachineKey(slot))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The claim key of the machine an ingredient slot goes into: this module's own, or its Smart Satellite's. */
    private MachineClaims.Key slotMachineKey(int slot) {
        return advancedSatelliteIdArray[slot] == 0 ? machineKey() : machineKeyOf(endpointOf(slot));
    }

    /** The tank of the machine a Smart Satellite faces, or null when it faces none that holds fluid. */
    private static IFluidHandler satelliteTank(PipeSmartSatellite sat) {
        TileEntity machine = sat.getMachineTile();
        return sat.getMachineFacing() != null && machine instanceof IFluidHandler ? (IFluidHandler) machine : null;
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
     * Many GT machines won't hold more than one stack of an item type even when they have free slots. When the machine's
     * own setting can be read ({@link GregTechMultiStack}) it is followed: with several stacks allowed only the free
     * slots limit it, with one stack the machine is never given more than one in total. Otherwise the machine is never
     * given more than one stack of an item in total, or one set's worth if the recipe needs more than that.
     *
     * @param perSet     how many of this item one recipe set needs
     * @param multiStack whether the machine takes several stacks of one item, or null when that isn't known
     * @return false if it doesn't all fit
     */
    private static boolean place(IInventory inv, ForgeDirection side, int[] slots, ItemStack[] simulated,
            ItemIdentifierStack material, int count, int perSet, Boolean multiStack) {
        ItemStack proto = material.getItem().makeNormalStack(1);
        int limit = Math.min(inv.getInventoryStackLimit(), proto.getMaxStackSize());
        if (!Boolean.TRUE.equals(multiStack)) {
            int held = 0;
            for (ItemStack stack : simulated) {
                if (isSameItem(stack, proto)) {
                    held += stack.stackSize;
                }
            }
            if (held + count > (multiStack == null ? Math.max(limit, perSet) : limit)) {
                return false;
            }
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
