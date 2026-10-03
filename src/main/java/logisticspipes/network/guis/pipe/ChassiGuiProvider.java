package logisticspipes.network.guis.pipe;

import java.io.IOException;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;

import logisticspipes.LogisticsPipes;
import logisticspipes.gui.GuiChassiPipe;
import logisticspipes.modules.abstractmodules.LogisticsModule;
import logisticspipes.network.LPDataInputStream;
import logisticspipes.network.LPDataOutputStream;
import logisticspipes.network.NewGuiHandler;
import logisticspipes.network.abstractguis.BooleanModuleCoordinatesGuiProvider;
import logisticspipes.network.abstractguis.GuiProvider;
import logisticspipes.pipes.PipeLogisticsChassi;
import logisticspipes.pipes.basic.LogisticsTileGenericPipe;
import logisticspipes.pipes.upgrades.ModuleUpgradeManager;
import logisticspipes.utils.PipeLabel;
import logisticspipes.utils.gui.DummyContainer;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

@Accessors(chain = true)
public class ChassiGuiProvider extends BooleanModuleCoordinatesGuiProvider {

    /** How far the Label Upgrade's name and tags strip pushes the rest of the chassis GUI down. */
    public static final int LABEL_HEIGHT = 36;

    /** The label to show, or null when the chassis has no Label Upgrade and the GUI looks as it always did. */
    @Getter
    @Setter
    private PipeLabel label;

    public ChassiGuiProvider(int id) {
        super(id);
    }

    /** The chassis GUI for this pipe, with the upgrade module slots and the label strip if it has those upgrades. */
    public static ChassiGuiProvider forPipe(PipeLogisticsChassi chassis) {
        ChassiGuiProvider provider = NewGuiHandler.getGui(ChassiGuiProvider.class)
                .setLabel(chassis.hasLabelUpgrade() ? chassis.getLabel() : null);
        provider.setFlag(chassis.getUpgradeManager().hasUpgradeModuleUpgrade());
        return provider;
    }

    @Override
    public Object getClientGui(EntityPlayer player) {
        LogisticsTileGenericPipe pipe = getPipe(player.getEntityWorld());
        if (pipe == null || !(pipe.pipe instanceof PipeLogisticsChassi)) {
            return null;
        }
        return new GuiChassiPipe(player, (PipeLogisticsChassi) pipe.pipe, isFlag(), label);
    }

    @Override
    public DummyContainer getContainer(EntityPlayer player) {
        LogisticsTileGenericPipe pipe = getPipe(player.getEntityWorld());
        if (pipe == null || !(pipe.pipe instanceof PipeLogisticsChassi)) {
            return null;
        }
        final PipeLogisticsChassi _chassiPipe = (PipeLogisticsChassi) pipe.pipe;
        IInventory _moduleInventory = _chassiPipe.getModuleInventory();
        DummyContainer dummy = new DummyContainer(player.inventory, _moduleInventory);
        // Only the order of the slots has to match the client's; the positions are kept the same anyway.
        int off = _chassiPipe.hasLabelUpgrade() ? LABEL_HEIGHT : 0;
        if (_chassiPipe.getChassiSize() < 5) {
            dummy.addNormalSlotsForPlayerInventory(18, 97 + off);
        } else {
            dummy.addNormalSlotsForPlayerInventory(18, 174 + off);
        }
        if (_chassiPipe.getChassiSize() > 0) {
            dummy.addModuleSlot(0, _moduleInventory, 19, 9 + off, _chassiPipe);
        }
        if (_chassiPipe.getChassiSize() > 1) {
            dummy.addModuleSlot(1, _moduleInventory, 19, 29 + off, _chassiPipe);
        }
        if (_chassiPipe.getChassiSize() > 2) {
            dummy.addModuleSlot(2, _moduleInventory, 19, 49 + off, _chassiPipe);
        }
        if (_chassiPipe.getChassiSize() > 3) {
            dummy.addModuleSlot(3, _moduleInventory, 19, 69 + off, _chassiPipe);
        }
        if (_chassiPipe.getChassiSize() > 4) {
            dummy.addModuleSlot(4, _moduleInventory, 19, 89 + off, _chassiPipe);
            dummy.addModuleSlot(5, _moduleInventory, 19, 109 + off, _chassiPipe);
            dummy.addModuleSlot(6, _moduleInventory, 19, 129 + off, _chassiPipe);
            dummy.addModuleSlot(7, _moduleInventory, 19, 149 + off, _chassiPipe);
        }

        if (_chassiPipe.getUpgradeManager().hasUpgradeModuleUpgrade()) {
            for (int i = 0; i < _chassiPipe.getChassiSize(); i++) {
                final int fI = i;
                ModuleUpgradeManager upgradeManager = _chassiPipe.getModuleUpgradeManager(i);
                dummy.addRestrictedSlot(
                        0,
                        upgradeManager.getInv(),
                        145,
                        9 + i * 20 + off,
                        itemStack -> ChassiGuiProvider.checkStack(itemStack, _chassiPipe, fI));
                dummy.addRestrictedSlot(
                        1,
                        upgradeManager.getInv(),
                        165,
                        9 + i * 20 + off,
                        itemStack -> ChassiGuiProvider.checkStack(itemStack, _chassiPipe, fI));
            }
        }
        return dummy;
    }

    public static boolean checkStack(ItemStack stack, PipeLogisticsChassi chassiPipe, int moduleSlot) {
        if (stack == null) {
            return false;
        }
        if (!stack.getItem().equals(LogisticsPipes.UpgradeItem)) {
            return false;
        }
        LogisticsModule module = chassiPipe.getModules().getModule(moduleSlot);
        if (module == null) {
            return false;
        }
        return LogisticsPipes.UpgradeItem.getUpgradeForItem(stack, null).isAllowedForModule(module);
    }

    @Override
    public void writeData(LPDataOutputStream data) throws IOException {
        super.writeData(data);
        data.writeBoolean(label != null);
        if (label != null) {
            label.write(data);
        }
    }

    @Override
    public void readData(LPDataInputStream data) throws IOException {
        super.readData(data);
        label = data.readBoolean() ? PipeLabel.read(data) : null;
    }

    @Override
    public GuiProvider template() {
        return new ChassiGuiProvider(getId());
    }
}
