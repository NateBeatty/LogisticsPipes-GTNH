package logisticspipes.gui;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.IInventory;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IIcon;
import net.minecraftforge.fluids.FluidStack;

import org.lwjgl.opengl.GL11;

import logisticspipes.gui.modules.ModuleBaseGui;
import logisticspipes.gui.popup.GuiFluidAmountPopup;
import logisticspipes.modules.ModuleSmartCrafter;
import logisticspipes.modules.ModuleSmartCrafter.OutputRole;
import logisticspipes.network.packets.cpipe.SmartCrafterSetting;
import logisticspipes.utils.FluidIdentifier;
import logisticspipes.utils.gui.DummyContainer;
import logisticspipes.utils.gui.GuiCheckBox;
import logisticspipes.utils.gui.GuiGraphics;
import logisticspipes.utils.gui.GuiNumberField;
import logisticspipes.utils.gui.SmallGuiButton;
import logisticspipes.utils.string.StringUtils;

/**
 * The Smart Crafting module's gui: a 3x3 ingredient grid feeding three results, each with a satellite id, a chance and
 * a role (see {@link OutputRole}). The normal crafter's gui can't carry those, and its fluid and satellite panels are
 * upgrade-gated, which this module doesn't use.
 */
public class GuiSmartCrafter extends ModuleBaseGui {

    private static final String PREFIX = "gui.smartcrafter.";

    private static final int WIDTH = 248;
    private static final int HEIGHT = 260;

    private static final int[] INPUT_COL_X = { 10, 40, 70 };
    private static final int[] ROW_Y = { 24, 56, 88 };
    /** Satellite box under each ingredient slot, a little wider than the slot so three digits fit. */
    private static final int SAT_BOX_W = 28;
    private static final int BOX_H = 12;

    private static final int OUT_SLOT_X = 126;
    private static final int CHANCE_X = 146;
    private static final int CHANCE_W = 24;
    /** Wide enough for the longest role name, "Byproduct+", which is 57px of text. */
    private static final int ROLE_X = 178;
    private static final int ROLE_W = 62;

    private static final int ACTION_ROW_Y = 124;
    private static final int SETTING_ROW_Y = 138;
    private static final int STATUS_Y = 152;

    private static final int OPEN_X = 10;
    private static final int OPEN_W = 30;
    private static final int IMPORT_X = 44;
    private static final int IMPORT_W = 38;
    private static final int REQUEST_X = 86;
    private static final int REQUEST_W = 66;
    /** Priority sits on its own row, laid out as the crafting pipe's: a label, then the value between two arrows. */
    private static final int PRIORITY_LABEL_X = 140;
    private static final int PRIORITY_DOWN_X = 196;
    private static final int PRIORITY_UP_X = 228;
    private static final int PRIORITY_VALUE_X = 217;

    private static final int ID_OPEN = 0;
    private static final int ID_IMPORT = 1;
    private static final int ID_REQUEST = 2;
    private static final int ID_PRIORITY_DOWN = 3;
    private static final int ID_PRIORITY_UP = 4;
    private static final int ID_CLEANUP = 5;
    private static final int ID_ROLE = 10;

    /** How often the open gui asks the server for a fresh status. */
    private static final int REFRESH_TICKS = 40;

    private final EntityPlayer player;
    private final ModuleSmartCrafter crafter;
    private final GuiNumberField[] satelliteFields = new GuiNumberField[9];
    private final GuiNumberField[] outputSatelliteFields = new GuiNumberField[ModuleSmartCrafter.OUTPUT_SLOTS];
    private final GuiNumberField[] chanceFields = new GuiNumberField[ModuleSmartCrafter.OUTPUT_SLOTS];
    private final GuiButton[] roleButtons = new GuiButton[ModuleSmartCrafter.OUTPUT_SLOTS];
    private GuiCheckBox cleanupBox;
    private int refreshCountdown = REFRESH_TICKS;

    public GuiSmartCrafter(EntityPlayer player, IInventory dummyInventory, ModuleSmartCrafter module) {
        super(null, module);
        this.player = player;
        crafter = module;
        xSize = WIDTH;
        ySize = HEIGHT;

        DummyContainer dummy = new DummyContainer(player.inventory, dummyInventory);
        dummy.addNormalSlotsForPlayerInventory(8, ySize - 82);
        for (int slot = 0; slot < 9; slot++) {
            dummy.addDummySlot(slot, INPUT_COL_X[slot % 3], ROW_Y[slot / 3]);
        }
        for (int out = 0; out < ModuleSmartCrafter.OUTPUT_SLOTS; out++) {
            dummy.addDummySlot(ModuleSmartCrafter.outputInventorySlot(out), OUT_SLOT_X, ROW_Y[out]);
        }
        inventorySlots = dummy;
    }

    @Override
    public void initGui() {
        super.initGui();
        buttonList.clear();

        for (int slot = 0; slot < 9; slot++) {
            final int index = slot;
            satelliteFields[slot] = new GuiNumberField(
                    INPUT_COL_X[slot % 3] - 5,
                    ROW_Y[slot / 3] + 19,
                    SAT_BOX_W,
                    BOX_H,
                    0,
                    999,
                    value -> crafter.sendSetting(SmartCrafterSetting.SATELLITE, index, value))
                            .setEmptyText(StringUtils.translate(GuiSmartCrafter.PREFIX + "Off"))
                            .setTooltip(StringUtils.translate(GuiSmartCrafter.PREFIX + "tooltip.InputSatellite"));
            satelliteFields[slot].setValue(crafter.advancedSatelliteIdArray[slot]);
        }
        for (int out = 0; out < ModuleSmartCrafter.OUTPUT_SLOTS; out++) {
            final int index = out;
            outputSatelliteFields[out] = new GuiNumberField(
                    OUT_SLOT_X - 5,
                    ROW_Y[out] + 19,
                    SAT_BOX_W,
                    BOX_H,
                    0,
                    999,
                    value -> crafter.sendSetting(SmartCrafterSetting.OUTPUT_SATELLITE, index, value))
                            .setEmptyText(StringUtils.translate(GuiSmartCrafter.PREFIX + "Off"))
                            .setTooltip(StringUtils.translate(GuiSmartCrafter.PREFIX + "tooltip.OutputSatellite"));
            outputSatelliteFields[out].setValue(crafter.getOutputSatelliteId(out));
            chanceFields[out] = new GuiNumberField(
                    CHANCE_X,
                    ROW_Y[out] + 3,
                    CHANCE_W,
                    BOX_H,
                    1,
                    100,
                    value -> crafter.sendSetting(SmartCrafterSetting.OUTPUT_CHANCE, index, value))
                            // 100 means "no chance behaviour", the same sort of default as a satellite id of 0.
                            .setPlaceholderValue(100)
                            .setTooltip(StringUtils.translate(GuiSmartCrafter.PREFIX + "tooltip.Chance"));
            chanceFields[out].setValue(crafter.getOutputChance(out));
            addButton(
                    roleButtons[out] = new SmallGuiButton(
                            ID_ROLE + out,
                            guiLeft + ROLE_X,
                            guiTop + ROW_Y[out] + 4,
                            ROLE_W,
                            10,
                            ""));
        }

        addButton(
                new SmallGuiButton(
                        ID_OPEN,
                        guiLeft + OPEN_X,
                        guiTop + ACTION_ROW_Y,
                        OPEN_W,
                        10,
                        StringUtils.translate(GuiSmartCrafter.PREFIX + "Open")));
        addButton(
                new SmallGuiButton(
                        ID_IMPORT,
                        guiLeft + IMPORT_X,
                        guiTop + ACTION_ROW_Y,
                        IMPORT_W,
                        10,
                        StringUtils.translate(GuiSmartCrafter.PREFIX + "Import")));
        addButton(
                new SmallGuiButton(
                        ID_REQUEST,
                        guiLeft + REQUEST_X,
                        guiTop + ACTION_ROW_Y,
                        REQUEST_W,
                        10,
                        StringUtils.translate(GuiSmartCrafter.PREFIX + "Request")));
        addButton(
                new SmallGuiButton(
                        ID_PRIORITY_DOWN,
                        guiLeft + PRIORITY_DOWN_X,
                        guiTop + SETTING_ROW_Y - 1,
                        10,
                        10,
                        "<"));
        addButton(new SmallGuiButton(ID_PRIORITY_UP, guiLeft + PRIORITY_UP_X, guiTop + SETTING_ROW_Y - 1, 10, 10, ">"));
        addButton(
                cleanupBox = new GuiCheckBox(
                        ID_CLEANUP,
                        guiLeft + 10,
                        guiTop + SETTING_ROW_Y - 1,
                        10,
                        10,
                        crafter.isCleanupEnabled()));
    }

    @Override
    protected void actionPerformed(GuiButton guibutton) {
        if (guibutton.id >= ID_ROLE && guibutton.id < ID_ROLE + ModuleSmartCrafter.OUTPUT_SLOTS) {
            int out = guibutton.id - ID_ROLE;
            OutputRole next = crafter.getOutputRole(out).next(!crafter.isChanced(out));
            crafter.sendSetting(SmartCrafterSetting.OUTPUT_ROLE, out, next.ordinal());
            return;
        }
        switch (guibutton.id) {
            case ID_OPEN:
                crafter.openAttachedGui(player);
                return;
            case ID_IMPORT:
                crafter.importFromCraftingTable(player);
                return;
            case ID_REQUEST:
                crafter.sendSetting(SmartCrafterSetting.REQUEST_SET, 0, 0);
                return;
            case ID_PRIORITY_DOWN:
                crafter.priorityDown(player);
                return;
            case ID_PRIORITY_UP:
                crafter.priorityUp(player);
                return;
            case ID_CLEANUP:
                crafter.sendSetting(SmartCrafterSetting.CLEANUP, 0, cleanupBox.change() ? 1 : 0);
                return;
            default:
                super.actionPerformed(guibutton);
        }
    }

    /** The ingredient slot under the cursor, or -1. */
    private int ingredientSlotAt(int mouseX, int mouseY) {
        for (int slot = 0; slot < 9; slot++) {
            int left = guiLeft + INPUT_COL_X[slot % 3];
            int top = guiTop + ROW_Y[slot / 3];
            if (mouseX >= left && mouseX < left + 18 && mouseY >= top && mouseY < top + 18) {
                return slot;
            }
        }
        return -1;
    }

    private void openAmountPopup(int slot) {
        FluidIdentifier fluid = crafter.getFluidIngredient(slot);
        if (fluid == null) {
            return;
        }
        setSubGui(
                new GuiFluidAmountPopup(
                        fluid.getName(),
                        crafter.getFluidAmount(slot),
                        amount -> crafter.sendSetting(SmartCrafterSetting.FLUID_AMOUNT, slot, amount)));
    }

    /** Every entry box, in one list, so clicks and keys reach all of them. */
    private List<GuiNumberField> entryFields() {
        List<GuiNumberField> fields = new ArrayList<>();
        fields.addAll(Arrays.asList(satelliteFields));
        fields.addAll(Arrays.asList(outputSatelliteFields));
        fields.addAll(Arrays.asList(chanceFields));
        return fields;
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) {
        if (button == 2) {
            int slot = ingredientSlotAt(mouseX, mouseY);
            // Middle click sets how much of a fluid the recipe takes; an item slot carries its amount as a stack size.
            if (slot >= 0 && crafter.isFluidSlot(slot)) {
                openAmountPopup(slot);
                return;
            }
        }
        boolean taken = false;
        for (GuiNumberField field : entryFields()) {
            taken |= field.mouseClicked(mouseX, mouseY, guiLeft, guiTop);
        }
        if (!taken) {
            super.mouseClicked(mouseX, mouseY, button);
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        for (GuiNumberField field : entryFields()) {
            if (field.keyTyped(typedChar, keyCode)) {
                return;
            }
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        super.drawScreen(mouseX, mouseY, partialTicks);
        if (hasSubGui()) {
            return;
        }
        for (GuiNumberField field : entryFields()) {
            if (field.getTooltip() != null && field.isMouseOver(mouseX, mouseY, guiLeft, guiTop)) {
                GuiGraphics.drawToolTip(
                        mouseX,
                        mouseY,
                        Collections.singletonList(field.getTooltip()),
                        EnumChatFormatting.WHITE);
                return;
            }
        }
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        if (--refreshCountdown <= 0) {
            refreshCountdown = REFRESH_TICKS;
            crafter.sendSetting(SmartCrafterSetting.REFRESH, 0, 0);
        }
    }

    @Override
    public void onGuiClosed() {
        super.onGuiClosed();
        for (GuiNumberField field : entryFields()) {
            field.unfocus();
        }
        inventorySlots.onContainerClosed(player);
    }

    @Override
    protected void drawGuiContainerBackgroundLayer(float f, int x, int y) {
        GuiGraphics.drawGuiBackGround(mc, guiLeft, guiTop, guiLeft + xSize, guiTop + ySize, zLevel, true);
        for (int slot = 0; slot < 9; slot++) {
            GuiGraphics.drawSlotBackground(mc, guiLeft + INPUT_COL_X[slot % 3] - 1, guiTop + ROW_Y[slot / 3] - 1);
        }
        for (int out = 0; out < ModuleSmartCrafter.OUTPUT_SLOTS; out++) {
            GuiGraphics.drawSlotBackground(mc, guiLeft + OUT_SLOT_X - 1, guiTop + ROW_Y[out] - 1);
        }
        GuiGraphics.drawPlayerInventoryBackground(mc, guiLeft + 8, guiTop + ySize - 82);
        super.renderExtentions();
    }

    @Override
    protected void drawGuiContainerForegroundLayer(int par1, int par2) {
        super.drawGuiContainerForegroundLayer(par1, par2);
        mc.fontRenderer.drawString(StringUtils.translate(GuiSmartCrafter.PREFIX + "Title"), 10, 6, 0x404040);
        mc.fontRenderer.drawString(StringUtils.translate(GuiSmartCrafter.PREFIX + "Inputs"), 10, 14, 0x404040);
        mc.fontRenderer.drawString(StringUtils.translate(GuiSmartCrafter.PREFIX + "Outputs"), OUT_SLOT_X, 14, 0x404040);
        // No headers over the chance boxes or the role buttons: "Outputs" reaches almost to them, the boxes have
        // tooltips, and "Product" / "Byproduct" on the button says what it is without a title above it.
        mc.fontRenderer
                .drawString(StringUtils.translate("gui.logisticspipes.inventory.title"), 10, ySize - 93, 0x404040);
        mc.fontRenderer.drawString("»", 104, ROW_Y[1] + 5, 0x404040);

        for (int slot = 0; slot < 9; slot++) {
            satelliteFields[slot].setValue(crafter.advancedSatelliteIdArray[slot]);
            satelliteFields[slot].setEnabled(crafter.getMaterials(slot) != null);
            satelliteFields[slot].draw(mc, 0, 0);
            if (crafter.isFluidSlot(slot)) {
                // LP's fluid item is one flat icon whatever the fluid, so the fluid's own texture goes over the top:
                // a fluid in a recipe should look like the fluid, the way GT shows it.
                drawFluidIcon(crafter.getFluidIngredient(slot), INPUT_COL_X[slot % 3], ROW_Y[slot / 3]);
                drawFluidAmount(crafter.getFluidAmount(slot), INPUT_COL_X[slot % 3], ROW_Y[slot / 3]);
            }
        }
        for (int out = 0; out < ModuleSmartCrafter.OUTPUT_SLOTS; out++) {
            boolean used = crafter.getOutput(out) != null;
            outputSatelliteFields[out].setValue(crafter.getOutputSatelliteId(out));
            outputSatelliteFields[out].setEnabled(used);
            outputSatelliteFields[out].draw(mc, 0, 0);
            chanceFields[out].setValue(crafter.getOutputChance(out));
            chanceFields[out].setEnabled(used);
            chanceFields[out].draw(mc, 0, 0);
            mc.fontRenderer.drawString("%", CHANCE_X + CHANCE_W + 1, ROW_Y[out] + 6, 0x404040);
            roleButtons[out].enabled = used;
            roleButtons[out].displayString = StringUtils
                    .translate(GuiSmartCrafter.PREFIX + crafter.getOutputRole(out).name());
        }

        mc.fontRenderer.drawString(
                StringUtils.translate(GuiSmartCrafter.PREFIX + "Priority") + ":",
                PRIORITY_LABEL_X,
                SETTING_ROW_Y + 1,
                0x404040);
        // Between the two arrows, not across them.
        String priority = Integer.toString(crafter.priority);
        mc.fontRenderer.drawString(
                priority,
                PRIORITY_VALUE_X - mc.fontRenderer.getStringWidth(priority) / 2,
                SETTING_ROW_Y + 1,
                0x404040);
        mc.fontRenderer
                .drawString(StringUtils.translate(GuiSmartCrafter.PREFIX + "Cleanup"), 24, SETTING_ROW_Y + 1, 0x404040);
        String sets = StringUtils.translate(GuiSmartCrafter.PREFIX + "SetsInFlight") + ": "
                + StringUtils.translate(GuiSmartCrafter.PREFIX + "Auto");
        mc.fontRenderer.drawString(sets, PRIORITY_LABEL_X + 20, ACTION_ROW_Y + 1, 0x808080);

        drawStatus();
    }

    /**
     * Draws a fluid's own texture into a 16x16 slot, tinted the way the fluid is drawn in the world. Coordinates are
     * gui-relative, since the foreground layer is drawn under the gui's translation, and it runs after the slots so
     * this covers the placeholder item LP stores the fluid as.
     */
    private void drawFluidIcon(FluidIdentifier fluid, int x, int y) {
        if (fluid == null) {
            return;
        }
        FluidStack stack = fluid.makeFluidStack(1000);
        if (stack.getFluid() == null) {
            return;
        }
        IIcon icon = stack.getFluid().getStillIcon();
        if (icon == null) {
            icon = stack.getFluid().getIcon();
        }
        if (icon == null) {
            return;
        }
        int colour = stack.getFluid().getColor(stack);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glEnable(GL11.GL_BLEND);
        // Slot items are drawn at zLevel 100 with the depth test on, so a quad at zLevel 0 would land behind them.
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        float oldZ = zLevel;
        zLevel = 200F;
        mc.renderEngine.bindTexture(TextureMap.locationBlocksTexture);
        GL11.glColor4f(((colour >> 16) & 0xFF) / 255F, ((colour >> 8) & 0xFF) / 255F, (colour & 0xFF) / 255F, 1F);
        drawTexturedModelRectFromIcon(x, y, icon, 16, 16);
        zLevel = oldZ;
        GL11.glColor4f(1F, 1F, 1F, 1F);
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
    }

    /**
     * Draws the amount over a fluid slot the way GT draws its own: half scale, white, with a shadow, in the bottom
     * right corner. The placement is GT's formula from {@code RecipeMapFrontend.drawNEIOverlayText} with
     * {@code Alignment.BottomRight}: the text's right edge on the slot's right edge, its baseline on the bottom.
     * <p>
     * Half scale means everything is in doubled coordinates, so a gui position has to be multiplied out. The depth test
     * is off so the text lands on top of the fluid icon rather than behind it.
     */
    private void drawFluidAmount(int litres, int slotX, int slotY) {
        String text = formatLitres(litres);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glPushMatrix();
        GL11.glScalef(0.5F, 0.5F, 1F);
        int x = (slotX + 16) * 2 - mc.fontRenderer.getStringWidth(text);
        int y = (slotY + 16) * 2 - mc.fontRenderer.FONT_HEIGHT;
        mc.fontRenderer.drawString(text, x, y, 0xFFFFFF, true);
        GL11.glPopMatrix();
        GL11.glEnable(GL11.GL_DEPTH_TEST);
    }

    /** Grouped in thousands with GT's "L" suffix, e.g. 144000 as "144,000L". */
    private static String formatLitres(int litres) {
        StringBuilder digits = new StringBuilder(Integer.toString(litres));
        for (int at = digits.length() - 3; at > 0; at -= 3) {
            digits.insert(at, ',');
        }
        return digits.append('L').toString();
    }

    private void drawStatus() {
        int status = crafter.getLastStatus();
        int colour;
        switch (status) {
            case ModuleSmartCrafter.STATUS_NO_PRODUCT:
            case ModuleSmartCrafter.STATUS_NO_MACHINE:
            case ModuleSmartCrafter.STATUS_SET_TOO_LARGE:
                colour = 0xFFAA0000;
                break;
            case ModuleSmartCrafter.STATUS_WAITING_CLAIM:
            case ModuleSmartCrafter.STATUS_HOLDING:
                colour = 0xFFBB8800;
                break;
            default:
                colour = 0xFF00AA00;
        }
        Gui.drawRect(xSize - 16, 6, xSize - 8, 14, colour);
        String text = StringUtils.translate(GuiSmartCrafter.PREFIX + "status." + status);
        if (status == ModuleSmartCrafter.STATUS_RUNNING) {
            text = text + " (" + crafter.getSetsReleased() + ")";
        }
        mc.fontRenderer.drawString(text, 10, STATUS_Y, colour & 0xFFFFFF);
    }
}
