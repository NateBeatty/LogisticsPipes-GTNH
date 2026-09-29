package logisticspipes.network.packets.module;

import net.minecraft.entity.player.EntityPlayer;

import logisticspipes.modules.ModuleBufferProvider;
import logisticspipes.network.abstractpackets.IntegerModuleCoordinatesPacket;
import logisticspipes.network.abstractpackets.ModernPacket;

/** Sets a Buffer Provider's share percent from its gui. */
public class BufferProviderSharePacket extends IntegerModuleCoordinatesPacket {

    public BufferProviderSharePacket(int id) {
        super(id);
    }

    @Override
    public ModernPacket template() {
        return new BufferProviderSharePacket(getId());
    }

    @Override
    public void processPacket(EntityPlayer player) {
        ModuleBufferProvider module = this.getLogisticsModule(player, ModuleBufferProvider.class);
        if (module == null) {
            return;
        }
        module.setSharePercent(getInteger());
    }
}
