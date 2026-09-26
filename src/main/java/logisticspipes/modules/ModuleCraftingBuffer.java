package logisticspipes.modules;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
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
 * The stock itself is pooled (items aren't tagged with an owner), but each Smart Crafter keeps a <b>credit</b> for what
 * sub-crafters sent to a buffer on its behalf, and takes no more than that ({@code ModuleSmartCrafter.buffered}). A
 * fully anonymous pool, where any crafter could take any matching stock, broke as soon as the stock included something
 * no crafter was owed (a cancelled job's leftovers): a request that planned the same item from storage used the buffer
 * stock instead, and its provider order was never allowed to send and stayed open for ever.
 * <p>
 * Stock beyond all crafters' credit is an <b>orphan</b> and goes back to the network ({@link #returnAllOrphans}). That
 * happens on events, never on a timer: when a crafter drops its credit (its work ended or it was removed), and when a
 * buffer first loads (after a restart nothing is owed, so everything is an orphan).
 * <p>
 * A buffer never answers a general request: only a crafter collecting what it is owed may take from it, which is what
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
            // Freshly loaded: after a restart no crafter is owed anything, so whatever is here is an orphan. Loading
            // with a live network (a chunk coming back) only returns what really isn't owed.
            orphansPending = true;
        }
        if (orphansPending) {
            orphansPending = !returnOrphans();
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
     * Only takes an intermediate some crafter has credit for and that isn't in the buffers yet, and only with room to
     * put it. Never a general sink: without that check this would quietly become a second storage system, and an orphan
     * on its way back to storage would come straight back in.
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
        if (!isOwed(item)) {
            return debugDecline(
                    item,
                    "no crafter is owed more of it (crafters=" + ModuleSmartCrafter.AllCrafters.size() + ")");
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

    /**
     * Whether crafters have credit for more of this item than all buffers hold, i.e. some of it is still on its way.
     */
    private static boolean isOwed(ItemIdentifier item) {
        return creditFor(item) > stockInAllBuffers(item);
    }

    /** How much of an item all loaded Smart Crafters together have credit for. */
    private static int creditFor(ItemIdentifier item) {
        int total = 0;
        for (ModuleSmartCrafter crafter : ModuleSmartCrafter.AllCrafters) {
            total += crafter.getBufferedCredit(item);
        }
        return total;
    }

    private static int stockInAllBuffers(ItemIdentifier item) {
        int total = 0;
        for (ModuleCraftingBuffer buffer : ModuleCraftingBuffer.AllBuffers) {
            total += buffer.getAvailable(item);
        }
        return total;
    }

    /* Orphans */

    /** Most stacks one buffer sends back per tick, so a full chest can't flood the network at once. */
    private static final int MAX_RETURN_STACKS = 16;

    /** This buffer still holds orphans it couldn't send in one go; it carries on next tick until they're gone. */
    private boolean orphansPending = false;

    /**
     * Sends back everything in the buffers that no crafter has credit for. Called when that can have changed: a crafter
     * dropped its credit, or a buffer loaded. Stock beyond the credit is sent from whichever buffers hold it; each
     * buffer sends at most {@link #MAX_RETURN_STACKS} and, if more is left, finishes on the following ticks.
     */
    public static void returnAllOrphans() {
        for (ModuleCraftingBuffer buffer : ModuleCraftingBuffer.AllBuffers) {
            buffer.orphansPending = true;
        }
    }

    /** Sends this buffer's share of the orphans. @return true once none are left here */
    private boolean returnOrphans() {
        IInventoryUtil inv = inventory();
        if (inv == null) {
            return true;
        }
        int stacks = 0;
        for (Map.Entry<ItemIdentifier, Integer> entry : inv.getItemsAndCount().entrySet()) {
            ItemIdentifier item = entry.getKey();
            // Credit is shared by all buffers, so the orphans are whatever all of them hold beyond it, and this buffer
            // sends as much of that as it has. Buffers that ran earlier this tick have already sent their part.
            int excess = Math.min(entry.getValue(), stockInAllBuffers(item) - creditFor(item));
            if (excess <= 0) {
                continue;
            }
            if (!hasSomewhereToGo(item)) {
                // Nowhere would take it: it would travel, find nothing and be dropped. Left for the next event.
                continue;
            }
            while (excess > 0) {
                if (stacks >= MAX_RETURN_STACKS) {
                    return false;
                }
                ItemStack taken = take(item, Math.min(excess, item.getMaxStackSize()));
                if (taken == null || taken.stackSize <= 0) {
                    break;
                }
                _service.sendStack(taken, -1, ItemSendMode.Normal, null);
                excess -= taken.stackSize;
                stacks++;
            }
        }
        return true;
    }

    private boolean hasSomewhereToGo(ItemIdentifier item) {
        return _service.getRouter() != null && SimpleServiceLocator.logisticsManager
                .hasDestination(item, true, _service.getRouter().getSimpleID(), Collections.emptyList()) != null;
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
