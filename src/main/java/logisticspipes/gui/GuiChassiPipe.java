/*
 * Copyright (c) Krapht, 2011 "LogisticsPipes" is distributed under the terms of the Minecraft Mod Public License 1.0,
 * or MMPL. Please check the contents of the license located in http://www.mod-buildcraft.com/MMPL-1.0.txt
 */
package logisticspipes.gui;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;

import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;

import logisticspipes.items.ItemModule;
import logisticspipes.modules.abstractmodules.LogisticsModule;
import logisticspipes.network.PacketHandler;
import logisticspipes.network.abstractpackets.ModernPacket;
import logisticspipes.network.guis.pipe.ChassiGuiProvider;
import logisticspipes.network.packets.chassis.ChassisGUI;
import logisticspipes.network.packets.pipe.PipeLabelAutoRequest;
import logisticspipes.network.packets.pipe.PipeLabelSave;
import logisticspipes.pipes.PipeLogisticsChassi;
import logisticspipes.pipes.upgrades.ModuleUpgradeManager;
import logisticspipes.proxy.MainProxy;
import logisticspipes.utils.PipeLabel;
import logisticspipes.utils.gui.DummyContainer;
import logisticspipes.utils.gui.GuiGraphics;
import logisticspipes.utils.gui.LogisticsBaseGuiScreen;
import logisticspipes.utils.gui.SmallGuiButton;
import logisticspipes.utils.string.StringUtils;

public class GuiChassiPipe extends LogisticsBaseGuiScreen {

    private static final int AUTO_BUTTON_ID = 20;
    /** A texture row below the top border and above the first slot; repeated to make room for the label strip. */
    private static final int SPLIT_ROW = 6;

    private final PipeLogisticsChassi _chassiPipe;
    private final EntityPlayer _player;
    private final IInventory _moduleInventory;
    // private final GuiScreen _previousGui;

    private int left;
    private int top;

    private final boolean hasUpgradeModuleUpgarde;

    /* Label Upgrade: a name and tags strip above the modules, only while the chassis has the upgrade. */

    private final boolean hasLabel;
    /** How far everything below the label strip is moved down; 0 without a Label Upgrade. */
    private final int off;
    private GuiTextField nameField;
    private GuiTextField tagsField;
    /** What the server has, so closing the GUI only sends a change. */
    private String savedName = "";
    private String savedTags = "";
    private ItemStack savedIcon;
    /** The icon to save: the one already set, until Auto picks another (or none). */
    private ItemStack icon;

    public GuiChassiPipe(EntityPlayer player, PipeLogisticsChassi chassi, boolean hasUpgradeModuleUpgarde,
            PipeLabel label) { // ,
        // GuiScreen
        // previousGui)
        // {
        super(null);
        _player = player;
        _chassiPipe = chassi;
        _moduleInventory = chassi.getModuleInventory();
        // _previousGui = previousGui;
        this.hasUpgradeModuleUpgarde = hasUpgradeModuleUpgarde;
        hasLabel = label != null;
        off = hasLabel ? ChassiGuiProvider.LABEL_HEIGHT : 0;
        if (hasLabel) {
            savedName = label.getName();
            savedTags = label.getTagsText();
            savedIcon = label.getIcon();
            icon = savedIcon;
        }

        DummyContainer dummy = new DummyContainer(_player.inventory, _moduleInventory);
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

        if (hasUpgradeModuleUpgarde) {
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

        inventorySlots = dummy;

        xSize = 194;
        ySize = 186;

        if (_chassiPipe.getChassiSize() > 4) {
            ySize = 256;
        }
        ySize += off;
    }

    @Override
    public void initGui() {
        super.initGui();

        left = width / 2 - xSize / 2;
        top = height / 2 - ySize / 2;

        buttonList.clear();
        for (int i = 0; i < _chassiPipe.getChassiSize(); i++) {
            buttonList.add(new SmallGuiButton(i, left + 5, top + 12 + 20 * i + off, 10, 10, "!"));
            if (_moduleInventory == null) {
                continue;
            }
            ItemStack module = _moduleInventory.getStackInSlot(i);
            if (module == null || _chassiPipe.getLogisticsModule().getSubModule(i) == null) {
                ((SmallGuiButton) buttonList.get(i)).visible = false;
            } else {
                ((SmallGuiButton) buttonList.get(i)).visible = _chassiPipe.getLogisticsModule().getSubModule(i)
                        .hasGui();
            }
        }

        if (hasLabel) {
            // Added after the module buttons, which are looked up by index.
            buttonList.add(new SmallGuiButton(AUTO_BUTTON_ID, guiLeft + xSize - 38, guiTop + 7, 30, 12, "Auto"));
            // Keep what was typed when the window is resized, which calls this again.
            String name = nameField != null ? nameField.getText() : savedName;
            String tags = tagsField != null ? tagsField.getText() : savedTags;
            nameField = new GuiTextField(fontRendererObj, guiLeft + 38, guiTop + 7, xSize - 80, 12);
            nameField.setMaxStringLength(PipeLabel.MAX_NAME_LENGTH);
            nameField.setText(name);
            tagsField = new GuiTextField(fontRendererObj, guiLeft + 38, guiTop + 22, xSize - 46, 12);
            tagsField.setMaxStringLength(PipeLabel.MAX_TAGS_LENGTH);
            tagsField.setText(tags);
        }
    }

    @Override
    protected void actionPerformed(GuiButton guibutton) {

        if (guibutton.id >= 0 && guibutton.id <= 7) {
            LogisticsModule module = _chassiPipe.getLogisticsModule().getSubModule(guibutton.id);
            if (module != null) {
                final ModernPacket packet = PacketHandler.getPacket(ChassisGUI.class).setButtonID(guibutton.id)
                        .setPosX(_chassiPipe.getX()).setPosY(_chassiPipe.getY()).setPosZ(_chassiPipe.getZ());
                MainProxy.sendPacketToServer(packet);
            }
        } else if (guibutton.id == AUTO_BUTTON_ID) {
            MainProxy.sendPacketToServer(
                    PacketHandler.getPacket(PipeLabelAutoRequest.class).setPosX(_chassiPipe.getX())
                            .setPosY(_chassiPipe.getY()).setPosZ(_chassiPipe.getZ()));
        }
    }

    /**
     * Fills the fields with what the Auto button found, and takes its icon (a multiblock's controller, or none). Not
     * saved until Enter is pressed or the GUI is closed.
     */
    public void applyLabelSuggestion(String name, String tags, ItemStack suggestedIcon) {
        if (!hasLabel || nameField == null) {
            return;
        }
        nameField.setText(name);
        tagsField.setText(tags);
        icon = suggestedIcon;
    }

    private void saveLabel() {
        if (!hasLabel || nameField == null) {
            return;
        }
        String name = nameField.getText();
        String tags = tagsField.getText();
        if (name.equals(savedName) && tags.equals(savedTags) && PipeLabel.sameIcon(icon, savedIcon)) {
            return;
        }
        MainProxy.sendPacketToServer(
                PacketHandler.getPacket(PipeLabelSave.class).setName(name).setTags(tags).setIcon(icon)
                        .setPosX(_chassiPipe.getX()).setPosY(_chassiPipe.getY()).setPosZ(_chassiPipe.getZ()));
        savedName = name;
        savedTags = tags;
        savedIcon = icon;
    }

    @Override
    protected void keyTyped(char c, int key) {
        if (hasLabel && nameField != null && (nameField.isFocused() || tagsField.isFocused())) {
            if (key == Keyboard.KEY_ESCAPE) {
                super.keyTyped(c, key); // closes the GUI, which saves
            } else if (key == Keyboard.KEY_RETURN || key == Keyboard.KEY_NUMPADENTER) {
                nameField.setFocused(false);
                tagsField.setFocused(false);
                saveLabel();
            } else if (key == Keyboard.KEY_TAB) {
                boolean toTags = nameField.isFocused();
                nameField.setFocused(!toTags);
                tagsField.setFocused(toTags);
            } else {
                // Typed into a field, so the inventory key doesn't close the GUI.
                (nameField.isFocused() ? nameField : tagsField).textboxKeyTyped(c, key);
            }
            return;
        }
        super.keyTyped(c, key);
    }

    @Override
    protected void mouseClicked(int x, int y, int button) {
        if (hasLabel && nameField != null) {
            nameField.mouseClicked(x, y, button);
            tagsField.mouseClicked(x, y, button);
        }
        super.mouseClicked(x, y, button);
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        if (hasLabel && nameField != null) {
            nameField.updateCursorCounter();
            tagsField.updateCursorCounter();
        }
    }

    @Override
    public void onGuiClosed() {
        saveLabel();
        super.onGuiClosed();
    }

    @Override
    protected void drawGuiContainerForegroundLayer(int par1, int par2) {
        super.drawGuiContainerForegroundLayer(par1, par2);
        for (int i = 0; i < _chassiPipe.getChassiSize(); i++) {
            ItemStack module = _moduleInventory.getStackInSlot(i);
            if (module == null || _chassiPipe.getLogisticsModule().getSubModule(i) == null) {
                ((SmallGuiButton) buttonList.get(i)).visible = false;
            } else {
                ((SmallGuiButton) buttonList.get(i)).visible = _chassiPipe.getLogisticsModule().getSubModule(i)
                        .hasGui();
            }
        }
        if (hasLabel) {
            mc.fontRenderer.drawString("Name", 8, 9, 0x404040);
            mc.fontRenderer.drawString("Tags", 8, 24, 0x404040);
        }
        if (_chassiPipe.getChassiSize() > 0) {
            mc.fontRenderer.drawString(getModuleName(0), 40, 14 + off, 0x404040);
        }
        if (_chassiPipe.getChassiSize() > 1) {
            mc.fontRenderer.drawString(getModuleName(1), 40, 34 + off, 0x404040);
        }
        if (_chassiPipe.getChassiSize() > 2) {
            mc.fontRenderer.drawString(getModuleName(2), 40, 54 + off, 0x404040);
        }
        if (_chassiPipe.getChassiSize() > 3) {
            mc.fontRenderer.drawString(getModuleName(3), 40, 74 + off, 0x404040);
        }
        if (_chassiPipe.getChassiSize() > 4) {
            mc.fontRenderer.drawString(getModuleName(4), 40, 94 + off, 0x404040);
            mc.fontRenderer.drawString(getModuleName(5), 40, 114 + off, 0x404040);
            mc.fontRenderer.drawString(getModuleName(6), 40, 134 + off, 0x404040);
            mc.fontRenderer.drawString(getModuleName(7), 40, 154 + off, 0x404040);
        }
    }

    private String getModuleName(int slot) {
        if (_moduleInventory == null) {
            return "";
        }
        if (_moduleInventory.getStackInSlot(slot) == null) {
            return "";
        }
        if (!(_moduleInventory.getStackInSlot(slot).getItem() instanceof ItemModule)) {
            return "";
        }
        String name = _moduleInventory.getStackInSlot(slot).getItem()
                .getItemStackDisplayName(_moduleInventory.getStackInSlot(slot));
        if (!hasUpgradeModuleUpgarde) {
            return name;
        }
        return StringUtils.getWithMaxWidth(name, 100, fontRendererObj);
    }

    @Override
    protected void drawGuiContainerBackgroundLayer(float f, int x, int y) {
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
        mc.renderEngine.bindTexture(_chassiPipe.getChassiGUITexture());
        if (hasLabel) {
            // One window, taller: the texture's top border, then its plain row SPLIT_ROW repeated for the label strip,
            // then the rest of the texture. The first module slot starts below SPLIT_ROW in every chassis texture.
            drawTexturedModalRect(guiLeft, guiTop, 0, 0, xSize, SPLIT_ROW);
            for (int i = 0; i < off; i++) {
                drawTexturedModalRect(guiLeft, guiTop + SPLIT_ROW + i, 0, SPLIT_ROW, xSize, 1);
            }
            drawTexturedModalRect(guiLeft, guiTop + SPLIT_ROW + off, 0, SPLIT_ROW, xSize, ySize - off - SPLIT_ROW);
        } else {
            drawTexturedModalRect(guiLeft, guiTop, 0, 0, xSize, ySize);
        }
        if (hasUpgradeModuleUpgarde) {
            for (int i = 0; i < _chassiPipe.getChassiSize(); i++) {
                GuiGraphics.drawSlotBackground(mc, guiLeft + 144, guiTop + 8 + i * 20 + off);
                GuiGraphics.drawSlotBackground(mc, guiLeft + 164, guiTop + 8 + i * 20 + off);
            }
        }
        if (hasLabel && nameField != null) {
            nameField.drawTextBox();
            tagsField.drawTextBox();
        }
    }
}
