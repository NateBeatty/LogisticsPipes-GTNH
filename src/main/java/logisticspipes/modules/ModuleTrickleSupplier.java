package logisticspipes.modules;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import net.minecraft.client.renderer.texture.IIconRegister;
import net.minecraft.item.ItemStack;
import net.minecraft.util.IIcon;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import logisticspipes.LogisticsPipes;
import logisticspipes.interfaces.IInventoryUtil;
import logisticspipes.interfaces.routing.IAdditionalTargetInformation;
import logisticspipes.request.RequestLog;
import logisticspipes.request.RequestTree;
import logisticspipes.request.resources.IResource;
import logisticspipes.routing.order.CraftingJob;
import logisticspipes.routing.order.CraftingJobs;
import logisticspipes.routing.order.LinkedLogisticsOrderList;
import logisticspipes.utils.item.ItemIdentifier;
import logisticspipes.utils.item.ItemIdentifierStack;

/**
 * An Active Supplier that requests one item at a time: while one of an item is on its way, nothing more of that item is
 * requested, and the next one is requested as soon as it arrives. It never queues a large batch, so it can't flood the
 * crafters, and a request can't fail "all or nothing".
 * <p>
 * Same settings and GUI as the Active Supplier. Bulk modes wait for their threshold as usual, then keep going one at a
 * time until the target is reached. A request whose crafter pipe is broken, unloaded or loses its module is noticed
 * through its job ({@link CraftingJob#isOrdersLost()}), cancelled and requested again.
 */
public class ModuleTrickleSupplier extends ModuleActiveSupplier {

    /** How long a request may stay unarrived after everything for it was sent, before it is given up on. */
    private static final long SAFETY_TICKS = 20 * 60 * 5;

    /** A single requested item on its way. */
    private static final class InFlight {

        final CraftingJob job;
        /** World time the job finished sending, or -1 while it is still working. */
        long doneSince = -1;

        InFlight(CraftingJob job) {
            this.job = job;
        }
    }

    private final Map<ItemIdentifier, InFlight> inFlight = new HashMap<>();
    /** Bulk mode refills in progress: items (or pattern slots) past their threshold and not yet at their target. */
    private final Set<Object> refilling = new HashSet<>();
    /** Something arrived, was lost or needs requesting again: check on the next tick, not in up to 5 seconds. */
    private boolean ready = false;

    @Override
    public void tick() {
        checkInFlight();
        if (ready || _service.isNthTick(100)) {
            ready = false;
            supply();
        }
    }

    /**
     * Looks after the requests on their way. Only reads flags and the job's state, which the order hooks keep up to
     * date; nothing on the network is walked.
     */
    private void checkInFlight() {
        if (inFlight.isEmpty()) {
            return;
        }
        long now = _world.getWorld().getTotalWorldTime();
        Iterator<Entry<ItemIdentifier, InFlight>> it = inFlight.entrySet().iterator();
        while (it.hasNext()) {
            Entry<ItemIdentifier, InFlight> entry = it.next();
            ItemIdentifier item = entry.getKey();
            InFlight request = entry.getValue();
            if (!_requestedItems.containsKey(item)) {
                it.remove(); // arrived or reported lost
                continue;
            }
            CraftingJob job = request.job;
            // A cancel from elsewhere needs nothing here: it reports the unsent part as lost, and a cancelled job
            // counts as done, so anything still moving is waited for like any other finished request.
            if (job.isOrdersLost()) {
                _service.getDebug().log("Trickle Supplier: request for %s lost its crafter, requesting again", item);
                job.cancel();
                forget(item);
                it.remove();
                continue;
            }
            if (!job.isDone()) {
                request.doneSince = -1;
                continue;
            }
            if (request.doneSince < 0) {
                request.doneSince = now;
            } else if (now - request.doneSince > SAFETY_TICKS) {
                _service.getDebug().log("Trickle Supplier: %s was sent but never arrived, requesting again", item);
                forget(item);
                it.remove();
            }
        }
    }

    private void forget(ItemIdentifier item) {
        _requestedItems.remove(item);
        ready = true;
    }

    @Override
    protected void createSupplyRequest(IInventoryUtil invUtil) {
        Map<ItemIdentifier, Integer> targets = getDummyInventory().getItemsAndCount();
        Map<ItemIdentifier, Integer> have = new HashMap<>();
        for (Entry<ItemIdentifier, Integer> entry : invUtil.getItemsAndCount().entrySet()) {
            have.merge(entry.getKey().getUndamaged(), entry.getValue(), Integer::sum);
        }
        setRequestFailed(false);
        for (Entry<ItemIdentifier, Integer> target : targets.entrySet()) {
            ItemIdentifier item = target.getKey();
            if (_requestedItems.containsKey(item)) {
                logSkipDiag(item);
                continue; // one at a time
            }
            Integer haveCount = have.get(item.getUndamaged());
            if (!wantsMore(
                    item,
                    haveCount == null ? 0 : haveCount,
                    target.getValue(),
                    invUtil.roomForItem(item),
                    getSupplyMode() == SupplyMode.Bulk50,
                    getSupplyMode() == SupplyMode.Infinite)) {
                continue;
            }
            if (!_service.useEnergy(10)) {
                break;
            }
            requestOne(item, new SupplierTargetInformation());
        }
    }

    @Override
    protected void createPatternRequest(IInventoryUtil invUtil) {
        setRequestFailed(false);
        for (int i = 0; i < 9; i++) {
            ItemIdentifierStack needed = getDummyInventory().getIDStackInSlot(i);
            if (needed == null || invUtil.getSizeInventory() <= slotArray[i]) {
                continue;
            }
            ItemStack stack = invUtil.getStackInSlot(slotArray[i]);
            int haveCount = 0;
            if (stack != null) {
                ItemIdentifierStack have = ItemIdentifierStack.getFromStack(stack);
                if (!have.getItem().equals(needed.getItem())) {
                    _service.getDebug().log("Trickle Supplier: Slot for %d, %s already taken by %s", i, needed, have);
                    setRequestFailed(true);
                    continue;
                }
                haveCount = have.getStackSize();
            }
            if (_requestedItems.containsKey(needed.getItem())) {
                logSkipDiag(needed.getItem());
                continue;
            }
            int room = needed.getStackSize() - haveCount;
            if (!wantsMore(i, haveCount, needed.getStackSize(), room, getPatternMode() == PatternMode.Bulk50, false)) {
                continue;
            }
            if (!_service.useEnergy(10)) {
                break;
            }
            requestOne(needed.getItem(), new PatternSupplierTargetInformation(slotArray[i], needed.getStackSize()));
        }
    }

    /**
     * Whether one more should be requested.
     *
     * @param key      the item, or the pattern slot, a Bulk refill is tracked by
     * @param bulkHalf Bulk50: start only once at or below half the target, then keep going until the target
     * @param infinite fill while there is room, whatever the target
     */
    private boolean wantsMore(Object key, int have, int target, int room, boolean bulkHalf, boolean infinite) {
        if (room <= 0) {
            refilling.remove(key);
            return false;
        }
        if (infinite) {
            return true;
        }
        if (have >= target) {
            refilling.remove(key);
            return false;
        }
        if (bulkHalf && !refilling.contains(key)) {
            if (have > target / 2) {
                return false;
            }
            refilling.add(key);
        }
        return true;
    }

    private void requestOne(ItemIdentifier item, IAdditionalTargetInformation info) {
        CraftingJob[] job = new CraftingJob[1];
        boolean[] placed = new boolean[1];
        // Success is whether orders were placed, not what request() returns: when planning fails it fills the tree
        // with stand-in crafting promises to work out what is missing, and then reports their amount, so a failed
        // request can look like a full one. Counting that as one on its way, with nothing ever coming, stopped the
        // item from being requested again.
        RequestTree.request(item.makeStack(1), this, new RequestLog() {

            @Override
            public void handleMissingItems(List<IResource> resources) {
                // TEMP [Trickle DIAG]: what a failed request was missing.
                StringBuilder missing = new StringBuilder();
                for (IResource resource : resources) {
                    ItemIdentifierStack display = resource.getDisplayItem();
                    missing.append(missing.length() == 0 ? "" : ", ")
                            .append(display == null ? resource.toString() : display.getFriendlyName());
                }
                LogisticsPipes.log.info("[Trickle DIAG] " + where() + " request for 1 " + item.getFriendlyName()
                        + " failed, missing: " + missing);
            }

            @Override
            public void handleSucessfullRequestOf(IResource resource, LinkedLogisticsOrderList parts) {
                placed[0] = true;
                job[0] = CraftingJobs.findJob(parts);
            }

            @Override
            public void handleSucessfullRequestOfList(List<IResource> resources, LinkedLogisticsOrderList parts) {}
        }, info);
        boolean success = placed[0];
        _service.getDebug().log("Trickle Supplier: Request of one %s %s", item, success ? "succeeded" : "failed");
        if (!success) {
            setRequestFailed(true);
            return;
        }
        _requestedItems.merge(item, 1, Integer::sum);
        if (job[0] != null) {
            inFlight.put(item, new InFlight(job[0]));
        }
    }

    /** TEMP [Trickle DIAG] rate limit, per item. */
    private final Map<ItemIdentifier, Long> lastSkipDiag = new HashMap<>();

    /**
     * TEMP [Trickle DIAG]: why an item isn't requested because one is thought to be on its way. At most once a minute
     * per item. Remove once the "never requests again" stall is found.
     */
    private void logSkipDiag(ItemIdentifier item) {
        long now = _world.getWorld().getTotalWorldTime();
        Long last = lastSkipDiag.get(item);
        if (last != null && now - last < 20 * 60) {
            return;
        }
        lastSkipDiag.put(item, now);
        StringBuilder line = new StringBuilder("[Trickle DIAG] ").append(where())
                .append(" skipping ")
                .append(item.getFriendlyName())
                .append(": requested=")
                .append(_requestedItems.get(item));
        InFlight request = inFlight.get(item);
        if (request == null) {
            line.append(" | NO JOB TRACKED");
        } else {
            CraftingJob job = request.job;
            line.append(" | job #").append(job.getId())
                    .append(" done=")
                    .append(job.isDone())
                    .append(request.doneSince < 0 ? "" : " (for " + (now - request.doneSince) + " ticks)")
                    .append(" cancelled=")
                    .append(job.isCancelled())
                    .append(" ordersLost=")
                    .append(job.isOrdersLost())
                    .append(" replanShort=")
                    .append(job.isReplanShort())
                    .append(" crafting=")
                    .append(job.isCrafting())
                    .append(" sent=")
                    .append(job.getSent())
                    .append("/")
                    .append(job.getTotal());
            for (CraftingJob.WaitingOn open : job.getWaitingOn()) {
                line.append(" | open ").append(open.getType())
                        .append(" ")
                        .append(open.getItem() == null ? "?" : open.getItem().getFriendlyName())
                        .append(" at ")
                        .append(open.getPosition())
                        .append(" done ")
                        .append(open.getDone())
                        .append("/")
                        .append(open.getRequired())
                        .append(" idle ")
                        .append(open.getIdleTicks())
                        .append("t");
            }
        }
        LogisticsPipes.log.info(line.toString());
    }

    /** TEMP [Trickle DIAG]: where this module is, to tell suppliers apart in the log. */
    private String where() {
        return "(" + getX() + ", " + getY() + ", " + getZ() + ")";
    }

    @Override
    public void itemArrived(ItemIdentifierStack item, IAdditionalTargetInformation info) {
        super.itemArrived(item, info);
        ready = true;
    }

    @Override
    public void itemLost(ItemIdentifierStack item, IAdditionalTargetInformation info) {
        super.itemLost(item, info);
        ready = true;
    }

    @Override
    @SideOnly(Side.CLIENT)
    public IIcon getIconTexture(IIconRegister register) {
        return register.registerIcon("logisticspipes:itemModule/ModuleTrickleSupplier");
    }
}
