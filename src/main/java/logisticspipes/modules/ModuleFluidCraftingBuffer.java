package logisticspipes.modules;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.client.renderer.texture.IIconRegister;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
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
import logisticspipes.interfaces.routing.IFluidContainerReceiver;
import logisticspipes.logisticspipes.IRoutedItem;
import logisticspipes.logisticspipes.IRoutedItem.TransportMode;
import logisticspipes.modules.abstractmodules.LogisticsModule;
import logisticspipes.proxy.MainProxy;
import logisticspipes.proxy.SimpleServiceLocator;
import logisticspipes.routing.ExitRoute;
import logisticspipes.routing.IRouter;
import logisticspipes.utils.FluidIdentifier;
import logisticspipes.utils.SinkReply;
import logisticspipes.utils.item.ItemIdentifier;
import logisticspipes.utils.item.ItemIdentifierStack;

/**
 * Holds the FLUID intermediates of a craft until the crafter that needs them has room for them, in a tank the player
 * provides and can expand (a Multi-Fluid Tank, an RF tank, ...) that can hold several fluids at once.
 * <p>
 * The fluid equivalent of {@link ModuleCraftingBuffer}, which holds intermediates in a chest. The stock is pooled (the
 * fluid isn't tagged with an owner), but each Smart Crafter keeps a <b>credit</b> for what sub-crafters sent to a
 * buffer on its behalf, and takes no more than that ({@code ModuleSmartCrafter.fluidBuffered}). Stock beyond all
 * crafters' credit is an <b>orphan</b> and is drained back into the network ({@link #returnAllOrphans}).
 * <p>
 * A fluid reaches this module the way every fluid does in LP: as an ordinary routed item holding a container. Only the
 * receiving end is fluid-specific, and that is {@link IFluidContainerReceiver}. The chassis already forwards an
 * addressed container to its modules, so a producer that wants to buffer a fluid simply routes a container here
 * (Active) and this module pours it into the tank it faces, sending whatever doesn't fit back out.
 * <p>
 * Like the item buffer, this only accepts a fluid a crafter has credit for and only while there is room. It never acts
 * as a general fluid sink, so it can't become a second storage system.
 */
public class ModuleFluidCraftingBuffer extends LogisticsModule implements IFluidContainerReceiver {

    /** Every loaded fluid buffer, so a crafter can find them without walking the routing table. Server side only. */
    public static final Set<ModuleFluidCraftingBuffer> AllBuffers = new HashSet<>();

    /** Called on server shutdown, as the satellite registry is. */
    public static void cleanup() {
        ModuleFluidCraftingBuffer.AllBuffers.clear();
    }

    public ModuleFluidCraftingBuffer() {}

    @Override
    public int getX() {
        return _service == null ? 0 : _service.getX();
    }

    @Override
    public int getY() {
        return _service == null ? 0 : _service.getY();
    }

    @Override
    public int getZ() {
        return _service == null ? 0 : _service.getZ();
    }

    /**
     * The chassis router this module sits in. A producer buffering a fluid routes a container here, so the chassis
     * hands the container to this module.
     */
    public int getBufferRouter() {
        return _service == null || _service.getRouter() == null ? -1 : _service.getRouter().getSimpleID();
    }

    private boolean registered = false;

    /**
     * Registration is lazy rather than on placement: a module has no hook for "I am now in the world", and this also
     * covers a chunk reloading. Removal is not signalled either, so a buffer whose service has gone drops itself.
     */
    @Override
    public void tick() {
        World world = _world.getWorld();
        if (world == null || _service == null || !MainProxy.isServer(world)) {
            return;
        }
        if (!registered) {
            AllBuffers.add(this);
            registered = true;
            // Freshly loaded: after a restart no crafter is owed anything, so whatever is here is an orphan.
            orphansPending = true;
        }
        if (orphansPending) {
            orphansPending = !returnOrphans();
        }
    }

    /* Storage: the multi-fluid tank this module faces */

    /** The tank the chassis faces, or null when it isn't pointed at a fluid handler. */
    public IFluidHandler getTank() {
        ForgeDirection dir = _service == null ? null : _service.inventoryOrientation();
        if (dir == null || dir == ForgeDirection.UNKNOWN || _world.getWorld() == null) {
            return null;
        }
        TileEntity tile = _world.getWorld()
                .getTileEntity(getX() + dir.offsetX, getY() + dir.offsetY, getZ() + dir.offsetZ);
        return tile instanceof IFluidHandler ? (IFluidHandler) tile : null;
    }

    /** The face of the tank this module sits on, which is the face to pour into and drain from. */
    private ForgeDirection storageSide() {
        ForgeDirection dir = _service == null ? null : _service.inventoryOrientation();
        return dir == null || dir == ForgeDirection.UNKNOWN ? ForgeDirection.UNKNOWN : dir.getOpposite();
    }

    /**
     * How much of a fluid this buffer holds, summed across every tank it contains, so a tank with several fluids
     * accounts for each of them.
     */
    public int getBufferFluidAvailable(FluidIdentifier fluid) {
        IFluidHandler tank = getTank();
        if (tank == null || fluid == null) {
            return 0;
        }
        FluidTankInfo[] tanks = tank.getTankInfo(ForgeDirection.UNKNOWN);
        if (tanks == null || tanks.length == 0) {
            tanks = tank.getTankInfo(storageSide());
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

    /** Whether the tank can take this much of this fluid. A tank holds several fluids, so the answer is per fluid. */
    public boolean hasRoomFor(FluidIdentifier fluid, int amount) {
        return amount > 0 && roomFor(fluid, amount) >= amount;
    }

    /**
     * How much of this fluid the tank still takes, counting no further than {@code max}, after the deliveries already
     * on their way here. The tank itself only knows what it holds now, so two senders asking one after the other would
     * otherwise both be told the same room, and the second delivery would bounce.
     * <p>
     * A tank that holds a single fluid (most do) can't take a different fluid at all while one is on its way: an empty
     * one says yes to anything, but only the first fluid to arrive will fit.
     */
    public int roomFor(FluidIdentifier fluid, int max) {
        IFluidHandler tank = getTank();
        if (tank == null || fluid == null || max <= 0) {
            return 0;
        }
        expireReservations();
        if (!holdsSeveralFluids(tank) && hasIncomingOtherThan(fluid)) {
            return 0;
        }
        int pending = incomingOf(fluid);
        FluidStack preview = fluid.makeFluidStack((int) Math.min(Integer.MAX_VALUE, (long) max + pending));
        int room = tank.fill(storageSide(), preview, false);
        if (room <= 0) {
            room = tank.fill(ForgeDirection.UNKNOWN, preview, false);
        }
        return Math.max(0, Math.min(max, room - pending));
    }

    /* Room promised to deliveries still in the pipes */

    /**
     * How long room stays promised to a delivery that hasn't arrived. A container lost on the way never arrives, and
     * without a limit its room would stay taken for good. A minute is far longer than any route takes.
     */
    private static final long RESERVATION_TIMEOUT_TICKS = 1200;

    private static final class Incoming {

        final FluidIdentifier fluid;
        int amount;
        final long expires;

        Incoming(FluidIdentifier fluid, int amount, long expires) {
            this.fluid = fluid;
            this.amount = amount;
            this.expires = expires;
        }
    }

    /** Oldest first. In memory only: after a restart nothing is on its way any more. */
    private final List<Incoming> incoming = new ArrayList<>();

    /** A sender is routing this much fluid here; keep room for it until it arrives. */
    public void reserve(FluidIdentifier fluid, int amount) {
        if (fluid != null && amount > 0) {
            incoming.add(new Incoming(fluid, amount, now() + RESERVATION_TIMEOUT_TICKS));
        }
    }

    /** A delivery got here (stored or not): the room it was promised is no longer promised. */
    private void releaseReservation(FluidIdentifier fluid, int amount) {
        Iterator<Incoming> it = incoming.iterator();
        while (it.hasNext() && amount > 0) {
            Incoming entry = it.next();
            if (!entry.fluid.equals(fluid)) {
                continue;
            }
            int take = Math.min(amount, entry.amount);
            entry.amount -= take;
            amount -= take;
            if (entry.amount <= 0) {
                it.remove();
            }
        }
    }

    private void expireReservations() {
        long t = now();
        incoming.removeIf(entry -> entry.expires <= t);
    }

    private int incomingOf(FluidIdentifier fluid) {
        int total = 0;
        for (Incoming entry : incoming) {
            if (entry.fluid.equals(fluid)) {
                total += entry.amount;
            }
        }
        return total;
    }

    private boolean hasIncomingOtherThan(FluidIdentifier fluid) {
        for (Incoming entry : incoming) {
            if (!entry.fluid.equals(fluid)) {
                return true;
            }
        }
        return false;
    }

    /** Whether the tank reports more than one internal tank, i.e. can hold several fluids side by side. */
    private boolean holdsSeveralFluids(IFluidHandler tank) {
        FluidTankInfo[] tanks = tank.getTankInfo(storageSide());
        if (tanks == null || tanks.length == 0) {
            tanks = tank.getTankInfo(ForgeDirection.UNKNOWN);
        }
        return tanks != null && tanks.length > 1;
    }

    private long now() {
        World world = _world == null ? null : _world.getWorld();
        return world == null ? 0 : world.getTotalWorldTime();
    }

    /**
     * The route cost from this buffer to {@code target}, or -1 when there is no route. Measured from the buffer, since
     * that is the trip a buffered fluid makes to the machine it is for. A null target is distance 0 (no preference).
     */
    public double distanceTo(IRouter target) {
        IRouter own = _service == null ? null : _service.getRouter();
        if (own == null) {
            return -1;
        }
        if (target == null || own.getSimpleID() == target.getSimpleID()) {
            return 0;
        }
        double best = -1;
        for (ExitRoute route : own.getDistanceTo(target)) {
            if (best < 0 || route.distanceToDestination < best) {
                best = route.distanceToDestination;
            }
        }
        return best;
    }

    /**
     * The loaded fluid buffers that can reach {@code target} (the machine a fluid is for, or its Smart Satellite),
     * nearest first. Ties go to the lower router id, so the choice doesn't change from one call to the next.
     */
    public static List<ModuleFluidCraftingBuffer> nearestTo(IRouter target) {
        Map<ModuleFluidCraftingBuffer, Double> distance = new HashMap<>();
        for (ModuleFluidCraftingBuffer buffer : AllBuffers) {
            double d = buffer.distanceTo(target);
            if (d >= 0) {
                distance.put(buffer, d);
            }
        }
        List<ModuleFluidCraftingBuffer> sorted = new ArrayList<>(distance.keySet());
        sorted.sort(
                Comparator.<ModuleFluidCraftingBuffer>comparingDouble(distance::get)
                        .thenComparingInt(ModuleFluidCraftingBuffer::getBufferRouter));
        return sorted;
    }

    /**
     * Whether some fluid buffer that can reach {@code target} has room for this much of this fluid. The same choice as
     * {@link #chooseFor}, without logging it, for asking ahead whether a delivery will be buffered.
     */
    public static ModuleFluidCraftingBuffer withRoomFor(FluidIdentifier fluid, int amount, IRouter target) {
        return choose(fluid, amount, target, false);
    }

    /**
     * The fluid buffer a delivery of this much of this fluid for {@code target} goes to, or null when none that can
     * reach it has room. Logged with the fluidBuffer debug config.
     * <p>
     * That is the nearest one with room, unless a buffer already holding the fluid (or with some on its way) has room
     * too and is not too much further away. Most tanks hold a single fluid, so spreading one fluid over every nearest
     * buffer uses up tanks other fluids then can't go in. How much further is worth it grows as the free tanks run
     * out, from {@link Configs#FLUID_BUFFER_SAME_FLUID_MIN_RANGE} with every tank free to
     * {@link Configs#FLUID_BUFFER_SAME_FLUID_MAX_RANGE} with none.
     */
    public static ModuleFluidCraftingBuffer chooseFor(FluidIdentifier fluid, int amount, IRouter target) {
        return choose(fluid, amount, target, Configs.DEBUG_FLUID_BUFFER);
    }

    private static ModuleFluidCraftingBuffer choose(FluidIdentifier fluid, int amount, IRouter target, boolean log) {
        List<ModuleFluidCraftingBuffer> reachable = nearestTo(target);
        double range = sameFluidRange(reachable);
        StringBuilder line = log ? new StringBuilder() : null;
        ModuleFluidCraftingBuffer nearest = null;
        ModuleFluidCraftingBuffer chosen = null;
        double limit = 0;
        for (ModuleFluidCraftingBuffer buffer : reachable) {
            boolean room = buffer.getBufferRouter() >= 0 && buffer.hasRoomFor(fluid, amount);
            double distance = buffer.distanceTo(target);
            boolean holds = buffer.holdsOrAwaits(fluid);
            if (line != null) {
                line.append(" | ")
                        .append(buffer.getX())
                        .append(",")
                        .append(buffer.getY())
                        .append(",")
                        .append(buffer.getZ())
                        .append(" d=")
                        .append(Math.round(distance))
                        .append(holds ? " holds" : "")
                        .append(room ? "" : " no room");
            }
            if (!room || chosen != null) {
                continue;
            }
            if (nearest == null) {
                nearest = buffer;
                limit = distance + range;
            } else if (distance > limit) {
                // Nothing further can be chosen; carry on only to finish the log line.
                chosen = nearest;
                if (line == null) {
                    break;
                }
                continue;
            }
            // Nearest first, so the first one holding the fluid is the nearest of them.
            if (holds) {
                chosen = buffer;
                if (line == null) {
                    break;
                }
            }
        }
        if (chosen == null) {
            chosen = nearest;
        }
        if (line != null) {
            LogisticsPipes.log.info(
                    "[FluidBuffer DIAG] " + amount
                            + "mB+ "
                            + fluid
                            + " for router "
                            + (target == null ? -1 : target.getSimpleID())
                            + ": range "
                            + Math.round(range)
                            + " (nearest with room "
                            + (nearest == null ? "none" : "at d=" + Math.round(nearest.distanceTo(target)))
                            + ") -> "
                            + (chosen == null ? "NO BUFFER"
                                    : chosen.getX() + "," + chosen.getY() + "," + chosen.getZ()
                                            + (chosen == nearest ? " (nearest)" : " (holds it)"))
                            + line);
        }
        return chosen;
    }

    /**
     * How much further than the nearest buffer with room a buffer already holding the fluid may be: the minimum range
     * while every tank of the reachable buffers is free, growing to the maximum as they fill up.
     */
    private static double sameFluidRange(List<ModuleFluidCraftingBuffer> reachable) {
        int min = Configs.FLUID_BUFFER_SAME_FLUID_MIN_RANGE;
        int max = Math.max(min, Configs.FLUID_BUFFER_SAME_FLUID_MAX_RANGE);
        int used = 0;
        int total = 0;
        for (ModuleFluidCraftingBuffer buffer : reachable) {
            int[] slots = buffer.tankSlotsInUse();
            used += slots[0];
            total += slots[1];
        }
        return total == 0 ? min : min + (max - min) * (double) used / total;
    }

    /**
     * How many of this buffer's tanks hold a fluid, and how many there are: one for a single-fluid tank, one per
     * internal tank for a tank that holds several. An empty single-fluid tank with a fluid on its way counts as in use,
     * since nothing else can go in it now.
     *
     * @return {in use, total}
     */
    private int[] tankSlotsInUse() {
        IFluidHandler tank = getTank();
        if (tank == null) {
            return new int[] { 0, 0 };
        }
        FluidTankInfo[] tanks = tank.getTankInfo(ForgeDirection.UNKNOWN);
        if (tanks == null || tanks.length == 0) {
            tanks = tank.getTankInfo(storageSide());
        }
        if (tanks == null || tanks.length == 0) {
            return new int[] { 0, 0 };
        }
        int used = 0;
        for (FluidTankInfo info : tanks) {
            if (info != null && info.fluid != null && info.fluid.amount > 0) {
                used++;
            }
        }
        if (used == 0 && tanks.length == 1) {
            expireReservations();
            if (!incoming.isEmpty()) {
                used = 1;
            }
        }
        return new int[] { used, tanks.length };
    }

    /** Whether this buffer already holds some of this fluid, or has some on its way. */
    private boolean holdsOrAwaits(FluidIdentifier fluid) {
        return getBufferFluidAvailable(fluid) > 0 || incomingOf(fluid) > 0;
    }

    /**
     * Pours an addressed container into the tank it faces. The receiver owns the fluid from then on, so whatever the
     * tank can't take is sent back into the network (nearest fluid sink) rather than voided or left in the pipe.
     */
    @Override
    public boolean receiveFluidContainer(FluidStack fluid) {
        if (fluid == null || fluid.amount <= 0) {
            return false;
        }
        FluidIdentifier fid = FluidIdentifier.get(fluid);
        if (fid != null) {
            releaseReservation(fid, fluid.amount);
        }
        // Not for this buffer: another module in the same chassis, if any, may still want it.
        if (fid == null || !isOwed(fid)) {
            return false;
        }
        IFluidHandler tank = getTank();
        int filled = 0;
        if (tank != null) {
            filled = tank.fill(storageSide(), fluid, true);
            if (filled <= 0) {
                filled = tank.fill(ForgeDirection.UNKNOWN, fluid, true);
            }
        }
        if (filled < 0) {
            filled = 0;
        }
        if (filled < fluid.amount) {
            FluidStack rejected = fluid.copy();
            rejected.amount = fluid.amount - filled;
            sendFluidBack(rejected);
            // Crafters were credited for all of it, but this part is on its way back to storage and nothing would ever
            // fetch it: take that much credit back and have it asked for again, or the set waits for ever.
            uncreditBounced(fid, rejected.amount);
        }
        if (filled > 0) {
            for (ModuleSmartCrafter crafter : ModuleSmartCrafter.AllCrafters) {
                crafter.onBufferFluidStockArrived(fid);
            }
        }
        return true;
    }

    /**
     * A producer only routes a fluid here once it is owed (credit granted) and there is room, so a fluid that arrives
     * when it is no longer owed is a race or a stale delivery: the container bounces to the nearest sink instead of
     * being stored in a buffer nobody will ever pull from.
     */
    @Override
    public boolean wantsFluid(FluidStack fluid) {
        if (fluid == null || fluid.amount <= 0) {
            return false;
        }
        return isOwed(FluidIdentifier.get(fluid));
    }

    /**
     * Sends fluid out of this buffer's tank to a crafter that asked for it, wrapped in a container and routed Active to
     * that crafter's router. The crafter pours it into its own machine's tank on arrival.
     */
    public int sendToFluid(FluidIdentifier fluid, int amount, int destinationRouter) {
        IFluidHandler tank = getTank();
        if (tank == null || fluid == null || amount <= 0) {
            return 0;
        }
        FluidStack wanted = fluid.makeFluidStack(amount);
        FluidStack drained = tank.drain(storageSide(), wanted, true);
        if (drained == null || drained.amount <= 0) {
            drained = tank.drain(ForgeDirection.UNKNOWN, wanted, true);
        }
        if (drained == null || drained.amount <= 0) {
            return 0;
        }
        ItemIdentifierStack container = SimpleServiceLocator.logisticsFluidManager.getFluidContainer(drained);
        if (container == null) {
            return 0;
        }
        IRoutedItem item = SimpleServiceLocator.routedItemHelper.createNewTravelItem(container);
        item.setDestination(destinationRouter);
        item.setTransportMode(TransportMode.Active);
        _service.queueRoutedItem(item, ForgeDirection.UP);
        return drained.amount;
    }

    /** Puts a leftover container back on the network with no destination, so it ends in the nearest fluid sink. */
    private void sendFluidBack(FluidStack fluid) {
        if (fluid == null || fluid.amount <= 0) {
            return;
        }
        ItemIdentifierStack container = SimpleServiceLocator.logisticsFluidManager.getFluidContainer(fluid);
        if (container == null) {
            return;
        }
        IRoutedItem item = SimpleServiceLocator.routedItemHelper.createNewTravelItem(container.makeNormalStack());
        item.setDestination(-1);
        item.setTransportMode(TransportMode.Active);
        _service.queueRoutedItem(item, ForgeDirection.UP);
    }

    /* Orphans: credit bookkeeping lives in the crafters, exactly as for the item buffer */

    /**
     * Whether crafters have credit for more of this fluid than all buffers hold, i.e. some of it is still on its way.
     */
    private static boolean isOwed(FluidIdentifier fluid) {
        return creditFor(fluid) > stockInAllBuffers(fluid);
    }

    /**
     * Part of a delivery didn't fit. Only credit that no stock stands behind is taken back, since the pool is shared
     * and the stock may still cover it; it is taken from whichever crafters hold credit for the fluid, as the pool
     * doesn't say whose delivery it was. Each of them asks for what it lost again.
     */
    private static void uncreditBounced(FluidIdentifier fluid, int amount) {
        int unbacked = Math.min(amount, creditFor(fluid) - stockInAllBuffers(fluid));
        for (ModuleSmartCrafter crafter : ModuleSmartCrafter.AllCrafters) {
            if (unbacked <= 0) {
                return;
            }
            unbacked -= crafter.fluidBufferDeliveryBounced(fluid, unbacked);
        }
    }

    /** How much of a fluid all loaded Smart Crafters together have credit for. */
    private static int creditFor(FluidIdentifier fluid) {
        int total = 0;
        for (ModuleSmartCrafter crafter : ModuleSmartCrafter.AllCrafters) {
            total += crafter.getBufferFluidCredit(fluid);
        }
        return total;
    }

    private static int stockInAllBuffers(FluidIdentifier fluid) {
        int total = 0;
        for (ModuleFluidCraftingBuffer buffer : AllBuffers) {
            total += buffer.getBufferFluidAvailable(fluid);
        }
        return total;
    }

    /** Sends back everything in the buffers that no crafter has credit for, from every buffer that holds it. */
    public static void returnAllOrphans() {
        for (ModuleFluidCraftingBuffer buffer : AllBuffers) {
            buffer.orphansPending = true;
        }
    }

    /** Most containers one buffer sends back per tick, so a full tank can't flood the network at once. */
    private static final int MAX_RETURN_CONTAINERS = 16;

    /** This buffer still holds orphans it couldn't send in one go; it carries on next tick until they're gone. */
    private boolean orphansPending = false;

    /** Drains this buffer's share of the orphans. @return true once none are left here */
    private boolean returnOrphans() {
        IFluidHandler tank = getTank();
        if (tank == null) {
            return true;
        }
        FluidTankInfo[] tanks = tank.getTankInfo(ForgeDirection.UNKNOWN);
        if (tanks == null || tanks.length == 0) {
            tanks = tank.getTankInfo(storageSide());
        }
        if (tanks == null) {
            return true;
        }
        // Credit is shared by all buffers, so the orphans are whatever all of them hold beyond it, and this buffer
        // sends as much of that as it has. Buffers that ran earlier this tick have already sent their part.
        Map<FluidIdentifier, Integer> stock = new HashMap<>();
        for (FluidTankInfo info : tanks) {
            if (info == null || info.fluid == null || info.fluid.amount <= 0) {
                continue;
            }
            FluidIdentifier fid = FluidIdentifier.get(info.fluid);
            if (fid == null) {
                continue;
            }
            stock.put(fid, stock.getOrDefault(fid, 0) + info.fluid.amount);
        }
        int sent = 0;
        for (Map.Entry<FluidIdentifier, Integer> entry : stock.entrySet()) {
            int excess = entry.getValue() - creditFor(entry.getKey());
            if (excess <= 0) {
                continue;
            }
            while (excess > 0) {
                if (sent >= MAX_RETURN_CONTAINERS) {
                    return false;
                }
                int want = Math.min(excess, Configs.MAX_LOGISTICS_FLUID_TRANSPORT_INNER_CAPACITY / 2);
                FluidStack drained = tank.drain(storageSide(), entry.getKey().makeFluidStack(want), true);
                if (drained == null || drained.amount <= 0) {
                    drained = tank.drain(ForgeDirection.UNKNOWN, entry.getKey().makeFluidStack(want), true);
                }
                if (drained == null || drained.amount <= 0) {
                    break;
                }
                sendFluidBack(drained);
                excess -= drained.amount;
                sent++;
            }
        }
        return true;
    }

    /* LogisticsModule plumbing: this module stores fluid, not items, so the item side is all no-ops. */

    @Override
    public SinkReply sinksItem(ItemIdentifier stack, int bestPriority, int bestCustomPriority, boolean allowDefault,
            boolean includeInTransit) {
        // Never a general item sink; this module takes fluid only, addressed to it as a container.
        return null;
    }

    @Override
    public LogisticsModule getSubModule(int slot) {
        return null;
    }

    @Override
    public boolean hasGenericInterests() {
        return false;
    }

    @Override
    public java.util.Collection<ItemIdentifier> getSpecificInterests() {
        // Fluids don't use item interests; a producer routes a container here directly.
        return Collections.emptySet();
    }

    @Override
    public boolean interestedInAttachedInventory() {
        return false;
    }

    @Override
    public boolean interestedInUndamagedID() {
        return false;
    }

    @Override
    public boolean recievePassive() {
        return false;
    }

    @Override
    public void readFromNBT(NBTTagCompound nbttagcompound) {}

    @Override
    public void writeToNBT(NBTTagCompound nbttagcompound) {}

    @Override
    @SideOnly(Side.CLIENT)
    public IIcon getIconTexture(IIconRegister register) {
        return register.registerIcon("logisticspipes:itemModule/ModuleFluidCraftingBuffer");
    }
}
