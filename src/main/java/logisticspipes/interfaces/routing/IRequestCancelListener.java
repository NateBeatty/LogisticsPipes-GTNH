package logisticspipes.interfaces.routing;

import logisticspipes.utils.FluidIdentifier;
import logisticspipes.utils.item.ItemIdentifierStack;

/**
 * A requester that counts what it asked for until it arrives, such as a supplier. When a job is cancelled, the orders
 * that were to deliver to it are dropped without the usual lost or failed callback, so without this it would count
 * them as still coming for good and stop asking. It is told the amount that will no longer come, and may ask again.
 * <p>
 * Crafters must not implement this: their requests belong to the cancelled job and should stay gone.
 */
public interface IRequestCancelListener {

    default void itemRequestCancelled(ItemIdentifierStack item, IAdditionalTargetInformation info) {}

    default void fluidRequestCancelled(FluidIdentifier fluid, int amount) {}
}
