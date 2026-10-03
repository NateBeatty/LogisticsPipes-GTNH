package logisticspipes.network.packets.pipe;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ChatComponentText;

import logisticspipes.network.PacketHandler;
import logisticspipes.network.abstractpackets.CoordinatesPacket;
import logisticspipes.network.abstractpackets.ModernPacket;
import logisticspipes.pipes.PipeLogisticsChassi;
import logisticspipes.proxy.MainProxy;
import logisticspipes.utils.PipeLabel;
import logisticspipes.utils.PipeLabelSuggester;

/**
 * The chassis GUI's Auto button. The machine is read on the server, where GregTech's tier, circuit and tooltip are all
 * known; the answer only fills the GUI's fields and is saved like anything typed.
 */
public class PipeLabelAutoRequest extends CoordinatesPacket {

    public PipeLabelAutoRequest(int id) {
        super(id);
    }

    @Override
    public void processPacket(EntityPlayer player) {
        PipeLogisticsChassi chassis = getTileOrPipe(player.getEntityWorld(), PipeLogisticsChassi.class);
        if (chassis == null || !chassis.hasLabelUpgrade()) {
            return;
        }
        PipeLabel suggestion = PipeLabelSuggester.suggest(chassis);
        if (suggestion == null) {
            player.addChatComponentMessage(new ChatComponentText("This chassis isn't facing a machine."));
            return;
        }
        MainProxy.sendPacketToPlayer(
                PacketHandler.getPacket(PipeLabelSuggestion.class).setName(suggestion.getName())
                        .setTags(suggestion.getTagsText()).setIcon(suggestion.getIcon()),
                player);
    }

    @Override
    public ModernPacket template() {
        return new PipeLabelAutoRequest(getId());
    }
}
