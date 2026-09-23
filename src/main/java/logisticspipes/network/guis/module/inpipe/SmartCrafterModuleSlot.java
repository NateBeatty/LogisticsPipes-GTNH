package logisticspipes.network.guis.module.inpipe;

import net.minecraft.entity.player.EntityPlayer;

import logisticspipes.gui.GuiSmartCrafter;
import logisticspipes.modules.ModuleSmartCrafter;
import logisticspipes.network.abstractguis.GuiProvider;
import logisticspipes.network.abstractguis.ModuleCoordinatesGuiProvider;
import logisticspipes.proxy.MainProxy;
import logisticspipes.utils.gui.DummyContainer;

public class SmartCrafterModuleSlot extends ModuleCoordinatesGuiProvider {

    public SmartCrafterModuleSlot(int id) {
        super(id);
    }

    @Override
    public Object getClientGui(EntityPlayer player) {
        ModuleSmartCrafter module = this.getLogisticsModule(player.getEntityWorld(), ModuleSmartCrafter.class);
        if (module == null) {
            return null;
        }
        return new GuiSmartCrafter(player, module.getDummyInventory(), module);
    }

    @Override
    public DummyContainer getContainer(EntityPlayer player) {
        ModuleSmartCrafter module = this.getLogisticsModule(player.getEntityWorld(), ModuleSmartCrafter.class);
        if (module == null) {
            return null;
        }
        MainProxy.sendPacketToPlayer(module.getCPipePacket(), player);
        DummyContainer dummy = new DummyContainer(player.inventory, module.getDummyInventory());
        // Same slots in the same order as the client gui, which is what click handling goes by.
        dummy.addNormalSlotsForPlayerInventory(8, 178);
        for (int slot = 0; slot < 9; slot++) {
            dummy.addDummySlot(slot, 10 + (slot % 3) * 30, 24 + (slot / 3) * 32);
        }
        for (int out = 0; out < ModuleSmartCrafter.OUTPUT_SLOTS; out++) {
            dummy.addDummySlot(ModuleSmartCrafter.outputInventorySlot(out), 126, 24 + out * 32);
        }
        return dummy;
    }

    @Override
    public GuiProvider template() {
        return new SmartCrafterModuleSlot(getId());
    }
}
