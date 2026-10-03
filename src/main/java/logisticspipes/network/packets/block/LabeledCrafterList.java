package logisticspipes.network.packets.block;

import java.io.IOException;
import java.util.List;

import net.minecraft.entity.player.EntityPlayer;

import cpw.mods.fml.client.FMLClientHandler;
import logisticspipes.gui.GuiStatistics;
import logisticspipes.network.LPDataInputStream;
import logisticspipes.network.LPDataOutputStream;
import logisticspipes.network.abstractpackets.ModernPacket;
import logisticspipes.request.LabeledCrafters;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

/** The server's answer to {@link RequestLabeledCrafters}. */
@Accessors(chain = true)
public class LabeledCrafterList extends ModernPacket {

    @Getter
    @Setter
    private List<LabeledCrafters.Entry> entries;

    public LabeledCrafterList(int id) {
        super(id);
    }

    @Override
    public void processPacket(EntityPlayer player) {
        if (FMLClientHandler.instance().getClient().currentScreen instanceof GuiStatistics) {
            ((GuiStatistics) FMLClientHandler.instance().getClient().currentScreen).handleCrafterList(entries);
        }
    }

    @Override
    public void writeData(LPDataOutputStream data) throws IOException {
        data.writeList(entries, (out, entry) -> entry.write(out));
    }

    @Override
    public void readData(LPDataInputStream data) throws IOException {
        entries = data.readList(LabeledCrafters.Entry::read);
    }

    @Override
    public ModernPacket template() {
        return new LabeledCrafterList(getId());
    }
}
