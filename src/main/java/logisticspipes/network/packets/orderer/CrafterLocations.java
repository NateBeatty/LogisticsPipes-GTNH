package logisticspipes.network.packets.orderer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.entity.player.EntityPlayer;

import logisticspipes.network.LPDataInputStream;
import logisticspipes.network.LPDataOutputStream;
import logisticspipes.network.abstractpackets.ModernPacket;
import logisticspipes.proxy.MainProxy;
import logisticspipes.request.CrafterLocator;
import logisticspipes.utils.item.ItemIdentifier;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

/** The server's answer to {@link LocateCraftersPacket}: every crafter on the network that could make the item. */
@Accessors(chain = true)
public class CrafterLocations extends ModernPacket {

    @Getter
    @Setter
    private ItemIdentifier item;

    @Getter
    @Setter
    private List<CrafterLocator.Location> locations = new ArrayList<>();

    public CrafterLocations(int id) {
        super(id);
    }

    @Override
    public ModernPacket template() {
        return new CrafterLocations(getId());
    }

    @Override
    public void processPacket(EntityPlayer player) {
        MainProxy.proxy.processCrafterLocations(this, player);
    }

    @Override
    public void writeData(LPDataOutputStream data) throws IOException {
        data.writeItemIdentifier(item);
        data.writeList(locations, (out, location) -> location.write(out));
    }

    @Override
    public void readData(LPDataInputStream data) throws IOException {
        item = data.readItemIdentifier();
        locations = data.readList(CrafterLocator.Location::read);
    }
}
