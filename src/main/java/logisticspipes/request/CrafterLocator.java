package logisticspipes.request;

import java.io.IOException;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.block.Block;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;
import net.minecraftforge.common.util.ForgeDirection;

import logisticspipes.interfaces.routing.ICraft;
import logisticspipes.interfaces.routing.ICraftItems;
import logisticspipes.interfaces.routing.IFilter;
import logisticspipes.interfaces.routing.IRequestItems;
import logisticspipes.modules.abstractmodules.LogisticsModule;
import logisticspipes.network.LPDataInputStream;
import logisticspipes.network.LPDataOutputStream;
import logisticspipes.pipes.PipeLogisticsChassi;
import logisticspipes.pipes.basic.CoreRoutedPipe;
import logisticspipes.pipes.basic.LogisticsTileGenericPipe;
import logisticspipes.proxy.SimpleServiceLocator;
import logisticspipes.items.LogisticsFluidContainer;
import logisticspipes.request.resources.FluidResource;
import logisticspipes.request.resources.IResource;
import logisticspipes.request.resources.ItemResource;
import logisticspipes.routing.ExitRoute;
import logisticspipes.routing.IRouter;
import logisticspipes.routing.PipeRoutingConnectionType;
import logisticspipes.routing.ServerRouter;
import logisticspipes.utils.FluidIdentifier;
import logisticspipes.utils.item.ItemIdentifier;

/**
 * Finds every crafter a request from a given pipe could use for an item, for the request table's Locate button. Uses
 * the same tests as {@code RequestTreeNode.checkCrafting}: reachable with request rights, the pipe offers a template
 * for the exact item, and no route filter blocks it. Read only; nothing is planned or ordered.
 */
public class CrafterLocator {

    /** One crafter: where its pipe is and, if it faces one, the block it crafts in. */
    public static class Location {

        public int dimension;
        public int x, y, z;
        /** Chassis slot of the crafting module LP would use, counted from 0, or -1 for a crafting pipe. */
        public int chassisSlot = -1;
        public boolean hasMachine;
        public int machineX, machineY, machineZ;
        /** The machine as an item, for its name; null if it has none. */
        public ItemStack machine;

        public void write(LPDataOutputStream out) throws IOException {
            out.writeInt(dimension);
            out.writeInt(x);
            out.writeInt(y);
            out.writeInt(z);
            out.writeInt(chassisSlot);
            out.writeBoolean(hasMachine);
            if (hasMachine) {
                out.writeInt(machineX);
                out.writeInt(machineY);
                out.writeInt(machineZ);
                out.writeItemStack(machine);
            }
        }

        public static Location read(LPDataInputStream in) throws IOException {
            Location l = new Location();
            l.dimension = in.readInt();
            l.x = in.readInt();
            l.y = in.readInt();
            l.z = in.readInt();
            l.chassisSlot = in.readInt();
            l.hasMachine = in.readBoolean();
            if (l.hasMachine) {
                l.machineX = in.readInt();
                l.machineY = in.readInt();
                l.machineZ = in.readInt();
                l.machine = in.readItemStack();
            }
            return l;
        }
    }

    /**
     * @param item an item, or LP's fluid container standing for a fluid (the request table's fluid view sends one),
     *             in which case the crafters of that fluid are found
     */
    public static List<Location> find(CoreRoutedPipe requester, ItemIdentifier item) {
        List<Location> found = new ArrayList<>();
        IRouter from = requester.getRouter();
        if (from == null) {
            return found;
        }
        IResource resource;
        FluidIdentifier fluid = item.item instanceof LogisticsFluidContainer ? FluidIdentifier.get(item) : null;
        if (fluid != null) {
            resource = new FluidResource(fluid, 1, null);
        } else {
            resource = new ItemResource(
                    item.makeStack(1),
                    requester instanceof IRequestItems ? (IRequestItems) requester : null);
        }
        Set<Integer> seen = new HashSet<>();
        BitSet routersIndex = ServerRouter.getRoutersInterestedIn(resource);
        for (int i = routersIndex.nextSetBit(0); i >= 0; i = routersIndex.nextSetBit(i + 1)) {
            IRouter r = SimpleServiceLocator.routerManager.getRouterUnsafe(i, false);
            if (r == null || !r.isValidCache()) {
                continue;
            }
            List<ExitRoute> routes = from.getDistanceTo(r);
            if (routes == null) {
                continue;
            }
            for (ExitRoute route : routes) {
                if (!route.containsFlag(PipeRoutingConnectionType.canRequestFrom)) {
                    continue;
                }
                CoreRoutedPipe pipe = route.destination.getPipe();
                if (!(pipe instanceof ICraft) || seen.contains(route.destination.getSimpleID())) {
                    continue;
                }
                ICraftingTemplate template = ((ICraft) pipe).addCrafting(resource);
                if (template == null || !template.canCraft(resource) || isFiltered(route, template)) {
                    continue;
                }
                seen.add(route.destination.getSimpleID());
                found.add(describe(pipe, resource));
            }
        }
        return found;
    }

    private static boolean isFiltered(ExitRoute route, ICraftingTemplate template) {
        for (IFilter filter : route.filters) {
            if (filter.isBlocked() == filter.isFilteredItem(template.getResultResource()) || filter.blockCrafting()) {
                return true;
            }
        }
        return false;
    }

    private static Location describe(CoreRoutedPipe pipe, IResource resource) {
        Location l = new Location();
        World world = pipe.getWorld();
        l.dimension = world.provider.dimensionId;
        l.x = pipe.getX();
        l.y = pipe.getY();
        l.z = pipe.getZ();
        if (pipe instanceof PipeLogisticsChassi) {
            // The first module that can craft it, the same one PipeLogisticsChassi.addCrafting answers with.
            PipeLogisticsChassi chassis = (PipeLogisticsChassi) pipe;
            for (int slot = 0; slot < chassis.getChassiSize(); slot++) {
                LogisticsModule module = chassis.getModules().getSubModule(slot);
                if (module instanceof ICraftItems && ((ICraftItems) module).canCraft(resource)) {
                    l.chassisSlot = slot;
                    break;
                }
            }
        }
        ForgeDirection dir = machineSide(pipe);
        if (dir != ForgeDirection.UNKNOWN) {
            l.hasMachine = true;
            l.machineX = l.x + dir.offsetX;
            l.machineY = l.y + dir.offsetY;
            l.machineZ = l.z + dir.offsetZ;
            l.machine = machineStack(world, l.machineX, l.machineY, l.machineZ, dir);
        }
        return l;
    }

    /**
     * The side the pipe points at, or else the first side with an inventory that isn't a pipe (a crafting pipe that was
     * never pointed with a wrench).
     */
    public static ForgeDirection machineSide(CoreRoutedPipe pipe) {
        ForgeDirection dir = pipe.inventoryOrientation();
        if (dir != null && dir != ForgeDirection.UNKNOWN) {
            return dir;
        }
        for (ForgeDirection side : ForgeDirection.VALID_DIRECTIONS) {
            TileEntity tile = pipe.getWorld()
                    .getTileEntity(pipe.getX() + side.offsetX, pipe.getY() + side.offsetY, pipe.getZ() + side.offsetZ);
            if (tile instanceof IInventory && !(tile instanceof LogisticsTileGenericPipe)) {
                return side;
            }
        }
        return ForgeDirection.UNKNOWN;
    }

    /** The block as its pick-block item, which names GregTech machines properly; null if it has no item. */
    public static ItemStack machineStack(World world, int x, int y, int z, ForgeDirection dir) {
        Block block = world.getBlock(x, y, z);
        if (block == null || block.isAir(world, x, y, z)) {
            return null;
        }
        try {
            MovingObjectPosition hit = new MovingObjectPosition(
                    x,
                    y,
                    z,
                    dir.getOpposite().ordinal(),
                    Vec3.createVectorHelper(x + 0.5, y + 0.5, z + 0.5));
            ItemStack stack = block.getPickBlock(hit, world, x, y, z);
            if (stack != null && stack.getItem() != null) {
                return stack;
            }
        } catch (RuntimeException | LinkageError ignored) {
            // Some blocks assume a client-side player here. On a dedicated server, Forge's default getPickBlock calls
            // the client-only Block.getItem(World, x, y, z) and throws NoSuchMethodError. Fall back below.
        }
        Item item = Item.getItemFromBlock(block);
        return item == null ? null : new ItemStack(item, 1, block.getDamageValue(world, x, y, z));
    }
}
