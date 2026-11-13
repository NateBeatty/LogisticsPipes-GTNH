package logisticspipes.gui.modules;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.inventory.IInventory;
import net.minecraft.util.ResourceLocation;

import org.lwjgl.opengl.GL11;

import logisticspipes.modules.ModuleFluidSink;
import logisticspipes.network.PacketHandler;
import logisticspipes.network.packets.modules.FluidSinkDefaultPacket;
import logisticspipes.proxy.MainProxy;
import logisticspipes.utils.gui.DummyContainer;
import logisticspipes.utils.gui.GuiStringHandlerButton;
import logisticspipes.utils.string.StringUtils;

/**
 * GUI for the Fluid Sink module. The same 9-slot filter plus default-route toggle as the item sink, minus fuzzy
 * matching and the import button (fluid identity already comes from the container's NBT). Reuses the item-sink texture.
 */
public class GuiFluidSink extends ModuleBaseGui {

    private static final String PREFIX = "gui.fluidsink.";
    private static final ResourceLocation TEXTURE = new ResourceLocation("logisticspipes", "textures/gui/itemsink.png");

    private final ModuleFluidSink _fluidSink;

    public GuiFluidSink(IInventory playerInventory, ModuleFluidSink fluidSink) {
        super(null, fluidSink);
        _fluidSink = fluidSink;
        DummyContainer dummy = new DummyContainer(playerInventory, _fluidSink.getFilterInventory());
        dummy.addNormalSlotsForPlayerInventory(8, 60);

        // Pipe slots
        for (int pipeSlot = 0; pipeSlot < 9; pipeSlot++) {
            dummy.addDummySlot(pipeSlot, 8 + pipeSlot * 18, 18);
        }

        inventorySlots = dummy;
        xSize = 175;
        ySize = 142;
    }

    // Fallback-mode button, relative to the GUI's left/top edge.
    private static final int BUTTON_X = 112;
    private static final int BUTTON_Y = 37;
    private static final int BUTTON_W = 56;
    private static final int BUTTON_H = 20;

    @Override
    public void initGui() {
        super.initGui();
        // Fallback mode: off -> default route -> overflow.
        buttonList.clear();
        buttonList.add(
                new GuiStringHandlerButton(
                        0,
                        guiLeft + BUTTON_X,
                        guiTop + BUTTON_Y,
                        BUTTON_W,
                        BUTTON_H,
                        () -> StringUtils.translate(GuiFluidSink.PREFIX + "mode." + _fluidSink.getMode())));
    }

    @Override
    protected void actionPerformed(GuiButton guibutton) {
        if (guibutton.id == 0) {
            _fluidSink.setMode(_fluidSink.nextMode());
            MainProxy.sendPacketToServer(
                    PacketHandler.getPacket(FluidSinkDefaultPacket.class).setMode(_fluidSink.getMode())
                            .setFlag(_fluidSink.isDefaultRoute()).setModulePos(_fluidSink));
        }
    }

    @Override
    protected void drawGuiContainerForegroundLayer(int par1, int par2) {
        mc.fontRenderer.drawString(_fluidSink.getFilterInventory().getInventoryName(), 8, 6, 0x404040);
        mc.fontRenderer
                .drawString(StringUtils.translate("gui.logisticspipes.inventory.title"), 8, ySize - 92, 0x404040);
        String label = StringUtils.translate(GuiFluidSink.PREFIX + "Fallback") + ":";
        mc.fontRenderer.drawString(
                label,
                BUTTON_X - 4 - mc.fontRenderer.getStringWidth(label),
                BUTTON_Y + (BUTTON_H - 8) / 2,
                0x404040);

        int mouseX = par1 - guiLeft;
        int mouseY = par2 - guiTop;
        if (mouseX >= BUTTON_X && mouseX < BUTTON_X + BUTTON_W
                && mouseY >= BUTTON_Y
                && mouseY < BUTTON_Y + BUTTON_H) {
            List<String> lines = new ArrayList<>();
            lines.add(StringUtils.translate(GuiFluidSink.PREFIX + "mode." + _fluidSink.getMode()));
            lines.addAll(
                    mc.fontRenderer.listFormattedStringToWidth(
                            "§7" + StringUtils.translate(GuiFluidSink.PREFIX + "mode." + _fluidSink.getMode() + ".tip"),
                            180));
            func_146283_a(lines, mouseX, mouseY);
        }
    }

    @Override
    protected void drawGuiContainerBackgroundLayer(float f, int x, int y) {
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
        mc.renderEngine.bindTexture(GuiFluidSink.TEXTURE);
        int j = guiLeft;
        int k = guiTop;
        drawTexturedModalRect(j, k, 0, 0, xSize, ySize);
    }
}
