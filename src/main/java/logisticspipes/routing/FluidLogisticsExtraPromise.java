package logisticspipes.routing;

import logisticspipes.interfaces.routing.IProvideFluids;
import logisticspipes.request.IExtraPromise;
import logisticspipes.request.resources.IResource;
import logisticspipes.routing.order.IOrderInfoProvider.ResourceType;
import logisticspipes.utils.FluidIdentifier;
import lombok.Getter;

/**
 * The surplus of a fluid promise that covered more than was asked for: a crafter making 1000L a set against a request
 * for 216L leaves 784L over. The item side has had this since the beginning; fluid needed it once fluid could be
 * crafted.
 */
public class FluidLogisticsExtraPromise extends FluidLogisticsPromise implements IExtraPromise {

    @Getter
    private final boolean provided;

    public FluidLogisticsExtraPromise(FluidIdentifier liquid, int amount, IProvideFluids sender, ResourceType type,
            boolean provided) {
        super(liquid, amount, sender, type);
        this.provided = provided;
    }

    @Override
    public FluidLogisticsExtraPromise copy() {
        return new FluidLogisticsExtraPromise(getLiquid(), amount, sender, getType(), provided);
    }

    @Override
    public void registerExtras(IResource requestType) {
        // Only a crafter can hold extras, and it tracks them per item; a fluid crafter has no such store, so the
        // surplus is simply left in the machine for the next request instead of being promised to this one.
    }

    @Override
    public void lowerAmount(int usedcount) {
        amount -= usedcount;
    }

    @Override
    public void setAmount(int amount) {
        this.amount = amount;
    }
}
