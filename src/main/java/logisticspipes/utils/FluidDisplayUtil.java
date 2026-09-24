package logisticspipes.utils;

import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;

import cpw.mods.fml.common.Loader;
import logisticspipes.items.LogisticsFluidContainer;
import logisticspipes.proxy.gregtech.GregTechFluidDisplay;

/**
 * Tells a stack that <i>stands for</i> a fluid from one that merely holds some.
 * <p>
 * The difference matters wherever a player drops a stack into a recipe slot: a cell or a bucket is a real item and a
 * recipe may well want it as one, while a display stack can't exist in an inventory at all and only ever means the
 * fluid inside it.
 */
public final class FluidDisplayUtil {

    private static final boolean GREGTECH = Loader.isModLoaded("gregtech");

    private FluidDisplayUtil() {}

    /**
     * @return the fluid this stack stands for, or null if it is a real item (including a filled container, which the
     *         recipe may want as an item)
     */
    public static FluidStack getDisplayedFluid(ItemStack stack) {
        if (stack == null || stack.getItem() == null) {
            return null;
        }
        if (stack.getItem() instanceof LogisticsFluidContainer) {
            return stack.hasTagCompound() ? FluidStack.loadFluidStackFromNBT(stack.getTagCompound()) : null;
        }
        return GREGTECH ? GregTechFluidDisplay.getFluid(stack) : null;
    }
}
