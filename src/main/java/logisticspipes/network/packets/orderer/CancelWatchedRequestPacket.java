package logisticspipes.network.packets.orderer;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ChatComponentText;

import logisticspipes.network.abstractpackets.IntegerCoordinatesPacket;
import logisticspipes.network.abstractpackets.ModernPacket;
import logisticspipes.pipes.PipeBlockRequestTable;
import logisticspipes.pipes.basic.LogisticsTileGenericPipe;
import logisticspipes.request.resources.IResource;
import logisticspipes.routing.order.CraftingJob;
import logisticspipes.routing.order.CraftingJobs;
import logisticspipes.routing.order.LinkedLogisticsOrderList;
import logisticspipes.utils.tuples.Pair;

/**
 * Cancels the request a request table is watching, from the Cancel button of its Request Monitor popup. The integer is
 * the table's watch id for that request.
 * <p>
 * Nothing is sent back: once the cancelled orders are finished and nothing is in flight, the table's own expiry check
 * removes the watched request, which closes the popup.
 */
public class CancelWatchedRequestPacket extends IntegerCoordinatesPacket {

    public CancelWatchedRequestPacket(int id) {
        super(id);
    }

    @Override
    public void processPacket(EntityPlayer player) {
        LogisticsTileGenericPipe tile = this.getPipe(player.worldObj);
        if (tile == null || !(tile.pipe instanceof PipeBlockRequestTable)) {
            return;
        }
        Pair<IResource, LinkedLogisticsOrderList> watched = ((PipeBlockRequestTable) tile.pipe).watchedRequests
                .get(getInteger());
        if (watched == null) {
            say(player, "That request has already finished.");
            return;
        }
        CraftingJob job = CraftingJobs.findJob(watched.getValue2());
        if (job == null) {
            say(player, "That request can't be cancelled (it was made before cancelling existed).");
            return;
        }
        CraftingJob.CancelResult result = job.cancel();
        if (result == null) {
            say(player, "That request has already finished.");
            return;
        }
        say(
                player,
                "Cancelled: stopped " + result.getCrafts()
                        + " craft(s) and "
                        + result.getDeliveries()
                        + " delivery(ies). Items already moving will still arrive.");
        if (result.getReplanned() > 0) {
            say(
                    player,
                    result.getReplanned()
                            + " order(s) in other requests used this request's surplus and were planned again.");
        }
        for (CraftingJob other : result.getShortJobs()) {
            say(
                    player,
                    "Request #" + other.getId()
                            + " couldn't be fully re-planned from what the network has, and will wait until cancelled.");
        }
    }

    private static void say(EntityPlayer player, String message) {
        player.addChatComponentMessage(new ChatComponentText(message));
    }

    @Override
    public ModernPacket template() {
        return new CancelWatchedRequestPacket(getId());
    }
}
