package logisticspipes.network.guis.module.inhand;

import net.minecraft.entity.player.EntityPlayer;

import logisticspipes.gui.modules.GuiBufferProvider;
import logisticspipes.modules.ModuleBufferProvider;
import logisticspipes.modules.abstractmodules.LogisticsModule;
import logisticspipes.network.abstractguis.GuiProvider;

/** The Buffer Provider's gui for a module held in hand. The container is the Provider module's. */
public class BufferProviderInHand extends ProviderModuleInHand {

    public BufferProviderInHand(int id) {
        super(id);
    }

    @Override
    public Object getClientGui(EntityPlayer player) {
        LogisticsModule module = getLogisticsModule(player);
        if (!(module instanceof ModuleBufferProvider)) {
            return null;
        }
        return new GuiBufferProvider(player.inventory, (ModuleBufferProvider) module);
    }

    @Override
    public GuiProvider template() {
        return new BufferProviderInHand(getId());
    }
}
