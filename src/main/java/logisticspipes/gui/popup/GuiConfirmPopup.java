package logisticspipes.gui.popup;

import net.minecraft.client.gui.GuiButton;

import logisticspipes.utils.gui.GuiGraphics;
import logisticspipes.utils.gui.SubGuiScreen;
import logisticspipes.utils.string.StringUtils;

/**
 * A yes / no question, for actions that can't be undone. Runs the action only on "Yes"; either button closes it.
 */
public class GuiConfirmPopup extends SubGuiScreen {

    private final String[] text;
    private final String confirmLabel;
    private final Runnable onConfirm;
    private int mWidth = 0;

    public GuiConfirmPopup(String confirmLabel, Runnable onConfirm, String... message) {
        super(200, (message.length * 10) + 40, 0, 0);
        this.text = message;
        this.confirmLabel = confirmLabel;
        this.onConfirm = onConfirm;
    }

    @Override
    public void initGui() {
        super.initGui();
        buttonList.clear();
        buttonList.add(new GuiButton(0, xCenter - 55, bottom - 25, 50, 20, confirmLabel));
        buttonList.add(new GuiButton(1, xCenter + 5, bottom - 25, 50, 20, "Back"));
    }

    @Override
    protected void renderGuiBackground(int par1, int par2) {
        if (mWidth == 0) {
            int lWidth = 0;
            for (String msg : text) {
                lWidth = Math.max(lWidth, mc.fontRenderer.getStringWidth(msg));
            }
            xSize = mWidth = Math.max(Math.min(lWidth + 20, 400), 130);
            initGui();
        }
        GuiGraphics.drawGuiBackGround(mc, guiLeft, guiTop, right, bottom, zLevel, true);
        for (int i = 0; i < text.length; i++) {
            String msg = StringUtils.getCuttedString(text[i], mWidth - 10, mc.fontRenderer);
            int stringWidth = mc.fontRenderer.getStringWidth(msg);
            mc.fontRenderer.drawString(msg, xCenter - (stringWidth / 2), guiTop + 10 + (i * 10), 0x404040);
        }
    }

    @Override
    protected void actionPerformed(GuiButton guibutton) {
        if (guibutton.id == 0) {
            onConfirm.run();
            exitGui();
        } else if (guibutton.id == 1) {
            exitGui();
        }
    }
}
