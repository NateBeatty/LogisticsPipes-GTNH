package logisticspipes.proxy.gregtech;

import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;

import gregtech.api.util.GTUtility;

/**
 * Reads GregTech's fluid display item, the stack NEI shows in place of a fluid in a recipe. It carries both the fluid
 * and the recipe's amount.
 * <p>
 * Kept in its own class so nothing here is loaded unless GregTech is present; call it through
 * {@link logisticspipes.utils.FluidDisplayUtil} rather than directly.
 */
public final class GregTechFluidDisplay {

    private GregTechFluidDisplay() {}

    public static FluidStack getFluid(ItemStack stack) {
        return GTUtility.getFluidFromDisplayStack(stack);
    }
}
