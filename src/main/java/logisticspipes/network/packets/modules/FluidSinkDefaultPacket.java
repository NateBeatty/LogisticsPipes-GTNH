package logisticspipes.network.packets.modules;

import net.minecraft.entity.player.EntityPlayer;

import logisticspipes.modules.ModuleFluidSink;
import logisticspipes.network.abstractpackets.BooleanModuleCoordinatesPacket;
import logisticspipes.network.abstractpackets.ModernPacket;

public class FluidSinkDefaultPacket extends BooleanModuleCoordinatesPacket {

    public FluidSinkDefaultPacket(int id) {
        super(id);
    }

    @Override
    public ModernPacket template() {
        return new FluidSinkDefaultPacket(getId());
    }

    @Override
    public void processPacket(EntityPlayer player) {
        ModuleFluidSink module = this.getLogisticsModule(player, ModuleFluidSink.class);
        if (module == null) {
            return;
        }
        module.setDefaultRoute(isFlag());
    }
}
