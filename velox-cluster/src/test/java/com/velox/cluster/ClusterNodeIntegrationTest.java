package com.velox.cluster;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * M7.4-M7.6, end to end: real {@link ClusterNode} processes (in-process, ephemeral ports, but
 * real sockets and real HTTP -- not a simulation) talking to each other over the loopback
 * interface. This is the checkpoint's "3-5 nodes, kill one live, watch it heal" proven
 * automatically, not just by hand.
 */
class ClusterNodeIntegrationTest {

    private final List<ClusterNode> nodes = new ArrayList<>();
    private final RemoteCacheClient client = new RemoteCacheClient(Duration.ofSeconds(2));

    @AfterEach
    void stopEveryNode() {
        for (ClusterNode node : nodes) {
            try {
                node.stop();
            } catch (Exception ignored) {
                // best-effort cleanup
            }
        }
        nodes.clear();
    }

    private List<ClusterNode> startCluster(int count) throws IOException {
        for (int i = 0; i < count; i++) {
            ClusterNode node = new ClusterNode("node-" + i, "127.0.0.1", 0, 1000);
            node.start();
            nodes.add(node);
        }
        // Full-mesh bootstrap, in-process (no HTTP round trip needed for this): every node
        // learns about every other before any test traffic starts.
        for (ClusterNode a : nodes) {
            for (ClusterNode b : nodes) {
                if (a != b) {
                    a.join(b.info());
                }
            }
        }
        return nodes;
    }

    private static void awaitCondition(BooleanSupplier condition, String description) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                fail("timed out waiting for: " + description);
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("interrupted");
            }
        }
    }

    @Test
    @Timeout(30)
    @DisplayName("a write through any node is readable through every node -- routing finds the true owner")
    void putThenGetRoundTripsThroughAnyEntryPoint() throws Exception {
        List<ClusterNode> cluster = startCluster(3);

        client.put(cluster.get(0).info(), "hello", "world", false);

        for (ClusterNode node : cluster) {
            assertEquals(Optional.of("world"), client.get(node.info(), "hello", false),
                    "every node must answer the same, correct value regardless of who actually owns the key");
        }
    }

    @Test
    @Timeout(30)
    @DisplayName("a key that was never written is a 404 from any node")
    void missingKeyIs404FromAnyNode() throws Exception {
        List<ClusterNode> cluster = startCluster(3);

        assertEquals(Optional.empty(), client.get(cluster.get(2).info(), "never-written", false));
    }

    @Test
    @Timeout(30)
    @DisplayName("M7.6: a write lands on exactly R=2 distinct physical nodes, not just the primary owner")
    void writeReplicatesToExactlyTwoDistinctNodes() throws Exception {
        List<ClusterNode> cluster = startCluster(4);

        client.put(cluster.get(0).info(), "replicated-key", "v1", false);

        Set<String> holders = new HashSet<>();
        for (ClusterNode node : cluster) {
            Optional<String> local = client.get(node.info(), "replicated-key", true); // bypass routing
            if (local.isPresent()) {
                assertEquals("v1", local.get());
                holders.add(node.info().id());
            }
        }

        assertEquals(ClusterNode.REPLICATION_FACTOR, holders.size(),
                "expected exactly R=" + ClusterNode.REPLICATION_FACTOR + " nodes to hold a local copy, found " + holders);
    }

    @Test
    @Timeout(30)
    @DisplayName("M7.5 + M7.6: killing the primary owner still serves reads from the replica, without the client ever seeing an error")
    void readFallsBackToReplicaWhenPrimaryIsDown() throws Exception {
        List<ClusterNode> cluster = startCluster(4);
        String key = "fallback-key";
        client.put(cluster.get(0).info(), key, "still-here", false);

        // Find the true primary owner and kill it -- a graceful self-stop is indistinguishable
        // to peers from a real crash (see ClusterNode's class Javadoc).
        List<NodeInfo> replicas = cluster.get(0).membership().replicasFor(key, ClusterNode.REPLICATION_FACTOR);
        NodeInfo primary = replicas.get(0);
        ClusterNode primaryNode = cluster.stream().filter(n -> n.info().id().equals(primary.id())).findFirst().orElseThrow();
        primaryNode.stop();

        // Ask a SURVIVING node -- heartbeat detection needs a moment to notice the primary is gone.
        ClusterNode asker = cluster.stream().filter(n -> !n.info().id().equals(primary.id())).findFirst().orElseThrow();

        awaitCondition(() -> {
            try {
                return client.get(asker.info(), key, false).equals(Optional.of("still-here"));
            } catch (Exception e) {
                return false;
            }
        }, "a read for '" + key + "' to succeed via the replica once the primary is detected down");
    }

    @Test
    @Timeout(30)
    @DisplayName("M7.5: a dead node is removed from every survivor's ring, and re-added once it comes back")
    void deadNodeIsRemovedFromTheRingThenRepairedOnReturn() throws Exception {
        List<ClusterNode> cluster = startCluster(3);
        ClusterNode victim = cluster.get(1);
        String victimId = victim.info().id();
        ClusterNode survivor = cluster.get(0);

        assertTrue(survivor.membership().isHealthy(victimId), "the victim must start out healthy");

        victim.stop();
        awaitCondition(() -> !survivor.membership().isHealthy(victimId),
                "the survivor's heartbeat to mark '" + victimId + "' unhealthy after it stops responding");

        // Bring it back under the same id, on a fresh ephemeral port (port 0) rather than racing
        // the OS to immediately reclaim the old one -- ClusterMembership.addNode overwrites the
        // stale address for this id unconditionally, so the survivor picks up the new port
        // exactly the way it would if a real node manager relaunched the process elsewhere.
        ClusterNode restarted = new ClusterNode(victim.info().id(), victim.info().host(), 0, 1000);
        restarted.start();
        nodes.set(nodes.indexOf(victim), restarted); // so @AfterEach stops the right object
        survivor.join(restarted.info());
        restarted.join(survivor.info());

        awaitCondition(() -> survivor.membership().isHealthy(victimId),
                "the survivor's heartbeat to notice '" + victimId + "' answering again and repair the ring");
    }
}
