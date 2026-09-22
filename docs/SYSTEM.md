# VeloxCache — Web Server, Dashboard, Cluster & Durability

Covers **Tiers 5–8**: the Spring Boot demo server over Postgres, the live React dashboard, the distributed layer with consistent hashing, persistence, and the documentation that does the selling.

← Back to [PROJECT_PLAN.md](../PROJECT_PLAN.md) · See also [ARCHITECTURE.md](ARCHITECTURE.md), [ALGORITHMS.md](ALGORITHMS.md)

---

## 1. Tier 5 — Web Server + Database

> This tier is what makes the phrase *"for a Web Server"* in the brief literally true, and it produces the numbers your resume bullet will quote.

### 1.1 The demo domain

A **product catalog** with 1,000,000 rows (or news articles, or user profiles — pick one and commit to it). Seed it with `scripts/seed-db.sql`.

Make the uncached path expensive in a way that is both realistic and controllable:

- a genuine multi-table join (product → category → inventory → reviews aggregate), **and**
- a configurable artificial latency knob (`velox.demo.db-latency-ms=25`) so the effect is visible and tunable during a live demo.

**Be transparent in the report that the latency knob exists and why.** Honesty about methodology is a feature, not a weakness — and an examiner will ask.

### 1.2 Endpoints

```
GET  /api/products/{id}                 cache-aside read
POST /api/products/{id}?mode=...        write-through | write-around | write-behind | delayed-double-delete
GET  /api/products/{id}?cache=off|lru|arc|w_tiny_lfu   ← A/B toggle, live (CacheVariants)

GET  /api/stats                         full CacheStats snapshot (primary + every A/B variant + write-behind)
GET  /api/stats/stream                  SSE, 10 Hz, drives the dashboard

POST /api/admin/policy/{name}           hot-swap the eviction policy
POST /api/admin/capacity/{n}            resize the cache live
POST /api/admin/invalidate              flush
POST /api/admin/invalidate-tag/{tag}    drop every product filed under a category tag

POST /api/chaos/stampede?key=&n=1000    fire N concurrent requests at one cold key
POST /api/chaos/scan?startId=&n=100000  flood with a sequential scan
POST /api/chaos/expire-all              trigger mass expiry (avalanche demo)
POST /api/chaos/penetrate?n=10000       request keys that do not exist
POST /api/chaos/kill-node/{id}          (Tier 7) drop a cluster node

POST /api/visualizer/pause              freeze the Internals Visualizer against live traffic
POST /api/visualizer/resume             un-freeze it
POST /api/visualizer/step?key=          apply exactly one access by hand -- step mode

GET  /actuator/prometheus               Prometheus scrape endpoint
```

The **A/B toggle** and **hot-swap** endpoints are what make the live demo compelling: flip the policy mid-traffic and watch the hit-ratio line bend on the dashboard in real time.

### 1.3 Integration patterns to implement

- **Cache-aside (read-through)** — the default, and the one to explain in depth.
- **Write-through** — update the DB and the cache together on write.
- **Write-behind** — buffer dirty entries, **coalesce repeated writes to the same key**, flush in batches. Report the write-amplification reduction as a measured number.
- **Write-around** — writes bypass the cache entirely. Useful when written data is rarely re-read; include it so you can discuss when each is appropriate.
- **Invalidation on mutation** — the genuinely hard part. Implement **delayed double-delete** (delete → write DB → brief delay → delete again) and explain in the report *which* race it closes and which it does not. **Knowing the limits of your own solution is what separates a senior answer from a junior one.**
- **Tag-based invalidation** — an inverted index `tag → Set<key>` so `invalidateByTag("category:42")` drops every affected entry in O(m).

### 1.4 The headline numbers

Measured, not illustrative: `LoadGenerator` (`com.velox.server.loadgen`) against a locally running
`velox-server`, catalog seeded at 20,000 products, `velox.demo.db-latency-ms=15`, key-space 5,000,
Zipfian exponent 1.0, 32 concurrent virtual-thread callers.

| Metric | Cache OFF (3,000 req) | Cache ON, W-TinyLFU (20,000 req) | Result |
|---|---|---|---|
| Throughput | 1,350 req/s | 5,534 req/s | **4.1× ↑** |
| p50 latency | 19.2 ms | 3.5 ms | **5.5× ↓** |
| p99 latency | 311.0 ms | 24.0 ms | **13.0× ↓** |
| Hit ratio | — | 89.6% (cumulative, warmed by a prior 20,000-request run) | — |

*(Reproduce with `java -cp velox-server/target/classes com.velox.server.loadgen.LoadGenerator
--requests=N --cache=off` and again with `--cache=w_tiny_lfu`, then `GET /api/stats`. See
`LoadGenerator`'s own Javadoc for the exact commands.)*

**Two honest caveats, stated rather than hidden:**

1. This run's cache capacity (10,000) exceeds the key-space (5,000), so once warm the whole
   worked set fits in memory with no real eviction pressure — the 89.6% hit ratio mostly shows
   "the working set fits," not eviction-policy skill under a tight capacity. Tier 4's 864-row
   `hit-ratio-matrix.csv` is where eviction policies are actually compared against each other and
   against Belady's oracle under real capacity pressure; this table's job is narrower: cache vs.
   no cache, measured at the HTTP layer.
2. p99 latency under `cache=off` is inflated by JVM/JIT warm-up in the first few hundred
   requests of a short 3,000-request run, not a steady-state number — a longer run would likely
   show a tighter p99. Reported exactly as measured rather than smoothed.

**A real bug this measurement caught:** the first attempt at this run threw `NullPointerException`s
under concurrent traffic and reported thousands of errors. Root cause: `CacheBuilder.build()`
returns a single-threaded cache unless `.concurrencyLevel(n)` is set explicitly, and neither
`HotSwappableCache` nor `CacheVariants` were setting it — a genuine concurrency bug, invisible to
every existing test (none of them drove real concurrent HTTP traffic against these specific
caches), caught only once a real load generator existed to run against a real server. Fixed in
both classes; see their Javadoc.

---

## 2. Tier 6 — The Live Dashboard

> Recruiters skim repos in about 40 seconds. A GIF of your cache's internals animating in real time, at the top of the README, is worth more than any paragraph you could write.

**Stack, as planned:** React 18 + Vite + TypeScript, Recharts for standard charts, hand-written SVG/Canvas for the custom visualizations, Tailwind for layout. Data arrives over **SSE** (`EventSource`) — one-way, dead simple, no WebSocket handshake complexity.

**Stack, as built** (`velox-dashboard`): React 19 (not 18 — the current release at build time) + Vite 8 + TypeScript + Recharts + Tailwind v4, plus one addition the plan did not call for: **framer-motion**, for the Internals screen's slide/reorder animations (`layout` + `AnimatePresence`) — simpler and more reliable than hand-rolled SVG/Canvas transform math for the same effect, and the only new frontend dependency this whole tier needed. Screens are tabs in one single-page app, not separate numbered pages. SSE and the 10 Hz tick are exactly as planned.

**Critical performance rule:** never stream one event per cache operation. At 14,000 RPS you would melt the browser. Aggregate server-side into a bounded ring buffer and emit **one snapshot frame at 10 Hz**. As built, this is simpler than the plan's "snapshot plus a sample of individual events": every visualized structure (the Internals screen's three policies, the Policy Arena's eleven) is kept at a small, deliberately bounded size, so the *entire* current state fits in one frame -- there was no need for a second, separately-sampled event stream just to drive animations, since the frontend can derive "what moved" from consecutive full snapshots via React's keyed reconciliation.

### Screen 1 — Live Ops
Ops/sec gauge; hit-ratio sparkline over the last 60s with an EWMA line; latency percentile bars (p50/p90/p99/p999); capacity fill bar in both entries and bytes; per-shard load distribution; evictions/sec; admission-reject rate.

### Screen 2 — Internals Visualizer ★ the money shot

Render the actual data structure, animated:

- **LRU** — the doubly linked list, MRU at the left. On a hit the node **slides** to the front with a spring animation. On eviction the tail node flashes red and flies off. Node color encodes access frequency.
- **LFU** — the frequency buckets as vertical columns; entries hop rightward as their counts rise.
- **ARC** — four horizontal lanes (T1, T2, B1, B2) with ghosts drawn as dashed outlines, and the adaptive **`p` marker sliding left and right in real time** as the workload shifts. Genuinely mesmerising, and it makes the algorithm obvious to a viewer within five seconds.
- **W-TinyLFU** — window / probation / protected regions, plus the **admission duel** rendered as a head-to-head card: *candidate freq 3 vs victim freq 17 → REJECTED*, flashing red.

Add a **step mode**: pause, then single-step through operations to narrate the algorithm during your viva. This turns your demo into a teaching tool, which examiners reward.

### Screen 3 — Policy Arena ★ the innovative one

Run **shadow caches** — the same live request stream fed simultaneously into all 10 policies. Shadow caches store *keys only, never values*, so they cost a few megabytes and could genuinely run in production alongside the real cache. Race their hit ratios on one live chart, with a leaderboard that reorders as the workload shifts.

**Why this is genuinely novel for a student project:** it is not merely a visualization, it is a *mechanism*. Shadow caches are how you would actually decide which policy to deploy, and how you would justify a config change with data rather than opinion. Emphasise that framing in interviews.

It is also the natural jumping-off point for the best stretch goal in the project: **auto-switch to whichever policy is currently winning.**

### Screen 4 — Heat Map
The key space as a grid, cell color from the Count-Min Sketch frequency estimate. Watch hot regions glow and migrate during a hot-set shift. Side panel: **top-20 keys from the Space-Saving structure**, plus the HyperLogLog unique-key estimate with its error bound displayed.

### Screen 5 — Cluster Ring (Tier 7)
The consistent hash ring drawn as a circle, virtual nodes as colored ticks, keys as dots at their hash positions. **Kill a node** → its arc redistributes to neighbours with an animation, and a counter reads *"12,431 keys moved (24.8% — a modulo hash would have moved 74.9%)."* This single screen makes consistent hashing self-evident to anyone watching.

### Screen 6 — Benchmark Explorer
Loads the CSVs from Tier 4. Interactive hit-ratio-vs-capacity curves; toggle policies on and off; switch workloads; Belady drawn as a dashed ceiling. Effectively your research paper, made interactive.

### Screen 7 — Chaos Panel ★ the demo closer

Buttons that break things on purpose, each with before/after graphs:

- **Stampede** — 1,000 concurrent requests for one cold key. Toggle single-flight: DB query count goes **1,000 → 1**.
- **Scan flood** — 100k sequential keys. Watch LRU's hit ratio collapse to zero while W-TinyLFU barely moves. **The best ten seconds of your entire demo.**
- **Avalanche** — expire everything at once. Toggle TTL jitter: a knife-edge spike becomes a gentle ramp.
- **Penetration attack** — flood with non-existent keys. Toggle the Bloom guard: DB load drops to zero.
- **Kill node** — (Tier 7) watch the ring heal.

---

## 3. Tier 7 — The Distributed Layer

### 3.1 Consistent hashing

**The problem with `hash(key) % N`:** add one node to a 3-node cluster and **~75% of all keys** change owner — every one of them an instant cache miss, all at the same moment. That is a self-inflicted avalanche, and it is why naive sharding does not survive contact with production.

**Consistent hashing:** map both nodes and keys onto a 64-bit ring. A key belongs to the first node clockwise from it. Adding a node steals keys only from its immediate successor → only **K/N** keys move.

**Virtual nodes are mandatory, not optional.** With 3 physical nodes placed once each, the ring is wildly unbalanced — one node might own 60% of the space. Hash each physical node **160 times** (`hash(nodeId + "#" + i)`) and the distribution tightens to within a few percent. Equally important: a failing node's load then spreads across *all* survivors instead of dumping entirely onto one unlucky neighbour.

**Implementation — do not use `TreeMap`.** Use a **sorted `long[]` of vnode hashes with a parallel `Node[]`**, and binary-search for the ceiling.

Rationale: contiguous memory, no pointer chasing, roughly 2–3× faster lookups than a red-black tree. A rebuild on membership change is O(V log V), but membership changes are rare while lookups are constant. **Benchmark both and report the difference** — a measured justification for an unusual choice is exactly the kind of detail that stands out.

```java
public final class ConsistentHashRing {
    private volatile long[] ring;      // sorted vnode hashes
    private volatile Node[] owners;    // parallel array; owners[i] owns ring[i]
    private static final int VNODES = 160;

    public Node locate(long keyHash) {                       // O(log V)
        long[] r = ring;                                      // single volatile read
        int i = Arrays.binarySearch(r, keyHash);
        if (i < 0) i = -(i + 1);
        return owners[i == r.length ? 0 : i];                 // wrap around the ring
    }

    /** Next R DISTINCT PHYSICAL nodes clockwise — not just the next R vnodes. */
    public List<Node> locateReplicas(long keyHash, int r) { ... }
}
```

**The `locateReplicas` subtlety is a common bug and a great thing to have noticed:** the next R vnodes clockwise may all belong to the *same physical node*, which would place all R copies on one machine — zero redundancy, and a silent one. You must skip forward until you have R **distinct physical** nodes.

### 3.2 Membership & failure detection

A static seed list in config, plus HTTP heartbeats every 2s. Mark a node suspect after 3 consecutive misses and dead after 5, then rebuild the ring.

*Stretch:* implement **phi-accrual failure detection** (Cassandra's approach). Instead of a binary alive/dead verdict, maintain a rolling window of heartbeat inter-arrival times and output a continuous *suspicion level* φ. It adapts to network conditions and produces far fewer false positives on a noisy link. Strong extra credit, and a great thing to be able to explain.

### 3.3 Replication & reads

Replication factor R=2: write to the primary plus the next distinct physical node. Read from the primary; on failure, fall back to the replica.

Document honestly that this gives **eventual consistency with last-write-wins conflict resolution**, and that for a *cache* this is acceptable because the database remains the source of truth. **Knowing exactly which guarantee you are giving up, and why it is safe in this specific context, is a senior-level answer.**

*Stretch:* Merkle-tree anti-entropy to detect and repair divergence between replicas in O(log n) comparisons.

### 3.4 The measurement that proves it works

| Event | Modulo hashing | Consistent hashing (160 vnodes) |
|---|---|---|
| 3 → 4 nodes | 74.9% of keys move | **24.8%** |
| 4 → 3 nodes (failure) | 74.2% | **25.1%** |
| Key distribution std-dev | — | **< 4%** across nodes |

Generate this table from an **actual test**, not from theory.

**Running the cluster without Docker:** launch 3–5 JVM processes on ports 8081–8085 via `scripts/run-cluster.ps1`. Functionally identical for the demo, and one less thing to install on your machine.

---

## 4. Tier 8 — Durability & Polish

### 4.1 Persistence

A cache that loses everything on restart causes a **cold-start stampede**: the instant it comes back, every request misses and the database absorbs the full load. Warm restart is a real production feature, not a toy — Redis has RDB and AOF for exactly this reason.

- **WAL** — append-only binary records:
  `[magic:4][op:1][ttl:8][keyLen:4][key][valLen:4][val][crc32c:4]`
  fsync policy configurable: `ALWAYS` (safe, slow) / `EVERYSEC` (Redis's default, and the right trade-off) / `NEVER`. **Explain the durability-vs-throughput trade-off with measured numbers for all three** — that table is worth more than the feature itself.
- **Snapshot** — a periodic full dump *including the policy's ordering metadata*, so the cache resumes warm **and correctly ordered**, not merely populated. This detail is easy to miss and good to call out.
- **Compaction** — replay the log, drop superseded and expired records, rewrite compactly, then atomically rename.
- **Recovery** — load the newest snapshot → replay the WAL tail → validate CRC → truncate at the first torn record.
- **Test it for real** — force-kill the process mid-write (`Stop-Process -Force` on Windows), restart, and verify there is no corruption and no data loss beyond the fsync window. **A passing crash-recovery test is genuinely rare in a student project.**

### 4.2 Documentation that does the selling

- **README.md** — hero GIF (the internals visualizer), one-paragraph pitch, the benchmark chart, a 3-command quickstart, the architecture diagram, the feature table. **Assume 40 seconds of attention and front-load accordingly.**
- **`docs/adr/`** — the 10 Architecture Decision Records listed in [PROJECT_PLAN.md](../PROJECT_PLAN.md) Appendix C. One page each: context, options considered, decision, consequences.
- **`docs/COMPLEXITY.md`** — every operation, best/average/worst, with justification. This is your DSA grade in a single file.
- **`docs/REPORT.pdf`** — abstract, problem statement, literature review (LRU → LFU → LRU-K → 2Q → ARC → TinyLFU), design, implementation, experimental methodology, results with charts, discussion of trade-offs, conclusion, references. Around 20 pages.
- **Demo video** — 3 minutes, unlisted on YouTube, linked from the README.

**Suggested video script:**

| Time | Content |
|---|---|
| 0:00–0:15 | The problem: a slow DB, a web server under Zipfian load |
| 0:15–0:35 | Architecture diagram, 30-second tour |
| 0:35–1:15 | Live traffic + dashboard: hit ratio climbing, latency collapsing |
| 1:15–1:45 | **Chaos: scan flood.** LRU's hit ratio falls off a cliff; W-TinyLFU holds |
| 1:45–2:05 | Hot-swap the policy mid-traffic; watch the line bend |
| 2:05–2:30 | Kill a cluster node; the ring heals, keys redistribute |
| 2:30–2:50 | The benchmark chart with the Belady ceiling |
| 2:50–3:00 | Repo link, tech stack, close |

---

## 5. Stretch Goals (only after Tier 8)

Ranked by impact per unit of effort:

1. **Auto-tuning policy selection** — use the Policy Arena's shadow caches to automatically switch the live policy to whichever is currently winning, with hysteresis to prevent flapping. This is the single most impressive stretch goal available, and it follows naturally from work you have already done.
2. **Adaptive window sizing for W-TinyLFU** — Caffeine's hill-climbing optimizer, which tunes the window/main split at runtime by sampling the hit-ratio gradient.
3. **Phi-accrual failure detection** (see §3.2).
4. **Merkle-tree anti-entropy** between replicas.
5. **Off-heap storage** via `MemorySegment` (Java 21's FFM API) to escape GC pressure for large values — a strong, modern-Java talking point.
6. **Cuckoo filter** as an alternative to the Bloom doorkeeper, since it supports deletion.
7. **A Spring Boot starter** (`velox-spring-boot-starter`) with `@Cacheable` integration, so the engine drops into any Spring app. Packaging your own library for reuse reads as real engineering.
8. **Publish to Maven Central** — genuinely impressive on a resume, and not as hard as it sounds.
