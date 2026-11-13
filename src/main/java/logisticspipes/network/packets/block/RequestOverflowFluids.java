package logisticspipes.network.packets.block;

import java.util.Collections;
import java.util.List;

import net.minecraft.entity.player.EntityPlayer;

import logisticspipes.blocks.stats.LogisticsStatisticsTileEntity;
import logisticspipes.network.PacketHandler;
import logisticspipes.network.abstractpackets.CoordinatesPacket;
import logisticspipes.network.abstractpackets.ModernPacket;
import logisticspipes.pipes.basic.CoreRoutedPipe;
import logisticspipes.proxy.MainProxy;
import logisticspipes.request.OverflowFluids;

/**
 * The Statistics Table's overflow tab asking for the fluids its network's overflow sinks took. Sent every couple of
 * seconds while that tab is open, and never otherwise.
 */
public class RequestOverflowFluids extends CoordinatesPacket {

    public RequestOverflowFluids(int id) {
        super(id);
    }

    @Override
    public void processPacket(EntityPlayer player) {
        LogisticsStatisticsTileEntity tile = this.getTile(player.getEntityWorld(), LogisticsStatisticsTileEntity.class);
        CoreRoutedPipe pipe = tile == null ? null : tile.getConnectedPipe();
        List<OverflowFluids.Entry> entries = pipe == null ? Collections.emptyList()
                : OverflowFluids.collect(pipe.getRouter());
        MainProxy.sendPacketToPlayer(PacketHandler.getPacket(OverflowFluidList.class).setEntries(entries), player);
    }

    @Override
    public ModernPacket template() {
        return new RequestOverflowFluids(getId());
    }
}
