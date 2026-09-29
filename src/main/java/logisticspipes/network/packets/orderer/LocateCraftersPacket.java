package logisticspipes.network.packets.orderer;

import net.minecraft.entity.player.EntityPlayer;

import logisticspipes.network.PacketHandler;
import logisticspipes.network.abstractpackets.ModernPacket;
import logisticspipes.network.abstractpackets.RequestPacket;
import logisticspipes.pipes.basic.CoreRoutedPipe;
import logisticspipes.pipes.basic.LogisticsTileGenericPipe;
import logisticspipes.proxy.MainProxy;
import logisticspipes.request.CrafterLocator;
import lombok.experimental.Accessors;

/** The request table's Locate button: asks where the selected item is crafted on the table's network. */
@Accessors(chain = true)
public class LocateCraftersPacket extends RequestPacket {

    public LocateCraftersPacket(int id) {
        super(id);
    }

    @Override
    public ModernPacket template() {
        return new LocateCraftersPacket(getId());
    }

    @Override
    public void processPacket(EntityPlayer player) {
        final LogisticsTileGenericPipe pipe = MainProxy.proxy
                .getPipeInDimensionAt(getDimension(), getPosX(), getPosY(), getPosZ(), player);
        if (pipe == null || !(pipe.pipe instanceof CoreRoutedPipe) || getStack() == null) {
            return;
        }
        MainProxy.sendPacketToPlayer(
                PacketHandler.getPacket(CrafterLocations.class).setItem(getStack().getItem())
                        .setLocations(CrafterLocator.find((CoreRoutedPipe) pipe.pipe, getStack().getItem())),
                player);
    }
}
