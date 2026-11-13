package logisticspipes.network.packets.block;

import java.io.IOException;

import net.minecraft.entity.player.EntityPlayer;

import logisticspipes.blocks.stats.LogisticsStatisticsTileEntity;
import logisticspipes.network.LPDataInputStream;
import logisticspipes.network.LPDataOutputStream;
import logisticspipes.network.abstractpackets.CoordinatesPacket;
import logisticspipes.network.abstractpackets.ModernPacket;
import logisticspipes.pipes.basic.CoreRoutedPipe;
import logisticspipes.request.OverflowFluids;
import logisticspipes.utils.item.ItemIdentifier;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

/**
 * Clears the Statistics Table's overflow list: one fluid on every overflow sink of the table's network, or the whole
 * list when no fluid is given. Only the log is cleared; nothing in the sinks' tanks is touched.
 */
@Accessors(chain = true)
public class ClearOverflowFluids extends CoordinatesPacket {

    /** The fluid's container item, or null for all. */
    @Getter
    @Setter
    private ItemIdentifier fluid;

    public ClearOverflowFluids(int id) {
        super(id);
    }

    @Override
    public void writeData(LPDataOutputStream data) throws IOException {
        super.writeData(data);
        data.writeBoolean(fluid != null);
        if (fluid != null) {
            data.writeItemIdentifier(fluid);
        }
    }

    @Override
    public void readData(LPDataInputStream data) throws IOException {
        super.readData(data);
        fluid = data.readBoolean() ? data.readItemIdentifier() : null;
    }

    @Override
    public void processPacket(EntityPlayer player) {
        LogisticsStatisticsTileEntity tile = this.getTile(player.getEntityWorld(), LogisticsStatisticsTileEntity.class);
        CoreRoutedPipe pipe = tile == null ? null : tile.getConnectedPipe();
        if (pipe != null) {
            OverflowFluids.clear(pipe.getRouter(), fluid);
        }
    }

    @Override
    public ModernPacket template() {
        return new ClearOverflowFluids(getId());
    }
}
