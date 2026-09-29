package logisticspipes.modules;

import java.util.Collection;
import java.util.Collections;

import net.minecraft.client.renderer.texture.IIconRegister;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.IIcon;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.IFluidHandler;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import logisticspipes.interfaces.routing.IFluidContainerReceiver;
import logisticspipes.interfaces.routing.IFluidSinkModule;
import logisticspipes.logisticspipes.IRoutedItem;
import logisticspipes.logisticspipes.IRoutedItem.TransportMode;
import logisticspipes.modules.abstractmodules.LogisticsGuiModule;
import logisticspipes.modules.abstractmodules.LogisticsModule;
import logisticspipes.network.NewGuiHandler;
import logisticspipes.network.abstractguis.ModuleCoordinatesGuiProvider;
import logisticspipes.network.abstractguis.ModuleInHandGuiProvider;
import logisticspipes.network.guis.module.inhand.FluidSinkInHand;
import logisticspipes.network.guis.module.inpipe.FluidSinkSlot;
import logisticspipes.proxy.SimpleServiceLocator;
import logisticspipes.proxy.computers.interfaces.CCCommand;
import logisticspipes.proxy.computers.interfaces.CCType;
import logisticspipes.utils.FluidIdentifier;
import logisticspipes.utils.SinkReply;
import logisticspipes.utils.item.ItemIdentifier;
import logisticspipes.utils.item.ItemIdentifierInventory;
import logisticspipes.utils.item.ItemIdentifierStack;
import logisticspipes.utils.string.StringUtils;

/**
 * The fluid analog of {@link ModuleItemSink}: a filtered list of fluids this chassis will accept, plus an optional
 * <b>default-path</b> flag that makes it a last-resort endpoint for <em>any</em> fluid.
 *
 * <p>
 * A fluid reaches this module the way every fluid does in LP: as an ordinary routed item holding a fluid container.
 * The fluid router only targets this chassis because the chassis forwards its room queries here
 * ({@link IFluidSinkModule}); the receiving end is {@link IFluidContainerReceiver}, already wired into the chassis the
 * same way {@link ModuleFluidCraftingBuffer} proved it. The module pours an arriving container into the tank the chassis
 * faces and sends whatever doesn't fit back into the network.
 * </p>
 *
 * <p>
 * <b>Backward-compat default:</b> an empty filter and an unset flag accept nothing. Nothing is opt-in — the module is
 * placed deliberately, and the default-path flag is set deliberately.
 * </p>
 */
@CCType(name = "FluidSink Module")
public class ModuleFluidSink extends LogisticsGuiModule implements IFluidContainerReceiver, IFluidSinkModule {

    private final ItemIdentifierInventory _filterInventory = new ItemIdentifierInventory(
            9,
            StringUtils.translate("gui.fluidsink.requestedFluids"),
            1);
    private boolean _isDefaultRoute;

    /*
     * IFluidSinkModule: the two room queries the fluid router makes. Pass 1 (defaultPath = false) is the filtered sink;
     * pass 2 (defaultPath = true) is the last-resort default path. Both answer with room for THIS fluid only.
     */
    @Override
    public int sinkAmount(FluidStack stack, boolean defaultPath) {
        if (stack == null || stack.amount <= 0) {
            return 0;
        }
        FluidIdentifier ident = FluidIdentifier.get(stack);
        if (ident == null) {
            return 0;
        }
        if (defaultPath) {
            if (!_isDefaultRoute) {
                return 0;
            }
        } else if (!matchesFilter(ident)) {
            return 0;
        }

        IFluidHandler tank = getTank();
        if (tank == null) {
            return 0;
        }

        // Room is per-(fluid, room): a simulated fill of THIS fluid, minus what is already on the way to this pipe.
        // Never "total free volume" — a multi-fluid tank would otherwise report room for a fluid whose tanks are full,
        // and the real fill would then mix/void.
        long onTheWay = _service == null ? 0 : _service.countFluidOnRoute(ident);
        long freeSpace = -onTheWay;
        int simulatedFill = tank.fill(storageSide(), stack.copy(), false);
        if (simulatedFill <= 0) {
            simulatedFill = tank.fill(ForgeDirection.UNKNOWN, stack.copy(), false);
        }
        freeSpace += simulatedFill;
        if (freeSpace < 0) {
            freeSpace = 0;
        }
        long clamped = Math.min(freeSpace, (long) stack.amount);
        return (int) Math.min(clamped, (long) Integer.MAX_VALUE);
    }

    /*
     * IFluidContainerReceiver: a fluid container arrives, pour it into the tank we face.
     */

    /**
     * Whether this module will take this fluid at all. Room is the router's job; here we only decide membership, so a
     * default-path module accepts any fluid and a filtered module accepts only its filter.
     */
    @Override
    public boolean wantsFluid(FluidStack fluid) {
        if (fluid == null || fluid.amount <= 0) {
            return false;
        }
        FluidIdentifier ident = FluidIdentifier.get(fluid);
        if (ident == null) {
            return false;
        }
        return matchesFilter(ident) || _isDefaultRoute;
    }

    /**
     * Pours an addressed container into the tank we face. The module owns the fluid from then on, so whatever the tank
     * can't take is sent back into the network (nearest fluid sink) rather than voided or left in the pipe.
     */
    @Override
    public boolean receiveFluidContainer(FluidStack fluid) {
        if (fluid == null || fluid.amount <= 0) {
            return false;
        }
        // Not for this module: another module in the same chassis, if any, may still want it.
        if (!wantsFluid(fluid)) {
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
        return true;
    }

    /*
     * Storage: the multi-fluid tank this module faces (mirrors ModuleFluidCraftingBuffer).
     */

    /** The tank the chassis faces, or null when it isn't pointed at a fluid handler. */
    private IFluidHandler getTank() {
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
     * Puts a leftover container back on the network with no destination, so it ends in the nearest fluid sink rather
     * than being voided or stuck in the pipe.
     */
    private void sendFluidBack(FluidStack fluid) {
        if (fluid == null || fluid.amount <= 0 || _service == null) {
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

    /** Whether any filter slot holds a container of this fluid. An empty filter matches nothing. */
    private boolean matchesFilter(FluidIdentifier ident) {
        for (int i = 0; i < _filterInventory.getSizeInventory(); i++) {
            ItemIdentifierStack stack = _filterInventory.getIDStackInSlot(i);
            if (stack == null) {
                continue;
            }
            FluidIdentifier filterFluid = FluidIdentifier.get(stack.getItem());
            if (filterFluid != null && filterFluid.equals(ident)) {
                return true;
            }
        }
        return false;
    }

    /*
     * GUI
     */

    @Override
    public ModuleCoordinatesGuiProvider getPipeGuiProvider() {
        return NewGuiHandler.getGui(FluidSinkSlot.class).setDefaultRoute(_isDefaultRoute);
    }

    @Override
    public ModuleInHandGuiProvider getInHandGuiProvider() {
        return NewGuiHandler.getGui(FluidSinkInHand.class);
    }

    /*
     * CC
     */

    @CCCommand(description = "Returns the FilterInventory of this Module")
    public ItemIdentifierInventory getFilterInventory() {
        return _filterInventory;
    }

    @CCCommand(description = "Returns true if the module is a default route")
    public boolean isDefaultRoute() {
        return _isDefaultRoute;
    }

    @CCCommand(description = "Sets the default route status of this module")
    public void setDefaultRoute(Boolean isDefaultRoute) {
        _isDefaultRoute = isDefaultRoute;
    }

    /*
     * NBT
     */

    @Override
    public void readFromNBT(NBTTagCompound nbttagcompound) {
        _filterInventory.readFromNBT(nbttagcompound, "");
        _isDefaultRoute = nbttagcompound.getBoolean("defaultdestination");
    }

    @Override
    public void writeToNBT(NBTTagCompound nbttagcompound) {
        _filterInventory.writeToNBT(nbttagcompound, "");
        nbttagcompound.setBoolean("defaultdestination", isDefaultRoute());
    }

    /*
     * LogisticsModule plumbing: this module stores fluid, not items, so the item side is all no-ops.
     */

    @Override
    public SinkReply sinksItem(ItemIdentifier stack, int bestPriority, int bestCustomPriority, boolean allowDefault,
            boolean includeInTransit) {
        // Never an item sink; this module takes fluid only, addressed to it as a container.
        return null;
    }

    @Override
    public LogisticsModule getSubModule(int slot) {
        return null;
    }

    @Override
    public void tick() {}

    @Override
    public boolean hasGenericInterests() {
        return false;
    }

    @Override
    public Collection<ItemIdentifier> getSpecificInterests() {
        // Fluids don't use item interests; the router asks this module directly via IFluidSinkModule.
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
    @SideOnly(Side.CLIENT)
    public IIcon getIconTexture(IIconRegister register) {
        return register.registerIcon("logisticspipes:itemModule/ModuleFluidSink");
    }
}
