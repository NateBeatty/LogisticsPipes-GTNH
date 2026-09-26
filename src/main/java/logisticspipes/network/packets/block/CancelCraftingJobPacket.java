package logisticspipes.network.packets.block;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ChatComponentText;

import logisticspipes.blocks.stats.LogisticsStatisticsTileEntity;
import logisticspipes.network.abstractpackets.IntegerCoordinatesPacket;
import logisticspipes.network.abstractpackets.ModernPacket;
import logisticspipes.routing.order.CraftingJob;
import logisticspipes.routing.order.CraftingJobs;

/**
 * Cancels a job from the Statistics Table's job list. The integer is the job id. Only jobs on the table's own network
 * can be cancelled from it. Answers with a fresh job list, so the row turns to "Cancelled" straight away.
 */
public class CancelCraftingJobPacket extends IntegerCoordinatesPacket {

    public CancelCraftingJobPacket(int id) {
        super(id);
    }

    @Override
    public void processPacket(EntityPlayer player) {
        LogisticsStatisticsTileEntity tile = this.getTile(player.getEntityWorld(), LogisticsStatisticsTileEntity.class);
        CraftingJob job = CraftingJobs.get(getInteger());
        if (job == null || !RequestCraftingJobs.isOnNetwork(tile, job)) {
            player.addChatComponentMessage(new ChatComponentText("That request has already finished."));
        } else {
            CraftingJob.CancelResult result = job.cancel();
            if (result == null) {
                player.addChatComponentMessage(new ChatComponentText("That request has already finished."));
            } else {
                for (String line : result.describe()) {
                    player.addChatComponentMessage(new ChatComponentText(line));
                }
            }
        }
        RequestCraftingJobs.sendJobList(player, tile, -1);
    }

    @Override
    public ModernPacket template() {
        return new CancelCraftingJobPacket(getId());
    }
}
