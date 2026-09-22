package com.velox.server.loadgen;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.random.RandomGenerator;

/**
 * The Zipfian load generator M5.5 asks for: a plain-JDK command-line tool (no Spring context to
 * boot, so it starts instantly) that fires a Zipfian-distributed stream of real HTTP requests at
 * this application's own {@code GET /api/products/{id}}, so the headline throughput/latency
 * numbers in {@code docs/SYSTEM.md} are measured against a live server, not simulated in
 * process.
 *
 * <h2>How to run it</h2>
 *
 * <pre>{@code
 * # with velox-server already running (mvn -pl velox-server spring-boot:run):
 * java -cp velox-server/target/classes com.velox.server.loadgen.LoadGenerator \
 *     --requests=20000 --concurrency=32 --key-space=5000 --exponent=1.0 --cache=off
 *
 * java -cp velox-server/target/classes com.velox.server.loadgen.LoadGenerator \
 *     --requests=20000 --concurrency=32 --key-space=5000 --exponent=1.0 --cache=w_tiny_lfu
 * }</pre>
 *
 * <p>Run once with {@code --cache=off} and once with a real policy over the same id range, then
 * compare -- this pair of runs is exactly how the headline table in {@code docs/SYSTEM.md} is
 * produced. Follow up with {@code GET /api/stats} for the per-variant hit rate the generator
 * itself cannot see (it only observes wall-clock latency, not what happened inside the cache).
 */
public final class LoadGenerator {

    private LoadGenerator() {
    }

    public static void main(String[] args) throws InterruptedException {
        Options options = Options.parse(args);
        Result result = run(options);
        result.print(options);
    }

    static Result run(Options options) throws InterruptedException {
        ZipfianSampler sampler = new ZipfianSampler(options.keySpace, options.exponent, RandomGenerator.getDefault());
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();

        LongAdder completed = new LongAdder();
        LongAdder notFound = new LongAdder();
        LongAdder errors = new LongAdder();
        long[] latenciesNanos = new long[options.requests];
        AtomicLong nextSlot = new AtomicLong();
        Semaphore inFlight = new Semaphore(options.concurrency);
        CountDownLatch done = new CountDownLatch(options.requests);

        long start = System.nanoTime();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < options.requests; i++) {
                inFlight.acquire();
                long id = sampler.sample() + 1; // seeded ids are 1-based
                String url = options.baseUrl + "/api/products/" + id
                        + (options.cacheVariant == null ? "" : "?cache=" + options.cacheVariant);
                pool.submit(() -> {
                    long requestStart = System.nanoTime();
                    try {
                        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                                .timeout(Duration.ofSeconds(10))
                                .GET()
                                .build();
                        HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
                        if (response.statusCode() == 404) {
                            notFound.increment();
                        } else if (response.statusCode() >= 400) {
                            errors.increment();
                        }
                    } catch (Exception e) {
                        errors.increment();
                    } finally {
                        latenciesNanos[(int) nextSlot.getAndIncrement()] = System.nanoTime() - requestStart;
                        completed.increment();
                        inFlight.release();
                        done.countDown();
                    }
                });
            }
            done.await(30, TimeUnit.MINUTES);
        }
        long elapsedNanos = System.nanoTime() - start;

        int used = (int) Math.min(nextSlot.get(), latenciesNanos.length);
        long[] sorted = Arrays.copyOf(latenciesNanos, used);
        Arrays.sort(sorted);

        return new Result(completed.sum(), errors.sum(), notFound.sum(), sorted, elapsedNanos);
    }

    record Result(long completed, long errors, long notFound, long[] sortedLatenciesNanos, long elapsedNanos) {
        double throughputPerSecond() {
            return completed / (elapsedNanos / 1_000_000_000.0);
        }

        double averageLatencyMillis() {
            if (sortedLatenciesNanos.length == 0) {
                return 0.0;
            }
            long sum = 0;
            for (long nanos : sortedLatenciesNanos) {
                sum += nanos;
            }
            return (sum / 1_000_000.0) / sortedLatenciesNanos.length;
        }

        /** @param fraction e.g. 0.50 for p50, 0.99 for p99 */
        double percentileMillis(double fraction) {
            if (sortedLatenciesNanos.length == 0) {
                return 0.0;
            }
            int index = (int) Math.min(sortedLatenciesNanos.length - 1,
                    Math.floor(fraction * sortedLatenciesNanos.length));
            return sortedLatenciesNanos[index] / 1_000_000.0;
        }

        void print(Options options) {
            System.out.printf(
                    "%n=== load generator: %d requests, key-space=%d, exponent=%.2f, cache=%s ===%n",
                    options.requests, options.keySpace, options.exponent,
                    options.cacheVariant == null ? "(default)" : options.cacheVariant);
            System.out.printf("elapsed:      %.2f s%n", elapsedNanos / 1_000_000_000.0);
            System.out.printf("throughput:   %.1f req/s%n", throughputPerSecond());
            System.out.printf("avg latency:  %.3f ms%n", averageLatencyMillis());
            System.out.printf("p50 latency:  %.3f ms%n", percentileMillis(0.50));
            System.out.printf("p99 latency:  %.3f ms%n", percentileMillis(0.99));
            System.out.printf("errors:       %d%n", errors);
            System.out.printf("not found:    %d%n", notFound);
            System.out.println("next: GET " + options.baseUrl + "/api/stats for the per-variant hit rate");
        }
    }

    record Options(String baseUrl, String cacheVariant, int keySpace, double exponent, int requests, int concurrency) {

        static Options parse(String[] args) {
            Map<String, String> flags = new HashMap<>();
            for (String arg : args) {
                if (!arg.startsWith("--")) {
                    continue;
                }
                int eq = arg.indexOf('=');
                if (eq < 0) {
                    flags.put(arg.substring(2), "true");
                } else {
                    flags.put(arg.substring(2, eq), arg.substring(eq + 1));
                }
            }
            return new Options(
                    flags.getOrDefault("base-url", "http://localhost:8080"),
                    flags.get("cache"),
                    Integer.parseInt(flags.getOrDefault("key-space", "5000")),
                    Double.parseDouble(flags.getOrDefault("exponent", "1.0")),
                    Integer.parseInt(flags.getOrDefault("requests", "20000")),
                    Integer.parseInt(flags.getOrDefault("concurrency", "32")));
        }
    }
}
