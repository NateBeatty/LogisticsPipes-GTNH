package logisticspipes.utils;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraftforge.common.util.ForgeDirection;

import cpw.mods.fml.common.Loader;
import logisticspipes.LogisticsPipes;
import logisticspipes.pipes.basic.CoreRoutedPipe;
import logisticspipes.proxy.gregtech.GregTechLabelInfo;
import logisticspipes.request.CrafterLocator;

/**
 * Fills in a label for the Label Upgrade's Auto button from the machine the chassis faces: the block's name for any
 * machine, plus tier, circuit and machine type for GregTech ones, read from the multiblock when the chassis faces one
 * of its buses or hatches. A one-time suggestion the player can edit; nothing stays linked to the machine.
 */
public final class PipeLabelSuggester {

    private static final boolean GREGTECH = Loader.isModLoaded("gregtech");

    private PipeLabelSuggester() {}

    /** A label for the machine the pipe faces, or null if it faces nothing that has a name. */
    public static PipeLabel suggest(CoreRoutedPipe pipe) {
        ForgeDirection dir = CrafterLocator.machineSide(pipe);
        if (dir == ForgeDirection.UNKNOWN) {
            return null;
        }
        World world = pipe.getWorld();
        int x = pipe.getX() + dir.offsetX;
        int y = pipe.getY() + dir.offsetY;
        int z = pipe.getZ() + dir.offsetZ;
        TileEntity tile = world.getTileEntity(x, y, z);

        String name = null;
        List<String> tags = new ArrayList<>();
        ItemStack icon = null;
        if (GREGTECH && tile != null) {
            try {
                GregTechLabelInfo.Result gt = GregTechLabelInfo.describe(tile);
                if (gt != null) {
                    name = gt.name;
                    tags.addAll(gt.tags);
                    icon = gt.icon;
                }
            } catch (RuntimeException | LinkageError e) {
                // A GregTech version without one of the members used; the generic name below still works.
                LogisticsPipes.log.warn("Could not read GregTech machine info for a label", e);
                tags.clear();
            }
        }
        if (name == null) {
            ItemStack stack = CrafterLocator.machineStack(world, x, y, z, dir);
            if (stack != null) {
                name = stack.getDisplayName();
            }
        }
        if (name == null && tags.isEmpty()) {
            return null;
        }
        return PipeLabel.of(name, String.join(",", tags)).withIcon(icon);
    }
}
