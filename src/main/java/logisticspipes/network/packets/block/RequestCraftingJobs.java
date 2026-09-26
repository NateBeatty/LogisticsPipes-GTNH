package logisticspipes.network.packets.block;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraft.entity.player.EntityPlayer;

import logisticspipes.blocks.stats.LogisticsStatisticsTileEntity;
import logisticspipes.network.PacketHandler;
import logisticspipes.network.abstractpackets.IntegerCoordinatesPacket;
import logisticspipes.network.abstractpackets.ModernPacket;
import logisticspipes.pipes.basic.CoreRoutedPipe;
import logisticspipes.proxy.MainProxy;
import logisticspipes.routing.order.CraftingJob;
import logisticspipes.routing.order.CraftingJobInfo;
import logisticspipes.routing.order.CraftingJobs;

/**
 * The Statistics Table's job list asking for the jobs on its network. Sent by the client about once a second while that
 * tab is open, and never otherwise, so nothing is tracked or sent for a table nobody is looking at. The integer is the
 * job whose open orders the client is showing, or -1.
 */
public class RequestCraftingJobs extends IntegerCoordinatesPacket {

    public RequestCraftingJobs(int id) {
        super(id);
    }

    @Override
    public void processPacket(EntityPlayer player) {
        LogisticsStatisticsTileEntity tile = this.getTile(player.getEntityWorld(), LogisticsStatisticsTileEntity.class);
        sendJobList(player, tile, getInteger());
    }

    /** Sends the jobs on the table's network, with the open orders of one of them if asked for. */
    static void sendJobList(EntityPlayer player, LogisticsStatisticsTileEntity tile, int detailJobId) {
        CoreRoutedPipe pipe = tile == null ? null : tile.getConnectedPipe();
        List<CraftingJob> jobs = pipe == null ? Collections.emptyList() : CraftingJobs.getLiveOn(pipe.getRouter());
        List<CraftingJobInfo> infos = new ArrayList<>(jobs.size());
        List<CraftingJob.WaitingOn> detail = Collections.emptyList();
        for (CraftingJob job : jobs) {
            infos.add(CraftingJobInfo.of(job));
            if (job.getId() == detailJobId) {
                detail = job.getWaitingOn();
            }
        }
        MainProxy.sendPacketToPlayer(
                PacketHandler.getPacket(CraftingJobList.class).setJobs(infos)
                        .setEnded(new ArrayList<>(CraftingJobs.getRecentlyEnded())).setDetailJobId(detailJobId)
                        .setDetail(detail),
                player);
    }

    /** Whether the job is on the table's network, i.e. one this table may show and cancel. */
    static boolean isOnNetwork(LogisticsStatisticsTileEntity tile, CraftingJob job) {
        CoreRoutedPipe pipe = tile == null ? null : tile.getConnectedPipe();
        return pipe != null && job != null && CraftingJobs.getLiveOn(pipe.getRouter()).contains(job);
    }

    @Override
    public ModernPacket template() {
        return new RequestCraftingJobs(getId());
    }
}
