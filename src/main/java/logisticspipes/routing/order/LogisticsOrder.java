package logisticspipes.routing.order;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import logisticspipes.interfaces.routing.IAdditionalTargetInformation;
import logisticspipes.pipes.basic.CoreRoutedPipe;
import logisticspipes.routing.IRouter;
import logisticspipes.utils.item.ItemIdentifier;
import logisticspipes.utils.tuples.LPPosition;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

@Accessors(chain = true)
public abstract class LogisticsOrder implements IOrderInfoProvider {

    private static final int MIN_DISTANCE_TO_DISPLAY = 4;

    @Getter
    private final IAdditionalTargetInformation information;

    @Getter
    @Setter
    private boolean isFinished = false;

    /*
     * Display Information
     */
    @Getter
    private final ResourceType type;

    @Getter
    @Setter
    private boolean inProgress;

    @Getter
    private boolean isWatched = false;

    @Getter
    @Setter
    private byte machineProgress = 0;

    private final List<IDistanceTracker> trackers = new CopyOnWriteArrayList<>();

    /** The request this order was made for, or null if it was made outside one. Server side only. */
    @Getter
    @Setter
    private CraftingJob job;

    /** Set once {@link CraftingJobs} has counted this order out, so no path can count it twice. */
    boolean closed;

    /** Whether this order delivers the job's requested item itself, rather than an ingredient on the way to it. */
    @Getter
    @Setter
    private boolean root;

    /** When this order last sent anything, in {@link CraftingJobs#now()} ticks. Starts at creation. */
    @Getter
    @Setter
    private long lastProgressTick;

    /**
     * The jobs whose surplus this order was planned from, and how much from each. Set when a request spends another
     * job's extras, so cancelling that job can plan this part again instead of leaving it waiting for a set that will
     * never be made. Null when nothing was borrowed.
     */
    private Map<CraftingJob, Integer> borrowed;

    public void addBorrowed(Map<CraftingJob, Integer> from) {
        if (from == null || from.isEmpty()) {
            return;
        }
        if (borrowed == null) {
            borrowed = new HashMap<>();
        }
        for (Map.Entry<CraftingJob, Integer> entry : from.entrySet()) {
            borrowed.merge(entry.getKey(), entry.getValue(), Integer::sum);
        }
    }

    /** How much of this order came from the given job's surplus. */
    public int getBorrowedFrom(CraftingJob job) {
        if (borrowed == null) {
            return 0;
        }
        Integer amount = borrowed.get(job);
        return amount == null ? 0 : amount;
    }

    public LogisticsOrder(ResourceType type, IAdditionalTargetInformation info) {
        if (type == null) {
            throw new NullPointerException();
        }
        this.type = type;
        information = info;
    }

    @Override
    public int getRouterId() {
        if (getRouter() == null) {
            return -1;
        }
        return getRouter().getSimpleID();
    }

    public abstract IRouter getRouter();

    @Override
    public void setWatched() {
        isWatched = true;
    }

    public void addDistanceTracker(IDistanceTracker tracker) {
        trackers.add(tracker);
    }

    @Override
    public List<Float> getProgresses() {
        List<Float> progresses = new ArrayList<>();
        for (IDistanceTracker tracker : trackers) {
            if (!tracker.hasReachedDestination() && !tracker.isTimeout()) {
                float f;
                if (tracker.getInitialDistanceToTarget() != 0) {
                    f = ((float) tracker.getCurrentDistanceToTarget()) / ((float) tracker.getInitialDistanceToTarget());
                } else {
                    f = 1.0F;
                }
                if (!progresses.contains(f)) {
                    if (tracker.getInitialDistanceToTarget() > LogisticsOrder.MIN_DISTANCE_TO_DISPLAY
                            || tracker.getInitialDistanceToTarget() == 0) {
                        progresses.add(f);
                    }
                }
            }
        }
        return progresses;
    }

    public abstract void sendFailed();

    public abstract int getAmount();

    public abstract void reduceAmountBy(int amount);

    @Override
    public ItemIdentifier getTargetType() {
        IRouter router = getRouter();
        if (router == null) return null;
        CoreRoutedPipe pipe = router.getPipe();
        if (pipe == null) return null;
        return ItemIdentifier.get(pipe.item, 0, null);
    }

    @Override
    public LPPosition getTargetPosition() {
        IRouter router = getRouter();
        if (router == null) return null;
        return router.getLPPosition();
    }
}
