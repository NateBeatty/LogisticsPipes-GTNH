package logisticspipes.utils;

import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraftforge.common.util.ForgeDirection;

import cpw.mods.fml.common.Loader;
import logisticspipes.proxy.gregtech.GregTechOutputPush;

/**
 * Finds machine outputs that empty themselves, so a Smart Crafter can warn that results would bypass it: pushed
 * straight into whatever is in front of them, they are never extracted by the crafter, never fill an order and never
 * count as a finished set.
 */
public final class OutputPushUtil {

    private static final boolean GREGTECH = Loader.isModLoaded("gregtech");

    private OutputPushUtil() {}

    /** Whether this tile pushes its outputs away on its own. */
    public static boolean pushesOutputs(TileEntity tile) {
        return GREGTECH && tile != null && GregTechOutputPush.pushesOutputs(tile);
    }

    /** The first block next to x, y, z that pushes its outputs away on its own, or null if there is none. */
    public static TileEntity findPushingNeighbour(World world, int x, int y, int z) {
        if (!GREGTECH || world == null) {
            return null;
        }
        for (ForgeDirection dir : ForgeDirection.VALID_DIRECTIONS) {
            TileEntity tile = world.getTileEntity(x + dir.offsetX, y + dir.offsetY, z + dir.offsetZ);
            if (pushesOutputs(tile)) {
                return tile;
            }
        }
        return null;
    }
}
