package logisticspipes.network.packets.pipe;

import java.io.IOException;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;

import logisticspipes.network.LPDataInputStream;
import logisticspipes.network.LPDataOutputStream;
import logisticspipes.network.abstractpackets.CoordinatesPacket;
import logisticspipes.network.abstractpackets.ModernPacket;
import logisticspipes.pipes.PipeLogisticsChassi;
import logisticspipes.utils.PipeLabel;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

/** The chassis GUI saving the name and tags typed for a chassis with a Label Upgrade, and the icon Auto picked. */
@Accessors(chain = true)
public class PipeLabelSave extends CoordinatesPacket {

    @Getter
    @Setter
    private String name = "";

    @Getter
    @Setter
    private String tags = "";

    /** The icon Auto picked, or null. Only its item and damage are kept. */
    @Getter
    @Setter
    private ItemStack icon;

    public PipeLabelSave(int id) {
        super(id);
    }

    @Override
    public void processPacket(EntityPlayer player) {
        PipeLogisticsChassi chassis = getTileOrPipe(player.getEntityWorld(), PipeLogisticsChassi.class);
        if (chassis == null || !chassis.hasLabelUpgrade()) {
            return;
        }
        chassis.setLabel(PipeLabel.of(name, tags).withIcon(icon));
        chassis.getWorld()
                .markTileEntityChunkModified(chassis.getX(), chassis.getY(), chassis.getZ(), chassis.container);
    }

    @Override
    public void writeData(LPDataOutputStream data) throws IOException {
        super.writeData(data);
        data.writeUTF(name);
        data.writeUTF(tags);
        data.writeItemStack(icon);
    }

    @Override
    public void readData(LPDataInputStream data) throws IOException {
        super.readData(data);
        name = data.readUTF();
        tags = data.readUTF();
        icon = data.readItemStack();
    }

    @Override
    public ModernPacket template() {
        return new PipeLabelSave(getId());
    }
}
