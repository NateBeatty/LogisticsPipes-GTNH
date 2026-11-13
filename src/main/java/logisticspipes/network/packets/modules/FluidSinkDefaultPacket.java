package logisticspipes.network.packets.modules;

import java.io.IOException;

import net.minecraft.entity.player.EntityPlayer;

import logisticspipes.modules.ModuleFluidSink;
import logisticspipes.network.LPDataInputStream;
import logisticspipes.network.LPDataOutputStream;
import logisticspipes.network.abstractpackets.BooleanModuleCoordinatesPacket;
import logisticspipes.network.abstractpackets.ModernPacket;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

/**
 * Sets a Fluid Sink's fallback mode. The old default-route flag still comes first; the mode follows it, so a client
 * built before the mode existed sends just the flag and is read as off or default route.
 */
@Accessors(chain = true)
public class FluidSinkDefaultPacket extends BooleanModuleCoordinatesPacket {

    /** {@code ModuleFluidSink.MODE_*}, or -1 when the sender only knows the flag. */
    @Getter
    @Setter
    private int mode = -1;

    public FluidSinkDefaultPacket(int id) {
        super(id);
    }

    @Override
    public void writeData(LPDataOutputStream data) throws IOException {
        super.writeData(data);
        data.writeByte(mode);
    }

    @Override
    public void readData(LPDataInputStream data) throws IOException {
        super.readData(data);
        mode = data.available() > 0 ? data.readByte() : -1;
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
        if (mode >= 0) {
            module.setMode(mode);
        } else {
            module.setDefaultRoute(isFlag());
        }
    }
}
