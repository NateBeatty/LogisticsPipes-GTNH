package logisticspipes.routing.order;

import logisticspipes.interfaces.routing.IAdditionalTargetInformation;
import logisticspipes.interfaces.routing.IRequestItems;
import logisticspipes.modules.ModuleCrafter;
import logisticspipes.request.resources.DictResource;
import logisticspipes.routing.IRouter;
import logisticspipes.utils.item.ItemIdentifierStack;
import lombok.Getter;
import lombok.Setter;

public class LogisticsItemOrder extends LogisticsOrder {

    public LogisticsItemOrder(DictResource item, IRequestItems destination, ResourceType type,
            IAdditionalTargetInformation info) {
        super(type, info);
        if (item == null) {
            throw new NullPointerException();
        }
        resource = item;
        this.destination = destination;
    }

    @Getter
    private final DictResource resource;

    @Getter
    private final IRequestItems destination;

    /**
     * The crafting module whose recipe makes this order's item, or null if it wasn't made by one. A chassis shares one
     * order queue between its modules, so two recipes for the same item can't otherwise tell whose order this is.
     * Server side only.
     */
    @Getter
    @Setter
    private ModuleCrafter maker;

    @Override
    public IRouter getRouter() {
        if (destination == null) {
            return null;
        }
        return destination.getRouter();
    }

    @Override
    public void sendFailed() {
        if (destination == null) {
            return;
        }
        destination.itemCouldNotBeSend(getResource().stack, getInformation());
    }

    @Override
    public ItemIdentifierStack getAsDisplayItem() {
        return resource.stack;
    }

    @Override
    public int getAmount() {
        return resource.stack.getStackSize();
    }

    @Override
    public void reduceAmountBy(int amount) {
        resource.stack.setStackSize(resource.stack.getStackSize() - amount);
    }
}
