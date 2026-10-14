package logisticspipes.modules;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which Smart Crafting module may currently send ingredients into a machine, so two recipes never mix inside one
 * machine. Server side only, not saved: after a reload the crafters simply claim again.
 * <p>
 * A crafter puts ingredients into several machines at once: the one its chassis faces and the machine of each Smart
 * Satellite its recipe uses. It claims all of them together or none of them, so no crafter ever holds part of what it
 * needs while waiting for the rest, which is what would let two crafters block each other for good.
 * <p>
 * Crafters that find a machine taken wait in one line, oldest first. Whenever something changes the line is walked in
 * order: a waiter gets its machines if none of them is held, and none is needed by someone ahead of it. That second
 * rule keeps the line fair - a crafter that needs several machines isn't overtaken for ever by crafters that need only
 * one of them - while crafters that share nothing with the ones ahead still go straight away. A crafter that has just
 * finished joins the back of the line, so it can't take the machines back first.
 * <p>
 * The owner refreshes its claims while it is loaded. A claim whose owner stopped refreshing (chunk unloaded, module
 * removed) expires, so a machine can't stay blocked forever. Age is measured on the owner's own world clock, since
 * dimensions need not share one.
 * <p>
 * Normal crafting modules don't use claims, so mixing a normal and a smart crafter on one machine can still collide.
 */
final class MachineClaims {

    /** Ticks without a refresh after which a claim is considered abandoned. */
    static final int CLAIM_TIMEOUT_TICKS = 200;

    private static final Map<Key, Claim> CLAIMS = new HashMap<>();
    /** Crafters waiting for their machines, oldest first. */
    private static final List<Waiter> LINE = new ArrayList<>();

    private MachineClaims() {}

    /**
     * Claims every one of these machines for the crafter if it can have them all now, otherwise puts it in line for
     * them (keeping its place if it already was). The machines a waiter needs are taken from its latest call, so a
     * recipe that changed its satellites waits for the right ones.
     *
     * @return true if the crafter now holds all of them
     */
    static boolean tryClaimAll(List<Key> machines, ModuleSmartCrafter crafter) {
        if (machines.isEmpty()) {
            return false;
        }
        if (holdsAll(machines, crafter)) {
            refresh(crafter);
            removeWaiter(crafter);
            return true;
        }
        Waiter waiter = waiterFor(crafter);
        if (waiter == null) {
            waiter = new Waiter(crafter);
            LINE.add(waiter);
        }
        waiter.machines = new ArrayList<>(machines);
        grant(crafter);
        return holdsAll(machines, crafter);
    }

    /** True if another crafter holds a live claim on the machine. */
    static boolean isHeldByOther(Key machine, ModuleSmartCrafter crafter) {
        ModuleSmartCrafter holder = holderOf(machine);
        return holder != null && holder != crafter;
    }

    /** The crafter holding a live claim on the machine, or null. */
    static ModuleSmartCrafter holderOf(Key machine) {
        Claim claim = CLAIMS.get(machine);
        return claim == null || !claim.isLive() ? null : claim.owner.get();
    }

    /** Keeps every claim the crafter holds alive. */
    static void refresh(ModuleSmartCrafter crafter) {
        long now = crafter.claimClock();
        for (Claim claim : CLAIMS.values()) {
            if (claim.owner.get() == crafter) {
                claim.lastSeen = now;
            }
        }
    }

    /** Gives up every machine the crafter holds and hands them on to whoever in line can now go. */
    static void releaseAll(ModuleSmartCrafter crafter) {
        boolean released = false;
        Iterator<Claim> it = CLAIMS.values().iterator();
        while (it.hasNext()) {
            if (it.next().owner.get() == crafter) {
                it.remove();
                released = true;
            }
        }
        if (released) {
            grant(null);
        }
    }

    /** Leaves the line, e.g. when the crafter has nothing left to craft. */
    static void stopWaiting(ModuleSmartCrafter crafter) {
        if (removeWaiter(crafter)) {
            // Its place may have been holding back crafters behind it.
            grant(null);
        }
    }

    /** True if another crafter is waiting for any machine this crafter holds. */
    static boolean hasOthersWaiting(ModuleSmartCrafter crafter) {
        Set<Key> held = heldBy(crafter);
        if (held.isEmpty()) {
            return false;
        }
        for (Waiter waiter : LINE) {
            ModuleSmartCrafter other = waiter.crafter.get();
            if (other != null && other != crafter && overlaps(waiter.machines, held)) {
                return true;
            }
        }
        return false;
    }

    /** How many other loaded crafters are in line for a machine this crafter holds or waits for. */
    static int waitingCount(ModuleSmartCrafter crafter) {
        Set<Key> mine = heldBy(crafter);
        Waiter own = waiterFor(crafter);
        if (own != null) {
            mine.addAll(own.machines);
        }
        int count = 0;
        for (Waiter waiter : LINE) {
            ModuleSmartCrafter other = waiter.crafter.get();
            if (other != null && other != crafter && overlaps(waiter.machines, mine)) {
                count++;
            }
        }
        return count;
    }

    /**
     * Walks the line oldest first and grants each waiter its machines when none is held and none is needed by an
     * earlier waiter. Expired claims are dropped first. Waiters other than {@code caller} are told through
     * {@link ModuleSmartCrafter#onClaimGranted}; the caller reads its answer from {@link #tryClaimAll}.
     */
    private static void grant(ModuleSmartCrafter caller) {
        CLAIMS.values().removeIf(claim -> !claim.isLive());
        Set<Key> reserved = new HashSet<>(CLAIMS.keySet());
        List<Waiter> granted = new ArrayList<>();
        Iterator<Waiter> it = LINE.iterator();
        while (it.hasNext()) {
            Waiter waiter = it.next();
            ModuleSmartCrafter crafter = waiter.crafter.get();
            if (crafter == null) {
                it.remove();
                continue;
            }
            boolean free = true;
            for (Key machine : waiter.machines) {
                if (reserved.contains(machine) && holderOf(machine) != crafter) {
                    free = false;
                    break;
                }
            }
            if (free) {
                long now = crafter.claimClock();
                for (Key machine : waiter.machines) {
                    CLAIMS.put(machine, new Claim(crafter, now));
                }
                it.remove();
                if (crafter != caller) {
                    granted.add(waiter);
                }
            }
            reserved.addAll(waiter.machines);
        }
        for (Waiter waiter : granted) {
            ModuleSmartCrafter crafter = waiter.crafter.get();
            if (crafter != null) {
                crafter.onClaimGranted(waiter.machines);
            }
        }
    }

    private static boolean holdsAll(List<Key> machines, ModuleSmartCrafter crafter) {
        for (Key machine : machines) {
            if (holderOf(machine) != crafter) {
                return false;
            }
        }
        return true;
    }

    private static Set<Key> heldBy(ModuleSmartCrafter crafter) {
        Set<Key> held = new HashSet<>();
        for (Map.Entry<Key, Claim> entry : CLAIMS.entrySet()) {
            if (entry.getValue().owner.get() == crafter && entry.getValue().isLive()) {
                held.add(entry.getKey());
            }
        }
        return held;
    }

    private static boolean overlaps(List<Key> machines, Set<Key> others) {
        for (Key machine : machines) {
            if (others.contains(machine)) {
                return true;
            }
        }
        return false;
    }

    private static Waiter waiterFor(ModuleSmartCrafter crafter) {
        for (Waiter waiter : LINE) {
            if (waiter.crafter.get() == crafter) {
                return waiter;
            }
        }
        return null;
    }

    /** @return true if the crafter was in line */
    private static boolean removeWaiter(ModuleSmartCrafter crafter) {
        boolean[] found = { false };
        LINE.removeIf(waiter -> {
            ModuleSmartCrafter c = waiter.crafter.get();
            if (c == crafter) {
                found[0] = true;
            }
            return c == null || c == crafter;
        });
        return found[0];
    }

    private static final class Claim {

        /** Weak, so a module that was dropped without releasing doesn't stay referenced. */
        private final WeakReference<ModuleSmartCrafter> owner;
        private long lastSeen;

        private Claim(ModuleSmartCrafter owner, long now) {
            this.owner = new WeakReference<>(owner);
            this.lastSeen = now;
        }

        /** Refreshed recently by an owner that still exists, on that owner's own clock. */
        private boolean isLive() {
            ModuleSmartCrafter crafter = owner.get();
            if (crafter == null) {
                return false;
            }
            long age = crafter.claimClock() - lastSeen;
            return age >= 0 && age <= CLAIM_TIMEOUT_TICKS;
        }
    }

    private static final class Waiter {

        /** Weak, so an unloaded crafter doesn't stay in line forever. */
        private final WeakReference<ModuleSmartCrafter> crafter;
        private List<Key> machines = new ArrayList<>();

        private Waiter(ModuleSmartCrafter crafter) {
            this.crafter = new WeakReference<>(crafter);
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
