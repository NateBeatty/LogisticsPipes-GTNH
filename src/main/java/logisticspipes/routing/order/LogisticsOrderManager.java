/*
 * Copyright (c) Krapht, 2011 "LogisticsPipes" is distributed under the terms of the Minecraft Mod Public License 1.0,
 * or MMPL. Please check the contents of the license located in http://www.mod-buildcraft.com/MMPL-1.0.txt
 */
package logisticspipes.routing.order;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.function.Predicate;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.World;

import logisticspipes.interfaces.IChangeListener;
import logisticspipes.interfaces.ILPPositionProvider;
import logisticspipes.logisticspipes.IRoutedItem;
import logisticspipes.network.PacketHandler;
import logisticspipes.network.packets.pipe.PipeManagerContentPacket;
import logisticspipes.proxy.MainProxy;
import logisticspipes.routing.order.IOrderInfoProvider.ResourceType;
import logisticspipes.utils.PlayerCollectionList;
import logisticspipes.utils.item.ItemIdentifierStack;
import logisticspipes.utils.tuples.LPPosition;

public abstract class LogisticsOrderManager<T extends LogisticsOrder, I> implements Iterable<T> {

    public LogisticsOrderManager(LogisticsOrderLinkedList<T, I> orders, ILPPositionProvider pos) {
        _orders = orders;
        this.pos = pos;
    }

    public LogisticsOrderManager(IChangeListener listener, ILPPositionProvider pos,
            LogisticsOrderLinkedList<T, I> orders) {
        this(orders, pos);
        this.listener = listener;
    }

    protected final LogisticsOrderLinkedList<T, I> _orders;
    protected IChangeListener listener = null;

    protected PlayerCollectionList watchingPlayers = new PlayerCollectionList();

    private final ILPPositionProvider pos;

    protected void listen() {
        changed();
        if (listener != null) {
            listener.listenedChanged();
        }
    }

    public void dump() {
        StringBuilder sb = new StringBuilder(" ############################################# ")
                .append(System.getProperty("line.separator"));
        for (T s : _orders) {
            sb.append(s.getAsDisplayItem()).append(" / ").append(s.getAmount()).append(" / ").append(s.getType().name())
                    .append(System.getProperty("line.separator"));
        }
        System.out.print(sb.append(" ############################################# "));
        System.out.println();
    }

    public LinkedList<ItemIdentifierStack> getContentList(World world) {
        if (MainProxy.isClient(world) || _orders.size() == 0) {
            return new LinkedList<>();
        }
        LinkedList<ItemIdentifierStack> list = new LinkedList<>();
        for (LogisticsOrder request : _orders) {
            LogisticsOrderManager.addToList(request.getAsDisplayItem(), list);
        }
        return list;
    }

    private static void addToList(ItemIdentifierStack stack, LinkedList<ItemIdentifierStack> list) {
        for (ItemIdentifierStack ident : list) {
            if (ident.getItem().equals(stack.getItem())) {
                ident.setStackSize(ident.getStackSize() + stack.getStackSize());
                return;
            }
        }
        list.addLast(stack.clone());
    }

    public boolean hasOrders(ResourceType... type) {
        return peekAtTopRequest(type) != null;
    }

    /*
     * only multi-access SAFE when type is null; all other access patterns may change the state of the stack so the
     * returned element is on top
     */
    @SuppressWarnings("unchecked")
    public T peekAtTopRequest(ResourceType... type) {
        List<ResourceType> typeList = Arrays.asList(type);
        if (_orders.size() == 0) {
            return null;
        }
        T top = (T) _orders.getFirst().setInProgress(true);
        int loopCount = 0;
        while (!typeList.contains(top.getType())) {
            loopCount++;
            if (loopCount > _orders.size()) {
                return null;
            }
            deferSend(); // sets the new top to InProgress
            top = _orders.getFirst();
        }
        return top;
    }

    /**
     * The oldest open order of one of these types that {@code accept} takes, or null. Oldest means oldest request
     * first, then the order made first (see {@link #compareAge}), not where it is in the queue: the queue rotates, so
     * its front says nothing about age. One pass, and nothing is moved.
     */
    public T oldestRequest(Predicate<T> accept, ResourceType... types) {
        List<ResourceType> typeList = Arrays.asList(types);
        T oldest = null;
        for (T order : _orders) {
            if (order.isFinished() || order.getAmount() <= 0 || !typeList.contains(order.getType())) {
                continue;
            }
            if ((oldest == null || isOlder(order, oldest)) && accept.test(order)) {
                oldest = order;
            }
        }
        return oldest;
    }

    /**
     * Puts an order at the front of the queue, so {@link #sendSuccessfull} and {@link #sendFailed}, which act on the
     * front order, act on it.
     *
     * @return false if it isn't in this queue
     */
    @SuppressWarnings("unchecked")
    public boolean moveToFront(T order) {
        if (_orders.isEmpty()) {
            return false;
        }
        T first = _orders.getFirst();
        if (first == order) {
            return true;
        }
        if (!_orders.moveToFront(order)) {
            return false;
        }
        first.setInProgress(false);
        order.setInProgress(true);
        return true;
    }

    /**
     * The top order sent {@code number} items. They are counted against the oldest orders that are interchangeable with
     * it first (see {@link #interchangeable}), and only what is left against the top order itself. The items go where
     * they were going either way; this only decides which request they finish. Otherwise a crafter whose queue rotates
     * hands out its results round-robin, and with hundreds of orders for the same ingredient every job ends up waiting
     * on its last few items for hours.
     */
    public void sendSuccessfull(int number, boolean defersend, IRoutedItem item) {
        sendSuccessfull(number, defersend, item, null);
    }

    /**
     * {@link #sendSuccessfull(int, boolean, IRoutedItem)}, with only the older orders {@code mayTakeCredit} accepts
     * counted before the top order; null accepts all of them.
     */
    @SuppressWarnings("unchecked")
    public void sendSuccessfull(int number, boolean defersend, IRoutedItem item, Predicate<T> mayTakeCredit) {
        T top = _orders.getFirst();
        int destination = top.getRouterId();
        int left = number;
        boolean tagged = false;
        for (T older : olderInterchangeable(top)) {
            if (mayTakeCredit != null && !mayTakeCredit.test(older)) {
                continue;
            }
            if (left <= 0) {
                break;
            }
            int portion = Math.min(left, older.getAmount());
            credit(older, portion, item, !tagged);
            tagged = true;
            left -= portion;
            if (older.getAmount() <= 0) {
                _orders.remove(older);
                older.setFinished(true);
                older.setInProgress(false);
                CraftingJobs.orderFinished(older);
            }
        }
        if (left > 0 || !tagged) {
            credit(top, left, item, !tagged);
        }
        if (top.getAmount() <= 0) {
            LogisticsOrder order = _orders.removeFirst();
            order.setFinished(true);
            order.setInProgress(false);
            CraftingJobs.orderFinished(order);
        }
        if (!_orders.isEmpty()) {
            LogisticsOrder start = _orders.getFirst();
            if (defersend && destination == start.getRouterId()) {
                _orders.addLast((T) _orders.removeFirst().setInProgress(false));
                while (start != _orders.getFirst() && destination == _orders.getFirst().getRouterId()) {
                    _orders.addLast(_orders.removeFirst());
                }
                if (start == _orders.getFirst()) {
                    _orders.addLast(_orders.removeFirst());
                }
                _orders.getFirst().setInProgress(true);
            }
        }
        listen();
    }

    /**
     * Counts sent items against one order. The routed item joins the job, and carries the distance tracker, of the
     * first order it is counted against: an item has room for one of each.
     */
    private void credit(T order, int amount, IRoutedItem item, boolean first) {
        CraftingJobs.orderProgressed(order, amount);
        order.reduceAmountBy(amount);
        if (!first || item == null) {
            return;
        }
        if (item.getInfo() != null) {
            item.getInfo().job = order.getJob();
        }
        if (order.isWatched()) {
            IDistanceTracker tracker = new DistanceTracker();
            item.setDistanceTracker(tracker);
            order.addDistanceTracker(tracker);
        }
    }

    /** Open orders interchangeable with {@code top} that are older than it, oldest first. */
    private List<T> olderInterchangeable(T top) {
        List<T> older = new ArrayList<>();
        for (T order : _orders) {
            if (order != top && !order.isFinished()
                    && order.getAmount() > 0
                    && isOlder(order, top)
                    && interchangeable(top, order)) {
                older.add(order);
            }
        }
        older.sort(LogisticsOrderManager::compareAge);
        return older;
    }

    /** By job first, so the oldest request finishes first, then by when the order itself was made. */
    private static int compareAge(LogisticsOrder a, LogisticsOrder b) {
        int byJob = Integer.compare(jobAge(a), jobAge(b));
        return byJob != 0 ? byJob : Long.compare(a.getSequence(), b.getSequence());
    }

    private static boolean isOlder(LogisticsOrder a, LogisticsOrder b) {
        return compareAge(a, b) < 0;
    }

    private static int jobAge(LogisticsOrder order) {
        return order.getJob() == null ? Integer.MAX_VALUE : order.getJob().getId();
    }

    /**
     * Whether an item sent for order {@code a} could just as well have been sent for {@code b}: same item, same
     * destination, same target there, same kind of order. None by default.
     */
    protected boolean interchangeable(T a, T b) {
        return false;
    }

    public void sendFailed() {
        if (!_orders.isEmpty()) {
            LogisticsOrder order = _orders.removeFirst();
            order.setFinished(true);
            order.setInProgress(false);
            CraftingJobs.orderFinished(order);
        }
        if (!_orders.isEmpty()) {
            _orders.getFirst().setInProgress(true);
        }
        listen();
    }

    /**
     * Tells the order's destination that it won't arrive. Whatever the destination requests in reply joins the same
     * job, so a re-request after a failed delivery stays part of the request it replaces.
     */
    protected void notifySendFailed(LogisticsOrder order) {
        CraftingJob previous = CraftingJobs.enter(order.getJob());
        try {
            order.sendFailed();
        } finally {
            CraftingJobs.exit(previous);
        }
    }

    /** Registers a newly added order with the job being planned, if any. */
    protected void added(T order) {
        CraftingJobs.orderAdded(this, order);
    }

    /**
     * Removes an order from anywhere in the queue, for a cancelled job. Unlike {@link #sendFailed} the destination is
     * not told: the whole request is being dropped, so there is nobody who should ask again.
     *
     * @return false if the order wasn't in this queue
     */
    @SuppressWarnings("unchecked")
    public boolean cancel(LogisticsOrder order) {
        boolean wasFirst = !_orders.isEmpty() && _orders.getFirst() == order;
        if (!_orders.remove((T) order)) {
            return false;
        }
        order.setFinished(true);
        order.setInProgress(false);
        CraftingJobs.orderFinished(order);
        if (wasFirst && !_orders.isEmpty()) {
            _orders.getFirst().setInProgress(true);
        }
        listen();
        return true;
    }

    /** Lowers an order that stays in the queue, keeping the running totals right. */
    public void shrink(LogisticsOrder order, int amount) {
        CraftingJobs.orderShrunk(order, amount);
        order.reduceAmountBy(amount);
        listen();
    }

    /** Where the pipe holding these orders is, or null if unknown. */
    public LPPosition getPosition() {
        return pos == null ? null : pos.getLPPosition();
    }

    @SuppressWarnings("unchecked")
    public void deferSend() {
        _orders.addLast((T) _orders.removeFirst().setInProgress(false));
        _orders.getFirst().setInProgress(true);
        listen();
    }

    public int totalAmountCountInAllOrders() {
        int amount = 0;
        for (LogisticsOrder request : _orders) {
            amount += request.getAmount();
        }
        return amount;
    }

    public void setMachineProgress(byte progress) {
        if (_orders.isEmpty()) {
            return;
        }
        _orders.getFirst().setMachineProgress(progress);
        changed();
    }

    public boolean isFirstOrderWatched() {
        if (_orders.isEmpty()) {
            return false;
        }
        return _orders.getFirst().isWatched();
    }

    public void startWatching(EntityPlayer player) {
        watchingPlayers.add(player);
        MainProxy.sendPacketToPlayer(
                PacketHandler.getPacket(PipeManagerContentPacket.class).setManager(this).setLPPos(pos.getLPPosition()),
                player);
    }

    public void stopWatching(EntityPlayer player) {
        watchingPlayers.remove(player);
    }

    public boolean hasExtras() {
        return _orders.hasExtras();
    }

    private void changed() {
        if (watchingPlayers.isEmpty()) {
            return;
        }
        // if(!oldOrders.equals(_orders)) {
        // oldOrders.clear();
        // oldOrders.addAll(_orders);
        MainProxy.sendToPlayerList(
                PacketHandler.getPacket(PipeManagerContentPacket.class).setManager(this).setLPPos(pos.getLPPosition()),
                watchingPlayers);
        // }
    }

    /**
     * DON'T MODIFY TROUGH THIS ONLY READ THE VALUES
     */
    @Override
    public Iterator<T> iterator() {
        return this._orders.iterator();
    }
}
