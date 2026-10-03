package logisticspipes.network.packets.gui;

import net.minecraft.entity.player.EntityPlayer;

import logisticspipes.modules.abstractmodules.LogisticsModule.ModulePositionType;
import logisticspipes.network.abstractpackets.CoordinatesPacket;
import logisticspipes.network.abstractpackets.ModernPacket;
import logisticspipes.network.guis.pipe.ChassiGuiProvider;
import logisticspipes.pipes.PipeLogisticsChassi;
import logisticspipes.pipes.basic.LogisticsTileGenericPipe;

public class GuiOpenChassie extends CoordinatesPacket {

    public GuiOpenChassie(int id) {
        super(id);
    }

    @Override
    public void processPacket(EntityPlayer player) {
        LogisticsTileGenericPipe pipe = this.getPipe(player.getEntityWorld());
        if (!(pipe.pipe instanceof PipeLogisticsChassi)) {
            return;
        }
        ChassiGuiProvider.forPipe((PipeLogisticsChassi) pipe.pipe).setSlot(ModulePositionType.IN_PIPE).setPositionInt(0)
                .setPosX(getPosX()).setPosY(getPosY()).setPosZ(getPosZ()).open(player);
    }

    @Override
    public ModernPacket template() {
        return new GuiOpenChassie(getId());
    }
}
