package logisticspipes.modules;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.renderer.texture.IIconRegister;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.ISidedInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.IIcon;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.fluids.FluidStack;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import logisticspipes.interfaces.IInventoryUtil;
import logisticspipes.interfaces.ISlotUpgradeManager;
import logisticspipes.interfaces.routing.IAdditionalTargetInformation;
import logisticspipes.interfaces.routing.IGatedItemSink;
import logisticspipes.interfaces.routing.IRequestItems;
import logisticspipes.network.NewGuiHandler;
import logisticspipes.network.PacketHandler;
import logisticspipes.network.abstractguis.ModuleCoordinatesGuiProvider;
import logisticspipes.network.abstractguis.ModuleInHandGuiProvider;
import logisticspipes.network.abstractpackets.ModernPacket;
import logisticspipes.network.guis.module.inhand.SmartCrafterInHand;
import logisticspipes.network.guis.module.inpipe.SmartCrafterModuleSlot;
import logisticspipes.network.packets.cpipe.SmartCrafterSetting;
import logisticspipes.network.packets.pipe.SmartCrafterUpdatePacket;
import logisticspipes.proxy.MainProxy;
import logisticspipes.proxy.SimpleServiceLocator;
import logisticspipes.request.IReqCraftingTemplate;
import logisticspipes.request.RequestTree;
import logisticspipes.request.resources.DictResource;
import logisticspipes.request.resources.IResource;
import logisticspipes.request.resources.ItemResource;
import logisticspipes.routing.IRouter;
import logisticspipes.routing.LogisticsPromise;
import logisticspipes.routing.order.IOrderInfoProvider.ResourceType;
import logisticspipes.routing.order.LogisticsItemOrder;
import logisticspipes.utils.CacheHolder.CacheTypes;
import logisticspipes.utils.SidedInventoryMinecraftAdapter;
import logisticspipes.utils.item.ItemIdentifier;
import logisticspipes.utils.item.ItemIdentifierInventory;
import logisticspipes.utils.item.ItemIdentifierStack;
import logisticspipes.utils.string.StringUtils;
import lombok.Getter;

/**
 * A crafting module that only lets ingredients be sent once the machine has room for whole recipe sets.
 * <p>
 * When orders arrive it releases as many sets as fit in the machine. Each time a set's result is pulled out, room is
 * checked again and more sets are released. Providers ask {@link #getGatedAllowance} before sending, so held
 * ingredients stay reserved in storage instead of bouncing off a full machine.
 * <p>
 * Only ingredients delivered to this module's own machine are gated. Ingredients sent to satellites are not.
 * <p>
 * Sets are only released while this module holds the machine's claim ({@link MachineClaims}), so two Smart Crafters on
 * one machine take turns instead of mixing their ingredients. When another crafter is waiting, the owner stops
 * releasing new sets, lets the ones in the machine finish and hands the machine over.
 */
public class ModuleSmartCrafter extends ModuleCrafter implements IGatedItemSink {

    private static final int INGREDIENT_SLOTS = 9;
    /** Result slots, each with its own {@link OutputRole}. They follow the ingredient slots in the inventory. */
    public static final int OUTPUT_SLOTS = 3;
    private static final int INVENTORY_SIZE = INGREDIENT_SLOTS + OUTPUT_SLOTS;
    /** Chance values are whole percent. */
    private static final int GUARANTEED = 100;
    /** Upper limit on sets released but not yet finished, so a huge inventory can't make the room check loop long. */
    private static final int MAX_SETS_IN_FLIGHT = 128;
    /** Slow re-check for results the module never saw (taken by a player, auto-output, failed chanced craft). */
    private static final int SAFETY_NET_TICKS = 100;
    /** How often a held claim is refreshed. Must be well below {@link MachineClaims#CLAIM_TIMEOUT_TICKS}. */
    private static final int CLAIM_REFRESH_TICKS = 20;
    /**
     * Released sets are given up after this long with no progress, once everything was delivered and none of the
     * ingredients are left in the machine. Covers results that never come back to this module.
     */
    private static final int STUCK_TICKS = 400;
    /** Upper limit on stacks pulled out in one sweep, so a huge leftover pile can't flood the network at once. */
    private static final int MAX_SWEEP_STACKS = 64;
    /** How often results no order is waiting for are cleared out of the machine. */
    private static final int UNORDERED_OUTPUT_TICKS = 20;

    /** Items per ingredient slot that providers may still send for the released sets. */
    private final int[] allowance = new int[INGREDIENT_SLOTS];
    /** Items per ingredient slot that were sent but haven't arrived yet. */
    private final int[] inFlight = new int[INGREDIENT_SLOTS];
    private int setsReleased = 0;
    /** Results pulled out since the gate was last reset, per output slot. */
    private final int[] extractedPerOutput = new int[OUTPUT_SLOTS];
    /** Sets already taken off {@link #setsReleased} by {@link #onResultExtracted}, so none is counted twice. */
    private int creditedSets = 0;
    private boolean gateDirty = true; // run the load sweep on the first tick
    private long lastProgressTick = 0;

    /** The machine this module currently holds the claim for, or null. */
    private MachineClaims.Key claimedMachine = null;
    /** The machine this module is waiting to claim, or null. */
    private MachineClaims.Key awaitedMachine = null;
    /** Whether sets were released since the claim was taken, so a new owner always gets at least one turn. */
    private boolean releasedThisTurn = false;
    private long claimTakenTick = 0;

    /** Sweep leftovers once the module is loaded: orders aren't saved, so anything of this recipe there is stale. */
    private boolean pendingLoadSweep = true;
    /** Whether this crafter had orders last time the gate was updated, to notice when its last order finishes. */
    private boolean hadOrders = false;
    /** Sweep leftovers before releasing the first sets after taking a machine. */
    private boolean sweepBeforeRelease = false;
    /** Sets asked for through the gui's "Request set" button, which have no order behind them. */
    private int pendingManualSets = 0;
    /** Master switch for the built-in sweeps. Off leaves whatever is in the machine alone. */
    private boolean cleanupEnabled = true;

    /** True when not even one recipe set fits in an empty machine, so the gate can never open. */
    @Getter
    private boolean setTooLarge = false;

    /**
     * What an output slot means for planning. All three are pulled out of the machine; they differ in what the planner
     * may do with them.
     */
    public enum OutputRole {

        /** The module advertises itself as a way to craft this item ({@link #canCraft}). */
        PRODUCT,
        /** Made on the side. Never craftable, never promised; goes to storage. */
        BYPRODUCT,
        /**
         * Made on the side, and registered as an extra once the craft runs, so a <b>later</b> request can spend it
         * instead of sourcing that item elsewhere. It cannot help the request that produced it:
         * {@code RequestTreeNode.checkForExtras} walks the tree's {@code extrapromises}, never its {@code byproducts},
         * which are only handed to {@code registerExtras} in {@code fullFill}, after planning is done.
         */
        BYPRODUCT_COUNTED;

        public OutputRole next(boolean allowCounted) {
            switch (this) {
                case PRODUCT:
                    return BYPRODUCT;
                case BYPRODUCT:
                    return allowCounted ? BYPRODUCT_COUNTED : PRODUCT;
                default:
                    return PRODUCT;
            }
        }
    }

    private final OutputRole[] outputRole = new OutputRole[OUTPUT_SLOTS];
    /** Percent chance of getting this output from one set. {@link #GUARANTEED} means every set yields it. */
    private final int[] outputChance = new int[OUTPUT_SLOTS];
    /**
     * Which satellite an output comes out of, for a recipe whose results appear somewhere other than the block this
     * module faces (a multiblock's output bus). <b>Stored and edited, but nothing reads it yet:</b> extracting through
     * a satellite needs the Smart Satellite, which doesn't exist. Until then every output is taken from this module's
     * own machine, whatever is set here.
     */
    private final int[] outputSatelliteId = new int[OUTPUT_SLOTS];

    public ModuleSmartCrafter() {
        _dummyInventory = new ItemIdentifierInventory(
                INVENTORY_SIZE,
                StringUtils.translate("gui.module.requestedItems"),
                127);
        for (int i = 0; i < OUTPUT_SLOTS; i++) {
            outputRole[i] = i == 0 ? OutputRole.PRODUCT : OutputRole.BYPRODUCT;
            outputChance[i] = GUARANTEED;
        }
    }

    /* Outputs */

    public static int outputInventorySlot(int output) {
        return INGREDIENT_SLOTS + output;
    }

    public ItemIdentifierStack getOutput(int output) {
        return _dummyInventory.getIDStackInSlot(outputInventorySlot(output));
    }

    public OutputRole getOutputRole(int output) {
        return outputRole[output];
    }

    public void setOutputRole(int output, OutputRole role) {
        outputRole[output] = role == OutputRole.BYPRODUCT_COUNTED && isChanced(output) ? OutputRole.BYPRODUCT : role;
    }

    public int getOutputChance(int output) {
        return outputChance[output];
    }

    public void setOutputChance(int output, int chance) {
        outputChance[output] = Math.max(1, Math.min(GUARANTEED, chance));
        // A chanced output may not be promised: the set it was planned into can produce nothing.
        if (isChanced(output) && outputRole[output] == OutputRole.BYPRODUCT_COUNTED) {
            outputRole[output] = OutputRole.BYPRODUCT;
        }
    }

    public boolean isChanced(int output) {
        return outputChance[output] < GUARANTEED;
    }

    /** See {@link #outputSatelliteId}: set by the player, not acted on yet. */
    public int getOutputSatelliteId(int output) {
        return outputSatelliteId[output];
    }

    public void setOutputSatelliteId(int output, int satelliteId) {
        outputSatelliteId[output] = Math.max(0, satelliteId);
    }

    /** The output whose arrivals count finished sets. */
    private int primaryOutput() {
        for (int i = 0; i < OUTPUT_SLOTS; i++) {
            if (outputRole[i] == OutputRole.PRODUCT && getOutput(i) != null) {
                return i;
            }
        }
        return -1;
    }

    /** True while no output can be requested, so nothing will ever start this recipe. */
    public boolean hasNoCraftableOutput() {
        return primaryOutput() < 0;
    }

    @Override
    public ItemIdentifierStack getConfiguredCraftResult() {
        int primary = primaryOutput();
        return primary < 0 ? null : getOutput(primary);
    }

    @Override
    public List<ItemIdentifierStack> getConfiguredCraftResults() {
        List<ItemIdentifierStack> list = new ArrayList<>(OUTPUT_SLOTS);
        for (int i = 0; i < OUTPUT_SLOTS; i++) {
            if (outputRole[i] != OutputRole.PRODUCT) {
                continue;
            }
            ItemIdentifierStack output = getOutput(i);
            if (output != null) {
                list.add(output);
            }
        }
        return list;
    }

    @Override
    public boolean canCraft(IResource toCraft) {
        if (!(toCraft instanceof ItemResource) && !(toCraft instanceof DictResource)) {
            return false;
        }
        for (ItemIdentifierStack result : getConfiguredCraftResults()) {
            if (toCraft.matches(result.getItem(), IResource.MatchSettings.NORMAL)) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected void addTemplateByproducts(IReqCraftingTemplate template) {
        for (int i = 0; i < OUTPUT_SLOTS; i++) {
            ItemIdentifierStack output = getOutput(i);
            if (outputRole[i] == OutputRole.BYPRODUCT_COUNTED && output != null) {
                template.addByproduct(output);
            }
        }
    }

    /** The byproduct upgrade's extra slot doesn't exist here; roles do the same job. */
    @Override
    public ItemIdentifierStack getByproductItem() {
        return null;
    }

    /** Built in, no Advanced Satellite upgrade needed. */
    @Override
    protected boolean usesPerSlotSatellites() {
        return true;
    }

    /* Settings sync */

    @Override
    public ModernPacket getCPipePacket() {
        SmartCrafterUpdatePacket packet = PacketHandler.getPacket(SmartCrafterUpdatePacket.class);
        int[] roles = new int[OUTPUT_SLOTS];
        int[] chances = new int[OUTPUT_SLOTS];
        for (int i = 0; i < OUTPUT_SLOTS; i++) {
            roles[i] = outputRole[i].ordinal();
            chances[i] = outputChance[i];
        }
        packet.setOutputRole(roles).setOutputChance(chances).setOutputSatelliteId(outputSatelliteId.clone())
                .setCleanupEnabled(cleanupEnabled)
                .setStatus(MainProxy.isServer(getWorld()) ? computeStatus() : lastStatus).setSetsReleased(setsReleased);
        packet.setSatelliteId(satelliteId).setAdvancedSatelliteIdArray(advancedSatelliteIdArray).setPriority(priority)
                .setAmount(amount).setLiquidSatelliteIdArray(liquidSatelliteIdArray)
                .setLiquidSatelliteId(liquidSatelliteId);
        packet.setModulePos(this);
        return packet;
    }

    public void handleSmartUpdatePacket(SmartCrafterUpdatePacket packet) {
        int[] roles = packet.getOutputRole();
        int[] chances = packet.getOutputChance();
        int[] satellites = packet.getOutputSatelliteId();
        for (int i = 0; i < OUTPUT_SLOTS; i++) {
            if (i < roles.length && roles[i] >= 0 && roles[i] < OutputRole.values().length) {
                outputRole[i] = OutputRole.values()[roles[i]];
            }
            if (i < chances.length && chances[i] > 0) {
                outputChance[i] = Math.min(GUARANTEED, chances[i]);
            }
            if (i < satellites.length) {
                outputSatelliteId[i] = Math.max(0, satellites[i]);
            }
        }
        cleanupEnabled = packet.isCleanupEnabled();
        lastStatus = packet.getStatus();
        setsReleased = packet.getSetsReleased();
    }

    /** Client-side copy of the last status the server sent, for the gui's warning line. */
    private int lastStatus = STATUS_IDLE;

    public int getLastStatus() {
        return lastStatus;
    }

    /** Applies one setting from {@link SmartCrafterSetting}, on either side. */
    public void handleSettingPacket(int setting, int index, int value) {
        switch (setting) {
            case SmartCrafterSetting.SATELLITE:
                if (index >= 0 && index < INGREDIENT_SLOTS) {
                    advancedSatelliteIdArray[index] = Math.max(0, value);
                    gateDirty = true;
                }
                return;
            case SmartCrafterSetting.OUTPUT_SATELLITE:
                if (index >= 0 && index < OUTPUT_SLOTS) {
                    setOutputSatelliteId(index, value);
                }
                return;
            case SmartCrafterSetting.OUTPUT_ROLE:
                if (index >= 0 && index < OUTPUT_SLOTS && value >= 0 && value < OutputRole.values().length) {
                    setOutputRole(index, OutputRole.values()[value]);
                }
                return;
            case SmartCrafterSetting.OUTPUT_CHANCE:
                if (index >= 0 && index < OUTPUT_SLOTS) {
                    setOutputChance(index, value);
                }
                return;
            case SmartCrafterSetting.CLEANUP:
                cleanupEnabled = value != 0;
                return;
            default:
        }
    }

    public boolean isCleanupEnabled() {
        return cleanupEnabled;
    }

    @Override
    protected ModuleCoordinatesGuiProvider getPipeGuiProvider() {
        return NewGuiHandler.getGui(SmartCrafterModuleSlot.class);
    }

    @Override
    protected ModuleInHandGuiProvider getInHandGuiProvider() {
        return NewGuiHandler.getGui(SmartCrafterInHand.class);
    }

    /* Status, for the gui's warning line */

    public static final int STATUS_RUNNING = 0;
    public static final int STATUS_IDLE = 1;
    public static final int STATUS_NO_PRODUCT = 2;
    public static final int STATUS_NO_MACHINE = 3;
    public static final int STATUS_SET_TOO_LARGE = 4;
    public static final int STATUS_WAITING_CLAIM = 5;
    public static final int STATUS_HOLDING = 6;

    public int computeStatus() {
        if (hasNoCraftableOutput()) {
            return STATUS_NO_PRODUCT;
        }
        if (_service == null || _service.getRealInventory() == null) {
            return STATUS_NO_MACHINE;
        }
        if (setTooLarge) {
            return STATUS_SET_TOO_LARGE;
        }
        if (!_service.getItemOrderManager().hasOrders(ResourceType.CRAFTING, ResourceType.EXTRA)) {
            return STATUS_IDLE;
        }
        MachineClaims.Key machine = machineKey();
        if (machine != null && MachineClaims.isHeldByOther(machine, this, now())) {
            return STATUS_WAITING_CLAIM;
        }
        return setsReleased > 0 ? STATUS_RUNNING : STATUS_HOLDING;
    }

    /** Sets released into the machine and not yet finished, shown next to the status. */
    public int getSetsReleased() {
        return setsReleased;
    }

    /**
     * Requests one more set of ingredients. For sets a machine consumed without producing anything (GTNH cleanrooms
     * void ingredients), where nothing is reported lost and the order would otherwise wait forever.
     */
    public void requestOneSet(EntityPlayer player) {
        int slots = 0;
        int satisfied = 0;
        for (int slot = 0; slot < INGREDIENT_SLOTS; slot++) {
            ItemIdentifierStack material = getMaterials(slot);
            if (material == null || material.getStackSize() <= 0) {
                continue;
            }
            slots++;
            IRequestItems target = this;
            if (advancedSatelliteIdArray[slot] != 0) {
                IRouter router = getSatelliteRouter(slot);
                if (router != null) {
                    target = (IRequestItems) router.getPipe();
                }
            }
            int got = RequestTree.requestPartial(
                    new ItemIdentifierStack(material.getItem(), material.getStackSize()),
                    target,
                    new CraftingChassieInformation(slot, getPositionInt()));
            if (got >= material.getStackSize()) {
                satisfied++;
            }
        }
        if (slots == 0) {
            say(player, "no ingredients configured");
            return;
        }
        // One more set of work for the gate, which then grants the allowance through the normal release path (room
        // check and machine claim included). Raising the allowance here instead would be undone by the next
        // updateGate: with no order behind this set it takes the "nothing to do" branch and resets the gate.
        pendingManualSets++;
        int waitingBefore = pendingManualSets;
        // Run the gate now rather than on the next tick, so the answer below is what actually happened.
        updateGate();
        String outcome = pendingManualSets < waitingBefore ? "on its way" : whyNotReleased();
        say(player, satisfied + "/" + slots + " ingredients found, " + outcome);
    }

    /** Why the gate didn't hand a set over, for the "Request set" reply. */
    private String whyNotReleased() {
        MachineClaims.Key machine = machineKey();
        if (machine == null || _service.getRealInventory() == null) {
            return "no machine in front of this module";
        }
        if (MachineClaims.isHeldByOther(machine, this, now())) {
            return "another crafter is using the machine";
        }
        if (countSetsThatFit(1, false) == 0) {
            return countSetsThatFit(1, true) == 0 ? "a set does not fit this machine at all"
                    : "no room in the machine right now";
        }
        return "queued, waiting for ingredients";
    }

    private void say(EntityPlayer player, String message) {
        if (player != null) {
            player.addChatMessage(new ChatComponentText("Smart Crafter: " + message));
        }
    }

    /** Sends one setting to the server, from the gui. */
    public void sendSetting(int setting, int index, int value) {
        handleSettingPacket(setting, index, value);
        MainProxy.sendPacketToServer(
                PacketHandler.getPacket(SmartCrafterSetting.class).setSetting(setting).setIndex(index).setValue(value)
                        .setModulePos(this));
    }

    @Override
    public void readFromNBT(NBTTagCompound nbttagcompound) {
        super.readFromNBT(nbttagcompound);
        cleanupEnabled = !nbttagcompound.hasKey("SmartCleanup") || nbttagcompound.getBoolean("SmartCleanup");
        int[] roles = nbttagcompound.getIntArray("SmartOutputRole");
        int[] chances = nbttagcompound.getIntArray("SmartOutputChance");
        int[] satellites = nbttagcompound.getIntArray("SmartOutputSatellite");
        for (int i = 0; i < OUTPUT_SLOTS; i++) {
            if (i < roles.length && roles[i] >= 0 && roles[i] < OutputRole.values().length) {
                outputRole[i] = OutputRole.values()[roles[i]];
            }
            if (i < chances.length && chances[i] > 0) {
                outputChance[i] = Math.min(GUARANTEED, chances[i]);
            }
            if (i < satellites.length) {
                outputSatelliteId[i] = Math.max(0, satellites[i]);
            }
        }
    }

    @Override
    public void writeToNBT(NBTTagCompound nbttagcompound) {
        super.writeToNBT(nbttagcompound);
        int[] roles = new int[OUTPUT_SLOTS];
        int[] chances = new int[OUTPUT_SLOTS];
        for (int i = 0; i < OUTPUT_SLOTS; i++) {
            roles[i] = outputRole[i].ordinal();
            chances[i] = outputChance[i];
        }
        nbttagcompound.setIntArray("SmartOutputRole", roles);
        nbttagcompound.setIntArray("SmartOutputChance", chances);
        nbttagcompound.setIntArray("SmartOutputSatellite", outputSatelliteId);
        nbttagcompound.setBoolean("SmartCleanup", cleanupEnabled);
    }

    @Override
    public void handleAdvancedNEIRecipePacket(List<ItemStack> inputs, List<ItemStack> outputs,
            List<FluidStack> fluidInputs, EntityPlayer player) {
        super.handleAdvancedNEIRecipePacket(inputs, outputs, fluidInputs, player);
        // The base only fills two result slots, and the second one as the upgrade's byproduct slot.
        for (int i = 0; i < OUTPUT_SLOTS; i++) {
            _dummyInventory
                    .setInventorySlotContents(outputInventorySlot(i), i < outputs.size() ? outputs.get(i) : null);
            outputRole[i] = i == 0 ? OutputRole.PRODUCT : OutputRole.BYPRODUCT;
            outputChance[i] = GUARANTEED;
        }
        if (player != null) {
            MainProxy.sendPacketToPlayer(getCPipePacket(), player);
        }
    }

    @Override
    @SideOnly(Side.CLIENT)
    public IIcon getIconTexture(IIconRegister register) {
        return register.registerIcon("logisticspipes:itemModule/ModuleSmartCrafter");
    }

    @Override
    protected int neededEnergy() {
        return 20;
    }

    @Override
    protected int itemsToExtract() {
        return 128;
    }

    @Override
    protected int stacksToExtract() {
        return 8;
    }

    @Override
    public void tick() {
        super.tick();
        if (_service == null || !MainProxy.isServer(getWorld())) {
            return;
        }
        if (claimedMachine != null && _service.isNthTick(CLAIM_REFRESH_TICKS)) {
            if (claimedMachine.equals(machineKey())) {
                MachineClaims.refresh(claimedMachine, this, now());
            } else {
                // The chassis was turned to face another block.
                releaseClaim();
                gateDirty = true;
            }
        }
        if (gateDirty || _service.isNthTick(SAFETY_NET_TICKS)) {
            updateGate();
        }
        if (_service.isNthTick(UNORDERED_OUTPUT_TICKS)) {
            drainUnorderedOutputs();
        }
    }

    /**
     * Takes results nothing asked for out of the machine and sends them to storage. The crafter's normal extraction
     * loop only takes what an order wants, so anything else piles up until it blocks the machine: a byproduct, but
     * equally a second <b>product</b> of a recipe whose other product was the one requested (one iron into a lathe
     * gives a rod and two dust; ask for rods and the dust fills its output slot after 32 crafts).
     * <p>
     * Role has no bearing on this. What matters is only whether an order is waiting for the item, in which case it is
     * left for that order.
     */
    private void drainUnorderedOutputs() {
        // Only while this module has a job of its own. With nothing ordered there is no craft of ours to keep running,
        // so anything in the machine is the player's (a set handed over by "Request set", something they put there) and
        // is left alone. Clearing an idle machine is the Cleanup checkbox's job, not this.
        if (!_service.getItemOrderManager().hasOrders(ResourceType.CRAFTING, ResourceType.EXTRA)) {
            return;
        }
        IInventory inv = null;
        int stacks = 0;
        for (int i = 0; i < OUTPUT_SLOTS && stacks < MAX_SWEEP_STACKS; i++) {
            ItemIdentifierStack output = getOutput(i);
            if (output == null || outstandingResults(output) > 0) {
                continue;
            }
            if (inv == null) {
                MachineClaims.Key machine = machineKey();
                inv = _service.getRealInventory();
                if (inv == null || machine == null || MachineClaims.isHeldByOther(machine, this, now())) {
                    return;
                }
            }
            stacks += takeOut(inv, output.getItem(), MAX_SWEEP_STACKS - stacks);
        }
        if (stacks > 0) {
            _service.getCacheHolder().trigger(CacheTypes.Inventory);
        }
    }

    @Override
    public void onAllowedRemoval() {
        super.onAllowedRemoval();
        releaseClaim();
        stopWaiting();
    }

    /** Called by {@link MachineClaims} when the machine this module waited for is handed to it. */
    void onClaimGranted(MachineClaims.Key machine) {
        claimedMachine = machine;
        awaitedMachine = null;
        releasedThisTurn = false;
        claimTakenTick = now();
        sweepBeforeRelease = true;
        gateDirty = true;
    }

    @Override
    public LogisticsItemOrder fullFill(LogisticsPromise promise, IRequestItems destination,
            IAdditionalTargetInformation info) {
        LogisticsItemOrder order = super.fullFill(promise, destination, info);
        gateDirty = true;
        return order;
    }

    /**
     * Counts a set as finished from whichever output turns up.
     * <p>
     * A set yields every output at once, so the outputs are alternative views of the same progress, not separate
     * progress to add up: the set count is the <b>largest</b> any one output implies. Taking the largest also keeps a
     * chanced output from holding the count back, since a guaranteed one overtakes it.
     */
    @Override
    protected void onResultExtracted(ItemIdentifier item, int amount) {
        int output = outputIndexFor(item);
        if (output < 0) {
            return;
        }
        extractedPerOutput[output] += amount;
        int finished = 0;
        for (int i = 0; i < OUTPUT_SLOTS; i++) {
            ItemIdentifierStack stack = getOutput(i);
            if (stack != null && stack.getStackSize() > 0) {
                finished = Math.max(finished, extractedPerOutput[i] / stack.getStackSize());
            }
        }
        if (finished > creditedSets) {
            setsReleased = Math.max(0, setsReleased - (finished - creditedSets));
            creditedSets = finished;
            gateDirty = true;
        }
        lastProgressTick = now();
    }

    private void clearResultProgress() {
        for (int i = 0; i < OUTPUT_SLOTS; i++) {
            extractedPerOutput[i] = 0;
        }
        creditedSets = 0;
    }

    /** Which output slot an extracted item belongs to, or -1 if it isn't one of this recipe's results. */
    private int outputIndexFor(ItemIdentifier item) {
        for (int i = 0; i < OUTPUT_SLOTS; i++) {
            ItemIdentifierStack output = getOutput(i);
            if (output != null && output.getStackSize() > 0 && isOurResult(item, output)) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public void itemArrived(ItemIdentifierStack item, IAdditionalTargetInformation info) {
        super.itemArrived(item, info);
        int slot = ingredientSlot(info);
        if (slot >= 0) {
            inFlight[slot] = Math.max(0, inFlight[slot] - item.getStackSize());
            lastProgressTick = now();
        }
    }

    @Override
    public void itemLost(ItemIdentifierStack item, IAdditionalTargetInformation info) {
        super.itemLost(item, info);
        int slot = ingredientSlot(info);
        if (slot >= 0) {
            // The crafter re-requests lost items, so let the replacement through.
            inFlight[slot] = Math.max(0, inFlight[slot] - item.getStackSize());
            allowance[slot] += item.getStackSize();
        }
    }

    /* IGatedItemSink */

    @Override
    public int getGatedAllowance(ItemIdentifier item, IAdditionalTargetInformation info) {
        int slot = ingredientSlot(info);
        if (slot < 0 || !isGatedSlot(slot)) {
            return Integer.MAX_VALUE;
        }
        return allowance[slot];
    }

    @Override
    public void onGatedSend(ItemIdentifier item, int amount, IAdditionalTargetInformation info) {
        int slot = ingredientSlot(info);
        if (slot < 0 || !isGatedSlot(slot)) {
            return;
        }
        allowance[slot] = Math.max(0, allowance[slot] - amount);
        inFlight[slot] += amount;
        lastProgressTick = now();
    }

    @Override
    public boolean declinesUntrackedItem(ItemIdentifier item) {
        for (int slot = 0; slot < INGREDIENT_SLOTS; slot++) {
            if (isGatedSlot(slot) && getMaterials(slot).getItem().equals(item)) {
                return true;
            }
        }
        return false;
    }

    /* Gate */

    private void updateGate() {
        gateDirty = false;
        if (getConfiguredCraftResult() == null) {
            resetGate();
            return;
        }
        // Sets needed for whichever product is furthest behind: one set yields every output at once, so ordering two
        // of this recipe's products needs the larger of the two set counts, not their sum.
        int setsNeeded = 0;
        for (int i = 0; i < OUTPUT_SLOTS; i++) {
            ItemIdentifierStack output = getOutput(i);
            if (outputRole[i] != OutputRole.PRODUCT || output == null || output.getStackSize() <= 0) {
                continue;
            }
            int outstandingForOutput = outstandingResults(output);
            if (outstandingForOutput > 0) {
                setsNeeded = Math
                        .max(setsNeeded, (outstandingForOutput + output.getStackSize() - 1) / output.getStackSize());
            }
        }
        // Sets asked for by the "Request set" button have no order behind them, so they are counted here instead.
        int wanted = setsNeeded + pendingManualSets;
        if (wanted <= 0) {
            if (deliveryInProgress()) {
                // A set is still on its way (a manual one, or the tail of a finished job). Resetting now would zero
                // the allowance mid-delivery and strand it in storage.
                return;
            }
            if (pendingLoadSweep) {
                // Retried on the next update if the machine isn't reachable yet or another crafter is using it.
                pendingLoadSweep = !sweepMachine();
            } else if (hadOrders) {
                // The last order finished. Usually nothing is left, but a request that failed partway can leave part
                // of a set behind, a lost item can turn up after its replacement did, and results beyond what was
                // ordered stay in the output. Clearing them now, instead of at this crafter's next job, keeps the
                // machine usable for other recipes, players or other automation in the meantime.
                sweepMachine();
            }
            hadOrders = false;
            resetGate();
            return;
        }
        // Only an ordered job counts, so the end-of-job sweep above never fires for a set handed over by hand.
        hadOrders |= setsNeeded > 0;
        pendingLoadSweep = false; // the pre-craft sweep below covers it
        giveUpStuckSets();

        int toRelease = Math.min(wanted, MAX_SETS_IN_FLIGHT) - setsReleased;
        if (toRelease > 0 && mayReleaseSets()) {
            if (sweepBeforeRelease) {
                // First release since taking the machine: anything of this recipe still in it is stale (left over from
                // before a restart, or from a job that didn't finish cleanly). Clear it so the room check sees the
                // machine's real free space and the new sets don't mix with a partial old one.
                sweepMachine();
                sweepBeforeRelease = false;
            }
            int fits = countSetsThatFit(toRelease, false);
            if (fits > 0) {
                releaseSets(fits);
                setTooLarge = false;
            } else if (setsReleased == 0) {
                setTooLarge = countSetsThatFit(1, true) == 0;
            }
        }

        // Hand the machine over once the sets in it are done, if someone else is waiting for it. An owner that
        // couldn't release anything (machine blocked) keeps it for a while first, so two blocked crafters don't pass it
        // back and forth every tick.
        boolean hadTurn = releasedThisTurn || now() - claimTakenTick >= SAFETY_NET_TICKS;
        if (claimedMachine != null && hadTurn
                && setsReleased == 0
                && nothingInFlight()
                && MachineClaims.hasOthersWaiting(claimedMachine, this)) {
            releaseClaim();
            gateDirty = true; // queue up again behind the crafter that is waiting
        }
    }

    /**
     * Takes the machine's claim if needed. An owner stops releasing new sets once another crafter waits, but only after
     * it released at least once in its turn, so two crafters can't hand the machine back and forth without crafting
     * anything.
     */
    private boolean mayReleaseSets() {
        MachineClaims.Key machine = machineKey();
        if (machine == null) {
            return false;
        }
        if (claimedMachine == null) {
            if (!MachineClaims.tryClaim(machine, this, now())) {
                awaitedMachine = machine;
                return false;
            }
            claimedMachine = machine;
            awaitedMachine = null;
            releasedThisTurn = false;
            claimTakenTick = now();
            sweepBeforeRelease = true;
        }
        return !releasedThisTurn || !MachineClaims.hasOthersWaiting(claimedMachine, this);
    }

    private void releaseSets(int sets) {
        int manual = Math.min(sets, pendingManualSets);
        pendingManualSets -= manual;
        for (int slot = 0; slot < INGREDIENT_SLOTS; slot++) {
            if (isGatedSlot(slot)) {
                allowance[slot] += sets * getMaterials(slot).getStackSize();
            }
        }
        // A set from "Request set" is finished once its ingredients are delivered: it exists to hand the machine one
        // more set, so no result is expected and none is taken back out. Only ordered sets are counted as in flight.
        setsReleased += sets - manual;
        releasedThisTurn = true;
        lastProgressTick = now();
    }

    /**
     * Gives up released sets whose results never came back to this module (taken by a player, auto-output, a chanced
     * output that produced nothing). Only when everything was delivered, none of the ingredients are left in the
     * machine and nothing happened for a while. A slow recipe that is still running is harmless to give up: its result
     * is still counted when it comes out, and no extra ingredients arrive because providers only send what was ordered.
     */
    private void giveUpStuckSets() {
        if (setsReleased == 0 || !nothingInFlight() || now() - lastProgressTick < STUCK_TICKS) {
            return;
        }
        for (int slot = 0; slot < INGREDIENT_SLOTS; slot++) {
            if (allowance[slot] > 0) {
                return;
            }
        }
        if (machineHoldsIngredients()) {
            return;
        }
        setsReleased = 0;
        clearResultProgress();
    }

    /**
     * Whether ingredients are still granted but unsent, or sent and not yet arrived. Bounded by {@link #STUCK_TICKS} so
     * a provider that never delivers can't hold the gate open for good.
     */
    private boolean deliveryInProgress() {
        if (now() - lastProgressTick >= STUCK_TICKS) {
            return false;
        }
        for (int slot = 0; slot < INGREDIENT_SLOTS; slot++) {
            if (allowance[slot] > 0 || inFlight[slot] > 0) {
                return true;
            }
        }
        return false;
    }

    private boolean nothingInFlight() {
        for (int slot = 0; slot < INGREDIENT_SLOTS; slot++) {
            if (inFlight[slot] > 0) {
                return false;
            }
        }
        return true;
    }

    private void resetGate() {
        for (int slot = 0; slot < INGREDIENT_SLOTS; slot++) {
            allowance[slot] = 0;
            inFlight[slot] = 0;
        }
        setsReleased = 0;
        pendingManualSets = 0;
        clearResultProgress();
        setTooLarge = false;
        releaseClaim();
        stopWaiting();
    }

    private void releaseClaim() {
        if (claimedMachine != null) {
            MachineClaims.Key machine = claimedMachine;
            claimedMachine = null;
            MachineClaims.release(machine, this, now());
        }
        // Leftover permission from finished sets must not carry over into the next turn.
        for (int slot = 0; slot < INGREDIENT_SLOTS; slot++) {
            allowance[slot] = 0;
        }
    }

    private void stopWaiting() {
        if (awaitedMachine != null) {
            MachineClaims.stopWaiting(awaitedMachine, this);
            awaitedMachine = null;
        }
    }

    /** The block the chassis faces, which is where this module's ingredients go. */
    private MachineClaims.Key machineKey() {
        ForgeDirection dir = _service.inventoryOrientation();
        if (dir == null || dir == ForgeDirection.UNKNOWN || getWorld() == null) {
            return null;
        }
        return new MachineClaims.Key(
                getWorld().provider.dimensionId,
                getX() + dir.offsetX,
                getY() + dir.offsetY,
                getZ() + dir.offsetZ);
    }

    private long now() {
        return getWorld() == null ? 0 : getWorld().getTotalWorldTime();
    }

    /**
     * Results this crafter still has to produce. Orders in a chassis are shared by all its modules, so only orders for
     * this module's result are counted. Extras (leftovers of a partial set) are included, since the machine makes them
     * too.
     */
    private int outstandingResults(ItemIdentifierStack result) {
        int total = 0;
        for (LogisticsItemOrder order : _service.getItemOrderManager()) {
            if (order.getType() != ResourceType.CRAFTING && order.getType() != ResourceType.EXTRA) {
                continue;
            }
            if (order.getResource().matches(result.getItem(), IResource.MatchSettings.NORMAL)) {
                total += order.getAmount();
            }
        }
        return total;
    }

    private boolean isOurResult(ItemIdentifier item, ItemIdentifierStack result) {
        if (item.equals(result.getItem())) {
            return true;
        }
        if (getUpgradeManager().isFuzzyUpgrade() && outputFuzzyFlags.getBitSet().nextSetBit(0) != -1) {
            DictResource dict = new DictResource(result, null);
            dict.loadFromBitSet(outputFuzzyFlags.getBitSet());
            return dict.matches(item, IResource.MatchSettings.NORMAL);
        }
        return false;
    }

    /** Ingredient slots delivered to this module's own machine. Slots sent to a satellite aren't gated. */
    private boolean isGatedSlot(int slot) {
        ItemIdentifierStack material = getMaterials(slot);
        if (material == null || material.getStackSize() <= 0) {
            return false;
        }
        ISlotUpgradeManager upgrades = getUpgradeManager();
        if (upgrades.isAdvancedSatelliteCrafter()) {
            return advancedSatelliteIdArray[slot] == 0;
        }
        return satelliteId == 0 || slot < 6;
    }

    private static int ingredientSlot(IAdditionalTargetInformation info) {
        if (!(info instanceof CraftingChassieInformation)) {
            return -1;
        }
        int slot = ((CraftingChassieInformation) info).getCraftingSlot();
        return slot >= 0 && slot < INGREDIENT_SLOTS ? slot : -1;
    }

    /* Room simulation */

    /**
     * Simulates inserting whole recipe sets into the machine, on top of what it already holds and what is already on
     * its way to it.
     *
     * @param emptyMachine ignore the machine's current contents (used to tell "full right now" from "never fits")
     * @return how many sets fit, up to maxSets
     */
    private int countSetsThatFit(int maxSets, boolean emptyMachine) {
        IInventory inv = _service.getRealInventory();
        if (inv == null) {
            return 0;
        }
        ForgeDirection side = insertionSide();
        int[] slots = insertableSlots(inv, side);
        ItemStack[] simulated = new ItemStack[slots.length];
        if (!emptyMachine) {
            for (int i = 0; i < slots.length; i++) {
                ItemStack stack = inv.getStackInSlot(slots[i]);
                simulated[i] = stack == null ? null : stack.copy();
            }
        }

        int[] perSet = new int[INGREDIENT_SLOTS];
        for (int slot = 0; slot < INGREDIENT_SLOTS; slot++) {
            if (isGatedSlot(slot)) {
                perSet[slot] = amountPerSet(getMaterials(slot).getItem());
            }
        }

        for (int slot = 0; slot < INGREDIENT_SLOTS; slot++) {
            int pending = allowance[slot] + inFlight[slot];
            if (pending > 0 && isGatedSlot(slot)
                    && !place(inv, side, slots, simulated, getMaterials(slot), pending, perSet[slot])) {
                return 0;
            }
        }

        int sets = 0;
        while (sets < maxSets) {
            for (int slot = 0; slot < INGREDIENT_SLOTS; slot++) {
                if (isGatedSlot(slot)) {
                    ItemIdentifierStack material = getMaterials(slot);
                    if (!place(inv, side, slots, simulated, material, material.getStackSize(), perSet[slot])) {
                        return sets;
                    }
                }
            }
            sets++;
        }
        return sets;
    }

    /** Total of this item one recipe set needs, across all gated slots that hold it. */
    private int amountPerSet(ItemIdentifier item) {
        int total = 0;
        for (int slot = 0; slot < INGREDIENT_SLOTS; slot++) {
            if (isGatedSlot(slot) && getMaterials(slot).getItem().equals(item)) {
                total += getMaterials(slot).getStackSize();
            }
        }
        return total;
    }

    /**
     * Pulls this recipe's own leftover items out of the machine the chassis faces and sends them into the network,
     * usually to storage. Other recipes' items are never touched. Never runs while another Smart Crafter holds the
     * machine, since two recipes can share an ingredient.
     * <p>
     * Results and ingredients are taken out differently:
     * <ul>
     * <li>The result only through the machine's normal extraction rules, i.e. from its output slots. Some machines keep
     * a player-set copy of the result elsewhere (e.g. the auto-chisel's target slot), which must stay.</li>
     * <li>Ingredients directly from the slot, bypassing the extraction rules: GT machines don't let anything pull from
     * their input slots, so leftover ingredients could otherwise never be cleared. To keep this from touching template
     * or config slots (molds, circuits, targets), only input slots are emptied: slots a pipe could insert into from
     * some side ({@link #isInputSlot}).</li>
     * </ul>
     *
     * @return false if the machine couldn't be swept right now (not reachable, or in use by another crafter)
     */
    private boolean sweepMachine() {
        if (!cleanupEnabled) {
            return true; // nothing to retry
        }
        IInventory inv = _service.getRealInventory();
        MachineClaims.Key machine = machineKey();
        if (inv == null || machine == null || MachineClaims.isHeldByOther(machine, this, now())) {
            return false;
        }
        int stacks = sweepResult(inv);
        stacks += sweepIngredients(inv, MAX_SWEEP_STACKS - stacks);
        if (stacks > 0) {
            _service.getCacheHolder().trigger(CacheTypes.Inventory);
        }
        return true;
    }

    /** @return how many stacks were taken out */
    private int sweepResult(IInventory inv) {
        int stacks = 0;
        for (int i = 0; i < OUTPUT_SLOTS && stacks < MAX_SWEEP_STACKS; i++) {
            ItemIdentifierStack output = getOutput(i);
            if (output != null) {
                stacks += takeOut(inv, output.getItem(), MAX_SWEEP_STACKS - stacks);
            }
        }
        return stacks;
    }

    /**
     * Pulls an item out of the machine through its normal extraction rules and sends it to storage. Only the machine's
     * output slots are touched: some machines keep a player-set copy of the result elsewhere (e.g. the auto-chisel's
     * target slot), which must stay.
     *
     * @return how many stacks were taken out
     */
    private int takeOut(IInventory inv, ItemIdentifier item, int maxStacks) {
        IInventory extractable = inv instanceof ISidedInventory
                ? new SidedInventoryMinecraftAdapter((ISidedInventory) inv, ForgeDirection.UNKNOWN, true)
                : inv;
        IInventoryUtil util = SimpleServiceLocator.inventoryUtilFactory
                .getInventoryUtil(extractable, _service.inventoryOrientation());
        int stacks = 0;
        int left = util.itemCount(item);
        while (left > 0 && stacks < maxStacks) {
            ItemStack taken = util.getMultipleItems(item, Math.min(left, item.getMaxStackSize()));
            if (taken == null || taken.stackSize <= 0) {
                break;
            }
            left -= taken.stackSize;
            stacks++;
            sendSwept(taken);
        }
        return stacks;
    }

    /** @return how many stacks were taken out */
    private int sweepIngredients(IInventory inv, int maxStacks) {
        ForgeDirection ourSide = insertionSide();
        int stacks = 0;
        for (int machineSlot = 0; machineSlot < inv.getSizeInventory(); machineSlot++) {
            if (stacks >= maxStacks) {
                break;
            }
            ItemStack stack = inv.getStackInSlot(machineSlot);
            if (stack == null || stack.stackSize <= 0) {
                continue;
            }
            if (!isIngredient(stack) || !isInputSlot(inv, machineSlot, stack, ourSide)) {
                continue;
            }
            ItemStack taken = inv.decrStackSize(machineSlot, stack.stackSize);
            if (taken != null && taken.stackSize > 0) {
                stacks++;
                sendSwept(taken);
            }
        }
        return stacks;
    }

    /**
     * Whether a pipe could insert this stack into the slot from any side, i.e. it's an input slot rather than a
     * template, config or circuit slot. Not just from our side: GT machines refuse input on their output face (unless
     * "allow input from output side" is on), on their main face and on faces with a blocking cover, and the chassis
     * usually sits on the output face to pull results. Our side is tried first since it usually answers yes.
     */
    private static boolean isInputSlot(IInventory inv, int slot, ItemStack stack, ForgeDirection ourSide) {
        if (!(inv instanceof ISidedInventory)) {
            return inv.isItemValidForSlot(slot, stack);
        }
        if (isInsertableFrom(inv, ourSide, slot, stack)) {
            return true;
        }
        for (ForgeDirection side : ForgeDirection.VALID_DIRECTIONS) {
            if (side != ourSide && isInsertableFrom(inv, side, slot, stack)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isInsertableFrom(IInventory inv, ForgeDirection side, int slot, ItemStack stack) {
        for (int accessible : insertableSlots(inv, side)) {
            if (accessible == slot) {
                return canInsert(inv, side, slot, stack);
            }
        }
        return false;
    }

    private boolean isIngredient(ItemStack stack) {
        for (int slot = 0; slot < INGREDIENT_SLOTS; slot++) {
            if (isGatedSlot(slot) && isSameItem(stack, getMaterials(slot).getItem().makeNormalStack(1))) {
                return true;
            }
        }
        return false;
    }

    private void sendSwept(ItemStack stack) {
        _service.queueRoutedItem(SimpleServiceLocator.routedItemHelper.createNewTravelItem(stack), ForgeDirection.UP);
    }

    /** Whether any of this recipe's gated ingredients are still sitting in the machine's input slots. */
    private boolean machineHoldsIngredients() {
        IInventory inv = _service.getRealInventory();
        if (inv == null) {
            return false;
        }
        int[] slots = insertableSlots(inv, insertionSide());
        for (int slot = 0; slot < INGREDIENT_SLOTS; slot++) {
            if (!isGatedSlot(slot)) {
                continue;
            }
            ItemStack proto = getMaterials(slot).getItem().makeNormalStack(1);
            for (int machineSlot : slots) {
                if (isSameItem(inv.getStackInSlot(machineSlot), proto)) {
                    return true;
                }
            }
        }
        return false;
    }

    private ForgeDirection insertionSide() {
        return getUpgradeManager().hasSneakyUpgrade() ? getUpgradeManager().getSneakyOrientation()
                : _service.inventoryOrientation().getOpposite();
    }

    private static int[] insertableSlots(IInventory inv, ForgeDirection side) {
        if (inv instanceof ISidedInventory) {
            int[] slots = ((ISidedInventory) inv).getAccessibleSlotsFromSide(side.ordinal());
            return slots == null ? new int[0] : slots;
        }
        int[] slots = new int[inv.getSizeInventory()];
        for (int i = 0; i < slots.length; i++) {
            slots[i] = i;
        }
        return slots;
    }

    /**
     * Places count of the material into the simulated slots, filling matching stacks before empty slots.
     * <p>
     * Many GT machines won't hold more than one stack of an item type even when they have free slots, so the machine is
     * never given more than one stack of an item in total, or one set's worth if the recipe needs more than that.
     *
     * @param perSet how many of this item one recipe set needs
     * @return false if it doesn't all fit
     */
    private static boolean place(IInventory inv, ForgeDirection side, int[] slots, ItemStack[] simulated,
            ItemIdentifierStack material, int count, int perSet) {
        ItemStack proto = material.getItem().makeNormalStack(1);
        int limit = Math.min(inv.getInventoryStackLimit(), proto.getMaxStackSize());
        int held = 0;
        for (ItemStack stack : simulated) {
            if (isSameItem(stack, proto)) {
                held += stack.stackSize;
            }
        }
        if (held + count > Math.max(limit, perSet)) {
            return false;
        }
        for (int i = 0; i < slots.length && count > 0; i++) {
            ItemStack existing = simulated[i];
            if (isSameItem(existing, proto) && canInsert(inv, side, slots[i], proto)) {
                int added = Math.min(count, limit - existing.stackSize);
                if (added > 0) {
                    existing.stackSize += added;
                    count -= added;
                }
            }
        }
        for (int i = 0; i < slots.length && count > 0; i++) {
            if (simulated[i] == null && canInsert(inv, side, slots[i], proto)) {
                int added = Math.min(count, limit);
                simulated[i] = proto.copy();
                simulated[i].stackSize = added;
                count -= added;
            }
        }
        return count <= 0;
    }

    private static boolean isSameItem(ItemStack stack, ItemStack proto) {
        return stack != null && stack.isItemEqual(proto) && ItemStack.areItemStackTagsEqual(stack, proto);
    }

    private static boolean canInsert(IInventory inv, ForgeDirection side, int slot, ItemStack stack) {
        if (!inv.isItemValidForSlot(slot, stack)) {
            return false;
        }
        return !(inv instanceof ISidedInventory) || ((ISidedInventory) inv).canInsertItem(slot, stack, side.ordinal());
    }
}
