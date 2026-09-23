package logisticspipes.network.packets.pipe;

import java.io.IOException;

import net.minecraft.entity.player.EntityPlayer;

import logisticspipes.modules.ModuleSmartCrafter;
import logisticspipes.network.LPDataInputStream;
import logisticspipes.network.LPDataOutputStream;
import logisticspipes.network.abstractpackets.ModernPacket;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

/**
 * The crafting module's settings plus the ones only the Smart Crafter has, so opening its gui takes one packet.
 */
@Accessors(chain = true)
public class SmartCrafterUpdatePacket extends CraftingPipeUpdatePacket {

    @Getter
    @Setter
    private int[] outputRole = new int[ModuleSmartCrafter.OUTPUT_SLOTS];

    @Getter
    @Setter
    private int[] outputChance = new int[ModuleSmartCrafter.OUTPUT_SLOTS];

    @Getter
    @Setter
    private int[] outputSatelliteId = new int[ModuleSmartCrafter.OUTPUT_SLOTS];

    @Getter
    @Setter
    private boolean cleanupEnabled = true;

    /** One of {@code ModuleSmartCrafter.STATUS_*}; a snapshot from when this was sent. */
    @Getter
    @Setter
    private int status;

    @Getter
    @Setter
    private int setsReleased;

    public SmartCrafterUpdatePacket(int id) {
        super(id);
    }

    @Override
    public void processPacket(EntityPlayer player) {
        ModuleSmartCrafter module = this.getLogisticsModule(player, ModuleSmartCrafter.class);
        if (module == null) {
            return;
        }
        module.handleCraftingUpdatePacket(this);
        module.handleSmartUpdatePacket(this);
    }

    @Override
    public void writeData(LPDataOutputStream data) throws IOException {
        super.writeData(data);
        data.writeIntegerArray(outputRole);
        data.writeIntegerArray(outputChance);
        data.writeIntegerArray(outputSatelliteId);
        data.writeBoolean(cleanupEnabled);
        data.writeInt(status);
        data.writeInt(setsReleased);
    }

    @Override
    public void readData(LPDataInputStream data) throws IOException {
        super.readData(data);
        outputRole = data.readIntegerArray();
        outputChance = data.readIntegerArray();
        outputSatelliteId = data.readIntegerArray();
        cleanupEnabled = data.readBoolean();
        status = data.readInt();
        setsReleased = data.readInt();
    }

    @Override
    public ModernPacket template() {
        return new SmartCrafterUpdatePacket(getId());
    }
}
