package com.velox.cluster;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConsistentHashRingTest {

    @Test
    @DisplayName("routing on an empty ring fails loudly, not with a null or a wrong answer")
    void emptyRingThrows() {
        var ring = new ConsistentHashRing(160);
        assertThrows(IllegalStateException.class, () -> ring.nodeFor("k1"));
    }

    @Test
    @DisplayName("a single node owns every key")
    void singleNodeOwnsEverything() {
        var ring = new ConsistentHashRing(160);
        ring.addNode("A");

        for (int i = 0; i < 1000; i++) {
            assertEquals("A", ring.nodeFor("key" + i));
        }
    }

    @Test
    @DisplayName("routing is deterministic: the same key maps to the same node while the ring is unchanged")
    void routingIsDeterministic() {
        var ring = new ConsistentHashRing(160);
        ring.addNode("A");
        ring.addNode("B");
        ring.addNode("C");

        for (int i = 0; i < 500; i++) {
            String key = "key" + i;
            assertEquals(ring.nodeFor(key), ring.nodeFor(key));
        }
    }

    @Test
    @DisplayName("nodesFor returns distinct physical nodes, starting with the primary owner, and never more than exist")
    void nodesForReturnsDistinctNodesStartingWithThePrimaryOwner() {
        var ring = new ConsistentHashRing(160);
        ring.addNode("A");
        ring.addNode("B");
        ring.addNode("C");

        String key = "some-key";
        String primary = ring.nodeFor(key);
        java.util.List<String> replicas = ring.nodesFor(key, 2);

        assertEquals(2, replicas.size());
        assertEquals(primary, replicas.get(0), "nodesFor's first entry must agree with nodeFor");
        assertEquals(2, java.util.Set.copyOf(replicas).size(), "the two entries must be distinct physical nodes");

        assertEquals(3, ring.nodesFor(key, 10).size(), "cannot return more distinct nodes than exist on the ring");
    }

    @Test
    @DisplayName("addNode/removeNode are idempotent no-ops when the node is already/not present")
    void addRemoveAreIdempotent() {
        var ring = new ConsistentHashRing(160);
        ring.addNode("A");
        ring.addNode("A"); // no-op
        assertEquals(java.util.Set.of("A"), ring.nodes());

        ring.removeNode("B"); // no-op, B was never present
        assertEquals(java.util.Set.of("A"), ring.nodes());

        ring.removeNode("A");
        assertEquals(java.util.Set.of(), ring.nodes());
    }

    @Test
    @DisplayName("virtualNodesPerNode must be at least 1")
    void rejectsNonPositiveVirtualNodeCount() {
        assertThrows(IllegalArgumentException.class, () -> new ConsistentHashRing(0));
    }

    /**
     * M7.2: key-distribution fairness -- standard deviation across nodes under 5% of the mean.
     *
     * <p>160 virtual nodes per physical node (a commonly-cited textbook default) measurably
     * failed this test at 5 physical nodes: 11.8% std-dev, more than double the target. 500 is
     * used here because it is the number that measurement actually called for -- see {@link
     * ConsistentHashRing}'s own class Javadoc for the full account, including the {@code
     * 1/sqrt(V)} relationship this is consistent with.
     */
    @Test
    @DisplayName("with enough virtual nodes, keys split across physical nodes within 5% of perfectly even")
    void fairnessAcrossNodesIsWithinFivePercent() {
        double stdDevPercent = stdDevPercentOfMean(500, 5, 200_000);

        assertTrue(stdDevPercent < 5.0,
                "std-dev was %.2f%% of the mean at V=500, expected under 5%%".formatted(stdDevPercent));
    }

    @Test
    @DisplayName("the finding itself, reproduced: 160 virtual nodes (a commonly-cited default) is not enough at 5 physical nodes, 500 is")
    void oneSixtyVirtualNodesIsMeasurablyNotEnough() {
        double stdDevPercentAt160 = stdDevPercentOfMean(160, 5, 200_000);
        double stdDevPercentAt500 = stdDevPercentOfMean(500, 5, 200_000);

        assertTrue(stdDevPercentAt160 > 5.0,
                "expected V=160 to measurably fail the 5%% target (it did when this was first discovered, at 11.8%%), got %.2f%%"
                        .formatted(stdDevPercentAt160));
        assertTrue(stdDevPercentAt500 < stdDevPercentAt160,
                "V=500 (%.2f%%) should be a clear improvement over V=160 (%.2f%%)"
                        .formatted(stdDevPercentAt500, stdDevPercentAt160));
    }

    private static double stdDevPercentOfMean(int virtualNodesPerNode, int physicalNodeCount, int totalKeys) {
        var ring = new ConsistentHashRing(virtualNodesPerNode);
        String[] physicalNodes = new String[physicalNodeCount];
        for (int i = 0; i < physicalNodeCount; i++) {
            physicalNodes[i] = "node-" + i;
            ring.addNode(physicalNodes[i]);
        }

        Map<String, Integer> counts = new HashMap<>();
        for (String node : physicalNodes) {
            counts.put(node, 0);
        }
        for (int i = 0; i < totalKeys; i++) {
            counts.merge(ring.nodeFor("product:" + i), 1, Integer::sum);
        }

        double mean = (double) totalKeys / physicalNodeCount;
        double variance = 0;
        for (int count : counts.values()) {
            variance += Math.pow(count - mean, 2);
        }
        variance /= physicalNodeCount;
        return (Math.sqrt(variance) / mean) * 100.0;
    }

    /**
     * M7.3: rebalance cost. Consistent hashing should move roughly {@code K/N} keys when the
     * {@code N}-th node joins; naive modulo hashing ({@code hash(key) % oldCount} vs.
     * {@code hash(key) % newCount}) should move nearly everything, because the divisor changing
     * scrambles almost every key's remainder. Both are measured empirically over the same key
     * set for a direct, honest comparison rather than an assumed number.
     */
    @Test
    @DisplayName("adding a node moves roughly K/N keys under consistent hashing, versus nearly all of them under modulo")
    void rebalanceMovesFarFewerKeysThanModulo() {
        var ring = new ConsistentHashRing(160);
        String[] fourNodes = {"node-0", "node-1", "node-2", "node-3"};
        for (String node : fourNodes) {
            ring.addNode(node);
        }

        int totalKeys = 100_000;
        String[] keys = new String[totalKeys];
        Random random = new Random(11);
        Map<String, String> ownerBefore = new HashMap<>();
        for (int i = 0; i < totalKeys; i++) {
            keys[i] = "key-" + random.nextInt(10_000_000);
            ownerBefore.put(keys[i], ring.nodeFor(keys[i]));
        }

        ring.addNode("node-4"); // 4 -> 5 nodes

        int movedUnderConsistentHashing = 0;
        for (String key : keys) {
            if (!ring.nodeFor(key).equals(ownerBefore.get(key))) {
                movedUnderConsistentHashing++;
            }
        }
        double movedFractionChr = (double) movedUnderConsistentHashing / totalKeys;

        // The same key set, routed by plain hash(key) % N -- the textbook approach consistent
        // hashing exists to replace. Not production code: a reference-only comparator built
        // purely to make the rebalance cost difference measurable in this one test.
        int movedUnderModulo = 0;
        for (String key : keys) {
            int oldOwner = Math.floorMod(key.hashCode(), fourNodes.length);
            int newOwner = Math.floorMod(key.hashCode(), fourNodes.length + 1);
            if (oldOwner != newOwner) {
                movedUnderModulo++;
            }
        }
        double movedFractionModulo = (double) movedUnderModulo / totalKeys;

        // Expected under consistent hashing: close to 1/5 = 20% (the new node's fair share).
        // Generous band: hash placement has real variance, especially with a finite virtual
        // node count, so this checks "close to the theoretical fraction," not an exact number.
        assertTrue(movedFractionChr > 0.10 && movedFractionChr < 0.35,
                "consistent hashing moved %.1f%% of keys on a 4->5 join, expected roughly 20%%"
                        .formatted(movedFractionChr * 100));

        // Modulo has no such guarantee at all -- expect it to move the large majority.
        assertTrue(movedFractionModulo > 0.60,
                "naive modulo moved only %.1f%% of keys, expected the large majority to move"
                        .formatted(movedFractionModulo * 100));

        assertTrue(movedFractionChr < movedFractionModulo / 2,
                "consistent hashing (%.1f%%) should move far fewer keys than modulo (%.1f%%) on the same join"
                        .formatted(movedFractionChr * 100, movedFractionModulo * 100));
    }
}
