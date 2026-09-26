package logisticspipes.modules;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import net.minecraft.client.renderer.texture.IIconRegister;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.IIcon;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import logisticspipes.LogisticsPipes;
import logisticspipes.interfaces.IInventoryUtil;
import logisticspipes.interfaces.routing.IAdditionalTargetInformation;
import logisticspipes.interfaces.routing.IRequireReliableTransport;
import logisticspipes.modules.abstractmodules.LogisticsModule;
import logisticspipes.pipes.PipeLogisticsChassi.ChassiTargetInformation;
import logisticspipes.pipes.basic.CoreRoutedPipe.ItemSendMode;
import logisticspipes.proxy.MainProxy;
import logisticspipes.proxy.SimpleServiceLocator;
import logisticspipes.utils.SinkReply;
import logisticspipes.utils.SinkReply.FixedPriority;
import logisticspipes.utils.item.ItemIdentifier;
import logisticspipes.utils.item.ItemIdentifierStack;

/**
 * Holds the intermediates of a craft until the crafter that needs them has a whole set, in a chest the player provides
 * and can expand.
 * <p>
 * The pool is <b>anonymous</b>: nothing in here belongs to a particular crafter. That is safe because intermediates are
 * fungible, so a crafter taking "someone else's" circuits is made whole when the other crafter's own order delivers.
 * What keeps two crafters from both counting the same stock is that a crafter checks and reserves its whole set in one
 * synchronous pass, never a partial one. See "REVISED 2026-09-24" in the design notes.
 * <p>
 * A buffer never answers a general request: only a crafter collecting a reserved set may take from it, which is what
 * stops ordinary storage requests draining the pool.
 */
public class ModuleCraftingBuffer extends LogisticsModule implements IRequireReliableTransport {

    /** Every loaded buffer, so a crafter can find them without walking the routing table. Server side only. */
    public static final Set<ModuleCraftingBuffer> AllBuffers = new HashSet<>();

    /** Called on server shutdown, as the satellite registry is. */
    public static void cleanup() {
        ModuleCraftingBuffer.AllBuffers.clear();
    }

    /**
     * Built in {@link #registerPosition}, not as a field: the reply has to carry this module's slot, which isn't known
     * until then, and {@code ChassiModule.sinksItem} casts that information without checking it for null.
     */
    private SinkReply _sinkReply;

    @Override
    public void registerPosition(ModulePositionType slot, int positionInt) {
        super.registerPosition(slot, positionInt);
        _sinkReply = new SinkReply(
                FixedPriority.CraftingBuffer,
                0,
                true,
                false,
                2,
                0,
                new ChassiTargetInformation(getPositionInt()));
    }

    private boolean registered = false;

    public ModuleCraftingBuffer() {}

    @Override
    public int getX() {
        return _service == null ? 0 : _service.getX();
    }

    @Override
    public int getY() {
        return _service == null ? 0 : _service.getY();
    }

    @Override
    public int getZ() {
        return _service == null ? 0 : _service.getZ();
    }

    /**
     * Registration is lazy rather than on placement: a module has no hook for "I am now in the world", and this also
     * covers a chunk reloading. Removal is not signalled either, so a buffer whose service has gone drops itself.
     */
    @Override
    public void tick() {
        if (_world == null || _service == null || !MainProxy.isServer(_world.getWorld())) {
            return;
        }
        if (!registered) {
            ModuleCraftingBuffer.AllBuffers.add(this);
            registered = true;
        }
    }

    /**
     * Stock has landed here. The buffer deliberately says only "I received this" and lets each crafter work out what it
     * means: knowing whether a *set* is now complete needs the recipe and the gate rules, which belong to the crafter.
     * Doing that arithmetic here would duplicate it in the wrong place.
     */
    @Override
    public void itemArrived(ItemIdentifierStack item, IAdditionalTargetInformation info) {
        for (ModuleSmartCrafter crafter : ModuleSmartCrafter.AllCrafters) {
            crafter.onBufferStockArrived(item.getItem());
        }
    }

    @Override
    public void itemLost(ItemIdentifierStack item, IAdditionalTargetInformation info) {}

    /** The chest this module faces, or null when it isn't pointed at one. */
    public IInventory getBufferInventory() {
        return _service == null ? null : _service.getRealInventory();
    }

    /** How many of an item this buffer holds and hasn't promised to a crafter. */
    public int getAvailable(ItemIdentifier item) {
        IInventoryUtil inv = inventory();
        return inv == null ? 0 : inv.itemCount(item);
    }

    /** Whether there is space for this item, which decides if an intermediate may be routed here. */
    public boolean hasRoomFor(ItemIdentifier item, int count) {
        IInventoryUtil inv = inventory();
        return inv != null && inv.roomForItem(item, count) >= count;
    }

    /**
     * Takes items out for a crafter, up to what is actually here. Asking for more than the buffer holds must not come
     * back empty: {@code getMultipleItems} is all-or-nothing, so a crafter wanting 16 plates from a buffer holding 15
     * would get none at all and wait for a delivery that already arrived.
     *
     * @return what was removed, or null if there was nothing
     */
    public ItemStack take(ItemIdentifier item, int count) {
        IInventoryUtil inv = inventory();
        if (inv == null) {
            return null;
        }
        int available = Math.min(count, inv.itemCount(item));
        return available <= 0 ? null : inv.getMultipleItems(item, available);
    }

    /**
     * Sends items this buffer holds to a crafter that asked for them, tagged with the ingredient slot so the crafter's
     * gate tracks them the same way it tracks a provider's delivery.
     */
    public int sendTo(ItemIdentifier item, int count, int destinationRouter, IAdditionalTargetInformation info) {
        ItemStack taken = take(item, count);
        if (taken == null || taken.stackSize <= 0) {
            return 0;
        }
        _service.sendStack(taken, destinationRouter, ItemSendMode.Normal, info);
        return taken.stackSize;
    }

    private IInventoryUtil inventory() {
        IInventory inv = getBufferInventory();
        return inv == null ? null
                : SimpleServiceLocator.inventoryUtilFactory.getInventoryUtil(inv, _service.inventoryOrientation());
    }

    /**
     * Only takes an intermediate a crafter is actually waiting for, and only with room to put it. Never a general sink:
     * without the crafter check this would quietly become a second storage system.
     */
    @Override
    public SinkReply sinksItem(ItemIdentifier item, int bestPriority, int bestCustomPriority, boolean allowDefault,
            boolean includeInTransit) {
        if (_sinkReply == null) {
            return debugDecline(item, "not registered in a chassis slot yet");
        }
        if (bestPriority > _sinkReply.fixedPriority.ordinal() || (bestPriority == _sinkReply.fixedPriority.ordinal()
                && bestCustomPriority >= _sinkReply.customPriority)) {
            return debugDecline(item, "a better sink already replied");
        }
        if (!isWantedByACrafter(item)) {
            return debugDecline(
                    item,
                    "no crafter is waiting for it (crafters=" + ModuleSmartCrafter.AllCrafters.size() + ")");
        }
        IInventoryUtil inv = inventory();
        if (inv == null) {
            return debugDecline(item, "no chest in front of this module");
        }
        // Bounded at a stack: roomForItem stops counting once it has found this many, and a sink reply never needs
        // more than that. Passing 0 counts nothing at all, since the loop runs while count > found.
        int room = inv.roomForItem(item, item.getMaxStackSize());
        if (room <= 0) {
            return debugDecline(item, "chest is full");
        }
        if (!_service.canUseEnergy(2)) {
            return debugDecline(item, "no power");
        }
        debugDecline(item, "ACCEPTED room=" + room);
        return new SinkReply(_sinkReply, room);
    }

    private String lastDebug = null;
    private String lastInterestDebug = null;

    /** Temporary: says why a buffer turned an item away, logged only when the answer changes. */
    private SinkReply debugDecline(ItemIdentifier item, String reason) {
        String line = item + ": " + reason;
        if (!line.equals(lastDebug)) {
            lastDebug = line;
            LogisticsPipes.log.info("[Buffer DEBUG] " + line);
        }
        return null;
    }

    /** Whether any loaded Smart Crafter has this item as an ingredient of a recipe it is currently working on. */
    private boolean isWantedByACrafter(ItemIdentifier item) {
        for (ModuleSmartCrafter crafter : ModuleSmartCrafter.AllCrafters) {
            if (crafter.wantsIntermediate(item)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public LogisticsModule getSubModule(int slot) {
        return null;
    }

    @Override
    public boolean hasGenericInterests() {
        return false;
    }

    @Override
    public Collection<ItemIdentifier> getSpecificInterests() {
        // Interest in exactly the intermediates crafters are waiting for. Never generic: a buffer that advertised
        // "anything" would be consulted for every item routed anywhere on the network.
        Set<ItemIdentifier> wanted = new HashSet<>();
        for (ModuleSmartCrafter crafter : ModuleSmartCrafter.AllCrafters) {
            wanted.addAll(crafter.getIntermediates());
        }
        String summary = "interests from " + ModuleSmartCrafter.AllCrafters.size() + " crafters: " + wanted;
        if (!summary.equals(lastInterestDebug)) {
            lastInterestDebug = summary;
            LogisticsPipes.log.info("[Buffer DEBUG] " + summary);
        }
        return wanted.isEmpty() ? Collections.emptySet() : wanted;
    }

    @Override
    public boolean interestedInAttachedInventory() {
        return false;
    }

    @Override
    public boolean interestedInUndamagedID() {
        return false;
    }

    /**
     * True, and load-bearing: an intermediate arrives through {@code sendStack(..., -1, ...)}, i.e. as a passive
     * best-sink route, and {@code LogisticsManager.canSink} returns null without even calling {@code sinksItem} unless
     * the chassis reports that one of its modules receives passively.
     */
    @Override
    public boolean recievePassive() {
        return true;
    }

    @Override
    public void readFromNBT(NBTTagCompound nbttagcompound) {}

    @Override
    public void writeToNBT(NBTTagCompound nbttagcompound) {}

    @Override
    @SideOnly(Side.CLIENT)
    public IIcon getIconTexture(IIconRegister register) {
        return register.registerIcon("logisticspipes:itemModule/ModuleCraftingBuffer");
    }
}
