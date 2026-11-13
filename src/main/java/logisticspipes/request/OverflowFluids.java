package logisticspipes.request;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.world.World;

import logisticspipes.modules.ModuleFluidSink;
import logisticspipes.modules.abstractmodules.LogisticsModule;
import logisticspipes.network.LPDataInputStream;
import logisticspipes.network.LPDataOutputStream;
import logisticspipes.pipes.PipeLogisticsChassi;
import logisticspipes.pipes.basic.CoreRoutedPipe;
import logisticspipes.routing.ExitRoute;
import logisticspipes.routing.IRouter;
import logisticspipes.utils.FluidIdentifier;
import logisticspipes.utils.item.ItemIdentifier;

/**
 * The fluids that went into overflow-mode Fluid Sinks on a network, for the Statistics Table's overflow tab: one line
 * per fluid, added up over every such sink. Each sink keeps its own log ({@link ModuleFluidSink#getOverflowLog()}).
 */
public class OverflowFluids {

    public static class Entry {

        /** The fluid as LP's fluid container item, for its icon, name and to identify it when clearing. */
        public ItemIdentifier fluid;
        public long litres;
        public int deliveries;
        public long ticksAgo;
        /** How many overflow sinks took some. */
        public int sinks;
        /** The sink that took it last, to locate. */
        public int dimension;
        public int x, y, z;

        public void write(LPDataOutputStream out) throws IOException {
            out.writeItemIdentifier(fluid);
            out.writeLong(litres);
            out.writeInt(deliveries);
            out.writeLong(ticksAgo);
            out.writeInt(sinks);
            out.writeInt(dimension);
            out.writeInt(x);
            out.writeInt(y);
            out.writeInt(z);
        }

        public static Entry read(LPDataInputStream in) throws IOException {
            Entry e = new Entry();
            e.fluid = in.readItemIdentifier();
            e.litres = in.readLong();
            e.deliveries = in.readInt();
            e.ticksAgo = in.readLong();
            e.sinks = in.readInt();
            e.dimension = in.readInt();
            e.x = in.readInt();
            e.y = in.readInt();
            e.z = in.readInt();
            return e;
        }
    }

    /** Every logged fluid on the network reachable from this router, most recent first. */
    public static List<Entry> collect(IRouter router) {
        Map<FluidIdentifier, Entry> byFluid = new LinkedHashMap<>();
        for (ModuleFluidSink sink : sinks(router)) {
            World world = sink.getSinkWorld();
            if (world == null) {
                continue;
            }
            long now = world.getTotalWorldTime();
            for (Map.Entry<FluidIdentifier, ModuleFluidSink.OverflowRecord> log : sink.getOverflowLog().entrySet()) {
                ModuleFluidSink.OverflowRecord record = log.getValue();
                long ago = Math.max(0, now - record.lastTime);
                Entry e = byFluid.get(log.getKey());
                if (e == null) {
                    e = new Entry();
                    e.fluid = log.getKey().getItemIdentifier();
                    e.ticksAgo = Long.MAX_VALUE;
                    byFluid.put(log.getKey(), e);
                }
                e.litres += record.litres;
                e.deliveries += record.deliveries;
                e.sinks++;
                if (ago < e.ticksAgo) {
                    e.ticksAgo = ago;
                    e.dimension = world.provider.dimensionId;
                    e.x = sink.getX();
                    e.y = sink.getY();
                    e.z = sink.getZ();
                }
            }
        }
        List<Entry> entries = new ArrayList<>(byFluid.values());
        entries.sort((a, b) -> Long.compare(a.ticksAgo, b.ticksAgo));
        return entries;
    }

    /** Forgets one fluid on every overflow sink of the network, or everything when {@code fluid} is null. */
    public static void clear(IRouter router, ItemIdentifier fluid) {
        FluidIdentifier ident = fluid == null ? null : FluidIdentifier.get(fluid);
        if (fluid != null && ident == null) {
            return;
        }
        for (ModuleFluidSink sink : sinks(router)) {
            sink.clearOverflowLog(ident);
        }
    }

    /** The Fluid Sink modules in every chassis reachable from this router, the router's own pipe included. */
    private static List<ModuleFluidSink> sinks(IRouter router) {
        List<ModuleFluidSink> found = new ArrayList<>();
        if (router == null) {
            return found;
        }
        Set<Integer> seen = new HashSet<>();
        addSinks(router, found, seen);
        for (ExitRoute route : router.getIRoutersByCost()) {
            if (route != null && route.destination != null) {
                addSinks(route.destination, found, seen);
            }
        }
        return found;
    }

    private static void addSinks(IRouter router, List<ModuleFluidSink> found, Set<Integer> seen) {
        if (!seen.add(router.getSimpleID())) {
            return;
        }
        CoreRoutedPipe pipe = router.getPipe();
        if (!(pipe instanceof PipeLogisticsChassi)) {
            return;
        }
        PipeLogisticsChassi chassis = (PipeLogisticsChassi) pipe;
        for (int slot = 0; slot < chassis.getChassiSize(); slot++) {
            LogisticsModule module = chassis.getModules().getSubModule(slot);
            if (module instanceof ModuleFluidSink) {
                found.add((ModuleFluidSink) module);
            }
        }
    }
}
