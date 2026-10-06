package logisticspipes.nei;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;

import codechicken.nei.PositionedStack;
import codechicken.nei.api.IOverlayHandler;
import codechicken.nei.recipe.IRecipeHandler;
import codechicken.nei.recipe.StackInfo;
import cpw.mods.fml.common.Loader;
import logisticspipes.gui.modules.ModuleBaseGui;
import logisticspipes.gui.popup.GuiRecipeImport;
import logisticspipes.modules.ModuleCrafter;
import logisticspipes.modules.ModuleSmartCrafter;
import logisticspipes.network.PacketHandler;
import logisticspipes.network.packets.NEISetAdvancedCraftingRecipe;
import logisticspipes.proxy.gregtech.GregTechNEIStacks;
import logisticspipes.utils.gui.SubGuiScreen;
import logisticspipes.utils.item.ItemIdentifier;

public class CraftingPipeOverlayHandler implements IOverlayHandler {

    @Override
    public void overlayRecipe(GuiContainer firstGui, IRecipeHandler recipe, int recipeIndex, boolean maxTransfer) {

        // Both the crafting pipe's gui and the Smart Crafter's take the same import packet.
        if (!(firstGui instanceof ModuleBaseGui)
                || !(((ModuleBaseGui) firstGui).getModule() instanceof ModuleCrafter)) {
            return;
        }

        ModuleBaseGui gui = (ModuleBaseGui) firstGui;
        ModuleCrafter module = (ModuleCrafter) gui.getModule();
        // The Smart Crafter leaves out what the recipe doesn't consume and keeps each output's chance; the crafting
        // pipe imports exactly as it always has.
        boolean smart = module instanceof ModuleSmartCrafter;

        List<List<ItemStack>> inputOptions = new ArrayList<>();
        List<ItemStack> outputs = new ArrayList<>();
        List<Integer> outputChances = new ArrayList<>();
        List<ItemStack> nonConsumed = new ArrayList<>();
        List<FluidStack> fluidInputs = new ArrayList<>();

        for (PositionedStack ps : recipe.getIngredientStacks(recipeIndex)) {
            if (smart && ps.items != null && ps.items.length > 0 && isNotConsumed(ps)) {
                // A mold, lens, shape or programmed circuit stays in the machine; the player puts it there once.
                if (StackInfo.getFluid(ps.items[0]) == null) {
                    nonConsumed.add(ps.items[0].copy());
                }
                continue;
            }
            if (ps.items != null && ps.items.length > 0) {
                FluidStack fluid = StackInfo.getFluid(ps.items[0]);

                if (fluid != null) {
                    fluidInputs.add(fluid);
                } else {
                    List<ItemStack> options = new ArrayList<>(Arrays.asList(ps.items));
                    inputOptions.add(options);
                }

            } else {
                inputOptions.add(null);
            }
        }

        PositionedStack result = recipe.getResultStack(recipeIndex);
        if (result != null && result.items != null && result.items.length > 0) {
            outputs.add(result.items[0]);
            outputChances.add(chancePercent(result));
        }

        for (PositionedStack ps : recipe.getOtherStacks(recipeIndex)) {
            if (ps.items != null && ps.items.length > 0) {
                outputs.add(ps.items[0]);
                outputChances.add(chancePercent(ps));
            }
        }

        final List<FluidStack> finalFluidInputs = collapseFluids(fluidInputs);
        final List<ItemStack> finalOutputs;
        if (smart) {
            // 6 Wood Pulp plus 1 Wood Pulp at 80% stay two outputs; only same item at the same chance is added up.
            List<Integer> finalChances = new ArrayList<>();
            finalOutputs = collapseStacksByChance(outputs, outputChances, finalChances);
            outputChances = finalChances;
        } else {
            finalOutputs = collapseStacks(outputs);
        }

        List<List<ItemStack>> collapsedInputOptions = collapseInputOptions(inputOptions);

        // Special check if we don't have any substitutions
        boolean allSingle = true;
        for (List<ItemStack> options : collapsedInputOptions) {
            if (options != null && options.size() > 1) {
                allSingle = false;
                break;
            }
        }

        if (allSingle) {
            NEISetAdvancedCraftingRecipe packet = PacketHandler.getPacket(NEISetAdvancedCraftingRecipe.class);
            packet.setModulePos(module);
            List<ItemStack> inputs = new ArrayList<>();
            for (List<ItemStack> options : collapsedInputOptions) {
                if (options != null && !options.isEmpty()) {
                    inputs.add(options.get(0));
                }
            }
            packet.setInputs(inputs).setOutputs(finalOutputs).setFluidInputs(finalFluidInputs);
            if (smart) {
                packet.setOutputChances(outputChances).setNonConsumed(nonConsumed);
            }
            logisticspipes.proxy.MainProxy.sendPacketToServer(packet);
            return;
        }

        ItemStack[][] stacks = new ItemStack[9][];
        int i = 0;
        for (List<ItemStack> options : collapsedInputOptions) {
            if (options != null) {
                stacks[i] = options.toArray(new ItemStack[0]);
            }
            i++;
            if (i >= 9) break;
        }

        NEISetAdvancedCraftingRecipe packet = PacketHandler.getPacket(NEISetAdvancedCraftingRecipe.class);
        packet.setModulePos(module);
        if (smart) {
            // The ore dictionary picker only fills in the inputs, so these ride along on the packet it sends.
            packet.setOutputChances(outputChances).setNonConsumed(nonConsumed);
        }

        GuiRecipeImport subGui = new GuiRecipeImport(null, stacks, packet, finalOutputs, finalFluidInputs);
        if (!gui.hasSubGui()) {
            gui.setSubGui(subGui);
        } else {
            SubGuiScreen next = gui.getSubGui();
            while (next.hasSubGui()) {
                next = next.getSubGui();
            }
            next.setSubGui(subGui);
        }
    }

    private List<FluidStack> collapseFluids(List<FluidStack> fluids) {
        List<FluidStack> result = new ArrayList<>();
        for (FluidStack fluid : fluids) {
            if (fluid == null || fluid.getFluid() == null) continue;
            boolean found = false;
            for (FluidStack existing : result) {
                if (existing.isFluidEqual(fluid)) {
                    existing.amount += fluid.amount;
                    found = true;
                    break;
                }
            }
            if (!found) {
                result.add(fluid.copy());
            }
        }
        return result;
    }

    private List<List<ItemStack>> collapseInputOptions(List<List<ItemStack>> inputOptions) {
        List<List<ItemStack>> result = new ArrayList<>();
        for (List<ItemStack> options : inputOptions) {
            if (options == null || options.isEmpty()) continue;
            boolean found = false;
            for (List<ItemStack> existingOptions : result) {
                if (optionsAreEqual(options, existingOptions)) {
                    for (int i = 0; i < existingOptions.size(); i++) {
                        existingOptions.get(i).stackSize += options.get(i).stackSize;
                    }
                    found = true;
                    break;
                }
            }
            if (!found) {
                List<ItemStack> copy = new ArrayList<>();
                for (ItemStack stack : options) {
                    copy.add(stack.copy());
                }
                result.add(copy);
            }
        }
        return result;
    }

    private boolean optionsAreEqual(List<ItemStack> options1, List<ItemStack> options2) {
        if (options1.size() != options2.size()) return false;
        for (int i = 0; i < options1.size(); i++) {
            ItemStack stack1 = options1.get(i);
            ItemStack stack2 = options2.get(i);
            if (!ItemIdentifier.get(stack1).equals(ItemIdentifier.get(stack2))) return false;
        }
        return true;
    }

    /**
     * Whether NEI marks this ingredient "NC". GregTech's own stack says so directly; anything else is read from the
     * convention GT's handler follows, a stack size of 0.
     */
    private static boolean isNotConsumed(PositionedStack ps) {
        if (GREGTECH) {
            Boolean gt = GregTechNEIStacks.isNotConsumed(ps);
            if (gt != null) {
                return gt;
            }
        }
        return ps.items[0].stackSize == 0;
    }

    private static final boolean GREGTECH = Loader.isModLoaded("gregtech");

    /**
     * NEI's chance (1 = 0.01%) as the whole percent the Smart Crafter keeps. Only a real 100% counts as guaranteed, so
     * 99.5% stays a chanced 99 instead of rounding up to one.
     */
    private static int chancePercent(PositionedStack ps) {
        int chance = ps.getChance();
        if (chance <= 0 || chance >= PositionedStack.CHANCE_FULL) {
            return 100;
        }
        return Math.max(1, Math.min(99, Math.round(chance / 100f)));
    }

    /** Like {@link #collapseStacks}, but only stacks of the same item <i>and</i> the same chance are added up. */
    private List<ItemStack> collapseStacksByChance(List<ItemStack> stacks, List<Integer> chances,
            List<Integer> chancesOut) {
        List<ItemIdentifier> ids = new ArrayList<>();
        List<Integer> amounts = new ArrayList<>();
        for (int i = 0; i < stacks.size(); i++) {
            ItemStack stack = stacks.get(i);
            if (stack == null) continue;
            ItemIdentifier id = ItemIdentifier.get(stack);
            int chance = chances.get(i);
            boolean found = false;
            for (int j = 0; j < ids.size(); j++) {
                if (ids.get(j).equals(id) && chancesOut.get(j) == chance) {
                    amounts.set(j, amounts.get(j) + stack.stackSize);
                    found = true;
                    break;
                }
            }
            if (!found) {
                ids.add(id);
                amounts.add(stack.stackSize);
                chancesOut.add(chance);
            }
        }
        List<ItemStack> result = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            result.add(ids.get(i).makeNormalStack(amounts.get(i)));
        }
        return result;
    }

    private List<ItemStack> collapseStacks(List<ItemStack> stacks) {
        Map<ItemIdentifier, Integer> collapsed = new LinkedHashMap<>();
        for (ItemStack stack : stacks) {
            if (stack == null) continue;
            ItemIdentifier id = ItemIdentifier.get(stack);
            collapsed.put(id, collapsed.getOrDefault(id, 0) + stack.stackSize);
        }
        List<ItemStack> result = new ArrayList<>();
        for (Map.Entry<ItemIdentifier, Integer> entry : collapsed.entrySet()) {
            result.add(entry.getKey().makeNormalStack(entry.getValue()));
        }
        return result;
    }
}
