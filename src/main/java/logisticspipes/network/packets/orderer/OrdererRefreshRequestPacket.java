package logisticspipes.network.packets.orderer;

import net.minecraft.entity.player.EntityPlayer;

import logisticspipes.network.abstractpackets.IntegerCoordinatesPacket;
import logisticspipes.network.abstractpackets.ModernPacket;
import logisticspipes.pipes.basic.CoreRoutedPipe;
import logisticspipes.pipes.basic.LogisticsTileGenericPipe;
import logisticspipes.proxy.MainProxy;
import logisticspipes.request.RequestHandler;

public class OrdererRefreshRequestPacket extends IntegerCoordinatesPacket {

    public OrdererRefreshRequestPacket(int id) {
        super(id);
    }

    @Override
    public ModernPacket template() {
        return new OrdererRefreshRequestPacket(getId());
    }

    @Override
    public void processPacket(EntityPlayer player) {
        // The client sends dimension * 10 + mode. Floor division keeps that apart for a negative dimension too (the
        // Nether's -1 is sent as -10 + mode), which plain / and % did not.
        int dimension = Math.floorDiv(getInteger(), 10);
        final LogisticsTileGenericPipe pipe = MainProxy.proxy
                .getPipeInDimensionAt(dimension, getPosX(), getPosY(), getPosZ(), player);
        if (pipe == null || !(pipe.pipe instanceof CoreRoutedPipe)) {
            return;
        }
        // The last digit is the display option, plus 3 for the request table's fluid view.
        int mode = Math.floorMod(getInteger(), 10);
        boolean fluids = mode >= 3 && mode <= 5;
        RequestHandler.DisplayOptions option;
        switch (fluids ? mode - 3 : mode) {
            case 1:
                option = RequestHandler.DisplayOptions.SupplyOnly;
                break;
            case 2:
                option = RequestHandler.DisplayOptions.CraftOnly;
                break;
            case 0:
            default:
                option = RequestHandler.DisplayOptions.Both;
                break;
        }
        if (fluids) {
            RequestHandler.refreshFluids(player, (CoreRoutedPipe) pipe.pipe, option);
        } else {
            RequestHandler.refresh(player, (CoreRoutedPipe) pipe.pipe, option);
        }
    }
}
