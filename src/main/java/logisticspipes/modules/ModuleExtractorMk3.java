package logisticspipes.modules;

import java.util.List;

import net.minecraft.client.renderer.texture.IIconRegister;
import net.minecraft.util.IIcon;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import logisticspipes.config.Configs;
import logisticspipes.pipes.basic.CoreRoutedPipe.ItemSendMode;
import logisticspipes.utils.SinkDiag;
import logisticspipes.utils.SinkReply;
import logisticspipes.utils.item.ItemIdentifier;
import logisticspipes.utils.tuples.Pair;

public class ModuleExtractorMk3 extends ModuleExtractorMk2 {

    // TEMPORARY [SinkDiag]: this module acts every tick, so trace at most one decision a second.
    private static final int DIAG_INTERVAL_TICKS = 20;
    private long lastDiagTick = -DIAG_INTERVAL_TICKS;

    public ModuleExtractorMk3() {
        super();
    }

    @Override
    protected Pair<Integer, SinkReply> findDestination(ItemIdentifier item, List<Integer> jamList) {
        if (!Configs.DEBUG_SINK_ROUTING) {
            return super.findDestination(item, jamList);
        }
        long now = _world.getWorld().getTotalWorldTime();
        if (now - lastDiagTick < DIAG_INTERVAL_TICKS) {
            return super.findDestination(item, jamList);
        }
        lastDiagTick = now;
        SinkDiag.begin(item, _service.getX(), _service.getY(), _service.getZ(), jamList);
        Pair<Integer, SinkReply> reply = null;
        try {
            reply = super.findDestination(item, jamList);
            return reply;
        } finally {
            SinkDiag.end(reply);
        }
    }

    @Override
    protected int ticksToAction() {
        return 1;
    }

    @Override
    protected int itemsToExtract() {
        return 64;
    }

    @Override
    protected int neededEnergy() {
        return 10;
    }

    @Override
    protected ItemSendMode itemSendMode() {
        return ItemSendMode.Fast;
    }

    @Override
    @SideOnly(Side.CLIENT)
    public IIcon getIconTexture(IIconRegister register) {
        return register.registerIcon("logisticspipes:itemModule/ModuleExtractorMk3");
    }
}
