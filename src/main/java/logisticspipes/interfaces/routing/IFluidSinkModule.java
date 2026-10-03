package logisticspipes.interfaces.routing;

import net.minecraftforge.fluids.FluidStack;

/**
 * A chassis module that accepts fluid. The chassis forwards the fluid router's room queries here, the way it forwards
 * item routing to item modules.
 *
 * <p>
 * A module answers two kinds of query, which the fluid router uses as two passes:
 * </p>
 * <ul>
 * <li>{@code defaultPath == false} — room only if this fluid is explicitly accepted (e.g. listed in the module's
 * filter).</li>
 * <li>{@code defaultPath == true} — room as a last-resort default path, for any fluid the module has opted in to.</li>
 * </ul>
 */
public interface IFluidSinkModule {

    /**
     * How much of {@code stack} this module can currently accept.
     *
     * @param stack       the fluid being routed (never null)
     * @param defaultPath {@code true} queries the module as a default-path (last resort) endpoint; {@code false}
     *                    queries it as a filtered endpoint
     * @return the amount (0 to {@code stack.amount}) this module can take right now
     */
    int sinkAmount(FluidStack stack, boolean defaultPath);
}
