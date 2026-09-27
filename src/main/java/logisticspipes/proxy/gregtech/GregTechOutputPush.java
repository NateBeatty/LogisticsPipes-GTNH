package logisticspipes.proxy.gregtech;

import net.minecraft.tileentity.TileEntity;

import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.implementations.MTEHatchOutput;
import gregtech.api.metatileentity.implementations.MTEHatchOutputBus;
import gregtech.api.metatileentity.implementations.MTEHatchVoid;
import gregtech.common.tileentities.machines.outputme.MTEHatchOutputME;

/**
 * Tells whether a GregTech output bus or hatch empties itself into the block in front of it.
 * <p>
 * GregTech does that every few ticks while the hatch is switched on ("Machine Processing: Enabled", toggled with a soft
 * mallet), using the same checks as here: {@code MTEHatchOutputBus#onPostTick} and {@code MTEHatchOutput#onPostTick}.
 * A result pushed out that way never passes the crafter, so its order is never filled and its set never counted.
 * <p>
 * Kept in its own class so nothing here is loaded unless GregTech is present; call it through
 * {@link logisticspipes.utils.OutputPushUtil} rather than directly.
 */
public final class GregTechOutputPush {

    private GregTechOutputPush() {}

    public static boolean pushesOutputs(TileEntity tile) {
        if (!(tile instanceof IGregTechTileEntity)) {
            return false;
        }
        IGregTechTileEntity gt = (IGregTechTileEntity) tile;
        if (!gt.isAllowedToWork()) {
            return false;
        }
        IMetaTileEntity mte = gt.getMetaTileEntity();
        if (mte instanceof MTEHatchOutputBus) {
            // ME, void and steam buses answer false: they never push into the block in front.
            return ((MTEHatchOutputBus) mte).pushOutputInventory()
                    && gt.getTileEntityAtSide(gt.getFrontFacing()) != null;
        }
        if (mte instanceof MTEHatchOutput) {
            if (mte instanceof MTEHatchOutputME || mte instanceof MTEHatchVoid) {
                return false;
            }
            return gt.getITankContainerAtSide(gt.getFrontFacing()) != null;
        }
        return false;
    }
}
