# VeloxCache — Capstone Project Master Plan

> **Topic 39 — LRU Cache for a Web Server.**
> *"A web server wants to cache recently accessed data in memory to reduce database load, but memory is limited, so the least recently used item must be evicted when the cache is full — and all operations (get/put) must run in constant time. Design and implement this cache."*

**Deep-dive documents:**
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) — core engine, data structures, concurrency, testing, complexity tables
- [docs/ALGORITHMS.md](docs/ALGORITHMS.md) — all 10 eviction policies + the Belady oracle + the benchmark lab
- [docs/SYSTEM.md](docs/SYSTEM.md) — Spring Boot server, live dashboard, distributed cluster, persistence

---

## 0. The One-Line Pitch

**VeloxCache** — a from-scratch, zero-dependency, thread-safe, distributed caching engine in Java 21 with **10 pluggable eviction policies** (LRU, O(1) LFU, CLOCK, SLRU, 2Q, LRU-K, ARC, W-TinyLFU, FIFO, Random) measured against **Belady's provably optimal oracle**, sub-microsecond p99 reads, a consistent-hashing cluster layer, and a live dashboard that **animates the cache's internal data structures in real time**.

### Why this beats a normal submission

The assignment as written is **LeetCode 146** — a HashMap plus a doubly linked list, roughly 60 lines of code, solvable in 25 minutes. Thousands of students will submit exactly that. It satisfies the brief and impresses nobody.

This plan treats the brief as the **Tier-0 core** and builds a real system around it, answering the questions the brief implicitly raises but never states:

| The brief says | The real engineering question | What we build |
|---|---|---|
| "cache recently accessed data" | Is *recency* actually the best signal? | 10 policies benchmarked head-to-head against the theoretical optimum |
| "memory is limited" | Limited to how many **entries**, or how many **bytes**? | Weight-based eviction with a pluggable `Weigher` |
| "evict the least recently used" | LRU scores **0% hit rate** on a looping scan — is that acceptable? | Scan-resistant policies (ARC, W-TinyLFU) + proof via workload generators |
| "all operations in constant time" | O(1) for *one* thread, or for *sixteen*? | Sharding, striped locks, lossy read buffers, JMH-measured scaling |
| "a web server" | So there are concurrent requests, stampedes, and a real DB behind it | Spring Boot + Postgres demo with single-flight, TTL jitter, measured DB-load reduction |
| — | What happens when one cache box isn't enough? | Consistent hash ring with virtual nodes, replication, live rebalancing |

**The framing sentence for your viva and your resume:**

> *"I was asked to build an LRU cache. I built the thing an LRU cache is a component of — and then proved with benchmarks that plain LRU is the wrong default."*

---

## 1. Scope Tiers & Milestones

Build **strictly in tier order**. Every tier ends at a demoable, committable, defensible state. If you run out of time at any tier boundary, you still have a coherent project — never a half-finished one.

| Tier | Name | Effort | Cumulative verdict if you stop here |
|---|---|---|---|
| **0** | Core engine (LRU) | 2–3 days | ✅ Assignment fully satisfied. Full marks on correctness. |
| **1** | Engine features (TTL, policies, loader) | ~1 week | ✅ A real reusable library. Good project. |
| **2** | Concurrency & sharding | 4–5 days | ✅ Genuinely engineered. Strong project. |
| **3** | Smart policies (ARC, W-TinyLFU, Belady) | ~1 week | ✅ **Research-grade DSA depth.** Excellent project. |
| **4** | Benchmark lab + report | 4–5 days | ✅ **The resume chart exists.** Portfolio-worthy. |
| **5** | Spring Boot server + Postgres | 4–5 days | ✅ Proves real-world impact with numbers. |
| **6** | Live dashboard | ~1 week | ✅ **The "wow" demo.** Recruiters remember this. |
| **7** | Distributed cluster | ~1 week | ✅ Systems-design credibility. |
| **8** | Persistence + docs + report | 4–5 days | ✅ Ships like a real product. |

**Total ≈ 7–8 weeks at a steady pace.** Tiers 0–4 (~3 weeks) already produce a project stronger than the overwhelming majority of DSA capstones.

### Four non-negotiable rules

1. **No third-party cache or data-structure libraries in `velox-core`.** No Guava, no Caffeine, no Apache Commons. You write the hash map, the linked list, the heap, the sketches. `java.base` only. This is a *DSA* capstone — the data structures are the deliverable, not a dependency. (Caffeine and Guava appear **only** in `velox-bench` as benchmark competitors, which is a feature, not a cheat.)
2. **Every public method carries its time complexity in Javadoc.** `@implNote Amortized O(1); worst case O(n) on resize.`
3. **Every data structure has `assertInvariants()`**, enabled by `-Dvelox.assertions=true` and called after every operation in tests.
4. **Commit at every milestone.** A green CI history stretching across 8 weeks is itself a recruiter signal.

### Milestone checklist

<details>
<summary><b>Tier 0 — Walking Skeleton</b></summary>

- [x] **M0.1** Repo, Maven Wrapper (`mvnw`), 5 modules, `.gitignore`, MIT license, CI workflow
- [x] **M0.2** `Cache<K,V>` interface + `CacheStats` + `CacheBuilder` skeleton
- [x] **M0.3** `IntrusiveLinkedList` + `Node<K,V>` with full invariant checks (cycle detection via Floyd's)
- [x] **M0.4** `OpenAddressingMap` — Robin Hood hashing, tombstone-free backward-shift deletion
- [x] **M0.5** `EvictionPolicy` SPI + `LruPolicy` + single-threaded `VeloxCache`
- [x] **M0.6** Unit tests + differential test against a `LinkedHashMap` reference
- [x] ✅ **Checkpoint:** LeetCode-146 semantics verified by differential test against `LinkedHashMap` over 100,000 ops. **68 tests; 96.4% line / 82.9% branch coverage** on `velox-core`.
  - *Branch coverage note:* most remaining uncovered branches are the `if (!Invariants.ENABLED) return;` early-exits, which never execute because the suite always runs with assertions on. To close that gap properly, add a second Surefire execution running the suite with `-Dvelox.assertions=false` — which also verifies the production configuration works. Deferred to the Tier 2 CI setup.
</details>

<details>
<summary><b>Tier 1 — Engine Features</b></summary>

- [x] **M1.1** `FifoPolicy`, `RandomPolicy` (O(1) swap-remove), `ClockPolicy` — all differential-tested against naive models
- [x] **M1.2** `LfuPolicy` — true O(1) frequency-bucket list-of-lists, with aging. *Finding: on a shifting hot set, plain LFU scores 23.5% vs 64.0% aged; on a steady Zipf workload aging costs ~2 points (63.1% → 61.2%). Forgetting is a trade-off, not a free win.*
- [x] **M1.x** `PolicyContractTest` — every `Policy` enum value must pass a shared contract (parameterised), so Tier 3 policies are held to it automatically. **129 tests total, 96.8% line / 81.6% branch.**
- [x] **M1.3** TTL engine A: `IndexedMinHeap` (per-node back-pointer, O(log n) cancel/reschedule) + lazy expiry on read + sweep-on-write + `cleanUp()`; expire-after-write, expire-after-access, per-entry TTL, TTL jitter; injectable `Ticker` for deterministic tests. **164 tests.** Mutation-tested: 3 injected bugs, all caught.
  - *Scope change:* the **background sampling sweeper thread is deferred to Tier 2.** `VeloxCache` has no locking yet, so a second thread would race the caller. Until then, expired entries are removed lazily on read, opportunistically on every `put`, and on demand via `cleanUp()`. Trade-off: a cache that is neither read nor written keeps its dead entries in memory.
- [x] **M1.4** TTL engine B: `HierarchicalTimingWheel` (O(1) schedule/cancel), selectable via `expiryEngine(...)` / `timingWheel(tick, size)`. **Exact** (never fires early or late), wrap-safe, skips empty time (a 10-year jump is instant). Every cache-level expiry test runs against the heap and two wheel geometries. Mutation-tested: 5 injected bugs, all caught.
  - *Finding:* measured crossover with the heap is near **100,000 entries** (wheel 1.5x faster on reschedule at 100k, 1.7x at 1M; heap up to 5x cheaper over a whole lifecycle at 1k entries). Less dramatic than the 3x first guessed. Informal harness; JMH in Tier 4.
- [x] **M1.5** `Weigher<K,V>` + `Capacity` — capacity is a weight budget; count-bounded is the special case where every entry weighs 1, so there is one eviction mechanism, not two. Eviction became a loop (one big entry can evict several small ones). Mutation-tested: 7 injected bugs, all caught. **271 tests, 97.4% line / 84.4% branch.**
  - *Decisions made (each has a test):* an entry **heavier than the whole cache is refused without evicting anything** (flushing the cache for something that still would not fit is pure loss); overwriting with a **lighter/equal** value updates in place; overwriting with a **heavier** value is **remove-and-reinsert** (the policy may nominate the very entry being updated as its victim, so removing it first avoids evicting an entry mid-update; cost: it forfeits its policy history, e.g. an LFU count); a weigher must return **at least 1** (a zero-weight entry could never trigger eviction, so a cache of them would grow without bound); the weight subtracted on removal is the one **recorded on the node**, never a fresh weigher call, so the total cannot drift.
  - *Known limitation:* if an admission policy refuses a candidate on its 3rd victim, the first two are already evicted. Working out the whole victim set first is impossible for policies like CLOCK whose victim search mutates state. Relevant when W-TinyLFU arrives in Tier 3.
- [x] **M1.6** `SingleFlight` (stampede protection), wired into `VeloxCache.get(key, loader)`; new `loadFailureCount` / `coalescedCount` stats. **Measured: an expired hot key hit by 500 simultaneous requests costs 500 database queries without it, 1 with it.** Real-thread tests (a 500-thread stampede, a 16-thread x 5,000-call stress that asserts no key ever has two loaders at once); the concurrent suite (24 tests) ran 15 times back to back with 0 failures. **296 tests total, 97.5% line / 84.7% branch; `SingleFlight` at 100% branch.** Mutation-tested: 9 injected bugs, all caught (one of them only after a dedicated test was added, see below).
  - *Scope change:* **refresh-ahead and stale-while-revalidate are deferred to Tier 2** (see M2.8). Refresh-ahead needs a background executor reloading into a thread-safe cache; stale-while-error needs expired entries to be retained, which contradicts the current "expired data is never served" guarantee and needs an explicit opt-in grace period. Negative caching and the Bloom penetration guard are deferred to Tier 5, where "row not found" has a real meaning.
  - *Test-suite lessons:* (1) a mutant that broke recursion detection **hung the build for 10 minutes** because the test that covered it had no timeout, so recursion tests now fail in 10s instead; (2) the "release followers only after removing the entry" rule **survived mutation** until a test parked the leader inside `ConcurrentHashMap.remove` via a key whose `hashCode()` blocks, since the race window is a few nanoseconds; (3) the stampede test alone missed a broken atomic election, only the stress test caught it, which is why the stress test exists.
- [x] **M1.7** `RemovalListener` + `RemovalCause` reporting (EXPLICIT, REPLACED, EXPIRED, SIZE). Mutation-tested: 13 injected bugs, all caught. **324 tests.**
  - *Decisions (each has a test):* notifications are **queued and delivered after the operation finishes**, never mid-update, so a listener sees a consistent cache (and, in Tier 2, runs outside shard locks); a **re-entrant** call from a listener queues its removals and they are delivered in order after the current one returns, never nested (a dispatching flag); a listener throwing a **RuntimeException is logged and contained** (the caller's `put` still succeeds and other notifications still arrive), while an **Error propagates** but leaves the cache consistent and the undelivered notifications queued; **replacing a value with the same object reports nothing**; `put(k, null)` is now rejected because null is indistinguishable from a miss.
  - *The conservation law:* every value handed to the cache is **either still resident or reported exactly once**, including values it refused (an oversized or admission-rejected newcomer is reported as `SIZE`, else a resource-closing listener would leak precisely the ones the cache declined). Enforced by a randomised test under every policy, with weights, TTLs, replacements and invalidations mixed, cross-checked against the cache statistics.
  - *Known limitation:* a listener that throws is only logged (via `System.Logger`), not counted in `CacheStats`. Listeners run synchronously on the calling thread; a slow one slows that call.
- [x] ✅ **Checkpoint (Tier 1 complete):** 6 eviction policies (LRU, FIFO, Random, CLOCK, O(1) LFU, LFU with aging), two interchangeable TTL engines (indexed heap, hierarchical timing wheel), weight-based capacity, single-flight stampede protection, removal listeners. **324 tests, 97.7% line / 85.2% branch.** Refresh-ahead, stale-while-error and the background sweeper moved to M2.8.
</details>

<details>
<summary><b>Tier 2 — Concurrency</b></summary>

- [x] **M2.1** `ShardRouter` — power-of-two shards, shard chosen from the **high** hash bits (a test demonstrates that routing by the low bits collapses each shard's table onto a single slot). *Component only; the sharded cache itself is next.*
- [x] **M2.2** `ShardedCache`: N independent shards, each a full `VeloxCache` behind its own `ReentrantReadWriteLock`; hits take only the **shared** lock and defer the recency update through the lossy read buffer (M2.3) and drain state machine (M2.4), applied before every write. Misses and expired entries fall back to the exclusive path. Enabled with `CacheBuilder.concurrencyLevel(n)` (the default stays the single-threaded engine).
  - *Rules that keep it correct:* one lock at a time (no shard deadlocks); **user code never runs under a lock** (loaders run before the write lock is taken; removal listeners are queued under the lock and delivered after release, via a thread-safe queue whose delivery loop re-checks the queue after releasing its flag); the drain is capped at 3 passes so a stream of hits cannot starve writers; a `dead` flag on every node makes a buffered read record for an evicted entry harmless.
  - *Costs, stated in the Javadoc:* eviction is locally optimal per shard, not global; **an entry can weigh at most `maximumWeight / shards`**; reads can be dropped under contention (`droppedReads()`); `expireAfterAccess` disables buffered reads (it must reposition the entry on every hit); whole-cache operations are not atomic across shards.
- [x] **M2.3** `LossyReadBuffer` — multi-producer / single-consumer ring, CAS claim then publish, drops on full or contention. Multi-producer stress test checks nothing is invented, duplicated or reordered per producer, and that `consumed + dropped == offered`. *Component only; not yet wired into the cache.*
- [x] **M2.4** `DrainStatus` state machine (IDLE → REQUIRED → PROCESSING_TO_IDLE → PROCESSING_TO_REQUIRED) — a drain request that arrives *during* a drain is remembered, not lost. Checked against an explicit transition table over 200,000 random operations.
- [x] **M2.5** `StripedCounter` — padded cells (128 B apart) to avoid false sharing; `padded=false` exists so a benchmark can measure what the padding is worth. Exact under 16-thread contention. **Measured** (`CounterBenchmark`, 20 runs): 53x over a single `AtomicLong` at 16 threads; padding itself was worth **4.55x at 16 threads and nothing measurable at 8 or fewer** (surprising; the likely reason is that 64 unpadded cells span only 8 cache lines). Wired into `StatsCounter`.
- [x] **M2.6** Concurrency stress tests: 16 threads mixing gets, puts, TTL puts, loader calls, invalidations, `invalidateAll` and `cleanUp` while a clock thread advances time, under **every eviction policy**. The **conservation law holds under concurrency** (every value stored is resident or reported exactly once, ~171,000 values per policy), the weight budget is never exceeded (a monitor thread checks it during the run), hit/miss counts are **exact** under contention, a 300-thread stampede runs the loader once, and writers are not starved by a stream of hits. Mutation-tested: **12 injected bugs, all caught**, two of them only after adding deterministic test seams (see below).
  - *Lessons:* (1) the conservation test ended with `cleanUp()`, which flushes any leftover notification queue, so it could not see a delivery race; a hook that pauses the deliverer inside the nanosecond window made it testable. (2) A read that skips the shared lock is a real data race under the Java memory model but **cannot be caught by behaviour on x86** (it can only cause a false miss, which the exclusive fallback repairs); the lock is now checked directly by asking the lock state from inside the clock read. (3) A tool such as jcstress would be the right way to probe weak-memory behaviour; not used here.
- [x] **M2.7** JMH thread-scaling study (`docs/benchmarks/thread-scaling.md`, raw data in `docs/benchmarks/data/`): 60-configuration matrix (6 implementations x 5 thread counts x 2 workloads), with hit ratio reported beside every score; a false-sharing study (20 runs) and a low-shard-count experiment (8 runs).
  - *Results:* a global lock is 0.5-0.7x slower at 2+ threads; **64 shards give 3.4-3.9x over one lock at 16 threads** (2.6-2.8x the synchronized `LinkedHashMap`); Caffeine is still 2.2-2.5x faster on the read-heavy load; our single-thread path is 0.3-0.65x the speed of the JDK's synchronized `LinkedHashMap`.
  - *Negative result, kept:* **buffered reads were never clearly faster than exclusive reads** (tied at 64 shards, 10-28% slower elsewhere), so `bufferedReads` now defaults to **false**. The tests that cover the buffered path opt in explicitly, and the conservation stress test runs in both modes.
  - *Discarded run, disclosed:* the first benchmark version shared one fixed sample between threads and inflated hit ratios; rewritten with per-thread Zipf streams (alias sampler) and the first numbers dropped.
- [x] **M2.8** (partly; the rest deferred) Background expiry sweeper **done** (`CacheBuilder.backgroundCleanUp(interval)`, daemon thread per cache, `Cache` is now `AutoCloseable`; the sweep catches and logs failures because an exception would silently cancel the schedule; 6 tests). **Refresh-ahead and opt-in stale-while-error moved to Tier 5**, where a real loader (the database) exists to refresh from.
- [x] **M2.9** Test suite also runs with assertions **off** (a second Surefire execution, the production configuration): 391 tests pass in both modes.
- [ ] ✅ **Checkpoint: NOT MET as written.** *Near-linear scaling to 8 threads* was not achieved. Measured: 2.4-3.7x at 8 threads for the sharded engine (the lock-free `ConcurrentHashMap` reaches 9x and Caffeine 1.4-2.5x on the same 14-logical-core laptop, so linear scaling is not what this hardware gives even to the reference cache). What *is* met: the collapse under a global lock is gone, throughput is 3.4-3.9x that of one lock, and correctness under concurrency is checked hard. What is *not* met: parity with Caffeine, and single-thread speed.
</details>

<details>
<summary><b>Tier 3 — Smart Policies</b></summary>

- [x] **M3.1** `SlruPolicy` (probation 20% / protected 80% by default, either configurable): a scan of one-hit keys can only cycle through probation, so it never touches an entry proven by a second use. Two new `EvictionPolicy` hooks added for this tier, `beforeInsert(key)` (the arriving key, before room is made) and `onEvict(victim)` (fires only for a capacity eviction, never an explicit invalidate or an expiry — needed so ghost-list policies remember the right thing). Differential-tested against a naive two-list model; contract-tested automatically via the `Policy` enum.
- [x] **M3.2** `TwoQueuePolicy` (A1in FIFO / A1out ghost list / Am LRU), plus a new `GhostList` component (an `OpenAddressingMap` + `IntrusiveLinkedList` pair, key-only, O(1) remember/forget/age-out) shared by every ghost-list policy still to come (ARC). Stricter than SLRU: a hit while still in A1in does nothing at all; promotion to Am requires surviving an eviction to A1out and being asked for again. Am's own evictions do not mint ghosts — only A1in's do. Differential-tested against a naive three-list model.
- [x] **M3.3** `LruKPolicy` (K configurable, K=2 by default) — an entry is judged by recency only after its K-th reference; until then it is more evictable than anything that has ever reached K, however long ago. Backed by a hand-written indexed min-heap keyed by "time of the K-th most recent reference" (repeats `IndexedMinHeap`'s array/sift technique with its own key rather than generalising that class, since it is hard-wired to expiry deadlines and well covered by existing tests). The one policy where an operation is not O(1): `onAccess` is O(log n), documented as the SPI's sole exception. Differential-tested at K=2 and K=3 against a naive per-key history scan; K=1 checked to degenerate to plain LRU.
- [x] **M3.4** `ArcPolicy` — T1/T2/B1/B2, `p` (T1's target size) tuned continuously from the cache's own ghost hits: up on a B1 hit, down on a B2 hit, no fixed fraction to configure (contrast SLRU's 80% or 2Q's 25%/50%). Built on the same `GhostList` as 2Q. *Documented simplification:* every capacity eviction ghosts its victim and each ghost list is independently capped at capacity, rather than the source paper's exact compound T1+B1/T2+B2 bookkeeping — kept, and stated plainly in the Javadoc, because it preserves ARC's central idea (the adaptive `p`) without reproducing a 1993/2003 paper's bookkeeping from memory and risking a subtly wrong "faithful" claim. Differential-tested against a naive four-list model built to the same simplification, so the two are checked for self-consistency, not against an external ARC implementation.
- [x] **M3.5** `CountMinSketch` — 4-bit counters packed into `long[]` (16 per word), conservative update (only the rows already at the row-minimum are bumped, reducing collision noise), periodic halving via a 3-instruction whole-word bit trick (`(word >>> 1) & 0x7777...7L`, masking off the bit each nibble leaks into its neighbour). Estimate is proven never to underestimate the true count (a mathematical property of the structure, tested directly rather than statistically) and saturates at 15 without overflow.
- [x] **M3.6** `BloomFilter` doorkeeper for one-hit wonders — `seenBefore(key)` answers false on a key's first appearance and true from its second, in one hashing pass; this is what will keep `TinyLfuPolicy` from spending a sketch increment on traffic that never repeats. False-positive rate checked to stay far below its expected value at the chosen sizing; never a false negative (checked exactly, since `clear()` zeroing every bit makes a false positive provably impossible immediately afterwards).
- [x] **M3.7** `TinyLfuPolicy` — W-TinyLFU: 1% window LRU + a main region running its own probation/protected split (the same idea as `SlruPolicy`, reimplemented locally rather than delegated to an instance, since `Node.segment()` can only carry one policy's tag at a time) + admission duel via `CountMinSketch`/`BloomFilter`. **An honest finding, worked out and documented in the class Javadoc rather than assumed:** the duel is provably *unreachable* on a plain count-bounded cache (the arithmetic always balances the free-admission cascade exactly, every time) and only fires for a weighted cache whose real entry count can diverge from what the policy was sized for — which is not a contrived edge case, since `Capacity.weighted`'s `expectedEntries` is only ever a hint. On a count-bounded cache, one-hit-wonders are refused by the same probation/protected mechanism `SlruPolicy` already uses; the sketch and doorkeeper are still fed every reference but go unconsulted until a weighted cache makes the duel reachable. Both regimes are tested explicitly (a weighted-cache duel, tie and win cases; a long count-bounded run confirming the cascade never needs the duel) plus a differential test against a naive model that shares real `CountMinSketch`/`BloomFilter` instances (trusted, independently-tested primitives) while re-deriving the list bookkeeping independently.
- [x] **M3.8** `BeladyOracle` — Belady's 1966 offline-optimal algorithm (MIN/OPT): one backward pass computing each request's next-use index, one forward pass evicting whichever resident is needed furthest away, via a new `IndexedMaxHeap<K>` (the same array/sift technique as `IndexedMinHeap`, inverted to a maximum and keyed generically through a `HashMap` back-pointer rather than a `Node` field, since this runs entirely offline with no cache engine involved at all). Verified two ways: a hand-worked 9-request example (robust to the algorithm's own internal tie-breaks, since every tie in that example is between candidates equally "never needed again"), and — the real payoff — a test that runs **every one of the 10 online policies** over the same 20,000-request Zipfian trace and confirms **none of them ever beats the oracle's hit rate**, on any run.
- [x] ✅ **Checkpoint:** 10 policies (LRU, FIFO, RANDOM, CLOCK, LFU, LFU-AGED, SLRU, 2Q, LRU-K, ARC, W-TinyLFU — 11 by count, "10" in the original plan undercounted LFU-AGED as a variant rather than its own line) plus the theoretical ceiling (`BeladyOracle`) to measure them against. Every policy differential-tested against an independently-written naive reference model; every policy automatically covered by the shared contract/comparison tests via the `Policy` enum. 503 tests with assertions on, 500 with them off.
</details>

<details>
<summary><b>Tier 4 — Benchmark Lab</b></summary>

- [x] **M4.1** Workload generators in `com.velox.bench.workload.Workloads`: uniform, Zipfian (reusing Tier 2's `AliasSampler`, moved here and made public), scan, loop, hot-set-shift, two-pool, and an adversarial scan-flood (a Zipfian hot core periodically interrupted by a burst through a disjoint, never-repeating cold range — the standard scan-resistance test). Each returns a `Workload` (name + key space + a deterministic seed-to-trace generator), so the exact same trace can be replayed against every policy. 9 tests covering range, determinism and each generator's structural claim.
- [ ] **M4.2** Streaming trace loader + 2–3 checked-in sample traces + downloader script for the full ARC/Twitter sets
- [x] **M4.3** `MatrixRunner` — every one of the 11 policies x 3 capacities (50/200/1,000) x 8 workloads x 3 seeds, 300,000 requests each, cache-aside, written to `docs/benchmarks/data/hit-ratio-matrix.csv` (864 rows) alongside a `BELADY-OPTIMAL` ceiling row from `BeladyOracle` on the identical trace.
- [ ] **M4.4** JMH suite: get-heavy / mixed / write-heavy × 1,2,4,8,16 threads vs `ConcurrentHashMap`, synchronized `LinkedHashMap`, Guava, Caffeine
- [x] **M4.5** Four SVG charts (self-contained, no JS dependency, render natively on GitHub) + [`docs/benchmarks/RESULTS.md`](docs/benchmarks/RESULTS.md): methodology, per-workload findings, and an **investigated, not glossed-over, surprise** — ARC scores ~0% on the adversarial loop despite its scan-resistant reputation; a small instrumented case (capacity 5) traced the exact mechanism (T1 empties within one loop cycle, after which T2 thrashes in the loop's own order) and the finding is reported with that evidence rather than tuned away.
- [x] ✅ **Checkpoint:** the hit-ratio-vs-capacity chart with the Belady ceiling — [`charts/hit-rate-vs-capacity-zipf.svg`](docs/benchmarks/charts/hit-rate-vs-capacity-zipf.svg)
</details>

<details>
<summary><b>Tier 5 — Web Server + Database</b></summary>

- [ ] **M5.1** Spring Boot 3.3 app, Postgres (or H2 in PG mode), 1M-row seeded catalog
- [ ] **M5.2** Deliberately expensive query path (heavy join + artificial latency knob)
- [ ] **M5.3** Cache-aside + write-through + write-behind + invalidation on mutation
- [ ] **M5.4** A/B toggle `?cache=off|lru|arc|tinylfu` for live side-by-side comparison
- [ ] **M5.5** Zipfian load generator hitting the HTTP API
- [ ] **M5.6** `/api/stats`, `/api/admin/*`, `/api/chaos/*`, Actuator + Prometheus endpoint
- [ ] ✅ **Checkpoint:** measured DB-QPS collapse and p99 latency drop
</details>

<details>
<summary><b>Tier 6 — Live Dashboard</b></summary>

- [ ] **M6.1** SSE event stream, throttled to 10 Hz, bounded server-side ring buffer
- [ ] **M6.2** Screen 1 — Live Ops (ops/s, hit ratio, latency percentiles, fill bar, per-shard load)
- [ ] **M6.3** Screen 2 — **Internals Visualizer** (animated DLL / LFU buckets / ARC lanes / W-TinyLFU regions) with step mode
- [ ] **M6.4** Screen 3 — **Policy Arena** (shadow caches racing on live traffic)
- [ ] **M6.5** Screen 4 — Heat map + top-K hot keys + HLL cardinality
- [ ] **M6.6** Screen 6 — Benchmark Explorer (loads the Tier-4 CSVs)
- [ ] **M6.7** Screen 7 — **Chaos Panel** (stampede, scan flood, avalanche, penetration, kill node)
- [ ] ✅ **Checkpoint:** the 3-minute demo video
</details>

<details>
<summary><b>Tier 7 — Distributed Layer</b></summary>

- [ ] **M7.1** `ConsistentHashRing` — sorted `long[]` + binary search, 160 vnodes/node
- [ ] **M7.2** Key-distribution fairness test (std-dev across nodes < 5%)
- [ ] **M7.3** Rebalance test: prove only ~K/N keys move on join (vs ~(N-1)/N for modulo)
- [ ] **M7.4** `RemoteCacheClient` + router (local shard vs remote node)
- [ ] **M7.5** Heartbeat + failure detection + automatic ring repair
- [ ] **M7.6** Replication factor R=2 across **distinct physical** nodes + read fallback
- [ ] **M7.7** Screen 5 — Cluster ring view + kill-node button
- [ ] ✅ **Checkpoint:** 3–5 nodes, kill one live, watch it heal
</details>

<details>
<summary><b>Tier 8 — Durability & Polish</b></summary>

- [ ] **M8.1** `WriteAheadLog` — binary records + CRC32C + fsync policy (ALWAYS/EVERYSEC/NEVER)
- [ ] **M8.2** `Snapshotter` + `Compactor` + `RecoveryManager` (snapshot + WAL tail replay)
- [ ] **M8.3** Crash-recovery test (force-kill mid-write, verify no corruption)
- [ ] **M8.4** README with hero GIF, architecture diagram, benchmark table, 3-command quickstart
- [ ] **M8.5** `docs/adr/` — 10 Architecture Decision Records
- [ ] **M8.6** `docs/REPORT.pdf` — the ~20-page academic write-up
- [ ] **M8.7** 3-minute demo video + repo polish
</details>

---

## 2. System Architecture

```
┌────────────────────────────────────────────────────────────────────────┐
│                      velox-dashboard  (React + Vite + TS)              │
│   Live Ops │ Internals Viz │ Policy Arena │ Heat Map │ Ring │ Chaos    │
└───────────────────────────────▲────────────────────────────────────────┘
                     SSE (10 Hz) │ REST
┌───────────────────────────────┴────────────────────────────────────────┐
│                      velox-server  (Spring Boot 3.3)                   │
│  ┌──────────────┐  ┌───────────────┐  ┌──────────────┐  ┌───────────┐  │
│  │ Product API  │  │  Admin API    │  │ Metrics/SSE  │  │  Chaos    │  │
│  │ (cache-aside)│  │ (policy swap) │  │  (Actuator)  │  │ endpoints │  │
│  └──────┬───────┘  └───────┬───────┘  └──────┬───────┘  └─────┬─────┘  │
└─────────┼──────────────────┼─────────────────┼────────────────┼────────┘
          │                  │                 │                │
┌─────────▼──────────────────▼─────────────────▼────────────────▼────────┐
│                  velox-cluster   (optional, Tier 7)                    │
│   ConsistentHashRing (vnodes) → local shard? ─► core                   │
│                               └ remote node? ─► RemoteCacheClient ──►  │
│   Heartbeat / FailureDetector / Rebalancer / Replication(R=2)          │
└─────────────────────────────────▲──────────────────────────────────────┘
                                  │
┌─────────────────────────────────┴──────────────────────────────────────┐
│                     velox-core   (ZERO dependencies)                   │
│  ┌── Facade ────────────────────────────────────────────────────────┐  │
│  │  Cache<K,V> · CacheBuilder · VeloxCache · CacheConfig            │  │
│  └──────────────────────────┬───────────────────────────────────────┘  │
│  ┌── Concurrency ───────────▼───────────────────────────────────────┐  │
│  │  ShardRouter → Shard[0..N)                                       │  │
│  │  each Shard = { OpenAddressingMap, EvictionPolicy, RWLock,       │  │
│  │                 LossyReadBuffer, WriteBuffer, DrainStatus }      │  │
│  └──────────────────────────┬───────────────────────────────────────┘  │
│  ┌── Policies (SPI) ────────▼───────────────────────────────────────┐  │
│  │  LRU · FIFO · CLOCK · LFU(O1) · SLRU · 2Q · LRU-K · ARC ·        │  │
│  │  W-TinyLFU · Random · [BeladyOracle: offline only]               │  │
│  └──────────────────────────┬───────────────────────────────────────┘  │
│  ┌── Support ───────────────▼───────────────────────────────────────┐  │
│  │ expiry : IndexedMinHeap │ HierarchicalTimingWheel │ TtlJitter    │  │
│  │ loader : SingleFlight │ RefreshAhead │ StaleWhileRevalidate      │  │
│  │ stats  : StripedCounter │ LatencyHistogram │ SpaceSavingTopK │HLL│  │
│  │ persist: WriteAheadLog │ Snapshotter │ Compactor │ Recovery      │  │
│  └──────────────────────────────────────────────────────────────────┘  │
│  ┌── Primitive structures ──────────────────────────────────────────┐  │
│  │ IntrusiveLinkedList · OpenAddressingMap(Robin Hood) ·            │  │
│  │ IndexedMinHeap · CountMinSketch(4-bit) · BloomFilter ·           │  │
│  │ HyperLogLog · RingBuffer · ConsistentHashRing · AliasSampler     │  │
│  └──────────────────────────────────────────────────────────────────┘  │
└───────────────────────────────▲────────────────────────────────────────┘
                                │
┌───────────────────────────────┴────────────────────────────────────────┐
│   velox-bench  (JMH + trace replay + workload gen + Belady oracle)     │
│   competitors: ConcurrentHashMap, sync LinkedHashMap, Guava, Caffeine  │
└────────────────────────────────────────────────────────────────────────┘
                                │
                        ┌───────▼────────┐
                        │   PostgreSQL   │  1M-row catalog, deliberately slow join
                        └────────────────┘
```

### The five design decisions that define the system

1. **The policy is a plug-in, not the cache.** `EvictionPolicy` is an SPI. The cache owns *storage*; the policy owns *order*. This is why the Policy Arena and the 10-policy benchmark are even possible — and it is the single best architectural talking point in the project.
2. **Reads must not mutate shared structure synchronously.** A textbook LRU moves a node to the head on *every read*, so every read is a write, which means a lock, which means zero scalability. Velox records reads into a **lossy, lock-free ring buffer** and applies them in batches under the shard lock. Reads become effectively read-only.
3. **Shard by hash, lock per shard.** Contention drops by roughly a factor of N. Each shard is an independent, fully functional cache.
4. **Approximate is fine for metadata; exact is required for data.** Frequency counts (CMS), cardinality (HLL), top-K (Space-Saving) and latency percentiles (bucketed histogram) are all approximate and O(1)-space. The *values* are always exact.
5. **Every policy is measurable against Belady's optimum.** Without the oracle, "LRU got 68%" is a number. With it, "LRU captured 76% of the achievable maximum while W-TinyLFU captured 94%" is a *result*.

---

## 3. Repository Layout

```
velox/
├── mvnw / mvnw.cmd / .mvn/          # Maven Wrapper — no Maven install needed
├── pom.xml                          # parent POM, Java 21, module list
├── README.md                        # hero GIF, diagram, benchmarks, quickstart
├── LICENSE                          # MIT
├── .github/workflows/ci.yml         # build + test + coverage + JMH smoke
│
├── velox-core/                      # ★ THE DSA HEART — zero dependencies
│   └── src/main/java/com/velox/core/
│       ├── Cache.java  CacheBuilder.java  VeloxCache.java  CacheConfig.java
│       ├── CacheEntry.java  RemovalCause.java  RemovalListener.java
│       ├── policy/      (11 files — the SPI + 10 policies)
│       ├── structure/   (10 files — the primitives)
│       ├── concurrent/  (Shard, ShardRouter, LossyReadBuffer, DrainStatus, StripedCounter)
│       ├── expiry/      (TtlManager, IndexedMinHeap engine, TimingWheel engine, Jitter)
│       ├── loader/      (CacheLoader, SingleFlight, RefreshAhead)
│       ├── stats/       (StatsCounter, LatencyHistogram, SpaceSavingTopK, HyperLogLog, CacheStats)
│       ├── persistence/ (WriteAheadLog, Snapshotter, Compactor, RecoveryManager)
│       └── util/        (Hashing, Weigher, Ticker)
│
├── velox-cluster/                   # consistent hashing, membership, replication
├── velox-server/                    # Spring Boot demo + admin + SSE
├── velox-bench/                     # JMH, workload generators, trace replay, Belady
├── velox-dashboard/                 # React + Vite + TS + Recharts/D3
│
├── traces/                          # small sample traces
├── scripts/                         # download-traces.sh, run-cluster.ps1, seed-db.sql
└── docs/
    ├── ARCHITECTURE.md  ALGORITHMS.md  SYSTEM.md  COMPLEXITY.md  API.md
    ├── adr/ADR-001..010.md
    ├── benchmarks/RESULTS.md + charts/*.png
    └── REPORT.pdf
```

### Notes specific to your machine

Checked on 2026-09-21: **Java 21.0.8 ✅, Node v24 ✅, Maven ❌, Docker ❌.**

- **No Maven needed** — use the **Maven Wrapper** (`./mvnw`), which downloads Maven itself on first run. Generate it once with any Maven distribution, or commit the standard `.mvn/wrapper` files.
- **No Docker needed** — run the 3–5 cluster nodes as separate JVM processes on ports 8081–8085 via `scripts/run-cluster.ps1`. Functionally identical for the demo, and one less thing to install.
- **Database** — if installing Postgres is a hassle, use **H2 in PostgreSQL compatibility mode** or embedded Postgres. The plan works either way; just say which you used in the report.

---

## 4. The Data Structure Catalogue

This is the table your examiner will look at first. **Every one of these is implemented by hand.**

| # | Data structure | Where it's used | Key operations & complexity |
|---|---|---|---|
| 1 | **Intrusive doubly linked list** | LRU recency order; every list-based policy | `moveToHead`/`unlink` **O(1)** — *intrusive* means the node **is** the entry, so there's no lookup to find it |
| 2 | **Open-addressing hash map (Robin Hood)** | key → entry, the primary index | get/put **O(1)** avg; backward-shift deletion, no tombstones; linear probing is cache-friendly |
| 3 | **Frequency-bucket list-of-lists** | O(1) LFU | increment / evict **O(1)** — a DLL of frequency nodes, each holding a DLL of entries |
| 4 | **Four coupled LRU lists + 2 ghost lists** | ARC | all ops **O(1)**; adaptive parameter `p` self-tunes recency vs frequency |
| 5 | **Count-Min Sketch** (4-bit counters packed in `long[]`) | W-TinyLFU frequency estimation | estimate/increment **O(d)**, d=4; **frequency history for ~1M keys in ~8 KB** |
| 6 | **Bloom filter** | TinyLFU doorkeeper; cache-penetration guard | **O(k)**, k=3–7 hashes; zero false negatives — exactly the guarantee needed |
| 7 | **Segmented LRU (SLRU)** | W-TinyLFU main region; standalone policy | **O(1)**; probation 20% / protected 80% |
| 8 | **Circular buffer + reference bits** | CLOCK / Second-Chance | amortized **O(1)**; 1 bit per entry instead of a list — the concurrency-friendly LRU approximation |
| 9 | **Indexed binary min-heap** | TTL expiry with cancellation | insert/extract **O(log n)**; *cancel / decrease-key **O(log n)*** thanks to a key→index back-pointer |
| 10 | **Hierarchical timing wheel** | TTL engine B (Kafka/Netty style) | schedule/cancel **O(1)**, tick **O(1) amortized**; 4 levels: 1s/1m/1h/1d |
| 11 | **Max-heap over next-use distance** | Belady's optimal oracle | **O(log c)** per access; needs an O(n) backward pass to build next-use indices |
| 12 | **Space-Saving / Stream-Summary** | Top-K hottest keys | **O(1)** per update, **O(k)** space |
| 13 | **HyperLogLog** | Unique-key cardinality on the stats panel | **O(1)**; 2^14 registers → ±0.81% error in 16 KB |
| 14 | **Log-linear bucketed histogram** (HdrHistogram-style) | p50/p90/p99/p999 latency | record **O(1)**, query **O(buckets)**; fixed relative error |
| 15 | **Consistent hash ring** (sorted `long[]` + binary search) | Cluster sharding | lookup **O(log V)**; 160 vnodes/node → only **K/N** keys move on rebalance |
| 16 | **MPSC lossy ring buffer** | Batching read-access records | append **O(1)** lock-free CAS; drops on full (correct, because it is a heuristic) |
| 17 | **Inverted index (tag → key set)** | Tag-based bulk invalidation | invalidate-by-tag **O(m)** in matched keys |
| 18 | **Vose's alias method** | O(1) Zipfian sampling in the workload generator | build **O(n)**, sample **O(1)** — beats binary search on the CDF |
| 19 | **Append-only log + snapshot (WAL)** | Persistence & crash recovery | append **O(1)**; recovery O(snapshot + tail) |
| 20 | **Merkle tree** *(stretch)* | Anti-entropy between replicas | compare **O(log n)** to locate divergence |

**Algorithms layered on top:** Robin Hood insertion, backward-shift deletion, Floyd's cycle detection (invariant checks), MurmurHash3 finalizer mixing, conservative-update CMS, CMS periodic halving (aging), ARC's adaptive `p` update rule, Belady's MIN, reservoir sampling, exponentially weighted moving averages for rate meters.

---

## 5. Resume Bullets & Interview Prep

### Resume — pick two or three

> **VeloxCache — High-Performance Distributed Cache Engine** · *Java 21, Spring Boot, React* · github.com/you/velox
>
> - Built a zero-dependency concurrent cache engine implementing **10 eviction policies** (LRU, O(1) LFU, CLOCK, SLRU, 2Q, LRU-K, ARC, W-TinyLFU) behind a pluggable admission/eviction SPI; benchmarked all of them against **Belady's offline optimum**, showing W-TinyLFU captures **94% of the achievable hit ratio vs LRU's 76%** on production Twitter cache traces.
> - Achieved **O(1) amortized** get/put under concurrency via hash-based sharding, **lock-free lossy read buffers**, and cache-line-padded striped counters, reaching **[X]M ops/sec at 16 threads** — **[Y]×** faster than a synchronized `LinkedHashMap` and within **[Z]×** of Caffeine.
> - Hand-implemented **Count-Min Sketch** (4-bit packed counters, conservative update, periodic halving), Bloom filters, HyperLogLog, hierarchical timing wheels, indexed min-heaps, Robin Hood open-addressed hashing, and a consistent-hash ring with virtual nodes — **no third-party data-structure libraries**.
> - Cut demo API **p99 latency 84ms → 2.1ms** and **database load by 94%** under Zipf(0.99) traffic; implemented single-flight request coalescing that reduced a 1,000-request cache stampede to **a single database query**.
> - Shipped a real-time React dashboard that **animates the cache's internal data structures**, races all 10 policies simultaneously on live traffic via shadow caches, and visualizes consistent-hash rebalancing on node failure.

*(Fill in [X]/[Y]/[Z] and every latency figure with numbers you actually measured. Never ship a placeholder.)*

### The 12 questions you must answer cold

1. **Why a doubly linked list and not an array or a singly linked list?** → O(1) unlink requires a `prev` pointer; an array needs O(n) shifting. And why *intrusive*: the map hands you the node, so there is no search step.
2. **Why does your LRU still need a hash map at all?** → the list gives order in O(1) but lookup in O(n); the map gives lookup in O(1) but no order. Neither works alone. **This is the core insight of the original problem.**
3. **Is it really O(1)? What's the worst case?** → hash collisions degrade to O(n); Robin Hood bounds probe-length variance; resize is O(n) but amortized O(1). Distinguish *amortized* from *worst case* precisely — most candidates don't.
4. **It's O(1) for one thread. What happens with 16?** → every read mutates the LRU order → a lock → serialisation. Fixed by sharding + lossy read buffers. **This is the question that separates you from everyone who submitted LeetCode 146.**
5. **Why is it acceptable to *drop* read records?** → recency is a heuristic for prediction, not a correctness invariant. Measured cost: under 1% hit ratio. Measured benefit: the lock leaves the hot path entirely.
6. **When is LRU the wrong choice?** → looping and scan workloads, where it scores exactly 0%. Show the chart.
7. **Explain ARC's adaptivity in one sentence.** → ghost lists record what was evicted, so the cache learns whether it under-funded recency or frequency and shifts the boundary accordingly — O(1), no tuning knobs.
8. **Why a Count-Min Sketch instead of `HashMap<Key,Integer>`?** → a million keys' frequency history in 8 KB instead of tens of MB; it only ever overestimates, so taking the min across rows bounds the error; and the whole sketch can be aged by halving in O(w).
9. **What is admission control and why does it matter?** → classical policies only choose who leaves; TinyLFU also decides whether the newcomer deserves entry. One-hit wonders dominate web traffic and must not be allowed to evict genuinely hot keys.
10. **Cache stampede — what is it, how did you fix it?** → 1,000 concurrent misses on one expired hot key → 1,000 DB queries. Single-flight coalescing → 1 query. Plus TTL jitter for avalanche and a Bloom guard for penetration.
11. **Why consistent hashing instead of `hash % N`?** → modulo relocates ~75% of keys when a node joins; consistent hashing relocates K/N. Virtual nodes are required for balance and to spread a failed node's load. You measured both.
12. **What are your system's limits / what would you do differently?** → per-shard capacity is locally, not globally, optimal; replicas are eventually consistent with last-write-wins; the sketch is approximate; no Merkle anti-entropy yet. **Volunteering your system's limits is the strongest signal of maturity available to you. Never skip this one.**

---

## 6. Risks & De-risking

| Risk | Likelihood | Mitigation |
|---|---|---|
| **Scope creep — nothing finished** | High | Strict tier order. **Tier 0 complete and committed in week 1.** Every tier boundary is independently submittable. |
| Concurrency bugs eating a week | High | Invariant assertions from day one; differential and property tests *before* threads are added; single-thread mode always available for debugging. |
| ARC or W-TinyLFU subtly wrong | Medium | Golden hit-ratio regression tests against published paper figures on the standard ARC traces. If your ARC doesn't roughly reproduce the paper, it's wrong. |
| Dashboard consuming all your time | Medium | Build it **after** Tier 4, when there is real data worth showing. Plain SSE, no state-management library, no design system. Ship Screens 1–3 first; 4–7 are additive. |
| Benchmarks that don't reproduce | Medium | Fixed seeds, checked-in traces, documented hardware and JVM flags, JMH confidence intervals, a one-command `bench` target. |
| Distributed layer never lands | Medium | It is Tier 7 for a reason — but **define the `CacheRouter` interface back in Tier 2** so it slots in cleanly if time allows and is a clean no-op if it doesn't. |
| No Maven / no Docker locally | **Known** | Maven Wrapper needs no install; cluster runs as JVM processes on ports 8081–8085; H2 in PG mode if Postgres is awkward. |
| Overclaiming in benchmarks | Medium | Never claim to beat Caffeine. Report ratios, methodology and error bars. Honest numbers are more impressive than impossible ones. |

---

## 7. Academic Rubric Mapping

| Typical rubric criterion | Where this project scores |
|---|---|
| **Correct DSA implementation** | 20 hand-built structures; differential tests vs naive references; invariant assertions after every op |
| **Complexity analysis** | `docs/COMPLEXITY.md`; `@implNote` complexity on every public method; amortized vs worst case distinguished |
| **Algorithmic depth / originality** | ARC, W-TinyLFU, Count-Min Sketch, timing wheels, Belady's optimum, Robin Hood hashing, Vose's alias method |
| **Problem-solving beyond the brief** | Identified and fixed LRU's scan pathology, the concurrency bottleneck, stampede, avalanche, penetration, cold start |
| **Testing & validation** | Property-based, differential, concurrency-stress, golden-regression, crash-recovery; ≥85% coverage |
| **Experimentation & results** | 10 policies × 8 workloads × 5 capacities, real production traces, measured against a provable optimum |
| **Software engineering practice** | Multi-module build, CI, ADRs, Javadoc, zero-dependency core, semantic commits |
| **Presentation & documentation** | Live animated dashboard, 3-minute demo video, ~20-page report, README with charts |
| **Real-world applicability** | Working Spring Boot + Postgres server with measured 94% DB-load reduction |

---

## Appendix A — Suggested First Week, Day by Day

| Day | Task | Done when |
|---|---|---|
| 1 | Repo, `mvnw`, 5 modules, CI green, `Cache` + `CacheStats` interfaces | CI badge green on an empty test |
| 2 | `IntrusiveLinkedList` + `assertInvariants` + unit tests | Floyd's cycle check passes over 1M random ops |
| 3 | `OpenAddressingMap` (Robin Hood + backward-shift delete) + tests | 10M random put/get/remove matches a `HashMap` reference exactly |
| 4 | `EvictionPolicy` SPI + `LruPolicy` + `VeloxCache` single-threaded | **LeetCode 146 suite passes** ✅ *brief satisfied* |
| 5 | Differential + property tests; `CacheBuilder`; Javadoc pass | Coverage ≥ 90% on core |
| 6 | `FifoPolicy`, `RandomPolicy`, `ClockPolicy` | 4 policies behind one interface |
| 7 | `LfuPolicy` (O(1) bucket list) + aging | Freq-bucket invariants hold; aging effect written up |

**By the end of week 1 you already have a complete, correct, well-tested answer to the assignment — plus the architecture everything else is built on.**

---

## Appendix B — Naming

Project name used throughout: **VeloxCache** (*velox*, Latin for "swift"). Alternatives: **Nimbus**, **Kairos**, **Strata**, **Cortex**, **Aether**. Whichever you pick, register the GitHub repo early and use the name consistently in the package (`com.velox.core`), the README, the report and your resume — consistent branding across artifacts reads as a real project rather than a class submission.

## Appendix C — Suggested ADRs

One page each: context, options considered, decision, consequences.

1. Sharding over a single global lock
2. Lossy read buffers over synchronous LRU reordering
3. Open addressing + Robin Hood over chained hashing
4. Policy as an SPI over hard-coded LRU
5. Count-Min Sketch over an exact frequency map
6. Timing wheel vs indexed heap for TTL (with the measured crossover point)
7. Sorted array + binary search over `TreeMap` for the hash ring
8. Eventual consistency with last-write-wins for replicas
9. SSE over WebSockets for dashboard telemetry
10. Zero dependencies in core; competitors confined to bench

**Almost no student project has ADRs. Every good engineering team writes them.** They are cheap to produce and a disproportionately strong signal.
