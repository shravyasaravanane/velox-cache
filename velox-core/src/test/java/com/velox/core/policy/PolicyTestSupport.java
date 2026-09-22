package com.velox.core.policy;

import com.velox.core.VeloxCache;
import com.velox.core.stats.CacheStats;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.IntFunction;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Shared machinery for testing eviction policies by <b>differential testing</b>.
 *
 * <h2>The technique</h2>
 *
 * For each policy we write a second implementation that is deliberately
 * stupid: plain lists, linear scans, no cleverness, obviously correct by
 * inspection. Then we drive the real cache and the naive one with the same
 * random operations and demand they agree on <i>every</i> answer and on
 * <i>exactly which keys survive</i>.
 *
 * <p>The real policies are all O(1) with pointer surgery, so their bugs are
 * subtle: a mis-linked node, a bucket left behind, a stale index. Hand-written
 * test cases only catch the situations someone thought of. Random operations
 * against a trivially-correct model explore the ones nobody did.
 *
 * <p>Note what a naive model is <i>not</i>: it is not a second copy of the
 * same algorithm. It uses different data structures (an ArrayList and a
 * scan, rather than a bucket chain), so a mistake in the clever version is
 * very unlikely to be repeated in the simple one.
 */
final class PolicyTestSupport {

    private PolicyTestSupport() {
    }

    // ------------------------------------------------------------------
    //  The naive reference cache
    // ------------------------------------------------------------------

    /** The policy-specific part of a reference cache: who should be evicted. */
    interface NaiveModel {
        void onInsert(String key);

        void onAccess(String key);

        void onMiss(String key);

        void onRemove(String key);

        /** @return the key to evict; called only when the cache is full and non-empty */
        String victim();

        /** A new key is about to be stored (before room is made for it). */
        default void beforeInsert(String key) {
        }

        /** {@code key} is being evicted for lack of room; {@link #onRemove} follows. */
        default void onEvict(String key) {
        }
    }

    /** A bounded map that delegates its eviction decision to a {@link NaiveModel}. */
    static final class NaiveCache {
        private final int capacity;
        private final Map<String, Integer> data = new HashMap<>();
        private final NaiveModel model;

        NaiveCache(int capacity, NaiveModel model) {
            this.capacity = capacity;
            this.model = model;
        }

        Integer get(String key) {
            Integer value = data.get(key);
            if (value != null) {
                model.onAccess(key);
            } else {
                model.onMiss(key);
            }
            return value;
        }

        void put(String key, int value) {
            if (data.containsKey(key)) {
                data.put(key, value);
                model.onAccess(key);
                return;
            }
            model.beforeInsert(key);
            if (data.size() >= capacity) {
                String victim = model.victim();
                model.onEvict(victim);
                data.remove(victim);
                model.onRemove(victim);
            }
            data.put(key, value);
            model.onInsert(key);
        }

        void invalidate(String key) {
            if (data.remove(key) != null) {
                model.onRemove(key);
            }
        }

        int size() {
            return data.size();
        }

        Set<String> keys() {
            return new TreeSet<>(data.keySet());
        }
    }

    // ------------------------------------------------------------------
    //  Naive models
    // ------------------------------------------------------------------

    /** FIFO as an ArrayList in insertion order; the victim is the first element. */
    static final class NaiveFifo implements NaiveModel {
        private final List<String> order = new ArrayList<>();

        @Override
        public void onInsert(String key) {
            order.add(key);
        }

        @Override
        public void onAccess(String key) {
        }

        @Override
        public void onMiss(String key) {
        }

        @Override
        public void onRemove(String key) {
            order.remove(key);
        }

        @Override
        public String victim() {
            return order.get(0);
        }
    }

    /**
     * CLOCK as an ArrayList used as a ring, index 0 newest, last index the
     * "hand". Sweeping past a referenced entry rotates it to the front.
     */
    static final class NaiveClock implements NaiveModel {
        private final List<String> ring = new ArrayList<>();
        private final Set<String> referenced = new HashSet<>();

        @Override
        public void onInsert(String key) {
            ring.add(0, key);
        }

        @Override
        public void onAccess(String key) {
            referenced.add(key);
        }

        @Override
        public void onMiss(String key) {
        }

        @Override
        public void onRemove(String key) {
            ring.remove(key);
            referenced.remove(key);
        }

        @Override
        public String victim() {
            while (referenced.contains(ring.get(ring.size() - 1))) {
                String passed = ring.remove(ring.size() - 1);
                referenced.remove(passed);
                ring.add(0, passed);
            }
            return ring.get(ring.size() - 1);
        }
    }

    /**
     * LFU by brute force: every entry carries (frequency, lastUsedTick) and
     * the victim is found by scanning for the minimum. O(n), and correct by
     * inspection. Aging is modelled by explicitly sorting and re-ranking.
     */
    static final class NaiveLfu implements NaiveModel {
        private static final class Meta {
            int frequency;
            long lastUsed;
        }

        private final Map<String, Meta> meta = new HashMap<>();
        private final long agingPeriod;
        private long requests;
        private long tick;

        NaiveLfu(long agingPeriod) {
            this.agingPeriod = agingPeriod;
        }

        @Override
        public void onInsert(String key) {
            Meta m = new Meta();
            m.frequency = 1;
            m.lastUsed = ++tick;
            meta.put(key, m);
        }

        @Override
        public void onAccess(String key) {
            Meta m = meta.get(key);
            m.frequency++;
            m.lastUsed = ++tick;
            countRequest();
        }

        @Override
        public void onMiss(String key) {
            countRequest();
        }

        @Override
        public void onRemove(String key) {
            meta.remove(key);
        }

        @Override
        public String victim() {
            String best = null;
            Meta bestMeta = null;
            for (Map.Entry<String, Meta> e : meta.entrySet()) {
                Meta m = e.getValue();
                if (bestMeta == null
                        || m.frequency < bestMeta.frequency
                        || (m.frequency == bestMeta.frequency && m.lastUsed < bestMeta.lastUsed)) {
                    best = e.getKey();
                    bestMeta = m;
                }
            }
            return best;
        }

        private void countRequest() {
            if (agingPeriod > 0 && ++requests >= agingPeriod) {
                requests = 0;
                age();
            }
        }

        private void age() {
            // Rank by (old frequency, recency) BEFORE halving, then halve and
            // overwrite recency with the rank. Ties created by merging buckets
            // are then broken by old frequency first, then recency -- the
            // order the real policy's bucket rebuild produces.
            List<Meta> ordered = new ArrayList<>(meta.values());
            ordered.sort(Comparator.<Meta>comparingInt(m -> m.frequency).thenComparingLong(m -> m.lastUsed));
            long rank = 0;
            for (Meta m : ordered) {
                m.frequency = Math.max(1, m.frequency >> 1);
                m.lastUsed = rank++;
            }
            tick = ordered.size();
        }
    }

    /**
     * SLRU as two plain lists (index 0 = most recently used). Matches the real policy's
     * default 80% protected fraction.
     */
    static final class NaiveSlru implements NaiveModel {
        private final List<String> probation = new ArrayList<>();
        private final List<String> protectedList = new ArrayList<>();
        private final int protectedCapacity;

        NaiveSlru(int capacity) {
            this.protectedCapacity = (int) (capacity * 0.8);
        }

        @Override
        public void onInsert(String key) {
            probation.add(0, key);
        }

        @Override
        public void onAccess(String key) {
            if (protectedList.remove(key)) {
                protectedList.add(0, key);
                return;
            }
            probation.remove(key);
            protectedList.add(0, key);
            if (protectedList.size() > protectedCapacity) {
                String demoted = protectedList.remove(protectedList.size() - 1);
                probation.add(0, demoted);
            }
        }

        @Override
        public void onMiss(String key) {
        }

        @Override
        public void onRemove(String key) {
            if (!probation.remove(key)) {
                protectedList.remove(key);
            }
        }

        @Override
        public String victim() {
            if (!probation.isEmpty()) {
                return probation.get(probation.size() - 1);
            }
            return protectedList.get(protectedList.size() - 1);
        }
    }

    /**
     * 2Q as three plain lists: A1in (FIFO, index 0 = newest), a ghost list of evicted
     * A1in keys (index 0 = newest ghost), and Am (index 0 = most recently used).
     */
    static final class NaiveTwoQueue implements NaiveModel {
        private final List<String> a1in = new ArrayList<>();
        private final List<String> main = new ArrayList<>();
        private final List<String> ghosts = new ArrayList<>();
        private final int a1InTarget;
        private final int a1OutTarget;
        private String pendingGhostHit;

        NaiveTwoQueue(int capacity) {
            this.a1InTarget = Math.max(1, (int) (capacity * 0.25));
            this.a1OutTarget = (int) (capacity * 0.5);
        }

        @Override
        public void beforeInsert(String key) {
            if (ghosts.remove(key)) {
                pendingGhostHit = key;
            }
        }

        @Override
        public void onInsert(String key) {
            if (key.equals(pendingGhostHit)) {
                main.add(0, key);
            } else {
                a1in.add(0, key);
            }
            pendingGhostHit = null;
        }

        @Override
        public void onAccess(String key) {
            if (main.remove(key)) {
                main.add(0, key);
            }
            // A hit while still in A1in is deliberately left exactly where it is.
        }

        @Override
        public void onMiss(String key) {
        }

        @Override
        public void onEvict(String key) {
            if (a1in.contains(key)) {
                ghosts.add(0, key);
                while (ghosts.size() > a1OutTarget) {
                    ghosts.remove(ghosts.size() - 1);
                }
            }
        }

        @Override
        public void onRemove(String key) {
            if (!a1in.remove(key)) {
                main.remove(key);
            }
        }

        @Override
        public String victim() {
            if (a1in.size() > a1InTarget && !a1in.isEmpty()) {
                return a1in.get(a1in.size() - 1);
            }
            if (!main.isEmpty()) {
                return main.get(main.size() - 1);
            }
            return a1in.get(a1in.size() - 1);
        }
    }

    /**
     * LRU-K by a linear scan: each key's last (up to) k reference ticks, and the victim
     * found by comparing every tracked key's sort key directly. A single global counter
     * hands out a distinct tick to every reference, so no two entries can ever tie --
     * the real heap and this scan are guaranteed to agree on a unique minimum.
     */
    static final class NaiveLruK implements NaiveModel {
        private final int k;
        private final Map<String, Deque<Long>> history = new HashMap<>();
        private long clock;

        NaiveLruK(int k) {
            this.k = k;
        }

        private void record(String key) {
            Deque<Long> times = history.computeIfAbsent(key, unused -> new ArrayDeque<>());
            times.addLast(++clock);
            if (times.size() > k) {
                times.removeFirst();
            }
        }

        @Override
        public void onInsert(String key) {
            record(key);
        }

        @Override
        public void onAccess(String key) {
            record(key);
        }

        @Override
        public void onMiss(String key) {
        }

        @Override
        public void onRemove(String key) {
            history.remove(key);
        }

        @Override
        public String victim() {
            String best = null;
            long bestKey = Long.MAX_VALUE;
            for (Map.Entry<String, Deque<Long>> entry : history.entrySet()) {
                Deque<Long> times = entry.getValue();
                long oldest = times.peekFirst();
                long sortKey = times.size() == k ? oldest : oldest - Long.MAX_VALUE / 2;
                if (sortKey < bestKey) {
                    bestKey = sortKey;
                    best = entry.getKey();
                }
            }
            return best;
        }
    }

    /**
     * ARC as four plain lists, matching the same simplification {@link ArcPolicy} documents
     * (every eviction ghosts its victim; each ghost list is independently capped at
     * capacity, rather than the source paper's compound T1+B1/T2+B2 bounds).
     */
    static final class NaiveArc implements NaiveModel {
        private final List<String> t1 = new ArrayList<>();
        private final List<String> t2 = new ArrayList<>();
        private final List<String> b1 = new ArrayList<>();
        private final List<String> b2 = new ArrayList<>();
        private final int capacity;
        private double p;
        private int pendingArrival;      // 0 = fresh, 1 = from B1, 2 = from B2

        NaiveArc(int capacity) {
            this.capacity = capacity;
        }

        @Override
        public void beforeInsert(String key) {
            if (b1.contains(key)) {
                p = Math.min(capacity, p + Math.max(1.0, (double) b2.size() / Math.max(1, b1.size())));
                b1.remove(key);
                pendingArrival = 1;
                return;
            }
            if (b2.contains(key)) {
                p = Math.max(0.0, p - Math.max(1.0, (double) b1.size() / Math.max(1, b2.size())));
                b2.remove(key);
                pendingArrival = 2;
                return;
            }
            pendingArrival = 0;
        }

        @Override
        public void onInsert(String key) {
            if (pendingArrival == 0) {
                t1.add(0, key);
            } else {
                t2.add(0, key);
            }
            pendingArrival = 0;
        }

        @Override
        public void onAccess(String key) {
            if (t1.remove(key)) {
                t2.add(0, key);
            } else {
                t2.remove(key);
                t2.add(0, key);
            }
        }

        @Override
        public void onMiss(String key) {
        }

        @Override
        public void onEvict(String key) {
            if (t1.contains(key)) {
                b1.add(0, key);
            } else {
                b2.add(0, key);
            }
            while (b1.size() > capacity) {
                b1.remove(b1.size() - 1);
            }
            while (b2.size() > capacity) {
                b2.remove(b2.size() - 1);
            }
        }

        @Override
        public void onRemove(String key) {
            if (!t1.remove(key)) {
                t2.remove(key);
            }
        }

        @Override
        public String victim() {
            boolean evictFromT1 = !t1.isEmpty() && (pendingArrival == 2 ? t1.size() >= p : t1.size() > p);
            if (evictFromT1) {
                return t1.get(t1.size() - 1);
            }
            if (!t2.isEmpty()) {
                return t2.get(t2.size() - 1);
            }
            return t1.get(t1.size() - 1);
        }
    }

    // ------------------------------------------------------------------
    //  The differential driver
    // ------------------------------------------------------------------

    /**
     * Runs the real cache and a naive reference on identical random operations
     * and fails at the first disagreement.
     *
     * @return the real cache's final statistics, for reporting
     */
    static CacheStats runDifferential(
            String label,
            IntFunction<EvictionPolicy<String, Integer>> policyFactory,
            Supplier<NaiveModel> naiveModel,
            int capacity,
            int keySpace,
            int operations,
            long seed) {

        var real = new VeloxCache<String, Integer>(capacity, policyFactory.apply(capacity));
        var naive = new NaiveCache(capacity, naiveModel.get());
        var random = new Random(seed);

        for (int step = 0; step < operations; step++) {
            // Skewed keys: low indexes are far more popular. That is what makes
            // frequencies diverge, which is what LFU-style logic needs to be tested.
            String key = "k" + (int) (keySpace * Math.pow(random.nextDouble(), 2.0));
            int action = random.nextInt(10);

            if (action < 6) {
                assertEquals(naive.get(key), real.getIfPresent(key),
                        label + ": step " + step + " disagreed on get(" + key + ")");
            } else if (action < 9) {
                int value = random.nextInt(1_000_000);
                naive.put(key, value);
                real.put(key, value);
            } else {
                naive.invalidate(key);
                real.invalidate(key);
            }

            assertEquals(naive.size(), real.size(), label + ": size diverged at step " + step);

            if (step % 250 == 0) {
                assertEquals(naive.keys(), liveKeys(real, keySpace),
                        label + ": surviving keys diverged at step " + step);
                real.assertInvariants();
            }
        }

        real.assertInvariants();
        assertEquals(naive.keys(), liveKeys(real, keySpace), label + ": final surviving keys diverged");
        return real.stats();
    }

    /** Lists the keys the cache holds, using containsKey so recency is not disturbed. */
    static Set<String> liveKeys(VeloxCache<String, Integer> cache, int keySpace) {
        var present = new TreeSet<String>();
        for (int i = 0; i < keySpace; i++) {
            String key = "k" + i;
            if (cache.containsKey(key)) {
                present.add(key);
            }
        }
        return present;
    }
}
