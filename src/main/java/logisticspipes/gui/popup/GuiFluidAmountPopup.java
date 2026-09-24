package logisticspipes.gui.popup;

import java.util.function.IntConsumer;

import net.minecraft.client.gui.GuiButton;

import logisticspipes.utils.gui.GuiGraphics;
import logisticspipes.utils.gui.GuiNumberField;
import logisticspipes.utils.gui.SmallGuiButton;
import logisticspipes.utils.gui.SubGuiScreen;

/**
 * Types the amount of a fluid ingredient, in litres, the unit GT recipes and NEI are written in.
 */
public class GuiFluidAmountPopup extends SubGuiScreen {

    private static final int MAX_AMOUNT = 2_000_000_000;

    private final String fluidName;
    private final int startAmount;
    private final IntConsumer onSet;
    private GuiNumberField field;

    public GuiFluidAmountPopup(String fluidName, int amount, IntConsumer onSet) {
        super(150, 60, 0, 0);
        this.fluidName = fluidName;
        startAmount = amount;
        this.onSet = onSet;
    }

    @Override
    public void initGui() {
        super.initGui();
        buttonList.clear();
        field = new GuiNumberField(12, 26, 90, 14, 0, MAX_AMOUNT, value -> {});
        field.setValue(startAmount);
        // Open with the box ready to type in, so the amount can be replaced without clicking it first.
        field.mouseClicked(guiLeft + 13, guiTop + 27, guiLeft, guiTop);
        buttonList.add(new SmallGuiButton(0, guiLeft + 108, guiTop + 27, 30, 12, "OK"));
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id == 0) {
            accept();
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) {
        field.mouseClicked(mouseX, mouseY, guiLeft, guiTop);
        super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        if (keyCode == 28 || keyCode == 156) { // enter, numpad enter
            accept();
            return;
        }
        if (field.keyTyped(typedChar, keyCode)) {
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    private void accept() {
        field.unfocus();
        onSet.accept(field.getValue());
        exitGui();
    }

    @Override
    protected void renderGuiBackground(int mouseX, int mouseY) {
        GuiGraphics.drawGuiBackGround(mc, guiLeft, guiTop, right, bottom, zLevel, true);
        mc.fontRenderer.drawString(fluidName, guiLeft + 12, guiTop + 12, 0x404040);
        field.draw(mc, guiLeft, guiTop);
        mc.fontRenderer.drawString("L", guiLeft + 106, guiTop + 30, 0x404040);
    }
}
