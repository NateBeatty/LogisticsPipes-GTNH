package logisticspipes.network.packets.block;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.entity.player.EntityPlayer;

import logisticspipes.blocks.stats.LogisticsStatisticsTileEntity;
import logisticspipes.modules.ModuleCrafter;
import logisticspipes.modules.ModuleSmartCrafter;
import logisticspipes.modules.abstractmodules.LogisticsModule;
import logisticspipes.network.PacketHandler;
import logisticspipes.network.abstractpackets.CoordinatesPacket;
import logisticspipes.network.abstractpackets.ModernPacket;
import logisticspipes.pipes.PipeItemsCraftingLogistics;
import logisticspipes.pipes.PipeLogisticsChassi;
import logisticspipes.pipes.basic.CoreRoutedPipe;
import logisticspipes.proxy.MainProxy;
import logisticspipes.routing.ExitRoute;
import logisticspipes.routing.order.IOrderInfoProvider.ResourceType;
import logisticspipes.routing.order.LogisticsItemOrder;
import logisticspipes.utils.item.ItemIdentifierStack;

public class RequestRunningCraftingTasks extends CoordinatesPacket {

    public RequestRunningCraftingTasks(int id) {
        super(id);
    }

    @Override
    public void processPacket(EntityPlayer player) {
        LogisticsStatisticsTileEntity tile = this.getTile(player.getEntityWorld(), LogisticsStatisticsTileEntity.class);
        CoreRoutedPipe pipe = tile.getConnectedPipe();
        if (pipe == null) {
            return;
        }

        List<ItemIdentifierStack> items = new ArrayList<>();

        for (ExitRoute r : pipe.getRouter().getIRoutersByCost()) {
            if (r == null) {
                continue;
            }
            if (r.destination.getPipe() instanceof PipeItemsCraftingLogistics) {
                PipeItemsCraftingLogistics crafting = (PipeItemsCraftingLogistics) r.destination.getPipe();
                List<ItemIdentifierStack> content = crafting.getItemOrderManager()
                        .getContentList(player.getEntityWorld());
                items.addAll(content);
            } else if (r.destination.getPipe() instanceof PipeLogisticsChassi) {
                addChassisCrafts((PipeLogisticsChassi) r.destination.getPipe(), items);
            }
        }
        MainProxy.sendPacketToPlayer(PacketHandler.getPacket(RunningCraftingTasks.class).setIdentList(items), player);
    }

    /**
     * Crafting modules in a chassis share one order manager with every other module in it, provider and supplier orders
     * included, so only the crafting orders are taken. A Smart Crafter keeps its fluid orders apart, so those are asked
     * for separately.
     */
    private static void addChassisCrafts(PipeLogisticsChassi chassis, List<ItemIdentifierStack> items) {
        boolean hasCrafter = false;
        for (int i = 0; i < chassis.getChassiSize(); i++) {
            LogisticsModule module = chassis.getLogisticsModule().getSubModule(i);
            if (module instanceof ModuleCrafter) {
                hasCrafter = true;
            }
            if (module instanceof ModuleSmartCrafter) {
                for (ItemIdentifierStack fluid : ((ModuleSmartCrafter) module).getFluidCraftingContent()) {
                    merge(items, fluid);
                }
            }
        }
        if (!hasCrafter) {
            return;
        }
        for (LogisticsItemOrder order : chassis.getItemOrderManager()) {
            if (order.getType() == ResourceType.CRAFTING) {
                merge(items, order.getAsDisplayItem());
            }
        }
    }

    /** Adds to an existing entry for the same item, as {@code getContentList} does. */
    private static void merge(List<ItemIdentifierStack> items, ItemIdentifierStack stack) {
        for (ItemIdentifierStack existing : items) {
            if (existing.getItem().equals(stack.getItem())) {
                existing.setStackSize(existing.getStackSize() + stack.getStackSize());
                return;
            }
        }
        items.add(stack.clone());
    }

    @Override
    public ModernPacket template() {
        return new RequestRunningCraftingTasks(getId());
    }
}
