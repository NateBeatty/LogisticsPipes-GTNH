package logisticspipes.recipes;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.oredict.OreDictionary;

import logisticspipes.LogisticsPipes;
import logisticspipes.config.Configs;
import logisticspipes.interfaces.ICraftingResultHandler;
import logisticspipes.items.ItemModule;
import logisticspipes.items.ItemPipeComponents;
import logisticspipes.items.LogisticsItemCard;
import logisticspipes.proxy.interfaces.ICraftingParts;

// @formatter:off
// CHECKSTYLE:OFF

public class SolderingStationRecipes {

    public static class SolderingStationRecipe {
        public final ItemStack[] source;
        public final ItemStack result;
        public final ICraftingResultHandler handler;

        public SolderingStationRecipe(ItemStack[] stacks, ItemStack result, ICraftingResultHandler handler) {
            source = stacks;
            this.result = result;
            this.handler = handler;
        }
    }

    private static final ArrayList<SolderingStationRecipe> recipes = new ArrayList<>();

    /**
     * GTNH makes everything else with GregTech recipes (NewHorizonsCoreMod), but the Frequency Card has to come from the
     * station: only its result handler can stamp the random UUID that pairs the two cards of one craft.
     */
    private static void loadGTNHRecipes() {
        List<ItemStack> pearlPlates = OreDictionary.getOres("plateEnderPearl");
        if (pearlPlates.isEmpty()) {
            return;
        }
        ItemStack pearlPlate = singleExact(pearlPlates.get(0));
        ItemStack blankModule = new ItemStack(LogisticsPipes.ModuleItem, 1, ItemModule.BLANK);
        // The station matches inputs by exact item and damage, so each HV circuit variant needs its own recipe.
        for (ItemStack circuitStack : OreDictionary.getOres("circuitAdvanced")) {
            ItemStack circuit = singleExact(circuitStack);
            SolderingStationRecipes.recipes.add(new SolderingStationRecipe(
                    new ItemStack[] {
                        circuit,
                        null,
                        circuit,
                        blankModule,
                        pearlPlate,
                        blankModule,
                        null,
                        null,
                        null
                    },
                    new ItemStack(LogisticsPipes.LogisticsItemCard, 2, LogisticsItemCard.FREQ_CARD),
                    stack -> {
                        stack.stackTagCompound = new NBTTagCompound();
                        stack.stackTagCompound.setString(
                                "UUID", UUID.randomUUID().toString());
                    }));
        }
    }

    private static ItemStack singleExact(ItemStack oreStack) {
        ItemStack stack = oreStack.copy();
        stack.stackSize = 1;
        if (stack.getItemDamage() == OreDictionary.WILDCARD_VALUE) {
            stack.setItemDamage(0);
        }
        return stack;
    }

    public static void loadRecipe(ICraftingParts parts) {
        if (LogisticsPipes.isGTNH) {
            loadGTNHRecipes();
        }
        if (!Configs.ENABLE_BETA_RECIPES && !LogisticsPipes.isGTNH) {
			SolderingStationRecipes.recipes.add(new SolderingStationRecipe(new ItemStack[] {
					parts.getChipTear1(),
					null,
					parts.getChipTear1(),
					new ItemStack(LogisticsPipes.ModuleItem,1,0),
					new ItemStack(Items.ender_pearl,1),
					new ItemStack(LogisticsPipes.ModuleItem,1,0),
					null,
					null,
					null },
					new ItemStack(LogisticsPipes.LogisticsItemCard,2,0), new ICraftingResultHandler() {
				@Override
				public void handleCrafting(ItemStack stack) {
					stack.stackTagCompound = new NBTTagCompound();
					stack.stackTagCompound.setString("UUID", UUID.randomUUID().toString());
				}
			}));

			SolderingStationRecipes.recipes.add(new SolderingStationRecipe(new ItemStack[] {
					new ItemStack(LogisticsPipes.LogisticsParts,1,0),
					null,
					new ItemStack(LogisticsPipes.LogisticsParts,1,0),
					new ItemStack(LogisticsPipes.LogisticsParts,1,1),
					new ItemStack(LogisticsPipes.LogisticsParts,1,2),
					new ItemStack(LogisticsPipes.LogisticsParts,1,1),
					null,
					null,
					null },
					new ItemStack(LogisticsPipes.LogisticsHUDArmor,1,0), null));

			SolderingStationRecipes.recipes.add(new SolderingStationRecipe(new ItemStack[]{
					new ItemStack(Items.redstone,1,0),
					parts.getChipTear3(),
					new ItemStack(Items.redstone,1,0),
					new ItemStack(Items.redstone,1,0),
					new ItemStack(Blocks.glass,1,0),
					new ItemStack(Items.redstone,1,0),
					new ItemStack(Items.redstone,1,0),
					new ItemStack(Items.redstone,1,0),
					new ItemStack(Items.redstone,1,0) },
					new ItemStack(LogisticsPipes.LogisticsParts,1,1), null));

			SolderingStationRecipes.recipes.add(new SolderingStationRecipe(new ItemStack[]{
					null,
					null,
					null,
					new ItemStack(Items.diamond,1,0),
					new ItemStack(Items.diamond,1,0),
					new ItemStack(Items.diamond,1,0),
					null,
					null,
					parts.getChipTear3() },
					new ItemStack(LogisticsPipes.LogisticsParts,1,0), null));

			SolderingStationRecipes.recipes.add(new SolderingStationRecipe(new ItemStack[]{
					null,
					parts.getChipTear3(),
					null,
					new ItemStack(Items.diamond,1,0),
					null,
					new ItemStack(Items.diamond,1,0),
					null,
					null,
					null },
					new ItemStack(LogisticsPipes.LogisticsParts,1,2), null));

			SolderingStationRecipes.recipes.add(new SolderingStationRecipe(new ItemStack[]{
					new ItemStack(Blocks.hopper,1),
					new ItemStack(Blocks.hopper,1),
					new ItemStack(Blocks.hopper,1),
					new ItemStack(Blocks.hopper,1),
					null,
					new ItemStack(Blocks.hopper,1),
					new ItemStack(Blocks.hopper,1),
					new ItemStack(Blocks.hopper,1),
					new ItemStack(Blocks.hopper,1) },
					new ItemStack(LogisticsPipes.LogisticsParts,1,3), null));

			SolderingStationRecipes.recipes.add(new SolderingStationRecipe(new ItemStack[]{
					null,
					new ItemStack(LogisticsPipes.LogisticsParts,1,3),
					null,
					null,
					parts.getChipTear3(),
					null,
					null,
					new ItemStack(LogisticsPipes.LogisticsCraftingPipeMk2,1),null },
					new ItemStack(LogisticsPipes.LogisticsCraftingPipeMk3,1), null));

			SolderingStationRecipes.recipes.add(new SolderingStationRecipe(new ItemStack[]{
					new ItemStack(Blocks.nether_brick,1),
					parts.getChipTear3(),
					new ItemStack(Blocks.nether_brick,1),
					null,
					new ItemStack(LogisticsPipes.LogisticsBasicPipe,1),
					null,
					new ItemStack(Blocks.nether_brick,1),
					null,
					new ItemStack(Blocks.nether_brick,1) },
					new ItemStack(LogisticsPipes.LogisticsFirewallPipe,1), null));

			SolderingStationRecipes.recipes.add(new SolderingStationRecipe(new ItemStack[]{
					new ItemStack(Blocks.stonebrick,1),
					new ItemStack(Blocks.crafting_table,1),
					new ItemStack(Blocks.stonebrick,1),
					new ItemStack(LogisticsPipes.LogisticsCraftingPipeMk2,1),
					new ItemStack(Blocks.hopper,1),
					new ItemStack(LogisticsPipes.LogisticsRequestPipeMk2,1),
					parts.getChipTear3(),
					new ItemStack(Blocks.chest,3),
					parts.getChipTear3()},
					new ItemStack(LogisticsPipes.logisticsRequestTable,1), null));
        }
        if (Configs.ENABLE_BETA_RECIPES) {
            ItemStack logproc =
                    new ItemStack(LogisticsPipes.LogisticsPipeComponents, 1, ItemPipeComponents.ITEM_ROUTEPROCESSOR);
            ItemStack expand =
                    new ItemStack(LogisticsPipes.LogisticsPipeComponents, 1, ItemPipeComponents.ITEM_LOGICEXPANDER);

            SolderingStationRecipes.recipes.add(new SolderingStationRecipe(
                    new ItemStack[] {
                        expand,
                        null,
                        expand,
                        new ItemStack(LogisticsPipes.ModuleItem, 1, 0),
                        new ItemStack(Items.ender_pearl, 1),
                        new ItemStack(LogisticsPipes.ModuleItem, 1, 0),
                        null,
                        null,
                        null
                    },
                    new ItemStack(LogisticsPipes.LogisticsItemCard, 2, 0),
                    stack -> {
                        stack.stackTagCompound = new NBTTagCompound();
                        stack.stackTagCompound.setString(
                                "UUID", UUID.randomUUID().toString());
                    }));

            SolderingStationRecipes.recipes.add(new SolderingStationRecipe(
                    new ItemStack[] {
                        new ItemStack(LogisticsPipes.LogisticsParts, 1, 0),
                        null,
                        new ItemStack(LogisticsPipes.LogisticsParts, 1, 0),
                        new ItemStack(LogisticsPipes.LogisticsParts, 1, 1),
                        new ItemStack(LogisticsPipes.LogisticsParts, 1, 2),
                        new ItemStack(LogisticsPipes.LogisticsParts, 1, 1),
                        null,
                        null,
                        null
                    },
                    new ItemStack(LogisticsPipes.LogisticsHUDArmor, 1, 0),
                    null));

            SolderingStationRecipes.recipes.add(new SolderingStationRecipe(
                    new ItemStack[] {
                        new ItemStack(Items.redstone, 1, 0),
                        expand,
                        new ItemStack(Items.redstone, 1, 0),
                        new ItemStack(Items.redstone, 1, 0),
                        new ItemStack(Blocks.glass, 1, 0),
                        new ItemStack(Items.redstone, 1, 0),
                        new ItemStack(Items.redstone, 1, 0),
                        new ItemStack(Items.redstone, 1, 0),
                        new ItemStack(Items.redstone, 1, 0)
                    },
                    new ItemStack(LogisticsPipes.LogisticsParts, 1, 1),
                    null));

            SolderingStationRecipes.recipes.add(new SolderingStationRecipe(
                    new ItemStack[] {
                        null,
                        null,
                        null,
                        new ItemStack(Items.diamond, 1, 0),
                        new ItemStack(Items.diamond, 1, 0),
                        new ItemStack(Items.diamond, 1, 0),
                        null,
                        null,
                        logproc
                    },
                    new ItemStack(LogisticsPipes.LogisticsParts, 1, 0),
                    null));

            SolderingStationRecipes.recipes.add(new SolderingStationRecipe(
                    new ItemStack[] {
                        null,
                        logproc,
                        null,
                        new ItemStack(Items.diamond, 1, 0),
                        null,
                        new ItemStack(Items.diamond, 1, 0),
                        null,
                        null,
                        null
                    },
                    new ItemStack(LogisticsPipes.LogisticsParts, 1, 2),
                    null));

            SolderingStationRecipes.recipes.add(new SolderingStationRecipe(
                    new ItemStack[] {
                        new ItemStack(Blocks.hopper, 1),
                        new ItemStack(Blocks.hopper, 1),
                        new ItemStack(Blocks.hopper, 1),
                        new ItemStack(Blocks.hopper, 1),
                        null,
                        new ItemStack(Blocks.hopper, 1),
                        new ItemStack(Blocks.hopper, 1),
                        new ItemStack(Blocks.hopper, 1),
                        new ItemStack(Blocks.hopper, 1)
                    },
                    new ItemStack(LogisticsPipes.LogisticsParts, 1, 3),
                    null));

            SolderingStationRecipes.recipes.add(new SolderingStationRecipe(
                    new ItemStack[] {
                        null,
                        new ItemStack(LogisticsPipes.LogisticsParts, 1, 3),
                        null,
                        null,
                        logproc,
                        null,
                        null,
                        new ItemStack(LogisticsPipes.LogisticsCraftingPipeMk2, 1),
                        null
                    },
                    new ItemStack(LogisticsPipes.LogisticsCraftingPipeMk3, 1),
                    null));

            SolderingStationRecipes.recipes.add(new SolderingStationRecipe(
                    new ItemStack[] {
                        new ItemStack(Blocks.nether_brick, 1),
                        logproc,
                        new ItemStack(Blocks.nether_brick, 1),
                        null,
                        new ItemStack(LogisticsPipes.LogisticsBasicPipe, 1),
                        null,
                        new ItemStack(Blocks.nether_brick, 1),
                        null,
                        new ItemStack(Blocks.nether_brick, 1)
                    },
                    new ItemStack(LogisticsPipes.LogisticsFirewallPipe, 1),
                    null));

            SolderingStationRecipes.recipes.add(new SolderingStationRecipe(
                    new ItemStack[] {
                        new ItemStack(Blocks.stonebrick, 1),
                        new ItemStack(Blocks.crafting_table, 1),
                        new ItemStack(Blocks.stonebrick, 1),
                        new ItemStack(LogisticsPipes.LogisticsCraftingPipeMk2, 1),
                        new ItemStack(Blocks.hopper, 1),
                        new ItemStack(LogisticsPipes.LogisticsRequestPipeMk2, 1),
                        logproc,
                        new ItemStack(Blocks.chest, 3),
                        logproc
                    },
                    new ItemStack(LogisticsPipes.logisticsRequestTable, 1),
                    null));
        }
    }

    public static List<SolderingStationRecipe> getRecipes() {
        return Collections.unmodifiableList(SolderingStationRecipes.recipes);
    }
}
