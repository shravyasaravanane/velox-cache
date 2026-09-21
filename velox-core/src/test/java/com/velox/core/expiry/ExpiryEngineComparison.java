package com.velox.core.expiry;

import com.velox.core.structure.Node;

import java.util.Arrays;
import java.util.SplittableRandom;
import java.util.function.Supplier;

/**
 * An INFORMAL comparison of the two expiry engines. Run it with {@code main};
 * it is not a unit test and asserts nothing.
 *
 * <p>Read the numbers with care. This is a hand-rolled harness: it warms the
 * JIT and takes the median of several trials, but it has none of the
 * safeguards of JMH (no dead-code-elimination guards, no forking, no
 * statistical treatment). Tier 4 replaces it with a proper JMH suite. Use
 * these results to see the SHAPE of the trade-off, not to quote to three
 * significant figures.
 *
 * <p>Two scenarios:
 * <ol>
 *   <li><b>reschedule churn</b> -- n entries are scheduled, then random entries
 *       have their deadline pushed to a new random time. This is what
 *       expire-after-access does on every cache hit.</li>
 *   <li><b>fill and drain</b> -- n entries are scheduled with random deadlines
 *       within ten minutes, then time is advanced until all have fired.
 *       This is the cost of the whole lifecycle per entry.</li>
 * </ol>
 */
public final class ExpiryEngineComparison {

    private static final long TTL_NANOS = 600_000_000_000L;   // ten minutes
    private static final int TRIALS = 7;

    public static void main(String[] args) {
        int[] sizes = {1_000, 10_000, 100_000, 1_000_000};

        Supplier<ExpiryEngine<String, Integer>> heap = HeapExpiryEngine::new;
        Supplier<ExpiryEngine<String, Integer>> wheel = () -> new WheelExpiryEngine<>(1_000_000, 64, 0);

        System.out.println();
        System.out.println("Reschedule churn: ns per reschedule (median of " + TRIALS + " trials)");
        System.out.printf("  %-12s %12s %12s %12s%n", "entries", "heap", "wheel", "heap/wheel");
        for (int n : sizes) {
            double h = churn(heap, n);
            double w = churn(wheel, n);
            System.out.printf("  %-12d %12.1f %12.1f %11.2fx%n", n, h, w, h / w);
        }

        System.out.println();
        System.out.println("Fill and drain: ns per entry for schedule + expire (median of " + TRIALS + " trials)");
        System.out.printf("  %-12s %12s %12s %12s%n", "entries", "heap", "wheel", "heap/wheel");
        for (int n : sizes) {
            double h = fillAndDrain(heap, n);
            double w = fillAndDrain(wheel, n);
            System.out.printf("  %-12d %12.1f %12.1f %11.2fx%n", n, h, w, h / w);
        }
        System.out.println();
    }

    private static Node<String, Integer>[] nodes(int n) {
        @SuppressWarnings("unchecked")
        Node<String, Integer>[] nodes = (Node<String, Integer>[]) new Node[n];
        for (int i = 0; i < n; i++) {
            nodes[i] = new Node<>("k" + i, i, i);
        }
        return nodes;
    }

    private static double churn(Supplier<ExpiryEngine<String, Integer>> factory, int n) {
        int ops = Math.max(2_000_000, n * 2);
        double[] results = new double[TRIALS];

        for (int trial = -2; trial < TRIALS; trial++) {          // two warm-up trials, discarded
            // Fresh nodes every trial: a node remembers which engine it was scheduled
            // in, so reusing one across engines corrupts the second engine's counts.
            Node<String, Integer>[] nodes = nodes(n);
            ExpiryEngine<String, Integer> engine = factory.get();
            var random = new SplittableRandom(trial + 100);
            for (Node<String, Integer> node : nodes) {
                node.setExpiresAtNanos(1 + random.nextLong(TTL_NANOS));
                engine.schedule(node);
            }

            long started = System.nanoTime();
            for (int i = 0; i < ops; i++) {
                Node<String, Integer> node = nodes[random.nextInt(n)];
                node.setExpiresAtNanos(1 + random.nextLong(TTL_NANOS));
                engine.schedule(node);
            }
            long elapsed = System.nanoTime() - started;

            if (engine.size() != n) {
                throw new IllegalStateException("lost entries: " + engine.size());
            }
            if (trial >= 0) {
                results[trial] = (double) elapsed / ops;
            }
        }
        Arrays.sort(results);
        return results[TRIALS / 2];
    }

    private static double fillAndDrain(Supplier<ExpiryEngine<String, Integer>> factory, int n) {
        double[] results = new double[TRIALS];

        for (int trial = -2; trial < TRIALS; trial++) {
            Node<String, Integer>[] nodes = nodes(n);      // fresh per trial, see churn()
            ExpiryEngine<String, Integer> engine = factory.get();
            var random = new SplittableRandom(trial + 200);

            long started = System.nanoTime();
            for (Node<String, Integer> node : nodes) {
                node.setExpiresAtNanos(1 + random.nextLong(TTL_NANOS));
                engine.schedule(node);
            }
            int fired = 0;
            for (long now = 1_000_000_000L; now <= TTL_NANOS + 1_000_000_000L; now += 1_000_000_000L) {
                while (engine.pollExpired(now) != null) {
                    fired++;
                }
            }
            long elapsed = System.nanoTime() - started;

            if (fired != n) {
                throw new IllegalStateException("expected " + n + " to fire but " + fired + " did");
            }
            if (trial >= 0) {
                results[trial] = (double) elapsed / n;
            }
        }
        Arrays.sort(results);
        return results[TRIALS / 2];
    }
}
