package com.velox.server.livestats;

import com.velox.core.policy.ArcPolicy;
import com.velox.core.policy.EvictionPolicy;
import com.velox.core.policy.LruPolicy;
import com.velox.core.policy.TinyLfuPolicy;
import com.velox.core.structure.Node;
import com.velox.core.util.Hashing;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * M6.3: drives three real {@link EvictionPolicy} instances -- {@link LruPolicy},
 * {@link ArcPolicy}, {@link TinyLfuPolicy} -- directly, at a small, deliberately legible
 * capacity, and exposes their internal structure for the dashboard's animated Internals view.
 *
 * <h2>Why this needed no new introspection API on the policies themselves</h2>
 *
 * {@link LruPolicy#keysFromMruToLru()}, {@link ArcPolicy#t1Keys()}/{@code t2Keys()}/
 * {@code targetT1Size()}, and {@link TinyLfuPolicy#windowKeys()}/{@code probationKeys()}/
 * {@code protectedKeys()}/{@code frequency(K)} already exist, public, each documented
 * "for tests and dashboards" -- this class is exactly the dashboard that Javadoc anticipated.
 * The one genuine gap was ARC's ghost lists, which only exposed membership tests
 * ({@code isRecencyGhost}/{@code isFrequencyGhost}), not enumeration; {@code b1Keys()}/
 * {@code b2Keys()} closed that, a one-line addition to {@link GhostList} plus two delegating
 * methods, not a new design.
 *
 * <h2>Why LFU is not visualized here</h2>
 *
 * Unlike the other three, {@code LfuPolicy} has no existing "for tests and dashboards"
 * introspection, and its frequency-bucket shape does not fit the same region-list model the
 * other three share. Adding it would be new design, not reuse -- disclosed as out of scope for
 * this pass rather than silently dropped.
 *
 * <h2>Why a hand-driven orchestrator instead of a {@link com.velox.core.Cache}</h2>
 *
 * {@code VeloxCache} does not expose the {@link EvictionPolicy} instance it was built with --
 * deliberately, the same way {@link com.velox.server.cache.HotSwappableCache} only exposes
 * derived summaries ({@code shardSizes()}), never raw internals. Driving the three policies
 * directly through their public {@link EvictionPolicy} contract, with a minimal hand-written
 * hit/miss/evict orchestration matching {@code VeloxCache}'s own call sequence exactly (
 * {@code onAccess} on a hit; on a miss, {@code onMiss} then {@code beforeInsert}, then an
 * eviction loop of {@code selectVictim}/{@code admit}/{@code onEvict}/{@code onRemove}, then
 * {@code onInsert}), keeps that same boundary intact while still getting real, policy-accurate
 * behaviour -- not a simplified re-implementation of LRU/ARC/W-TinyLFU's logic, the actual
 * classes this project already built and tested in Tier 3.
 *
 * <h2>Step mode</h2>
 *
 * {@link #pause()} stops {@link #recordLiveTraffic(long)} (called from the real primary read
 * path) from feeding these policies, so the state on screen holds still. {@link #step(long)}
 * always applies regardless of pause state -- it is the explicit single-step a presenter drives
 * by hand to narrate one operation at a time.
 */
@Component
public class InternalsVisualizer {

    private final int capacity;
    private final AtomicBoolean paused = new AtomicBoolean(false);

    private final LruPolicy<Long, Long> lru = new LruPolicy<>();
    private final Map<Long, Node<Long, Long>> lruNodes = new HashMap<>();

    private final ArcPolicy<Long, Long> arc;
    private final Map<Long, Node<Long, Long>> arcNodes = new HashMap<>();

    private final TinyLfuPolicy<Long, Long> tinyLfu;
    private final Map<Long, Node<Long, Long>> tinyLfuNodes = new HashMap<>();

    public InternalsVisualizer(@Value("${velox.demo.visualizer-capacity:8}") int capacity) {
        this.capacity = capacity;
        this.arc = new ArcPolicy<>(capacity);
        this.tinyLfu = new TinyLfuPolicy<>(capacity);
    }

    /** Fed by the real primary read path; a no-op while {@link #paused}. */
    public synchronized void recordLiveTraffic(long key) {
        if (!paused.get()) {
            apply(key);
        }
    }

    /** Applies one access regardless of pause state -- the manual single-step. */
    public synchronized void step(long key) {
        apply(key);
    }

    public void pause() {
        paused.set(true);
    }

    public void resume() {
        paused.set(false);
    }

    public boolean isPaused() {
        return paused.get();
    }

    private void apply(long key) {
        drive(lru, lruNodes, key);
        drive(arc, arcNodes, key);
        drive(tinyLfu, tinyLfuNodes, key);
    }

    /**
     * The minimal hit/miss/evict orchestration {@code VeloxCache} itself runs, reproduced here
     * because {@code VeloxCache} will not hand back the {@link EvictionPolicy} it was built
     * with. See the class Javadoc for the exact call sequence this matches.
     */
    private void drive(EvictionPolicy<Long, Long> policy, Map<Long, Node<Long, Long>> nodes, long key) {
        Node<Long, Long> existing = nodes.get(key);
        if (existing != null) {
            policy.onAccess(existing);
            return;
        }

        policy.onMiss(key);
        policy.beforeInsert(key);

        Node<Long, Long> candidate = new Node<>(key, key, Hashing.spread(key));
        while (nodes.size() >= capacity) {
            Node<Long, Long> victim = policy.selectVictim();
            if (victim == null || !policy.admit(candidate, victim)) {
                return; // rejected: nothing stored, nothing evicted
            }
            policy.onEvict(victim);
            nodes.remove(victim.key());
            policy.onRemove(victim);
        }

        nodes.put(key, candidate);
        policy.onInsert(candidate);
    }

    public synchronized Snapshot describe() {
        return new Snapshot(
                paused.get(),
                capacity,
                new Snapshot.LruView(lru.keysFromMruToLru()),
                new Snapshot.ArcView(arc.t1Keys(), arc.t2Keys(), arc.b1Keys(), arc.b2Keys(), arc.targetT1Size()),
                new Snapshot.TinyLfuView(tinyLfu.windowKeys(), tinyLfu.probationKeys(), tinyLfu.protectedKeys(),
                        frequenciesOf(tinyLfu, tinyLfuNodes.keySet())));
    }

    private static Map<Long, Integer> frequenciesOf(TinyLfuPolicy<Long, Long> policy, java.util.Set<Long> keys) {
        Map<Long, Integer> result = new HashMap<>();
        for (Long key : keys) {
            result.put(key, policy.frequency(key));
        }
        return result;
    }

    public record Snapshot(boolean paused, int capacity, LruView lru, ArcView arc, TinyLfuView tinyLfu) {

        public record LruView(List<Long> mruToLru) {
        }

        public record ArcView(List<Long> t1, List<Long> t2, List<Long> b1, List<Long> b2, double targetT1Size) {
        }

        public record TinyLfuView(List<Long> window, List<Long> probation, List<Long> protectedKeys,
                Map<Long, Integer> frequencies) {
        }
    }
}
