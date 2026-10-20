package logisticspipes.proxy.gregtech;

import net.minecraft.tileentity.TileEntity;
import net.minecraftforge.fluids.FluidStack;

import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.implementations.MTEBasicMachine;
import logisticspipes.LogisticsPipes;

/**
 * Empties the input tank of a GregTech single-block machine. Its fluid handler only ever drains the output tank, so a
 * leftover fluid ingredient can't be taken back out the normal way, the same as GT input slots can't be pulled from.
 * <p>
 * Kept in its own class so nothing here is loaded unless GregTech is present.
 */
public final class GregTechInputTank {

    private GregTechInputTank() {}

    /**
     * Takes up to {@code wanted.amount} of that fluid out of the machine's input tank.
     *
     * @return what was taken, or null when the tile isn't a single-block machine or its input tank holds none of it
     */
    public static FluidStack drainInput(TileEntity tile, FluidStack wanted) {
        try {
            return drain(tile, wanted);
        } catch (RuntimeException | LinkageError e) {
            LogisticsPipes.log.warn("Could not drain the input tank of {}", tile, e);
            return null;
        }
    }

    private static FluidStack drain(TileEntity tile, FluidStack wanted) {
        if (!(tile instanceof IGregTechTileEntity) || wanted == null || wanted.amount <= 0) {
            return null;
        }
        IGregTechTileEntity gt = (IGregTechTileEntity) tile;
        IMetaTileEntity mte = gt.getMetaTileEntity();
        if (!(mte instanceof MTEBasicMachine)) {
            return null;
        }
        MTEBasicMachine machine = (MTEBasicMachine) mte;
        FluidStack held = machine.getFillableStack();
        if (held == null || held.amount <= 0 || !held.isFluidEqual(wanted)) {
            return null;
        }
        int taken = Math.min(held.amount, wanted.amount);
        FluidStack drained = held.copy();
        drained.amount = taken;
        held.amount -= taken;
        if (held.amount <= 0) {
            machine.setFillableStack(null);
        }
        gt.markDirty();
        return drained;
    }
}
