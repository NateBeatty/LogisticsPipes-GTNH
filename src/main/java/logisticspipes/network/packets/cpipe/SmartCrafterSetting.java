package logisticspipes.network.packets.cpipe;

import java.io.IOException;

import net.minecraft.entity.player.EntityPlayer;

import logisticspipes.modules.ModuleSmartCrafter;
import logisticspipes.network.LPDataInputStream;
import logisticspipes.network.LPDataOutputStream;
import logisticspipes.network.abstractpackets.ModernPacket;
import logisticspipes.network.abstractpackets.ModuleCoordinatesPacket;
import logisticspipes.proxy.MainProxy;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

/**
 * One per-slot setting of a Smart Crafting module, typed or clicked in its gui. The server answers with the module's
 * full state, so a rejected value (clamped chance, a role a chanced output may not have) shows up right away.
 */
@Accessors(chain = true)
public class SmartCrafterSetting extends ModuleCoordinatesPacket {

    public static final int SATELLITE = 0;
    public static final int OUTPUT_ROLE = 1;
    public static final int OUTPUT_CHANCE = 2;
    public static final int CLEANUP = 3;
    /** Asks for one more set of ingredients; carries no value. */
    public static final int REQUEST_SET = 4;
    /** Carries nothing: the reply below is the point, so the gui's status line stays current while it is open. */
    public static final int REFRESH = 5;
    /** Stored and shown, but nothing acts on it until the Smart Satellite exists. */
    public static final int OUTPUT_SATELLITE = 6;

    @Getter
    @Setter
    private int setting;

    /** Which ingredient or output slot the setting belongs to; unused by module-wide settings. */
    @Getter
    @Setter
    private int index;

    @Getter
    @Setter
    private int value;

    public SmartCrafterSetting(int id) {
        super(id);
    }

    @Override
    public void processPacket(EntityPlayer player) {
        ModuleSmartCrafter module = this.getLogisticsModule(player, ModuleSmartCrafter.class);
        if (module == null) {
            return;
        }
        if (!MainProxy.isServer(player.worldObj)) {
            return;
        }
        if (setting == REQUEST_SET) {
            module.requestOneSet(player);
        } else if (setting != REFRESH) {
            module.handleSettingPacket(setting, index, value);
        }
        MainProxy.sendPacketToPlayer(module.getCPipePacket(), player);
    }

    @Override
    public void writeData(LPDataOutputStream data) throws IOException {
        super.writeData(data);
        data.writeInt(setting);
        data.writeInt(index);
        data.writeInt(value);
    }

    @Override
    public void readData(LPDataInputStream data) throws IOException {
        super.readData(data);
        setting = data.readInt();
        index = data.readInt();
        value = data.readInt();
    }

    @Override
    public ModernPacket template() {
        return new SmartCrafterSetting(getId());
    }
}
