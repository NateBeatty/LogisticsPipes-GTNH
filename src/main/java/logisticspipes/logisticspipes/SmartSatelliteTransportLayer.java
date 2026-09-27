package logisticspipes.logisticspipes;

import logisticspipes.pipes.PipeLogisticsChassi.ChassiTargetInformation;
import logisticspipes.pipes.PipeSmartSatellite;
import logisticspipes.routing.IRouter;
import logisticspipes.utils.item.ItemIdentifier;

/**
 * Like the default {@link PipeTransportLayer} (which always accepts items so passive deliveries can reach the
 * machine), except it declines <em>active</em> deliveries that lost their slot information when the owning Smart
 * Crafter would reject them.
 * <p>
 * This mirrors {@link ChassiTransportLayer#acceptsActiveItem}: a normal order to a slot always carries that
 * information; only items saved mid-pipe and loaded again (server restart, chunk reload) lose it. Their orders are
 * gone, so letting them in would leave partial sets in the machine. Such deliveries are sent on to storage instead.
 */
public class SmartSatelliteTransportLayer extends PipeTransportLayer {

    private final PipeSmartSatellite _pipe;

    public SmartSatelliteTransportLayer(IAdjacentWorldAccess worldAccess, ITrackStatistics trackStatistics,
            IRouter router, PipeSmartSatellite pipe) {
        super(worldAccess, trackStatistics, router);
        _pipe = pipe;
    }

    @Override
    public boolean acceptsActiveItem(IRoutedItem item) {
        if (item.getAdditionalTargetInformation() instanceof ChassiTargetInformation
                || item.getItemIdentifierStack() == null) {
            return true;
        }
        ItemIdentifier id = item.getItemIdentifierStack().getItem();
        if (_pipe.declinesUntrackedItem(id)) {
            _pipe.notifyOfItemArival(item.getInfo());
            return false;
        }
        return true;
    }
}
