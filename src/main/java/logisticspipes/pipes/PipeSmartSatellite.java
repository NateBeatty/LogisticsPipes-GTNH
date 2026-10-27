package logisticspipes.pipes;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChatComponentText;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.IFluidHandler;

import logisticspipes.LogisticsPipes;
import logisticspipes.interfaces.routing.IAdditionalTargetInformation;
import logisticspipes.interfaces.routing.IGatedItemSink;
import logisticspipes.interfaces.routing.IRequestFluid;
import logisticspipes.interfaces.routing.IRequireReliableFluidTransport;
import logisticspipes.interfaces.routing.IRequireReliableTransport;
import logisticspipes.logisticspipes.IRoutedItem;
import logisticspipes.logisticspipes.IRoutedItem.TransportMode;
import logisticspipes.logisticspipes.SmartSatelliteTransportLayer;
import logisticspipes.logisticspipes.TransportLayer;
import logisticspipes.modules.ModuleSatelite;
import logisticspipes.modules.ModuleSmartCrafter;
import logisticspipes.modules.abstractmodules.LogisticsModule;
import logisticspipes.network.GuiIDs;
import logisticspipes.network.PacketHandler;
import logisticspipes.network.abstractpackets.ModernPacket;
import logisticspipes.network.packets.satpipe.SatPipeNext;
import logisticspipes.network.packets.satpipe.SatPipePrev;
import logisticspipes.network.packets.satpipe.SatPipeSetID;
import logisticspipes.pipes.basic.LogisticsTileGenericPipe;
import logisticspipes.pipes.basic.fluid.FluidRoutedPipe;
import logisticspipes.proxy.MainProxy;
import logisticspipes.proxy.SimpleServiceLocator;
import logisticspipes.textures.Textures;
import logisticspipes.textures.Textures.TextureType;
import logisticspipes.transport.LPTravelingItem.LPTravelingItemServer;
import logisticspipes.utils.CacheHolder.CacheTypes;
import logisticspipes.utils.FluidIdentifier;
import logisticspipes.utils.OutputPushUtil;
import logisticspipes.utils.item.ItemIdentifier;
import logisticspipes.utils.item.ItemIdentifierStack;
import logisticspipes.utils.tuples.LPPosition;
import logisticspipes.utils.tuples.Pair;

/**
 * One pipe that carries both items and fluids for a Smart Crafter, addressed by a single numeric {@link #satelliteId}.
 * It is a fluid-type pipe ({@link FluidRoutedPipe}) so it can drain its own tank and fill the facing machine's tank;
 * plain items routed to it are not handled here (they fall through to the machine's inventory in
 * {@code PipeTransportLogistics#handleTileReachedServer}).
 * <p>
 * The Smart Crafter stores this pipe's id in one of its input/output slots; the pipe finds that crafter by scanning
 * {@link ModuleSmartCrafter#AllCrafters} for a crafter whose slot array holds the id. That is NBT-derived, so it
 * survives a restart. All arrival/loss/gating is forwarded to that crafter.
 */
public class PipeSmartSatellite extends FluidRoutedPipe
        implements IRequestFluid, IRequireReliableTransport, IRequireReliableFluidTransport, IGatedItemSink {

    /**
     * Loaded Smart Satellites, keyed only by {@link #satelliteId}. Kept separate from the normal item/fluid satellite
     * registries so a normal crafter's lookups are untouched.
     */
    public static final Set<PipeSmartSatellite> AllSatellites = Collections.newSetFromMap(new WeakHashMap<>());

    // called only on server shutdown
    public static void cleanup() {
        PipeSmartSatellite.AllSatellites.clear();
    }

    public int satelliteId;
    private ModuleSmartCrafter ownerCrafter;

    public PipeSmartSatellite(Item item) {
        super(item);
        throttleTime = 40;
    }

    /* -- Fluid-pipe capability flags -- */

    @Override
    public boolean canInsertFromSideToTanks() {
        // No tank pumping: the Smart Satellite has no overflow tanks.
        return false;
    }

    @Override
    public boolean canInsertToTanks() {
        // Let endReached run; it only ever fills the facing machine.
        return true;
    }

    @Override
    public boolean canReceiveFluid() {
        // Never pushed into by the fluid pipe network; fluid arrives as container items routed to this pipe.
        return false;
    }

    @Override
    public ItemSendMode getItemSendMode() {
        return ItemSendMode.Normal;
    }

    @Override
    public TextureType getCenterTexture() {
        return Textures.LOGISTICSPIPE_SMART_SATELLITE;
    }

    @Override
    public LogisticsModule getLogisticsModule() {
        // Stray, unrouted items default-route into the machine, like a normal satellite.
        return new ModuleSatelite(this);
    }

    @Override
    public TransportLayer getTransportLayer() {
        if (_transportLayer == null) {
            _transportLayer = new SmartSatelliteTransportLayer(this, this, getRouter(), this);
        }
        return _transportLayer;
    }

    /* -- Registry / NBT / id -- */

    @Override
    public void readFromNBT(NBTTagCompound nbttagcompound) {
        super.readFromNBT(nbttagcompound);
        satelliteId = nbttagcompound.getInteger("satelliteid");
        ownerCrafter = null;
        ensureAllSatelliteStatus();
    }

    @Override
    public void writeToNBT(NBTTagCompound nbttagcompound) {
        nbttagcompound.setInteger("satelliteid", satelliteId);
        super.writeToNBT(nbttagcompound);
    }

    protected int findId(int increment) {
        if (MainProxy.isClient(getWorld())) {
            return satelliteId;
        }
        int potentialId = satelliteId;
        boolean conflict = true;
        while (conflict) {
            potentialId += increment;
            if (potentialId < 0) {
                return 0;
            }
            conflict = false;
            for (final PipeSmartSatellite sat : PipeSmartSatellite.AllSatellites) {
                if (sat.satelliteId == potentialId) {
                    conflict = true;
                    break;
                }
            }
        }
        return potentialId;
    }

    protected void ensureAllSatelliteStatus() {
        if (MainProxy.isClient()) {
            return;
        }
        if (satelliteId == 0) {
            PipeSmartSatellite.AllSatellites.remove(this);
        }
        if (satelliteId != 0) {
            PipeSmartSatellite.AllSatellites.add(this);
        }
    }

    public void setSatelliteId(int integer) {
        satelliteId = integer;
        ownerCrafter = null;
        ensureAllSatelliteStatus();
    }

    public void setNextId(EntityPlayer player) {
        satelliteId = findId(1);
        ensureAllSatelliteStatus();
        if (MainProxy.isClient(player.worldObj)) {
            final ModernPacket packet = PacketHandler.getPacket(SatPipeNext.class).setPosX(getX()).setPosY(getY())
                    .setPosZ(getZ());
            MainProxy.sendPacketToServer(packet);
        } else {
            final ModernPacket packet = PacketHandler.getPacket(SatPipeSetID.class).setSatID(satelliteId)
                    .setPosX(getX()).setPosY(getY()).setPosZ(getZ());
            MainProxy.sendPacketToPlayer(packet, player);
        }
    }

    public void setPrevId(EntityPlayer player) {
        satelliteId = findId(-1);
        ensureAllSatelliteStatus();
        if (MainProxy.isClient(player.worldObj)) {
            final ModernPacket packet = PacketHandler.getPacket(SatPipePrev.class).setPosX(getX()).setPosY(getY())
                    .setPosZ(getZ());
            MainProxy.sendPacketToServer(packet);
        } else {
            final ModernPacket packet = PacketHandler.getPacket(SatPipeSetID.class).setSatID(satelliteId)
                    .setPosX(getX()).setPosY(getY()).setPosZ(getZ());
            MainProxy.sendPacketToPlayer(packet, player);
        }
    }

    @Override
    public void onAllowedRemoval() {
        if (MainProxy.isClient(getWorld())) {
            return;
        }
        PipeSmartSatellite.AllSatellites.remove(this);
    }

    @Override
    public void onWrenchClicked(EntityPlayer entityplayer) {
        if (MainProxy.isServer(getWorld())) {
            TileEntity pushing = findPushingOutput();
            if (pushing != null) {
                entityplayer.addChatComponentMessage(
                        new ChatComponentText(
                                "Smart Satellite " + satelliteId
                                        + ": the output at "
                                        + pushing.xCoord
                                        + ", "
                                        + pushing.yCoord
                                        + ", "
                                        + pushing.zCoord
                                        + " pushes its contents out by itself, so results will bypass the crafter."
                                        + " Soft-mallet it to Machine Processing: Disabled."));
            }
        }
        // Send the satellite id when opening the GUI, then open the (reused) satellite id GUI.
        final ModernPacket packet = PacketHandler.getPacket(SatPipeSetID.class).setSatID(satelliteId).setPosX(getX())
                .setPosY(getY()).setPosZ(getZ());
        MainProxy.sendPacketToPlayer(packet, entityplayer);
        entityplayer.openGui(LogisticsPipes.instance, GuiIDs.GUI_SatelitePipe_ID, getWorld(), getX(), getY(), getZ());
    }

    /* -- Owning crafter (NBT-derived; restart-safe) -- */

    /**
     * @return the Smart Crafter whose slot array holds this pipe's {@link #satelliteId}, or null if none. Cached, and
     *         invalidated when the id changes or the cached crafter is no longer loaded/valid.
     */
    public ModuleSmartCrafter getOwner() {
        if (ownerCrafter != null && ModuleSmartCrafter.AllCrafters.contains(ownerCrafter)
                && ownsThisSatellite(ownerCrafter)) {
            return ownerCrafter;
        }
        ownerCrafter = null;
        if (satelliteId == 0) {
            return null;
        }
        for (ModuleSmartCrafter c : ModuleSmartCrafter.AllCrafters) {
            if (ownsThisSatellite(c)) {
                ownerCrafter = c;
                return c;
            }
        }
        return null;
    }

    private boolean ownsThisSatellite(ModuleSmartCrafter c) {
        int[] ids = c.advancedSatelliteIdArray;
        if (ids != null) {
            for (int id : ids) {
                if (id == satelliteId) {
                    return true;
                }
            }
        }
        // A satellite may be addressed to an output slot rather than an ingredient slot.
        for (int i = 0; i < ModuleSmartCrafter.OUTPUT_SLOTS; i++) {
            if (c.getOutputSatelliteId(i) == satelliteId) {
                return true;
            }
        }
        return false;
    }

    /**
     * The crafter a delivery to this satellite is for. Several Smart Crafters can send to one satellite (recipes that
     * share a machine's input bus), so the one named by the delivery's target information is used; only a delivery that
     * names none falls back to {@link #getOwner()}.
     */
    public ModuleSmartCrafter crafterFor(IAdditionalTargetInformation info) {
        ModuleSmartCrafter named = ModuleSmartCrafter.crafterOf(info);
        return named != null ? named : getOwner();
    }

    /** A crafter bound to this satellite whose recipe pours this fluid into its machine, or null if none does. */
    public ModuleSmartCrafter crafterWantingFluid(FluidIdentifier fluid) {
        for (ModuleSmartCrafter c : ModuleSmartCrafter.AllCrafters) {
            if (c.poursFluidInto(this, fluid)) {
                return c;
            }
        }
        return null;
    }

    /* -- Item arrival / loss: forward to the crafter the delivery was for -- */

    @Override
    public void itemArrived(ItemIdentifierStack item, IAdditionalTargetInformation info) {
        ModuleSmartCrafter c = crafterFor(info);
        if (c != null) {
            c.itemArrived(item, info);
        }
    }

    @Override
    public void itemLost(ItemIdentifierStack item, IAdditionalTargetInformation info) {
        ModuleSmartCrafter c = crafterFor(info);
        if (c != null) {
            c.itemLost(item, info);
        }
    }

    /** An order for this satellite that never left its provider: lost to the crafter, but nothing was on its way. */
    @Override
    public void itemCouldNotBeSend(ItemIdentifierStack item, IAdditionalTargetInformation info) {
        ModuleSmartCrafter.whileReportingUnsent(() -> super.itemCouldNotBeSend(item, info));
    }

    /* -- Fluid arrival / not-inserted: a container carries no word of its crafter, so the crafters sort it out -- */

    /**
     * Not counted here: this is called as the container reaches the pipe, before anything is poured, so it would count
     * fluid the machine then turns away. {@link #endReached} counts what actually went in.
     */
    @Override
    public void liquidArrived(FluidIdentifier fluid, int amount) {}

    @Override
    public void liquidNotInserted(FluidIdentifier fluid, int amount) {
        ModuleSmartCrafter.fluidNotInsertedAtSatellite(this, fluid, amount);
    }

    @Override
    public void liquidLost(FluidIdentifier fluid, int amount) {
        liquidNotInserted(fluid, amount);
    }

    @Override
    public void sendFailed(FluidIdentifier fluid, Integer amount) {
        fluidSendFailed(fluid, amount, null);
    }

    /**
     * A provider couldn't send an order for this satellite: its tanks were empty. Unlike a container lost on the way,
     * nothing of it was counted as on its way, so there is nothing to refund; the crafter the order was for asks for
     * it again instead, or its set waits for good.
     */
    public void fluidSendFailed(FluidIdentifier fluid, Integer amount, IAdditionalTargetInformation info) {
        ModuleSmartCrafter c = ModuleSmartCrafter.crafterOf(info);
        if (c == null) {
            c = crafterWantingFluid(fluid);
        }
        if (c != null) {
            c.fluidSendFailedAt(this, fluid, amount == null ? 0 : amount);
        }
    }

    /* -- Gating: delegate to the per-slot allowance of the crafter the delivery is for -- */

    @Override
    public int getGatedAllowance(ItemIdentifier item, IAdditionalTargetInformation info) {
        ModuleSmartCrafter c = crafterFor(info);
        return c == null ? Integer.MAX_VALUE : c.getGatedAllowance(item, info);
    }

    @Override
    public void onGatedSend(ItemIdentifier item, int amount, IAdditionalTargetInformation info) {
        ModuleSmartCrafter c = crafterFor(info);
        if (c != null) {
            c.onGatedSend(item, amount, info);
        }
    }

    /** Turned away if any crafter bound to this satellite gates that item into its machine. */
    @Override
    public boolean declinesUntrackedItem(ItemIdentifier item) {
        for (ModuleSmartCrafter c : ModuleSmartCrafter.AllCrafters) {
            if (c.usesSatellite(this) && c.declinesUntrackedAt(this, item)) {
                return true;
            }
        }
        return false;
    }

    /* -- Fluid delivery: fill ONLY the facing machine, report not-inserted on overflow -- */

    @Override
    public boolean endReached(LPTravelingItemServer arrivingItem, TileEntity tile) {
        if (!canInsertToTanks() || !MainProxy.isServer(getWorld())) {
            return false;
        }
        ItemIdentifierStack stack = arrivingItem.getItemIdentifierStack();
        if (stack == null || !stack.getItem().isFluidContainer()) {
            // Plain items are not handled here; they fall through to the machine's inventory.
            return false;
        }
        if (getRouter().getSimpleID() != arrivingItem.getDestination()) {
            return false;
        }
        FluidStack liquid = SimpleServiceLocator.logisticsFluidManager.getFluidFromContainer(stack);
        if (liquid == null) {
            return false;
        }
        getCacheHolder().trigger(CacheTypes.Inventory);
        FluidIdentifier fluid = FluidIdentifier.get(liquid);
        // Only what a crafter bound here is waiting for goes in (see ModuleSmartCrafter.fluidExpectedAtSatellite). The
        // rest is sent on to storage without a refund, since no slot was waiting for it.
        int expected = ModuleSmartCrafter.fluidExpectedAtSatellite(this, fluid);
        if (liquid.amount > expected) {
            FluidStack unexpected = liquid.copy();
            unexpected.amount = liquid.amount - expected;
            // TEMP [ItemGate DIAG]
            LogisticsPipes.log.info(
                    "[ItemGate DIAG] sat " + satelliteId
                            + " at "
                            + getX()
                            + ","
                            + getY()
                            + ","
                            + getZ()
                            + " FLUID DECLINED "
                            + unexpected.amount
                            + "mB "
                            + fluid
                            + " (expected "
                            + expected
                            + "mB)");
            sendOnToStorage(unexpected, arrivingItem.output);
            if (expected <= 0) {
                return true;
            }
            liquid = liquid.copy();
            liquid.amount = expected;
        }
        int arrived = liquid.amount;
        // Only the machine this satellite faces may take the fluid. No side/internal overflow tanks.
        if (isConnectableTank(tile, arrivingItem.output, false) && (tile instanceof IFluidHandler handler)) {
            fillSide(liquid, arrivingItem.output, handler);
        }
        // Counted as delivered only now, and only what went in. Counting the whole container on arrival took the
        // turned-away part off the crafter's in-flight count too, so when it was the last fluid on its way the refund
        // below found nothing in flight and was dropped: the crafter thought the fluid was in the machine for good.
        ModuleSmartCrafter.fluidArrivedAtSatellite(this, fluid, arrived - liquid.amount);
        if (liquid.amount == 0) {
            return true;
        }
        // The machine tank is full: report it so the owning crafter refunds the slot, then re-queue the leftover to a
        // sink that has room so the fluid is not voided.
        liquidNotInserted(fluid, liquid.amount);
        sendOnToStorage(liquid, arrivingItem.output);
        return true;
    }

    /** Re-queues fluid this satellite won't pour into its machine to a fluid sink that has room, so it isn't voided. */
    private void sendOnToStorage(FluidStack liquid, ForgeDirection machineSide) {
        IRoutedItem routedItem = SimpleServiceLocator.routedItemHelper
                .createNewTravelItem(SimpleServiceLocator.logisticsFluidManager.getFluidContainer(liquid));
        Pair<Integer, Integer> replies = SimpleServiceLocator.logisticsFluidManager
                .getBestReply(liquid, getRouter(), routedItem.getJamList());
        routedItem.setDestination(replies.getValue1());
        routedItem.setTransportMode(TransportMode.Passive);
        this.queueRoutedItem(routedItem, machineSide.getOpposite());
    }

    /* -- Machine access (used by the owning crafter for room checks, claims, and outputs) -- */

    /**
     * @return the direction from this satellite to the machine it faces — a connected neighbour that is not another
     *         pipe and exposes an inventory or a fluid handler — or null if there is none.
     */
    public ForgeDirection getMachineFacing() {
        for (ForgeDirection dir : ForgeDirection.VALID_DIRECTIONS) {
            if (!container.isPipeConnected(dir)) {
                continue;
            }
            LPPosition pos = new LPPosition(getX(), getY(), getZ());
            pos.moveForward(dir);
            TileEntity tile = pos.getTileEntity(getWorld());
            if (tile == null || tile instanceof LogisticsTileGenericPipe) {
                continue;
            }
            if (tile instanceof net.minecraft.inventory.IInventory || tile instanceof IFluidHandler) {
                return dir;
            }
        }
        return null;
    }

    /**
     * @return a neighbouring machine output (a GregTech output bus or hatch left on) that empties itself into the block
     *         in front of it, or null if there is none. Results pushed out that way never reach the crafter.
     */
    public TileEntity findPushingOutput() {
        return OutputPushUtil.findPushingNeighbour(getWorld(), getX(), getY(), getZ());
    }

    /**
     * @return the machine tile this satellite faces — the neighbour at {@link #getMachineFacing()} — or null if there
     *         is none.
     */
    public TileEntity getMachineTile() {
        ForgeDirection dir = getMachineFacing();
        if (dir == null) {
            return null;
        }
        LPPosition pos = new LPPosition(getX(), getY(), getZ());
        pos.moveForward(dir);
        return pos.getTileEntity(getWorld());
    }
}
