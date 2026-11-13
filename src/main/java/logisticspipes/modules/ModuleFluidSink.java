package logisticspipes.modules;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.client.renderer.texture.IIconRegister;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.IIcon;
import net.minecraft.world.World;
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
 * A fluid reaches this module the way every fluid does in LP: as an ordinary routed item holding a fluid container. The
 * fluid router only targets this chassis because the chassis forwards its room queries here ({@link IFluidSinkModule});
 * the receiving end is {@link IFluidContainerReceiver}, already wired into the chassis the same way
 * {@link ModuleFluidCraftingBuffer} proved it. The module pours an arriving container into the tank the chassis faces
 * and sends whatever doesn't fit back into the network.
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
    /** Takes only its filter. */
    public static final int MODE_OFF = 0;
    /** Also a default route: any fluid from any sender, extractors included. */
    public static final int MODE_DEFAULT = 1;
    /**
     * Also an overflow endpoint: any fluid, but only from senders that must put it somewhere (cleanup sweeps, bounced or
     * lost containers), and only when no filtered or default-route sink has room. Extractors never send here.
     */
    public static final int MODE_OVERFLOW = 2;
    private static final int MODE_COUNT = 3;

    private int _mode = MODE_OFF;

    /*
     * IFluidSinkModule: the room queries the fluid router makes. Pass 1 (defaultPath = false) is the filtered sink;
     * pass 2 (defaultPath = true) is the last-resort default path; pass 3 (overflowSinkAmount) is overflow. All answer
     * with room for THIS fluid only.
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
            if (_mode != MODE_DEFAULT) {
                return 0;
            }
        } else if (!matchesFilter(ident)) {
            return 0;
        }
        return roomFor(stack, ident);
    }

    @Override
    public int overflowSinkAmount(FluidStack stack) {
        if (_mode != MODE_OVERFLOW || stack == null || stack.amount <= 0) {
            return 0;
        }
        FluidIdentifier ident = FluidIdentifier.get(stack);
        if (ident == null) {
            return 0;
        }
        return roomFor(stack, ident);
    }

    private int roomFor(FluidStack stack, FluidIdentifier ident) {
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
        return matchesFilter(ident) || _mode != MODE_OFF;
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
        if (filled > 0 && _mode == MODE_OVERFLOW) {
            FluidIdentifier ident = FluidIdentifier.get(fluid);
            if (ident != null && !matchesFilter(ident)) {
                logOverflow(ident, filled);
            }
        }
        if (filled < fluid.amount) {
            FluidStack rejected = fluid.copy();
            rejected.amount = fluid.amount - filled;
            sendFluidBack(rejected);
        }
        return true;
    }

    /*
     * Overflow log: what this sink took in overflow mode (fluids not in its filter), for the Statistics Table's
     * "Overflow fluids" tab, so players see what they are voiding and need real storage for. Saved with the module.
     */

    /** One fluid's total since it was last cleared. */
    public static final class OverflowRecord {

        public long litres;
        public int deliveries;
        /** World time of the latest delivery. */
        public long lastTime;
    }

    private final Map<FluidIdentifier, OverflowRecord> overflowLog = new LinkedHashMap<>();

    private void logOverflow(FluidIdentifier ident, int litres) {
        OverflowRecord record = overflowLog.computeIfAbsent(ident, k -> new OverflowRecord());
        record.litres += litres;
        record.deliveries++;
        record.lastTime = _world.getWorld() == null ? 0 : _world.getWorld().getTotalWorldTime();
    }

    /** The world this module's chassis is in, or null before it is placed. */
    public World getSinkWorld() {
        return _world == null ? null : _world.getWorld();
    }

    /** Read only; the Statistics Table merges these across the network. */
    public Map<FluidIdentifier, OverflowRecord> getOverflowLog() {
        return Collections.unmodifiableMap(overflowLog);
    }

    /** Forgets one fluid, or everything when {@code ident} is null. */
    public void clearOverflowLog(FluidIdentifier ident) {
        if (ident == null) {
            overflowLog.clear();
        } else {
            overflowLog.remove(ident);
        }
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
        if (_mode == MODE_OVERFLOW && _service.getRouter() != null) {
            // An overflow sink is often the only place left: without this the rejected rest would be addressed straight
            // back here and bounce between this pipe and the tank that just turned it away.
            item.getJamList().add(_service.getRouter().getSimpleID());
        }
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
        return NewGuiHandler.getGui(FluidSinkSlot.class).setDefaultRoute(isDefaultRoute()).setMode(_mode);
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
        return _mode == MODE_DEFAULT;
    }

    @CCCommand(description = "Sets the default route status of this module (false also turns overflow off)")
    public void setDefaultRoute(Boolean isDefaultRoute) {
        _mode = isDefaultRoute ? MODE_DEFAULT : MODE_OFF;
    }

    @CCCommand(description = "Returns the fallback mode of this module: off, default or overflow")
    public String getSinkMode() {
        return _mode == MODE_DEFAULT ? "default" : _mode == MODE_OVERFLOW ? "overflow" : "off";
    }

    @CCCommand(description = "Sets the fallback mode of this module: off, default or overflow")
    public void setSinkMode(String mode) {
        if ("default".equalsIgnoreCase(mode)) {
            _mode = MODE_DEFAULT;
        } else if ("overflow".equalsIgnoreCase(mode)) {
            _mode = MODE_OVERFLOW;
        } else {
            _mode = MODE_OFF;
        }
    }

    public int getMode() {
        return _mode;
    }

    /** Out-of-range values (a newer or garbled packet) fall back to off. */
    public void setMode(int mode) {
        _mode = mode >= 0 && mode < MODE_COUNT ? mode : MODE_OFF;
    }

    /** The mode after this one, for the GUI's cycling button. */
    public int nextMode() {
        return (_mode + 1) % MODE_COUNT;
    }

    /*
     * NBT
     */

    @Override
    public void readFromNBT(NBTTagCompound nbttagcompound) {
        _filterInventory.readFromNBT(nbttagcompound, "");
        if (nbttagcompound.hasKey("sinkMode")) {
            setMode(nbttagcompound.getInteger("sinkMode"));
        } else {
            // Saved before the mode existed: only the default-route flag.
            _mode = nbttagcompound.getBoolean("defaultdestination") ? MODE_DEFAULT : MODE_OFF;
        }
        overflowLog.clear();
        NBTTagList log = nbttagcompound.getTagList("overflowLog", 10);
        for (int i = 0; i < log.tagCount(); i++) {
            NBTTagCompound tag = log.getCompoundTagAt(i);
            FluidStack stack = FluidStack.loadFluidStackFromNBT(tag.getCompoundTag("fluid"));
            FluidIdentifier ident = stack == null ? null : FluidIdentifier.get(stack);
            if (ident == null) {
                continue; // the fluid's mod was removed
            }
            OverflowRecord record = new OverflowRecord();
            record.litres = tag.getLong("litres");
            record.deliveries = tag.getInteger("deliveries");
            record.lastTime = tag.getLong("lastTime");
            overflowLog.put(ident, record);
        }
    }

    @Override
    public void writeToNBT(NBTTagCompound nbttagcompound) {
        _filterInventory.writeToNBT(nbttagcompound, "");
        // The old flag is still written so an older jar loading this world keeps a default route as one (and reads
        // overflow as off).
        nbttagcompound.setBoolean("defaultdestination", isDefaultRoute());
        nbttagcompound.setInteger("sinkMode", _mode);
        if (!overflowLog.isEmpty()) {
            NBTTagList log = new NBTTagList();
            for (Map.Entry<FluidIdentifier, OverflowRecord> e : overflowLog.entrySet()) {
                NBTTagCompound tag = new NBTTagCompound();
                NBTTagCompound fluid = new NBTTagCompound();
                e.getKey().makeFluidStack(1).writeToNBT(fluid);
                tag.setTag("fluid", fluid);
                tag.setLong("litres", e.getValue().litres);
                tag.setInteger("deliveries", e.getValue().deliveries);
                tag.setLong("lastTime", e.getValue().lastTime);
                log.appendTag(tag);
            }
            nbttagcompound.setTag("overflowLog", log);
        }
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
