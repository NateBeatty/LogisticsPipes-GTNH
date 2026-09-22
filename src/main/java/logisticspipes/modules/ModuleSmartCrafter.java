package logisticspipes.modules;

import net.minecraft.client.renderer.texture.IIconRegister;
import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.ISidedInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.util.IIcon;
import net.minecraftforge.common.util.ForgeDirection;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import logisticspipes.interfaces.ISlotUpgradeManager;
import logisticspipes.interfaces.routing.IAdditionalTargetInformation;
import logisticspipes.interfaces.routing.IGatedItemSink;
import logisticspipes.interfaces.routing.IRequestItems;
import logisticspipes.proxy.MainProxy;
import logisticspipes.request.resources.DictResource;
import logisticspipes.request.resources.IResource;
import logisticspipes.routing.LogisticsPromise;
import logisticspipes.routing.order.IOrderInfoProvider.ResourceType;
import logisticspipes.routing.order.LogisticsItemOrder;
import logisticspipes.utils.item.ItemIdentifier;
import logisticspipes.utils.item.ItemIdentifierStack;
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

    /** Items per ingredient slot that providers may still send for the released sets. */
    private final int[] allowance = new int[INGREDIENT_SLOTS];
    /** Items per ingredient slot that were sent but haven't arrived yet. */
    private final int[] inFlight = new int[INGREDIENT_SLOTS];
    private int setsReleased = 0;
    private int resultRemainder = 0;
    private boolean gateDirty = false;
    private long lastProgressTick = 0;

    /** The machine this module currently holds the claim for, or null. */
    private MachineClaims.Key claimedMachine = null;
    /** The machine this module is waiting to claim, or null. */
    private MachineClaims.Key awaitedMachine = null;
    /** Whether sets were released since the claim was taken, so a new owner always gets at least one turn. */
    private boolean releasedThisTurn = false;
    private long claimTakenTick = 0;

    /** True when not even one recipe set fits in an empty machine, so the gate can never open. */
    @Getter
    private boolean setTooLarge = false;

    public ModuleSmartCrafter() {}

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
        gateDirty = true;
    }

    @Override
    public LogisticsItemOrder fullFill(LogisticsPromise promise, IRequestItems destination,
            IAdditionalTargetInformation info) {
        LogisticsItemOrder order = super.fullFill(promise, destination, info);
        gateDirty = true;
        return order;
    }

    @Override
    protected void onResultExtracted(ItemIdentifier item, int amount) {
        ItemIdentifierStack result = getConfiguredCraftResult();
        if (result == null || result.getStackSize() <= 0 || !isOurResult(item, result)) {
            return;
        }
        resultRemainder += amount;
        int finished = resultRemainder / result.getStackSize();
        resultRemainder %= result.getStackSize();
        if (finished > 0) {
            setsReleased = Math.max(0, setsReleased - finished);
            gateDirty = true;
        }
        lastProgressTick = now();
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

    /* Gate */

    private void updateGate() {
        gateDirty = false;
        ItemIdentifierStack result = getConfiguredCraftResult();
        if (result == null || result.getStackSize() <= 0) {
            resetGate();
            return;
        }
        int outstanding = outstandingResults(result);
        if (outstanding <= 0) {
            resetGate();
            return;
        }
        giveUpStuckSets();

        int setsNeeded = (outstanding + result.getStackSize() - 1) / result.getStackSize();
        int toRelease = Math.min(setsNeeded, MAX_SETS_IN_FLIGHT) - setsReleased;
        if (toRelease > 0 && mayReleaseSets()) {
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
        }
        return !releasedThisTurn || !MachineClaims.hasOthersWaiting(claimedMachine, this);
    }

    private void releaseSets(int sets) {
        for (int slot = 0; slot < INGREDIENT_SLOTS; slot++) {
            if (isGatedSlot(slot)) {
                allowance[slot] += sets * getMaterials(slot).getStackSize();
            }
        }
        setsReleased += sets;
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
        resultRemainder = 0;
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
        resultRemainder = 0;
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
