package logisticspipes.routing.order;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import logisticspipes.interfaces.IChangeListener;
import logisticspipes.interfaces.ILPPositionProvider;
import logisticspipes.interfaces.routing.IAdditionalTargetInformation;
import logisticspipes.interfaces.routing.IRequestItems;
import logisticspipes.request.resources.DictResource;
import logisticspipes.routing.order.IOrderInfoProvider.ResourceType;
import logisticspipes.utils.item.ItemIdentifier;
import logisticspipes.utils.item.ItemIdentifierStack;

public class LogisticsItemOrderManager extends LogisticsOrderManager<LogisticsItemOrder, DictResource.Identifier> {

    private static class IC
            implements LogisticsOrderLinkedList.IIdentityProvider<LogisticsItemOrder, DictResource.Identifier> {

        @Override
        public DictResource.Identifier getIdentity(LogisticsItemOrder o) {
            if (o == null || o.getResource() == null) {
                return null;
            }
            return o.getResource().getIdentifier();
        }

        @Override
        public boolean isExtra(LogisticsItemOrder o) {
            return o instanceof LogisticsItemOrderExtra;
        }
    }

    private static class LogisticsItemOrderExtra extends LogisticsItemOrder {

        public LogisticsItemOrderExtra(DictResource item, IRequestItems destination, ResourceType type,
                IAdditionalTargetInformation info) {
            super(item, destination, type, info);
        }
    }

    public LogisticsItemOrderManager(ILPPositionProvider pos) {
        super(new LogisticsOrderLinkedList<>(new IC()), pos);
    }

    public LogisticsItemOrderManager(IChangeListener listener, ILPPositionProvider pos) {
        super(listener, pos, new LogisticsOrderLinkedList<>(new IC()));
    }

    @Override
    public void sendFailed() {
        notifySendFailed(_orders.getFirst());
        super.sendFailed();
    }

    /**
     * Same kind of order (extras never: they go nowhere), same item, to the same destination and target there, made by
     * the same recipe. Two such orders differ only in which request they belong to.
     */
    @Override
    protected boolean interchangeable(LogisticsItemOrder a, LogisticsItemOrder b) {
        if (a.getType() != b.getType() || a.getType() == ResourceType.EXTRA
                || a.getDestination() == null
                || a.getDestination() != b.getDestination()
                || a.getMaker() != b.getMaker()) {
            return false;
        }
        if (!a.getResource().getIdentifier().equals(b.getResource().getIdentifier())
                || !a.getResource().getItem().equals(b.getResource().getItem())) {
            return false;
        }
        IAdditionalTargetInformation infoA = a.getInformation();
        IAdditionalTargetInformation infoB = b.getInformation();
        return infoA == null ? infoB == null : infoA.sameTargetAs(infoB);
    }

    public LogisticsItemOrder addOrder(ItemIdentifierStack stack, IRequestItems requester, ResourceType type,
            IAdditionalTargetInformation info) {
        LogisticsItemOrder order = new LogisticsItemOrder(new DictResource(stack, null), requester, type, info);
        _orders.addLast(order);
        added(order);
        listen();
        return order;
    }

    public LogisticsItemOrder addOrder(DictResource stack, IRequestItems requester, ResourceType type,
            IAdditionalTargetInformation info) {
        LogisticsItemOrder order = new LogisticsItemOrder(stack, requester, type, info);
        _orders.addLast(order);
        added(order);
        listen();
        return order;
    }

    public LogisticsItemOrderExtra addExtra(DictResource stack) {
        LogisticsItemOrderExtra order = new LogisticsItemOrderExtra(stack, null, ResourceType.EXTRA, null);
        _orders.addLast(order);
        added(order);
        listen();
        return order;
    }

    /**
     * Spends extras on a new request.
     *
     * @return how much was taken from each job's surplus, so the new order can record what it borrowed
     */
    public Map<CraftingJob, Integer> removeExtras(DictResource resource) {
        return removeExtras(resource, extra -> true);
    }

    /**
     * Spends only the extras {@code mine} accepts: in a chassis, those of the recipe whose module offered them.
     *
     * @return how much was taken from each job's surplus, so the new order can record what it borrowed
     */
    public Map<CraftingJob, Integer> removeExtras(DictResource resource, Predicate<LogisticsItemOrder> mine) {
        Map<CraftingJob, Integer> borrowed = new HashMap<>();
        int itemsToRemove = resource.getRequestedAmount();
        DictResource.Identifier ident = resource.getIdentifier();
        Iterator<LogisticsItemOrder> iter = _orders.iterator();
        List<LogisticsItemOrder> toRemove = new LinkedList<>();
        while (iter.hasNext()) {
            LogisticsItemOrder order = iter.next();
            if (order.getType() != ResourceType.EXTRA || !mine.test(order)) continue;
            if (order.getResource().getIdentifier().equals(ident)) {
                if (itemsToRemove >= order.getAmount()) {
                    itemsToRemove -= order.getAmount();
                    toRemove.add(order);
                    noteBorrowed(borrowed, order, order.getAmount());
                    if (itemsToRemove == 0) {
                        break;
                    }
                } else {
                    noteBorrowed(borrowed, order, itemsToRemove);
                    order.getResource().getItemStack().setStackSize(order.getAmount() - itemsToRemove);
                    break;
                }
            }
        }
        _orders.removeAll(toRemove);
        // No longer this job's to make: the request that spent them owns them now.
        for (LogisticsItemOrder order : toRemove) {
            order.setFinished(true);
            CraftingJobs.orderFinished(order);
        }
        return borrowed;
    }

    private static void noteBorrowed(Map<CraftingJob, Integer> borrowed, LogisticsItemOrder extra, int amount) {
        if (extra.getJob() != null && amount > 0) {
            borrowed.merge(extra.getJob(), amount, Integer::sum);
        }
    }

    public int totalItemsCountInOrders(ItemIdentifier item) {
        int itemCount = 0;
        for (LogisticsItemOrder request : _orders) {
            if (!request.getResource().getItem().equals(item)) {
                continue;
            }
            itemCount += request.getResource().stack.getStackSize();
        }
        return itemCount;
    }
}
