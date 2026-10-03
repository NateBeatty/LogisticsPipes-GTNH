package logisticspipes.request;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.item.ItemStack;
import net.minecraftforge.common.util.ForgeDirection;

import logisticspipes.network.LPDataInputStream;
import logisticspipes.network.LPDataOutputStream;
import logisticspipes.pipes.PipeLogisticsChassi;
import logisticspipes.pipes.basic.CoreRoutedPipe;
import logisticspipes.routing.ExitRoute;
import logisticspipes.routing.IRouter;
import logisticspipes.utils.PipeLabel;
import logisticspipes.utils.item.ItemIdentifierStack;

/**
 * The chassis pipes with a Label Upgrade on a network, for the Statistics Table's crafter list: their label, the
 * machine they face, what they craft and how many module slots are still empty. Read only.
 */
public class LabeledCrafters {

    /** At most this many crafted items are sent per chassis; enough to search on and show. */
    private static final int MAX_OUTPUTS = 27;

    public static class Entry {

        public int dimension;
        public int x, y, z;
        public PipeLabel label = PipeLabel.EMPTY;
        /** The machine as an item, or the chassis pipe itself if it faces none; for the icon and the name. */
        public ItemStack icon;
        public String machineName = "";
        public boolean hasMachine;
        public int machineX, machineY, machineZ;
        public List<ItemIdentifierStack> outputs = new ArrayList<>();
        public int freeSlots;
        public int totalSlots;

        public void write(LPDataOutputStream out) throws IOException {
            out.writeInt(dimension);
            out.writeInt(x);
            out.writeInt(y);
            out.writeInt(z);
            label.write(out);
            out.writeItemStack(icon);
            out.writeUTF(machineName);
            out.writeBoolean(hasMachine);
            if (hasMachine) {
                out.writeInt(machineX);
                out.writeInt(machineY);
                out.writeInt(machineZ);
            }
            out.writeList(outputs, (o, stack) -> o.writeItemIdentifierStack(stack));
            out.writeByte(freeSlots);
            out.writeByte(totalSlots);
        }

        public static Entry read(LPDataInputStream in) throws IOException {
            Entry e = new Entry();
            e.dimension = in.readInt();
            e.x = in.readInt();
            e.y = in.readInt();
            e.z = in.readInt();
            e.label = PipeLabel.read(in);
            e.icon = in.readItemStack();
            e.machineName = in.readUTF();
            e.hasMachine = in.readBoolean();
            if (e.hasMachine) {
                e.machineX = in.readInt();
                e.machineY = in.readInt();
                e.machineZ = in.readInt();
            }
            e.outputs = in.readList(LPDataInputStream::readItemIdentifierStack);
            e.freeSlots = in.readByte();
            e.totalSlots = in.readByte();
            return e;
        }
    }

    /** Every labeled chassis reachable from this router, the router's own pipe included. */
    public static List<Entry> collect(IRouter router) {
        List<Entry> found = new ArrayList<>();
        if (router == null) {
            return found;
        }
        Set<Integer> seen = new HashSet<>();
        addIfLabeled(router, found, seen);
        for (ExitRoute route : router.getIRoutersByCost()) {
            if (route != null && route.destination != null) {
                addIfLabeled(route.destination, found, seen);
            }
        }
        return found;
    }

    private static void addIfLabeled(IRouter router, List<Entry> found, Set<Integer> seen) {
        if (!seen.add(router.getSimpleID())) {
            return;
        }
        CoreRoutedPipe pipe = router.getPipe();
        if (pipe instanceof PipeLogisticsChassi && ((PipeLogisticsChassi) pipe).hasLabelUpgrade()) {
            found.add(describe((PipeLogisticsChassi) pipe));
        }
    }

    private static Entry describe(PipeLogisticsChassi chassis) {
        Entry e = new Entry();
        e.dimension = chassis.getWorld().provider.dimensionId;
        e.x = chassis.getX();
        e.y = chassis.getY();
        e.z = chassis.getZ();
        e.label = chassis.getLabel();
        ForgeDirection dir = CrafterLocator.machineSide(chassis);
        if (dir != ForgeDirection.UNKNOWN) {
            e.hasMachine = true;
            e.machineX = e.x + dir.offsetX;
            e.machineY = e.y + dir.offsetY;
            e.machineZ = e.z + dir.offsetZ;
            e.icon = CrafterLocator.machineStack(chassis.getWorld(), e.machineX, e.machineY, e.machineZ, dir);
            if (e.icon != null) {
                e.machineName = e.icon.getDisplayName();
            }
        }
        if (e.label.getIcon() != null) {
            // Auto found a multiblock behind the bus or hatch the chassis faces; show that rather than the bus.
            e.icon = e.label.getIcon();
            e.machineName = e.icon.getDisplayName();
        }
        if (e.icon == null) {
            e.icon = new ItemStack(chassis.item);
        }
        List<ItemIdentifierStack> outputs = chassis.getConfiguredCraftResults();
        if (outputs != null) {
            for (ItemIdentifierStack stack : outputs) {
                if (e.outputs.size() >= MAX_OUTPUTS) {
                    break;
                }
                e.outputs.add(stack);
            }
        }
        e.totalSlots = chassis.getChassiSize();
        for (int slot = 0; slot < e.totalSlots; slot++) {
            if (chassis.getModuleInventory().getStackInSlot(slot) == null) {
                e.freeSlots++;
            }
        }
        return e;
    }
}
