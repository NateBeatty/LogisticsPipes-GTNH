package logisticspipes.routing;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import logisticspipes.interfaces.routing.IAdditionalTargetInformation;
import logisticspipes.logisticspipes.IRoutedItem.TransportMode;
import logisticspipes.proxy.MainProxy;
import logisticspipes.routing.order.CraftingJob;
import logisticspipes.routing.order.IDistanceTracker;
import logisticspipes.utils.item.ItemIdentifierStack;
import lombok.Getter;
import lombok.Setter;

public class ItemRoutingInformation {

    public static class DelayComparator implements Comparator<ItemRoutingInformation> {

        @Override
        public int compare(ItemRoutingInformation o1, ItemRoutingInformation o2) {
            return (int) (o2.getTimeOut() - o1.getTimeOut()); // cast will never overflow because the delta is in
            // 1/20ths of a second.
        }
    }

    @Override
    public ItemRoutingInformation clone() {
        ItemRoutingInformation that = new ItemRoutingInformation();
        that.destinationint = destinationint;
        that.destinationUUID = destinationUUID;
        that.arrived = arrived;
        that.bufferCounter = bufferCounter;
        that._doNotBuffer = _doNotBuffer;
        that._transportMode = _transportMode;
        that.jamlist = new ArrayList<>(jamlist);
        that.tracker = tracker;
        that.targetInfo = targetInfo;
        that.job = job;
        that.item = getItem().clone();
        if (diagTrail != null) {
            that.diagTrail = new ArrayList<>(diagTrail);
            that.diagLastEvent = diagLastEvent;
            that.diagRepeats = diagRepeats;
            that.diagHopX = diagHopX;
            that.diagHopY = diagHopY;
            that.diagHopZ = diagHopZ;
            that.diagHopTick = diagHopTick;
        }
        return that;
    }

    public int destinationint = -1;
    public UUID destinationUUID;
    public boolean arrived;
    public int bufferCounter = 0;
    public boolean _doNotBuffer;
    public TransportMode _transportMode = TransportMode.Unknown;
    public List<Integer> jamlist = new ArrayList<>();
    public IDistanceTracker tracker = null;
    public IAdditionalTargetInformation targetInfo;
    /**
     * The job whose order sent this item, so its destination knows which request a lost item was for and doesn't ask
     * again for a cancelled one. Not saved: jobs don't survive a restart either.
     */
    public CraftingJob job;

    /*
     * TEMP [InTransit DIAG] (Configs.DEBUG_IN_TRANSIT): what happened to this item, for a destination that still counts
     * it as on its way long after it should have arrived. Null while the switch is off. Not saved.
     */
    public List<String> diagTrail;
    public String diagLastEvent;
    public int diagRepeats;
    public boolean diagReported;
    public int diagHopX, diagHopY, diagHopZ;
    public long diagHopTick = -1;

    private long delay = 640 + MainProxy.getGlobalTick();

    @Getter
    @Setter
    private ItemIdentifierStack item;

    public void readFromNBT(NBTTagCompound nbttagcompound) {
        if (nbttagcompound.hasKey("destinationUUID")) {
            destinationUUID = UUID.fromString(nbttagcompound.getString("destinationUUID"));
        }
        arrived = nbttagcompound.getBoolean("arrived");
        bufferCounter = nbttagcompound.getInteger("bufferCounter");
        _transportMode = TransportMode.values()[nbttagcompound.getInteger("transportMode")];
        ItemStack stack = ItemStack.loadItemStackFromNBT(nbttagcompound.getCompoundTag("Item"));
        if (stack != null) {
            setItem(ItemIdentifierStack.getFromStack(stack));
        }
    }

    public void writeToNBT(NBTTagCompound nbttagcompound) {
        if (destinationUUID != null) {
            nbttagcompound.setString("destinationUUID", destinationUUID.toString());
        }
        nbttagcompound.setBoolean("arrived", arrived);
        nbttagcompound.setInteger("bufferCounter", bufferCounter);
        nbttagcompound.setInteger("transportMode", _transportMode.ordinal());

        NBTTagCompound nbttagcompound2 = new NBTTagCompound();
        getItem().makeNormalStack().writeToNBT(nbttagcompound2);
        nbttagcompound.setTag("Item", nbttagcompound2);
    }

    // the global LP tick in which getTickToTimeOut returns 0.
    public long getTimeOut() {
        return delay;
    }

    // how many ticks until this times out
    public long getTickToTimeOut() {
        return delay - MainProxy.getGlobalTick();
    }

    public void resetDelay() {
        delay = 640 + MainProxy.getGlobalTick();
        if (tracker != null) {
            tracker.setDelay(delay);
        }
    }

    @Override
    public String toString() {
        return "(" + item
                + ", "
                + destinationint
                + ", "
                + destinationUUID
                + ", "
                + _transportMode
                + ", "
                + jamlist
                + ", "
                + delay
                + ", "
                + tracker;
    }
}
