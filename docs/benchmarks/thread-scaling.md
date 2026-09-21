# Thread scaling

Does the cache stay fast when many threads use it at once? The brief says "all operations must run in constant time"; this is the test of whether that survives concurrency.

**Status:** baseline measured (below). Sharded and lock-free-read results are added as each Tier 2 step lands.

---

## Method

| | |
|---|---|
| Benchmark | `ThreadScalingBenchmark` in `velox-bench` (JMH 1.37) |
| Workload | cache-aside (`get`, and `put` on a miss); **Zipf(0.99)** key stream over 100,000 keys; cache capacity 10,000 (10% of the key space), so eviction is constant |
| Metric | **total operations per millisecond, all threads combined** (higher is better) |
| JMH settings | 1 fork, 3 warm-up iterations x 1 s, 5 measurement iterations x 2 s |
| Machine | Intel Core Ultra 5 225H laptop (hybrid CPU, **14 logical cores**), Windows 11, JDK 21.0.8 |
| Heap | `-Xms2g -Xmx2g` |

Run: `mvn -pl velox-bench -am package -DskipTests`, then `java -jar velox-bench/target/benchmarks.jar ThreadScalingBenchmark -t <threads> -p impl=<name>`.

## Baseline: what a global lock does

| implementation | 1 thread | 2 threads | 4 threads | 8 threads | 16 threads |
|---|---:|---:|---:|---:|---:|
| `SYNC_LINKED_HASH_MAP` (textbook Java LRU) | 13,160 | 6,669 | 4,246 | 6,406 | 4,305 |
| `VELOX_GLOBAL_LOCK` (our engine behind one lock) | 6,944 | 3,445 | 3,341 | 3,440 | 3,613 |
| `CONCURRENT_HASH_MAP` (no eviction; the ceiling) | 106,375 | 244,687 | 678,385 | 1,248,769 | 1,859,529 |

Speed-up relative to the same implementation on one thread:

| implementation | 2 | 4 | 8 | 16 |
|---|---:|---:|---:|---:|
| `SYNC_LINKED_HASH_MAP` | 0.51x | 0.32x | 0.49x | 0.33x |
| `VELOX_GLOBAL_LOCK` | 0.50x | 0.48x | 0.50x | 0.52x |
| `CONCURRENT_HASH_MAP` | 2.30x | 6.38x | 11.74x | 17.48x |

### What this shows

- **Adding threads makes both LRU caches slower, not faster.** Going from one thread to two roughly *halves* throughput, and it never recovers. That is the collapse the brief's "constant time" requirement quietly hides: it is constant *per operation on one thread*, and a single lock turns concurrent threads into a queue.
- **The hardware can scale.** The lock-free map reaches ~17x on the same machine. So the lost throughput is entirely the price of the lock that protects the LRU list, not a limit of the CPU.
- **Why a global lock is unavoidable in naive LRU:** a `get` moves the entry to the front of the recency list, which is a write to shared structure. So even reads need the exclusive lock.

### What this does *not* show (read before quoting)

- **The confidence intervals are wide.** Most 99.9% half-widths are 20-45% of the score, and `SYNC_LINKED_HASH_MAP` at 8 threads is +/-186%. The large effect (1 -> 2 threads halving) is well outside the noise; the ordering **among** 2, 4, 8 and 16 threads is *not* resolved (for example 4,246 at 4 threads and 6,406 at 8 threads overlap completely). Do not read a trend into them.
- **One fork, one machine, a laptop.** A hybrid CPU with mixed performance and efficiency cores, thermal limits and OS scheduling adds noise a server would not. 16 threads oversubscribes the 14 logical cores.
- **The two LRU rows are comparable; the `ConcurrentHashMap` row is not.** It never evicts, so it holds ~100% of keys. It is a ceiling, not a competitor.

### An uncomfortable finding

At **one thread**, our engine (6,944 ops/ms, ~145 ns per operation) is about **half the speed** of Java's own synchronized `LinkedHashMap` (13,160 ops/ms, ~76 ns). Some of that is the uncontended lock cost; some is that our engine does more per call (statistics, weight accounting, expiry bookkeeping). It is still well under a microsecond, but "faster than the JDK" is not a claim we can currently make, and single-thread cost matters to the final comparison. To be profiled in Tier 4.
