package logisticspipes.modules;

import java.util.List;

import net.minecraft.client.renderer.texture.IIconRegister;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.IIcon;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import logisticspipes.interfaces.IInventoryUtil;
import logisticspipes.interfaces.routing.IRequestItems;
import logisticspipes.modules.abstractmodules.LogisticsModule;
import logisticspipes.network.NewGuiHandler;
import logisticspipes.network.abstractguis.ModuleCoordinatesGuiProvider;
import logisticspipes.network.abstractguis.ModuleInHandGuiProvider;
import logisticspipes.network.guis.module.inhand.BufferProviderInHand;
import logisticspipes.network.guis.module.inpipe.BufferProviderGuiProvider;
import logisticspipes.pipes.PipeLogisticsChassi;
import logisticspipes.pipes.basic.CoreRoutedPipe;
import logisticspipes.request.RequestTree;
import logisticspipes.request.resources.DictResource;
import logisticspipes.request.resources.IResource;
import logisticspipes.request.resources.ItemResource;
import logisticspipes.routing.IRouter;
import logisticspipes.utils.item.ItemIdentifier;

/**
 * A Provider for a buffer chest, i.e. a chest kept stocked by a supplier in the same chassis. Buffers built this way
 * can share with each other without ever feeding each other in a loop.
 * <p>
 * Each buffer has a floor per item: {@code target × (100 − share%) / 100}, the target being what its supplier keeps.
 * <ul>
 * <li>Asked by a request that isn't a buffer's (a machine, a player, a crafter): a normal Provider.</li>
 * <li>Asked by another buffer that is <b>running low</b> (below its own floor): gives only what it has above its own
 * floor.</li>
 * <li>Asked by another buffer that is only <b>topping up</b> (at or above its floor): gives nothing, so it is filled
 * from storage and crafting.</li>
 * </ul>
 * A buffer never gives itself below its floor, so what it asks for next is always a top-up, which no buffer answers.
 * One buffer's request therefore can't make another buffer request from buffers: no loop can form. This only holds
 * because giving and "running low" use the same floor.
 */
public class ModuleBufferProvider extends ModuleProvider {

    public static final int DEFAULT_SHARE_PERCENT = 50;

    private int sharePercent = DEFAULT_SHARE_PERCENT;

    public int getSharePercent() {
        return sharePercent;
    }

    public void setSharePercent(int percent) {
        sharePercent = Math.max(0, Math.min(100, percent));
    }

    @Override
    public void readFromNBT(NBTTagCompound nbttagcompound) {
        super.readFromNBT(nbttagcompound);
        setSharePercent(
                nbttagcompound.hasKey("sharePercent") ? nbttagcompound.getInteger("sharePercent")
                        : DEFAULT_SHARE_PERCENT);
    }

    @Override
    public void writeToNBT(NBTTagCompound nbttagcompound) {
        super.writeToNBT(nbttagcompound);
        nbttagcompound.setInteger("sharePercent", sharePercent);
    }

    @Override
    protected int getProvidableCount(ItemIdentifier item, RequestTree root) {
        ModuleBufferProvider requester = requestingBuffer(root);
        if (requester == null || requester == this) {
            return super.getProvidableCount(item, root);
        }
        ItemIdentifier wanted = requestedItem(root);
        if (wanted == null || !requester.isRunningLow(wanted)) {
            return 0; // a top-up: storage and crafting only
        }
        return Math.max(0, getAvailableItemCount(item) - getFloor(item));
    }

    /** Below its floor: its supplier's request may take from other buffers. */
    public boolean isRunningLow(ItemIdentifier item) {
        return getStock(item) < getFloor(item);
    }

    /**
     * The least this buffer keeps for itself when another buffer asks. Items its supplier doesn't manage have no
     * target, so a floor of 0: nothing refills them, so giving them away can't start a loop.
     */
    public int getFloor(ItemIdentifier item) {
        int target = getTarget(item);
        // Rounded up, so a buffer keeps a little more rather than less.
        return (target * (100 - sharePercent) + 99) / 100;
    }

    /** What the supplier(s) in this chassis keep of the item. */
    private int getTarget(ItemIdentifier item) {
        PipeLogisticsChassi chassis = ownChassis();
        if (chassis == null) {
            return 0;
        }
        int target = 0;
        for (int i = 0; i < chassis.getChassiSize(); i++) {
            LogisticsModule module = chassis.getModules().getSubModule(i);
            if (module instanceof ModuleActiveSupplier) {
                Integer amount = ((ModuleActiveSupplier) module).getDummyInventory().getItemsAndCount().get(item);
                if (amount != null) {
                    target += amount;
                }
            }
        }
        return target;
    }

    /** In the chest, less what is already promised to other requests, whatever this provider's filter says. */
    private int getStock(ItemIdentifier item) {
        IInventoryUtil inv = _service.getPointedInventory(_extractionMode, true);
        if (inv == null) {
            return 0;
        }
        return inv.itemCount(item) - _service.getItemOrderManager().totalItemsCountInOrders(item);
    }

    private PipeLogisticsChassi ownChassis() {
        IRouter router = _service == null ? null : _service.getRouter();
        CoreRoutedPipe pipe = router == null ? null : router.getPipe();
        return pipe instanceof PipeLogisticsChassi ? (PipeLogisticsChassi) pipe : null;
    }

    /**
     * The Buffer Provider of the buffer that started this request, or null if it wasn't started by a buffer. A request
     * is a buffer's when its original requester is a supplier whose chassis also holds a Buffer Provider; this holds
     * for ingredients of sub-crafts at any depth, since every provider is asked with the request's root.
     */
    private static ModuleBufferProvider requestingBuffer(RequestTree root) {
        IRequestItems requester = requesterOf(root.getRequestType());
        if (!(requester instanceof ModuleActiveSupplier)) {
            return null;
        }
        IRouter router = ((ModuleActiveSupplier) requester).getRouter();
        CoreRoutedPipe pipe = router == null ? null : router.getPipe();
        if (!(pipe instanceof PipeLogisticsChassi)) {
            return null;
        }
        PipeLogisticsChassi chassis = (PipeLogisticsChassi) pipe;
        for (int i = 0; i < chassis.getChassiSize(); i++) {
            LogisticsModule module = chassis.getModules().getSubModule(i);
            if (module instanceof ModuleBufferProvider) {
                return (ModuleBufferProvider) module;
            }
        }
        return null;
    }

    private static IRequestItems requesterOf(IResource resource) {
        if (resource instanceof ItemResource) {
            return ((ItemResource) resource).getTarget();
        }
        if (resource instanceof DictResource) {
            return ((DictResource) resource).getTarget();
        }
        return null;
    }

    /** The item the requesting buffer asked for (a supplier asks for one item per request). */
    private static ItemIdentifier requestedItem(RequestTree root) {
        IResource resource = root.getRequestType();
        return resource instanceof ItemResource ? ((ItemResource) resource).getItem() : null;
    }

    @Override
    public List<String> getClientInformation() {
        List<String> list = super.getClientInformation();
        list.add(0, "Share: " + sharePercent + "%");
        return list;
    }

    @Override
    protected ModuleCoordinatesGuiProvider getPipeGuiProvider() {
        return NewGuiHandler.getGui(BufferProviderGuiProvider.class).setSharePercent(sharePercent)
                .setExtractorMode(getExtractionMode().ordinal()).setExclude(isExcludeFilter);
    }

    @Override
    protected ModuleInHandGuiProvider getInHandGuiProvider() {
        return NewGuiHandler.getGui(BufferProviderInHand.class);
    }

    @Override
    @SideOnly(Side.CLIENT)
    public IIcon getIconTexture(IIconRegister register) {
        return register.registerIcon("logisticspipes:itemModule/ModuleBufferProvider");
    }
}
