package logisticspipes.network.packets.pipe;

import java.io.IOException;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;

import cpw.mods.fml.client.FMLClientHandler;
import logisticspipes.gui.GuiChassiPipe;
import logisticspipes.network.LPDataInputStream;
import logisticspipes.network.LPDataOutputStream;
import logisticspipes.network.abstractpackets.ModernPacket;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

/** The server's answer to {@link PipeLabelAutoRequest}: a name and tags to put in the open chassis GUI. */
@Accessors(chain = true)
public class PipeLabelSuggestion extends ModernPacket {

    @Getter
    @Setter
    private String name = "";

    @Getter
    @Setter
    private String tags = "";

    /** The multiblock's controller when the chassis faces one of its hatches, else null. */
    @Getter
    @Setter
    private ItemStack icon;

    public PipeLabelSuggestion(int id) {
        super(id);
    }

    @Override
    public void processPacket(EntityPlayer player) {
        if (FMLClientHandler.instance().getClient().currentScreen instanceof GuiChassiPipe) {
            ((GuiChassiPipe) FMLClientHandler.instance().getClient().currentScreen)
                    .applyLabelSuggestion(name, tags, icon);
        }
    }

    @Override
    public void writeData(LPDataOutputStream data) throws IOException {
        data.writeUTF(name);
        data.writeUTF(tags);
        data.writeItemStack(icon);
    }

    @Override
    public void readData(LPDataInputStream data) throws IOException {
        name = data.readUTF();
        tags = data.readUTF();
        icon = data.readItemStack();
    }

    @Override
    public ModernPacket template() {
        return new PipeLabelSuggestion(getId());
    }
}
