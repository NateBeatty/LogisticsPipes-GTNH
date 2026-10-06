package logisticspipes.proxy.gregtech;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;

import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.implementations.MTEBasicMachine;
import gregtech.api.metatileentity.implementations.MTEHatchInputBus;
import logisticspipes.LogisticsPipes;
import logisticspipes.utils.item.ItemIdentifier;

/**
 * Looks for a recipe's non-consumed items (molds, lenses, shapes, programmed circuits) in the GregTech machine a Smart
 * Crafter faces.
 * <p>
 * Only machines whose slots give a sure answer are checked: a single-block machine, where the item has to be in an
 * input slot or the circuit slot, and a plain input bus, where a multiblock reads it from that same bus. An ME bus, a
 * crafting input buffer or anything else may keep it somewhere this can't see, so for those no answer is given rather
 * than a wrong one.
 * <p>
 * Kept in its own class so nothing here is loaded unless GregTech is present.
 */
public final class GregTechNonConsumed {

    private GregTechNonConsumed() {}

    /**
     * @return the first of {@code items} the machine doesn't hold, or null when it holds them all or can't be checked
     */
    public static ItemIdentifier missingFrom(TileEntity tile, List<ItemIdentifier> items) {
        try {
            return find(tile, items);
        } catch (RuntimeException | LinkageError e) {
            LogisticsPipes.log.warn("Could not look for non-consumed items in {}", tile, e);
            return null;
        }
    }

    private static ItemIdentifier find(TileEntity tile, List<ItemIdentifier> items) {
        if (!(tile instanceof IGregTechTileEntity)) {
            return null;
        }
        IGregTechTileEntity gt = (IGregTechTileEntity) tile;
        IMetaTileEntity mte = gt.getMetaTileEntity();
        List<ItemIdentifier> missing = new ArrayList<>(items);
        if (mte instanceof MTEBasicMachine) {
            MTEBasicMachine machine = (MTEBasicMachine) mte;
            int from = machine.getInputSlot();
            removeHeld(gt, from, from + machine.mInputSlotCount, missing);
            if (machine.allowSelectCircuit()) {
                int circuit = machine.getCircuitSlot();
                removeHeld(gt, circuit, circuit + 1, missing);
            }
        } else if (mte != null && mte.getClass() == MTEHatchInputBus.class) {
            // Exactly the plain bus: its subclasses include the ME bus, whose slots mirror the network.
            removeHeld(gt, 0, gt.getSizeInventory(), missing);
        } else {
            return null;
        }
        return missing.isEmpty() ? null : missing.get(0);
    }

    private static void removeHeld(IGregTechTileEntity gt, int from, int to, List<ItemIdentifier> missing) {
        for (int slot = Math.max(0, from); slot < Math.min(to, gt.getSizeInventory()) && !missing.isEmpty(); slot++) {
            ItemStack stack = gt.getStackInSlot(slot);
            if (stack != null && stack.getItem() != null) {
                missing.remove(ItemIdentifier.get(stack));
            }
        }
    }
}
