package logisticspipes.network.guis.module.inpipe;

import java.io.IOException;

import net.minecraft.entity.player.EntityPlayer;

import logisticspipes.gui.modules.GuiFluidSink;
import logisticspipes.modules.ModuleFluidSink;
import logisticspipes.network.LPDataInputStream;
import logisticspipes.network.LPDataOutputStream;
import logisticspipes.network.abstractguis.GuiProvider;
import logisticspipes.network.abstractguis.ModuleCoordinatesGuiProvider;
import logisticspipes.utils.gui.DummyContainer;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

@Accessors(chain = true)
public class FluidSinkSlot extends ModuleCoordinatesGuiProvider {

    @Getter
    @Setter
    private boolean isDefaultRoute;

    /** {@code ModuleFluidSink.MODE_*}. Sent after the old flag so a client without it reads the flag and stops. */
    @Getter
    @Setter
    private int mode = -1;

    public FluidSinkSlot(int id) {
        super(id);
    }

    @Override
    public void writeData(LPDataOutputStream data) throws IOException {
        super.writeData(data);
        data.writeBoolean(isDefaultRoute);
        data.writeByte(mode);
    }

    @Override
    public void readData(LPDataInputStream data) throws IOException {
        super.readData(data);
        isDefaultRoute = data.readBoolean();
        mode = data.available() > 0 ? data.readByte() : -1;
    }

    @Override
    public Object getClientGui(EntityPlayer player) {
        ModuleFluidSink module = this.getLogisticsModule(player.getEntityWorld(), ModuleFluidSink.class);
        if (module == null) {
            return null;
        }
        if (mode >= 0) {
            module.setMode(mode);
        } else {
            module.setDefaultRoute(isDefaultRoute);
        }
        return new GuiFluidSink(player.inventory, module);
    }

    @Override
    public DummyContainer getContainer(EntityPlayer player) {
        ModuleFluidSink module = this.getLogisticsModule(player.getEntityWorld(), ModuleFluidSink.class);
        if (module == null) {
            return null;
        }
        DummyContainer dummy = new DummyContainer(player.inventory, module.getFilterInventory());
        dummy.addNormalSlotsForPlayerInventory(8, 60);

        // Pipe slots
        for (int pipeSlot = 0; pipeSlot < 9; pipeSlot++) {
            dummy.addDummySlot(pipeSlot, 8 + pipeSlot * 18, 18);
        }

        return dummy;
    }

    @Override
    public GuiProvider template() {
        return new FluidSinkSlot(getId());
    }
}
