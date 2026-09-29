package logisticspipes.gui.modules;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.inventory.IInventory;

import logisticspipes.modules.ModuleBufferProvider;
import logisticspipes.network.PacketHandler;
import logisticspipes.network.packets.module.BufferProviderSharePacket;
import logisticspipes.proxy.MainProxy;
import logisticspipes.utils.gui.SmallGuiButton;
import logisticspipes.utils.string.StringUtils;

/** The Provider gui plus the share percent, in the free space above the Switch button. -/+ step 10, 1 with shift. */
public class GuiBufferProvider extends GuiProvider {

    private static final int SHARE_DOWN = 10;
    private static final int SHARE_UP = 11;

    private final ModuleBufferProvider _buffer;

    public GuiBufferProvider(IInventory playerInventory, ModuleBufferProvider provider) {
        super(playerInventory, provider);
        _buffer = provider;
    }

    @Override
    public void initGui() {
        super.initGui();
        buttonList.add(new SmallGuiButton(SHARE_DOWN, guiLeft + 8, guiTop + 32, 10, 10, "-"));
        buttonList.add(new SmallGuiButton(SHARE_UP, guiLeft + 52, guiTop + 32, 10, 10, "+"));
    }

    @Override
    protected void actionPerformed(GuiButton guibutton) {
        if (guibutton.id == SHARE_DOWN || guibutton.id == SHARE_UP) {
            int step = GuiScreen.isShiftKeyDown() ? 1 : 10;
            _buffer.setSharePercent(_buffer.getSharePercent() + (guibutton.id == SHARE_UP ? step : -step));
            MainProxy.sendPacketToServer(
                    PacketHandler.getPacket(BufferProviderSharePacket.class).setInteger(_buffer.getSharePercent())
                            .setModulePos(_buffer));
            return;
        }
        super.actionPerformed(guibutton);
    }

    @Override
    protected void drawGuiContainerForegroundLayer(int par1, int par2) {
        super.drawGuiContainerForegroundLayer(par1, par2);
        String label = StringUtils.translate("gui.module.ModuleBufferProvider.Share");
        mc.fontRenderer.drawString(label, 35 - mc.fontRenderer.getStringWidth(label) / 2, 21, 0x404040);
        String value = _buffer.getSharePercent() + "%";
        mc.fontRenderer.drawString(value, 35 - mc.fontRenderer.getStringWidth(value) / 2, 33, 0x404040);
    }
}
