package logisticspipes.proxy.gregtech;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraftforge.common.util.ForgeDirection;

import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.implementations.MTEBasicMachine;
import gregtech.api.metatileentity.implementations.MTEHatchInputBus;
import logisticspipes.LogisticsPipes;

/**
 * Reads whether a GregTech machine takes more than one stack of the same item: an input bus's limiting mode
 * (screwdriver or the bus GUI) or a single-block machine's multi stack input (soldering iron on its main face).
 * <p>
 * Only machines that use GT's own insertion rule are read. A machine that overrides it - an ME bus, a compressed bus,
 * an apiary - may not follow the setting, so no answer is given rather than a wrong one.
 * <p>
 * Kept in its own class so nothing here is loaded unless GregTech is present.
 */
public final class GregTechMultiStack {

    /** Per machine class: whether its allowPutStack is the GT base one the setting belongs to. */
    private static final Map<Class<?>, Boolean> USES_BASE_RULE = new HashMap<>();

    private GregTechMultiStack() {}

    /**
     * @return true when the machine takes several stacks of one item, false when it takes only one, or null when the
     *         tile isn't a GT machine whose setting can be trusted
     */
    public static Boolean takesMultipleStacks(TileEntity tile) {
        try {
            return read(tile);
        } catch (RuntimeException | LinkageError e) {
            LogisticsPipes.log.warn("Could not read the stack limit setting of {}", tile, e);
            return null;
        }
    }

    private static Boolean read(TileEntity tile) {
        if (!(tile instanceof IGregTechTileEntity)) {
            return null;
        }
        IMetaTileEntity mte = ((IGregTechTileEntity) tile).getMetaTileEntity();
        if (mte instanceof MTEBasicMachine && usesBaseRule(mte.getClass(), MTEBasicMachine.class)) {
            return !((MTEBasicMachine) mte).mDisableMultiStack;
        }
        if (mte instanceof MTEHatchInputBus && usesBaseRule(mte.getClass(), MTEHatchInputBus.class)) {
            return ((MTEHatchInputBus) mte).disableLimited;
        }
        return null;
    }

    private static boolean usesBaseRule(Class<?> type, Class<?> base) {
        Boolean known = USES_BASE_RULE.get(type);
        if (known == null) {
            try {
                known = type.getMethod(
                        "allowPutStack",
                        IGregTechTileEntity.class,
                        int.class,
                        ForgeDirection.class,
                        ItemStack.class).getDeclaringClass() == base;
            } catch (NoSuchMethodException e) {
                known = false;
            }
            USES_BASE_RULE.put(type, known);
        }
        return known;
    }
}
