package logisticspipes.modules;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.DelayQueue;
import java.util.function.Predicate;

import net.minecraft.block.Block;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.renderer.texture.IIconRegister;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.IIcon;
import net.minecraft.world.World;
import net.minecraftforge.common.util.Constants;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.fluids.FluidStack;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import logisticspipes.blocks.crafting.LogisticsCraftingTableTileEntity;
import logisticspipes.interfaces.IHUDModuleHandler;
import logisticspipes.interfaces.IHUDModuleRenderer;
import logisticspipes.interfaces.IInventoryUtil;
import logisticspipes.interfaces.IModuleInventoryReceive;
import logisticspipes.interfaces.IModuleWatchReciver;
import logisticspipes.interfaces.IPipeServiceProvider;
import logisticspipes.interfaces.ISlotUpgradeManager;
import logisticspipes.interfaces.IWorldProvider;
import logisticspipes.interfaces.routing.IAdditionalTargetInformation;
import logisticspipes.interfaces.routing.ICraftItems;
import logisticspipes.interfaces.routing.IFilter;
import logisticspipes.interfaces.routing.IGatedItemSink;
import logisticspipes.interfaces.routing.IItemSpaceControl;
import logisticspipes.interfaces.routing.IRequest;
import logisticspipes.interfaces.routing.IRequestFluid;
import logisticspipes.interfaces.routing.IRequestItems;
import logisticspipes.items.ItemUpgrade;
import logisticspipes.logistics.LogisticsManager;
import logisticspipes.logisticspipes.IRoutedItem;
import logisticspipes.logisticspipes.IRoutedItem.TransportMode;
import logisticspipes.modules.abstractmodules.LogisticsGuiModule;
import logisticspipes.modules.abstractmodules.LogisticsModule;
import logisticspipes.network.NewGuiHandler;
import logisticspipes.network.PacketHandler;
import logisticspipes.network.abstractguis.ModuleCoordinatesGuiProvider;
import logisticspipes.network.abstractguis.ModuleInHandGuiProvider;
import logisticspipes.network.abstractpackets.CoordinatesPacket;
import logisticspipes.network.abstractpackets.ModernPacket;
import logisticspipes.network.guis.module.inhand.CraftingModuleInHand;
import logisticspipes.network.guis.module.inpipe.CraftingModuleSlot;
import logisticspipes.network.packets.block.CraftingPipeNextAdvancedSatellitePacket;
import logisticspipes.network.packets.block.CraftingPipePrevAdvancedSatellitePacket;
import logisticspipes.network.packets.cpipe.CPipeNextSatellite;
import logisticspipes.network.packets.cpipe.CPipePrevSatellite;
import logisticspipes.network.packets.cpipe.CPipeSatelliteId;
import logisticspipes.network.packets.cpipe.CPipeSatelliteImport;
import logisticspipes.network.packets.cpipe.CPipeSatelliteImportBack;
import logisticspipes.network.packets.cpipe.CraftingAdvancedSatelliteId;
import logisticspipes.network.packets.cpipe.CraftingPipeOpenConnectedGuiPacket;
import logisticspipes.network.packets.hud.HUDStartModuleWatchingPacket;
import logisticspipes.network.packets.hud.HUDStopModuleWatchingPacket;
import logisticspipes.network.packets.module.ModuleInventory;
import logisticspipes.network.packets.pipe.CraftingPipePriorityDownPacket;
import logisticspipes.network.packets.pipe.CraftingPipePriorityUpPacket;
import logisticspipes.network.packets.pipe.CraftingPipeUpdatePacket;
import logisticspipes.network.packets.pipe.CraftingPriority;
import logisticspipes.network.packets.pipe.FluidCraftingAdvancedSatelliteId;
import logisticspipes.network.packets.pipe.FluidCraftingAmount;
import logisticspipes.network.packets.pipe.FluidCraftingPipeAdvancedSatelliteNextPacket;
import logisticspipes.network.packets.pipe.FluidCraftingPipeAdvancedSatellitePrevPacket;
import logisticspipes.pipefxhandlers.Particles;
import logisticspipes.pipes.PipeFluidSatellite;
import logisticspipes.pipes.PipeItemsCraftingLogistics;
import logisticspipes.pipes.PipeItemsSatelliteLogistics;
import logisticspipes.pipes.PipeLogisticsChassi.ChassiTargetInformation;
import logisticspipes.pipes.basic.CoreRoutedPipe;
import logisticspipes.pipes.basic.CoreRoutedPipe.ItemSendMode;
import logisticspipes.proxy.MainProxy;
import logisticspipes.proxy.SimpleServiceLocator;
import logisticspipes.proxy.interfaces.ICraftingRecipeProvider;
import logisticspipes.proxy.interfaces.IFuzzyRecipeProvider;
import logisticspipes.request.DictCraftingTemplate;
import logisticspipes.request.ICraftingTemplate;
import logisticspipes.request.IPromise;
import logisticspipes.request.IReqCraftingTemplate;
import logisticspipes.request.ItemCraftingTemplate;
import logisticspipes.request.RequestTree;
import logisticspipes.request.RequestTreeNode;
import logisticspipes.request.resources.DictResource;
import logisticspipes.request.resources.FluidResource;
import logisticspipes.request.resources.IResource;
import logisticspipes.request.resources.ItemResource;
import logisticspipes.routing.ExitRoute;
import logisticspipes.routing.IRouter;
import logisticspipes.routing.LogisticsDictPromise;
import logisticspipes.routing.LogisticsExtraDictPromise;
import logisticspipes.routing.LogisticsExtraPromise;
import logisticspipes.routing.LogisticsPromise;
import logisticspipes.routing.order.CraftingJob;
import logisticspipes.routing.order.CraftingJobs;
import logisticspipes.routing.order.IOrderInfoProvider.ResourceType;
import logisticspipes.routing.order.LogisticsItemOrder;
import logisticspipes.routing.order.LogisticsItemOrderManager;
import logisticspipes.utils.AdjacentTile;
import logisticspipes.utils.CacheHolder.CacheTypes;
import logisticspipes.utils.DelayedGeneric;
import logisticspipes.utils.FluidIdentifier;
import logisticspipes.utils.ISimpleInventoryEventHandler;
import logisticspipes.utils.PlayerCollectionList;
import logisticspipes.utils.SidedInventoryMinecraftAdapter;
import logisticspipes.utils.SinkReply;
import logisticspipes.utils.SinkReply.BufferMode;
import logisticspipes.utils.SinkReply.FixedPriority;
import logisticspipes.utils.WorldUtil;
import logisticspipes.utils.item.ItemIdentifier;
import logisticspipes.utils.item.ItemIdentifierInventory;
import logisticspipes.utils.item.ItemIdentifierStack;
import logisticspipes.utils.string.StringUtils;
import logisticspipes.utils.tuples.Pair;
import lombok.Getter;

public class ModuleCrafter extends LogisticsGuiModule implements ICraftItems, IHUDModuleHandler, IModuleWatchReciver,
        ISimpleInventoryEventHandler, IModuleInventoryReceive {

    private IRequestItems _invRequester;
    // private ForgeDirection _sneakyDirection = ForgeDirection.UNKNOWN;

    public int satelliteId = 0;
    public int[] advancedSatelliteIdArray = new int[9];
    public DictResource[] fuzzyCraftingFlagArray = new DictResource[9];
    public DictResource outputFuzzyFlags = new DictResource(null, null);
    public int priority = 0;

    // from PipeItemsCraftingLogistics
    protected ItemIdentifierInventory _dummyInventory = new ItemIdentifierInventory(
            11,
            StringUtils.translate("gui.module.requestedItems"),
            127);
    protected ItemIdentifierInventory _liquidInventory = new ItemIdentifierInventory(
            ItemUpgrade.MAX_LIQUID_CRAFTER,
            "Fluid items",
            1,
            true);
    protected ItemIdentifierInventory _cleanupInventory = new ItemIdentifierInventory(
            ItemUpgrade.MAX_CRAFTING_CLEANUP * 3,
            "Cleanup Filer Items",
            1);

    protected int[] amount = new int[ItemUpgrade.MAX_LIQUID_CRAFTER];
    public int[] liquidSatelliteIdArray = new int[ItemUpgrade.MAX_LIQUID_CRAFTER];
    public int liquidSatelliteId = 0;

    public boolean[] craftingSigns = new boolean[6];
    public boolean waitingForCraft = false;

    private WeakReference<TileEntity> lastAccessedCrafter = new WeakReference<>(null);

    public boolean cleanupModeIsExclude = true;
    // for reliable transport
    protected final DelayQueue<DelayedGeneric<LostItem>> _lostItems = new DelayQueue<>();

    protected final PlayerCollectionList localModeWatchers = new PlayerCollectionList();

    public ModuleCrafter() {
        for (int i = 0; i < fuzzyCraftingFlagArray.length; i++) {
            fuzzyCraftingFlagArray[i] = new DictResource(null, null);
        }
    }

    public ModuleCrafter(PipeItemsCraftingLogistics parent) {
        _dummyInventory.addListener(this);
        _service = parent;
        _invRequester = parent;
        _world = parent;
        registerPosition(ModulePositionType.IN_PIPE, 0);
        for (int i = 0; i < fuzzyCraftingFlagArray.length; i++) {
            fuzzyCraftingFlagArray[i] = new DictResource(null, null);
        }
    }

    /**
     * assumes that the invProvider is also IRequest items.
     */
    @Override
    public void registerHandler(IWorldProvider world, IPipeServiceProvider service) {
        super.registerHandler(world, service);
        _invRequester = (IRequestItems) service;
    }

    protected SinkReply _sinkReply;

    @Override
    public void registerPosition(ModulePositionType slot, int positionInt) {
        super.registerPosition(slot, positionInt);
        _sinkReply = new SinkReply(
                FixedPriority.ItemSink,
                0,
                true,
                false,
                1,
                0,
                new ChassiTargetInformation(getPositionInt()));
    }

    @Override
    public SinkReply sinksItem(ItemIdentifier item, int bestPriority, int bestCustomPriority, boolean allowDefault,
            boolean includeInTransit) {
        if (bestPriority > _sinkReply.fixedPriority.ordinal() || (bestPriority == _sinkReply.fixedPriority.ordinal()
                && bestCustomPriority >= _sinkReply.customPriority)) {
            return null;
        }
        return new SinkReply(
                _sinkReply,
                spaceFor(item, includeInTransit),
                areAllOrderesToBuffer() ? BufferMode.DESTINATION_BUFFERED : BufferMode.NONE);
    }

    protected int spaceFor(ItemIdentifier item, boolean includeInTransit) {
        Pair<String, ItemIdentifier> key = new Pair<>("spaceFor", item);
        Object cache = _service.getCacheHolder().getCacheFor(CacheTypes.Inventory, key);
        if (cache != null) {
            int count = (Integer) cache;
            if (includeInTransit) {
                count -= _service.countOnRoute(item);
            }
            return count;
        }
        int count = 0;
        WorldUtil wUtil = new WorldUtil(getWorld(), _service.getX(), _service.getY(), _service.getZ());
        for (AdjacentTile tile : wUtil.getAdjacentTileEntities(true)) {
            if (!(tile.tile instanceof IInventory)) {
                continue;
            }
            IInventory base = (IInventory) tile.tile;
            if (base instanceof net.minecraft.inventory.ISidedInventory) {
                base = new SidedInventoryMinecraftAdapter(
                        (net.minecraft.inventory.ISidedInventory) base,
                        tile.orientation.getOpposite(),
                        false);
            }
            ForgeDirection dir = tile.orientation;
            if (getUpgradeManager().hasSneakyUpgrade()) {
                dir = getUpgradeManager().getSneakyOrientation();
            }
            IInventoryUtil inv = SimpleServiceLocator.inventoryUtilFactory.getInventoryUtil(base, dir);
            count += inv.roomForItem(item, 9999);
        }
        _service.getCacheHolder().setCache(CacheTypes.Inventory, key, count);
        if (includeInTransit) {
            count -= _service.countOnRoute(item);
        }
        return count;
    }

    public int getPriority() {
        return priority;
    }

    @Override
    public LogisticsModule getSubModule(int slot) {
        return null;
    }

    public void onAllowedRemoval() {}

    @Override
    public void tick() {
        enabledUpdateEntity();
        if (_lostItems.isEmpty()) {
            return;
        }
        // if(true) return;
        DelayedGeneric<LostItem> lostItem = _lostItems.poll();
        int rerequested = 0;
        while (lostItem != null && rerequested < 100) {
            LostItem lost = lostItem.get();
            if (lost.job != null && lost.job.isCancelled()) {
                // The request it was for is gone: asking again would only make ingredients nobody wants.
                lostItem = _lostItems.poll();
                continue;
            }
            // A finished job may still be owed it: crafters hand results to whichever order is first, so the job could
            // have finished on another job's ingredients, leaving that job a set short. Ask again under a job of its
            // own.
            CraftingJob job = lost.job != null && lost.job.isDone() ? null : lost.job;
            if (_service.getItemOrderManager().hasOrders(ResourceType.CRAFTING)) {
                SinkReply reply = LogisticsManager
                        .canSink(getRouter(), null, true, lost.stack.getItem(), null, true, true);
                if (reply == null || reply.maxNumberOfItems < 1) {
                    _lostItems.add(new DelayedGeneric<>(lost, 9000 + (int) (Math.random() * 2000)));
                    lostItem = _lostItems.poll();
                    continue;
                }
            }
            int received;
            CraftingJob previous = CraftingJobs.enter(job);
            try {
                received = RequestTree.requestPartial(lost.stack, lostItemTarget(lost.info), lost.info);
            } finally {
                CraftingJobs.exit(previous);
            }
            rerequested++;
            if (received < lost.stack.getStackSize()) {
                lost.stack.setStackSize(lost.stack.getStackSize() - received);
                _lostItems.add(new DelayedGeneric<>(lost, 4500 + (int) (Math.random() * 1000)));
            }
            lostItem = _lostItems.poll();
        }
        // The loop polls before re-testing its condition, so exiting on the rerequested cap
        // leaves an already-polled item in hand that was never processed. Put it back.
        if (lostItem != null) {
            _lostItems.add(new DelayedGeneric<>(lostItem.get(), 9000 + (int) (Math.random() * 2000)));
        }
    }

    /**
     * An ingredient to ask for again, and the job it was for when that is known (a failed delivery says so, and so does
     * an item lost in transit or bounced off a full machine if an order sent it). The re-request joins that job, and is
     * dropped if the job was cancelled meanwhile.
     */
    protected static final class LostItem {

        final ItemIdentifierStack stack;
        final IAdditionalTargetInformation info;
        final CraftingJob job;

        LostItem(ItemIdentifierStack stack, IAdditionalTargetInformation info, CraftingJob job) {
            this.stack = stack;
            this.info = info;
            this.job = job;
        }
    }

    /** Where a lost ingredient is asked for again: this module's chassis, which is where it always went. */
    protected IRequestItems lostItemTarget(IAdditionalTargetInformation info) {
        return (CoreRoutedPipe) _service;
    }

    /** The target information an ingredient slot's requests carry, so arrivals and losses find their slot. */
    protected IAdditionalTargetInformation ingredientInformation(int slot) {
        return new CraftingChassieInformation(slot, getPositionInt());
    }

    @Override
    public void itemArrived(ItemIdentifierStack item, IAdditionalTargetInformation info) {}

    @Override
    public void itemLost(ItemIdentifierStack item, IAdditionalTargetInformation info) {
        _lostItems.add(new DelayedGeneric<>(new LostItem(item, info, CraftingJobs.current()), 5000));
    }

    @Override
    public boolean hasGenericInterests() {
        return false;
    }

    @Override
    public Set<ItemIdentifier> getSpecificInterests() {
        List<ItemIdentifierStack> result = getConfiguredCraftResults();
        if (result == null) {
            return null;
        }
        Set<ItemIdentifier> l1 = new TreeSet<>();
        for (ItemIdentifierStack craftable : result) {
            l1.add(craftable.getItem());
        }
        /*
         * for(int i=0; i<9;i++) { ItemIdentifierStack stack = getMaterials(i); if(stack != null) {
         * l1.add(stack.getItem()); // needed to be interested in things for a chassi to report reliableDelivery
         * failure. } }
         */
        return l1;
    }

    @Override
    public boolean interestedInAttachedInventory() {
        return false;
        // when we are default we are interested in everything anyway, otherwise we're only interested in our filter.
    }

    @Override
    public boolean interestedInUndamagedID() {
        return false;
    }

    @Override
    public boolean recievePassive() {
        return false;
    }

    @Override
    @SideOnly(Side.CLIENT)
    public IIcon getIconTexture(IIconRegister register) {
        return register.registerIcon("logisticspipes:itemModule/ModuleCrafter");
    }

    @Override
    public void canProvide(RequestTreeNode tree, RequestTree root, List<IFilter> filters) {
        if (!_service.getItemOrderManager().hasExtras() || tree.hasBeenQueried(_service.getItemOrderManager())) {
            return;
        }

        IResource requestedItem = tree.getRequestType();

        if (!canCraft(requestedItem)) {
            return;
        }

        for (IFilter filter : filters) {
            if (filter.isBlocked() == filter.isFilteredItem(requestedItem) || filter.blockProvider()) {
                return;
            }
        }
        // A chassis shares one order queue, so its extras can come from several of its recipes. Each recipe's extras
        // are offered by that recipe's own module: the order that spends them is then that recipe's, and only its
        // sets have to make them. Offered as one lot from whichever module was asked first, the order could only be
        // credited to one recipe, which would then wait for a set nobody sent ingredients for.
        Map<ModuleCrafter, Integer> extrasByMaker = new LinkedHashMap<>();
        for (LogisticsItemOrder extra : _service.getItemOrderManager()) {
            if (extra.getType() == ResourceType.EXTRA) {
                if (extra.getResource().getItem().equals(requestedItem)) {
                    extrasByMaker.merge(
                            extraMakerHere(extra.getMaker(), requestedItem),
                            extra.getResource().stack.getStackSize(),
                            Integer::sum);
                }
            }
        }
        for (Map.Entry<ModuleCrafter, Integer> entry : extrasByMaker.entrySet()) {
            if (tree.getMissingAmount() <= 0) {
                break;
            }
            entry.getKey().offerExtras(tree, root, requestedItem, entry.getValue());
        }
        tree.setQueried(_service.getItemOrderManager());
    }

    /**
     * The module of this chassis that made an extra and offers it. An extra with no maker recorded, or whose maker was
     * since taken out of the chassis or no longer makes the item, falls to this module, as all extras did before.
     */
    private ModuleCrafter extraMakerHere(ModuleCrafter maker, IResource requestedItem) {
        if (maker == null || maker == this
                || maker._service == null
                || maker._service.getItemOrderManager() != _service.getItemOrderManager()
                || maker.resultFor(requestedItem) == null) {
            return this;
        }
        return maker;
    }

    /**
     * The result that was asked for, which is not always the first one: a module with several results answers for any
     * of them. Null if this module doesn't make it.
     */
    private ItemIdentifierStack resultFor(IResource requestedItem) {
        for (ItemIdentifierStack candidate : getConfiguredCraftResults()) {
            if (candidate != null && requestedItem.matches(candidate.getItem(), IResource.MatchSettings.NORMAL)) {
                return candidate;
            }
        }
        return null;
    }

    /** Offers this module's extras of the requested item, as this module's promise. */
    private void offerExtras(RequestTreeNode tree, RequestTree root, IResource requestedItem, int extras) {
        ItemIdentifierStack result = resultFor(requestedItem);
        if (result == null) {
            return;
        }
        int remaining = extras - root.getAllPromissesFor(this, result.getItem());
        if (remaining < 1) {
            return;
        }
        if (this.getUpgradeManager().isFuzzyUpgrade() && outputFuzzyFlags.getBitSet().nextSetBit(0) != -1) {
            DictResource dict = new DictResource(result, null).loadFromBitSet(outputFuzzyFlags.getBitSet());
            LogisticsExtraDictPromise promise = new LogisticsExtraDictPromise(
                    dict,
                    Math.min(remaining, tree.getMissingAmount()),
                    this,
                    true);
            tree.addPromise(promise);
        } else {
            LogisticsExtraPromise promise = new LogisticsExtraPromise(
                    result.getItem(),
                    Math.min(remaining, tree.getMissingAmount()),
                    this,
                    true);
            tree.addPromise(promise);
        }
    }

    @Override
    public LogisticsItemOrder fullFill(LogisticsPromise promise, IRequestItems destination,
            IAdditionalTargetInformation info) {
        // Spending another request's surplus: remember whose, so cancelling that request can plan this part again.
        // Only this module's own extras: canProvide offers each recipe's extras from that recipe's module.
        Map<CraftingJob, Integer> borrowed = null;
        DictResource extras = null;
        if (promise instanceof LogisticsExtraDictPromise) {
            extras = ((LogisticsExtraDictPromise) promise).getResource();
        } else if (promise instanceof LogisticsExtraPromise) {
            extras = new DictResource(new ItemIdentifierStack(promise.item, promise.numberOfItems), null);
        }
        if (extras != null) {
            DictResource spent = extras;
            borrowed = _service.getItemOrderManager()
                    .removeExtras(spent, extra -> extraMakerHere(extra.getMaker(), spent) == this);
        }
        LogisticsItemOrder order;
        if (promise instanceof LogisticsDictPromise) {
            order = _service.getItemOrderManager()
                    .addOrder(((LogisticsDictPromise) promise).getResource(), destination, ResourceType.CRAFTING, info);
        } else {
            order = _service.getItemOrderManager().addOrder(
                    new ItemIdentifierStack(promise.item, promise.numberOfItems),
                    destination,
                    ResourceType.CRAFTING,
                    info);
        }
        order.addBorrowed(borrowed);
        order.setMaker(this);
        _service.spawnParticle(Particles.WhiteParticle, 2);
        return order;
    }

    @Override
    public void getAllItems(Map<ItemIdentifier, Integer> list, List<IFilter> filter) {}

    @Override
    public IRouter getRouter() {
        return _service.getRouter();
    }

    @Override
    public void itemCouldNotBeSend(ItemIdentifierStack item, IAdditionalTargetInformation info) {
        _invRequester.itemCouldNotBeSend(item, info);
    }

    @Override
    public int getID() {
        return _service.getRouter().getSimpleID();
    }

    @Override
    public int compareTo(IRequest request) {
        return 0;
    }

    @Override
    public void registerExtras(IPromise promise) {
        LogisticsItemOrder extra;
        if (promise instanceof LogisticsDictPromise) {
            extra = _service.getItemOrderManager().addExtra(((LogisticsDictPromise) promise).getResource());
        } else {
            ItemIdentifierStack stack = new ItemIdentifierStack(promise.getItemType(), promise.getAmount());
            extra = _service.getItemOrderManager().addExtra(new DictResource(stack, null));
        }
        extra.setMaker(this);
    }

    @Override
    public ICraftingTemplate addCrafting(IResource toCraft) {

        IReqCraftingTemplate template = createTemplateFor(toCraft);
        if (template == null) {
            return null;
        }
        return fillTemplate(template);
    }

    /**
     * Builds the template for a result this module makes, or null if it doesn't make it. Split out so a subclass can
     * answer for a result that isn't an item.
     */
    protected IReqCraftingTemplate createTemplateFor(IResource toCraft) {
        List<ItemIdentifierStack> stack = getConfiguredCraftResults();
        if (stack == null) {
            return null;
        }
        IReqCraftingTemplate template = null;
        if (this.getUpgradeManager().isFuzzyUpgrade() && outputFuzzyFlags.getBitSet().nextSetBit(0) != -1) {
            if (toCraft instanceof DictResource) {
                for (ItemIdentifierStack craftable : stack) {
                    DictResource dict = new DictResource(craftable, null);
                    dict.loadFromBitSet(outputFuzzyFlags.getBitSet());
                    if (toCraft.matches(craftable.getItem(), IResource.MatchSettings.NORMAL)
                            && dict.matches(((DictResource) toCraft).getItem(), IResource.MatchSettings.NORMAL)
                            && dict.getBitSet().equals(((DictResource) toCraft).getBitSet())) {
                        template = new DictCraftingTemplate(dict, this, priority);
                        break;
                    }
                }
            }
        } else {
            for (ItemIdentifierStack craftable : stack) {
                if (toCraft.matches(craftable.getItem(), IResource.MatchSettings.NORMAL)) {
                    template = new ItemCraftingTemplate(craftable, this, priority);
                    break;
                }
            }
        }
        return template;
    }

    /** Adds this module's ingredients and byproducts to a template, whatever its result is. */
    private IReqCraftingTemplate fillTemplate(IReqCraftingTemplate template) {
        IRequestItems[] target = new IRequestItems[9];
        for (int i = 0; i < 9; i++) {
            target[i] = this;
        }

        boolean hasSatellite = isSatelliteConnected();
        if (!hasSatellite) {
            return null;
        }
        if (!usesPerSlotSatellites()) {
            if (satelliteId != 0) {
                IRouter r = getSatelliteRouter(-1);
                if (r != null) {
                    IRequestItems sat = r.getPipe();
                    for (int i = 6; i < 9; i++) {
                        target[i] = sat;
                    }
                }
            }
        } else {
            for (int i = 0; i < 9; i++) {
                if (advancedSatelliteIdArray[i] != 0) {
                    IRouter r = getSatelliteRouter(i);
                    if (r != null) {
                        target[i] = r.getPipe();
                    }
                }
            }
        }

        // Check all materials
        for (int i = 0; i < 9; i++) {
            ItemIdentifierStack resourceStack = getMaterials(i);
            if (resourceStack == null || resourceStack.getStackSize() == 0) {
                continue;
            }
            if (addIngredientForSlot(template, i)) {
                continue;
            }
            template.addIngredient(ingredientResource(i, resourceStack, target[i]), ingredientInformation(i));
        }

        int liquidCrafter = getUpgradeManager().getFluidCrafter();
        IRequestFluid[] liquidTarget = new IRequestFluid[liquidCrafter];

        if (!getUpgradeManager().isAdvancedSatelliteCrafter()) {
            if (liquidSatelliteId != 0) {
                IRouter r = getFluidSatelliteRouter(-1);
                if (r != null) {
                    IRequestFluid sat = (IRequestFluid) r.getPipe();
                    for (int i = 0; i < liquidCrafter; i++) {
                        liquidTarget[i] = sat;
                    }
                }
            }
        } else {
            for (int i = 0; i < liquidCrafter; i++) {
                if (liquidSatelliteIdArray[i] != 0) {
                    IRouter r = getFluidSatelliteRouter(i);
                    if (r != null) {
                        liquidTarget[i] = (IRequestFluid) r.getPipe();
                    }
                }
            }
        }

        for (int i = 0; i < liquidCrafter; i++) {
            FluidIdentifier liquid = getFluidMaterial(i);
            int amount = getFluidAmount()[i];
            if (liquid == null || amount <= 0 || liquidTarget[i] == null) {
                continue;
            }
            template.addIngredient(new FluidResource(liquid, amount, liquidTarget[i]), null);
        }

        addTemplateByproducts(template);

        return template;
    }

    /**
     * What the planner asks for to fill an item ingredient slot: the exact item, or a fuzzy match when the slot has
     * fuzzy flags set. Split out so a subclass can loosen the match for its own slots.
     */
    protected IResource ingredientResource(int slot, ItemIdentifierStack resourceStack, IRequestItems target) {
        if (getUpgradeManager().isFuzzyUpgrade() && fuzzyCraftingFlagArray[slot].getBitSet().nextSetBit(0) != -1) {
            DictResource dict = new DictResource(resourceStack, target);
            dict.loadFromBitSet(fuzzyCraftingFlagArray[slot].getBitSet());
            return dict;
        }
        return new ItemResource(resourceStack, target);
    }

    /**
     * Registers the results this module produces on the side, which the planner may spend within the same request but
     * never starts a craft for.
     */
    protected void addTemplateByproducts(IReqCraftingTemplate template) {
        if (getUpgradeManager().hasByproductExtractor() && getByproductItem() != null) {
            template.addByproduct(getByproductItem());
        }
    }

    /**
     * Lets a subclass take an ingredient slot over, e.g. as a fluid rather than as an item.
     *
     * @return true if the slot was added to the template here, so the item path below skips it
     */
    protected boolean addIngredientForSlot(IReqCraftingTemplate template, int slot) {
        return false;
    }

    /**
     * Whether every ingredient slot has its own satellite id. Otherwise one id covers slots 6-8.
     */
    protected boolean usesPerSlotSatellites() {
        return getUpgradeManager().isAdvancedSatelliteCrafter();
    }

    protected ISlotUpgradeManager getUpgradeManager() {
        if (_service == null) {
            return null;
        }
        return _service.getUpgradeManager(slot, positionInt);
    }

    public boolean isSatelliteConnected() {
        final List<ExitRoute> routes = getRouter().getIRoutersByCost();
        if (!usesPerSlotSatellites()) {
            if (satelliteId == 0) {
                return true;
            }
            for (final PipeItemsSatelliteLogistics satellite : PipeItemsSatelliteLogistics.AllSatellites) {
                if (satellite.satelliteId == satelliteId) {
                    if (satellite.stillNeedReplace() || satellite.getRouter() == null) {
                        continue;
                    }
                    IRouter satRouter = satellite.getRouter();
                    for (ExitRoute route : routes) {
                        if (route.destination == satRouter) {
                            return true;
                        }
                    }
                }
            }
        } else {
            boolean foundAll = true;
            for (int i = 0; i < 9; i++) {
                boolean foundOne = false;
                if (advancedSatelliteIdArray[i] == 0) {
                    continue;
                }
                for (final PipeItemsSatelliteLogistics satellite : PipeItemsSatelliteLogistics.AllSatellites) {
                    if (satellite.satelliteId == advancedSatelliteIdArray[i]) {
                        if (satellite.stillNeedReplace() || satellite.getRouter() == null) {
                            continue;
                        }
                        IRouter satRouter = satellite.getRouter();
                        for (ExitRoute route : routes) {
                            if (route.destination == satRouter) {
                                foundOne = true;
                                break;
                            }
                        }
                    }
                }
                foundAll &= foundOne;
            }
            return foundAll;
        }
        // TODO check for FluidCrafter
        return false;
    }

    @Override
    public boolean canCraft(IResource toCraft) {
        if (getConfiguredCraftResult() == null) {
            return false;
        }
        if (toCraft instanceof ItemResource || toCraft instanceof DictResource) {
            return toCraft.matches(getConfiguredCraftResult().getItem(), IResource.MatchSettings.NORMAL);
        }
        return false;
    }

    @Override
    public List<ItemIdentifierStack> getConfiguredCraftResults() {
        List<ItemIdentifierStack> list = new ArrayList<>(1);
        if (getConfiguredCraftResult() != null) {
            list.add(getConfiguredCraftResult());
        }
        return list;
    }

    public ItemIdentifierStack getConfiguredCraftResult() {
        return _dummyInventory.getIDStackInSlot(9);
    }

    @Override
    public int getTodo() {
        return _service.getItemOrderManager().totalAmountCountInAllOrders();
    }

    protected int getNextConnectSatelliteId(boolean prev, int x) {
        int closestIdFound = prev ? 0 : Integer.MAX_VALUE;
        if (_service == null) {
            return prev ? Math.max(0, satelliteId - 1) : satelliteId + 1;
        }
        for (final PipeItemsSatelliteLogistics satellite : PipeItemsSatelliteLogistics.AllSatellites) {
            if (satellite == null || satellite.stillNeedReplace()
                    || satellite.getRouter() == null
                    || satellite.isFluidPipe()) {
                continue;
            }
            IRouter satRouter = satellite.getRouter();
            List<ExitRoute> routes = getRouter().getDistanceTo(satRouter);
            if (routes != null && !routes.isEmpty()) {
                boolean filterFree = false;
                for (ExitRoute route : routes) {
                    if (route.filters.isEmpty()) {
                        filterFree = true;
                        break;
                    }
                }
                if (!filterFree) {
                    continue;
                }
                if (x == -1) {
                    if (!prev && satellite.satelliteId > satelliteId && satellite.satelliteId < closestIdFound) {
                        closestIdFound = satellite.satelliteId;
                    } else if (prev && satellite.satelliteId < satelliteId && satellite.satelliteId > closestIdFound) {
                        closestIdFound = satellite.satelliteId;
                    }
                } else {
                    if (!prev && satellite.satelliteId > advancedSatelliteIdArray[x]
                            && satellite.satelliteId < closestIdFound) {
                        closestIdFound = satellite.satelliteId;
                    } else if (prev && satellite.satelliteId < advancedSatelliteIdArray[x]
                            && satellite.satelliteId > closestIdFound) {
                                closestIdFound = satellite.satelliteId;
                            }
                }
            }
        }
        if (closestIdFound == Integer.MAX_VALUE) {
            if (x == -1) {
                return satelliteId;
            } else {
                return advancedSatelliteIdArray[x];
            }
        }
        return closestIdFound;
    }

    protected int getNextConnectFluidSatelliteId(boolean prev, int x) {
        int closestIdFound = prev ? 0 : Integer.MAX_VALUE;
        for (final PipeFluidSatellite satellite : PipeFluidSatellite.AllSatellites) {
            if (satellite == null || satellite.stillNeedReplace()
                    || satellite.getRouter() == null
                    || !((CoreRoutedPipe) satellite).isFluidPipe()) {
                continue;
            }
            IRouter satRouter = satellite.getRouter();
            List<ExitRoute> routes = getRouter().getDistanceTo(satRouter);
            if (routes != null && !routes.isEmpty()) {
                boolean filterFree = false;
                for (ExitRoute route : routes) {
                    if (route.filters.isEmpty()) {
                        filterFree = true;
                        break;
                    }
                }
                if (!filterFree) {
                    continue;
                }
                if (x == -1) {
                    if (!prev && satellite.satelliteId > liquidSatelliteId && satellite.satelliteId < closestIdFound) {
                        closestIdFound = satellite.satelliteId;
                    } else if (prev && satellite.satelliteId < liquidSatelliteId
                            && satellite.satelliteId > closestIdFound) {
                                closestIdFound = satellite.satelliteId;
                            }
                } else {
                    if (!prev && satellite.satelliteId > liquidSatelliteIdArray[x]
                            && satellite.satelliteId < closestIdFound) {
                        closestIdFound = satellite.satelliteId;
                    } else if (prev && satellite.satelliteId < liquidSatelliteIdArray[x]
                            && satellite.satelliteId > closestIdFound) {
                                closestIdFound = satellite.satelliteId;
                            }
                }
            }
        }
        if (closestIdFound == Integer.MAX_VALUE) {
            if (x == -1) {
                return liquidSatelliteId;
            } else {
                return liquidSatelliteIdArray[x];
            }
        }
        return closestIdFound;
    }

    public void setNextSatellite(EntityPlayer player) {
        if (MainProxy.isClient(player.worldObj)) {
            final CoordinatesPacket packet = PacketHandler.getPacket(CPipeNextSatellite.class).setModulePos(this);
            MainProxy.sendPacketToServer(packet);
        } else {
            satelliteId = getNextConnectSatelliteId(false, -1);
            final CoordinatesPacket packet = PacketHandler.getPacket(CPipeSatelliteId.class).setPipeId(satelliteId)
                    .setModulePos(this);
            MainProxy.sendPacketToPlayer(packet, player);
        }
    }

    // This is called by the packet PacketCraftingPipeSatelliteId
    public void setSatelliteId(int satelliteId, int x) {
        if (x == -1) {
            this.satelliteId = satelliteId;
        } else {
            advancedSatelliteIdArray[x] = satelliteId;
        }
    }

    public void setPrevSatellite(EntityPlayer player) {
        if (MainProxy.isClient(player.worldObj)) {
            final CoordinatesPacket packet = PacketHandler.getPacket(CPipePrevSatellite.class).setModulePos(this);
            MainProxy.sendPacketToServer(packet);
        } else {
            satelliteId = getNextConnectSatelliteId(true, -1);
            final CoordinatesPacket packet = PacketHandler.getPacket(CPipeSatelliteId.class).setPipeId(satelliteId)
                    .setModulePos(this);
            MainProxy.sendPacketToPlayer(packet, player);
        }
    }

    public IRouter getSatelliteRouter(int x) {
        if (x == -1) {
            for (final PipeItemsSatelliteLogistics satellite : PipeItemsSatelliteLogistics.AllSatellites) {
                if (satellite.satelliteId == satelliteId) {
                    if (satellite.stillNeedReplace() || satellite.getRouter() == null) {
                        continue;
                    }
                    return satellite.getRouter();
                }
            }
        } else {
            for (final PipeItemsSatelliteLogistics satellite : PipeItemsSatelliteLogistics.AllSatellites) {
                if (satellite.satelliteId == advancedSatelliteIdArray[x]) {
                    if (satellite.stillNeedReplace() || satellite.getRouter() == null) {
                        continue;
                    }
                    return satellite.getRouter();
                }
            }
        }
        return null;
    }

    @Override
    public void readFromNBT(NBTTagCompound nbttagcompound) {
        // super.readFromNBT(nbttagcompound);
        _dummyInventory.readFromNBT(nbttagcompound, "");
        _liquidInventory.readFromNBT(nbttagcompound, "FluidInv");
        _cleanupInventory.readFromNBT(nbttagcompound, "CleanupInv");
        satelliteId = nbttagcompound.getInteger("satelliteid");

        priority = nbttagcompound.getInteger("priority");
        for (int i = 0; i < 9; i++) {
            advancedSatelliteIdArray[i] = nbttagcompound.getInteger("advancedSatelliteId" + i);
        }
        if (nbttagcompound.hasKey("fuzzyCraftingFlag0")) {
            for (int i = 0; i < 9; i++) {
                int flags = nbttagcompound.getByte("fuzzyCraftingFlag" + i);
                DictResource dict = fuzzyCraftingFlagArray[i];
                if ((flags & 0x1) != 0) {
                    dict.use_od = true;
                }
                if ((flags & 0x2) != 0) {
                    dict.ignore_dmg = true;
                }
                if ((flags & 0x4) != 0) {
                    dict.ignore_nbt = true;
                }
                if ((flags & 0x8) != 0) {
                    dict.use_category = true;
                }
            }
        }
        if (nbttagcompound.hasKey("fuzzyFlags")) {
            NBTTagList lst = nbttagcompound.getTagList("fuzzyFlags", Constants.NBT.TAG_COMPOUND);
            for (int i = 0; i < 9; i++) {
                NBTTagCompound comp = lst.getCompoundTagAt(i);
                fuzzyCraftingFlagArray[i].ignore_dmg = comp.getBoolean("ignore_dmg");
                fuzzyCraftingFlagArray[i].ignore_nbt = comp.getBoolean("ignore_nbt");
                fuzzyCraftingFlagArray[i].use_od = comp.getBoolean("use_od");
                fuzzyCraftingFlagArray[i].use_category = comp.getBoolean("use_category");
            }
        }
        if (nbttagcompound.hasKey("outputFuzzyFlags")) {
            NBTTagCompound comp = nbttagcompound.getCompoundTag("outputFuzzyFlags");
            outputFuzzyFlags.ignore_dmg = comp.getBoolean("ignore_dmg");
            outputFuzzyFlags.ignore_nbt = comp.getBoolean("ignore_nbt");
            outputFuzzyFlags.use_od = comp.getBoolean("use_od");
            outputFuzzyFlags.use_category = comp.getBoolean("use_category");
        }
        for (int i = 0; i < 6; i++) {
            craftingSigns[i] = nbttagcompound.getBoolean("craftingSigns" + i);
        }
        if (nbttagcompound.hasKey("FluidAmount")) {
            amount = nbttagcompound.getIntArray("FluidAmount");
        }
        if (amount.length < ItemUpgrade.MAX_LIQUID_CRAFTER) {
            amount = new int[ItemUpgrade.MAX_LIQUID_CRAFTER];
        }
        for (int i = 0; i < ItemUpgrade.MAX_LIQUID_CRAFTER; i++) {
            liquidSatelliteIdArray[i] = nbttagcompound.getInteger("liquidSatelliteIdArray" + i);
        }
        for (int i = 0; i < ItemUpgrade.MAX_LIQUID_CRAFTER; i++) {
            liquidSatelliteIdArray[i] = nbttagcompound.getInteger("liquidSatelliteIdArray" + i);
        }
        liquidSatelliteId = nbttagcompound.getInteger("liquidSatelliteId");
        cleanupModeIsExclude = nbttagcompound.getBoolean("cleanupModeIsExclude");
    }

    @Override
    public void writeToNBT(NBTTagCompound nbttagcompound) {
        // super.writeToNBT(nbttagcompound);
        _dummyInventory.writeToNBT(nbttagcompound, "");
        _liquidInventory.writeToNBT(nbttagcompound, "FluidInv");
        _cleanupInventory.writeToNBT(nbttagcompound, "CleanupInv");
        nbttagcompound.setInteger("satelliteid", satelliteId);

        nbttagcompound.setInteger("priority", priority);
        for (int i = 0; i < 9; i++) {
            nbttagcompound.setInteger("advancedSatelliteId" + i, advancedSatelliteIdArray[i]);
        }
        NBTTagList lst = new NBTTagList();
        for (int i = 0; i < 9; i++) {
            NBTTagCompound comp = new NBTTagCompound();
            comp.setBoolean("ignore_dmg", fuzzyCraftingFlagArray[i].ignore_dmg);
            comp.setBoolean("ignore_nbt", fuzzyCraftingFlagArray[i].ignore_nbt);
            comp.setBoolean("use_od", fuzzyCraftingFlagArray[i].use_od);
            comp.setBoolean("use_category", fuzzyCraftingFlagArray[i].use_category);
            lst.appendTag(comp);
        }
        nbttagcompound.setTag("fuzzyFlags", lst);
        {
            NBTTagCompound comp = new NBTTagCompound();
            comp.setBoolean("ignore_dmg", outputFuzzyFlags.ignore_dmg);
            comp.setBoolean("ignore_nbt", outputFuzzyFlags.ignore_nbt);
            comp.setBoolean("use_od", outputFuzzyFlags.use_od);
            comp.setBoolean("use_category", outputFuzzyFlags.use_category);
            nbttagcompound.setTag("outputFuzzyFlags", comp);
        }
        for (int i = 0; i < 6; i++) {
            nbttagcompound.setBoolean("craftingSigns" + i, craftingSigns[i]);
        }
        for (int i = 0; i < ItemUpgrade.MAX_LIQUID_CRAFTER; i++) {
            nbttagcompound.setInteger("liquidSatelliteIdArray" + i, liquidSatelliteIdArray[i]);
        }
        nbttagcompound.setIntArray("FluidAmount", amount);
        nbttagcompound.setInteger("liquidSatelliteId", liquidSatelliteId);
        nbttagcompound.setBoolean("cleanupModeIsExclude", cleanupModeIsExclude);
    }

    public ModernPacket getCPipePacket() {
        return PacketHandler.getPacket(CraftingPipeUpdatePacket.class).setAmount(amount)
                .setLiquidSatelliteIdArray(liquidSatelliteIdArray).setLiquidSatelliteId(liquidSatelliteId)
                .setSatelliteId(satelliteId).setAdvancedSatelliteIdArray(advancedSatelliteIdArray).setPriority(priority)
                .setModulePos(this);
    }

    public void handleCraftingUpdatePacket(CraftingPipeUpdatePacket packet) {
        amount = packet.getAmount();
        liquidSatelliteIdArray = packet.getLiquidSatelliteIdArray();
        liquidSatelliteId = packet.getLiquidSatelliteId();
        satelliteId = packet.getSatelliteId();
        advancedSatelliteIdArray = packet.getAdvancedSatelliteIdArray();
        priority = packet.getPriority();
    }

    @Override
    protected ModuleCoordinatesGuiProvider getPipeGuiProvider() {
        return NewGuiHandler.getGui(CraftingModuleSlot.class)
                .setAdvancedSat(getUpgradeManager().isAdvancedSatelliteCrafter())
                .setLiquidCrafter(getUpgradeManager().getFluidCrafter()).setAmount(amount)
                .setHasByproductExtractor(getUpgradeManager().hasByproductExtractor())
                .setFuzzy(getUpgradeManager().isFuzzyUpgrade()).setCleanupSize(getUpgradeManager().getCrafterCleanup())
                .setCleanupExclude(cleanupModeIsExclude);
    }

    @Override
    protected ModuleInHandGuiProvider getInHandGuiProvider() {
        return NewGuiHandler.getGui(CraftingModuleInHand.class).setAmount(amount)
                .setCleanupExclude(cleanupModeIsExclude);
    }

    /**
     * Simply get the dummy inventory
     *
     * @return the dummy inventory
     */
    public ItemIdentifierInventory getDummyInventory() {
        return _dummyInventory;
    }

    public ItemIdentifierInventory getFluidInventory() {
        return _liquidInventory;
    }

    public IInventory getCleanupInventory() {
        return _cleanupInventory;
    }

    public void setDummyInventorySlot(int slot, ItemStack itemstack) {
        _dummyInventory.setInventorySlotContents(slot, itemstack);
    }

    public void handleNEIRecipePacket(ItemStack[] content, EntityPlayer player) {
        _dummyInventory.clearGrid();
        for (int i = 0; i < content.length; i++) {
            if (i < _dummyInventory.getSizeInventory()) {
                _dummyInventory.setInventorySlotContents(i, content[i]);
            }
        }
        if (player != null) {
            MainProxy.sendPacketToPlayer(getCPipePacket(), player);
        }
    }

    public void handleAdvancedNEIRecipePacket(List<ItemStack> inputs, List<ItemStack> outputs,
            List<FluidStack> fluidInputs, EntityPlayer player) {
        _dummyInventory.clearGrid();
        int itemSlot = 0;
        for (int i = 0; i < inputs.size() && itemSlot < 9; i++) {
            ItemStack stack = inputs.get(i);
            _dummyInventory.setInventorySlotContents(itemSlot++, stack);
        }
        if (!outputs.isEmpty()) {
            _dummyInventory.setInventorySlotContents(9, outputs.get(0));
            if (outputs.size() > 1) {
                _dummyInventory.setInventorySlotContents(10, outputs.get(1));
            }
        }

        // Populate liquids
        _liquidInventory.clearGrid();
        for (int i = 0; i < amount.length; i++) {
            amount[i] = 0;
        }

        for (int i = 0; i < fluidInputs.size() && i < _liquidInventory.getSizeInventory(); i++) {
            FluidStack fs = fluidInputs.get(i);
            if (fs != null && fs.getFluid() != null) {
                _liquidInventory.setInventorySlotContents(i, FluidIdentifier.get(fs).getItemIdentifier().makeStack(1));
                amount[i] = fs.amount;
            }
        }
        if (player != null) {
            MainProxy.sendPacketToPlayer(getCPipePacket(), player);
        }
    }

    public void importFromCraftingTable(EntityPlayer player) {
        if (MainProxy.isClient(getWorld())) {
            // Send packet asking for import
            final CoordinatesPacket packet = PacketHandler.getPacket(CPipeSatelliteImport.class).setModulePos(this);
            MainProxy.sendPacketToServer(packet);
        } else {
            final WorldUtil worldUtil = new WorldUtil(getWorld(), getX(), getY(), getZ());
            for (final AdjacentTile tile : worldUtil.getAdjacentTileEntities(true)) {
                for (ICraftingRecipeProvider provider : SimpleServiceLocator.craftingRecipeProviders) {
                    if (provider.importRecipe(tile.tile, _dummyInventory)) {
                        if (provider instanceof IFuzzyRecipeProvider) {
                            ((IFuzzyRecipeProvider) provider).importFuzzyFlags(
                                    tile.tile,
                                    _dummyInventory,
                                    fuzzyCraftingFlagArray,
                                    outputFuzzyFlags);
                        }
                        break;
                    }
                }
            }
            // Send inventory as packet
            final CoordinatesPacket packet = PacketHandler.getPacket(CPipeSatelliteImportBack.class)
                    .setInventory(_dummyInventory).setModulePos(this);
            if (player != null) {
                MainProxy.sendPacketToPlayer(packet, player);
            }
            MainProxy.sendPacketToAllWatchingChunk(getX(), getZ(), MainProxy.getDimensionForWorld(getWorld()), packet);
        }
    }

    protected World getWorld() {
        return _world.getWorld();
    }

    public void priorityUp(EntityPlayer player) {
        priority++;
        if (MainProxy.isClient(player.worldObj)) {
            MainProxy
                    .sendPacketToServer(PacketHandler.getPacket(CraftingPipePriorityUpPacket.class).setModulePos(this));
        } else if (MainProxy.isServer(player.worldObj)) {
            MainProxy.sendPacketToPlayer(
                    PacketHandler.getPacket(CraftingPriority.class).setInteger(priority).setModulePos(this),
                    player);
        }
    }

    public void priorityDown(EntityPlayer player) {
        priority--;
        if (MainProxy.isClient(player.worldObj)) {
            MainProxy.sendPacketToServer(
                    PacketHandler.getPacket(CraftingPipePriorityDownPacket.class).setModulePos(this));
        } else if (MainProxy.isServer(player.worldObj)) {
            MainProxy.sendPacketToPlayer(
                    PacketHandler.getPacket(CraftingPriority.class).setInteger(priority).setModulePos(this),
                    player);
        }
    }

    public void setPriority(int amount) {
        priority = amount;
    }

    public ItemIdentifierStack getByproductItem() {
        return _dummyInventory.getIDStackInSlot(10);
    }

    public ItemIdentifierStack getMaterials(int slotnr) {
        return _dummyInventory.getIDStackInSlot(slotnr);
    }

    public FluidIdentifier getFluidMaterial(int slotnr) {
        ItemIdentifierStack stack = _liquidInventory.getIDStackInSlot(slotnr);
        if (stack == null) {
            return null;
        }
        return FluidIdentifier.get(stack.getItem());
    }

    public void setNextSatellite(EntityPlayer player, int i) {
        if (MainProxy.isClient(player.worldObj)) {
            MainProxy.sendPacketToServer(
                    PacketHandler.getPacket(CraftingPipeNextAdvancedSatellitePacket.class).setInteger(i)
                            .setModulePos(this));
        } else {
            advancedSatelliteIdArray[i] = getNextConnectSatelliteId(false, i);
            MainProxy.sendPacketToPlayer(
                    PacketHandler.getPacket(CraftingAdvancedSatelliteId.class).setInteger2(i)
                            .setInteger(advancedSatelliteIdArray[i]).setModulePos(this),
                    player);
        }
    }

    public void setPrevSatellite(EntityPlayer player, int i) {
        if (MainProxy.isClient(player.worldObj)) {
            MainProxy.sendPacketToServer(
                    PacketHandler.getPacket(CraftingPipePrevAdvancedSatellitePacket.class).setInteger(i)
                            .setModulePos(this));
        } else {
            advancedSatelliteIdArray[i] = getNextConnectSatelliteId(true, i);
            MainProxy.sendPacketToPlayer(
                    PacketHandler.getPacket(CraftingAdvancedSatelliteId.class).setInteger2(i)
                            .setInteger(advancedSatelliteIdArray[i]).setModulePos(this),
                    player);
        }
    }

    public void changeFluidAmount(int change, int slot, EntityPlayer player) {
        if (MainProxy.isClient(player.worldObj)) {
            MainProxy.sendPacketToServer(
                    PacketHandler.getPacket(FluidCraftingAmount.class).setInteger2(slot).setInteger(change)
                            .setModulePos(this));
        } else {
            amount[slot] += change;
            if (amount[slot] <= 0) {
                amount[slot] = 0;
            }
            MainProxy.sendPacketToPlayer(
                    PacketHandler.getPacket(FluidCraftingAmount.class).setInteger2(slot).setInteger(amount[slot])
                            .setModulePos(this),
                    player);
        }
    }

    public void setPrevFluidSatellite(EntityPlayer player, int i) {
        if (MainProxy.isClient(player.worldObj)) {
            MainProxy.sendPacketToServer(
                    PacketHandler.getPacket(FluidCraftingPipeAdvancedSatellitePrevPacket.class).setInteger(i)
                            .setModulePos(this));
        } else {
            if (i == -1) {
                liquidSatelliteId = getNextConnectFluidSatelliteId(true, i);
                MainProxy.sendPacketToPlayer(
                        PacketHandler.getPacket(FluidCraftingAdvancedSatelliteId.class).setInteger2(i)
                                .setInteger(liquidSatelliteId).setModulePos(this),
                        player);
            } else {
                liquidSatelliteIdArray[i] = getNextConnectFluidSatelliteId(true, i);
                MainProxy.sendPacketToPlayer(
                        PacketHandler.getPacket(FluidCraftingAdvancedSatelliteId.class).setInteger2(i)
                                .setInteger(liquidSatelliteIdArray[i]).setModulePos(this),
                        player);
            }
        }
    }

    public void setNextFluidSatellite(EntityPlayer player, int i) {
        if (MainProxy.isClient(player.worldObj)) {
            MainProxy.sendPacketToServer(
                    PacketHandler.getPacket(FluidCraftingPipeAdvancedSatelliteNextPacket.class).setInteger(i)
                            .setModulePos(this));
        } else {
            if (i == -1) {
                liquidSatelliteId = getNextConnectFluidSatelliteId(false, i);
                MainProxy.sendPacketToPlayer(
                        PacketHandler.getPacket(FluidCraftingAdvancedSatelliteId.class).setInteger2(i)
                                .setInteger(liquidSatelliteId).setModulePos(this),
                        player);
            } else {
                liquidSatelliteIdArray[i] = getNextConnectFluidSatelliteId(false, i);
                MainProxy.sendPacketToPlayer(
                        PacketHandler.getPacket(FluidCraftingAdvancedSatelliteId.class).setInteger2(i)
                                .setInteger(liquidSatelliteIdArray[i]).setModulePos(this),
                        player);
            }
        }
    }

    public void setFluidAmount(int[] amount) {
        if (MainProxy.isClient(getWorld())) {
            this.amount = amount;
        }
    }

    public void defineFluidAmount(int integer, int slot) {
        if (MainProxy.isClient(getWorld())) {
            amount[slot] = integer;
        }
    }

    public int[] getFluidAmount() {
        return amount;
    }

    public void setFluidSatelliteId(int integer, int slot) {
        if (slot == -1) {
            liquidSatelliteId = integer;
        } else {
            liquidSatelliteIdArray[slot] = integer;
        }
    }

    public IRouter getFluidSatelliteRouter(int x) {
        if (x == -1) {
            for (final PipeFluidSatellite satellite : PipeFluidSatellite.AllSatellites) {
                if (satellite.satelliteId == liquidSatelliteId) {
                    if (satellite.stillNeedReplace() || satellite.getRouter() == null) {
                        continue;
                    }
                    return satellite.getRouter();
                }
            }
        } else {
            for (final PipeFluidSatellite satellite : PipeFluidSatellite.AllSatellites) {
                if (satellite.satelliteId == liquidSatelliteIdArray[x]) {
                    if (satellite.stillNeedReplace() || satellite.getRouter() == null) {
                        continue;
                    }
                    return satellite.getRouter();
                }
            }
        }
        return null;
    }

    public void openAttachedGui(EntityPlayer player) {
        if (MainProxy.isClient(player.worldObj)) {
            if (player instanceof EntityPlayerMP) {
                player.closeScreen();
            } else if (player instanceof EntityPlayerSP) {
                player.closeScreen();
            }
            MainProxy.sendPacketToServer(
                    PacketHandler.getPacket(CraftingPipeOpenConnectedGuiPacket.class).setModulePos(this));
            return;
        }

        // hack to avoid wrenching blocks
        int savedEquipped = player.inventory.currentItem;
        boolean foundSlot = false;
        // try to find a empty slot
        for (int i = 0; i < 9; i++) {
            if (player.inventory.getStackInSlot(i) == null) {
                foundSlot = true;
                player.inventory.currentItem = i;
                break;
            }
        }
        // okay, anything that's a block?
        if (!foundSlot) {
            for (int i = 0; i < 9; i++) {
                ItemStack is = player.inventory.getStackInSlot(i);
                if (is.getItem() instanceof ItemBlock) {
                    foundSlot = true;
                    player.inventory.currentItem = i;
                    break;
                }
            }
        }
        // give up and select whatever is right of the current slot
        if (!foundSlot) {
            player.inventory.currentItem = (player.inventory.currentItem + 1) % 9;
        }

        final WorldUtil worldUtil = new WorldUtil(getWorld(), getX(), getY(), getZ());
        boolean found = false;
        for (final AdjacentTile tile : worldUtil.getAdjacentTileEntities(true)) {
            for (ICraftingRecipeProvider provider : SimpleServiceLocator.craftingRecipeProviders) {
                if (provider.canOpenGui(tile.tile)) {
                    found = true;
                    break;
                }
            }

            if (!found) {
                found = (tile.tile instanceof IInventory);
            }

            if (found) {
                Block block = getWorld().getBlock(tile.tile.xCoord, tile.tile.yCoord, tile.tile.zCoord);
                if (block != null) {
                    if (block.onBlockActivated(
                            getWorld(),
                            tile.tile.xCoord,
                            tile.tile.yCoord,
                            tile.tile.zCoord,
                            player,
                            0,
                            0,
                            0,
                            0)) {
                        break;
                    }
                }
            }
        }
        player.inventory.currentItem = savedEquipped;
    }

    public void enabledUpdateEntity() {
        if (_service.getItemOrderManager().hasOrders(ResourceType.CRAFTING, ResourceType.EXTRA)) {
            if (_service.isNthTick(6)) {
                cacheAreAllOrderesToBuffer();
            }
            if (_service.getItemOrderManager().isFirstOrderWatched()) {
                TileEntity tile = lastAccessedCrafter.get();
                if (tile != null) {
                    _service.getItemOrderManager()
                            .setMachineProgress(SimpleServiceLocator.machineProgressProvider.getProgressForTile(tile));
                } else {
                    _service.getItemOrderManager().setMachineProgress((byte) 0);
                }
            }
        } else {
            cachedAreAllOrderesToBuffer = false;
        }

        if (!shouldCheckMachine()) {
            return;
        }

        waitingForCraft = false;

        if (!_service.getItemOrderManager().hasOrders(ResourceType.CRAFTING, ResourceType.EXTRA)) {
            if (getUpgradeManager().getCrafterCleanup() > 0) {
                List<AdjacentTile> crafters = locateCrafters();
                ItemStack extracted = null;
                for (AdjacentTile crafter : crafters) {
                    extracted = extractFiltered(
                            crafter,
                            _cleanupInventory,
                            cleanupModeIsExclude,
                            getUpgradeManager().getCrafterCleanup() * 3);
                    if (extracted != null && extracted.stackSize > 0) {
                        break;
                    }
                }
                if (extracted != null && extracted.stackSize > 0) {
                    _service.queueRoutedItem(
                            SimpleServiceLocator.routedItemHelper.createNewTravelItem(extracted),
                            ForgeDirection.UP);
                    _service.getCacheHolder().trigger(CacheTypes.Inventory);
                }
            }
            return;
        }

        waitingForCraft = true;

        List<AdjacentTile> crafters = locateCrafters();
        if (crafters.size() < 1) {
            if (_service.getItemOrderManager().hasOrders(ResourceType.CRAFTING, ResourceType.EXTRA)) {
                _service.getItemOrderManager().sendFailed();
            }
            return;
        }

        List<ItemIdentifierStack> wanteditem = getConfiguredCraftResults();
        if (wanteditem == null || wanteditem.isEmpty()) {
            return;
        }

        _service.spawnParticle(Particles.VioletParticle, 2);

        int itemsleft = itemsToExtract();
        int stacksleft = stacksToExtract();
        if (servesOldestFirst()) {
            extractOldestFirst(crafters, itemsleft, stacksleft);
            return;
        }
        while (itemsleft > 0 && stacksleft > 0
                && (_service.getItemOrderManager().hasOrders(ResourceType.CRAFTING, ResourceType.EXTRA))) {
            LogisticsItemOrder nextOrder = _service.getItemOrderManager()
                    .peekAtTopRequest(ResourceType.CRAFTING, ResourceType.EXTRA); // fetch but not remove.
            // Decided before extracting, not after: with nowhere to put the result, leaving it in the machine is
            // backpressure the player can see, where pulling it out first would only make it bounce.
            ResultRoute route = routeForResult(nextOrder);
            if (route == ResultRoute.HOLD) {
                _service.getItemOrderManager().deferSend();
                break;
            }
            int maxtosend = maxToSend(nextOrder, route, itemsleft);
            // retrieve the new crafted items
            ItemStack extracted = null;
            AdjacentTile tile = null;
            for (AdjacentTile crafter : resultSourcesFor(nextOrder, crafters)) {
                tile = crafter;
                extracted = extract(tile, resultToExtract(nextOrder), maxtosend);
                if (extracted != null && extracted.stackSize > 0) {
                    break;
                }
            }
            if (extracted == null || extracted.stackSize == 0) {
                _service.getItemOrderManager().deferSend();
                break;
            }
            _service.getCacheHolder().trigger(CacheTypes.Inventory);
            lastAccessedCrafter = new WeakReference<>(tile.tile);
            // send the new crafted items to the destination
            ItemIdentifier extractedID = ItemIdentifier.get(extracted);
            onResultExtracted(extractedID, extracted.stackSize);
            while (extracted.stackSize > 0) {
                if (!doesExtractionMatch(nextOrder, extractedID)) {
                    LogisticsItemOrder startOrder = nextOrder;
                    if (_service.getItemOrderManager().hasOrders(ResourceType.CRAFTING, ResourceType.EXTRA)) {
                        do {
                            _service.getItemOrderManager().deferSend();
                            nextOrder = _service.getItemOrderManager()
                                    .peekAtTopRequest(ResourceType.CRAFTING, ResourceType.EXTRA);
                        } while (!doesExtractionMatch(nextOrder, extractedID) && startOrder != nextOrder);
                    }
                    if (startOrder == nextOrder) {
                        int numtosend = Math.min(extracted.stackSize, extractedID.getMaxStackSize());
                        if (numtosend == 0) {
                            break;
                        }
                        stacksleft -= 1;
                        itemsleft -= numtosend;
                        ItemStack stackToSend = extracted.splitStack(numtosend);
                        // Route the unhandled item

                        sendUnrouted(tile, stackToSend, null);
                        continue;
                    }
                }
                int numtosend = Math.min(extracted.stackSize, extractedID.getMaxStackSize());
                numtosend = Math.min(numtosend, nextOrder.getResource().stack.getStackSize());
                if (numtosend == 0) {
                    break;
                }
                stacksleft -= 1;
                itemsleft -= numtosend;
                ItemStack stackToSend = extracted.splitStack(numtosend);
                sendResult(nextOrder, route, tile, stackToSend, true, null);
                if (_service.getItemOrderManager().hasOrders(ResourceType.CRAFTING, ResourceType.EXTRA)) {
                    nextOrder = _service.getItemOrderManager()
                            .peekAtTopRequest(ResourceType.CRAFTING, ResourceType.EXTRA); // fetch but not remove.
                }
            }
        }
    }

    /**
     * Whether results go to the oldest request first instead of to the front of the order queue. Off here: a normal
     * crafter serves its queue as it always has. The queue rotates whenever the front order can't be served yet - every
     * check while the machine is still working, among others - so its front says nothing about age, and a request that
     * keeps asking again (a Trickle Supplier) can win result after result over an older one.
     */
    protected boolean servesOldestFirst() {
        return false;
    }

    /**
     * For {@link #extractOldestFirst}: whether this request still has an ingredient for this module that hasn't been
     * sent - a craft not finished, a delivery not made. Its sets can't have run yet, so a result made now came from
     * another request's ingredients. False here: a normal crafter doesn't serve oldest first.
     */
    protected boolean jobOwesIngredients(CraftingJob job) {
        return false;
    }

    /**
     * The oldest order {@code accept} takes whose request has nothing left to send to this module, or the oldest it
     * takes at all when there is none. Handing a result to an order whose own ingredients are still on their way gives
     * it the result of another request's ingredients: the order counts as filled, its sub-crafts stay open, and the
     * other request shows nothing left but the result it was robbed of.
     */
    private static LogisticsItemOrder oldestPreferringReady(LogisticsItemOrderManager orders,
            Predicate<LogisticsItemOrder> accept, Predicate<LogisticsItemOrder> ready) {
        LogisticsItemOrder order = orders
                .oldestRequest(o -> accept.test(o) && ready.test(o), ResourceType.CRAFTING, ResourceType.EXTRA);
        return order != null ? order : orders.oldestRequest(accept, ResourceType.CRAFTING, ResourceType.EXTRA);
    }

    /**
     * {@link #enabledUpdateEntity}'s result loop for {@link #servesOldestFirst}: each result goes to the oldest order that can
     * take it right now. An order that can't (its destination is shut and no buffer has room) is passed over for this
     * check but keeps its place, so it is first again as soon as it can take one. Nothing is moved while the machine
     * has nothing ready. The orders served are the same as before - every crafting order in the chassis - only the
     * order they are served in changed. Once a result is out, requests whose ingredients for this module have all been
     * sent come first (see {@link #oldestPreferringReady}), so it goes to the request whose ingredients made it.
     */
    private void extractOldestFirst(List<AdjacentTile> crafters, int itemsleft, int stacksleft) {
        LogisticsItemOrderManager orders = _service.getItemOrderManager();
        // Passed over for the rest of this check: orders whose result has nowhere to go, items not ready in the
        // machine.
        Set<LogisticsItemOrder> held = new HashSet<>();
        Set<ItemIdentifier> notReady = new HashSet<>();
        List<RouteSeen> routes = new ArrayList<>();
        // Per request, for this check: walking a request's orders is the costly part, and what this module's sends do
        // here doesn't change what the other pipes still owe it. Only asked once a result is out of the machine: this
        // loop runs every few ticks while the machine is still working, and then there is nothing to hand out.
        Map<CraftingJob, Boolean> owes = new HashMap<>();
        Predicate<LogisticsItemOrder> ready = o -> o.getJob() == null
                || !owes.computeIfAbsent(o.getJob(), this::jobOwesIngredients);
        while (itemsleft > 0 && stacksleft > 0) {
            LogisticsItemOrder order = orders.oldestRequest(
                    o -> !held.contains(o) && !notReady.contains(o.getResource().getItem()),
                    ResourceType.CRAFTING,
                    ResourceType.EXTRA);
            if (order == null) {
                return;
            }
            // Decided before extracting, as in the normal loop: a result with nowhere to go stays in the machine.
            ResultRoute route = routeFor(order, routes);
            int maxtosend = route == ResultRoute.HOLD ? 0 : maxToSend(order, route, itemsleft);
            if (maxtosend <= 0) {
                held.add(order);
                continue;
            }
            ItemStack extracted = null;
            AdjacentTile tile = null;
            for (AdjacentTile crafter : resultSourcesFor(order, crafters)) {
                tile = crafter;
                extracted = extract(tile, resultToExtract(order), maxtosend);
                if (extracted != null && extracted.stackSize > 0) {
                    break;
                }
            }
            if (extracted == null || extracted.stackSize == 0) {
                // Not made yet. Every order for it would find the same, so they are all skipped for this check.
                notReady.add(order.getResource().getItem());
                continue;
            }
            _service.getCacheHolder().trigger(CacheTypes.Inventory);
            lastAccessedCrafter = new WeakReference<>(tile.tile);
            ItemIdentifier extractedID = ItemIdentifier.get(extracted);
            onResultExtracted(extractedID, extracted.stackSize);
            while (extracted.stackSize > 0) {
                LogisticsItemOrder target = order;
                ResultRoute targetRoute = route;
                if (target.isFinished() || target.getAmount() <= 0
                        || !doesExtractionMatch(target, extractedID)
                        || !ready.test(target)) {
                    // What came out isn't (or is no longer) for this order, or its request's own ingredients are still
                    // on their way: the oldest order that takes it gets it, one whose ingredients were all sent first.
                    // With no such order that is this one again. One that can't take any right now is passed over, or
                    // a ready order with its gate full would send the stack out unaddressed in place of this one.
                    target = oldestPreferringReady(orders, o -> {
                        if (held.contains(o) || !doesExtractionMatch(o, extractedID)) {
                            return false;
                        }
                        ResultRoute r = routeFor(o, routes);
                        return r != ResultRoute.HOLD && (r != ResultRoute.TO_ORDER || gatedAllowance(o) > 0);
                    }, ready);
                    targetRoute = target == null ? null : routeFor(target, routes);
                }
                int numtosend = Math.min(extracted.stackSize, extractedID.getMaxStackSize());
                if (target != null) {
                    numtosend = Math.min(numtosend, target.getResource().stack.getStackSize());
                    if (targetRoute == ResultRoute.TO_ORDER) {
                        numtosend = Math.min(numtosend, gatedAllowance(target));
                    }
                }
                if (numtosend <= 0) {
                    // Nobody can take the rest after all: it goes out unaddressed, as an unmatched result always has.
                    target = null;
                    numtosend = Math.min(extracted.stackSize, extractedID.getMaxStackSize());
                }
                stacksleft -= 1;
                itemsleft -= numtosend;
                ItemStack stackToSend = extracted.splitStack(numtosend);
                if (target == null) {
                    handoutDiag(null, extractedID, numtosend, order, null, null, false); // TEMP [Handout DIAG]
                    sendUnrouted(tile, stackToSend, null);
                    continue;
                }
                // The send counts against the front order, so the chosen one is put there first. No rotation after
                // it: which order is served next is decided by age, not by place in the queue.
                orders.moveToFront(target);
                // The order manager counts a send against older orders to the same place first. When the target was
                // chosen for being ready, an older one still waiting on its ingredients must not take it back.
                boolean targetReady = ready.test(target);
                Object diagBefore = handoutDiagSnapshot(extractedID); // TEMP [Handout DIAG]
                sendResult(target, targetRoute, tile, stackToSend, false, targetReady ? ready : null);
                // TEMP [Handout DIAG]
                handoutDiag(diagBefore, extractedID, numtosend, order, target, targetRoute, targetReady);
                // A send uses up gate allowance and buffer room, so the routes worked out so far may be stale.
                routes.clear();
            }
        }
    }

    /**
     * TEMP [Handout DIAG]: what the orders for {@code item} still owed before a send, so {@link #handoutDiag} can show
     * which ones the send was really counted against. Null when not logging; nothing is logged here.
     */
    protected Object handoutDiagSnapshot(ItemIdentifier item) {
        return null;
    }

    /**
     * TEMP [Handout DIAG]: one result handed out by {@link #extractOldestFirst}. {@code firstPick} is the order the
     * loop extracted for, {@code target} the one it went to (null: sent out unaddressed). Nothing is logged here.
     */
    protected void handoutDiag(Object before, ItemIdentifier item, int amount, LogisticsItemOrder firstPick,
            LogisticsItemOrder target, ResultRoute route, boolean targetReady) {}

    /** A route worked out during one check, for {@link #routeFor}. */
    private static final class RouteSeen {

        final LogisticsItemOrder order;
        final ResultRoute route;

        RouteSeen(LogisticsItemOrder order, ResultRoute route) {
            this.order = order;
            this.route = route;
        }
    }

    /**
     * {@link #routeForResult}, worked out once per destination: orders for the same item, to the same destination and
     * target there, get the same answer, so walking past many held orders for one machine costs one check, not one
     * each.
     */
    private ResultRoute routeFor(LogisticsItemOrder order, List<RouteSeen> seen) {
        for (RouteSeen known : seen) {
            if (sameRouting(known.order, order)) {
                return known.route;
            }
        }
        ResultRoute route = routeForResult(order);
        seen.add(new RouteSeen(order, route));
        return route;
    }

    private static boolean sameRouting(LogisticsItemOrder a, LogisticsItemOrder b) {
        if (a.getDestination() != b.getDestination() || !a.getResource().getItem().equals(b.getResource().getItem())) {
            return false;
        }
        IAdditionalTargetInformation infoA = a.getInformation();
        IAdditionalTargetInformation infoB = b.getInformation();
        return infoA == null ? infoB == null : infoA.sameTargetAs(infoB);
    }

    /**
     * How much to take out of the machine for an order: up to a stack of what it still needs, no more than its
     * destination's gate allows when it goes straight there, or than the chosen buffer has room for.
     */
    private int maxToSend(LogisticsItemOrder order, ResultRoute route, int itemsleft) {
        int maxtosend = Math.min(itemsleft, order.getResource().stack.getStackSize());
        maxtosend = Math.min(order.getResource().getItem().getMaxStackSize(), maxtosend);
        if (route == ResultRoute.TO_ORDER) {
            // A Smart Crafter takes only what its released sets still need; the rest waits for its next set.
            maxtosend = Math.min(maxtosend, gatedAllowance(order));
        } else {
            // Only take out what the chosen buffer has room for, so the result can be addressed to it.
            ModuleCraftingBuffer buffer = resultBufferFor(order);
            if (buffer != null) {
                maxtosend = Math.max(1, buffer.roomFor(order.getResource().getItem(), maxtosend));
            }
        }
        return maxtosend;
    }

    /**
     * Sends one stack of results for the front order and counts it against that order. {@code mayDefer}: let the queue
     * rotate past a destination that buffers or reported no room, as the normal loop always has. {@code mayTakeCredit}:
     * which older orders to the same place the send may count against before this one, null for all of them.
     */
    private void sendResult(LogisticsItemOrder order, ResultRoute route, AdjacentTile tile, ItemStack stackToSend,
            boolean mayDefer, Predicate<LogisticsItemOrder> mayTakeCredit) {
        if (route == ResultRoute.TO_BUFFER) {
            // The destination isn't ready for it, so it waits in a buffer instead of being pushed at a
            // machine that can't use it yet. The order is still fulfilled: this module made what it owed.
            onResultBuffered(order, stackToSend.stackSize);
            // Addressed to the buffer nearest the machine it is for. If that buffer can't take the whole stack
            // after all, it goes out unaddressed as before, to whichever buffer sinks it.
            ModuleCraftingBuffer buffer = resultBufferFor(order);
            ItemIdentifier sentID = ItemIdentifier.get(stackToSend);
            if (buffer != null && buffer.hasRoomFor(sentID, stackToSend.stackSize)) {
                buffer.reserve(sentID, stackToSend.stackSize);
                sendFrom(tile, stackToSend, buffer.getBufferRouter(), buffer.getTargetInformation());
            } else {
                sendUnrouted(tile, stackToSend, order.getInformation());
            }
            _service.getItemOrderManager().sendSuccessfull(stackToSend.stackSize, false, null, mayTakeCredit);
        } else if (order.getDestination() != null) {
            SinkReply reply = LogisticsManager.canSink(
                    order.getDestination().getRouter(),
                    null,
                    true,
                    ItemIdentifier.get(stackToSend),
                    null,
                    true,
                    false);
            boolean defersend = mayDefer
                    && (reply == null || reply.bufferMode != BufferMode.NONE || reply.maxNumberOfItems < 1);
            IRoutedItem item = SimpleServiceLocator.routedItemHelper.createNewTravelItem(stackToSend);
            item.setDestination(order.getDestination().getRouter().getSimpleID());
            item.setTransportMode(TransportMode.Active);
            item.setAdditionalTargetInformation(order.getInformation());
            queueResult(tile, item);
            IGatedItemSink gate = gateFor(order);
            if (gate != null) {
                // Counted as on its way, so the gate doesn't hand out the same room twice.
                gate.onGatedSend(ItemIdentifier.get(stackToSend), stackToSend.stackSize, order.getInformation());
            }
            _service.getItemOrderManager().sendSuccessfull(stackToSend.stackSize, defersend, item, mayTakeCredit);
        } else {
            sendUnrouted(tile, stackToSend, order.getInformation());
            _service.getItemOrderManager().sendSuccessfull(stackToSend.stackSize, false, null, mayTakeCredit);
        }
    }

    /**
     * Puts a result into the network through the pipe its source machine is attached to: the chassis, or the pipe named
     * by {@link AdjacentTile#sender} (a Smart Satellite). The order accounting stays with this module either way.
     */
    private void queueResult(AdjacentTile source, IRoutedItem item) {
        if (source.sender != null) {
            source.sender.queueRoutedItem(item, source.getSendOrientation());
        } else {
            _service.queueRoutedItem(item, source.getSendOrientation());
        }
    }

    /**
     * Like {@code _service.sendStack(stack, -1, ItemSendMode.Normal, info)}, but through {@link AdjacentTile#sender}
     * when set. The sender's own {@code sendStack} can't be used: it sends out of its pointed orientation, which a
     * satellite doesn't have.
     * <p>
     * The sender is jammed as a destination: a satellite sinks into the machine it faces, and from the satellite that
     * is the nearest sink there is, so the result would go straight back where it came from.
     */
    private void sendUnrouted(AdjacentTile source, ItemStack stack, IAdditionalTargetInformation info) {
        sendFrom(source, stack, -1, info);
    }

    /** {@link #sendUnrouted}, addressed to {@code destination} instead (-1: wherever it sinks). */
    private void sendFrom(AdjacentTile source, ItemStack stack, int destination, IAdditionalTargetInformation info) {
        if (source.sender == null) {
            _service.sendStack(stack, destination, ItemSendMode.Normal, info);
            return;
        }
        IRoutedItem item = SimpleServiceLocator.routedItemHelper.createNewTravelItem(stack);
        item.getJamList().add(source.sender.getRouter().getSimpleID());
        item.setDestination(destination);
        item.setTransportMode(TransportMode.Active);
        item.setAdditionalTargetInformation(info);
        source.sender.queueRoutedItem(item, source.getSendOrientation(), ItemSendMode.Normal);
    }

    /**
     * The machine(s) a finished result for {@code order} is pulled from. Defaults to the machines the chassis faces
     * (see {@link #locateCrafters()}); a crafter whose outputs are addressed to a satellite overrides this to pull a
     * particular output from the machine that satellite faces. The set accounting stays in the crafter either way.
     */
    protected List<AdjacentTile> resultSourcesFor(LogisticsItemOrder order, List<AdjacentTile> faced) {
        return faced;
    }

    /** Where a finished result should go. */
    protected enum ResultRoute {
        /** Straight to whoever ordered it, as the crafter has always done. */
        TO_ORDER,
        /** To a crafting buffer, to wait until the destination can use it. */
        TO_BUFFER,
        /** Nowhere yet: leave it in the machine and try again later. */
        HOLD
    }

    /**
     * A result is going to a buffer for the Smart Crafter that ordered it: give that crafter credit for it, so it takes
     * this stock from the buffer and nothing else.
     */
    protected void onResultBuffered(LogisticsItemOrder order, int amount) {
        ModuleSmartCrafter crafter = ModuleSmartCrafter.gatedCrafter(gateFor(order), order.getInformation());
        if (crafter != null) {
            crafter.creditBuffered(order.getInformation(), amount);
        }
    }

    /**
     * Where a finished result goes. A result for a Smart Crafter's machine only goes there while that crafter's gate is
     * open; otherwise it waits in a crafting buffer, or in this machine when no buffer has room, so it can't land in a
     * machine another recipe is using. Every other result goes straight to its order, as it always has.
     */
    protected ResultRoute routeForResult(LogisticsItemOrder order) {
        if (gatedAllowance(order) > 0) {
            return ResultRoute.TO_ORDER;
        }
        return resultBufferFor(order) != null ? ResultRoute.TO_BUFFER : ResultRoute.HOLD;
    }

    /**
     * The crafting buffer a held-back result goes to: the one nearest the machine (or Smart Satellite) the order is
     * for, so the trip from buffer to machine is short once the gate opens. Null when none that can reach it has room.
     */
    private static ModuleCraftingBuffer resultBufferFor(LogisticsItemOrder order) {
        IRouter target = order.getRouter();
        return target == null ? null : ModuleCraftingBuffer.withRoomFor(order.getResource().getItem(), target);
    }

    /** How much of the order's item its destination takes right now. Unlimited unless that is a gated Smart Crafter. */
    private static int gatedAllowance(LogisticsItemOrder order) {
        IGatedItemSink gate = gateFor(order);
        return gate == null ? Integer.MAX_VALUE
                : gate.getGatedAllowance(order.getResource().getItem(), order.getInformation());
    }

    /** The gated Smart Crafter (or Smart Satellite) an order is addressed to, or null. */
    private static IGatedItemSink gateFor(LogisticsItemOrder order) {
        if (order.getDestination() == null) {
            return null;
        }
        IRouter router = order.getDestination().getRouter();
        return router == null ? null : IGatedItemSink.findTarget(router, order.getInformation());
    }

    /**
     * Called each time crafted items are pulled out of the machine for an order, before they are sent.
     */
    protected void onResultExtracted(ItemIdentifier item, int amount) {}

    /**
     * Whether to look into the machine this tick, for results to pull out (or for the cleanup upgrade).
     */
    protected boolean shouldCheckMachine() {
        return _service.isNthTick(6);
    }

    private boolean doesExtractionMatch(LogisticsItemOrder nextOrder, ItemIdentifier extractedID) {
        return nextOrder.getResource().getItem().equals(extractedID)
                || (this.getUpgradeManager().isFuzzyUpgrade() && nextOrder.getResource().getBitSet().nextSetBit(0) != -1
                        && nextOrder.getResource().matches(extractedID, IResource.MatchSettings.NORMAL))
                || extractedFillsOrder(nextOrder, extractedID);
    }

    /**
     * What to take out of the machine for an order: the ordered item itself. Split out so a subclass can take its
     * result in whatever state the machine makes it.
     */
    protected IResource resultToExtract(LogisticsItemOrder order) {
        return order.getResource();
    }

    /** Whether an extracted item other than the ordered one still fills the order. None does here. */
    protected boolean extractedFillsOrder(LogisticsItemOrder order, ItemIdentifier extracted) {
        return false;
    }

    private boolean cachedAreAllOrderesToBuffer;

    public boolean areAllOrderesToBuffer() {
        return cachedAreAllOrderesToBuffer;
    }

    public void cacheAreAllOrderesToBuffer() {
        boolean result = true;
        for (LogisticsItemOrder order : _service.getItemOrderManager()) {
            if (order.getDestination() instanceof IItemSpaceControl) {
                SinkReply reply = LogisticsManager.canSink(
                        order.getDestination().getRouter(),
                        null,
                        true,
                        order.getResource().getItem(),
                        null,
                        true,
                        false);
                if (reply != null && reply.bufferMode == BufferMode.NONE && reply.maxNumberOfItems >= 1) {
                    result = false;
                    break;
                }
            } else { // No Space control
                result = false;
                break;
            }
        }
        cachedAreAllOrderesToBuffer = result;
    }

    private ItemStack extract(AdjacentTile tile, IResource item, int amount) {
        if (tile.tile instanceof LogisticsCraftingTableTileEntity) {
            return extractFromLogisticsCraftingTable(
                    (LogisticsCraftingTableTileEntity) tile.tile,
                    item,
                    amount,
                    tile.orientation);
        } else if (tile.tile instanceof net.minecraft.inventory.ISidedInventory) {
            IInventory sidedadapter = new SidedInventoryMinecraftAdapter(
                    (net.minecraft.inventory.ISidedInventory) tile.tile,
                    ForgeDirection.UNKNOWN,
                    true);
            return extractFromIInventory(sidedadapter, item, amount, tile.orientation);
        } else if (tile.tile instanceof IInventory) {
            return extractFromIInventory((IInventory) tile.tile, item, amount, tile.orientation);
        }
        return null;
    }

    private ItemStack extractFiltered(AdjacentTile tile, ItemIdentifierInventory inv, boolean isExcluded,
            int filterInvLimit) {
        if (tile.tile instanceof net.minecraft.inventory.ISidedInventory) {
            IInventory sidedadapter = new SidedInventoryMinecraftAdapter(
                    (net.minecraft.inventory.ISidedInventory) tile.tile,
                    ForgeDirection.UNKNOWN,
                    true);
            return extractFromIInventoryFiltered(sidedadapter, inv, isExcluded, filterInvLimit, tile.orientation);
        } else if (tile.tile instanceof IInventory) {
            return extractFromIInventoryFiltered(
                    (IInventory) tile.tile,
                    inv,
                    isExcluded,
                    filterInvLimit,
                    tile.orientation);
        }
        return null;
    }

    private ItemStack extractFromIInventory(IInventory inv, IResource wanteditem, int count, ForgeDirection dir) {
        IInventoryUtil invUtil = SimpleServiceLocator.inventoryUtilFactory.getInventoryUtil(inv, dir);
        ItemIdentifier itemToExtract = null;
        if (wanteditem instanceof ItemResource) {
            itemToExtract = ((ItemResource) wanteditem).getItem();
        } else if (wanteditem instanceof DictResource) {
            int max = Integer.MIN_VALUE;
            ItemIdentifier toExtract = null;
            for (Map.Entry<ItemIdentifier, Integer> content : invUtil.getItemsAndCount().entrySet()) {
                if (wanteditem.matches(content.getKey(), IResource.MatchSettings.NORMAL)) {
                    if (content.getValue() > max) {
                        max = content.getValue();
                        toExtract = content.getKey();
                    }
                }
            }
            if (toExtract == null) {
                return null;
            }
            itemToExtract = toExtract;
        }
        int available = invUtil.itemCount(itemToExtract);
        if (available == 0) {
            return null;
        }
        if (!_service.useEnergy(neededEnergy() * Math.min(count, available))) {
            return null;
        }
        return invUtil.getMultipleItems(itemToExtract, Math.min(count, available));
    }

    private ItemStack extractFromIInventoryFiltered(IInventory inv, ItemIdentifierInventory filter, boolean isExcluded,
            int filterInvLimit, ForgeDirection dir) {
        IInventoryUtil invUtil = SimpleServiceLocator.inventoryUtilFactory.getInventoryUtil(inv, dir);
        ItemIdentifier wanteditem = null;
        for (ItemIdentifier item : invUtil.getItemsAndCount().keySet()) {
            if (isExcluded) {
                boolean found = false;
                for (int i = 0; i < filter.getSizeInventory() && i < filterInvLimit; i++) {
                    ItemIdentifierStack identStack = filter.getIDStackInSlot(i);
                    if (identStack == null) {
                        continue;
                    }
                    if (identStack.getItem().equalsWithoutNBT(item)) {
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    wanteditem = item;
                }
            } else {
                boolean found = false;
                for (int i = 0; i < filter.getSizeInventory() && i < filterInvLimit; i++) {
                    ItemIdentifierStack identStack = filter.getIDStackInSlot(i);
                    if (identStack == null) {
                        continue;
                    }
                    if (identStack.getItem().equalsWithoutNBT(item)) {
                        found = true;
                        break;
                    }
                }
                if (found) {
                    wanteditem = item;
                }
            }
        }
        if (wanteditem == null) {
            return null;
        }
        int available = invUtil.itemCount(wanteditem);
        if (available == 0) {
            return null;
        }
        if (!_service.useEnergy(neededEnergy() * Math.min(64, available))) {
            return null;
        }
        return invUtil.getMultipleItems(wanteditem, Math.min(64, available));
    }

    private ItemStack extractFromLogisticsCraftingTable(LogisticsCraftingTableTileEntity tile, IResource wanteditem,
            int count, ForgeDirection dir) {
        ItemStack extracted = extractFromIInventory(tile, wanteditem, count, dir);
        if (extracted != null) {
            return extracted;
        }
        ItemStack retstack = null;
        while (count > 0) {
            ItemStack stack = tile.getOutput(wanteditem, _service);
            if (stack == null || stack.stackSize == 0) {
                break;
            }
            if (retstack == null) {
                if (!wanteditem.matches(
                        ItemIdentifier.get(stack),
                        wanteditem instanceof ItemResource ? IResource.MatchSettings.WITHOUT_NBT
                                : IResource.MatchSettings.NORMAL)) {
                    break;
                }
            } else {
                if (!retstack.isItemEqual(stack)) {
                    break;
                }
                if (!ItemStack.areItemStackTagsEqual(retstack, stack)) {
                    break;
                }
            }
            if (!_service.useEnergy(neededEnergy() * stack.stackSize)) {
                break;
            }

            if (retstack == null) {
                retstack = stack;
            } else {
                retstack.stackSize += stack.stackSize;
            }
            count -= stack.stackSize;
            if (getUpgradeManager().isFuzzyUpgrade()) {
                break;
            }
        }
        return retstack;
    }

    protected int neededEnergy() {
        return 10;
    }

    protected int itemsToExtract() {
        return 1;
    }

    protected int stacksToExtract() {
        return 1;
    }

    private List<AdjacentTile> _cachedCrafters = null;

    public List<AdjacentTile> locateCrafters() {
        if (_cachedCrafters == null) {
            WorldUtil worldUtil = new WorldUtil(getWorld(), getX(), getY(), getZ());
            LinkedList<AdjacentTile> crafters = new LinkedList<>();
            for (AdjacentTile tile : worldUtil.getAdjacentTileEntities(true)) {
                if (!(tile.tile instanceof IInventory)) {
                    continue;
                }
                crafters.add(tile);
            }
            _cachedCrafters = crafters;
        }

        return _cachedCrafters;
    }

    public void clearCraftersCache() {
        _cachedCrafters = null;
    }

    @Override
    public void clearCache() {
        clearCraftersCache();
    }

    public void importCleanup() {
        for (int i = 0; i < 10; i++) {
            _cleanupInventory.setInventorySlotContents(i, _dummyInventory.getStackInSlot(i));
        }
        for (int i = 10; i < _cleanupInventory.getSizeInventory(); i++) {
            _cleanupInventory.setInventorySlotContents(i, (ItemStack) null);
        }
        _cleanupInventory.compact_first(10);
        _cleanupInventory.recheckStackLimit();
        cleanupModeIsExclude = false;
    }

    public void toogleCleaupMode() {
        cleanupModeIsExclude = !cleanupModeIsExclude;
    }

    @Override
    public void startHUDWatching() {
        MainProxy.sendPacketToServer(PacketHandler.getPacket(HUDStartModuleWatchingPacket.class).setModulePos(this));
    }

    @Override
    public void stopHUDWatching() {
        MainProxy.sendPacketToServer(PacketHandler.getPacket(HUDStopModuleWatchingPacket.class).setModulePos(this));
    }

    @Override
    public void startWatching(EntityPlayer player) {
        localModeWatchers.add(player);
        MainProxy.sendPacketToPlayer(
                PacketHandler.getPacket(ModuleInventory.class)
                        .setIdentList(ItemIdentifierStack.getListFromInventory(_dummyInventory)).setModulePos(this),
                player);
    }

    @Override
    public void stopWatching(EntityPlayer player) {
        localModeWatchers.remove(player);
    }

    @Override
    public IHUDModuleRenderer getHUDRenderer() {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public void handleInvContent(Collection<ItemIdentifierStack> _allItems) {
        _dummyInventory.handleItemIdentifierList(_allItems);
    }

    @Override
    public void InventoryChanged(IInventory inventory) {
        if (MainProxy.isServer(_world.getWorld())) {
            MainProxy.sendToPlayerList(
                    PacketHandler.getPacket(ModuleInventory.class)
                            .setIdentList(ItemIdentifierStack.getListFromInventory(inventory)).setModulePos(this),
                    localModeWatchers);
        }
    }

    public static class CraftingChassieInformation extends ChassiTargetInformation {

        @Getter
        private final int craftingSlot;

        public CraftingChassieInformation(int craftingSlot, int moduleSlot) {
            super(moduleSlot);
            this.craftingSlot = craftingSlot;
        }

        @Override
        public boolean sameTargetAs(IAdditionalTargetInformation other) {
            return super.sameTargetAs(other) && ((CraftingChassieInformation) other).craftingSlot == craftingSlot;
        }
    }
}
