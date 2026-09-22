package com.velox.bench.trace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads a request trace from a text file: one key per line, blank lines and lines starting
 * with {@code #} ignored.
 *
 * <h2>Why a trace file looks nothing like a {@link com.velox.bench.workload.Workload}</h2>
 *
 * Every generator in {@link com.velox.bench.workload.Workloads} is a <i>recipe</i>: given a
 * seed, it can produce as much traffic as asked for, on demand, matching a precise
 * mathematical pattern (Zipfian, a loop of a chosen period, and so on). A trace file is the
 * opposite — a <b>fixed recording</b> of what real requests actually were, with no seed and
 * no way to make more of it. Both are useful for different reasons: synthetic workloads
 * isolate one mechanism at a time by construction (that is why they were built first, in
 * {@code docs/benchmarks/RESULTS.md}); a real trace mixes every mechanism a real system
 * produces at once, which no synthetic generator can fully reproduce.
 *
 * <h2>Two key formats</h2>
 *
 * Real traces (the ARC paper's own traces, Twitter's published cache trace, block-storage
 * traces from the UMass repository) key their requests by everything from small integers
 * (block numbers) to opaque hashed strings. {@link #loadIntegerKeys} handles the first case
 * directly. {@link #loadStringKeysAsDenseIds} handles the second by assigning each distinct
 * string key a small integer id, in the order it is first seen — the same numbering a
 * {@code HashMap}-backed cache would use internally, and compact enough that the resulting
 * {@code int[]} plays directly through the exact same policy-comparison code the synthetic
 * workloads do (see {@code MatrixRunner}).
 */
public final class TraceLoader {

    private TraceLoader() {
    }

    /**
     * @param path a text file, one integer key per line ({@code #}-prefixed and blank lines skipped)
     * @return the keys, in file order
     */
    public static int[] loadIntegerKeys(Path path) throws IOException {
        List<String> lines = readSignificantLines(path);
        int[] keys = new int[lines.size()];
        for (int i = 0; i < lines.size(); i++) {
            keys[i] = Integer.parseInt(lines.get(i));
        }
        return keys;
    }

    /**
     * @param path a text file, one string key per line ({@code #}-prefixed and blank lines skipped)
     * @return the keys remapped to dense integer ids in order of first appearance, so the
     *         same string always maps to the same id and the ids fit compactly in an
     *         {@code int[]} regardless of how large or how few the distinct keys are
     */
    public static int[] loadStringKeysAsDenseIds(Path path) throws IOException {
        List<String> lines = readSignificantLines(path);
        Map<String, Integer> idOf = new HashMap<>();
        int[] keys = new int[lines.size()];
        for (int i = 0; i < lines.size(); i++) {
            keys[i] = idOf.computeIfAbsent(lines.get(i), unused -> idOf.size());
        }
        return keys;
    }

    private static List<String> readSignificantLines(Path path) throws IOException {
        try (var lines = Files.lines(path)) {
            return lines.map(String::trim)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .toList();
        }
    }
}
