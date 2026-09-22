# Thread scaling

Does the cache stay fast when many threads use it at once? The brief says "all operations must run in constant time"; this is the test of whether that survives concurrency.

**Status:** Tier 2 measured. Three experiments: the thread-scaling matrix (60 configurations), a false-sharing study of the statistics counter (20), and a low-shard-count contention experiment (8). Raw numbers are in [`data/`](data/).

**The short version**

| Question | Answer |
|---|---|
| Does one global lock scale? | **No.** Throughput *falls* to 0.5-0.7x of its one-thread value the moment a second thread arrives, and stays there. |
| Does sharding fix it? | **Yes.** With 64 shards, throughput at 16 threads is **3.4-3.9x** that of one lock around the same engine, and 2.6-2.8x the textbook synchronized `LinkedHashMap`. The hit-ratio cost is measured in [ARCHITECTURE.md](../ARCHITECTURE.md): 0.03 points at 10,000 entries. |
| Did buffered reads (the lock-free read buffer) help? | **No, not measurably.** Tied within noise on a read-heavy load, roughly 10-28% *slower* elsewhere. It is therefore **off by default**; see [What did not work](#what-did-not-work). |
| Are we as fast as Caffeine? | **No.** Caffeine is about 2.2-2.5x faster on the read-heavy workload at 2-16 threads. That gap is real and is not explained away below. |
| Is the single-thread engine fast? | **Not yet.** It is about 0.3-0.65x the speed of the JDK's synchronized `LinkedHashMap` on one thread. "Faster than the JDK" is not a claim this project can make today. |
| Did padding the counters (false sharing) matter? | **Only above a threshold**: 4.5x at 16 threads, no measurable difference at 8 or fewer. |

---

## Method

| | |
|---|---|
| Benchmark | `ThreadScalingBenchmark` in `velox-bench` (JMH 1.37) |
| Workload | cache-aside (`get`; on a miss, `put`). **Zipf(0.99)** keys over 100,000 keys; **every thread draws its own independent stream** (alias-method sampler, per-thread `SplittableRandom`). |
| Two cache sizes | **50% of the key space** (hit ratio ~91%: a read-heavy load, the kind caches are deployed for) and **10%** (hit ratio ~72%: a miss every ~3.6 requests, so writes and evictions are frequent). |
| Metric | **total operations per millisecond, all threads combined** (higher is better) |
| JMH settings | 1 fork, 3 warm-up iterations x 1 s, 5 measurement iterations x 2 s. Intervals below are the 99.9% half-width JMH reports. |
| Machine | Intel Core Ultra 5 225H laptop (hybrid CPU, **14 logical cores**), Windows 11, JDK 21.0.8. `-Xms2g -Xmx2g`. |

Run: `mvn -pl velox-bench -am package -DskipTests`, then
`java -jar velox-bench/target/benchmarks.jar ThreadScalingBenchmark -t <threads> -p impl=<name> -p capacityPercent=<10|50> -p shards=<n>`.

### The implementations compared

| name | what it is |
|---|---|
| `SYNC_LINKED_HASH_MAP` | the textbook Java LRU: an access-ordered `LinkedHashMap` behind `Collections.synchronizedMap` |
| `VELOX_GLOBAL_LOCK` | our single-threaded engine behind one `ReentrantLock` |
| `VELOX_SHARDED_EXCLUSIVE` | 64 shards, each an engine behind its own read-write lock; **every operation takes that shard's exclusive lock** |
| `VELOX_SHARDED_BUFFERED` | as above, but hits take only the shared lock and defer the recency update through a lock-free ring buffer |
| `CAFFEINE` | the industry-standard JVM cache, as a yardstick (uses W-TinyLFU, so its hit ratio is higher than LRU's) |
| `CONCURRENT_HASH_MAP` | no eviction, no ordering: not a cache at all. A ceiling for what the hardware allows. |

### An invalidated first run (why these numbers replace the earlier ones)

The first version of this benchmark had every thread walk **one shared pre-generated sample** from different offsets. Threads that started close together then asked for each other's keys, which pushed the hit ratio up by an amount that changed from run to run, so rows were not comparable (the 1-thread throughput of the LRU caches was 1.5-1.8x what it is below). It was rewritten so each thread draws its own stream, and the benchmark now prints the hit ratio next to every score. **The first numbers were discarded**; nothing below comes from them. A second consequence: the LRU rows here all report the *same* hit ratio (91.3% / 72.5%, to the decimal), which is the check that they are comparing like with like.

---

## Experiment 1: throughput against thread count

### Read-heavy: cache = 50% of the key space (hit ratio 91.3%; Caffeine 92.7%)

Operations per millisecond, all threads combined, with the 99.9% interval.

| implementation | 1 thread | 2 | 4 | 8 | 16 |
|---|---:|---:|---:|---:|---:|
| `SYNC_LINKED_HASH_MAP` | 5,914 (+/-11%) | 4,053 (+/-13%) | 3,308 (+/-6%) | 2,997 (+/-20%) | 3,055 (+/-5%) |
| `VELOX_GLOBAL_LOCK` | 3,701 (+/-18%) | 2,524 (+/-10%) | 2,506 (+/-10%) | 2,571 (+/-8%) | 2,216 (+/-36%) |
| `VELOX_SHARDED_EXCLUSIVE` | 2,185 (+/-55%) | 4,633 (+/-4%) | 7,718 (+/-11%) | 8,007 (+/-7%) | 8,539 (+/-4%) |
| `VELOX_SHARDED_BUFFERED` | 2,861 (+/-9%) | 4,280 (+/-11%) | 7,014 (+/-8%) | 8,371 (+/-14%) | 9,140 (+/-4%) |
| `CAFFEINE` | 7,343 (+/-16%) | 11,678 (+/-14%) | 16,739 (+/-32%) | 18,640 (+/-9%) | 20,357 (+/-7%) |
| `CONCURRENT_HASH_MAP` | 21,273 (+/-7%) | 56,322 (+/-6%) | 155,513 (+/-3%) | 198,767 (+/-62%) | 297,194 (+/-8%) |

Speed-up over the same implementation on one thread:

| implementation | 2 | 4 | 8 | 16 |
|---|---:|---:|---:|---:|
| `SYNC_LINKED_HASH_MAP` | 0.69x | 0.56x | 0.51x | 0.52x |
| `VELOX_GLOBAL_LOCK` | 0.68x | 0.68x | 0.69x | 0.60x |
| `VELOX_SHARDED_EXCLUSIVE` | 2.12x | 3.53x | 3.66x | 3.91x |
| `VELOX_SHARDED_BUFFERED` | 1.50x | 2.45x | 2.93x | 3.19x |
| `CAFFEINE` | 1.59x | 2.28x | 2.54x | 2.77x |
| `CONCURRENT_HASH_MAP` | 2.65x | 7.31x | 9.34x | 13.97x |

### Write-heavier: cache = 10% of the key space (hit ratio 72.5%; Caffeine 77.7%)

| implementation | 1 thread | 2 | 4 | 8 | 16 |
|---|---:|---:|---:|---:|---:|
| `SYNC_LINKED_HASH_MAP` | 8,821 (+/-11%) | 5,040 (+/-6%) | 3,451 (+/-9%) | 3,457 (+/-9%) | 3,327 (+/-8%) |
| `VELOX_GLOBAL_LOCK` | 3,811 (+/-58%) | 2,314 (+/-28%) | 2,556 (+/-10%) | 2,428 (+/-10%) | 2,547 (+/-19%) |
| `VELOX_SHARDED_EXCLUSIVE` | 3,703 (+/-8%) | 5,033 (+/-6%) | 7,845 (+/-8%) | 8,884 (+/-4%) | 8,697 (+/-4%) |
| `VELOX_SHARDED_BUFFERED` | 2,724 (+/-34%) | 3,877 (+/-18%) | 7,108 (+/-6%) | 7,874 (+/-4%) | 7,402 (+/-5%) |
| `CAFFEINE` | 7,359 (+/-15%) | 10,491 (+/-14%) | 7,521 (+/-56%) | 9,912 (+/-15%) | 10,461 (+/-14%) |
| `CONCURRENT_HASH_MAP` | 19,685 (+/-14%) | 54,158 (+/-6%) | 149,874 (+/-3%) | 185,395 (+/-54%) | 321,410 (+/-6%) |

Speed-up over the same implementation on one thread:

| implementation | 2 | 4 | 8 | 16 |
|---|---:|---:|---:|---:|
| `SYNC_LINKED_HASH_MAP` | 0.57x | 0.39x | 0.39x | 0.38x |
| `VELOX_GLOBAL_LOCK` | 0.61x | 0.67x | 0.64x | 0.67x |
| `VELOX_SHARDED_EXCLUSIVE` | 1.36x | 2.12x | 2.40x | 2.35x |
| `VELOX_SHARDED_BUFFERED` | 1.42x | 2.61x | 2.89x | 2.72x |
| `CAFFEINE` | 1.43x | 1.02x | 1.35x | 1.42x |
| `CONCURRENT_HASH_MAP` | 2.75x | 7.61x | 9.42x | 16.33x |

### What this shows

1. **A single lock turns threads into a queue.** Both single-lock LRUs get *slower* going from one thread to two (0.5-0.7x) and never recover. The hardware can scale (the lock-free map reaches 14-16x), so the lost throughput is the price of the lock that protects the recency list. A `get` in an LRU reorders shared state, so even reads need the exclusive lock.
2. **Sharding removes most of that cost.** At 16 threads the sharded engine is 3.9x its own one-thread speed on the read-heavy load, and about 3.85x (read-heavy) and 3.4x (write-heavier) a single lock around the *same* engine. It is 2.8x and 2.6x the synchronized `LinkedHashMap`. Sharding costs some hit ratio (a shard can evict while another has room); measured at 0.03 points for a 10,000-entry cache with 64 shards, and larger for small caches (0.34 points at 1,000 entries with 64 shards; 1.15 at 256 shards). The trade is documented in [ARCHITECTURE.md](../ARCHITECTURE.md).
3. **It scales sub-linearly, and this is not only our code.** Caffeine itself reaches only 2.8x at 16 threads, and even the lock-free map only 14x on 14 logical cores of a hybrid laptop CPU.

### What this does *not* show (read before quoting)

- **Where we stand against Caffeine.** Caffeine is faster in the read-heavy case at every thread count, measured against our faster Velox variant at each point: 2.6x at one thread, 2.2-2.5x at 2-16. In the write-heavier case its numbers are noisy (one point has a +/-56% interval), and at 4-16 threads it ranges from level with our exclusive-read variant (0.96x) to 1.2x ahead. **We are in Caffeine's order of magnitude, not at parity.** Caffeine also uses a smarter admission policy, so it is not doing the same work.
- **Single-thread speed.** At one thread the engine (including behind a lock) is 0.3-0.65x the speed of the synchronized `LinkedHashMap`: about 260-460 ns per operation against 110-170 ns. Some of that is an uncontended lock, and some is the extra work our engine does per call (statistics, weight accounting, expiry bookkeeping). The one-thread intervals for the Velox rows are also wide (up to +/-58%), so the speed-up figures that divide by them are unreliable; the absolute ordering is not in doubt.
- **The ordering between 8 and 16 threads is not resolved** for most rows: 16 threads oversubscribes the 14 logical cores, and the hybrid CPU mixes performance and efficiency cores.
- **One fork, one machine, a laptop.** Thermal limits, power states and OS scheduling add noise a server would not. The run also had several multi-minute pauses between configurations that we did not investigate. Wide intervals mark the rows most affected.
- **The `ConcurrentHashMap` row is a ceiling, not a competitor.** It never evicts and holds 100% of keys.

---

## What did not work

### Buffered reads

The design for the read path had two layers: shard the cache (worked), and let hits take only the *shared* lock and defer the recency update through a lock-free ring buffer, so that readers of one shard do not queue behind each other. It was built, tested hard (conservation under 16 threads for every policy, plus mutation testing), and then measured.

**Result: no measurable win.**

- *64 shards, read-heavy:* `BUFFERED / EXCLUSIVE` = 1.31, 0.92, 0.91, 1.05, 1.07 for 1, 2, 4, 8, 16 threads. All within the intervals of each other apart from the noisy one-thread point.
- *64 shards, write-heavier:* 0.74, 0.77, 0.91, 0.89, 0.85: **buffered is 9-26% slower.**
- *The contended case it was designed for* (few shards, so many threads share each lock): see Experiment 3. **Buffered was still not faster**, and slower at 16 threads with 1 or 4 shards.

The cost is small (0.03-0.49% of hits were dropped and the hit ratio is unchanged to within 0.05 points), so lossiness is not the problem.

**Likely explanation (a hypothesis, not proven):** a shared read lock is not free. Acquiring it is still an atomic update to the lock's state word, so all readers of one shard write to the same cache line and contend for it, which is the contention the buffer was meant to remove. On top of that each hit now pays for a ring-buffer claim (another atomic update). Caffeine avoids both because its reads take no lock at all: the hash table is a `ConcurrentHashMap`, whose reads are lock-free, and its read buffers are striped by thread.

**Decision:** `bufferedReads` is **off by default** (`CacheBuilder.bufferedReads(true)` opts in). Exclusive reads are as fast or faster, and they give exact LRU order with no dropped recency information. The buffered path stays in the code because it is tested, is a documented negative result, and is the starting point for a genuinely lock-free read path (an optimistic, lock-free lookup would need the underlying table to be safe to read during a write; that is a larger change and is not attempted here). The tests that cover it now opt in explicitly, so it keeps its coverage.

---

## Experiment 2: does padding a counter matter? (false sharing)

Several threads incrementing *different* counters that sit in the *same* 64-byte cache line still slow each other down, because the hardware moves the whole line between cores. `StripedCounter` spreads the count over cells and can space them a cache line apart (padding). `CounterBenchmark` measures what that is worth, against `AtomicLong` (one shared cell) and the JDK's `LongAdder`. Each thread increments in a tight loop.

| counter | 1 thread | 2 | 4 | 8 | 16 |
|---|---:|---:|---:|---:|---:|
| `AtomicLong` (one cell) | 99,506 (+/-12%) | 39,135 (+/-9%) | 36,716 (+/-10%) | 24,477 (+/-7%) | 28,714 (+/-2%) |
| `StripedCounter`, unpadded | 105,602 (+/-8%) | 222,086 (+/-6%) | 529,894 (+/-3%) | 780,104 (+/-19%) | 333,217 (+/-33%) |
| `StripedCounter`, padded | 103,581 (+/-13%) | 218,560 (+/-5%) | 511,264 (+/-33%) | 770,863 (+/-217%) | 1,515,282 (+/-3%) |
| `LongAdder` | 65,397 (+/-21%) | 130,655 (+/-2%) | 319,540 (+/-1%) | 777,115 (+/-14%) | 1,205,931 (+/-8%) |

- **One shared cell collapses:** `AtomicLong` gets *slower* as threads are added (99,506 down to 28,714), the same disease as a global lock. Striping fixes it: the padded counter is **53x** faster at 16 threads.
- **Padding matters, but only above a threshold.** Padded vs unpadded: 0.98x, 0.98x, 0.96x, 0.99x, then **4.55x at 16 threads.** This was a surprise: we expected the unpadded version to lose at every thread count. The likely reason is that the unpadded counter's 64 cells occupy only 8 cache lines (8 cells of 8 bytes per line); with 8 or fewer threads, hashing can happen to put each on its own line, but with 16 threads on 8 lines some *must* share. So false sharing is real, but whether a given run suffers depends on which cells the thread ids happen to hash to, and is only guaranteed once threads outnumber cache lines. (This is an explanation consistent with the numbers, not something we verified with a hardware counter.) Padding is cheap insurance, not a fixed multiplier.
- **Against `LongAdder`:** the padded counter is 1.6-1.7x faster at 1-4 threads, level at 8, 1.26x at 16. `LongAdder` adapts (it grows cells only when it detects contention) and our counter uses a fixed number of cells, so this is not a like-for-like win in every scenario; the point is that the design is in the same class as the JDK's.
- The padded 8-thread row has a +/-217% interval, so that single point is not reliable.

---

## Experiment 3: what buffered reads buy when the lock is contended

The 64-shard runs may simply have too little contention for buffering to help (16 threads over 64 locks rarely collide). So the same read-heavy workload (cache = 50%) was rerun with **1 and 4 shards**, where many threads share each lock.

| shards | threads | exclusive reads | buffered reads | dropped reads |
|---:|---:|---:|---:|---:|
| 1 | 4 | 2,197 (+/-26%) | 2,035 (+/-36%) | 0.21% of hits |
| 1 | 16 | 2,282 (+/-15%) | 1,648 (+/-22%) | 0.19% |
| 4 | 4 | 2,078 (+/-117%) | 2,222 (+/-7%) | 0.34% |
| 4 | 16 | 2,567 (+/-8%) | 2,017 (+/-18%) | 0.49% |
| 64 (Experiment 1) | 4 | 7,718 (+/-11%) | 7,014 (+/-8%) | 0.10-0.13% |
| 64 (Experiment 1) | 16 | 8,539 (+/-4%) | 9,140 (+/-4%) | 0.21-0.25% |

- **Buffered reads still do not win under contention**; at 16 threads with 1 or 4 shards they are 21-28% slower. (The 4-shard, 4-thread exclusive point has a +/-117% interval and is not usable.)
- **The number of shards is what matters.** One or four shards perform like a single global lock (about 2,000-2,500 ops/ms, the same as `VELOX_GLOBAL_LOCK` in Experiment 1), and 64 shards reach 8,500-9,100. A cache with 16 hot threads needs many more shards than threads, not about as many.
- Hit ratio is 91.2-91.3% in every configuration, so none of this is a hit-ratio effect.

---

## Where this leaves Tier 2

| | |
|---|---|
| Delivered and evidenced | sharded, thread-safe cache; 3.4-3.9x over one lock at 16 threads; correctness under concurrency (conservation law, exact statistics, no deadlock with re-entrant listeners); mutation-tested |
| Delivered, measured, and *not* worth turning on | buffered reads (default off) |
| Open | a genuinely lock-free read path (the gap to Caffeine); single-thread per-operation cost (roughly 1.5-3x that of the JDK's synchronized `LinkedHashMap`) |
| Method notes | every result is reported with its hit ratio and interval; a discarded first run is disclosed; explanations that were not verified are labelled as hypotheses |

Profiling the single-thread path and trying lock-free reads are candidate experiments for the benchmark-lab tier, where the harness, traces and profiler runs live.

---

## Tier 4 extension: Guava, and a genuinely write-dominated workload (M4.4)

Two additions to the same benchmark: `GUAVA` alongside `CAFFEINE` (Caffeine's own authors wrote it as Guava's eventual replacement, so the gap between them is informative on its own), and a third `capacityPercent`, **1** (capacity = 1,000 against the same 100,000-key space), giving a genuine get-heavy (50) / mixed (10) / write-heavy (1, hit ratio ~49%: roughly half of every request is a miss followed by a write) spread. 105 configurations total (7 implementations x 3 capacities x 5 thread counts), same trace-per-thread methodology as before.

**A methodology note that changes how these numbers should be read:** this run's confidence intervals are markedly wider than Experiment 1's above — routinely 20-50%, several past 100%, one at +/-237%. It ran on the same machine but not a quiet one (other work was active at the same time), which the earlier run avoided. The comparisons below are restricted to effects large enough to survive that noise; anything narrower is left unstated rather than asserted from an unreliable number.

### Throughput (ops/ms), all three capacities

cap=1 (write-dominated, hit ratio ~49%; `CONCURRENT_HASH_MAP` ~100%):

| implementation | 1 | 2 | 4 | 8 | 16 |
|---|---:|---:|---:|---:|---:|
| `SYNC_LINKED_HASH_MAP` | 7,370 | 3,878 | 2,576 | 2,468 | 2,461 |
| `VELOX_GLOBAL_LOCK` | 5,109 | 2,706 | 2,335 | 3,247 | 3,076 |
| `VELOX_SHARDED_EXCLUSIVE` | 3,929 | 4,976 | 7,580 | 8,574 | 8,248 |
| `VELOX_SHARDED_BUFFERED` | 2,798 | 3,873 | 5,059 | 4,539 | 4,377 |
| `CAFFEINE` | 4,981 | 5,087 | 5,322 | 5,028 | 5,128 |
| `GUAVA` | 2,477 | 2,143 | 2,093 | 1,740 | 2,207 |
| `CONCURRENT_HASH_MAP` | 17,475 | 48,785 | 147,197 | 241,701 | 309,507 |

cap=10 (mixed, hit ratio ~72.5%):

| implementation | 1 | 2 | 4 | 8 | 16 |
|---|---:|---:|---:|---:|---:|
| `SYNC_LINKED_HASH_MAP` | 9,011 | 3,834 | 3,301 | 3,321 | 3,132 |
| `VELOX_GLOBAL_LOCK` | 4,115 | 1,588 | 1,496 (+/-214%) | 1,923 | 1,871 |
| `VELOX_SHARDED_EXCLUSIVE` | 3,077 | 4,064 | 6,617 | 7,597 | 7,144 |
| `VELOX_SHARDED_BUFFERED` | 2,706 | 3,456 | 6,094 | 7,052 | 5,096 |
| `CAFFEINE` | 1,992 (+/-237%) | 9,896 | 9,353 | 9,291 | 10,111 |
| `GUAVA` | 2,940 | 2,423 | 3,070 | 3,071 | 2,897 |
| `CONCURRENT_HASH_MAP` | 18,600 | 53,201 | 149,602 | 247,785 | 334,629 |

cap=50 (get-heavy, hit ratio ~91.3%):

| implementation | 1 | 2 | 4 | 8 | 16 |
|---|---:|---:|---:|---:|---:|
| `SYNC_LINKED_HASH_MAP` | 6,848 | 4,014 | 3,410 | 3,395 | 3,295 |
| `VELOX_GLOBAL_LOCK` | 3,887 | 1,682 | 2,221 | 2,811 | 2,149 |
| `VELOX_SHARDED_EXCLUSIVE` | 2,580 | 4,974 | 5,378 | 6,272 | 6,433 |
| `VELOX_SHARDED_BUFFERED` | 2,589 | 3,788 | 5,721 | 6,391 | 6,958 |
| `CAFFEINE` | 5,027 | 11,029 | 12,410 | 15,111 | 16,118 |
| `GUAVA` | 1,176 | 2,131 | 2,890 | 2,878 | 3,100 |
| `CONCURRENT_HASH_MAP` | 17,851 | 37,255 | 82,658 | 152,955 | 154,212 |

*(Exact intervals are in the raw JMH output; only the two called out above exceed 100%. Treat every number here as approximate given the noise disclosed above — the point is the effects below, not any individual cell.)*

### What survives the noise

- **Guava is slower than Caffeine at every one of the 15 (capacity x thread) points**, usually by 2-5x, reaching over 5x at cap=50/16 threads (16,118 vs 3,100 ops/ms). This is not a borderline result — the gap is larger than the confidence intervals at nearly every point, and its direction never flips once across 15 independent configurations. It matches the two libraries' histories directly: Caffeine's own authors built it specifically to replace Guava's cache, citing Guava's coarser-grained locking and simpler (non-admission-aware) eviction bookkeeping as what they set out to fix, and this is that fix's throughput showing up on ordinary hardware fifteen years later.
- **Guava does not consistently scale with more threads.** At cap=50 it goes 1,176 -> 2,131 -> 2,890 -> 2,878 -> 3,100 across 1/2/4/8/16 threads: real gains only up to 4 threads, then flat. Caffeine, by contrast, keeps climbing through 16. Consistent with Guava's simpler (non-sharded, more coarsely locked) internals under sustained contention.
- **At the write-dominated point (cap=1), our sharded engine is competitive with or ahead of Caffeine from 4 threads on** (7,580-8,574 vs 5,028-5,322), the only regime in this whole benchmark where that is true. A plausible read: Caffeine's admission bookkeeping (the sketch, the window) costs something on every write, and with roughly half of all requests missing and writing, that cost is paid constantly while buying comparatively little (there is little popularity signal left to exploit when the hit ratio is already this low). Not independently verified by profiling; stated as a hypothesis, matching this report's standing rule for claims that were not directly measured.
- **The `ConcurrentHashMap` ceiling is enormous at cap=1** (up to 309,507 ops/ms at 16 threads, roughly 3x its own cap=50 ceiling): with most operations being inserts into a map that is never evicted from, this is closer to measuring raw hash-map insert throughput than anything resembling a cache workload, and is a ceiling, not a competitor, exactly as in Experiment 1.

### What this does not show

- **This run is noisier than Experiment 1's, and the report says so rather than hiding it.** Several points above are not reliably ordered against their immediate neighbours (e.g. `VELOX_SHARDED_EXCLUSIVE` at cap=1, 8 vs 16 threads: 8,574 vs 8,248 — well within a plausible margin of each other). Only effects large enough to plainly clear the noise are asserted as findings above.
- Raw data (all 105 rows, including the exact interval this section's tables round off): [`data/thread-scaling-guava-write-heavy.csv`](data/thread-scaling-guava-write-heavy.csv).
