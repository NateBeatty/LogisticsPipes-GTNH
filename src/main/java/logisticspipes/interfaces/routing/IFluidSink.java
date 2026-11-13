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

    /**
     * Room available on this sink as an <em>overflow</em> endpoint: the last place fluid goes when no filtered or
     * default-path sink has room. Only senders that clean up or recover fluid ask for it (Smart Crafter sweeps, bounced or
     * lost containers); extractor and insertion pipes never do, so they don't pump into it.
     *
     * <p>
     * Default returns 0, so the third routing pass is inert until a sink opts in.
     * </p>
     */
    default int overflowSinkAmount(FluidStack stack) {
        return 0;
    }
}
