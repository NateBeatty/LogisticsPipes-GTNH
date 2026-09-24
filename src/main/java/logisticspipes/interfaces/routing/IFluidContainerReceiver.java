package logisticspipes.interfaces.routing;

import net.minecraftforge.fluids.FluidStack;

/**
 * A pipe that takes fluid delivered to it and puts it somewhere itself.
 * <p>
 * LP moves fluid as an ordinary routed item holding a fluid container, so any pipe can be addressed as a fluid
 * destination; what a fluid pipe adds is only the receiving end, emptying the container into a tank. This lets a pipe
 * that isn't a {@code FluidRoutedPipe} do the same, the way {@code PipeItemsFluidSupplier} already does for the
 * supplier.
 */
public interface IFluidContainerReceiver {

    /**
     * Asked while the item is still travelling, to decide whether to hand it over at all. Without this the chassis
     * turns a fluid container away, since no module sinks one as an item.
     */
    boolean wantsFluid(FluidStack fluid);

    /**
     * Offered a fluid container that arrived here, before it would be inserted as an item (which would destroy it,
     * since LP's fluid container can't exist in an inventory).
     *
     * @return true if it was taken. The receiver owns the fluid from then on, including sending back whatever it
     *         couldn't store.
     */
    boolean receiveFluidContainer(FluidStack fluid);
}
