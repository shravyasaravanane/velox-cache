package com.velox.core.policy;

import com.velox.core.structure.IntrusiveLinkedList;
import com.velox.core.structure.Node;
import com.velox.core.util.Invariants;

/**
 * <b>Segmented LRU</b>: two LRU lists, so that "used once" and "used again" are told apart.
 *
 * <h2>The idea</h2>
 *
 * Plain LRU treats every entry alike: the moment a key is touched, it is as safe as a key
 * touched a thousand times. So a single long scan (a crawler, a report, a full table
 * read) marches thousands of never-again-used keys through the cache and pushes out
 * every genuinely popular entry. That is the classic failure of LRU.
 *
 * <p>SLRU splits the cache into two segments:
 *
 * <ul>
 *   <li><b>probation</b>: where every new entry starts. Entries here have been seen once.</li>
 *   <li><b>protected</b>: where an entry is promoted <i>on its second use</i>. Entries here
 *       have proved they are wanted more than once.</li>
 * </ul>
 *
 * Victims come from probation first. A scan can only ever cycle through probation, because
 * scanned keys are never touched a second time, so it cannot reach the protected entries.
 *
 * <p>Protected has a size cap (80% by default). Promoting into a full protected segment
 * demotes its least recently used entry back to the <i>front</i> of probation: it is not
 * thrown out, it is given one more chance.
 *
 * <p>SLRU is also the "main" region of W-TinyLFU, so this class is the base of the most
 * advanced policy in the project.
 *
 * <h2>Costs</h2>
 *
 * Every operation is O(1): a handful of pointer writes on two intrusive lists.
 *
 * @param <K> the key type
 * @param <V> the value type
 */
public final class SlruPolicy<K, V> implements EvictionPolicy<K, V> {

    static final int PROBATION = 0;
    static final int PROTECTED = 1;

    private final IntrusiveLinkedList<K, V> probation = new IntrusiveLinkedList<>();
    private final IntrusiveLinkedList<K, V> protectedSegment = new IntrusiveLinkedList<>();
    private final int protectedCapacity;

    /** An SLRU for a cache of {@code capacity} entries, with an 80% protected segment. */
    public SlruPolicy(int capacity) {
        this(capacity, 0.8);
    }

    /**
     * @param capacity          the cache's entry capacity, which sizes the protected segment
     * @param protectedFraction the share of capacity reserved for entries used more than
     *                          once, in [0, 1)
     */
    public SlruPolicy(int capacity, double protectedFraction) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be at least 1, got " + capacity);
        }
        if (!(protectedFraction >= 0 && protectedFraction < 1)) {
            throw new IllegalArgumentException("protectedFraction must be in [0, 1), got " + protectedFraction);
        }
        this.protectedCapacity = (int) (capacity * protectedFraction);
    }

    @Override
    public void onInsert(Node<K, V> node) {
        node.setSegment(PROBATION);
        probation.addToHead(node);           // a new entry is on trial
    }

    @Override
    public void onAccess(Node<K, V> node) {
        if (node.segment() == PROTECTED) {
            protectedSegment.moveToHead(node);
            return;
        }

        // Second use: it has proved itself, so it is promoted...
        probation.unlink(node);
        node.setSegment(PROTECTED);
        protectedSegment.addToHead(node);

        // ...but protected is bounded. Its own least recently used entry goes back to
        // probation's front, not out of the cache.
        if (protectedSegment.size() > protectedCapacity) {
            Node<K, V> demoted = protectedSegment.removeTail();
            demoted.setSegment(PROBATION);
            probation.addToHead(demoted);
        }
    }

    @Override
    public void onRemove(Node<K, V> node) {
        segmentOf(node).unlink(node);
    }

    @Override
    public Node<K, V> selectVictim() {
        Node<K, V> victim = probation.tail();
        return victim != null ? victim : protectedSegment.tail();
    }

    @Override
    public void clear() {
        probation.clear();
        protectedSegment.clear();
    }

    @Override
    public String name() {
        return "SLRU";
    }

    private IntrusiveLinkedList<K, V> segmentOf(Node<K, V> node) {
        return node.segment() == PROTECTED ? protectedSegment : probation;
    }

    /** @return how many entries the protected segment may hold */
    int protectedCapacity() {
        return protectedCapacity;
    }

    /** @return keys of the probation segment, most recently inserted first */
    public java.util.List<K> probationKeys() {
        return probation.keysFromMruToLru();
    }

    /** @return keys of the protected segment, most recently used first */
    public java.util.List<K> protectedKeys() {
        return protectedSegment.keysFromMruToLru();
    }

    @Override
    public void assertInvariants(int expectedEntryCount) {
        if (!Invariants.ENABLED) {
            return;
        }
        probation.assertInvariants();
        protectedSegment.assertInvariants();
        Invariants.check(probation.size() + protectedSegment.size() == expectedEntryCount,
                "SLRU tracks " + (probation.size() + protectedSegment.size()) + " entries but the cache holds "
                        + expectedEntryCount);
        Invariants.check(protectedSegment.size() <= protectedCapacity,
                "protected segment holds " + protectedSegment.size() + " entries, above its cap of " + protectedCapacity);
        probation.forEach(node -> Invariants.check(node.segment() == PROBATION,
                node.key() + " is on the probation list but is tagged protected"));
        protectedSegment.forEach(node -> Invariants.check(node.segment() == PROTECTED,
                node.key() + " is on the protected list but is tagged probation"));
    }
}
