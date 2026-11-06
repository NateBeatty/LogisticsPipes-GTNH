package logisticspipes.config;

import java.io.File;

import net.minecraftforge.common.config.Configuration;
import net.minecraftforge.common.config.Property;

import cpw.mods.fml.common.Loader;
import logisticspipes.LPConstants;

// @formatter:off
// CHECKSTYLE:OFF

public class Configs {

    public static final String CATEGORY_MULTITHREAD = "multithread";
    public static final String CATEGORY_DEBUG = "debug";

    private static Configuration CONFIGURATION;

    // Configrables
    public static int LOGISTICS_DETECTION_LENGTH = 50;
    public static int LOGISTICS_DETECTION_COUNT = 100;
    public static int LOGISTICS_DETECTION_FREQUENCY = 20 * 30;
    public static boolean LOGISTICS_ORDERER_COUNT_INVERTWHEEL = false;
    public static boolean LOGISTICS_ORDERER_PAGE_INVERTWHEEL = false;
    public static final float LOGISTICS_ROUTED_SPEED_MULTIPLIER = 20F;
    public static final float LOGISTICS_DEFAULTROUTED_SPEED_MULTIPLIER = 10F;
    public static int MAX_UNROUTED_CONNECTIONS = 32;

    public static int LOGISTICS_HUD_RENDER_DISTANCE = 15;

    public static float pipeDurability = 0.25F; // TODO

    public static boolean LOGISTICS_POWER_USAGE_DISABLED = false;
    public static boolean ENABLE_RESEARCH_SYSTEM = false;

    public static boolean TOOLTIP_INFO = LPConstants.DEBUG;
    public static boolean MANDATORY_CARPENTER_RECIPES = true;
    public static boolean ENABLE_BETA_RECIPES = false;
    public static boolean ENABLE_PARTICLE_FX = true;

    // GuiOrderer Popup setting
    public static boolean DISPLAY_POPUP = true;

    // MultiThread
    public static int MULTI_THREAD_NUMBER = 4;
    public static int MULTI_THREAD_PRIORITY = Thread.NORM_PRIORITY;

    public static double POWER_USAGE_MULTIPLIER = 1;
    public static int LOGISTICS_CRAFTING_TABLE_POWER_USAGE = 250;

    public static boolean CHECK_FOR_UPDATES = true;

    public static boolean EASTER_EGGS = true;

    public static boolean OPAQUE = false;

    public static int MAX_ROBOT_DISTANCE = 64;

    public static int MAX_LOGISTICS_FLUID_TRANSPORT_INNER_CAPACITY = 10000;

    public static int FLUID_BUFFER_SAME_FLUID_MIN_RANGE = 8;
    public static int FLUID_BUFFER_SAME_FLUID_MAX_RANGE = 64;

    // Debug logging, all off by default: switched on in the config to trace a stall, then off again
    public static boolean DEBUG_ITEM_GATE = false;
    public static boolean DEBUG_FLUID_GATE = false;
    public static boolean DEBUG_FLUID_ORDER = false;
    public static boolean DEBUG_CANCEL = false;
    public static boolean DEBUG_TRICKLE = false;
    public static boolean DEBUG_SMART_CRAFTER_RECIPE = false;
    public static boolean DEBUG_FLUID_BUFFER = false;
    public static boolean DEBUG_REQUESTS = false;
    public static boolean DEBUG_SINK_ROUTING = false;
    public static boolean DEBUG_IN_TRANSIT = false;

    /** Milliseconds per tick a player's request may be planned for, spreading big ones over several ticks; 0 = off. */
    public static int REQUEST_SLICE_MS = 0;

    private static boolean loaded = false;

    public static void load() {
        if (Configs.loaded) {
            return;
        }
        if (Loader.instance().getConfigDir() == null) {
            return;
        }
        Configs.CONFIGURATION = new Configuration(new File(Loader.instance().getConfigDir(), "LogisticsPipes.cfg"));
        Configs.CONFIGURATION.load();
        Configs.loaded = true;

        if (Configs.CONFIGURATION.hasCategory("logisticspipe.id")
                || Configs.CONFIGURATION.hasCategory("logisticsPipe.id")) {
            throw new RuntimeException("Old config, please remove it and manually reconfigure LogisticPipes");
        }

        Configs.LOGISTICS_DETECTION_LENGTH = Configs.CONFIGURATION
                .get(
                        Configuration.CATEGORY_GENERAL,
                        "detectionLength",
                        Configs.LOGISTICS_DETECTION_LENGTH,
                        "The maximum shortest length between logistics pipes. This is an indicator on the maxim depth of the recursion algorithm to discover logistics neighbours. A low value might use less CPU, a high value will allow longer pipe sections")
                .getInt();
        Configs.LOGISTICS_DETECTION_COUNT = Configs.CONFIGURATION
                .get(
                        Configuration.CATEGORY_GENERAL,
                        "detectionCount",
                        Configs.LOGISTICS_DETECTION_COUNT,
                        "The maximum number of buildcraft pipes (including forks) between logistics pipes. This is an indicator of the maximum amount of nodes the recursion algorithm will visit before giving up. As it is possible to fork a pipe connection using standard BC pipes the algorithm will attempt to discover all available destinations through that pipe. Do note that the logistics system will not interfere with the operation of non-logistics pipes. So a forked pipe will usually be sup-optimal, but it is possible. A low value might reduce CPU usage, a high value will be able to handle more complex pipe setups. If you never fork your connection between the logistics pipes this has the same meaning as detectionLength and the lower of the two will be used")
                .getInt();
        Configs.LOGISTICS_DETECTION_FREQUENCY = Math.max(
                Configs.CONFIGURATION
                        .get(
                                Configuration.CATEGORY_GENERAL,
                                "reDetectionFrequency",
                                Configs.LOGISTICS_DETECTION_FREQUENCY,
                                "The amount of time that passes between checks to see if it is still connected to its neighbours (Independently from block place detection). A low value will mean that it will correct wrong values faster but use more CPU. A high value means error correction takes longer, but CPU consumption is reduced. A value of 20 will check about every second (default 600 [30 seconds])")
                        .getInt(),
                1);

        Configs.MAX_ROBOT_DISTANCE = Configs.CONFIGURATION
                .get(
                        Configuration.CATEGORY_GENERAL,
                        "maxRobotDistance",
                        Configs.MAX_ROBOT_DISTANCE,
                        "The max. distance between two robots when there is no zone defined.")
                .getInt();
        Configs.LOGISTICS_ORDERER_COUNT_INVERTWHEEL = Configs.CONFIGURATION
                .get(
                        Configuration.CATEGORY_GENERAL,
                        "ordererCountInvertWheel",
                        Configs.LOGISTICS_ORDERER_COUNT_INVERTWHEEL,
                        "Inverts the the mouse wheel scrolling for remote order number of items")
                .getBoolean(false);
        Configs.LOGISTICS_ORDERER_PAGE_INVERTWHEEL = Configs.CONFIGURATION
                .get(
                        Configuration.CATEGORY_GENERAL,
                        "ordererPageInvertWheel",
                        Configs.LOGISTICS_ORDERER_PAGE_INVERTWHEEL,
                        "Inverts the the mouse wheel scrolling for remote order pages")
                .getBoolean(false);

        Configs.LOGISTICS_POWER_USAGE_DISABLED = Configs.CONFIGURATION
                .get(
                        Configuration.CATEGORY_GENERAL,
                        "powerUsageDisabled",
                        Configs.LOGISTICS_POWER_USAGE_DISABLED,
                        "Disable the power usage trough LogisticsPipes")
                .getBoolean(false);

        Configs.LOGISTICS_HUD_RENDER_DISTANCE = Configs.CONFIGURATION
                .get(
                        Configuration.CATEGORY_GENERAL,
                        "HUDRenderDistance",
                        Configs.LOGISTICS_HUD_RENDER_DISTANCE,
                        "The max. distance between a player and the HUD that get's shown in blocks.")
                .getInt();

        Configs.DISPLAY_POPUP = Configs.CONFIGURATION
                .get(
                        Configuration.CATEGORY_GENERAL,
                        "displayPopup",
                        Configs.DISPLAY_POPUP,
                        "Set the default configuration for the popup of the Orderer Gui. Should it be used?")
                .getBoolean(false);

        Configs.MANDATORY_CARPENTER_RECIPES = Configs.CONFIGURATION
                .get(
                        Configuration.CATEGORY_GENERAL,
                        "mandatoryCarpenterRecipes",
                        Configs.MANDATORY_CARPENTER_RECIPES,
                        "Whether or not the Carpenter is required to craft Forestry related pipes/modules.")
                .getBoolean(false);
        Configs.ENABLE_BETA_RECIPES = Configs.CONFIGURATION
                .get(
                        Configuration.CATEGORY_GENERAL,
                        "enableBetaRecipes",
                        Configs.ENABLE_BETA_RECIPES,
                        "Wether or not the Beta-Recipes for Stuff shall be used for pipes/modules.")
                .getBoolean(false);
        Configs.ENABLE_PARTICLE_FX = Configs.CONFIGURATION
                .get(
                        Configuration.CATEGORY_GENERAL,
                        "enableParticleFX",
                        Configs.ENABLE_PARTICLE_FX,
                        "Whether or not special particles will spawn.")
                .getBoolean(false);

        if (Configs.CONFIGURATION.hasKey(Configs.CATEGORY_MULTITHREAD, "enabled")) {
            // ConfigCategory.remove is deprecated, but there's no other way to remove a key-value pair without
            // completely recreating the config...
            Configs.CONFIGURATION.getCategory(Configs.CATEGORY_MULTITHREAD).remove("enabled");
        }
        Configs.MULTI_THREAD_NUMBER = Configs.CONFIGURATION
                .get(
                        Configs.CATEGORY_MULTITHREAD,
                        "count",
                        Configs.MULTI_THREAD_NUMBER,
                        "Number of routing table update Threads, 0 to disable.")
                .getInt();
        if (Configs.MULTI_THREAD_NUMBER < 0) {
            Configs.MULTI_THREAD_NUMBER = 0;
            Configs.CONFIGURATION
                    .get(
                            Configs.CATEGORY_MULTITHREAD,
                            "count",
                            Configs.MULTI_THREAD_NUMBER,
                            "Number of routing table update Threads, 0 to disable.")
                    .set(Integer.toString(Configs.MULTI_THREAD_NUMBER));
        }
        Configs.MULTI_THREAD_PRIORITY = Configs.CONFIGURATION
                .get(
                        Configs.CATEGORY_MULTITHREAD,
                        "priority",
                        Configs.MULTI_THREAD_PRIORITY,
                        "Priority of the multiThread Threads. 10 is highest, 5 normal, 1 lowest")
                .getInt();
        if (Configs.MULTI_THREAD_PRIORITY < 1 || Configs.MULTI_THREAD_PRIORITY > 10) {
            Configs.MULTI_THREAD_PRIORITY = Thread.NORM_PRIORITY;
            Configs.CONFIGURATION
                    .get(
                            Configs.CATEGORY_MULTITHREAD,
                            "priority",
                            Configs.MULTI_THREAD_PRIORITY,
                            "Priority of the multiThread Threads. 10 is highest, 5 normal, 1 lowest")
                    .set(Integer.toString(Thread.NORM_PRIORITY));
        }

        Configs.POWER_USAGE_MULTIPLIER = Configs.CONFIGURATION
                .get(
                        Configuration.CATEGORY_GENERAL, "powerUsageMultiplyer",
                        Configs.POWER_USAGE_MULTIPLIER, "A Multiplyer for the power usage.")
                .getDouble(Configs.POWER_USAGE_MULTIPLIER);

        if (Configs.POWER_USAGE_MULTIPLIER <= 0) {
            Configs.POWER_USAGE_MULTIPLIER = 1;
            Configs.CONFIGURATION
                    .get(
                            Configuration.CATEGORY_GENERAL,
                            "powerUsageMultiplyer",
                            Configs.POWER_USAGE_MULTIPLIER,
                            "A Multiplyer for the power usage.")
                    .set(1);
        }

        Configs.LOGISTICS_CRAFTING_TABLE_POWER_USAGE = Math.max(
                Configs.CONFIGURATION
                        .get(
                                Configuration.CATEGORY_GENERAL,
                                "logisticsCraftingTablePowerUsage",
                                Configs.LOGISTICS_CRAFTING_TABLE_POWER_USAGE,
                                "Number of LPower units the Logistics Crafting Table uses per craft.")
                        .getInt(),
                0);

        Configs.CHECK_FOR_UPDATES = Configs.CONFIGURATION
                .get(
                        Configuration.CATEGORY_GENERAL,
                        "checkForUpdates",
                        Configs.CHECK_FOR_UPDATES,
                        "Should LogisticsPipes check for updates?")
                .getBoolean(false);

        Configs.OPAQUE = Configs.CONFIGURATION
                .get(Configuration.CATEGORY_GENERAL, "OpaquePipes", Configs.OPAQUE, "Render every LP pipe opaque.")
                .getBoolean(false);

        Configs.EASTER_EGGS = Configs.CONFIGURATION
                .get(Configuration.CATEGORY_GENERAL, "easterEggs", Configs.EASTER_EGGS, "Do you fancy easter eggs?")
                .getBoolean(false);

        Configs.MAX_LOGISTICS_FLUID_TRANSPORT_INNER_CAPACITY = Configs.CONFIGURATION
            .get(
                Configuration.CATEGORY_GENERAL,
                "maxLogisticsFluidInnerCapacity",
                Configs.MAX_LOGISTICS_FLUID_TRANSPORT_INNER_CAPACITY,
                "Sets the maximum amount for a liquid that can be held in a fluid pipe. Default value: 10000 (1000 equals 1 Bucket for normal fluids). If you are playing GTNH this value represents liters (10000 = 10000L).")
            .getInt();

        Configs.FLUID_BUFFER_SAME_FLUID_MIN_RANGE = Math.max(
                Configs.CONFIGURATION
                        .get(
                                Configuration.CATEGORY_GENERAL,
                                "fluidBufferSameFluidMinRange",
                                Configs.FLUID_BUFFER_SAME_FLUID_MIN_RANGE,
                                "How much further away (route cost, about one per pipe block) than the nearest Fluid Crafting Buffer a buffer that already holds a fluid may be, and still be chosen for more of that fluid, while every buffer tank in reach is empty. Keeps one fluid in one tank instead of spread over several. Grows towards fluidBufferSameFluidMaxRange as the tanks fill up. Both 0 always uses the nearest buffer.")
                        .getInt(),
                0);
        Configs.FLUID_BUFFER_SAME_FLUID_MAX_RANGE = Math.max(
                Configs.CONFIGURATION
                        .get(
                                Configuration.CATEGORY_GENERAL,
                                "fluidBufferSameFluidMaxRange",
                                Configs.FLUID_BUFFER_SAME_FLUID_MAX_RANGE,
                                "The same as fluidBufferSameFluidMinRange, once no buffer tank in reach is empty. Never less than the minimum.")
                        .getInt(),
                0);

        Configs.DEBUG_ITEM_GATE = Configs.CONFIGURATION
                .get(
                        Configs.CATEGORY_DEBUG,
                        "itemGate",
                        Configs.DEBUG_ITEM_GATE,
                        "Log every change to a Smart Crafter's item gate: claims, releases, sends, arrivals, losses and finished sets. [ItemGate DIAG] in the log. Very verbose.")
                .getBoolean(false);
        Configs.DEBUG_FLUID_GATE = Configs.CONFIGURATION
                .get(
                        Configs.CATEGORY_DEBUG,
                        "fluidGate",
                        Configs.DEBUG_FLUID_GATE,
                        "Log a Smart Crafter's fluid slots (credit, allowance, in flight) about once a second. [FluidGate DIAG] in the log. Verbose.")
                .getBoolean(false);
        Configs.DEBUG_FLUID_ORDER = Configs.CONFIGURATION
                .get(
                        Configs.CATEGORY_DEBUG,
                        "fluidOrder",
                        Configs.DEBUG_FLUID_ORDER,
                        "Log every order a Fluid Provider takes and every send that finds its tanks empty. [FluidOrder DIAG] in the log.")
                .getBoolean(false);
        Configs.DEBUG_CANCEL = Configs.CONFIGURATION
                .get(
                        Configs.CATEGORY_DEBUG,
                        "cancel",
                        Configs.DEBUG_CANCEL,
                        "Log what cancelling a crafting job drops, and how Fluid Supplier Mk2s re-request afterwards. [Cancel DIAG] in the log.")
                .getBoolean(false);
        Configs.DEBUG_TRICKLE = Configs.CONFIGURATION
                .get(
                        Configs.CATEGORY_DEBUG,
                        "trickleSupplier",
                        Configs.DEBUG_TRICKLE,
                        "Log why a Trickle Supplier's request failed or is held back. [Trickle DIAG] in the log.")
                .getBoolean(false);
        Configs.DEBUG_SMART_CRAFTER_RECIPE = Configs.CONFIGURATION
                .get(
                        Configs.CATEGORY_DEBUG,
                        "smartCrafterRecipe",
                        Configs.DEBUG_SMART_CRAFTER_RECIPE,
                        "Log the results a Smart Crafter reads from its recipe. [SmartCrafter DEBUG] in the log.")
                .getBoolean(false);
        Configs.DEBUG_FLUID_BUFFER = Configs.CONFIGURATION
                .get(
                        Configs.CATEGORY_DEBUG,
                        "fluidBuffer",
                        Configs.DEBUG_FLUID_BUFFER,
                        "Log every choice of Fluid Crafting Buffer for a delivery: the range, each buffer in reach with its distance, whether it holds the fluid and has room, and the one chosen. [FluidBuffer DIAG] in the log.")
                .getBoolean(false);
        Configs.DEBUG_REQUESTS = Configs.CONFIGURATION
                .get(
                        Configs.CATEGORY_DEBUG,
                        "requests",
                        Configs.DEBUG_REQUESTS,
                        "Log each request made from a request table or request pipe: how long it took to plan, and with requestSliceMs above 0 the ticks and slices it took, slices that ran well over budget, and why a finished plan was found out of date. [Request DIAG] in the log. About one line per request.")
                .getBoolean(false);

        Configs.REQUEST_SLICE_MS = Math.max(
                0,
                Configs.CONFIGURATION.get(
                        Configuration.CATEGORY_GENERAL,
                        "requestSliceMs",
                        Configs.REQUEST_SLICE_MS,
                        "Milliseconds per server tick spent planning a request made from a request table or request pipe. A big request (thousands of crafts) is then worked out over several ticks instead of freezing the server until it is done; small ones still finish at once. Requests from suppliers, crafters and computers are not affected. 0 turns this off and plans every request at once, as before. Try 5.")
                        .getInt());

        Configs.DEBUG_SINK_ROUTING = Configs.CONFIGURATION
                .get(
                        Configs.CATEGORY_DEBUG,
                        "sinkRouting",
                        Configs.DEBUG_SINK_ROUTING,
                        "Log how an Extractor MK3 picks a destination, at most once a second per module: each candidate pipe, its reply or why it was skipped, and the winner. Also logs chassis that turn an item away on arrival. [SinkDiag] in the log.")
                .getBoolean(false);
        Configs.DEBUG_IN_TRANSIT = Configs.CONFIGURATION
                .get(
                        Configs.CATEGORY_DEBUG,
                        "inTransitLeaks",
                        Configs.DEBUG_IN_TRANSIT,
                        "Find items a pipe still counts as on their way to it long after they should have arrived or timed out (these use up a chest's room, so routing skips it). Keeps a short history of every routed item and logs it for each overdue one. [InTransit DIAG] in the log.")
                .getBoolean(false);

        Configs.CONFIGURATION.save();
    }

    public static void savePopupState() {
        Property pageDisplayPopupProperty = Configs.CONFIGURATION.get(
                Configuration.CATEGORY_GENERAL,
                "displayPopup",
                Configs.DISPLAY_POPUP,
                "Set the default configuration for the popup of the Orderer Gui. Should it be used?");
        pageDisplayPopupProperty.set(Boolean.toString(Configs.DISPLAY_POPUP));
        Configs.CONFIGURATION.save();
    }
}
