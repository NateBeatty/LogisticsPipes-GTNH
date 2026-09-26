package logisticspipes.routing.order;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import logisticspipes.LogisticsPipes;
import logisticspipes.modules.ModuleSmartCrafter;
import logisticspipes.proxy.SimpleServiceLocator;
import logisticspipes.request.RequestTree;
import logisticspipes.routing.ExitRoute;
import logisticspipes.routing.IRouter;
import logisticspipes.routing.order.IOrderInfoProvider.ResourceType;
import logisticspipes.utils.item.ItemIdentifier;
import logisticspipes.utils.item.ItemIdentifierStack;

/**
 * Every live {@link CraftingJob}, plus running totals of what is being crafted.
 * <p>
 * The totals are kept up to date as orders change (added, sent, failed, cancelled) rather than worked out when asked,
 * so a computer or gui asking "how much is being crafted" costs a map lookup instead of a walk over every pipe on the
 * network. The hooks are in {@link LogisticsOrderManager} and its subclasses, which every order change goes through;
 * {@link #reconcile} recounts now and then as a safety net and logs anything a hook missed.
 * <p>
 * Server side only. Everything here runs on the server thread: requests, order changes and packets alike.
 */
public final class CraftingJobs {

    /** How often the running totals are checked against a full recount. */
    private static final int RECONCILE_TICKS = 1200;
    /** How long an ended job is remembered, so a gui refresh can say how it ended. */
    private static final int ENDED_MEMORY_TICKS = 200;

    /** Server ticks. LP's own global tick counts client ticks in single player, so it pauses with the game menu. */
    private static long tick = 0;
    private static int nextId = 1;
    /** Jobs with at least one craft that haven't ended. Jobs that only move stock are never kept here. */
    private static final Map<Integer, CraftingJob> LIVE = new LinkedHashMap<>();
    private static final List<Ended> ENDED = new ArrayList<>();
    /** The job new orders belong to, while a request is being fulfilled. */
    private static CraftingJob current = null;
    /** Whether a re-plan's top-level orders deliver its job's requested item, and so count as root orders. */
    private static boolean replanAsRoot = false;

    /** Items still owed by crafting orders. */
    private static final Map<ItemIdentifier, Integer> ORDERED = new HashMap<>();
    /** Items in sets a Smart Crafter has released into its machine and not yet taken out. */
    private static final Map<ItemIdentifier, Integer> CRAFTING = new HashMap<>();

    private static boolean reportedDrift = false;

    private CraftingJobs() {}

    public static long now() {
        return tick;
    }

    public static void serverTick() {
        tick++;
        if (tick % RECONCILE_TICKS == 0) {
            reconcile();
        }
        if (!ENDED.isEmpty()) {
            ENDED.removeIf(e -> tick - e.tick > ENDED_MEMORY_TICKS);
        }
    }

    /** Called on server shutdown. */
    public static void clear() {
        LIVE.clear();
        ENDED.clear();
        ORDERED.clear();
        CRAFTING.clear();
        current = null;
        replanAsRoot = false;
    }

    /* Queries */

    public static CraftingJob get(int id) {
        return LIVE.get(id);
    }

    /**
     * The job behind a request's order tree, as kept by a request table. All orders of one request share a job, so the
     * first stamped order found is enough. Null if none of them carries one.
     */
    public static CraftingJob findJob(LinkedLogisticsOrderList orders) {
        if (orders == null) {
            return null;
        }
        for (IOrderInfoProvider info : orders) {
            if (info instanceof LogisticsOrder && ((LogisticsOrder) info).getJob() != null) {
                return ((LogisticsOrder) info).getJob();
            }
        }
        for (LinkedLogisticsOrderList sub : orders.getSubOrders()) {
            CraftingJob job = findJob(sub);
            if (job != null) {
                return job;
            }
        }
        return null;
    }

    /**
     * Live crafting jobs requested from the network this router is on, oldest first. A job whose requester is on
     * another network (or no longer exists) is left out, so one player's table can't see or cancel another network's
     * requests.
     */
    public static List<CraftingJob> getLiveOn(IRouter router) {
        List<CraftingJob> jobs = new ArrayList<>();
        if (router == null || LIVE.isEmpty()) {
            return jobs;
        }
        Set<Integer> network = new HashSet<>();
        network.add(router.getSimpleID());
        for (ExitRoute route : router.getIRoutersByCost()) {
            if (route != null && route.destination != null) {
                network.add(route.destination.getSimpleID());
            }
        }
        for (CraftingJob job : LIVE.values()) {
            if (network.contains(SimpleServiceLocator.routerManager.getIDforUUID(job.getRequesterId()))) {
                jobs.add(job);
            }
        }
        return jobs;
    }

    /** Live jobs that craft something, oldest first. */
    public static Collection<CraftingJob> getLive() {
        return Collections.unmodifiableCollection(LIVE.values());
    }

    /** Jobs that ended in the last few seconds, and how. */
    public static List<Ended> getRecentlyEnded() {
        return Collections.unmodifiableList(ENDED);
    }

    /** How much of this item crafting orders still owe, network-wide. */
    public static int getOrdered(ItemIdentifier item) {
        Integer amount = ORDERED.get(item);
        return amount == null ? 0 : amount;
    }

    /** How much of this item is in sets released into machines, i.e. actually being made. Smart Crafters only. */
    public static int getCrafting(ItemIdentifier item) {
        Integer amount = CRAFTING.get(item);
        return amount == null ? 0 : amount;
    }

    /** Owed but not in a machine yet. For ordinary crafters that is everything, since LP can't see their machines. */
    public static int getQueued(ItemIdentifier item) {
        return Math.max(0, getOrdered(item) - getCrafting(item));
    }

    /* Request scope */

    /**
     * Makes new orders belong to a job until {@link #exit}. Always pair the two in {@code try/finally}.
     *
     * @return the job that was current before, to hand back to {@link #exit}
     */
    public static CraftingJob enter(CraftingJob job) {
        CraftingJob previous = current;
        current = job;
        return previous;
    }

    public static void exit(CraftingJob previous) {
        current = previous;
    }

    /** The job new orders currently join, or null outside a request. */
    public static CraftingJob current() {
        return current;
    }

    /**
     * A job for a request that is about to be fulfilled. If a request is already being worked on (a re-request after a
     * failed delivery, or a re-plan) the new orders join that job instead of starting their own.
     */
    public static CraftingJob beginRequest(List<ItemIdentifierStack> requested, IRouter requester) {
        if (current != null) {
            return current;
        }
        return new CraftingJob(nextId++, requested, requester, tick);
    }

    /**
     * Marks which orders deliver the requested items themselves, once the request has been fulfilled.
     *
     * @param rootLevels the order lists holding the requested items' own orders: the request's list itself, or for a
     *                   request of several items, one sub-list per item
     */
    public static void endRequest(CraftingJob job, boolean joined, List<LinkedLogisticsOrderList> rootLevels) {
        if (job == null) {
            return;
        }
        boolean mark = !joined || replanAsRoot;
        if (mark) {
            for (LinkedLogisticsOrderList level : rootLevels) {
                if (level == null) {
                    continue;
                }
                for (IOrderInfoProvider info : level) {
                    if (info instanceof LogisticsOrder && ((LogisticsOrder) info).getJob() == job) {
                        job.markRoot((LogisticsOrder) info, !joined);
                    }
                }
            }
        }
        if (!joined && job.isDone()) {
            LIVE.remove(job.getId());
        }
    }

    /* Hooks, called by the order managers */

    static void orderAdded(LogisticsOrderManager<?, ?> manager, LogisticsOrder order) {
        order.setLastProgressTick(tick);
        order.setInitialAmount(Math.max(0, order.getAmount()));
        if (isCraftedItem(order)) {
            add(ORDERED, itemOf(order), order.getAmount());
        }
        CraftingJob job = current;
        if (job == null) {
            return;
        }
        order.setJob(job);
        job.add(manager, order);
        if (job.isCrafting() && !job.isCancelled()) {
            LIVE.putIfAbsent(job.getId(), job);
        }
    }

    static void orderProgressed(LogisticsOrder order, int number) {
        int counted = Math.min(number, Math.max(0, order.getAmount()));
        if (isCraftedItem(order)) {
            add(ORDERED, itemOf(order), -counted);
        }
        order.setLastProgressTick(tick);
        if (order.getJob() != null) {
            order.getJob().progressed(order, counted, tick);
        }
    }

    static void orderShrunk(LogisticsOrder order, int amount) {
        int dropped = Math.min(amount, Math.max(0, order.getAmount()));
        if (isCraftedItem(order)) {
            add(ORDERED, itemOf(order), -dropped);
        }
        order.setInitialAmount(Math.max(0, order.getInitialAmount() - dropped));
    }

    /** An order left its queue for good: done, failed, cancelled, or spent as another request's extra. */
    static void orderFinished(LogisticsOrder order) {
        if (order.closed) {
            return;
        }
        order.closed = true;
        if (isCraftedItem(order)) {
            add(ORDERED, itemOf(order), -Math.max(0, order.getAmount()));
        }
        CraftingJob job = order.getJob();
        if (job != null && job.closed(order)) {
            ended(job);
        }
    }

    /** A Smart Crafter's released sets changed. */
    public static void addCrafting(ItemIdentifier item, int delta) {
        add(CRAFTING, item, delta);
    }

    private static void ended(CraftingJob job) {
        if (LIVE.remove(job.getId()) != null) {
            // A finished job's last state goes with it: the gui refreshes once a second, so the last list it got was
            // usually taken just before the final items went out ("76/77 crafted" on a finished job). A cancelled job
            // keeps what the gui last saw instead, i.e. how far it got before the cancel.
            boolean cancelled = job.isCancelled();
            ENDED.add(
                    new Ended(
                            job.getId(),
                            cancelled ? CraftingJob.End.CANCELLED : CraftingJob.End.FINISHED,
                            tick,
                            cancelled ? null : CraftingJobInfo.of(job)));
        }
    }

    /* Cancel support */

    /** Open craft orders in other live jobs that were planned from this job's surplus. */
    static List<CraftingJob.Placed> findBorrowersOf(CraftingJob lender) {
        List<CraftingJob.Placed> borrowers = new ArrayList<>();
        for (CraftingJob job : LIVE.values()) {
            if (job == lender || job.isCancelled()) {
                continue;
            }
            for (CraftingJob.Placed placed : job.getPlaced()) {
                if (!placed.order.isFinished() && placed.order.getBorrowedFrom(lender) > 0) {
                    borrowers.add(placed);
                }
            }
        }
        return borrowers;
    }

    /**
     * Plans again the part of an order that relied on a cancelled job's surplus. The new request joins the borrower's
     * job and goes to the same destination with the same target information, so the order's destination can't tell the
     * difference. The request comes first and the borrowed part is dropped after: the other way round, a job whose only
     * open order this is would end before its replacement exists. If the network can't supply it all, the job is
     * flagged {@link CraftingJob#isReplanShort()}.
     */
    static void replan(CraftingJob.Placed borrower, CraftingJob lender) {
        LogisticsOrder order = borrower.order;
        if (order.isFinished() || !(order instanceof LogisticsItemOrder)) {
            return;
        }
        LogisticsItemOrder itemOrder = (LogisticsItemOrder) order;
        int amount = Math.min(order.getBorrowedFrom(lender), order.getAmount());
        if (amount <= 0 || itemOrder.getDestination() == null) {
            return;
        }
        CraftingJob job = order.getJob();
        CraftingJob previous = enter(job);
        boolean previousAsRoot = replanAsRoot;
        replanAsRoot = order.isRoot();
        int got;
        try {
            got = RequestTree.request(
                    new ItemIdentifierStack(itemOrder.getResource().getItem(), amount),
                    itemOrder.getDestination(),
                    null,
                    true,
                    false,
                    false,
                    false,
                    RequestTree.defaultRequestFlags,
                    order.getInformation());
        } finally {
            exit(previous);
            replanAsRoot = previousAsRoot;
        }
        // Only what the new request covers is dropped. Whatever it couldn't supply stays on the old order, which will
        // wait for ever: that is the honest state, and it keeps the job listed (and flagged) instead of letting it end
        // as if it had finished.
        if (got >= order.getAmount()) {
            borrower.manager.cancel(order);
        } else if (got > 0) {
            borrower.manager.shrink(order, got);
        }
        if (got < amount && job != null) {
            job.setReplanShort(true);
            LogisticsPipes.log.info(
                    "Crafting job " + job.getId()
                            + ": re-planned "
                            + got
                            + " of "
                            + amount
                            + " "
                            + itemOrder.getResource().getItem()
                            + " after job "
                            + lender.getId()
                            + " was cancelled; the rest can't be supplied.");
        }
    }

    /* Running totals */

    private static boolean isCraftedItem(LogisticsOrder order) {
        return order.getType() == ResourceType.CRAFTING && order instanceof LogisticsItemOrder;
    }

    private static ItemIdentifier itemOf(LogisticsOrder order) {
        return ((LogisticsItemOrder) order).getResource().getItem();
    }

    private static void add(Map<ItemIdentifier, Integer> totals, ItemIdentifier item, int delta) {
        if (item == null || delta == 0) {
            return;
        }
        Integer before = totals.get(item);
        int after = (before == null ? 0 : before) + delta;
        if (after <= 0) {
            totals.remove(item);
        } else {
            totals.put(item, after);
        }
    }

    /**
     * Recounts the totals from the jobs and crafters themselves and replaces the running ones if they differ. A
     * difference means some order change went around the hooks; it is logged once so the missing hook can be found.
     */
    private static void reconcile() {
        Map<ItemIdentifier, Integer> ordered = new HashMap<>();
        for (CraftingJob job : LIVE.values()) {
            for (CraftingJob.Placed placed : job.getPlaced()) {
                if (!placed.order.closed && isCraftedItem(placed.order)) {
                    add(ordered, itemOf(placed.order), placed.order.getAmount());
                }
            }
        }
        Map<ItemIdentifier, Integer> crafting = new HashMap<>();
        for (ModuleSmartCrafter crafter : ModuleSmartCrafter.AllCrafters) {
            crafter.addPublishedCrafting(crafting);
        }
        boolean drift = !ordered.equals(ORDERED) || !crafting.equals(CRAFTING);
        if (drift && !reportedDrift) {
            reportedDrift = true;
            LogisticsPipes.log.warn(
                    "Crafting totals drifted from a recount (a hook was missed somewhere). Ordered: " + ORDERED
                            + " vs "
                            + ordered
                            + "; crafting: "
                            + CRAFTING
                            + " vs "
                            + crafting);
        }
        if (drift) {
            ORDERED.clear();
            ORDERED.putAll(ordered);
            CRAFTING.clear();
            CRAFTING.putAll(crafting);
        }
    }

    /** A job that ended recently. */
    public static final class Ended {

        public final int id;
        public final CraftingJob.End end;
        public final long tick;
        /** The job's state when it finished, for the gui's "Done" row. Null for a cancelled job. */
        public final CraftingJobInfo finalInfo;

        public Ended(int id, CraftingJob.End end, long tick, CraftingJobInfo finalInfo) {
            this.id = id;
            this.end = end;
            this.tick = tick;
            this.finalInfo = finalInfo;
        }
    }
}
