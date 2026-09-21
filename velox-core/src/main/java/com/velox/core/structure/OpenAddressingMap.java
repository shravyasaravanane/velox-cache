package com.velox.core.structure;

import com.velox.core.util.Hashing;
import com.velox.core.util.Invariants;

import java.util.function.Consumer;

/**
 * A hash map from keys to {@link Node}s, using <b>open addressing</b> with
 * <b>Robin Hood hashing</b> and <b>backward-shift deletion</b>.
 *
 * <h2>Open addressing vs chaining</h2>
 *
 * Java's {@code HashMap} handles collisions by <i>chaining</i>: each slot
 * holds a linked list of every entry that landed there.
 *
 * <pre>
 *   chaining:          slot 3 -> [A] -> [B] -> [C]        (pointer chase per probe)
 *   open addressing:   ... [_][_][A][B][C][_] ...         (one flat array)
 * </pre>
 *
 * We use open addressing: one entry per slot, and when your slot is taken you
 * walk forward to the next free one ("linear probing"). Two advantages:
 *
 * <ul>
 *   <li><b>No wrapper objects.</b> Chaining allocates a cell per entry;
 *       we store the {@code Node} straight into the array.</li>
 *   <li><b>Cache locality.</b> Probing reads slot 7, then 8, then 9 — all
 *       adjacent in memory. The CPU fetches memory in 64-byte lines, so
 *       slots 8 and 9 are usually <i>already there</i> after reading slot 7.
 *       Chaining jumps to a random heap address each hop, and every jump risks
 *       a cache miss costing ~100ns — roughly 300 wasted CPU cycles.</li>
 * </ul>
 *
 * <h2>The problem open addressing creates: clustering</h2>
 *
 * With plain linear probing, whoever arrives first keeps the good slot and
 * latecomers get pushed further and further away. Probe distances become
 * wildly uneven — most keys sit at distance 0 while a few sit 30 slots out,
 * and lookups for those keys are slow. Worse, long runs tend to grow: a
 * cluster is a bigger target, so it catches more keys, so it grows faster.
 *
 * <h2>The fix: Robin Hood hashing</h2>
 *
 * Define an entry's <b>PSL</b> (probe sequence length) as how far it sits from
 * its ideal slot. PSL 0 means it got exactly the slot it hashed to.
 *
 * <p>On insertion, when we meet an occupant that is <i>richer</i> than us —
 * a smaller PSL, meaning it is closer to home — we <b>take its slot and carry
 * it onward instead</b>. Steal from the rich, give to the poor.
 *
 * <pre>
 *   inserting X (psl 3) and meeting Y (psl 1):
 *
 *   before:   [..][ Y ][..]        X has travelled further, so it is poorer
 *   after:    [..][ X ][..]        X takes the slot;
 *                                  now we carry Y forward looking for a slot
 * </pre>
 *
 * This does not reduce the <i>total</i> distance travelled — it redistributes
 * it. The maximum PSL collapses towards the average, so there are no more
 * outliers. Lookups become uniformly fast instead of usually-fast-sometimes-awful.
 *
 * <p>It also buys a genuinely clever <b>early exit on lookup</b>. If we are
 * probing at PSL 5 and meet an entry with PSL 3, our key <i>cannot</i> be
 * anywhere further along: had it existed, Robin Hood would have evicted that
 * poorer entry and put our key here. So we can stop immediately and report a
 * miss. Plain linear probing has to keep walking to the next empty slot.
 *
 * <h2>Deletion: backward shift, not tombstones</h2>
 *
 * The naive fix for deletion in an open-addressed table is a <b>tombstone</b>:
 * mark the slot "deleted" so probe chains passing through it are not broken.
 *
 * <p>That is fatal for a long-lived cache. A cache evicts constantly — it is
 * the single most common operation after lookup. Tombstones accumulate, never
 * get reclaimed, and the table gradually fills with markers that must still be
 * probed past. Over hours, an O(1) map degrades into an O(n) scan while
 * reporting a perfectly healthy {@code size()}.
 *
 * <p>Instead we <b>shift entries backwards</b> into the hole until we reach an
 * empty slot or an entry already in its ideal position. The table is left
 * exactly as if the deleted key had never been inserted. No debris, no decay,
 * no periodic rehash — it stays pristine for weeks.
 *
 * <h2>Thread safety</h2>
 *
 * <b>None.</b> Locking belongs to the shard that owns this map (Tier 2).
 *
 * @param <K> the key type
 * @param <V> the value type
 */
public final class OpenAddressingMap<K, V> {

    /**
     * How full the table may get before it grows.
     *
     * <p>Open addressing degrades sharply as it fills, because free slots get
     * rarer and probe runs get longer. At 90% full the average probe is
     * painful; at 100% an insert would loop forever. 0.75 is the standard
     * compromise between wasted memory and probe length.
     */
    private static final double LOAD_FACTOR = 0.75;

    /** Smallest table we ever allocate. */
    private static final int MINIMUM_CAPACITY = 16;

    /** The slots. Always a power-of-two length. A null means "free". */
    private Node<K, V>[] table;

    /** Cached {@code table.length - 1}, used to wrap indices. */
    private int mask;

    /** Number of live entries. */
    private int size;

    /** Entry count at which we grow the table. */
    private int growAt;

    /**
     * Creates a map sized to hold {@code expectedEntries} without resizing.
     *
     * @param expectedEntries how many entries this map should hold comfortably
     */
    public OpenAddressingMap(int expectedEntries) {
        int required = (int) (expectedEntries / LOAD_FACTOR) + 1;
        int capacity = Math.max(MINIMUM_CAPACITY, Hashing.nextPowerOfTwo(required));
        allocate(capacity);
    }

    @SuppressWarnings("unchecked")
    private void allocate(int capacity) {
        this.table = (Node<K, V>[]) new Node[capacity];
        this.mask = capacity - 1;
        this.growAt = (int) (capacity * LOAD_FACTOR);
    }

    // ------------------------------------------------------------------
    //  Core helpers
    // ------------------------------------------------------------------

    /** The slot an entry with this hash would most like to occupy. */
    private int idealSlot(int hash) {
        return hash & mask;
    }

    /**
     * How far the entry at {@code slot} has been displaced from its ideal.
     *
     * <p>The {@code & mask} handles wraparound. If an entry ideally belongs at
     * slot 15 in a 16-slot table but actually sits at slot 2, the subtraction
     * gives {@code 2 - 15 = -13}; masking with {@code 0b1111} turns that into
     * 3, which is the true distance travelled once you wrap past the end.
     */
    private int probeDistance(int slot, int hash) {
        return (slot - idealSlot(hash)) & mask;
    }

    // ------------------------------------------------------------------
    //  Lookup
    // ------------------------------------------------------------------

    /**
     * Finds the node stored under {@code key}.
     *
     * @param key  the key to look up
     * @param hash the key's spread hash (from {@link Hashing#spread})
     * @return the node, or {@code null} if absent
     * @implNote O(1) average. Worst case O(n) if every key collides, which
     *           {@link Hashing#spread} exists to prevent.
     */
    public Node<K, V> get(K key, int hash) {
        int slot = idealSlot(hash);
        int distance = 0;

        while (true) {
            Node<K, V> occupant = table[slot];

            // An empty slot ends every probe chain: the key is not here.
            if (occupant == null) {
                return null;
            }

            // --- The Robin Hood early exit. ---
            // This occupant is closer to home than we are. If our key existed,
            // insertion would have stolen this slot from it and placed our key
            // right here. It did not, so our key is not in the table at all.
            if (probeDistance(slot, occupant.hash) < distance) {
                return null;
            }

            // Compare the cheap int hash first: it rejects almost every
            // non-match in one instruction, so equals() — which may walk a
            // whole string — runs only on a genuine candidate.
            if (occupant.hash == hash && occupant.key.equals(key)) {
                return occupant;
            }

            slot = (slot + 1) & mask;
            distance++;
        }
    }

    /**
     * @param key  the key to test
     * @param hash the key's spread hash
     * @return whether the map holds an entry for {@code key}
     * @implNote O(1) average
     */
    public boolean containsKey(K key, int hash) {
        return get(key, hash) != null;
    }

    // ------------------------------------------------------------------
    //  Insertion
    // ------------------------------------------------------------------

    /**
     * Stores {@code node}, replacing any existing entry with the same key.
     *
     * @param node the node to store
     * @return the node previously stored under this key, or {@code null} if
     *         the key is new
     * @implNote Amortised O(1). An individual call is O(n) when it triggers a
     *           resize, but a resize doubles the capacity, so the cost is
     *           spread over the n inserts that made it necessary.
     */
    public Node<K, V> put(Node<K, V> node) {
        if (size + 1 > growAt) {
            grow();
        }
        return insert(node);
    }

    /**
     * The Robin Hood insertion loop.
     *
     * <p>We carry a node looking for a home. At each slot:
     * <ol>
     *   <li>slot empty → place the node, done</li>
     *   <li>slot holds our key → replace it in place, size unchanged</li>
     *   <li>occupant is richer (smaller PSL) → swap: the occupant becomes the
     *       node we are carrying, and we keep going</li>
     *   <li>otherwise → step forward</li>
     * </ol>
     *
     * <p>Case 2 is only checked while we still carry the <i>original</i> node.
     * Once a swap happens we are relocating someone else, and we already know
     * from case 3 that the original key was absent — that is exactly what
     * "we met a richer occupant" proves.
     */
    private Node<K, V> insert(Node<K, V> node) {
        Node<K, V> carried = node;
        int slot = idealSlot(node.hash);
        int distance = 0;

        while (true) {
            Node<K, V> occupant = table[slot];

            if (occupant == null) {
                table[slot] = carried;
                size++;
                return null;                       // the key was new
            }

            boolean stillCarryingOriginal = (carried == node);

            if (stillCarryingOriginal
                    && occupant.hash == node.hash
                    && occupant.key.equals(node.key)) {
                table[slot] = node;
                return occupant;                   // replaced — size unchanged
            }

            int occupantDistance = probeDistance(slot, occupant.hash);
            if (occupantDistance < distance) {
                // Rob the rich: take this slot and carry the occupant onward.
                table[slot] = carried;
                carried = occupant;
                distance = occupantDistance;
            }

            slot = (slot + 1) & mask;
            distance++;
        }
    }

    // ------------------------------------------------------------------
    //  Deletion
    // ------------------------------------------------------------------

    /**
     * Removes and returns the entry stored under {@code key}.
     *
     * <p>Uses backward-shift deletion, so the table is left in exactly the
     * state it would have been in had the key never been inserted. See the
     * class comment for why tombstones would be ruinous here.
     *
     * @param key  the key to remove
     * @param hash the key's spread hash
     * @return the removed node, or {@code null} if the key was absent
     * @implNote O(1) average — the shift loop runs for the length of the local
     *           probe cluster, which Robin Hood keeps short.
     */
    public Node<K, V> remove(K key, int hash) {
        int slot = idealSlot(hash);
        int distance = 0;

        // --- Phase 1: locate the key (same logic as get). ---
        while (true) {
            Node<K, V> occupant = table[slot];
            if (occupant == null) {
                return null;
            }
            if (probeDistance(slot, occupant.hash) < distance) {
                return null;
            }
            if (occupant.hash == hash && occupant.key.equals(key)) {
                break;
            }
            slot = (slot + 1) & mask;
            distance++;
        }

        Node<K, V> removed = table[slot];
        table[slot] = null;
        size--;

        // --- Phase 2: backward shift. ---
        //
        // We just punched a hole. Any entry after it that was displaced (PSL
        // > 0) may have been pushed past this very slot, so it can now move
        // one step closer to home. Slide entries back until we hit either an
        // empty slot (nothing beyond it probed through here) or an entry with
        // PSL 0 (already home — moving it would break its own lookup).
        int hole = slot;
        int next = (slot + 1) & mask;

        while (true) {
            Node<K, V> candidate = table[next];
            if (candidate == null) {
                break;
            }
            if (probeDistance(next, candidate.hash) == 0) {
                break;
            }
            table[hole] = candidate;
            table[next] = null;
            hole = next;
            next = (next + 1) & mask;
        }

        return removed;
    }

    // ------------------------------------------------------------------
    //  Growth
    // ------------------------------------------------------------------

    /**
     * Doubles the table and reinserts every entry.
     *
     * <p>Entries cannot simply be copied across: the slot index depends on
     * {@code hash & mask}, and the mask just changed, so almost everything
     * belongs somewhere new.
     *
     * @implNote O(n), amortised to O(1) per insert across the table's life.
     */
    private void grow() {
        Node<K, V>[] old = table;
        allocate(old.length * 2);
        size = 0;
        for (Node<K, V> node : old) {
            if (node != null) {
                insert(node);
            }
        }
    }

    // ------------------------------------------------------------------
    //  Bulk operations
    // ------------------------------------------------------------------

    /** @return the number of entries. @implNote O(1) */
    public int size() {
        return size;
    }

    /** @return whether the map is empty. @implNote O(1) */
    public boolean isEmpty() {
        return size == 0;
    }

    /** @return the current number of slots. @implNote O(1) */
    public int capacity() {
        return table.length;
    }

    /**
     * Removes every entry, keeping the current table size.
     *
     * @implNote O(capacity)
     */
    public void clear() {
        java.util.Arrays.fill(table, null);
        size = 0;
    }

    /**
     * Applies {@code action} to every node, in unspecified order.
     *
     * @param action what to do with each node
     * @implNote O(capacity) — it scans empty slots too
     */
    public void forEach(Consumer<Node<K, V>> action) {
        for (Node<K, V> node : table) {
            if (node != null) {
                action.accept(node);
            }
        }
    }

    // ------------------------------------------------------------------
    //  Diagnostics
    // ------------------------------------------------------------------

    /**
     * The probe distance of the unluckiest entry in the table.
     *
     * <p>This is the number that shows whether Robin Hood hashing is earning
     * its keep. A lookup costs roughly one array read per unit of probe
     * distance, so the maximum is the worst-case cost of any single lookup.
     *
     * <p>Plain linear probing produces a long tail here: most entries sit at
     * distance 0–2 while a few unlucky ones end up 30+ slots from home. Robin
     * Hood cannot reduce the <i>total</i> displacement, but by making richer
     * entries give up their slots it spreads that displacement evenly, which
     * pulls the maximum down close to the average.
     *
     * @return the largest probe distance, or 0 if the map is empty
     * @implNote O(capacity) — diagnostics only, never call this on a hot path
     */
    public int maxProbeDistance() {
        int worst = 0;
        for (int slot = 0; slot < table.length; slot++) {
            Node<K, V> node = table[slot];
            if (node != null) {
                worst = Math.max(worst, probeDistance(slot, node.hash));
            }
        }
        return worst;
    }

    /**
     * The mean probe distance across all entries — the expected lookup cost.
     *
     * @return the average probe distance, or 0 if the map is empty
     * @implNote O(capacity) — diagnostics only
     */
    public double averageProbeDistance() {
        if (size == 0) {
            return 0;
        }
        long total = 0;
        for (int slot = 0; slot < table.length; slot++) {
            Node<K, V> node = table[slot];
            if (node != null) {
                total += probeDistance(slot, node.hash);
            }
        }
        return (double) total / size;
    }

    // ------------------------------------------------------------------
    //  Self-check
    // ------------------------------------------------------------------

    /**
     * Verifies the table is internally consistent. Does nothing unless
     * {@code -Dvelox.assertions=true}.
     *
     * <p>Checks:
     * <ol>
     *   <li>the capacity is a power of two and the mask matches it</li>
     *   <li>occupied slots number exactly {@code size}</li>
     *   <li>the load factor has not been exceeded</li>
     *   <li>every stored entry is findable by {@link #get} — this is the big
     *       one, because a botched backward shift breaks a probe chain and
     *       makes entries invisible while leaving {@code size} looking fine</li>
     *   <li>the Robin Hood ordering holds: for any two adjacent occupied
     *       slots, {@code psl(i) <= psl(i-1) + 1}. Insertion keeps each
     *       cluster sorted by ideal slot, and that inequality is what being
     *       sorted implies — so a violation means the swap logic is wrong</li>
     * </ol>
     *
     * @throws IllegalStateException if the table is inconsistent
     */
    public void assertInvariants() {
        if (!Invariants.ENABLED) {
            return;
        }

        int capacity = table.length;
        Invariants.check(Integer.bitCount(capacity) == 1,
                "capacity must be a power of two but was " + capacity);
        Invariants.check(mask == capacity - 1, "mask is out of step with capacity");
        Invariants.check(size >= 0, "size went negative: " + size);

        int occupied = 0;
        for (Node<K, V> node : table) {
            if (node != null) {
                occupied++;
            }
        }
        Invariants.check(occupied == size,
                "found " + occupied + " occupied slots but size says " + size);
        Invariants.check(size <= growAt,
                "size " + size + " exceeded the grow threshold " + growAt);

        for (int slot = 0; slot < capacity; slot++) {
            Node<K, V> node = table[slot];
            if (node == null) {
                continue;
            }

            Invariants.check(get(node.key, node.hash) == node,
                    "entry " + node.key + " is in the table at slot " + slot
                            + " but get() cannot reach it — a probe chain is broken");

            int previousSlot = (slot - 1) & mask;
            Node<K, V> previous = table[previousSlot];
            if (previous != null) {
                int here = probeDistance(slot, node.hash);
                int before = probeDistance(previousSlot, previous.hash);
                Invariants.check(here <= before + 1,
                        "Robin Hood ordering broken at slot " + slot
                                + ": psl " + here + " follows psl " + before);
            }
        }
    }
}
