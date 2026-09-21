# Learn, Part 1 — Foundations

**Read this first.** It assumes you know Java basics and nothing else. By the end you will understand exactly what a cache is, exactly what the assignment asks for, and exactly how to build it — plus *why* it isn't good enough, which is what the rest of the project is about.

Next: [LEARN-02-BEYOND-LRU.md](LEARN-02-BEYOND-LRU.md) · Spec docs: [ARCHITECTURE.md](ARCHITECTURE.md), [ALGORITHMS.md](ALGORITHMS.md), [SYSTEM.md](SYSTEM.md)

---

## 1. What is this project, in plain English?

You are building **a box that remembers answers.**

A web server gets asked the same questions over and over: *"give me product 4471."* Each time, it asks the database, which takes ~25 milliseconds. If 10,000 people ask for product 4471 in a minute, the server asks the database 10,000 times for an answer that never changed.

A cache sits in front of the database and remembers the answer. First person asks → 25ms, and we save the answer in memory. The next 9,999 people → 0.0001ms, and the database never hears about them.

That's it. That's a cache.

The entire difficulty is one sentence: **memory is much smaller than the database, so you can only remember some answers — which ones?**

Everything in this project — all 10 algorithms, the sketches, the cluster — exists to answer that one question better.

---

## 2. Why caching matters: the numbers

Computers are fast, but not uniformly. Here is the speed hierarchy, in units you can feel:

| Where the data lives | Real time | If RAM took 1 second, this takes... |
|---|---|---|
| CPU L1 cache | ~1 ns | 0.01 s |
| **RAM (your cache lives here)** | **~100 ns** | **1 second** |
| SSD read | ~150 µs | 25 minutes |
| **Database query (local, with a join)** | **~25 ms** | **~3 days** |
| Database query (across a network) | ~100 ms | ~12 days |

**A cache hit is roughly 250,000× faster than a database query.**

This is why caching is not a micro-optimization. It is often *the* difference between an application that works and one that falls over. And it is why every serious system has one: your browser, your CPU, your DNS resolver, your CDN, Redis, Memcached, your database's own buffer pool.

### The one metric that matters: hit ratio

```
hit ratio = hits / (hits + misses)
```

If 90 out of 100 requests are answered from memory, your hit ratio is 90%, and only 10 requests reach the database — a **90% reduction in database load**.

Now here is the thing that makes this project interesting. Watch what happens to *average latency* as hit ratio improves, with a 25ms database and a 0.1ms cache:

| Hit ratio | Average latency | DB queries per 1000 requests |
|---|---|---|
| 0% (no cache) | 25.0 ms | 1000 |
| 50% | 12.6 ms | 500 |
| 90% | 2.6 ms | 100 |
| 95% | 1.35 ms | 50 |
| **99%** | **0.35 ms** | **10** |

Going from 90% to 99% — just nine percentage points — **cuts database load by a further 90%** and latency by 7×.

**This is the single most important idea in the project.** Small hit-ratio improvements are worth enormous effort, because the relationship is not linear. When you later read "W-TinyLFU beat LRU by 16 percentage points," that is not a rounding error. That is a database you didn't have to buy.

---

## 3. The assignment, dissected word by word

> *"A web server wants to cache recently accessed data in memory to reduce database load, but memory is limited, so the least recently used item must be evicted when the cache is full — and all operations (get/put) must run in constant time."*

Three requirements are hiding in there. Each is easy alone. **Together they are the whole problem.**

### Requirement 1 — Bounded memory

The cache can hold at most **N** items. Item N+1 cannot go in until something comes out. This is called **eviction**.

### Requirement 2 — Evict the least recently used

When you must remove something, remove whatever has gone longest without being touched.

The reasoning is an assumption about the world called **temporal locality**: *something used recently is likely to be used again soon.* If nobody has asked for product 4471 in an hour, probably nobody will ask in the next minute either. So it's the safest thing to throw away.

Note carefully: **this is a guess, not a fact.** Most of this project is about the situations where the guess is wrong.

### Requirement 3 — Constant time, O(1)

Every `get` and every `put` must take the same amount of time **regardless of how many items are in the cache**. Whether it holds 10 items or 10 million, a lookup takes the same time.

This is non-negotiable for a cache. If lookups got slower as the cache filled, the cache would eventually become slower than the database it was protecting — which is the exact opposite of its job.

### Why these three together are hard

- Requirement 3 (O(1) lookup) screams **hash map**.
- Requirement 2 (least recently used) needs to know the **order** things were touched in.

And a hash map has **no order at all.** That is its defining property — it scatters keys deliberately.

So you need fast lookup *and* ordering, at the same time, in constant time. Resolving that tension is the puzzle.

---

## 4. Building the LRU cache from scratch

Let's solve it properly, by failing twice first. Understanding *why* the wrong answers are wrong is how you'll be able to explain the right one in a viva.

### Attempt 1: just a HashMap

```java
HashMap<Integer, Product> cache = new HashMap<>();
```

✅ `get` is O(1). ✅ `put` is O(1).
❌ It's full — now what do I remove?

The map has no idea which entry was touched longest ago. Finding out would mean checking every entry: **O(n)**. And we'd need a "last touched" timestamp on every entry to check *against*.

**Dead end.** We need to track order.

### Attempt 2: HashMap + a list of keys in access order

```java
HashMap<Integer, Product> data = new HashMap<>();
ArrayList<Integer> order = new ArrayList<>();   // most-recent first
```

Eviction is now easy: remove `order.get(order.size() - 1)`, the last element. ✅

But what about a **hit**? If someone reads key 4471, it just became the most recently used, so it must move to the front of `order`. To do that you must:

1. **find** 4471 inside `order` → scan the list → **O(n)** ❌
2. **remove** it from the middle of an ArrayList → shift everything after it → **O(n)** ❌

So every cache *hit* — the common case, the thing that's supposed to be fast — costs O(n).

**This violates Requirement 3.** And this is the trap most people fall into. Write it down, because "why not just use an ArrayList?" is a likely viva question.

Two separate problems emerged:

- **Problem A:** finding an item's position in the order structure is O(n).
- **Problem B:** removing from the middle of an array is O(n).

Fix both and you have the answer.

### Attempt 3: HashMap + Doubly Linked List ✅

**Fixing Problem B — removing from the middle.**

In a **linked list**, removing a node doesn't shift anything. You just rewire two pointers:

```
Before:   [A] ⇄ [B] ⇄ [C]

Remove B: A.next = C
          C.prev = A

After:    [A] ⇄ [C]           B is now unreachable — O(1), nothing shifted
```

**Why doubly linked, not singly?** To unlink B you must update **B's predecessor** (`A.next = C`). In a singly linked list, B has no pointer back to A — you'd have to scan from the head to find who points at B. **O(n) again.**

The `prev` pointer is what buys O(1) removal. That is the entire reason for the "doubly."

> **Viva question:** *"Why a doubly linked list and not a singly linked one?"*
> **Answer:** *"To unlink a node in O(1) you must update its predecessor's `next` pointer, which requires a `prev` pointer. Without it, finding the predecessor is an O(n) scan."*

**Fixing Problem A — finding the node.**

This is the clever bit, and it's the heart of the design.

Don't store the *value* in the hash map. Store **the linked list node itself**:

```java
HashMap<K, Node<K,V>> map;        // key → the node, not the value
```

Now on `get(key)`:
1. `map.get(key)` hands you the node directly → **O(1)**
2. The node already knows its own neighbours → unlink it → **O(1)**
3. Re-insert at the head → **O(1)**

**No searching. Ever.** The map is the index *into* the list.

This is called an **intrusive** linked list — the list pointers live *inside* the data object rather than in separate wrapper cells. Both structures point at the same physical node.

```
       HASH MAP                          DOUBLY LINKED LIST
    (fast lookup)                        (tracks order)

    "A" ──────────┐
    "B" ────────┐ │
    "C" ──────┐ │ │
              │ │ │
              ▼ ▼ ▼
   head ⇄ [ C ] ⇄ [ B ] ⇄ [ A ] ⇄ tail
          MRU                 LRU
                              ▲
                        evict from here
```

**Both structures hold the same nodes.** The map answers *"where is it?"*; the list answers *"how recently was it used?"* Neither can do the job alone, and that is the insight the whole assignment is testing.

> **Viva question:** *"Why do you need both a hash map and a linked list?"*
> **Answer:** *"The list gives ordering in O(1) but lookup in O(n). The map gives lookup in O(1) but has no ordering. Each covers exactly the other's weakness — so I use both, pointing at the same nodes."*

---

## 5. Trace it by hand

**Capacity = 3.** Convention: list is written **MRU → LRU**, left to right. Evictions happen at the right end.

| # | Operation | What happens | List after (MRU → LRU) |
|---|---|---|---|
| 1 | `put(A,1)` | new entry, insert at head | `[A]` |
| 2 | `put(B,2)` | new entry, insert at head | `[B, A]` |
| 3 | `put(C,3)` | new entry, insert at head — now full | `[C, B, A]` |
| 4 | `get(A)` | **HIT** → returns 1, move A to head | `[A, C, B]` |
| 5 | `put(D,4)` | full → evict tail **B**, insert D at head | `[D, A, C]` |
| 6 | `get(B)` | **MISS** — B was evicted at step 5 | `[D, A, C]` |

Study step 4 closely. `get(A)` **changed the structure of the list.** A read is not read-only.

Hold on to that. It looks harmless here. In Part 2 you'll see it's the reason a textbook LRU cache falls apart under concurrent traffic, and it's why an entire tier of the project exists.

### The code, complete

```java
class LRUCache<K, V> {
    class Node { K key; V value; Node prev, next; }

    private final HashMap<K, Node> map = new HashMap<>();
    private final Node head, tail;                  // sentinels — never hold data
    private final int capacity;

    LRUCache(int capacity) {
        this.capacity = capacity;
        head = new Node(); tail = new Node();
        head.next = tail; tail.prev = head;          // empty list: head ⇄ tail
    }

    V get(K key) {                                   // O(1)
        Node node = map.get(key);
        if (node == null) return null;               // MISS
        moveToHead(node);                            // it's now the most recent
        return node.value;                           // HIT
    }

    void put(K key, V value) {                       // O(1)
        Node node = map.get(key);
        if (node != null) {                          // already present — update
            node.value = value;
            moveToHead(node);
            return;
        }
        if (map.size() == capacity) {                // full — evict LRU
            Node lru = tail.prev;                    // the node just before tail
            unlink(lru);
            map.remove(lru.key);                     // ← must remove from BOTH
        }
        Node fresh = new Node(key, value);
        map.put(key, fresh);
        addToHead(fresh);
    }

    // --- the three primitives, all O(1) ---

    private void unlink(Node n) {
        n.prev.next = n.next;
        n.next.prev = n.prev;
    }

    private void addToHead(Node n) {
        n.next = head.next;  n.prev = head;
        head.next.prev = n;  head.next = n;
    }

    private void moveToHead(Node n) { unlink(n); addToHead(n); }
}
```

**That is the assignment, complete.** Roughly 50 lines.

### Two details worth understanding

**Sentinel nodes.** `head` and `tail` are permanent dummy nodes that never hold data. Their only job is to guarantee that *every real node always has a non-null `prev` and `next`*. That means `unlink` needs no null checks — it's four unconditional pointer assignments. Without sentinels you'd need to handle "removing the first node," "removing the last node," and "removing the only node" as special cases. That's three branches, three chances to write a bug.

**The map and the list must always agree.** Every node in the map is in the list and vice versa. If you ever remove from one and forget the other, you get a memory leak or a phantom entry. This is exactly what `assertInvariants()` checks in the plan, and it's the number one source of bugs in this code.

### Proving O(1)

| Step | Cost | Why |
|---|---|---|
| `map.get(key)` | O(1) | hash table lookup |
| `unlink(node)` | O(1) | two pointer writes, no traversal |
| `addToHead(node)` | O(1) | four pointer writes |
| find victim (`tail.prev`) | O(1) | the tail sentinel points straight at it |
| `map.remove(key)` | O(1) | hash table removal |

**No loops. No traversals. No searching.** Total: O(1) for both operations. Requirement 3 satisfied. ✅

*(Strictly: O(1) **average**. A hash map degrades to O(n) if every key collides, and resizing is O(n) but happens rarely enough to be O(1) **amortized**. Say "amortized O(1)" in your viva — precision about this distinction is exactly what separates a strong answer from an average one.)*

---

## 6. Where LRU breaks — the five cracks

You now have a correct, complete, O(1) LRU cache. **And it is not good enough.** Here is exactly why — five specific ways it fails. Each one is the reason a tier of the project exists.

### Crack 1 — the scan disaster

Capacity **3**. Requests arrive in a loop: `1, 2, 3, 4, 1, 2, 3, 4, 1, 2, 3, 4, …`

| Request | In cache? | Action | Cache after (MRU→LRU) |
|---|---|---|---|
| 1 | miss | insert | `[1]` |
| 2 | miss | insert | `[2, 1]` |
| 3 | miss | insert | `[3, 2, 1]` |
| 4 | miss | evict **1**, insert 4 | `[4, 3, 2]` |
| 1 | **miss** | evict **2**, insert 1 | `[1, 4, 3]` |
| 2 | **miss** | evict **3**, insert 2 | `[2, 1, 4]` |
| 3 | **miss** | evict **4**, insert 3 | `[3, 2, 1]` |
| 4 | **miss** | evict **1**, insert 4 | `[4, 3, 2]` |

Look at what's happening: **every key is evicted on the request immediately before it's needed again.** Forever.

**Hit ratio: exactly 0%.** The cache is not merely useless — it is *worse* than useless, because it costs memory and CPU to achieve nothing.

And now the part that should genuinely annoy you: a cache that simply kept keys 1, 2, 3 and **refused to ever admit key 4** would score **75%**.

Doing less work produces a dramatically better result. LRU's problem isn't that it evicts badly — it's that **it has no way to refuse an incoming item.** Every new key gets in, automatically, no questions asked.

> This is not a toy scenario. It is any sequential scan: a batch job, an analytics query, a backup, a crawler walking your catalog. **A single scan flushes your entire working set.** In production this shows up as "the site got slow at 2am every night" — and this is the actual cause.

**→ This crack motivates: admission control, W-TinyLFU, ARC, 2Q (Tier 3).**

### Crack 2 — recency isn't popularity

Key `X` was requested 1,000 times today. Key `Y` was requested once, 5 seconds ago.

LRU will evict **X**, because Y is more recent.

That's obviously wrong. LRU only knows *when* something was last touched — it has no idea *how often*. It throws away a proven workhorse for a stranger who just walked in.

**→ This crack motivates: LFU, and frequency estimation via Count-Min Sketch (Tier 3).**

### Crack 3 — every read is a write

Go back and look at step 4 of the trace. `get(A)` **modified the linked list**.

That is fine with one thread. With many threads it is a catastrophe.

If every read mutates shared state, every read needs an exclusive lock. So all your threads — 16 of them on a modern server — queue up single-file to touch the cache one at a time.

**Result:** the more threads you add, the *slower* it gets. A globally locked LRU under heavy concurrency can genuinely be slower than having no cache at all.

The assignment says "constant time." It's constant for one thread. **With sixteen threads, it's a traffic jam** — and noticing that gap is what separates this project from a LeetCode submission.

**→ This crack motivates: sharding, lossy read buffers, striped counters (Tier 2).**

### Crack 4 — cached data goes stale

Your cache is holding the price of product 4471. Someone changes that price in the database.

Your cache doesn't know. It will happily serve the old price forever.

LRU has no concept of time-based expiry. It only evicts under memory pressure — so an entry that's never evicted is never refreshed, and is wrong indefinitely.

**→ This crack motivates: TTL, expiry engines (min-heap, timing wheel), invalidation on write (Tier 1 + Tier 5).**

### Crack 5 — the stampede

A very popular key expires at 12:00:00.000. At that exact instant, 1,000 requests for it are in flight.

All 1,000 look in the cache. All 1,000 miss. All 1,000 go to the database — **for the identical row, at the identical moment.**

Your cache just *amplified* the load it was supposed to absorb. This is called a **cache stampede** (or thundering herd), and it's one of the most common ways caching layers take down production systems.

Only **one** of those 1,000 requests needs to hit the database. The other 999 should wait for that answer and share it.

**→ This crack motivates: single-flight coalescing, TTL jitter, refresh-ahead, Bloom-filter penetration guards (Tier 1).**

---

## 7. The map — how the cracks become the project

This is the structure of your entire capstone. Every tier is a named answer to a named failure.

| # | The crack | Why LRU fails | The fix | Tier |
|---|---|---|---|---|
| — | *(baseline)* | — | HashMap + doubly linked list | **0** |
| 4 | Stale data | no concept of time | TTL, min-heap + timing wheel expiry | **1** |
| 5 | Stampede | N misses → N DB queries | single-flight, jitter, Bloom guard | **1** |
| 3 | Reads are writes | one global lock, no scaling | sharding + lossy read buffers | **2** |
| 1 | Scan disaster | can't refuse an item | **admission control** — W-TinyLFU, ARC, 2Q | **3** |
| 2 | Recency ≠ popularity | doesn't count accesses | LFU, Count-Min Sketch | **3** |
| — | "is this actually better?" | no evidence | benchmark lab + **Belady's optimum** | **4** |
| — | "does it help a real server?" | never tested for real | Spring Boot + Postgres, measured | **5** |
| — | "can you see it work?" | invisible | live animated dashboard | **6** |
| — | one machine isn't enough | single box | consistent hashing cluster | **7** |
| — | restart = empty cache | cold-start stampede | WAL + snapshot warm restart | **8** |

**Read that table until it's automatic.** If someone asks "why did you build all this for an LRU cache?", you don't defend the complexity — you name the failure each piece fixes. Every feature has a reason, and the reason came first.

---

## 8. Check your understanding

Answer these without scrolling up. If you can, you've got Part 1.

1. Why can't a hash map alone implement an LRU cache?
2. Why a *doubly* linked list instead of singly?
3. Why does the hash map store nodes instead of values?
4. What do the sentinel nodes buy you?
5. With capacity 3 and requests `1,2,3,4,1,2,3,4,…`, what is LRU's hit ratio, and why?
6. What would a *better* policy do on that workload, and what capability does it need that LRU lacks?
7. Why does `get()` being a mutation cause a problem with 16 threads?
8. What's the difference between "O(1)" and "amortized O(1)", and which does this cache actually have?

<details>
<summary>Answers</summary>

1. A hash map has no ordering, so finding the least recently used entry requires checking every entry — O(n).
2. Unlinking a node in O(1) requires updating its predecessor's `next` pointer, which requires a `prev` pointer. Otherwise finding the predecessor is an O(n) scan.
3. So a lookup hands you the node's position in the list directly. Storing values would mean searching the list to find the node — O(n) on every hit.
4. They guarantee every real node has non-null neighbours, so `unlink`/`addToHead` need no null checks or special cases for first/last/only node.
5. **0%.** Each key is evicted on the request immediately before it is needed again — the loop is exactly one longer than the cache.
6. Keep 1, 2, 3 and **refuse to admit** 4 → 75%. It needs **admission control**: the ability to reject an incoming item. LRU admits everything unconditionally.
7. A mutation requires an exclusive lock, so all threads serialise through the cache one at a time. Throughput falls as threads increase.
8. O(1) is per-operation always; amortized O(1) averages out occasional expensive operations (hash map resizing is O(n), but rare). This cache is **amortized O(1)**, with an O(n) worst case under pathological hash collisions.
</details>

---

**Next:** [LEARN-02-BEYOND-LRU.md](LEARN-02-BEYOND-LRU.md) — how each fix actually works: the smarter policies, the probabilistic data structures (with worked examples), concurrency, consistent hashing, and the end-to-end life of a request.
