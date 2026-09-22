package com.velox.bench;

import com.velox.bench.workload.Workload;
import com.velox.bench.workload.Workloads;
import com.velox.core.VeloxCache;
import com.velox.core.policy.BeladyOracle;
import com.velox.core.policy.Policy;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.IntFunction;

/**
 * Runs every policy, at every capacity, over every workload, and writes one row of hit-ratio
 * results per combination to a CSV. This is the hit-<b>ratio</b> counterpart to
 * {@code ThreadScalingBenchmark} (Tier 2's JMH suite, which measures throughput): here,
 * nothing is timed, and the only question is how many of {@code length} lookups land in the
 * cache.
 *
 * <h2>Why every policy sees the identical trace</h2>
 *
 * A workload is generated <b>once</b> per (workload, capacity, seed) and replayed against
 * every policy in turn, unmodified. Comparing "LRU got 61%" against "ARC got 74%" means
 * nothing if the two numbers came from different traffic; identical input is what makes the
 * comparison a comparison at all. {@link BeladyOracle} runs over the exact same trace too,
 * as the {@code BELADY-OPTIMAL} row: the ceiling every policy's number is read against.
 *
 * <h2>Usage</h2>
 *
 * <pre>{@code
 * mvn -pl velox-bench -am package -DskipTests
 * java -cp velox-bench/target/benchmarks.jar com.velox.bench.MatrixRunner docs/benchmarks/data/hit-ratio-matrix.csv
 * }</pre>
 */
public final class MatrixRunner {

    private static final int TRACE_LENGTH = 300_000;
    private static final int[] CAPACITIES = {50, 200, 1_000};
    private static final long[] SEEDS = {1, 2, 3};

    /** One entry in the workload catalogue. The factory takes the capacity because {@code loop} must be sized to it. */
    private record WorkloadSpec(String label, IntFunction<Workload> factory) {
    }

    private static final List<WorkloadSpec> WORKLOADS = List.of(
            new WorkloadSpec("uniform", cap -> Workloads.uniform(5_000)),
            new WorkloadSpec("zipf-0.99", cap -> Workloads.zipfian(5_000, 0.99)),
            new WorkloadSpec("zipf-1.5", cap -> Workloads.zipfian(5_000, 1.5)),
            new WorkloadSpec("scan", cap -> Workloads.scan(20_000)),
            new WorkloadSpec("loop", cap -> Workloads.loop(cap + 1)),
            new WorkloadSpec("hot-set-shift", cap -> Workloads.hotSetShift(5_000, 100, 10_000)),
            new WorkloadSpec("two-pool", cap -> Workloads.twoPool(50, 5_000, 0.9)),
            new WorkloadSpec("scan-flood", cap -> Workloads.scanFlood(300, 0.99, 20_000, 2_000, 400)));

    private MatrixRunner() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            System.err.println("usage: MatrixRunner <output-csv-path>");
            System.exit(1);
        }
        Path outputPath = Path.of(args[0]);
        if (outputPath.getParent() != null) {
            Files.createDirectories(outputPath.getParent());
        }

        int totalRows = WORKLOADS.size() * CAPACITIES.length * SEEDS.length * (Policy.values().length + 1);
        int rowsWritten = 0;

        try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(outputPath, StandardCharsets.UTF_8))) {
            out.println("workload,workload_params,key_space,capacity,seed,policy,hits,misses,hit_rate");

            for (WorkloadSpec spec : WORKLOADS) {
                for (int capacity : CAPACITIES) {
                    Workload workload = spec.factory().apply(capacity);
                    for (long seed : SEEDS) {
                        int[] trace = workload.generate(seed, TRACE_LENGTH);

                        var oracle = BeladyOracle.simulate(boxed(trace), capacity);
                        writeRow(out, spec.label(), workload.name(), workload.keySpace(), capacity, seed,
                                "BELADY-OPTIMAL", oracle.hits(), oracle.misses());
                        rowsWritten++;

                        for (Policy policy : Policy.values()) {
                            long[] result = simulate(trace, capacity, policy);
                            writeRow(out, spec.label(), workload.name(), workload.keySpace(), capacity, seed,
                                    policy.displayName(), result[0], result[1]);
                            rowsWritten++;
                        }
                        System.out.printf("%s capacity=%d seed=%d done (%d/%d rows)%n",
                                spec.label(), capacity, seed, rowsWritten, totalRows);
                    }
                }
            }
        }

        System.out.println("Wrote " + rowsWritten + " rows to " + outputPath.toAbsolutePath());
    }

    /** @return {hits, misses} from replaying {@code trace} as cache-aside against a fresh cache */
    private static long[] simulate(int[] trace, int capacity, Policy policy) {
        var cache = new VeloxCache<Integer, Integer>(capacity, policy.create(capacity));
        long hits = 0;
        for (int key : trace) {
            if (cache.getIfPresent(key) != null) {
                hits++;
            } else {
                cache.put(key, key);
            }
        }
        return new long[] {hits, trace.length - hits};
    }

    private static List<Integer> boxed(int[] trace) {
        Integer[] boxed = new Integer[trace.length];
        for (int i = 0; i < trace.length; i++) {
            boxed[i] = trace[i];
        }
        return List.of(boxed);
    }

    private static void writeRow(PrintWriter out, String label, String params, int keySpace, int capacity,
            long seed, String policyName, long hits, long misses) {
        double hitRate = (hits + misses) == 0 ? 1.0 : (double) hits / (hits + misses);
        out.printf("%s,\"%s\",%d,%d,%d,%s,%d,%d,%.6f%n",
                label, params, keySpace, capacity, seed, policyName, hits, misses, hitRate);
    }
}
