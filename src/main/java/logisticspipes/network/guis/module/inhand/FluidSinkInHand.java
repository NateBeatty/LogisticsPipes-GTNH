package logisticspipes.network.guis.module.inhand;

import net.minecraft.entity.player.EntityPlayer;

import logisticspipes.gui.modules.GuiFluidSink;
import logisticspipes.modules.ModuleFluidSink;
import logisticspipes.modules.abstractmodules.LogisticsModule;
import logisticspipes.network.abstractguis.GuiProvider;
import logisticspipes.network.abstractguis.ModuleInHandGuiProvider;
import logisticspipes.utils.gui.DummyContainer;
import logisticspipes.utils.gui.DummyModuleContainer;

public class FluidSinkInHand extends ModuleInHandGuiProvider {

    public FluidSinkInHand(int id) {
        super(id);
    }

    @Override
    public Object getClientGui(EntityPlayer player) {
        LogisticsModule module = getLogisticsModule(player);
        if (!(module instanceof ModuleFluidSink)) {
            return null;
        }
        return new GuiFluidSink(player.inventory, (ModuleFluidSink) module);
    }

    @Override
    public DummyContainer getContainer(EntityPlayer player) {
        DummyModuleContainer dummy = new DummyModuleContainer(player, getInvSlot());
        if (!(dummy.getModule() instanceof ModuleFluidSink)) {
            return null;
        }
        dummy.setInventory(((ModuleFluidSink) dummy.getModule()).getFilterInventory());
        dummy.addNormalSlotsForPlayerInventory(8, 60);

        // Pipe slots
        for (int pipeSlot = 0; pipeSlot < 9; pipeSlot++) {
            dummy.addDummySlot(pipeSlot, 8 + pipeSlot * 18, 18);
        }
        return dummy;
    }

    @Override
    public GuiProvider template() {
        return new FluidSinkInHand(getId());
    }
}
