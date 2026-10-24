/*
 * Copyright (c) Krapht, 2011 "LogisticsPipes" is distributed under the terms of the Minecraft Mod Public License 1.0,
 * or MMPL. Please check the contents of the license located in http://www.mod-buildcraft.com/MMPL-1.0.txt
 */
package logisticspipes.pipes;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.TreeMap;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ChatComponentText;

import logisticspipes.LogisticsPipes;
import logisticspipes.api.IRequestAPI;
import logisticspipes.interfaces.routing.IRequestItems;
import logisticspipes.modules.abstractmodules.LogisticsModule;
import logisticspipes.network.GuiIDs;
import logisticspipes.pipes.basic.CoreRoutedPipe;
import logisticspipes.proxy.MainProxy;
import logisticspipes.proxy.SimpleServiceLocator;
import logisticspipes.proxy.computers.interfaces.CCCommand;
import logisticspipes.proxy.computers.interfaces.CCQueued;
import logisticspipes.proxy.computers.interfaces.CCType;
import logisticspipes.request.RequestHandler;
import logisticspipes.request.RequestLog;
import logisticspipes.request.RequestTree;
import logisticspipes.request.resources.DictResource;
import logisticspipes.request.resources.IResource;
import logisticspipes.request.resources.ItemResource;
import logisticspipes.routing.order.LinkedLogisticsOrderList;
import logisticspipes.security.SecuritySettings;
import logisticspipes.textures.Textures;
import logisticspipes.textures.Textures.TextureType;
import logisticspipes.utils.item.ItemIdentifier;
import logisticspipes.utils.item.ItemIdentifierStack;
import logisticspipes.utils.tuples.Pair;

@CCType(name = "LogisticsPipes:Request")
public class PipeItemsRequestLogistics extends CoreRoutedPipe implements IRequestItems, IRequestAPI {

    private final LinkedList<Map<ItemIdentifier, Integer>> _history = new LinkedList<>();

    public PipeItemsRequestLogistics(Item item) {
        super(item);
    }

    @Override
    public TextureType getCenterTexture() {
        return Textures.LOGISTICSPIPE_REQUESTER_TEXTURE;
    }

    @Override
    public LogisticsModule getLogisticsModule() {
        return null;
    }

    public void openGui(EntityPlayer entityplayer) {
        entityplayer.openGui(LogisticsPipes.instance, GuiIDs.GUI_Normal_Orderer_ID, getWorld(), getX(), getY(), getZ());
    }

    @Override
    public boolean handleClick(EntityPlayer entityplayer, SecuritySettings settings) {
        if (SimpleServiceLocator.toolWrenchHandler.isWrenchEquipped(entityplayer)
                && SimpleServiceLocator.toolWrenchHandler.canWrench(entityplayer, getX(), getY(), getZ())) {
            if (MainProxy.isServer(getWorld())) {
                if (settings == null || settings.openRequest) {
                    openGui(entityplayer);
                } else {
                    entityplayer.addChatMessage(new ChatComponentText("Permission denied"));
                }
            }
            SimpleServiceLocator.toolWrenchHandler.wrenchUsed(entityplayer, getX(), getY(), getZ());
            return true;
        }
        return false;
    }

    @Override
    public void enabledUpdateEntity() {
        super.enabledUpdateEntity();
        if (getWorld().getTotalWorldTime() % 1200 == 0) {
            _history.addLast(SimpleServiceLocator.logisticsManager.getAvailableItems(getRouter().getIRoutersByCost()));
            if (_history.size() > 20) {
                _history.removeFirst();
            }
        }
    }

    public LinkedList<Map<ItemIdentifier, Integer>> getHistory() {
        return _history;
    }

    @Override
    public ItemSendMode getItemSendMode() {
        return ItemSendMode.Normal;
    }

    /* IRequestAPI */

    @Override
    public List<ItemStack> getProvidedItems() {
        if (stillNeedReplace()) {
            return new ArrayList<>();
        }
        Map<ItemIdentifier, Integer> items = SimpleServiceLocator.logisticsManager
                .getAvailableItems(getRouter().getIRoutersByCost());
        List<ItemStack> list = new ArrayList<>(items.size());
        for (Entry<ItemIdentifier, Integer> item : items.entrySet()) {
            ItemStack is = item.getKey().unsafeMakeNormalStack(item.getValue());
            list.add(is);
        }
        return list;
    }

    @Override
    public List<ItemStack> getCraftedItems() {
        if (stillNeedReplace()) {
            return new ArrayList<>();
        }
        LinkedList<ItemIdentifier> items = SimpleServiceLocator.logisticsManager
                .getCraftableItems(getRouter().getIRoutersByCost());
        List<ItemStack> list = new ArrayList<>(items.size());
        for (ItemIdentifier item : items) {
            ItemStack is = item.unsafeMakeNormalStack(0);
            list.add(is);
        }
        return list;
    }

    @Override
    public SimulationResult simulateRequest(ItemStack wanted) {
        final List<IResource> used = new ArrayList<>();
        final List<IResource> missing = new ArrayList<>();
        RequestTree.simulate(ItemIdentifier.get(wanted).makeStack(wanted.stackSize), this, new RequestLog() {

            @Override
            public void handleMissingItems(List<IResource> items) {
                missing.addAll(items);
            }

            @Override
            public void handleSucessfullRequestOf(IResource item, LinkedLogisticsOrderList parts) {}

            @Override
            public void handleSucessfullRequestOfList(List<IResource> items, LinkedLogisticsOrderList parts) {
                used.addAll(items);
            }
        });
        List<ItemStack> usedList = new ArrayList<>(used.size());
        List<ItemStack> missingList = new ArrayList<>(missing.size());
        for (IResource e : used) {
            if (e instanceof ItemResource) {
                usedList.add(((ItemResource) e).getItem().unsafeMakeNormalStack(e.getRequestedAmount()));
            } else if (e instanceof DictResource) {
                usedList.add(((DictResource) e).getItem().unsafeMakeNormalStack(e.getRequestedAmount()));
            }
        }
        for (IResource e : missing) {
            if (e instanceof ItemResource) {
                missingList.add(((ItemResource) e).getItem().unsafeMakeNormalStack(e.getRequestedAmount()));
            } else if (e instanceof DictResource) {
                missingList.add(((DictResource) e).getItem().unsafeMakeNormalStack(e.getRequestedAmount()));
            }
        }

        SimulationResult r = new SimulationResult();
        r.used = usedList;
        r.missing = missingList;
        return r;
    }

    @Override
    public List<ItemStack> performRequest(ItemStack wanted) {
        final List<IResource> missing = new ArrayList<>();
        RequestTree.request(ItemIdentifier.get(wanted).makeStack(wanted.stackSize), this, new RequestLog() {

            @Override
            public void handleMissingItems(List<IResource> items) {
                missing.addAll(items);
            }

            @Override
            public void handleSucessfullRequestOf(IResource item, LinkedLogisticsOrderList parts) {}

            @Override
            public void handleSucessfullRequestOfList(List<IResource> items, LinkedLogisticsOrderList parts) {}
        }, null);
        List<ItemStack> missingList = new ArrayList<>(missing.size());
        for (IResource e : missing) {
            if (e instanceof ItemResource) {
                missingList.add(((ItemResource) e).getItem().unsafeMakeNormalStack(e.getRequestedAmount()));
            } else if (e instanceof DictResource) {
                missingList.add(((DictResource) e).getItem().unsafeMakeNormalStack(e.getRequestedAmount()));
            }
        }

        return missingList;
    }

    /* CC */
    @CCCommand(description = "Requests the given ItemIdentifierStack")
    @CCQueued
    public Object[] makeRequest(ItemIdentifierStack stack) throws Exception {
        return makeRequest(stack.getItem(), (double) stack.getStackSize(), false);
    }

    @CCCommand(description = "Requests the given ItemIdentifierStack")
    @CCQueued
    public Object[] makeRequest(ItemIdentifierStack stack, Boolean forceCrafting) throws Exception {
        return makeRequest(stack.getItem(), (double) stack.getStackSize(), forceCrafting);
    }

    @CCCommand(description = "Requests the given ItemIdentifier with the given amount")
    @CCQueued
    public Object[] makeRequest(ItemIdentifier item, Double amount) throws Exception {
        return makeRequest(item, amount, false);
    }

    @CCCommand(description = "Requests the given ItemIdentifier with the given amount")
    @CCQueued
    public Object[] makeRequest(ItemIdentifier item, Double amount, Boolean forceCrafting) throws Exception {
        if (forceCrafting == null) {
            forceCrafting = false;
        }
        if (item == null) {
            throw new Exception("Invalid ItemIdentifier");
        }
        return RequestHandler.computerRequest(item.makeStack((int) Math.floor(amount)), this, forceCrafting);
    }

    @CCCommand(description = "Asks for all available ItemIdentifier inside the Logistics Network")
    @CCQueued
    public List<Pair<ItemIdentifier, Integer>> getAvailableItems() {
        Map<ItemIdentifier, Integer> items = SimpleServiceLocator.logisticsManager
                .getAvailableItems(getRouter().getIRoutersByCost());
        List<Pair<ItemIdentifier, Integer>> list = new LinkedList<>();
        for (Entry<ItemIdentifier, Integer> item : items.entrySet()) {
            int amount = item.getValue();
            list.add(new Pair<>(item.getKey(), amount));
        }
        return list;
    }

    // getAvailableItems hands a computer one wrapped object per item type, which runs an OpenComputers machine out of
    // memory on a large network. getAvailableItemsPage returns the same stock as plain text, a page at a time, from a
    // sorted copy that one listing reuses.
    private static final long STOCK_CACHE_MILLIS = 60_000;
    private long[] stockKeys;
    private long[] stockCounts;
    private long stockTime;

    private static long stockKey(long id, long data) {
        return (id << 32) | (data & 0xFFFFFFFFL);
    }

    private void refreshStockCache() {
        Map<ItemIdentifier, Integer> items = SimpleServiceLocator.logisticsManager
                .getAvailableItems(getRouter().getIRoutersByCost());
        // Items that differ only by NBT share a key and are added together.
        TreeMap<Long, Long> sorted = new TreeMap<>();
        for (Entry<ItemIdentifier, Integer> item : items.entrySet()) {
            ItemIdentifier id = item.getKey();
            sorted.merge(stockKey(Item.getIdFromItem(id.item), id.itemDamage), (long) item.getValue(), Long::sum);
        }
        stockKeys = new long[sorted.size()];
        stockCounts = new long[sorted.size()];
        int i = 0;
        for (Entry<Long, Long> entry : sorted.entrySet()) {
            stockKeys[i] = entry.getKey();
            stockCounts[i] = entry.getValue();
            i++;
        }
        stockTime = System.currentTimeMillis();
    }

    @CCCommand(
            description = "Returns up to count available items after the given item ID and data (afterId -1 starts a new listing) as lines \"id data count\", sorted by ID then data")
    @CCQueued
    public String getAvailableItemsPage(Number afterId, Number afterData, Number count) {
        boolean start = afterId.longValue() < 0;
        if (start || stockKeys == null || System.currentTimeMillis() - stockTime > STOCK_CACHE_MILLIS) {
            refreshStockCache();
        }
        int from = 0;
        if (!start) {
            int pos = Arrays.binarySearch(stockKeys, stockKey(afterId.longValue(), afterData.longValue()));
            from = pos >= 0 ? pos + 1 : -pos - 1;
        }
        int to = (int) Math.min(stockKeys.length, from + Math.max(1, Math.min(count.longValue(), 10000)));
        StringBuilder page = new StringBuilder();
        for (int i = from; i < to; i++) {
            page.append(stockKeys[i] >>> 32).append(' ').append((int) stockKeys[i]).append(' ')
                    .append(stockCounts[i]).append('\n');
        }
        return page.toString();
    }

    @CCCommand(description = "Asks for all craftable ItemIdentifier inside the Logistics Network")
    @CCQueued
    public List<ItemIdentifier> getCraftableItems() {
        return SimpleServiceLocator.logisticsManager.getCraftableItems(getRouter().getIRoutersByCost());
    }

    @CCCommand(description = "Asks for the amount of an ItemIdentifier Id inside the Logistics Network")
    @CCQueued
    public int getItemAmount(ItemIdentifier item) throws Exception {
        Map<ItemIdentifier, Integer> items = SimpleServiceLocator.logisticsManager
                .getAvailableItems(getRouter().getIRoutersByCost());
        if (item == null) {
            throw new Exception("Invalid ItemIdentifierID");
        }
        if (items.containsKey(item)) {
            return items.get(item);
        }
        return 0;
    }
}
