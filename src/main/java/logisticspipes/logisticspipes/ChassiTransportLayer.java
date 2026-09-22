package logisticspipes.logisticspipes;

import net.minecraftforge.common.util.ForgeDirection;

import logisticspipes.interfaces.routing.IGatedItemSink;
import logisticspipes.modules.abstractmodules.LogisticsModule;
import logisticspipes.pipes.PipeLogisticsChassi;
import logisticspipes.pipes.PipeLogisticsChassi.ChassiTargetInformation;
import logisticspipes.utils.SinkReply;
import logisticspipes.utils.item.ItemIdentifier;

public class ChassiTransportLayer extends TransportLayer {

    private final PipeLogisticsChassi _chassiPipe;

    public ChassiTransportLayer(PipeLogisticsChassi chassiPipe) {
        _chassiPipe = chassiPipe;
    }

    @Override
    public ForgeDirection itemArrived(IRoutedItem item, ForgeDirection blocked) {
        if (item.getItemIdentifierStack() != null) {
            _chassiPipe.recievedItem(item.getItemIdentifierStack().getStackSize());
        }
        return _chassiPipe.getPointedOrientation();
    }

    /**
     * Declines active deliveries that lost their slot information, if a gated module (Smart Crafter) in this chassis
     * uses the item. In normal play every delivery to a chassis carries that information; only items that were saved
     * mid-pipe and loaded again (server restart, chunk reload) lose it. Their orders are gone, so letting them in would
     * leave partial sets in the machine. They are sent on to storage instead.
     */
    @Override
    public boolean acceptsActiveItem(IRoutedItem item) {
        if (item.getAdditionalTargetInformation() instanceof ChassiTargetInformation
                || item.getItemIdentifierStack() == null) {
            return true;
        }
        LogisticsModule chassis = _chassiPipe.getLogisticsModule();
        if (chassis == null) {
            return true;
        }
        ItemIdentifier id = item.getItemIdentifierStack().getItem();
        for (int slot = 0; slot < _chassiPipe.getChassiSize(); slot++) {
            LogisticsModule module = chassis.getSubModule(slot);
            if (module instanceof IGatedItemSink && ((IGatedItemSink) module).declinesUntrackedItem(id)) {
                _chassiPipe.notifyOfItemArival(item.getInfo());
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean stillWantItem(IRoutedItem item) {
        LogisticsModule module = _chassiPipe.getLogisticsModule();
        if (module == null) {
            _chassiPipe.notifyOfItemArival(item.getInfo());
            return false;
        }
        if (!_chassiPipe.isEnabled()) {
            _chassiPipe.notifyOfItemArival(item.getInfo());
            return false;
        }
        SinkReply reply = module.sinksItem(item.getItemIdentifierStack().getItem(), -1, 0, true, false);
        if (reply == null || reply.maxNumberOfItems < 0) {
            _chassiPipe.notifyOfItemArival(item.getInfo());
            return false;
        }

        if (reply.maxNumberOfItems > 0 && item.getItemIdentifierStack().getStackSize() > reply.maxNumberOfItems) {
            ForgeDirection o = _chassiPipe.getPointedOrientation();
            if (o == null || o == ForgeDirection.UNKNOWN) {
                o = ForgeDirection.UP;
            }

            item.split(reply.maxNumberOfItems, o);
        }
        return true;
    }
}
