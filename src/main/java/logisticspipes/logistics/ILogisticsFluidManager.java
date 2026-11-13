package logisticspipes.logistics;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import net.minecraftforge.fluids.FluidStack;

import logisticspipes.routing.ExitRoute;
import logisticspipes.routing.IRouter;
import logisticspipes.utils.FluidIdentifier;
import logisticspipes.utils.item.ItemIdentifierStack;
import logisticspipes.utils.tuples.Pair;

public interface ILogisticsFluidManager {

    /** The nearest sink with room, without overflow sinks: what extractor and insertion pipes use. */
    default Pair<Integer, Integer> getBestReply(FluidStack stack, IRouter sourceRouter, List<Integer> jamList) {
        return getBestReply(stack, sourceRouter, jamList, false);
    }

    /**
     * @param allowOverflow also consider overflow sinks, after filtered and default-path sinks found nothing. Only for
     *                      fluid that has to go somewhere: cleanup sweeps, bounced or lost containers.
     */
    Pair<Integer, Integer> getBestReply(FluidStack stack, IRouter sourceRouter, List<Integer> jamList,
            boolean allowOverflow);

    ItemIdentifierStack getFluidContainer(FluidStack stack);

    FluidStack getFluidFromContainer(ItemIdentifierStack stack);

    TreeSet<ItemIdentifierStack> getAvailableFluid(List<ExitRoute> list);

    /** Like {@link #getAvailableFluid}, but leaving out what a firewall on the way stops a request from taking. */
    Map<FluidIdentifier, Integer> getProvidableFluids(List<ExitRoute> list);

    /** The fluids a request could have crafted, firewalls on the way allowing. */
    Set<FluidIdentifier> getCraftableFluids(List<ExitRoute> list);
}
