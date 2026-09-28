package logisticspipes.modules;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

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
import logisticspipes.config.Configs;
import logisticspipes.interfaces.routing.IFluidContainerReceiver;
import logisticspipes.logisticspipes.IRoutedItem;
import logisticspipes.logisticspipes.IRoutedItem.TransportMode;
import logisticspipes.modules.abstractmodules.LogisticsModule;
import logisticspipes.proxy.MainProxy;
import logisticspipes.proxy.SimpleServiceLocator;
import logisticspipes.utils.FluidIdentifier;
import logisticspipes.utils.SinkReply;
import logisticspipes.utils.item.ItemIdentifier;
import logisticspipes.utils.item.ItemIdentifierStack;

/**
 * Holds the FLUID intermediates of a craft until the crafter that needs them has room for them, in a tank the player
 * provides and can expand (a Multi-Fluid Tank, an RF tank, ...) that can hold several fluids at once.
 * <p>
 * The fluid equivalent of {@link ModuleCraftingBuffer}, which holds intermediates in a chest. The stock is pooled
 * (the fluid isn't tagged with an owner), but each Smart Crafter keeps a <b>credit</b> for what sub-crafters sent to a
 * buffer on its behalf, and takes no more than that ({@code ModuleSmartCrafter.fluidBuffered}). Stock beyond all
 * crafters' credit is an <b>orphan</b> and is drained back into the network ({@link #returnAllOrphans}).
 * <p>
 * A fluid reaches this module the way every fluid does in LP: as an ordinary routed item holding a container. Only the
 * receiving end is fluid-specific, and that is {@link IFluidContainerReceiver}. The chassis already forwards an
 * addressed container to its modules, so a producer that wants to buffer a fluid simply routes a container here (Active)
 * and this module pours it into the tank it faces, sending whatever doesn't fit back out.
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
        IFluidHandler tank = getTank();
        if (tank == null || fluid == null || amount <= 0) {
            return false;
        }
        FluidStack preview = fluid.makeFluidStack(amount);
        int room = tank.fill(storageSide(), preview, false);
        if (room <= 0) {
            room = tank.fill(ForgeDirection.UNKNOWN, preview, false);
        }
        return room >= amount;
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
