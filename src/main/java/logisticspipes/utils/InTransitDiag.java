package logisticspipes.utils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Queue;

import net.minecraft.tileentity.TileEntity;

import logisticspipes.LogisticsPipes;
import logisticspipes.config.Configs;
import logisticspipes.pipes.basic.CoreRoutedPipe;
import logisticspipes.proxy.MainProxy;
import logisticspipes.proxy.SimpleServiceLocator;
import logisticspipes.routing.IRouter;
import logisticspipes.routing.ItemRoutingInformation;

/**
 * TEMP [InTransit DIAG]: finds the items a pipe still counts as on their way to it long after they should have arrived.
 * Every routed item keeps a short history of what happened to it; a pipe that still holds an overdue entry logs it with
 * that history, so the last event shows where the item went missing without telling its destination.
 */
public final class InTransitDiag {

    private InTransitDiag() {}

    /** Events kept per item. The first (the send) is always kept; the oldest after it make room. */
    private static final int MAX_TRAIL = 16;

    /** How far past its timeout an entry must be before it's reported, so one about to be cleaned up isn't. */
    private static final int OVERDUE_GRACE = 200;

    /** How often each pipe looks through its list, in ticks. */
    public static final int SCAN_INTERVAL = 200;

    /** Most overdue entries one pipe reports per scan; the rest wait for the next scan. */
    private static final int MAX_REPORTS_PER_SCAN = 10;

    public static boolean on() {
        return Configs.DEBUG_IN_TRANSIT;
    }

    /** Adds an event to the item's history. A repeat of the last event is counted, not added again. */
    public static void note(ItemRoutingInformation info, String event) {
        if (info == null) {
            return;
        }
        if (info.diagTrail == null) {
            info.diagTrail = new ArrayList<>(4);
        }
        List<String> trail = info.diagTrail;
        if (!trail.isEmpty() && event.equals(info.diagLastEvent)) {
            info.diagRepeats++;
            String last = trail.get(trail.size() - 1);
            int cut = last.lastIndexOf(" (x");
            trail.set(
                    trail.size() - 1,
                    (cut >= 0 ? last.substring(0, cut) : last) + " (x" + (info.diagRepeats + 1) + ")");
            return;
        }
        info.diagLastEvent = event;
        info.diagRepeats = 0;
        if (trail.size() >= MAX_TRAIL) {
            trail.remove(1);
        }
        trail.add("t" + MainProxy.getGlobalTick() + " " + event);
    }

    /** Remembers the last pipe that made a routing decision for the item, without growing its history. */
    public static void hop(ItemRoutingInformation info, CoreRoutedPipe pipe) {
        if (info == null || pipe == null) {
            return;
        }
        info.diagHopX = pipe.getX();
        info.diagHopY = pipe.getY();
        info.diagHopZ = pipe.getZ();
        info.diagHopTick = MainProxy.getGlobalTick();
    }

    public static String at(CoreRoutedPipe pipe) {
        if (pipe == null) {
            return "(no pipe)";
        }
        return pipe.getClass().getSimpleName() + "@" + pipe.getX() + "," + pipe.getY() + "," + pipe.getZ();
    }

    public static String at(TileEntity tile) {
        if (tile == null) {
            return "(not in a pipe)";
        }
        return tile.getClass().getSimpleName() + "@" + tile.xCoord + "," + tile.yCoord + "," + tile.zCoord;
    }

    public static String at(int routerId) {
        if (routerId < 0) {
            return "(none)";
        }
        IRouter router = SimpleServiceLocator.routerManager.getRouterUnsafe(routerId, false);
        return router == null ? "router#" + routerId + " (gone)" : SinkDiag.where(router);
    }

    /**
     * Reports the entries in a pipe's in-transit list that are well past their timeout and not reported yet. They
     * should have been removed by arrival, by a reroute, or by the timeout cleanup; the history says which never came.
     */
    public static void scan(CoreRoutedPipe pipe, Collection<ItemRoutingInformation> inTransit) {
        if (inTransit.isEmpty()) {
            return;
        }
        int overdue = 0;
        int overdueItems = 0;
        List<ItemRoutingInformation> fresh = new ArrayList<>();
        for (ItemRoutingInformation info : inTransit) {
            if (info.getTickToTimeOut() >= -OVERDUE_GRACE) {
                continue;
            }
            overdue++;
            overdueItems += info.getItem().getStackSize();
            if (!info.diagReported) {
                fresh.add(info);
            }
        }
        if (fresh.isEmpty()) {
            return;
        }
        ItemRoutingInformation head = inTransit instanceof Queue ? ((Queue<ItemRoutingInformation>) inTransit).peek()
                : null;
        log(
                at(pipe) + " holds "
                        + overdue
                        + " overdue of "
                        + inTransit.size()
                        + " in-transit entries ("
                        + overdueItems
                        + " items counted as on their way); "
                        + fresh.size()
                        + " new. Cleanup only checks the queue head, which times out in "
                        + (head == null ? "?" : head.getTickToTimeOut())
                        + " ticks");
        int reported = 0;
        for (ItemRoutingInformation info : fresh) {
            if (reported >= MAX_REPORTS_PER_SCAN) {
                log("  ... more next scan");
                break;
            }
            reported++;
            info.diagReported = true;
            boolean forHere = pipe.getRouter() != null && info.destinationint == pipe.getRouter().getSimpleID();
            log(
                    "  overdue " + SinkDiag.describe(info.getItem().getItem())
                            + " x"
                            + info.getItem().getStackSize()
                            + " by "
                            + (-info.getTickToTimeOut())
                            + " ticks | destination now "
                            + at(info.destinationint)
                            + (forHere ? "" : " (NOT this pipe)")
                            + " arrived="
                            + info.arrived
                            + " mode="
                            + info._transportMode
                            + " | last routing decision "
                            + (info.diagHopTick < 0 ? "never seen"
                                    : "at " + info.diagHopX
                                            + ","
                                            + info.diagHopY
                                            + ","
                                            + info.diagHopZ
                                            + " t"
                                            + info.diagHopTick));
            log(
                    "    history: " + (info.diagTrail == null || info.diagTrail.isEmpty()
                            ? "(none: sent before the switch was turned on)"
                            : String.join(" | ", info.diagTrail)));
        }
    }

    public static void log(String message) {
        LogisticsPipes.log.info("[InTransit DIAG] " + message);
    }
}
