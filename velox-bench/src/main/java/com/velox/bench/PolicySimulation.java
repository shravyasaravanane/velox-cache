package com.velox.bench;

import com.velox.core.VeloxCache;
import com.velox.core.policy.Policy;

/** Replays a trace as cache-aside against one policy: shared by {@link MatrixRunner} and {@link TraceRunner}. */
final class PolicySimulation {

    private PolicySimulation() {
    }

    record Result(long hits, long misses) {
        double hitRate() {
            return (hits + misses) == 0 ? 1.0 : (double) hits / (hits + misses);
        }
    }

    /** Replays {@code trace} against a fresh cache of the given capacity and policy. */
    static Result simulate(int[] trace, int capacity, Policy policy) {
        var cache = new VeloxCache<Integer, Integer>(capacity, policy.create(capacity));
        long hits = 0;
        for (int key : trace) {
            if (cache.getIfPresent(key) != null) {
                hits++;
            } else {
                cache.put(key, key);
            }
        }
        return new Result(hits, trace.length - hits);
    }
}
