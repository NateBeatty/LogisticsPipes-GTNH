package logisticspipes.modules;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Map;

/**
 * Which Smart Crafting module may currently send ingredients into a machine, so two recipes never mix inside one
 * machine. Server side only, not saved: after a reload the crafters simply claim again.
 * <p>
 * Crafters that find the machine taken wait in line. When the owner releases the machine it goes straight to the
 * crafter that has waited longest, so the one that just finished can't take it back first.
 * <p>
 * The owner refreshes its claim while it is loaded. A claim whose owner stopped refreshing (chunk unloaded, module
 * removed) expires, so a machine can't stay blocked forever.
 * <p>
 * Normal crafting modules don't use claims, so mixing a normal and a smart crafter on one machine can still collide.
 */
final class MachineClaims {

    /** Ticks without a refresh after which a claim is considered abandoned. */
    static final int CLAIM_TIMEOUT_TICKS = 200;

    private static final Map<Key, Claim> CLAIMS = new HashMap<>();

    private MachineClaims() {}

    /**
     * Claims the machine if it is free, expired or already ours. Otherwise puts the crafter in line for it.
     */
    static boolean tryClaim(Key machine, ModuleSmartCrafter crafter, long now) {
        Claim claim = CLAIMS.computeIfAbsent(machine, k -> new Claim());
        ModuleSmartCrafter owner = claim.owner.get();
        if (owner == crafter) {
            claim.lastSeen = now;
            return true;
        }
        if (owner == null || now - claim.lastSeen > CLAIM_TIMEOUT_TICKS || now < claim.lastSeen) {
            claim.removeWaiter(crafter);
            ModuleSmartCrafter next = claim.firstWaiter();
            if (next != null) {
                // Someone was already in line for the abandoned claim.
                claim.giveTo(next, machine, now);
                claim.addWaiter(crafter);
                return false;
            }
            claim.owner = new WeakReference<>(crafter);
            claim.lastSeen = now;
            return true;
        }
        claim.addWaiter(crafter);
        return false;
    }

    /** True if another crafter holds a live claim on the machine. */
    static boolean isHeldByOther(Key machine, ModuleSmartCrafter crafter, long now) {
        Claim claim = CLAIMS.get(machine);
        if (claim == null) {
            return false;
        }
        ModuleSmartCrafter owner = claim.owner.get();
        return owner != null && owner != crafter
                && now - claim.lastSeen <= CLAIM_TIMEOUT_TICKS
                && now >= claim.lastSeen;
    }

    static void refresh(Key machine, ModuleSmartCrafter crafter, long now) {
        Claim claim = CLAIMS.get(machine);
        if (claim != null && claim.owner.get() == crafter) {
            claim.lastSeen = now;
        }
    }

    /** True if another crafter is waiting for this machine. */
    static boolean hasOthersWaiting(Key machine, ModuleSmartCrafter crafter) {
        Claim claim = CLAIMS.get(machine);
        if (claim == null) {
            return false;
        }
        for (WeakReference<ModuleSmartCrafter> ref : claim.waiters) {
            ModuleSmartCrafter waiter = ref.get();
            if (waiter != null && waiter != crafter) {
                return true;
            }
        }
        return false;
    }

    /** How many loaded crafters are in line for this machine, not counting the one holding it. */
    static int waitingCount(Key machine) {
        Claim claim = CLAIMS.get(machine);
        if (claim == null) {
            return 0;
        }
        int count = 0;
        for (WeakReference<ModuleSmartCrafter> ref : claim.waiters) {
            if (ref.get() != null) {
                count++;
            }
        }
        return count;
    }

    /** Releases the machine if the crafter owns it, handing it to the crafter that has waited longest. */
    static void release(Key machine, ModuleSmartCrafter crafter, long now) {
        Claim claim = CLAIMS.get(machine);
        if (claim == null || claim.owner.get() != crafter) {
            return;
        }
        claim.removeWaiter(crafter);
        ModuleSmartCrafter next = claim.firstWaiter();
        if (next == null) {
            CLAIMS.remove(machine);
            return;
        }
        claim.giveTo(next, machine, now);
    }

    /** Leaves the line for a machine, e.g. when the crafter has nothing left to craft. */
    static void stopWaiting(Key machine, ModuleSmartCrafter crafter) {
        Claim claim = CLAIMS.get(machine);
        if (claim == null) {
            return;
        }
        claim.removeWaiter(crafter);
        if (claim.owner.get() == null && claim.waiters.isEmpty()) {
            CLAIMS.remove(machine);
        }
    }

    private static final class Claim {

        private WeakReference<ModuleSmartCrafter> owner = new WeakReference<>(null);
        private long lastSeen;
        /** Oldest first. Weak, so an unloaded crafter doesn't stay in line forever. */
        private final LinkedList<WeakReference<ModuleSmartCrafter>> waiters = new LinkedList<>();

        private void addWaiter(ModuleSmartCrafter crafter) {
            for (WeakReference<ModuleSmartCrafter> ref : waiters) {
                if (ref.get() == crafter) {
                    return;
                }
            }
            waiters.add(new WeakReference<>(crafter));
        }

        private void removeWaiter(ModuleSmartCrafter crafter) {
            waiters.removeIf(ref -> ref.get() == null || ref.get() == crafter);
        }

        /** Removes and returns the crafter that has waited longest, skipping unloaded ones. */
        private ModuleSmartCrafter firstWaiter() {
            Iterator<WeakReference<ModuleSmartCrafter>> it = waiters.iterator();
            while (it.hasNext()) {
                ModuleSmartCrafter waiter = it.next().get();
                it.remove();
                if (waiter != null) {
                    return waiter;
                }
            }
            return null;
        }

        private void giveTo(ModuleSmartCrafter next, Key machine, long now) {
            owner = new WeakReference<>(next);
            lastSeen = now;
            next.onClaimGranted(machine);
        }
    }

    /** A machine position: dimension and block coordinates. */
    static final class Key {

        private final int dimension;
        private final int x;
        private final int y;
        private final int z;

        Key(int dimension, int x, int y, int z) {
            this.dimension = dimension;
            this.x = x;
            this.y = y;
            this.z = z;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Key)) {
                return false;
            }
            Key other = (Key) o;
            return dimension == other.dimension && x == other.x && y == other.y && z == other.z;
        }

        @Override
        public int hashCode() {
            int result = dimension;
            result = 31 * result + x;
            result = 31 * result + y;
            result = 31 * result + z;
            return result;
        }
    }
}
