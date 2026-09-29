package logisticspipes.network.guis.module.inpipe;

import java.io.IOException;

import net.minecraft.entity.player.EntityPlayer;

import logisticspipes.gui.modules.GuiBufferProvider;
import logisticspipes.modules.ModuleBufferProvider;
import logisticspipes.network.LPDataInputStream;
import logisticspipes.network.LPDataOutputStream;
import logisticspipes.network.abstractguis.GuiProvider;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

/** The Provider module's gui, plus the Buffer Provider's share setting. */
@Accessors(chain = true)
public class BufferProviderGuiProvider extends ProviderModuleGuiProvider {

    @Getter
    @Setter
    private int sharePercent;

    public BufferProviderGuiProvider(int id) {
        super(id);
    }

    @Override
    public Object getClientGui(EntityPlayer player) {
        ModuleBufferProvider module = this.getLogisticsModule(player.getEntityWorld(), ModuleBufferProvider.class);
        if (module == null) {
            return null;
        }
        module.setFilterExcluded(isExclude());
        module.setExtractionMode(getExtractorMode());
        module.setSharePercent(sharePercent);
        return new GuiBufferProvider(player.inventory, module);
    }

    @Override
    public GuiProvider template() {
        return new BufferProviderGuiProvider(getId());
    }

    @Override
    public void writeData(LPDataOutputStream data) throws IOException {
        super.writeData(data);
        data.writeInt(sharePercent);
    }

    @Override
    public void readData(LPDataInputStream data) throws IOException {
        super.readData(data);
        sharePercent = data.readInt();
    }
}
