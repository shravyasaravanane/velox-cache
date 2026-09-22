package com.velox.bench.workload;

/**
 * A named recipe for generating a request trace: a sequence of keys a benchmark plays
 * against a cache, one {@code get} per key (a cache-aside miss is followed by a {@code put}).
 *
 * <p>Every trace is generated fresh from a seed rather than stored, so the benchmark lab can
 * run millions of requests without ever materialising more than one {@code int[]} at a time,
 * and so the exact same trace can be replayed against every policy under test — the
 * comparison in {@code docs/benchmarks/RESULTS.md} means nothing unless every policy sees
 * <i>identical</i> traffic.
 *
 * @param name      a short, report-friendly label, e.g. {@code "zipf(0.99)"}
 * @param keySpace  the number of distinct keys the generator can produce; callers use this
 *                  to size a cache as a percentage of the working set
 * @param generator produces {@code length} keys from {@code seed}, deterministically
 */
public record Workload(String name, int keySpace, Generator generator) {

    /** @return {@code length} keys, deterministic in {@code seed}: the same call always returns the same trace */
    public int[] generate(long seed, int length) {
        return generator.generate(seed, length);
    }

    @FunctionalInterface
    public interface Generator {
        int[] generate(long seed, int length);
    }

    @Override
    public String toString() {
        return name;
    }
}
