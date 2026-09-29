package logisticspipes.gui.modules;

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
 * GUI for the Fluid Sink module. The same 9-slot filter plus default-route toggle as the item sink, minus fuzzy matching
 * and the import button (fluid identity already comes from the container's NBT). Reuses the item-sink texture.
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

    @Override
    public void initGui() {
        super.initGui();
        // Default-route toggle:
        buttonList.clear();
        buttonList.add(
                new GuiStringHandlerButton(
                        0,
                        width / 2 + 50,
                        height / 2 - 34,
                        30,
                        20,
                        () -> StringUtils.translate(GuiFluidSink.PREFIX + (_fluidSink.isDefaultRoute() ? "Yes" : "No"))));
    }

    @Override
    protected void actionPerformed(GuiButton guibutton) {
        if (guibutton.id == 0) {
            _fluidSink.setDefaultRoute(!_fluidSink.isDefaultRoute());
            MainProxy.sendPacketToServer(
                    PacketHandler.getPacket(FluidSinkDefaultPacket.class).setFlag(_fluidSink.isDefaultRoute())
                            .setModulePos(_fluidSink));
        }
    }

    @Override
    protected void drawGuiContainerForegroundLayer(int par1, int par2) {
        mc.fontRenderer.drawString(_fluidSink.getFilterInventory().getInventoryName(), 8, 6, 0x404040);
        mc.fontRenderer.drawString(StringUtils.translate("gui.logisticspipes.inventory.title"), 8, ySize - 92, 0x404040);
        mc.fontRenderer.drawString(StringUtils.translate(GuiFluidSink.PREFIX + "Defaultroute") + ":", 65, 45, 0x404040);
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
