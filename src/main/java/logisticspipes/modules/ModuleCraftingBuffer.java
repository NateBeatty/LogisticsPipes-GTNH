package logisticspipes.modules;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.client.renderer.texture.IIconRegister;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.IIcon;
import net.minecraft.world.World;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import logisticspipes.interfaces.IInventoryUtil;
import logisticspipes.interfaces.routing.IAdditionalTargetInformation;
import logisticspipes.interfaces.routing.IRequireReliableTransport;
import logisticspipes.modules.abstractmodules.LogisticsModule;
import logisticspipes.pipes.PipeLogisticsChassi.ChassiTargetInformation;
import logisticspipes.pipes.basic.CoreRoutedPipe.ItemSendMode;
import logisticspipes.proxy.MainProxy;
import logisticspipes.proxy.SimpleServiceLocator;
import logisticspipes.routing.ExitRoute;
import logisticspipes.routing.IRouter;
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
        releaseReservation(item.getItem(), item.getStackSize());
        for (ModuleSmartCrafter crafter : ModuleSmartCrafter.AllCrafters) {
            crafter.onBufferStockArrived(item.getItem());
        }
    }

    /**
     * Something sent here won't arrive: it was lost on the way, or didn't fit and went elsewhere. Crafters were
     * credited for it, so whatever credit no stock stands behind any more is taken back and asked for again; otherwise
     * the set would wait for items that are now in storage, with nothing to fetch them.
     */
    @Override
    public void itemLost(ItemIdentifierStack item, IAdditionalTargetInformation info) {
        ItemIdentifier id = item.getItem();
        releaseReservation(id, item.getStackSize());
        int unbacked = Math.min(item.getStackSize(), creditFor(id) - stockInAllBuffers(id));
        for (ModuleSmartCrafter crafter : ModuleSmartCrafter.AllCrafters) {
            if (unbacked <= 0) {
                return;
            }
            unbacked -= crafter.itemBufferDeliveryLost(id, unbacked);
        }
    }

    /** The chest this module faces, or null when it isn't pointed at one. */
    public IInventory getBufferInventory() {
        return _service == null ? null : _service.getRealInventory();
    }

    /** How many of an item this buffer holds and hasn't promised to a crafter. */
    public int getAvailable(ItemIdentifier item) {
        IInventoryUtil inv = inventory();
        return inv == null ? 0 : inv.itemCount(item);
    }

    /** Everything this buffer holds, by kind, for a crafter matching an ingredient in any of its states. */
    public Map<ItemIdentifier, Integer> getStock() {
        IInventoryUtil inv = inventory();
        return inv == null ? Collections.emptyMap() : inv.getItemsAndCount();
    }

    /** Whether there is space for this item, which decides if an intermediate may be routed here. */
    public boolean hasRoomFor(ItemIdentifier item, int count) {
        return count > 0 && roomFor(item, count) >= count;
    }

    /**
     * How many of an item the chest still takes, counting no further than {@code max}, after what is already on its way
     * here. The chest only knows what it holds now, so two senders asking one after the other would otherwise both be
     * told the same room. Other items on their way are counted as the slots they will fill, each slot as a full stack
     * of this item: a little cautious when they top up partial stacks, never too generous.
     */
    public int roomFor(ItemIdentifier item, int max) {
        IInventoryUtil inv = inventory();
        if (inv == null || item == null || max <= 0) {
            return 0;
        }
        expireReservations();
        int pending = 0;
        int otherSlots = 0;
        for (Incoming entry : incoming) {
            if (entry.item.equals(item)) {
                pending += entry.count;
            } else {
                int stack = Math.max(1, entry.item.getMaxStackSize());
                otherSlots += (entry.count + stack - 1) / stack;
            }
        }
        long held = (long) pending + (long) otherSlots * Math.max(1, item.getMaxStackSize());
        int ask = (int) Math.min(Integer.MAX_VALUE, max + held);
        return Math.max(0, (int) Math.min(max, inv.roomForItem(item, ask) - held));
    }

    /* Room promised to deliveries still in the pipes */

    /** How long room stays promised to a delivery that hasn't arrived and wasn't reported lost. */
    private static final long RESERVATION_TIMEOUT_TICKS = 1200;

    private static final class Incoming {

        final ItemIdentifier item;
        int count;
        final long expires;

        Incoming(ItemIdentifier item, int count, long expires) {
            this.item = item;
            this.count = count;
            this.expires = expires;
        }
    }

    /** Oldest first. In memory only: after a restart nothing is on its way any more. */
    private final List<Incoming> incoming = new ArrayList<>();

    /** A sender is routing these items here; keep room for them until they arrive. */
    public void reserve(ItemIdentifier item, int count) {
        if (item != null && count > 0) {
            incoming.add(new Incoming(item, count, now() + RESERVATION_TIMEOUT_TICKS));
        }
    }

    private void releaseReservation(ItemIdentifier item, int count) {
        Iterator<Incoming> it = incoming.iterator();
        while (it.hasNext() && count > 0) {
            Incoming entry = it.next();
            if (!entry.item.equals(item)) {
                continue;
            }
            int take = Math.min(count, entry.count);
            entry.count -= take;
            count -= take;
            if (entry.count <= 0) {
                it.remove();
            }
        }
    }

    private void expireReservations() {
        long t = now();
        incoming.removeIf(entry -> entry.expires <= t);
    }

    private long now() {
        World world = _world == null ? null : _world.getWorld();
        return world == null ? 0 : world.getTotalWorldTime();
    }

    /**
     * The chassis router this module sits in. A provider parking raw materials here sends them to it directly, so they
     * land in this chest whatever else the network's sinks would say.
     */
    public int getBufferRouter() {
        return _service == null || _service.getRouter() == null ? -1 : _service.getRouter().getSimpleID();
    }

    /** What an item sent here directly carries, so the chassis reports its arrival to this module. */
    public IAdditionalTargetInformation getTargetInformation() {
        return new ChassiTargetInformation(getPositionInt());
    }

    /**
     * The route cost from this buffer to {@code target}, or -1 when there is no route. Measured from the buffer, since
     * that is the trip a buffered item makes to the machine it is for. A null target is distance 0 (no preference).
     */
    public double distanceTo(IRouter target) {
        IRouter own = _service == null ? null : _service.getRouter();
        if (own == null) {
            return -1;
        }
        if (target == null || own.getSimpleID() == target.getSimpleID()) {
            return 0;
        }
        double best = -1;
        for (ExitRoute route : own.getDistanceTo(target)) {
            if (best < 0 || route.distanceToDestination < best) {
                best = route.distanceToDestination;
            }
        }
        return best;
    }

    /**
     * The loaded buffers that can reach {@code target} (the machine an ingredient is for, or its Smart Satellite),
     * nearest first. Ties go to the lower router id, so the choice doesn't change from one call to the next.
     */
    public static List<ModuleCraftingBuffer> nearestTo(IRouter target) {
        Map<ModuleCraftingBuffer, Double> distance = new HashMap<>();
        for (ModuleCraftingBuffer buffer : AllBuffers) {
            double d = buffer.distanceTo(target);
            if (d >= 0) {
                distance.put(buffer, d);
            }
        }
        List<ModuleCraftingBuffer> sorted = new ArrayList<>(distance.keySet());
        sorted.sort(
                Comparator.<ModuleCraftingBuffer>comparingDouble(distance::get)
                        .thenComparingInt(ModuleCraftingBuffer::getBufferRouter));
        return sorted;
    }

    /**
     * The buffer nearest to {@code target} with room for at least one of this item, or null when none that can reach
     * it has room.
     */
    public static ModuleCraftingBuffer withRoomFor(ItemIdentifier item, IRouter target) {
        for (ModuleCraftingBuffer buffer : nearestTo(target)) {
            if (buffer.getBufferRouter() >= 0 && buffer.hasRoomFor(item, 1)) {
                return buffer;
            }
        }
        return null;
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
            return null; // not registered in a chassis slot yet
        }
        if (bestPriority > _sinkReply.fixedPriority.ordinal() || (bestPriority == _sinkReply.fixedPriority.ordinal()
                && bestCustomPriority >= _sinkReply.customPriority)) {
            return null; // a better sink already replied
        }
        if (!isOwed(item)) {
            return null;
        }
        IInventoryUtil inv = inventory();
        if (inv == null) {
            return null; // no chest in front of this module
        }
        // Bounded at a stack: roomForItem stops counting once it has found this many, and a sink reply never needs
        // more than that. Passing 0 counts nothing at all, since the loop runs while count > found.
        int room = inv.roomForItem(item, item.getMaxStackSize());
        if (room <= 0 || !_service.canUseEnergy(2)) {
            return null;
        }
        return new SinkReply(_sinkReply, room);
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
