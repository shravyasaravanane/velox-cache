package com.velox.bench;

import com.velox.bench.trace.TraceLoader;
import com.velox.core.policy.BeladyOracle;
import com.velox.core.policy.Policy;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads a request trace from a file (see {@link TraceLoader}) and runs the same
 * policy-vs-Belady comparison {@link MatrixRunner} runs over synthetic workloads, against
 * real (or real-shaped) traffic instead.
 *
 * <h2>Usage</h2>
 *
 * <pre>{@code
 * mvn -pl velox-bench -am package -DskipTests
 * java -cp velox-bench/target/benchmarks.jar com.velox.bench.TraceRunner \
 *     docs/benchmarks/traces/sample-zipfian.trace int 50 200 1000
 * }</pre>
 *
 * <p>The second argument is {@code int} (one integer key per line, {@link TraceLoader#loadIntegerKeys})
 * or {@code string} (arbitrary text keys, {@link TraceLoader#loadStringKeysAsDenseIds}); the
 * rest are the capacities to test.
 */
public final class TraceRunner {

    private TraceRunner() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 3) {
            System.err.println("usage: TraceRunner <trace-file> <int|string> <capacity> [capacity ...]");
            System.exit(1);
        }
        Path tracePath = Path.of(args[0]);
        String format = args[1];
        int[] trace = switch (format) {
            case "int" -> TraceLoader.loadIntegerKeys(tracePath);
            case "string" -> TraceLoader.loadStringKeysAsDenseIds(tracePath);
            default -> throw new IllegalArgumentException("format must be 'int' or 'string', got '" + format + "'");
        };
        List<Integer> capacities = new ArrayList<>();
        for (int i = 2; i < args.length; i++) {
            capacities.add(Integer.parseInt(args[i]));
        }

        long distinctKeys = java.util.stream.IntStream.of(trace).distinct().count();
        System.out.printf("Loaded %s: %,d requests, %,d distinct keys%n", tracePath, trace.length, distinctKeys);
        System.out.println();

        for (int capacity : capacities) {
            System.out.printf("--- capacity %,d ---%n", capacity);
            var oracle = BeladyOracle.simulate(boxed(trace), capacity);
            System.out.printf("  %-16s %6.2f%%  (hits=%d misses=%d)%n",
                    "BELADY-OPTIMAL", oracle.hitRate() * 100, oracle.hits(), oracle.misses());
            for (Policy policy : Policy.values()) {
                var result = PolicySimulation.simulate(trace, capacity, policy);
                System.out.printf("  %-16s %6.2f%%  (hits=%d misses=%d)%n",
                        policy.displayName(), result.hitRate() * 100, result.hits(), result.misses());
            }
            System.out.println();
        }
    }

    private static List<Integer> boxed(int[] trace) {
        Integer[] boxed = new Integer[trace.length];
        for (int i = 0; i < trace.length; i++) {
            boxed[i] = trace[i];
        }
        return List.of(boxed);
    }
}
