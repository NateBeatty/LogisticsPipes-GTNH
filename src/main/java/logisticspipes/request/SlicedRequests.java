package logisticspipes.request;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.function.Consumer;
import java.util.function.Supplier;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ChatComponentTranslation;

import logisticspipes.LogisticsPipes;
import logisticspipes.config.Configs;
import logisticspipes.request.resources.IResource;
import logisticspipes.routing.order.LinkedLogisticsOrderList;

/**
 * Plans big player requests a few milliseconds per tick instead of all at once, so a deep request (thousands of crafts)
 * no longer freezes the server while it is worked out. Off unless {@link Configs#REQUEST_SLICE_MS} is above 0.
 * <p>
 * The planner runs on a thread of its own, but in lockstep with the server: each tick the server thread hands it the
 * slice budget and waits until it yields, so the two never run at the same time and the planner reads the network
 * exactly as it would from the server thread. It yields at {@link #checkpoint()}, called for every new request tree
 * node, so the recursive planner needs no rewriting - it just stops where it is between ticks.
 * <p>
 * The network keeps changing while a request is paused, so nothing is placed until the plan is finished and checked
 * again on the server thread ({@link RequestTreeNode#stillProvidable()}); placing the orders follows in the same tick.
 * One request is planned at a time; others wait their turn.
 */
public final class SlicedRequests {

    private SlicedRequests() {}

    /** How many times a finished plan whose stock went away meanwhile is worked out again before giving up. */
    private static final int REPLANS = 1;

    /** The planner thread. The side check in {@code MainProxy} treats it as the server. */
    public static final class PlannerThread extends Thread {

        private PlannerThread() {
            super("LogisticsPipes Request Planner");
            setDaemon(true);
        }

        @Override
        public void run() {
            while (true) {
                toPlanner.acquireUninterruptibly();
                Calculation calc = running;
                try {
                    calc.finish = calc.plan.get();
                } catch (Aborted ignored) {
                    calc.finish = null;
                } catch (Throwable t) {
                    calc.error = t;
                }
                calc.planned = true;
                toServer.release();
            }
        }
    }

    /** Thrown at a checkpoint to unwind a calculation that is being dropped. */
    private static final class Aborted extends RuntimeException {

        Aborted() {
            super(null, null, false, false);
        }
    }

    /** How a finished plan ended, as {@link Finish} reports it. */
    public enum Result {
        /** Orders placed. */
        PLACED,
        /** Couldn't be met; the player was told what is missing. */
        MISSING,
        /** A preview, shown to the player; nothing to place. */
        SHOWN,
        /** Stock the plan counted on went away while it was being worked out; it should be worked out again. */
        STALE
    }

    /** What a finished plan does on the server thread. */
    public interface Finish {

        Result run();
    }

    private static final class Calculation {

        final int id;
        /** What was asked for, for the [Request DIAG] log. */
        final String what;
        final EntityPlayer player;
        /** Runs on the planner thread; works the request out and returns what to do with it on the server thread. */
        final Supplier<Finish> plan;
        int replansLeft = REPLANS;
        boolean announced = false;
        // Written by the planner, read by the server after the hand-over, which orders the two.
        Finish finish;
        Throwable error;
        boolean planned;
        int nodes;
        // [Request DIAG] figures, server thread only
        long startTick = -1;
        int slices;
        long planNanos;
        long longestSliceNanos;
        int overruns;

        Calculation(int id, String what, EntityPlayer player, Supplier<Finish> plan) {
            this.id = id;
            this.what = what;
            this.player = player;
            this.plan = plan;
        }
    }

    private static final Semaphore toPlanner = new Semaphore(0);
    private static final Semaphore toServer = new Semaphore(0);
    private static final ArrayDeque<Calculation> waiting = new ArrayDeque<>();
    private static PlannerThread planner;
    private static Calculation running;
    private static volatile long sliceEnd;
    private static volatile boolean aborting;
    private static long ticks;
    private static int nextId = 1;

    /** A slice running this many times over its budget is logged: one long step with no checkpoint in it. */
    private static final int OVERRUN_FACTOR = 3;

    public static boolean enabled() {
        return Configs.REQUEST_SLICE_MS > 0;
    }

    /**
     * Queues a request. When nothing else is being planned, the first slice runs straight away, so a request that fits
     * in one slice is answered at once, just as without slicing.
     *
     * @param what what was asked for, for the log
     * @param plan runs on the planner thread: builds the request tree and returns what to do with it once finished
     */
    public static void submit(EntityPlayer player, String what, Supplier<Finish> plan) {
        Calculation calc = new Calculation(nextId++, what, player, plan);
        if (Configs.DEBUG_REQUESTS) {
            diag(
                    "#" + calc.id
                            + " queued: "
                            + what
                            + " for "
                            + (player == null ? "?" : player.getCommandSenderName())
                            + ", "
                            + (waiting.size() + (running == null ? 0 : 1))
                            + " ahead");
        }
        waiting.addLast(calc);
        if (running == null) {
            startNext();
        }
    }

    /** Called at the end of every server tick. */
    public static void serverTick() {
        ticks++;
        if (running == null && !waiting.isEmpty()) {
            startNext();
        } else if (running != null) {
            runSlice();
        }
    }

    /** Server stopping: drops every request still being planned. */
    public static void clear() {
        waiting.clear();
        if (running != null) {
            Calculation calc = running;
            aborting = true;
            runSlice();
            aborting = false;
            report(calc, "dropped, server stopping");
        }
        running = null;
    }

    /**
     * Plans a request at once, as without slicing, timing it for the [Request DIAG] log so the two ways can be
     * compared.
     */
    public static void timeUnsliced(String what, Runnable request) {
        if (!Configs.DEBUG_REQUESTS) {
            request.run();
            return;
        }
        long start = System.nanoTime();
        request.run();
        diag(what + ": planned at once in " + ms(System.nanoTime() - start) + " ms (slicing off)");
    }

    /**
     * Called for every new request tree node. On the planner thread this is where it pauses once its slice is used up;
     * anywhere else it does nothing.
     */
    static void checkpoint() {
        if (Thread.currentThread() != planner) {
            return;
        }
        running.nodes++;
        if (aborting) {
            throw new Aborted();
        }
        if (System.nanoTime() < sliceEnd) {
            return;
        }
        toServer.release();
        toPlanner.acquireUninterruptibly();
        if (aborting) {
            throw new Aborted();
        }
    }

    private static void startNext() {
        running = waiting.pollFirst();
        if (running == null) {
            return;
        }
        if (planner == null) {
            planner = new PlannerThread();
            planner.start();
        }
        runSlice();
        if (running != null && !running.announced && running.player != null) {
            running.announced = true;
            running.player.addChatMessage(new ChatComponentTranslation("lp.misc.request.calculating"));
        }
    }

    /** Lets the planner work for one slice, then finishes the request if the plan is done. Server thread only. */
    private static void runSlice() {
        Calculation calc = running;
        long budget = Configs.REQUEST_SLICE_MS * 1_000_000L;
        long start = System.nanoTime();
        sliceEnd = start + budget;
        toPlanner.release();
        toServer.acquireUninterruptibly();
        long took = System.nanoTime() - start;
        if (calc.startTick < 0) {
            calc.startTick = ticks;
        }
        calc.slices++;
        calc.planNanos += took;
        calc.longestSliceNanos = Math.max(calc.longestSliceNanos, took);
        if (took > budget * OVERRUN_FACTOR) {
            calc.overruns++;
            if (calc.overruns == 1 && Configs.DEBUG_REQUESTS) {
                diag(
                        "#" + calc.id
                                + " slice took "
                                + ms(took)
                                + " ms, budget "
                                + Configs.REQUEST_SLICE_MS
                                + " ms: one long step with no pause in it (only the first such slice is logged)");
            }
        }
        if (!calc.planned || aborting) {
            return;
        }
        running = null;
        if (calc.error != null) {
            LogisticsPipes.log.error("Request planning failed", calc.error);
            tell(calc, "lp.misc.request.error");
            report(calc, "error while planning, see the stack trace above");
            return;
        }
        if (calc.finish == null) {
            report(calc, "dropped");
            return;
        }
        Result result;
        try {
            result = calc.finish.run();
        } catch (RuntimeException e) {
            LogisticsPipes.log.error("Placing a planned request failed", e);
            tell(calc, "lp.misc.request.error");
            report(calc, "error while placing it, see the stack trace above");
            return;
        }
        if (result != Result.STALE) {
            report(calc, result.name().toLowerCase());
            return;
        }
        // Stock the plan counted on was taken while it was being worked out.
        if (calc.replansLeft > 0) {
            report(calc, "stale, planning it again");
            Calculation again = new Calculation(calc.id, calc.what + " (again)", calc.player, calc.plan);
            again.replansLeft = calc.replansLeft - 1;
            again.announced = true;
            waiting.addFirst(again);
        } else {
            report(calc, "stale again, given up");
            tell(calc, "lp.misc.request.changed");
        }
    }

    /** [Request DIAG]: how a calculation ended, and what it took. */
    private static void report(Calculation calc, String outcome) {
        if (!Configs.DEBUG_REQUESTS) {
            return;
        }
        diag(
                "#" + calc.id
                        + " "
                        + calc.what
                        + ": "
                        + outcome
                        + " after "
                        + (ticks - calc.startTick + 1)
                        + " tick(s), "
                        + calc.slices
                        + " slice(s), "
                        + ms(calc.planNanos)
                        + " ms planning (longest slice "
                        + ms(calc.longestSliceNanos)
                        + " ms"
                        + (calc.overruns > 0 ? ", " + calc.overruns + " over budget" : "")
                        + "), "
                        + calc.nodes
                        + " nodes");
    }

    static void diag(String message) {
        LogisticsPipes.log.info("[Request DIAG] " + message);
    }

    private static String ms(long nanos) {
        return String.format("%.1f", nanos / 1_000_000.0);
    }

    private static void tell(Calculation calc, String key) {
        if (calc.player != null) {
            calc.player.addChatMessage(new ChatComponentTranslation(key));
        }
    }

    /**
     * Holds on to what the planner reports, to hand it to the real log on the server thread: the real logs send packets
     * and place watcher hooks, which belong on the server thread.
     */
    static final class DeferredLog implements RequestLog {

        private final List<Consumer<RequestLog>> calls = new ArrayList<>();

        @Override
        public void handleMissingItems(List<IResource> resources) {
            calls.add(log -> log.handleMissingItems(resources));
        }

        @Override
        public void handleSucessfullRequestOf(IResource item, LinkedLogisticsOrderList paticipating) {
            calls.add(log -> log.handleSucessfullRequestOf(item, paticipating));
        }

        @Override
        public void handleSucessfullRequestOfList(List<IResource> resources, LinkedLogisticsOrderList paticipating) {
            calls.add(log -> log.handleSucessfullRequestOfList(resources, paticipating));
        }

        void replayTo(RequestLog log) {
            if (log == null) {
                return;
            }
            for (Consumer<RequestLog> call : calls) {
                call.accept(log);
            }
        }
    }
}
