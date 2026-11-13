package logisticspipes.logistics;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeSet;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.fluids.FluidStack;

import logisticspipes.LogisticsPipes;
import logisticspipes.interfaces.routing.IFilter;
import logisticspipes.interfaces.routing.IFluidSink;
import logisticspipes.interfaces.routing.IProvideFluids;
import logisticspipes.items.LogisticsFluidContainer;
import logisticspipes.modules.ModuleSmartCrafter;
import logisticspipes.modules.abstractmodules.LogisticsModule;
import logisticspipes.pipes.PipeLogisticsChassi;
import logisticspipes.pipes.basic.CoreRoutedPipe;
import logisticspipes.request.resources.FluidResource;
import logisticspipes.routing.ExitRoute;
import logisticspipes.routing.IRouter;
import logisticspipes.routing.PipeRoutingConnectionType;
import logisticspipes.utils.FluidIdentifier;
import logisticspipes.utils.item.ItemIdentifierStack;
import logisticspipes.utils.tuples.Pair;

public class LogisticsFluidManager implements ILogisticsFluidManager {

    @Override
    public Pair<Integer, Integer> getBestReply(FluidStack stack, IRouter sourceRouter, List<Integer> jamList,
            boolean allowOverflow) {
        // Pass 2 fallback: the nearest default-path sink with room, consulted only if pass 1 finds nothing.
        // Candidates are cost-ordered (nearest first), so the first one seen is the nearest.
        int defaultPathDestination = 0;
        int defaultPathAmount = 0;
        // Pass 3 fallback (only when asked): the nearest overflow sink with room, if passes 1 and 2 find nothing.
        int overflowDestination = 0;
        int overflowAmount = 0;

        for (ExitRoute candidateRouter : sourceRouter.getIRoutersByCost()) {
            if (!candidateRouter.containsFlag(PipeRoutingConnectionType.canRouteTo)) {
                continue;
            }
            if (candidateRouter.destination.getSimpleID() == sourceRouter.getSimpleID()) {
                continue;
            }
            if (jamList.contains(candidateRouter.destination.getSimpleID())) {
                continue;
            }

            if (candidateRouter.destination.getPipe() == null || !candidateRouter.destination.getPipe().isEnabled()) {
                continue;
            }
            CoreRoutedPipe pipe = candidateRouter.destination.getPipe();

            if (!(pipe instanceof IFluidSink)) {
                continue;
            }
            IFluidSink sink = (IFluidSink) pipe;

            // Pass 1 (unchanged): first filtered sink with room wins immediately.
            int amount = sink.sinkAmount(stack);
            if (amount > 0) {
                return new Pair<>(candidateRouter.destination.getSimpleID(), amount);
            }

            // Pass 2 (default path): remember the nearest default-path sink with room. Inert while
            // no sink has the flag set — defaultPathSinkAmount defaults to 0 on every existing pipe.
            if (defaultPathDestination == 0) {
                int defaultAmount = sink.defaultPathSinkAmount(stack);
                if (defaultAmount > 0) {
                    defaultPathDestination = candidateRouter.destination.getSimpleID();
                    defaultPathAmount = defaultAmount;
                }
            }

            // Pass 3 (overflow): remember the nearest overflow sink with room. Skipped unless the caller asked, and
            // once a default-path sink was found, since that one wins anyway.
            if (allowOverflow && overflowDestination == 0 && defaultPathDestination == 0) {
                int amountOverflow = sink.overflowSinkAmount(stack);
                if (amountOverflow > 0) {
                    overflowDestination = candidateRouter.destination.getSimpleID();
                    overflowAmount = amountOverflow;
                }
            }
        }
        if (defaultPathDestination != 0) {
            return new Pair<>(defaultPathDestination, defaultPathAmount);
        }
        if (overflowDestination != 0) {
            return new Pair<>(overflowDestination, overflowAmount);
        }
        return new Pair<>(0, 0);
    }

    @Override
    public ItemIdentifierStack getFluidContainer(FluidStack stack) {
        ItemStack item = new ItemStack(LogisticsPipes.LogisticsFluidContainer, 1);
        NBTTagCompound nbt = new NBTTagCompound();
        stack.writeToNBT(nbt);
        item.setTagCompound(nbt);
        return ItemIdentifierStack.getFromStack(item);
    }

    @Override
    public FluidStack getFluidFromContainer(ItemIdentifierStack stack) {
        if (stack.getItem().item instanceof LogisticsFluidContainer && stack.getItem().tag != null) {
            return FluidStack.loadFluidStackFromNBT(stack.getItem().tag);
        }

        // Support for GregTech fluid containers
        if (stack.getItem().tag != null && stack.getItem().tag.hasKey("GT.FluidContent", 10)) {
            return FluidStack.loadFluidStackFromNBT(stack.getItem().tag.getCompoundTag("GT.FluidContent"));
        }

        return null;
    }

    @Override
    public TreeSet<ItemIdentifierStack> getAvailableFluid(List<ExitRoute> validDestinations) {
        Map<FluidIdentifier, Integer> allAvailableItems = new HashMap<>();
        for (ExitRoute r : validDestinations) {
            if (r == null) {
                continue;
            }
            if (!r.containsFlag(PipeRoutingConnectionType.canRequestFrom)) {
                continue;
            }
            if (!(r.destination.getPipe() instanceof IProvideFluids)) {
                continue;
            }

            IProvideFluids provider = (IProvideFluids) r.destination.getPipe();
            Map<FluidIdentifier, Integer> allItems = provider.getAvailableFluids();

            for (Entry<FluidIdentifier, Integer> liquid : allItems.entrySet()) {
                Integer amount = allAvailableItems.get(liquid.getKey());
                if (amount == null) {
                    allAvailableItems.put(liquid.getKey(), liquid.getValue());
                } else {
                    long addition = ((long) amount) + liquid.getValue();
                    if (addition > Integer.MAX_VALUE) {
                        addition = Integer.MAX_VALUE;
                    }
                    allAvailableItems.put(liquid.getKey(), (int) addition);
                }
            }
        }
        TreeSet<ItemIdentifierStack> itemIdentifierStackList = new TreeSet<>();
        for (Entry<FluidIdentifier, Integer> item : allAvailableItems.entrySet()) {
            itemIdentifierStackList.add(new ItemIdentifierStack(item.getKey().getItemIdentifier(), item.getValue()));
        }
        return itemIdentifierStackList;
    }

    /**
     * Per provider pipe rather than per route: a pipe reached by more than one route counts once, with each fluid that
     * any of its routes lets through.
     */
    @Override
    public Map<FluidIdentifier, Integer> getProvidableFluids(List<ExitRoute> validDestinations) {
        Map<Integer, Map<FluidIdentifier, Integer>> byProvider = new HashMap<>();
        for (ExitRoute r : validDestinations) {
            if (r == null || !r.containsFlag(PipeRoutingConnectionType.canRequestFrom)) {
                continue;
            }
            if (!(r.destination.getPipe() instanceof IProvideFluids) || blocks(r.filters, null, true)) {
                continue;
            }
            Map<FluidIdentifier, Integer> provided = ((IProvideFluids) r.destination.getPipe()).getAvailableFluids();
            Map<FluidIdentifier, Integer> counted = byProvider
                    .computeIfAbsent(r.destination.getSimpleID(), id -> new HashMap<>());
            for (Entry<FluidIdentifier, Integer> fluid : provided.entrySet()) {
                if (!blocks(r.filters, fluid.getKey(), true)) {
                    counted.put(fluid.getKey(), fluid.getValue());
                }
            }
        }
        Map<FluidIdentifier, Integer> all = new HashMap<>();
        for (Map<FluidIdentifier, Integer> counted : byProvider.values()) {
            for (Entry<FluidIdentifier, Integer> fluid : counted.entrySet()) {
                all.merge(fluid.getKey(), fluid.getValue(), (a, b) -> (int) Math.min(Integer.MAX_VALUE, (long) a + b));
            }
        }
        return all;
    }

    /**
     * Fluid Products of the Smart Crafters in each chassis a request can reach, with the same filter test
     * {@code RequestTreeNode.checkCrafting} applies before it uses a crafter.
     */
    @Override
    public Set<FluidIdentifier> getCraftableFluids(List<ExitRoute> validDestinations) {
        Set<FluidIdentifier> craftable = new HashSet<>();
        for (ExitRoute r : validDestinations) {
            if (r == null || !r.containsFlag(PipeRoutingConnectionType.canRequestFrom)) {
                continue;
            }
            if (!(r.destination.getPipe() instanceof PipeLogisticsChassi) || blocks(r.filters, null, false)) {
                continue;
            }
            PipeLogisticsChassi chassis = (PipeLogisticsChassi) r.destination.getPipe();
            for (int slot = 0; slot < chassis.getChassiSize(); slot++) {
                LogisticsModule module = chassis.getModules().getSubModule(slot);
                if (!(module instanceof ModuleSmartCrafter)) {
                    continue;
                }
                for (FluidIdentifier fluid : ((ModuleSmartCrafter) module).getCraftedFluids()) {
                    if (!blocks(r.filters, fluid, false)) {
                        craftable.add(fluid);
                    }
                }
            }
        }
        return craftable;
    }

    /**
     * Whether a route's filters stop a request: for a provider ({@code providing}) or a crafter, and with a fluid, for
     * that fluid too. Same test as {@code PipeFluidProvider.canProvide} and {@code RequestTreeNode.checkCrafting}.
     */
    private static boolean blocks(List<IFilter> filters, FluidIdentifier fluid, boolean providing) {
        FluidResource resource = fluid == null ? null : new FluidResource(fluid, 1, null);
        for (IFilter filter : filters) {
            if (providing ? filter.blockProvider() : filter.blockCrafting()) {
                return true;
            }
            if (resource != null && filter.isBlocked() == filter.isFilteredItem(resource)) {
                return true;
            }
        }
        return false;
    }
}
