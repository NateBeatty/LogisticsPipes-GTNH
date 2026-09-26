package logisticspipes.routing.order;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import logisticspipes.modules.ModuleSmartCrafter;
import logisticspipes.routing.IRouter;
import logisticspipes.routing.order.IOrderInfoProvider.ResourceType;
import logisticspipes.utils.item.ItemIdentifierStack;
import logisticspipes.utils.tuples.LPPosition;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;

/**
 * One request and everything it set in motion: the orders on every provider and crafter it planned, sub-crafts
 * included.
 * <p>
 * LP has no such object of its own. The planner creates orders all over the network and then forgets which request they
 * were for, which is why nothing could be cancelled. This is the equivalent of AE2's {@code CraftID}, and what saving
 * crafting progress will reconnect by.
 * <p>
 * Server side only, and in memory: a restart loses jobs just as it loses the orders themselves.
 */
public class CraftingJob {

    public enum End {
        FINISHED,
        CANCELLED
    }

    @Getter
    private final int id;
    private final List<ItemIdentifierStack> requested;
    /** The requesting pipe's router, lifetime stable. Null if the requester had no router. */
    @Getter
    private final UUID requesterId;
    @Getter
    private final LPPosition requesterPosition;
    @Getter
    private final long queuedTick;
    @Getter
    private long lastProgressTick;
    /** How much of the requested items has been sent towards the requester. */
    @Getter
    private int sent;
    /** How much of the requested items was promised when the job was planned. */
    @Getter
    private int total;
    /** Whether any of its orders is a craft. Jobs that only move stock are never listed. */
    @Getter
    private boolean crafting;
    @Getter
    private boolean cancelled;
    /**
     * Part of this job was re-planned after another job it borrowed surplus from was cancelled, and the network could
     * not supply all of it. The job will not finish on its own.
     */
    @Getter
    @Setter(AccessLevel.PACKAGE)
    private boolean replanShort;

    private final List<Placed> orders = new ArrayList<>();
    private int open = 0;

    CraftingJob(int id, List<ItemIdentifierStack> requested, IRouter requester, long now) {
        this.id = id;
        this.requested = requested;
        this.requesterId = requester == null ? null : requester.getId();
        this.requesterPosition = requester == null ? null : requester.getLPPosition();
        this.queuedTick = now;
        this.lastProgressTick = now;
    }

    /** What was asked for, for display. A list request has several entries. */
    public List<ItemIdentifierStack> getRequested() {
        return Collections.unmodifiableList(requested);
    }

    public boolean isDone() {
        return open <= 0;
    }

    void add(LogisticsOrderManager<?, ?> manager, LogisticsOrder order) {
        orders.add(new Placed(manager, order));
        if (keepsJobOpen(order)) {
            open++;
        }
        if (order.getType() == ResourceType.CRAFTING) {
            crafting = true;
        }
    }

    /**
     * Surplus (extras) is linked to the job that will make it, so a later request can borrow it and a cancel can remove
     * it, but it doesn't keep the job open: the request never needed it, and a chanced byproduct that never turns up
     * would otherwise keep the job listed for ever.
     */
    private static boolean keepsJobOpen(LogisticsOrder order) {
        return order.getType() != ResourceType.EXTRA;
    }

    void progressed(LogisticsOrder order, int amount, long now) {
        lastProgressTick = now;
        if (order.isRoot()) {
            sent += amount;
        }
    }

    /** @return true if that was the last order keeping the job open */
    boolean closed(LogisticsOrder order) {
        if (!keepsJobOpen(order)) {
            return false;
        }
        open--;
        return open <= 0;
    }

    void markRoot(LogisticsOrder order, boolean countTowardsTotal) {
        order.setRoot(true);
        if (countTowardsTotal) {
            total += Math.max(0, order.getAmount());
        }
    }

    /** Orders of this job that other jobs' orders borrowed surplus from are found through these. */
    List<Placed> getPlaced() {
        return orders;
    }

    /**
     * Drops every open order of this job, on whichever pipe holds it. Items already moving still arrive; what the
     * crafters do with ingredients already in their machines is up to them (a Smart Crafter sweeps them out).
     * <p>
     * Orders in other jobs that were planned from this job's surplus are planned again, since the sets that would have
     * made that surplus are no longer coming.
     *
     * @return what was stopped, or null if the job had already ended
     */
    public CancelResult cancel() {
        if (cancelled || isDone()) {
            return null;
        }
        cancelled = true;
        // Found before our own orders go, while the link from their borrowed amounts to this job still means something.
        List<Placed> borrowers = CraftingJobs.findBorrowersOf(this);
        int crafts = 0;
        int deliveries = 0;
        Set<LogisticsOrderManager<?, ?>> managers = new HashSet<>();
        Set<IRouter> destinations = new HashSet<>();
        for (Placed placed : new ArrayList<>(orders)) {
            if (placed.order.isFinished()) {
                continue;
            }
            managers.add(placed.manager);
            if (placed.order.getRouter() != null) {
                destinations.add(placed.order.getRouter());
            }
            if (placed.order.getType() == ResourceType.CRAFTING) {
                crafts++;
            } else if (placed.order.getType() == ResourceType.PROVIDER) {
                deliveries++;
            }
            if (!placed.manager.cancel(placed.order)) {
                // Not in its queue any more, e.g. the pipe was unloaded or broken. Close it here so the job can end.
                placed.order.setFinished(true);
                CraftingJobs.orderFinished(placed.order);
            }
        }
        ModuleSmartCrafter.wakeAfterCancel(managers, destinations);
        int replannedJobs = 0;
        List<CraftingJob> shortJobs = new ArrayList<>();
        for (Placed borrower : borrowers) {
            CraftingJob other = borrower.order.getJob();
            boolean wasShort = other != null && other.isReplanShort();
            CraftingJobs.replan(borrower, this);
            replannedJobs++;
            if (other != null && !wasShort && other.isReplanShort()) {
                shortJobs.add(other);
            }
        }
        return new CancelResult(crafts, deliveries, replannedJobs, shortJobs);
    }

    /** What a cancel stopped, for telling the player. */
    @Getter
    public static final class CancelResult {

        private final int crafts;
        private final int deliveries;
        /** Orders in other jobs that were planned again because they relied on this job's surplus. */
        private final int replanned;
        /** Other jobs that couldn't be fully re-planned and will now wait. */
        private final List<CraftingJob> shortJobs;

        CancelResult(int crafts, int deliveries, int replanned, List<CraftingJob> shortJobs) {
            this.crafts = crafts;
            this.deliveries = deliveries;
            this.replanned = replanned;
            this.shortJobs = shortJobs;
        }
    }

    /**
     * The orders still open, longest without progress first. This is what the job is waiting on. Worked out only when
     * asked, never kept up to date.
     */
    public List<WaitingOn> getWaitingOn() {
        long now = CraftingJobs.now();
        List<WaitingOn> list = new ArrayList<>();
        for (Placed placed : orders) {
            LogisticsOrder order = placed.order;
            if (order.isFinished() || !keepsJobOpen(order)) {
                continue;
            }
            ItemIdentifierStack display = order.getAsDisplayItem();
            list.add(
                    new WaitingOn(
                            order.getType(),
                            display == null ? null : display.clone(),
                            placed.manager.getPosition(),
                            now - order.getLastProgressTick(),
                            order.isRoot()));
        }
        list.sort((a, b) -> Long.compare(b.idleTicks, a.idleTicks));
        return list;
    }

    /** An order together with the queue it sits in, which an order doesn't know by itself. */
    static final class Placed {

        final LogisticsOrderManager<?, ?> manager;
        final LogisticsOrder order;

        Placed(LogisticsOrderManager<?, ?> manager, LogisticsOrder order) {
            this.manager = manager;
            this.order = order;
        }
    }

    /** One open order of a job, as shown to the player or a computer. */
    @Getter
    public static final class WaitingOn {

        private final ResourceType type;
        /** The item or fluid and how much of it is still owed. */
        private final ItemIdentifierStack item;
        /** The pipe that owes it: a provider or crafter. */
        private final LPPosition position;
        private final long idleTicks;
        private final boolean root;

        WaitingOn(ResourceType type, ItemIdentifierStack item, LPPosition position, long idleTicks, boolean root) {
            this.type = type;
            this.item = item;
            this.position = position;
            this.idleTicks = idleTicks;
            this.root = root;
        }
    }
}
