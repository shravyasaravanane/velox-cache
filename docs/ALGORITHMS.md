# VeloxCache — Eviction Algorithms & the Benchmark Lab

Covers **Tiers 3–4**: all 10 eviction policies, Belady's optimal oracle, the workload generators, real traces, the JMH suite and benchmark methodology.

← Back to [PROJECT_PLAN.md](../PROJECT_PLAN.md) · See also [ARCHITECTURE.md](ARCHITECTURE.md), [SYSTEM.md](SYSTEM.md)

---

## 1. Why LRU Is Not Good Enough — the Two Killer Workloads

Build these two demos **first**. They motivate everything that follows, and they are the most persuasive 30 seconds of your presentation.

### 1.1 The looping scan

Cache capacity = 100. Access keys 1, 2, 3, …, 101, then 1, 2, 3, …, 101, forever.

**LRU hit ratio: exactly 0%.** Every single key is evicted immediately before it is needed again. MRU scores ~99% on the identical workload.

This is not a contrived edge case. It is a table scan, a nightly batch job, or an analytics query running next to your web traffic. **One scan flushes your entire working set.** ARC and W-TinyLFU stay high because they resist exactly this.

Plot it as a bar chart with LRU sitting at literally zero. It is a striking visual and nobody forgets it.

### 1.2 The frequency/recency conflict

10% of keys receive 90% of traffic (Zipfian), but a background job touches thousands of cold keys. LRU keeps the recent-but-cold entries and evicts the frequently-hot ones. LFU keeps the hot ones but cannot adapt when the hot set shifts.

**Neither recency nor frequency alone is sufficient.** That is the entire thesis of ARC and TinyLFU — and a strong opening sentence for your report's abstract.

---

## 2. The Classical Policies

### 2.1 FIFO and Random (baselines)

Include them so you have a floor to compare against. **Random is a genuinely useful baseline** — it is surprisingly competitive, partly scan-resistant (a scan can't systematically target the hot set), and it has zero metadata cost. If a sophisticated policy cannot beat Random by a clear margin on a given workload, that is a finding worth reporting, not an embarrassment.

### 2.2 CLOCK / Second-Chance

A circular buffer with one **reference bit** per entry. A read merely *sets the bit* — no list mutation, no pointer surgery. Eviction sweeps the hand forward: bit set → clear it and move on (the "second chance"); bit clear → evict.

**Why it matters here:** CLOCK approximates LRU while making reads almost free and lock-friendly, which is precisely the concurrency problem from Tier 2 solved a different way. PostgreSQL uses a clock sweep for its buffer pool. Comparing CLOCK's throughput *and* hit ratio against your sharded LRU is a great experiment. The textbook expectation is that CLOCK gives up a little hit ratio for cheaper reads, but **our Tier 1 measurements did not show that**: CLOCK finished 1–2 points *ahead* of LRU on every skewed workload we tried (e.g. 55.5% vs 54.2% on Zipf 0.99). A single seed on synthetic traffic is not conclusive, so treat this as a hypothesis to re-test on real traces in Tier 4 — a coarse "used since the hand last passed" bit can be less sensitive to short bursts than exact recency. Quantify both hit ratio and throughput.

### 2.3 SLRU — Segmented LRU

Two LRU segments: **probation (20%)** and **protected (80%)**. New entries land in probation. A *second* hit promotes an entry to protected. When protected overflows, its LRU tail is demoted back to probation rather than evicted.

The promotion rule is the whole idea: **an entry must prove itself twice before it earns protected space.** One-hit wonders never make it out of probation. This is also the main region of W-TinyLFU, so implement it before attempting that.

### 2.4 2Q

Three structures: `A1in` (a FIFO for newcomers), `A1out` (a **ghost** FIFO holding only keys evicted from A1in), and `Am` (the main LRU).

- Miss, and the key is in `A1out` → it has been seen before, so promote it straight into `Am`.
- Miss, and it is nowhere → it goes into `A1in`.

Same insight as SLRU, reached through ghost entries rather than segments: **a second sighting is what earns long-term residency.** 2Q is strongly scan-resistant because a scan's keys pass through A1in and leave without ever polluting `Am`.

### 2.5 LRU-K (K = 2)

Instead of tracking only the most recent access, keep the last **K** access timestamps per entry and evict the one with the largest **backward K-distance** — the longest gap since its *Kth*-most-recent access.

Why K=2 is the sweet spot: a single access tells you almost nothing (it could be a scan); two accesses tell you there is a genuine reuse interval to estimate. This was designed for database buffer pools (O'Neil, O'Neil & Weikum, 1993), so pair it with a storage/OLTP trace in the benchmark for a fair showing.

**Implementation:** a min-heap keyed by the Kth-most-recent access time, plus a short history list for keys seen fewer than K times. **O(log n)** — and this is the one policy in the set that is *not* O(1). Report that honestly in the complexity table; the comparison "LRU-K buys scan resistance at the cost of O(log n)" is exactly the kind of trade-off analysis a capstone should contain.

---

## 3. ARC — Adaptive Replacement Cache

IBM, 2003 (Megiddo & Modha). **Four lists**, total real capacity `c`:

| List | Contents | Meaning |
|---|---|---|
| **T1** | real entries, seen **once** | recency |
| **T2** | real entries, seen **≥ twice** | frequency |
| **B1** | **ghosts** evicted from T1 (keys only) | "recency was under-funded" |
| **B2** | **ghosts** evicted from T2 (keys only) | "frequency was under-funded" |

Constraints: `|T1| + |T2| ≤ c`, and all four together ≤ `2c`.

A target parameter **`p` ∈ [0, c]** sets the desired size of T1. On every request:

- **Hit in T1 or T2** → move the entry to the MRU end of **T2**. (A second access promotes it to "frequent.")
- **Hit in B1** — we evicted this recently-used key too soon → **increase `p`** by `max(1, |B2|/|B1|)`, giving recency more room. Fetch the entry into T2.
- **Hit in B2** — we evicted this frequently-used key too soon → **decrease `p`** by `max(1, |B1|/|B2|)`. Fetch into T2.
- **Full miss** → evict from T1 if `|T1| > p`, else from T2; push the victim's **key** onto the matching ghost list.

### Why it's brilliant

The cache **learns from its own mistakes.** Ghost lists are the memory of what it threw away, so every ghost hit is direct evidence that the recency/frequency balance was wrong — and the cache corrects it immediately, at O(1) cost, with **no configuration and no tuning knobs**.

Ghost lists cost keys only: typically under 1% of the memory of real entries. That is why this is affordable.

**The patent story is worth knowing.** ARC is patented by IBM, which is *why PostgreSQL famously removed it in version 8.0.1* and reverted to a clock sweep. Dropping that in an interview shows you read beyond the algorithm into its real-world history.

### Implementation warning

ARC is by far the easiest policy here to get subtly wrong — it will *look* like it works while quietly producing mediocre hit ratios. Write `assertInvariants()` checking all six ARC invariants and run it after **every** operation in tests, and validate against published figures on the standard ARC traces.

---

## 4. W-TinyLFU — the State of the Art

Caffeine's algorithm (Einziger, Friedman & Manes, 2017), and the most impressive thing you will build. It is the current champion on most real traces.

```
                                  ┌──── W-TinyLFU ────────────────────────────┐
  new key ──► ┌─────────────────┐ │                                           │
              │  Window LRU 1%  │ │  evicted from window = CANDIDATE          │
              └────────┬────────┘ │            │                              │
                       │          │            ▼                              │
                       │          │   ┌──── ADMISSION DUEL ─────┐              │
                       │          │   │ freq(candidate) via CMS │              │
                       │          │   │          vs             │              │
                       │          │   │ freq(victim)    via CMS │              │
                       │          │   └───────┬─────────────────┘              │
                       │          │           │ candidate wins → admit         │
                       │          │           │ candidate loses → DISCARD      │
                       │          │           ▼                                │
                       │          │  ┌──── Main SLRU (99%) ──────────────┐     │
                       └──────────┼─►│ Probation 20%  │  Protected 80%   │     │
                                  │  └────────▲───────┴──────┬───────────┘     │
                                  │           │  hit promotes │                │
                                  │           └───────────────┘                │
                                  └───────────────────────────────────────────┘
```

### (a) The window (1%)

A tiny LRU that absorbs brand-new keys. It gives genuinely new-but-hot keys a chance to accumulate frequency before they are judged. This is what makes **W**-TinyLFU adapt to *bursty* workloads, which plain TinyLFU could not.

### (b) The Count-Min Sketch — 4-bit counters

Frequency estimates for *every key ever seen*, in a fixed, tiny space.

- `d = 4` hash functions, `w` counters per row, everything packed into a `long[]` — **16 four-bit counters per long**.
- `estimate(key) = min` across the 4 rows. The minimum, because hash collisions can only ever *inflate* a counter, never deflate it. That one-directional error is what makes the min a sound estimate — explain this in your report.
- **Conservative update:** on increment, bump only the rows currently holding the minimum. Cuts overestimation error by roughly 30% for free.
- **Aging / reset:** after `W` increments (W ≈ 10 × capacity), **halve every counter** with one masked shift per long:

  ```java
  v = (v >>> 1) & 0x7777777777777777L;   // halve 16 four-bit counters at once
  ```

  This is what lets the sketch *forget*, so a key that was hot yesterday loses to one that is hot now. **Halving an entire sketch at 16 counters per instruction is the neatest bit-manipulation in the project — put that one-liner in your report.**

- **Memory result to quote:** frequency history for ~1,000,000 distinct keys in roughly **8 KB**. An exact `HashMap<Key,Integer>` would need tens of megabytes. This is the number that makes probabilistic data structures click for an audience.

### (c) The doorkeeper

A small Bloom filter in front of the sketch. First sighting of a key → record it in the Bloom filter only; don't touch the sketch. Second sighting → promote it into the sketch.

Since most keys in real web traffic are seen exactly once, this keeps roughly 80% of the garbage out of the sketch entirely, roughly doubling its effective accuracy for the same memory.

### (d) The admission duel — the whole point

When the window evicts a candidate, the cache does **not** automatically let it into the main region. It compares the candidate's estimated frequency against the main region's eviction victim, and **the loser is discarded**.

A brand-new one-hit key cannot displace an item the cache has already seen 50 times. **That is admission control, and no classical policy has it.**

*Detail worth implementing:* when frequencies tie, or the candidate is only slightly lower, admit it with a small random probability. This prevents a pathological workload from permanently locking out every new key — a real failure mode of naive TinyLFU.

---

## 5. Belady's Optimal — the Oracle

Belady's MIN (1966) is the provably optimal eviction policy: **always evict the entry whose next use is furthest in the future.** It is unimplementable online, because it requires knowledge of the future — but on a *recorded trace*, the future is sitting right there in the file.

**Algorithm:**

1. **Backward pass** over the trace building `nextUse[i]` = the next index at which `trace[i]` reappears, or `∞`. Use a `HashMap<Key,Integer> lastSeen` and walk from the end. **O(n).**
2. **Forward simulation** with a **max-heap keyed by `nextUse`**. On eviction, pop the root — the entry needed furthest away. On a hit, update that entry's `nextUse` and sift. **O(n log c).**

### Why this transforms your report

Without the oracle you have: *"LRU 68%, ARC 79%, W-TinyLFU 84%."* With it:

| Policy | Hit ratio | **% of Belady-optimal** |
|---|---|---|
| **Belady OPT** | 89.1% | **100%** (provable ceiling) |
| W-TinyLFU | 84.2% | **94.5%** |
| ARC | 79.4% | 89.1% |
| 2Q | 74.8% | 84.0% |
| LRU | 68.0% | 76.3% |
| FIFO | 61.2% | 68.7% |

*(Illustrative shape — report what you actually measure.)*

Now you are reporting **how much of the achievable benefit each policy captures**, and you can state that **no online policy can ever exceed 89.1% on this trace.** That is a research result, not a class assignment. Very few student projects contain a provable upper bound.

---

## 6. Policy Summary Table

For `docs/ALGORITHMS.md` and your report:

| Policy | Structures | Access | Evict | Scan-resistant? | Adaptive? | Wins on |
|---|---|---|---|---|---|---|
| FIFO | queue | O(1) | O(1) | ❌ | ❌ | baseline only |
| Random | array | O(1) | O(1) | ⚠️ partly | ❌ | surprisingly decent baseline |
| LRU | DLL + map | O(1) | O(1) | ❌ | ❌ | strong recency locality |
| CLOCK | ring + ref bits | O(1)* | O(1)* | ❌ | ❌ | concurrency — no list writes on read |
| LFU (O(1)) | bucket list-of-lists | O(1) | O(1) | ✅ | ❌ | stable skewed popularity |
| SLRU | 2 LRU segments | O(1) | O(1) | ⚠️ partly | ❌ | mixed traffic |
| 2Q | A1in + A1out + Am | O(1) | O(1) | ✅ | ❌ | scan-heavy |
| LRU-K | history + min-heap | O(log n) | O(log n) | ✅ | ❌ | DB buffer pools, OLTP |
| **ARC** | 4 lists + ghosts | O(1) | O(1) | ✅ | ✅ | **shifting workloads** |
| **W-TinyLFU** | CMS + Bloom + window + SLRU | O(1) | O(1) | ✅ | ✅ | **most real traces** |
| Belady OPT | next-use max-heap | O(log c) | O(log c) | ✅ | — | *offline ceiling only* |

\* amortized

---

## 7. Tier 4 — The Benchmark Lab

> **This tier produces the single artifact most likely to get you an interview: one chart.** Do not skip it, and do not leave it until last.

### 7.1 Workload generators

| Generator | Definition | What it proves |
|---|---|---|
| **Uniform** | every key equally likely | worst case for *any* cache; hit ratio ≈ capacity/keyspace |
| **Zipfian(θ)** | P(rank i) ∝ 1/i^θ; θ = 0.7 / 0.99 / 1.2 | **realistic web traffic** — θ ≈ 0.99 matches most CDN and catalog workloads |
| **Sequential scan** | 1, 2, 3, …, N once | pollution resistance |
| **Looping** | 1..C+1 repeated forever | **LRU scores 0%** — the money shot |
| **Hot-set shift** | Zipf over keys [0,1000), jumping to [1000,2000) every 100k ops | **adaptivity** — ARC and TinyLFU recover fast, LFU never does |
| **Two-pool** | 90% of requests over 10% of keys, 10% uniform over the rest | the classic 90/10 |
| **Bursty / diurnal** | sinusoidal request rate + periodic hot-key spikes | TTL jitter and stampede defences |
| **Adversarial** | requests for keys that do not exist | the Bloom penetration guard |

**Implement Zipf with Vose's alias method**, not by binary-searching a CDF. Build the alias tables once in O(n), then every sample is **O(1)**: pick a uniform column, flip a biased coin, return either the column or its alias. It is a genuinely elegant algorithm and belongs in your data-structure catalogue.

### 7.2 Real traces

Synthetic workloads prove *mechanisms*; **real traces prove the result.** Ship `scripts/download-traces.sh` and check 2–3 small ones into the repo.

- **ARC traces (P1–P14, S1–S3, OLTP)** — the standard academic benchmark set from the original ARC paper. Using the same traces the authors used makes your numbers **directly comparable to published results**. Say exactly that in the report; it is the strongest methodological move available to you.
- **Twitter cache traces** (`twitter/cache-trace`) — 54 production Redis clusters. Enormous credibility.
- **Wikipedia CDN trace** — realistic web-server access patterns, which is literally the project brief.
- **SPC-1 / storage traces** — for the LRU-K / buffer-pool story.

Trace format: `timestamp,key,opType,size`. Write a **streaming** loader so multi-gigabyte traces never load into memory — and mention that you did, because it is the kind of detail that gets skipped.

### 7.3 The matrix runner

```powershell
./mvnw -pl velox-bench exec:java -Dexec.args="
    --policies=LRU,FIFO,CLOCK,LFU,SLRU,2Q,LRU-K,ARC,W-TINY-LFU,RANDOM,OPT
    --workloads=zipf0.99,loop,scan,hotshift,twitter-c1
    --capacities=0.001,0.005,0.01,0.05,0.10
    --out=docs/benchmarks/hitratio.csv"
```

Capacities are expressed as a **fraction of the key space**, which is the standard way these curves are published and makes your charts comparable to the literature.

Output columns:
`policy, workload, capacity, requests, hits, hitRatio, pctOfOptimal, evictions, admissionRejects, nsPerOp, bytesPerEntry`

**Chart 1 — the one for your resume:** hit ratio vs cache size (log x-axis), one line per policy, **Belady as a dashed ceiling**, on the Twitter trace. That chart tells the entire story of your project without a word of explanation. Put it in the README, the report and your portfolio.

**Chart 2:** hit ratio over time during a hot-set shift — watch ARC's adaptive `p` recover while LFU flatlines.

**Chart 3:** the looping workload bar chart with LRU at zero.

### 7.4 The JMH throughput suite

Hit ratio is only half the story. A policy with a 5% better hit ratio but 10× lower throughput is useless in production, and saying so demonstrates judgment.

```java
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 5, time = 2)  @Measurement(iterations = 10, time = 2)
@Fork(value = 3, jvmArgsAppend = {"-Xms4g", "-Xmx4g", "-XX:+UseG1GC"})
@State(Scope.Benchmark)
public class CacheThroughputBenchmark {
    @Param({"1", "2", "4", "8", "16"})           int threads;
    @Param({"VELOX_LRU", "VELOX_TINYLFU", "CONCURRENT_HASH_MAP",
            "SYNC_LINKED_HASH_MAP", "GUAVA", "CAFFEINE"}) String impl;
    @Param({"100", "75", "50"})                  int readPercent;

    @Benchmark public Object mixed(ThreadState ts) { ... }
}
```

Also run `-prof gc` to report **allocation rate (B/op)** — near-zero allocation on the read path is a strong result and easy to explain. Use `-prof perfasm` if you want to go further.

### 7.5 Be honest about Caffeine

Caffeine is written by a JVM performance specialist and has years of tuning behind it. Landing within **1.5–3×** of it is an excellent result for a from-scratch implementation, and *saying so plainly* signals maturity. A student who claims to have beaten Caffeine is a student nobody believes.

Frame it like this:

> *"Velox reaches 62% of Caffeine's throughput with 8% of its code and no dependencies, while matching its hit ratio within 0.4% — because both implement W-TinyLFU."*

That sentence is more impressive than any inflated number, and it is defensible under questioning.

### 7.6 Methodology section — do not skip

`docs/benchmarks/RESULTS.md` must state:

- hardware (CPU model, physical cores, RAM);
- JVM version and exact flags;
- JMH warmup / measurement / fork counts;
- trace provenance, size and request count;
- how ties were broken and how the cache was warmed;
- **error bars** — JMH gives you 99.9% confidence intervals; use them.

**Reporting confidence intervals instead of single numbers is the difference between a benchmark and a claim.** It is also the first thing an experienced reviewer checks.
