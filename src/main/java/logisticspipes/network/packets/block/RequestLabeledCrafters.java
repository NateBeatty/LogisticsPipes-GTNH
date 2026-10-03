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
import logisticspipes.request.LabeledCrafters;

/**
 * The Statistics Table's crafter list asking for the labeled chassis on its network. Sent every couple of seconds while
 * that tab is open, and never otherwise.
 */
public class RequestLabeledCrafters extends CoordinatesPacket {

    public RequestLabeledCrafters(int id) {
        super(id);
    }

    @Override
    public void processPacket(EntityPlayer player) {
        LogisticsStatisticsTileEntity tile = this.getTile(player.getEntityWorld(), LogisticsStatisticsTileEntity.class);
        CoreRoutedPipe pipe = tile == null ? null : tile.getConnectedPipe();
        List<LabeledCrafters.Entry> entries = pipe == null ? Collections.emptyList()
                : LabeledCrafters.collect(pipe.getRouter());
        MainProxy.sendPacketToPlayer(PacketHandler.getPacket(LabeledCrafterList.class).setEntries(entries), player);
    }

    @Override
    public ModernPacket template() {
        return new RequestLabeledCrafters(getId());
    }
}
