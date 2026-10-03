package logisticspipes.proxy.gregtech;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.StatCollector;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;

import gregtech.api.enums.GTValues;
import gregtech.api.enums.ItemList;
import gregtech.api.interfaces.IConfigurationCircuitSupport;
import gregtech.api.interfaces.ISecondaryDescribable;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.implementations.MTEHatch;
import gregtech.api.metatileentity.implementations.MTEMultiBlockBase;
import gregtech.api.metatileentity.implementations.MTETieredMachineBlock;
import logisticspipes.LogisticsPipes;

/**
 * What the Label Upgrade's Auto button can read from a GregTech machine: its name, voltage tier, programmed circuit and
 * the "Machine Type" line GTNH puts in multiblock tooltips. A bus or hatch is traced back to the multiblock it belongs
 * to, so a chassis on an input bus is labelled as the multiblock.
 * <p>
 * Kept in its own class so nothing here is loaded unless GregTech is present; call it through
 * {@link logisticspipes.utils.PipeLabelSuggester} rather than directly.
 */
public final class GregTechLabelInfo {

    private static final String MACHINE_TYPE = "Machine Type:";
    /** GregTech's translation key for {@link #MACHINE_TYPE}, which its tooltip builder uses. */
    private static final String MACHINE_TYPE_KEY = "GT5U.MBTT.MachineType";
    private static final Pattern FORMATTING_CODE = Pattern.compile("(?i)\\u00a7[0-9a-fk-or]");
    /** Chunks searched around a hatch for its controller, in each direction: a 3x3 chunk area. */
    private static final int CONTROLLER_CHUNK_RADIUS = 1;

    private GregTechLabelInfo() {}

    /** What Auto found; {@code icon} is only set for a multiblock, whose controller isn't the block the pipe faces. */
    public static final class Result {

        public String name;
        public final List<String> tags = new ArrayList<>();
        public ItemStack icon;
    }

    /** What can be read from the tile, or null if it isn't a GregTech machine. */
    public static Result describe(TileEntity tile) {
        IMetaTileEntity mte = metaTileEntity(tile);
        if (mte == null) {
            return null;
        }
        IGregTechTileEntity gt = (IGregTechTileEntity) tile;
        Result result = new Result();
        MTEMultiBlockBase controller = mte instanceof MTEHatch ? findController(tile, mte) : null;
        if (controller != null) {
            result.name = localName(controller);
            // The circuit sits in the bus; the bus's own tier says nothing about the multiblock.
            addCircuit(gt, result.tags);
            tryAddMachineTypes(controller, result.tags);
            result.icon = controller.getStackForm(1);
        } else {
            result.name = localName(mte);
            if (mte instanceof MTETieredMachineBlock) {
                int tier = ((MTETieredMachineBlock) mte).mTier;
                if (tier >= 0 && tier < GTValues.VN.length) {
                    result.tags.add(GTValues.VN[tier]);
                }
            }
            addCircuit(gt, result.tags);
            tryAddMachineTypes(mte, result.tags);
        }
        return result;
    }

    /** The tooltip is the least predictable part; if it can't be read, the name, tier and circuit still count. */
    private static void tryAddMachineTypes(IMetaTileEntity mte, List<String> tags) {
        try {
            addMachineTypes(mte, tags);
        } catch (RuntimeException | LinkageError e) {
            LogisticsPipes.log.warn("Could not read the machine type of {} for a label", mte.getClass().getName(), e);
        }
    }

    private static String localName(IMetaTileEntity mte) {
        String name = mte.getLocalName();
        return name == null || name.isEmpty() ? null : stripFormatting(name);
    }

    /**
     * EnumChatFormatting.getTextWithoutFormattingCodes is client-only and missing on a dedicated server, so the codes
     * are removed here.
     */
    private static String stripFormatting(String text) {
        return FORMATTING_CODE.matcher(text).replaceAll("");
    }

    /**
     * Tags the programmed circuit, {@code circuitN}. A machine or bus with a circuit slot but no circuit in it is tagged
     * {@code circuit0}, the same as a circuit set to 0; machines without a circuit slot get no circuit tag when empty.
     */
    private static void addCircuit(IGregTechTileEntity gt, List<String> tags) {
        for (int slot = 0; slot < gt.getSizeInventory(); slot++) {
            ItemStack stack = gt.getStackInSlot(slot);
            if (stack != null && stack.getItem() != null && stack.getItem() == ItemList.Circuit_Integrated.getItem()) {
                tags.add("circuit" + stack.getItemDamage());
                return;
            }
        }
        IMetaTileEntity mte = gt.getMetaTileEntity();
        if (mte instanceof IConfigurationCircuitSupport && ((IConfigurationCircuitSupport) mte).allowSelectCircuit()) {
            tags.add("circuit0");
        }
    }

    private static void addMachineTypes(IMetaTileEntity mte, List<String> tags) {
        // A multiblock's getDescription() checks the Shift key, which a dedicated server can't do; its primary
        // description is the same tooltip without that check.
        String[] description = mte instanceof ISecondaryDescribable
                ? ((ISecondaryDescribable) mte).getPrimaryDescription()
                : mte.getDescription();
        if (description == null) {
            return;
        }
        String translated = StatCollector.translateToLocal(MACHINE_TYPE_KEY) + ":";
        for (String line : description) {
            String plain = line == null ? "" : stripFormatting(line).trim();
            // Normally "Machine Type:", but a server without GregTech's translations may hold the bare key instead.
            String prefix = plain.startsWith(MACHINE_TYPE) ? MACHINE_TYPE
                    : plain.startsWith(translated) ? translated : null;
            if (prefix != null) {
                // Some multiblocks list several types, "Assembler, Circuit Assembler".
                for (String type : plain.substring(prefix.length()).split("[,|/]")) {
                    if (!type.trim().isEmpty()) {
                        tags.add(type.trim());
                    }
                }
                return;
            }
        }
    }

    /**
     * The formed multiblock that lists this hatch as one of its buses or hatches. Hatches don't know their controller,
     * so this looks through the GregTech machines in the chunks around it; only done when Auto is pressed.
     */
    private static MTEMultiBlockBase findController(TileEntity hatchTile, IMetaTileEntity hatch) {
        World world = hatchTile.getWorldObj();
        int chunkX = hatchTile.xCoord >> 4;
        int chunkZ = hatchTile.zCoord >> 4;
        for (int dx = -CONTROLLER_CHUNK_RADIUS; dx <= CONTROLLER_CHUNK_RADIUS; dx++) {
            for (int dz = -CONTROLLER_CHUNK_RADIUS; dz <= CONTROLLER_CHUNK_RADIUS; dz++) {
                if (!world.getChunkProvider().chunkExists(chunkX + dx, chunkZ + dz)) {
                    continue; // never load chunks for this
                }
                Chunk chunk = world.getChunkFromChunkCoords(chunkX + dx, chunkZ + dz);
                for (Object o : chunk.chunkTileEntityMap.values()) {
                    IMetaTileEntity mte = metaTileEntity((TileEntity) o);
                    if (mte instanceof MTEMultiBlockBase && owns((MTEMultiBlockBase) mte, hatch)) {
                        return (MTEMultiBlockBase) mte;
                    }
                }
            }
        }
        return null;
    }

    private static boolean owns(MTEMultiBlockBase controller, IMetaTileEntity hatch) {
        return contains(() -> controller.mInputBusses, hatch) || contains(() -> controller.mInputHatches, hatch)
                || contains(() -> controller.mOutputBusses, hatch)
                || contains(() -> controller.mOutputHatches, hatch)
                || contains(() -> controller.mDualInputHatches, hatch);
    }

    /** Each list is read on its own, so one a GregTech version lacks doesn't stop the others from being checked. */
    private static boolean contains(Supplier<List<?>> list, IMetaTileEntity hatch) {
        try {
            List<?> l = list.get();
            return l != null && l.contains(hatch);
        } catch (LinkageError e) {
            return false;
        }
    }

    private static IMetaTileEntity metaTileEntity(TileEntity tile) {
        return tile instanceof IGregTechTileEntity ? ((IGregTechTileEntity) tile).getMetaTileEntity() : null;
    }
}
