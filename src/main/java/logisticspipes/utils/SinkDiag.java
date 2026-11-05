package logisticspipes.utils;

import java.util.BitSet;
import java.util.List;
import java.util.Set;

import net.minecraft.item.Item;

import logisticspipes.LogisticsPipes;
import logisticspipes.interfaces.IInventoryUtil;
import logisticspipes.modules.ModulePolymorphicItemSink;
import logisticspipes.modules.abstractmodules.LogisticsModule;
import logisticspipes.pipes.PipeLogisticsChassi;
import logisticspipes.pipes.basic.CoreRoutedPipe;
import logisticspipes.proxy.SimpleServiceLocator;
import logisticspipes.routing.ExitRoute;
import logisticspipes.routing.IRouter;
import logisticspipes.routing.PipeRoutingConnectionType;
import logisticspipes.utils.item.ItemIdentifier;
import logisticspipes.utils.tuples.Pair;

/**
 * TEMPORARY [SinkDiag]: traces one routing decision of an Extractor MK3, to see why a passive sink was passed over.
 * Switched on with the sinkRouting debug config. Only logs inside begin/end, so other routing stays out of the log.
 */
public final class SinkDiag {

    private static Thread owner;

    private SinkDiag() {}

    public static boolean isActive() {
        return owner != null && owner == Thread.currentThread();
    }

    public static void begin(ItemIdentifier item, int x, int y, int z, List<Integer> jamList) {
        owner = Thread.currentThread();
        log("=== extractor at " + x + "," + y + "," + z + " routing " + describe(item) + " jamList=" + jamList);
    }

    public static void end(Pair<Integer, SinkReply> reply) {
        if (reply == null || reply.getValue1() == null) {
            log("=== RESULT: no destination");
        } else {
            IRouter router = SimpleServiceLocator.routerManager.getRouterUnsafe(reply.getValue1(), false);
            log("=== RESULT: " + where(router) + " " + describe(reply.getValue2()));
        }
        owner = null;
    }

    public static void log(String message) {
        LogisticsPipes.log.info("[SinkDiag] " + message);
    }

    public static String describe(ItemIdentifier item) {
        return item.getFriendlyName() + " id="
                + Item.getIdFromItem(item.item)
                + " dmg="
                + item.itemDamage
                + " nbt="
                + (item.tag == null ? "none" : item.tag.toString());
    }

    public static String describe(SinkReply reply) {
        if (reply == null) {
            return "no reply";
        }
        return "priority=" + reply.fixedPriority
                + " custom="
                + reply.customPriority
                + " default="
                + reply.isDefault
                + " max="
                + reply.maxNumberOfItems;
    }

    public static String where(IRouter router) {
        if (router == null) {
            return "(no router)";
        }
        CoreRoutedPipe pipe = router.getPipe();
        if (pipe == null) {
            return "router#" + router.getSimpleID() + " (not loaded)";
        }
        return pipe.getClass().getSimpleName() + "@" + pipe.getX() + "," + pipe.getY() + "," + pipe.getZ();
    }

    /** The inventory's contents that match the item exactly or carry the same name, to expose look-alike variants. */
    public static String describeMatches(IInventoryUtil inv, ItemIdentifier item) {
        Set<ItemIdentifier> items = inv.getItems();
        StringBuilder matches = new StringBuilder();
        for (ItemIdentifier held : items) {
            if (held.equals(item) || held.getFriendlyName().equals(item.getFriendlyName())) {
                matches.append(matches.length() == 0 ? "" : " | ").append(describe(held));
                matches.append(held.equals(item) ? " (exact)" : " (DIFFERENT ITEM, same name)");
            }
        }
        return "holds " + items.size() + " item types, matching: " + (matches.length() == 0 ? "none" : matches);
    }

    /**
     * Reports every Polymorphic Item Sink chassis that faces a drawer or holds a matching item, whether or not routing
     * would ask it, so a chassis that never shows up as a candidate can still be explained.
     */
    public static void logPolymorphicChassis(ItemIdentifier item, IRouter source, BitSet interested) {
        log("routers interested in this item: " + interested.cardinality());
        CoreRoutedPipe sourcePipe = source.getPipe();
        int scanned = 0;
        for (IRouter router : SimpleServiceLocator.routerManager.getRouters()) {
            if (router == null || !(router.getPipe() instanceof PipeLogisticsChassi)) {
                continue;
            }
            PipeLogisticsChassi chassis = (PipeLogisticsChassi) router.getPipe();
            for (int i = 0; i < chassis.getChassiSize(); i++) {
                LogisticsModule module = chassis.getModules().getSubModule(i);
                if (!(module instanceof ModulePolymorphicItemSink)) {
                    continue;
                }
                scanned++;
                IInventoryUtil inv = chassis.getSneakyInventory(false, module.getSlot(), module.getPositionInt());
                if (inv == null) {
                    continue;
                }
                String matches = describeMatches(inv, item);
                boolean drawer = inv.getClass().getSimpleName().startsWith("StorageDrawers");
                if (drawer || !matches.endsWith("matching: none")) {
                    log(
                            "polymorphic " + where(router)
                                    + " slot="
                                    + i
                                    + " inv="
                                    + inv.getClass().getSimpleName()
                                    + " "
                                    + matches
                                    + " | interested="
                                    + interested.get(router.getSimpleID())
                                    + " "
                                    + describeRoute(source, router)
                                    + " enabled="
                                    + chassis.isEnabled()
                                    + " sharesInventoryWithSource="
                                    + (sourcePipe != null && chassis.sharesInterestWith(sourcePipe))
                                    + " room="
                                    + inv.roomForItem(item));
                }
                break;
            }
        }
        log("polymorphic sinks scanned: " + scanned);
    }

    private static String describeRoute(IRouter source, IRouter destination) {
        List<ExitRoute> exits = source.getDistanceTo(destination);
        if (exits == null || exits.isEmpty()) {
            return "route=NONE";
        }
        boolean canRouteTo = false;
        boolean filtered = false;
        for (ExitRoute exit : exits) {
            canRouteTo |= exit.containsFlag(PipeRoutingConnectionType.canRouteTo);
            filtered |= !exit.filters.isEmpty();
        }
        return "routes=" + exits.size() + " canRouteTo=" + canRouteTo + " throughFilter=" + filtered;
    }
}
