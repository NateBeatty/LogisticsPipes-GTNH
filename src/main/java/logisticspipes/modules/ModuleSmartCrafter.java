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
 */
public class ModuleSmartCrafter extends ModuleCrafter implements IGatedItemSink {

    private static final int INGREDIENT_SLOTS = 9;
    /** Upper limit on sets released but not yet finished, so a huge inventory can't make the room check loop long. */
    private static final int MAX_SETS_IN_FLIGHT = 128;
    /** Slow re-check for results the module never saw (taken by a player, auto-output, failed chanced craft). */
    private static final int SAFETY_NET_TICKS = 100;

    /** Items per ingredient slot that providers may still send for the released sets. */
    private final int[] allowance = new int[INGREDIENT_SLOTS];
    /** Items per ingredient slot that were sent but haven't arrived yet. */
    private final int[] inFlight = new int[INGREDIENT_SLOTS];
    private int setsReleased = 0;
    private int resultRemainder = 0;
    private boolean gateDirty = false;

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
        if (gateDirty || _service.isNthTick(SAFETY_NET_TICKS)) {
            updateGate();
        }
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
    }

    @Override
    public void itemArrived(ItemIdentifierStack item, IAdditionalTargetInformation info) {
        super.itemArrived(item, info);
        int slot = ingredientSlot(info);
        if (slot >= 0) {
            inFlight[slot] = Math.max(0, inFlight[slot] - item.getStackSize());
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
        int setsNeeded = (outstanding + result.getStackSize() - 1) / result.getStackSize();
        int toRelease = Math.min(setsNeeded, MAX_SETS_IN_FLIGHT) - setsReleased;
        if (toRelease <= 0) {
            return;
        }
        int fits = countSetsThatFit(toRelease, false);
        if (fits > 0) {
            releaseSets(fits);
            setTooLarge = false;
        } else if (setsReleased == 0) {
            setTooLarge = countSetsThatFit(1, true) == 0;
        }
    }

    private void releaseSets(int sets) {
        for (int slot = 0; slot < INGREDIENT_SLOTS; slot++) {
            if (isGatedSlot(slot)) {
                allowance[slot] += sets * getMaterials(slot).getStackSize();
            }
        }
        setsReleased += sets;
    }

    private void resetGate() {
        for (int slot = 0; slot < INGREDIENT_SLOTS; slot++) {
            allowance[slot] = 0;
            inFlight[slot] = 0;
        }
        setsReleased = 0;
        resultRemainder = 0;
        setTooLarge = false;
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
        ForgeDirection side = getUpgradeManager().hasSneakyUpgrade() ? getUpgradeManager().getSneakyOrientation()
                : _service.inventoryOrientation().getOpposite();
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
