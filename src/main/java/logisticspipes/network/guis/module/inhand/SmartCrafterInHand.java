package logisticspipes.network.guis.module.inhand;

import net.minecraft.entity.player.EntityPlayer;

import logisticspipes.gui.GuiSmartCrafter;
import logisticspipes.modules.ModuleSmartCrafter;
import logisticspipes.modules.abstractmodules.LogisticsModule;
import logisticspipes.network.abstractguis.GuiProvider;
import logisticspipes.network.abstractguis.ModuleInHandGuiProvider;
import logisticspipes.proxy.MainProxy;
import logisticspipes.utils.gui.DummyContainer;
import logisticspipes.utils.gui.DummyModuleContainer;

public class SmartCrafterInHand extends ModuleInHandGuiProvider {

    public SmartCrafterInHand(int id) {
        super(id);
    }

    @Override
    public Object getClientGui(EntityPlayer player) {
        LogisticsModule module = getLogisticsModule(player);
        if (!(module instanceof ModuleSmartCrafter)) {
            return null;
        }
        ModuleSmartCrafter crafter = (ModuleSmartCrafter) module;
        return new GuiSmartCrafter(player, crafter.getDummyInventory(), crafter);
    }

    @Override
    public DummyContainer getContainer(EntityPlayer player) {
        DummyModuleContainer dummy = new DummyModuleContainer(player, getInvSlot());
        if (!(dummy.getModule() instanceof ModuleSmartCrafter)) {
            return null;
        }
        ModuleSmartCrafter crafter = (ModuleSmartCrafter) dummy.getModule();
        MainProxy.sendPacketToPlayer(crafter.getCPipePacket(), player);
        dummy.setInventory(crafter.getDummyInventory());
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
        return new SmartCrafterInHand(getId());
    }
}
