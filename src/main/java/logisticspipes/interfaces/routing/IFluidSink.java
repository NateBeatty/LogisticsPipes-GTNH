package logisticspipes.interfaces.routing;

import net.minecraftforge.fluids.FluidStack;

public interface IFluidSink {

    int sinkAmount(FluidStack stack);

    /**
     * Room available on this sink as a <em>default path</em> (last-resort) endpoint for the given fluid.
     *
     * <p>
     * Default returns 0, so every existing {@code IFluidSink} is unaffected and the second routing pass is inert until
     * a sink explicitly opts in.
     * </p>
     */
    default int defaultPathSinkAmount(FluidStack stack) {
        return 0;
    }
}
