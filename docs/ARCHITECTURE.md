# VeloxCache — Architecture & Core Engine

Covers **Tiers 0–2**: the public API, the hand-built data structures, the eviction SPI, engine features (TTL, loader, resilience), the concurrency design, the testing strategy and the complexity reference.

← Back to [PROJECT_PLAN.md](../PROJECT_PLAN.md) · See also [ALGORITHMS.md](ALGORITHMS.md), [SYSTEM.md](SYSTEM.md)

---

## 1. The Public API

```java
public interface Cache<K, V> {
    V getIfPresent(K key);                                   // O(1) avg
    V get(K key, Function<? super K, ? extends V> loader);   // O(1) + load, single-flighted
    void put(K key, V value);                                // O(1) amortized
    void put(K key, V value, Duration ttl);                  // O(1), or O(log n) with the heap TTL engine
    boolean putIfAbsent(K key, V value);
    void invalidate(K key);
    void invalidateAll(Iterable<? extends K> keys);
    void invalidateAll();
    long size();                                             // O(1)
    long weightedSize();                                     // O(1)
    CacheStats stats();                                      // O(1) snapshot
    ConcurrentMap<K, V> asMap();                             // live view
    void cleanUp();                                          // force a maintenance drain
}
```

```java
Cache<Long, Product> cache = CacheBuilder.<Long, Product>newBuilder()
    .maximumSize(100_000)                       // or .maximumWeight(64 * MB).weigher(...)
    .policy(Policy.W_TINY_LFU)                  // pluggable — the whole point
    .expireAfterWrite(Duration.ofMinutes(10))
    .expireAfterAccess(Duration.ofMinutes(2))
    .ttlJitter(0.15)                            // ±15% to prevent avalanche
    .refreshAfterWrite(Duration.ofMinutes(8))   // refresh-ahead for hot keys
    .concurrencyLevel(16)                       // shard-count hint
    .expiryEngine(ExpiryEngineType.TIMING_WHEEL)    // or INDEXED_HEAP (the default)
    .recordStats()
    .removalListener((k, v, cause) -> log.debug("evicted {} due to {}", k, cause))
    .build(productRepository::findById);        // CacheLoader
```

**Why the builder matters:** it makes every tier's feature discoverable in a single screenshot. Put this snippet at the top of your README — a reader understands the scope of the project in ten seconds.

---

## 2. `IntrusiveLinkedList` — the LRU backbone

The classic mistake is keeping a `LinkedList<K>` beside a `HashMap<K,V>`. Removing a key then costs **O(n)**, because you must scan the list to find its position — which silently breaks the "constant time" requirement in the brief.

An **intrusive** list fixes this: the map stores the *node itself*, so by the time you want to unlink it, you already hold the pointer.

```java
final class Node<K, V> {
    K key; V value;
    Node<K,V> prev, next;          // main recency list
    int weight;                     // weighted eviction
    long writeTimeNanos, accessTimeNanos, expiresAtNanos;

    // --- policy-specific fields; only the active policy touches these ---
    int frequency;                  // LFU
    boolean referenceBit;           // CLOCK
    byte segment;                   // SLRU/ARC/2Q: WINDOW|PROBATION|PROTECTED|T1|T2|B1|B2
    Node<K,V> freqPrev, freqNext;   // LFU bucket chain
    long[] historyK;                // LRU-K: last K access timestamps (ring)
    int heapIndex = -1;             // IndexedMinHeap back-pointer
}
```

All operations are **O(1)**: `addToHead`, `unlink`, `moveToHead`, `removeTail`, `isEmpty`, `size`.

**Sentinel nodes.** Keep permanent `head` and `tail` sentinels so `unlink` never needs a null check. This removes around eight branches from the hottest path in the whole system, and it is worth a paragraph in your report.

**`assertInvariants()` must verify:**
- the `size` field matches an actual traversal;
- `head.prev == null && tail.next == null`;
- forward traversal count == backward traversal count == size;
- **no cycles** (Floyd's tortoise-and-hare);
- for every node, `node.prev.next == node` and `node.next.prev == node`.

---

## 3. `OpenAddressingMap` — Robin Hood hashing

Java's `HashMap` chains with node objects: a pointer chase per probe and poor cache locality. You will write an **open-addressing map with Robin Hood hashing** instead — both more impressive and genuinely faster for this access pattern.

**The idea.** On insert, if the element being placed has travelled further from its ideal slot (a higher *probe sequence length*, PSL) than the element currently occupying that slot, **swap them** — steal from the rich, give to the poor. This bounds the *variance* of probe lengths, collapsing the worst case toward the average.

**Deletion: backward-shift, not tombstones.** After clearing a slot, walk forward shifting each subsequent element back one position while its PSL > 0, stopping at the first empty slot or PSL-0 element. Tombstones would gradually degrade a long-lived cache into an O(n) scan — fatal for a cache that runs for weeks. Backward-shift keeps the table pristine indefinitely. **This is a genuinely advanced detail and an excellent interview answer.**

**Other specifics:**
- capacity is always a power of two → `index = hash & (capacity - 1)`, no modulo;
- load factor 0.75 → resize doubles and rehashes, amortized O(1);
- hash spreading via the MurmurHash3 finalizer, which kills the clustering you would otherwise get from sequential integer keys (product IDs 1, 2, 3, …).

```java
static int spread(int h) {                 // murmur3 32-bit finalizer
    h ^= (h >>> 16); h *= 0x85ebca6b;
    h ^= (h >>> 13); h *= 0xc2b2ae35;
    return h ^ (h >>> 16);
}
```

---

## 4. The `EvictionPolicy` SPI

Getting this interface right is the architectural keystone of the project. Design it once, carefully — everything in Tier 3, the Policy Arena and the benchmark matrix depends on it.

```java
public interface EvictionPolicy<K, V> {

    /** Entry was just inserted. Start tracking it. */
    void onInsert(Node<K,V> node);

    /** Entry was just read (a hit). Must be cheap — hot path, called in batches. */
    void onAccess(Node<K,V> node);

    /** Entry was explicitly removed or expired — clean up policy metadata. */
    void onRemove(Node<K,V> node);

    /** Choose the next victim when over capacity. */
    Node<K,V> selectVictim();

    /**
     * ADMISSION CONTROL. Should this candidate be allowed in, at the cost of
     * evicting `victim`? Default = always yes (LRU, LFU, ARC, ...).
     * W-TinyLFU overrides this — its entire advantage lives here.
     */
    default boolean admit(Node<K,V> candidate, Node<K,V> victim) { return true; }

    /** Ghost-list bookkeeping for ARC / 2Q on a miss. */
    default void onMiss(K key) {}

    void clear();
    String name();

    /** @param expectedEntryCount how many entries the cache holds; the
     *  policy must be tracking exactly this many. */
    default void assertInvariants(int expectedEntryCount) {}
}
```

> **Implemented in Tier 0.** See
> [`EvictionPolicy.java`](../velox-core/src/main/java/com/velox/core/policy/EvictionPolicy.java)
> and [`LruPolicy.java`](../velox-core/src/main/java/com/velox/core/policy/LruPolicy.java).

### The single most important insight in the project

Separating **admission** (`admit`) from **eviction** (`selectVictim`).

Every classical policy answers only one question: *who leaves?* TinyLFU asks the smarter one: *should this newcomer even come in?*

One-hit wonders — keys accessed exactly once, ever — dominate real web traffic. Letting them evict a genuinely hot item is the main mechanism by which LRU loses hit ratio. Say this sentence in your viva; it demonstrates you understand *why* the modern algorithms win, not just *how* they work.

---

## 5. `LruPolicy` — the required deliverable

```java
public final class LruPolicy<K,V> implements EvictionPolicy<K,V> {
    private final IntrusiveLinkedList<K,V> list = new IntrusiveLinkedList<>();

    public Node<K,V> onInsert(Node<K,V> n) { list.addToHead(n); return null; }  // O(1)
    public void onAccess(Node<K,V> n)      { list.moveToHead(n); }              // O(1)
    public void onRemove(Node<K,V> n)      { list.unlink(n); }                  // O(1)
    public Node<K,V> selectVictim()        { return list.tail(); }              // O(1)
    public void clear()                    { list.clear(); }
    public String name()                   { return "LRU"; }
}
```

**That is the entire assignment** — eight lines, once the primitives exist. Everything else in this plan is the reason the project is worth building.

---

## 6. Tier 1 — Engine Features

### 6.1 O(1) LFU — the "list of lists"

Naive LFU keeps a min-heap ordered by frequency → **O(log n)** per access. The constant-time version (Shah, Mitra & Matani, 2010) is a **doubly linked list of frequency nodes**, where each frequency node owns a doubly linked list of the entries at exactly that frequency.

```
freqHead ──► [freq=1] ──► [freq=2] ──► [freq=5] ──► [freq=99]
                │             │            │             │
              A─B─C          D─E           F          G─H─I─J    (each an LRU list)
              ▲ tail = evict here
```

- **Increment:** an entry at frequency *f* moves to the node for *f+1*, creating that node if absent. Creation is O(1) because it is always the immediate successor. If the old frequency node becomes empty, unlink it. **O(1).**
- **Evict:** take the LRU tail of the *first* frequency node. **O(1).** Note the tie-break: among equally infrequent entries, the least recently used one goes. That detail matters and is easy to get wrong.
- **Aging:** pure LFU never forgets. A key that was hot last Tuesday blocks today's hot key forever — "cache pollution." Fix it by halving all frequencies every *W* operations, or with a windowed variant. **Implement the aging, then demonstrate its effect in the benchmark — that is a genuine experimental finding, not just a feature.**

### 6.2 TTL — two interchangeable engines

**Engine A — Indexed min-heap.** A binary heap ordered by `expiresAtNanos`, plus a `heapIndex` field on each node. That back-pointer is what makes **O(log n)** cancellation and re-scheduling possible when a TTL is refreshed; without it, cancellation would be O(n) and the whole structure would be useless for a cache with `expireAfterAccess`.

**Engine B — Hierarchical timing wheel.** Four levels with tick sizes 1s / 1m / 1h / 1d and 60–64 buckets each, exactly like Kafka's purgatory and Netty's `HashedWheelTimer`. Scheduling computes a bucket and appends to its list → **O(1)**. Cancellation unlinks from the bucket list → **O(1)**. On each tick, the current bucket fires; higher-level wheels *cascade* their expiring bucket down into the level below.

**Why build both?** So you can benchmark them and report a measured crossover point — the difference between "I implemented a timing wheel" and "I know when to use one."

**What we actually measured** (informal harness, one laptop, `ExpiryEngineComparison`; Tier 4 replaces it with JMH). An earlier draft of this section guessed "3.1× faster, heap wins below ~50k"; the measurements are more modest:

| Entries | Reschedule churn (heap / wheel, ns) | Whole lifecycle (heap / wheel, ns) |
|---|---|---|
| 1,000 | 72 / 68 — tie | 337 / 1795 — **heap 5.3×** |
| 10,000 | 95 / 92 — tie | 308 / 651 — **heap 2.1×** |
| 100,000 | 245 / 163 — **wheel 1.5×** | 486 / 489 — tie |
| 1,000,000 | 1025 / 613 — **wheel 1.7×** | 1959 / 843 — **wheel 2.3×** |

The crossover is near **100,000 entries**. The wheel loses at small sizes because its cost tracks elapsed *time* (the hand must sweep) whereas the heap's tracks the number of *entries*. Memory was not measured. The heap stays the default.

**Three complementary expiry mechanisms** — Redis uses all three, and so should you:

1. **Lazy** — check expiry on read. Free, but entries that expire and are never read again leak memory.
2. **Active sampling** — a background thread samples 20 random keys every 100ms, deletes the expired ones, and repeats immediately if more than 25% were expired. Probabilistic, bounded cost, no full scan.
3. **Precise** — the heap or wheel fires exactly on time. Required for `removalListener` correctness.

**TTL jitter.** Never let 10,000 entries share a single expiry instant, or they all miss simultaneously and slam the database — "cache avalanche." Apply:

```java
actualTtl = ttl * (1 + jitter * (random() * 2 - 1));   // jitter ≈ 0.15
```

**Demo this in the Chaos Panel:** a knife-edge spike with jitter off, a gentle ramp with it on.

### 6.3 Single-flight — cache stampede protection

When a hot key expires and 1,000 concurrent requests miss at the same instant, a naive cache-aside issues **1,000 identical database queries** at the worst possible moment. This is cache breakdown, the thundering herd — and one of the most frequently asked system-design interview questions.

```java
// velox-core/src/main/java/com/velox/core/loader/SingleFlight.java (abridged)
public V load(K key, Function<? super K, ? extends V> loader) {
    Call<V> mine = new Call<>(Thread.currentThread());
    Call<V> existing = inFlight.putIfAbsent(key, mine);       // atomic election: one winner
    if (existing != null) return follow(key, existing);       // FOLLOWER: wait for the leader's result

    loads.increment();                                        // LEADER: run the loader on this thread
    try {
        V value = loader.apply(key);
        mine.value = value;
        return value;
    } catch (RuntimeException | Error e) {
        failures.increment();
        mine.failure = e;                                     // followers receive this same failure
        throw e;
    } finally {
        inFlight.remove(key, mine);                           // remove FIRST ...
        mine.done.countDown();                                // ... THEN release the followers
    }
}
```

Design decisions, each enforced by a test (see `SingleFlightTest`):

- **The leader's own thread runs the loader** — no thread pool, natural backpressure.
- **Failures are shared but never cached**: every waiting caller gets the same exception, and the next caller retries fresh.
- **The entry is removed *before* followers are released.** The gap is nanoseconds, so a naive test can never see the violation; the test parks the leader inside `ConcurrentHashMap.remove` using a key whose `hashCode()` blocks.
- **A loader asking for its own key fails loudly** instead of deadlocking (same-thread cycles are detected; cross-thread cycles cannot be, cheaply).
- **Counters are `LongAdder`s**, and `coalesced` is incremented *before* a follower blocks, which is what lets tests wait deterministically for "all followers are waiting".

**Headline metric for the Chaos Panel:** *1,000 concurrent requests for one cold key → **1,000 DB queries** without single-flight, **1** with it.* That is the most quotable number in the entire project. **Measured** (`StampedeTest`, an expired hot key hit by 500 simultaneous requests): **500 database queries without single-flight, 1 with it (499 coalesced).**

### 6.4 The four cache failure modes (implement all four defences)

| Failure mode | What happens | Defence |
|---|---|---|
| **Stampede / breakdown** | one hot key expires, N concurrent misses → N DB queries | **Single-flight** request coalescing |
| **Avalanche** | many keys expire simultaneously → DB spike | **TTL jitter** + circuit breaker + stale-while-revalidate |
| **Penetration** | requests for keys that don't exist in the DB at all (often an attack) | **Bloom filter** of all DB keys + negative caching with a short TTL |
| **Cold start** | cache restarts empty → every request misses at once | **Snapshot + WAL warm restart** (Tier 8) |

Two more patterns worth implementing alongside them (**deferred to Tier 2**: refresh-ahead needs a background executor reloading into a thread-safe cache, and stale-while-error needs expired entries to be *retained*, which contradicts the "expired data is never served" guarantee the engine currently makes and needs an explicit, opt-in grace period):

- **Refresh-ahead** — when a hot entry is within 20% of its TTL, reload it asynchronously while continuing to serve the current value. The user never experiences the miss.
- **Stale-while-revalidate** — if the loader throws, keep serving the stale value rather than propagating the error. Availability over freshness, which is the right trade-off for a cache.

On the Bloom filter: **zero false negatives is exactly the guarantee you need here.** A "maybe present" sends you to the database (correct, just occasionally wasteful); a "definitely absent" is authoritative and skips the database entirely. Explain that asymmetry in your report — it shows you chose the structure for its guarantee, not because it sounded impressive.

---

## 7. Tier 2 — Concurrency

> The brief says *"all operations must run in constant time."* Tier 0 delivers that for one thread. This tier delivers it for sixteen — and that gap is where most student projects quietly fail.

### 7.1 The contention problem, stated precisely

A textbook LRU moves a node to the list head on every **read**. So every read mutates shared state → every read needs the exclusive lock → your "O(1) cache" serialises every request the web server receives. Under 16 threads, a globally locked LRU is frequently **slower than having no cache at all**.

**Measure this and put it in your report.** Benchmark `Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true))` at 1, 2, 4, 8 and 16 threads. Throughput will go *down* as threads go up. That single chart justifies this entire tier and is more persuasive than any amount of prose.

### 7.2 Four layered fixes

#### Fix 1 — Sharding

Split into N independent shards, N = the next power of two ≥ 4 × cores.

```java
shardIndex = spread(hash) >>> (32 - log2N);   // use the HIGH bits
```

Use the **high** bits for shard selection, because the low bits are already consumed by the intra-shard map index. Reusing the same bits would correlate the two and cluster badly — a subtle bug that would quietly cost you hit ratio.

Each shard owns its own map, policy, lock and capacity (`capacity / N`). Contention drops roughly N-fold.

*Trade-off to document honestly:* per-shard capacity makes eviction **locally** optimal, not globally. A shard that happens to receive unusually hot keys will evict entries a global LRU would have kept. **Measured** (`ShardingHitRatioExperiment`, Zipf 0.99 over 100,000 keys, 5M requests, identical request stream for every row):

| capacity | 1 shard | 4 | 16 | 64 | 256 |
|---|---|---|---|---|---|
| 1,000 entries | 48.92% | −0.04 pts | −0.08 | −0.34 | **−1.15** |
| 10,000 entries | 72.45% | −0.00 | −0.01 | **−0.03** | −0.15 |
| 50,000 entries | 90.86% | −0.00 | −0.00 | −0.01 | −0.03 |

The cost is **far smaller than the 0.5–2% first guessed** for realistic sizes, and it tracks *entries per shard*: 64 shards of a 10,000-entry cache (~156 entries each) lose 0.03 points, while 256 shards of a 1,000-entry cache (~4 entries each) lose 1.15. Rule of thumb: keep at least ~100 entries per shard and the cost is noise. A project that reports its own trade-offs — and corrects its own estimates when the measurement disagrees — is a mature project.

#### Fix 2 — Lossy read buffers (the good part)

Don't mutate LRU order during a read. Append the accessed node to a per-shard lock-free ring buffer and return immediately.

```java
final class LossyReadBuffer<K,V> {
    private static final int SIZE = 16;             // power of two, per shard
    private static final int MASK = SIZE - 1;
    private final AtomicReferenceArray<Node<K,V>> slots = new AtomicReferenceArray<>(SIZE);
    private final AtomicLong writeIndex = new AtomicLong();
    private final AtomicLong readIndex  = new AtomicLong();

    /** O(1), lock-free. Returns true if the buffer wants draining. */
    boolean offer(Node<K,V> node) {
        long w = writeIndex.get();
        if (w - readIndex.get() >= SIZE) return true;        // FULL → drop the record
        if (writeIndex.compareAndSet(w, w + 1)) {
            slots.lazySet((int)(w & MASK), node);
        }
        return (w & MASK) == MASK - 1;                        // nearly full → drain
    }
}
```

**Why losing records is correct.** The recency order is a *heuristic for predicting future accesses*, not a correctness invariant. Dropping a read record under extreme contention makes eviction very slightly less accurate — costing a fraction of a percent of hit ratio — while removing the lock from the hot path entirely.

**This is the best "I made a deliberate engineering trade-off" story in the project, and it is exactly how Caffeine works.** Have the number ready: measure hit ratio with read buffers on versus off, and quote both.

#### Fix 3 — A single drainer, via a state machine

Only one thread performs maintenance at a time; everyone else carries on unblocked.

```
IDLE ──offer()──► REQUIRED ──tryLock ok──► PROCESSING ──drain done──► IDLE
                                              │
                                     new offer during drain
                                              ▼
                                   PROCESSING_TO_REQUIRED ──► REQUIRED (drain again)
```

Implemented as an `AtomicReference<DrainStatus>` plus `shard.evictionLock.tryLock()`. A thread that fails to acquire the lock simply returns — its read was still correct, maintenance just happens a few microseconds later.

#### Fix 4 — Striped, padded stats counters

A single `AtomicLong hitCount` becomes the hottest cache line in the JVM under load, and every core fights over it. Stripe it across cores and pad to two cache lines to eliminate false sharing:

```java
final class StripedCounter {
    private static final int STRIPES =
        Integer.highestOneBit(Runtime.getRuntime().availableProcessors() * 2);
    // 16 longs per cell = 128 bytes = two cache lines → no two counters share a line
    private final long[] cells = new long[STRIPES * 16];
    private static final VarHandle VH = MethodHandles.arrayElementVarHandle(long[].class);

    void increment() { VH.getAndAdd(cells, (probe() & (STRIPES - 1)) * 16, 1L); }   // O(1)

    long sum() {
        long s = 0;
        for (int i = 0; i < STRIPES; i++) s += (long) VH.getVolatile(cells, i * 16);
        return s;
    }
}
```

**Benchmark false sharing on and off.** A 3–5× throughput difference from *padding alone* makes a spectacular chart and proves you understand the hardware, not just the Big-O.

### 7.3 The read and write paths, end to end

```
GET(key):
  h = spread(hash(key))
  shard = shards[h >>> shift]
  shard.readLock.lock()
    node = shard.map.get(key, h)                      // O(1)
    if node == null            → miss++; unlock; return null
    if node.isExpired(now)     → miss++; unlock; scheduleRemoval(node); return null
    value = node.value
  shard.readLock.unlock()
  needsDrain = shard.readBuffer.offer(node)           // O(1) lock-free, may drop
  if needsDrain → shard.scheduleDrain()               // tryLock, never blocks
  hit++; latencyHistogram.record(elapsed)
  return value

PUT(key, value):
  shard.writeLock.lock()
    node = shard.map.get(key, h)
    if node != null:
        node.value = value; policy.onAccess(node); cause = REPLACED
    else:
        node = new Node(key, value, weigher.weigh(key, value))
        while shard.weight + node.weight > shard.maxWeight:
            victim = policy.selectVictim()
            if !policy.admit(node, victim):           // W-TinyLFU only
                unlock; rejected++; return            // candidate discarded, cache unchanged
            evict(victim, RemovalCause.SIZE)
        shard.map.put(node); policy.onInsert(node); expiry.schedule(node)
    shard.drainReadBuffer()                            // we already hold the lock — free maintenance
  shard.writeLock.unlock()
```

Two details worth noticing:

- Eviction is a **loop**, not a single removal, because with weighted entries one large insert may require evicting several small victims.
- Draining the read buffer inside `put` is elegant: you already own the lock, so applying the batched access records costs essentially nothing.

---

## 8. Testing Strategy

> Test quality is the most reliable signal of engineering maturity in a portfolio repo — and the cheapest one to get right.

**1. Unit tests per structure**, with `assertInvariants()` called after every operation. The linked list checks for cycles with Floyd's algorithm; ARC checks all six of its invariants; the heap checks parent/child ordering *and* `key → heapIndex` consistency.

**2. Differential testing against a naive reference.** For each policy, write a deliberately dumb, obviously correct O(n) implementation (LRU as an `ArrayList` scanned linearly, for instance). Run millions of random operations through both and assert **identical hit/miss sequences**. This catches nearly every algorithmic bug and is far more effective than hand-written cases.

**3. Property-based testing** (jqwik, test scope only, so `velox-core` stays dependency-free):
- size never exceeds capacity, ever;
- `put` followed immediately by `get` always returns the value (absent TTL);
- after `invalidateAll()`, size is 0 and every policy structure is empty;
- hits + misses == total requests, always;
- every node in the map is in exactly one policy list and vice versa — no leaks, no duplicates;
- weighted size equals the sum of the weights of live entries.

**4. Concurrency tests.** 16 threads × 10M mixed operations: no deadlock, no lost updates, the size bound never violated, invariants hold after `cleanUp()`. Add **jcstress** for the lossy ring buffer's CAS protocol if you want to be thorough.

**5. Golden hit-ratio regression.** Check in a small trace and assert each policy's hit ratio stays within ±0.1% of a recorded baseline. **This catches accidental algorithm regressions that still compile and still pass unit tests** — by far the nastiest class of bug in this project.

**6. Crash-recovery tests** (Tier 8) — kill the process at random points during writes, restart, verify integrity.

**7. Coverage ≥ 85% on `velox-core`** via JaCoCo, with a badge in the README.

*Stretch:* **PIT mutation testing.** It measures whether your tests would actually *catch* a bug, not merely whether they execute the line. Almost no student uses it; mentioning it in an interview is memorable.

---

## 9. Complexity Reference

Keep this table in `docs/COMPLEXITY.md` too — it is the fastest way for an examiner to grade your DSA work.

| Operation | Best | Average | Worst | Note |
|---|---|---|---|---|
| `get` (hit) | O(1) | **O(1)** | O(n) | worst only on pathological collisions; Robin Hood bounds the variance |
| `get` (miss) | O(1) | **O(1)** | O(n) | |
| `put` (no evict) | O(1) | **O(1)** | O(n) | amortized O(1) including resize |
| `put` (with evict) | O(1) | **O(1)** | O(n) | O(log n) only with the heap-based TTL engine |
| `invalidate` | O(1) | **O(1)** | O(n) | backward-shift delete keeps this true long-term |
| LRU reorder | O(1) | O(1) | O(1) | intrusive list — the map hands us the node |
| LFU increment | O(1) | O(1) | O(1) | bucket list-of-lists |
| ARC full cycle | O(1) | O(1) | O(1) | including ghost bookkeeping |
| CMS estimate | O(d)=O(1) | O(1) | O(1) | d = 4 hashes |
| CMS aging (halve all) | O(w) | O(w) | O(w) | amortized O(1) per op; w/16 long operations |
| TTL schedule (heap) | O(1) | O(log n) | O(log n) | |
| TTL schedule (wheel) | O(1) | **O(1)** | O(1) | cascading is O(1) amortized |
| TTL cancel (heap) | O(1) | O(log n) | O(log n) | requires the `heapIndex` back-pointer |
| Top-K query | O(k) | O(k) | O(k) | Space-Saving |
| Cluster route | O(log V) | O(log V) | O(log V) | V = nodes × 160 vnodes |
| Zipf sample | O(1) | O(1) | O(1) | Vose's alias method |
| Belady simulate | — | O(n log c) | O(n log c) | offline only |

**Space:** O(n) entries + O(1) per-entry policy metadata + O(w·d/2) bytes for the sketch (~8 KB) + O(m/8) for the Bloom filter + O(V) for the ring.

**ARC's ghost lists cost keys only — roughly 1% of the memory of real entries.** That sentence is worth saying out loud in a viva: it explains why "remembering what you evicted" is affordable at all.
