package logisticspipes.interfaces.routing;

import logisticspipes.modules.abstractmodules.LogisticsModule;
import logisticspipes.pipes.PipeLogisticsChassi.ChassiTargetInformation;
import logisticspipes.routing.IRouter;
import logisticspipes.utils.item.ItemIdentifier;

/**
 * A chassis module that only accepts ordered items once it is ready for them. Providers ask the target module directly,
 * because the chassis turns a module's "no room" reply into "unlimited" (see ChassiModule.sinksItem).
 */
public interface IGatedItemSink {

    /**
     * @return how many of this item may be sent to the module right now, for an order carrying this target information.
     *         Integer.MAX_VALUE if the module doesn't gate it.
     */
    int getGatedAllowance(ItemIdentifier item, IAdditionalTargetInformation info);

    /**
     * Called by the sender after it sent items that were checked with {@link #getGatedAllowance}.
     */
    void onGatedSend(ItemIdentifier item, int amount, IAdditionalTargetInformation info);

    /**
     * Whether the chassis should turn away a delivery of this item that carries no slot information. Such deliveries
     * are items restored after a restart or chunk reload, whose orders no longer exist.
     */
    boolean declinesUntrackedItem(ItemIdentifier item);

    /**
     * @return the gated module an order is addressed to, or null if the order isn't going to one.
     */
    static IGatedItemSink findTarget(IRouter destination, IAdditionalTargetInformation info) {
        if (destination == null || !(info instanceof ChassiTargetInformation)) {
            return null;
        }
        LogisticsModule chassis = destination.getLogisticsModule();
        if (chassis == null) {
            return null;
        }
        LogisticsModule target = chassis.getSubModule(((ChassiTargetInformation) info).getModuleSlot());
        return target instanceof IGatedItemSink ? (IGatedItemSink) target : null;
    }
}
