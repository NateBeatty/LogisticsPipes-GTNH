package logisticspipes.routing.order;

import java.io.IOException;
import java.util.List;

import logisticspipes.network.LPDataInputStream;
import logisticspipes.network.LPDataOutputStream;
import logisticspipes.routing.order.IOrderInfoProvider.ResourceType;
import logisticspipes.utils.item.ItemIdentifierStack;
import logisticspipes.utils.tuples.LPPosition;
import lombok.Getter;

/**
 * What a client is told about one {@link CraftingJob}: a copy taken when the list was sent, since the job itself only
 * exists on the server. Times are relative ("this many ticks ago") so the client needs no clock of its own.
 */
@Getter
public class CraftingJobInfo {

    private final int id;
    private final List<ItemIdentifierStack> requested;
    /** Null if the requester had no router. */
    private final LPPosition requesterPosition;
    private final long queuedTicksAgo;
    private final long idleTicks;
    private final int sent;
    private final int total;
    private final int openCrafts;
    private final int openDeliveries;
    private final boolean replanShort;
    /** Items crafted so far over every craft of the request, and in total ({@link CraftingJob#getCraftedProgress}). */
    private final int craftedDone;
    private final int craftedRequired;

    private CraftingJobInfo(int id, List<ItemIdentifierStack> requested, LPPosition requesterPosition,
            long queuedTicksAgo, long idleTicks, int sent, int total, int openCrafts, int openDeliveries,
            boolean replanShort, int craftedDone, int craftedRequired) {
        this.id = id;
        this.requested = requested;
        this.requesterPosition = requesterPosition;
        this.queuedTicksAgo = queuedTicksAgo;
        this.idleTicks = idleTicks;
        this.sent = sent;
        this.total = total;
        this.openCrafts = openCrafts;
        this.openDeliveries = openDeliveries;
        this.replanShort = replanShort;
        this.craftedDone = craftedDone;
        this.craftedRequired = craftedRequired;
    }

    public static CraftingJobInfo of(CraftingJob job) {
        long now = CraftingJobs.now();
        int[] crafted = job.getCraftedProgress();
        return new CraftingJobInfo(
                job.getId(),
                job.getRequested(),
                job.getRequesterPosition(),
                now - job.getQueuedTick(),
                now - job.getLastProgressTick(),
                job.getSent(),
                job.getTotal(),
                job.countOpen(ResourceType.CRAFTING),
                job.countOpen(ResourceType.PROVIDER),
                job.isReplanShort(),
                crafted[0],
                crafted[1]);
    }

    /** The first requested item, for the row's icon and name. Null only for a request of nothing. */
    public ItemIdentifierStack getMainItem() {
        return requested.isEmpty() ? null : requested.get(0);
    }

    public void write(LPDataOutputStream data) throws IOException {
        data.writeInt(id);
        data.writeList(requested, LPDataOutputStream::writeItemIdentifierStack);
        data.writeBoolean(requesterPosition != null);
        if (requesterPosition != null) {
            data.writeLPPosition(requesterPosition);
        }
        data.writeLong(queuedTicksAgo);
        data.writeLong(idleTicks);
        data.writeInt(sent);
        data.writeInt(total);
        data.writeInt(openCrafts);
        data.writeInt(openDeliveries);
        data.writeBoolean(replanShort);
        data.writeInt(craftedDone);
        data.writeInt(craftedRequired);
    }

    public static CraftingJobInfo read(LPDataInputStream data) throws IOException {
        int id = data.readInt();
        List<ItemIdentifierStack> requested = data.readList(LPDataInputStream::readItemIdentifierStack);
        LPPosition position = data.readBoolean() ? data.readLPPosition() : null;
        return new CraftingJobInfo(
                id,
                requested,
                position,
                data.readLong(),
                data.readLong(),
                data.readInt(),
                data.readInt(),
                data.readInt(),
                data.readInt(),
                data.readBoolean(),
                data.readInt(),
                data.readInt());
    }
}
