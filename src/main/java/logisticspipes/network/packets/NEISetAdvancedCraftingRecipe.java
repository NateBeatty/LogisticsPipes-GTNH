package logisticspipes.network.packets;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;

import logisticspipes.modules.ModuleCrafter;
import logisticspipes.modules.ModuleSmartCrafter;
import logisticspipes.network.LPDataInputStream;
import logisticspipes.network.LPDataOutputStream;
import logisticspipes.network.abstractpackets.ModernPacket;
import logisticspipes.network.abstractpackets.ModuleCoordinatesPacket;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

@Accessors(chain = true)
public class NEISetAdvancedCraftingRecipe extends ModuleCoordinatesPacket {

    @Getter
    @Setter
    private List<ItemStack> inputs = new ArrayList<>();

    @Getter
    @Setter
    private List<ItemStack> outputs = new ArrayList<>();

    @Getter
    @Setter
    private List<FluidStack> fluidInputs = new ArrayList<>();

    /** Whole percent per output, in step with {@link #outputs}. Only filled for a Smart Crafter. */
    @Getter
    @Setter
    private List<Integer> outputChances = new ArrayList<>();

    /** Ingredients the recipe doesn't consume, left out of the inputs. Only filled for a Smart Crafter. */
    @Getter
    @Setter
    private List<ItemStack> nonConsumed = new ArrayList<>();

    public NEISetAdvancedCraftingRecipe(int id) {
        super(id);
    }

    @Override
    public void processPacket(EntityPlayer player) {
        ModuleCrafter module = getLogisticsModule(player, ModuleCrafter.class);
        if (module instanceof ModuleSmartCrafter) {
            ((ModuleSmartCrafter) module)
                    .handleSmartNEIRecipe(inputs, outputs, outputChances, nonConsumed, fluidInputs, player);
        } else if (module != null) {
            module.handleAdvancedNEIRecipePacket(inputs, outputs, fluidInputs, player);
        }
    }

    @Override
    public ModernPacket template() {
        return new NEISetAdvancedCraftingRecipe(getId());
    }

    @Override
    public void writeData(LPDataOutputStream data) throws IOException {
        super.writeData(data);
        data.writeInt(inputs.size());
        for (ItemStack stack : inputs) {
            data.writeItemStack(stack);
        }
        data.writeInt(outputs.size());
        for (ItemStack stack : outputs) {
            data.writeItemStack(stack);
        }
        data.writeInt(fluidInputs.size());
        for (FluidStack stack : fluidInputs) {
            data.writeFluidStack(stack);
        }
        data.writeInt(outputChances.size());
        for (int chance : outputChances) {
            data.writeInt(chance);
        }
        data.writeInt(nonConsumed.size());
        for (ItemStack stack : nonConsumed) {
            data.writeItemStack(stack);
        }
    }

    @Override
    public void readData(LPDataInputStream data) throws IOException {
        super.readData(data);
        int inputSize = data.readInt();
        inputs = new ArrayList<>(inputSize);
        for (int i = 0; i < inputSize; i++) {
            inputs.add(data.readItemStack());
        }
        int outputSize = data.readInt();
        outputs = new ArrayList<>(outputSize);
        for (int i = 0; i < outputSize; i++) {
            outputs.add(data.readItemStack());
        }
        int fluidSize = data.readInt();
        fluidInputs = new ArrayList<>(fluidSize);
        for (int i = 0; i < fluidSize; i++) {
            fluidInputs.add(data.readFluidStack());
        }
        int chanceSize = data.readInt();
        outputChances = new ArrayList<>(chanceSize);
        for (int i = 0; i < chanceSize; i++) {
            outputChances.add(data.readInt());
        }
        int nonConsumedSize = data.readInt();
        nonConsumed = new ArrayList<>(nonConsumedSize);
        for (int i = 0; i < nonConsumedSize; i++) {
            nonConsumed.add(data.readItemStack());
        }
    }
}
