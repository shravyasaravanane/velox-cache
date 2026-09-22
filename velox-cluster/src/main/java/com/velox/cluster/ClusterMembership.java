package com.velox.cluster;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * M7.5: heartbeat, failure detection, and automatic ring repair.
 *
 * <h2>The model: each node keeps its own view, nobody votes</h2>
 *
 * There is no consensus protocol here -- no Raft, no Paxos, no quorum. Every node independently
 * pings every peer it knows about on a fixed schedule and maintains its <i>own</i>
 * {@link ConsistentHashRing} accordingly: a node {@link #failureThreshold} consecutive missed
 * pings behind is removed from the ring (repair), and a node that starts answering again is
 * added back. Two nodes can disagree, briefly, about whether a third is up -- that is the
 * honest cost of skipping consensus, disclosed rather than hidden. What it buys back: no
 * election protocol, no split-brain handling, nothing to get wrong there, appropriate for a
 * teaching cluster whose point is consistent hashing and replication, not distributed consensus
 * (a genuinely different, much larger problem).
 *
 * <h2>Why removal from the ring, not from the node list</h2>
 *
 * {@link #allNodes()} keeps reporting a node as known even while it is unhealthy -- heartbeats
 * keep being sent to it so it can be noticed coming back. Only {@link ConsistentHashRing}
 * itself, which is what {@link ClusterNode} actually routes against, drops an unhealthy node,
 * so requests stop being sent to a peer that is not answering without losing track of it
 * entirely.
 */
public final class ClusterMembership {

    private final String selfId;
    private final ConsistentHashRing ring;
    private final RemoteCacheClient client;
    private final int failureThreshold;

    private final Map<String, NodeInfo> allKnownNodes = new ConcurrentHashMap<>();
    private final Map<String, Boolean> healthy = new ConcurrentHashMap<>();
    private final Map<String, Integer> consecutiveFailures = new ConcurrentHashMap<>();

    private final ScheduledExecutorService heartbeatScheduler;

    /**
     * @param selfId             this node's own id -- never pinged, always considered healthy
     * @param virtualNodesPerNode see {@link ConsistentHashRing}
     * @param client              used to ping peers
     * @param heartbeatInterval   how often every known peer is pinged
     * @param failureThreshold    consecutive missed pings before a node is removed from the ring
     */
    public ClusterMembership(String selfId, int virtualNodesPerNode, RemoteCacheClient client,
            Duration heartbeatInterval, int failureThreshold) {
        this.selfId = selfId;
        this.ring = new ConsistentHashRing(virtualNodesPerNode);
        this.client = client;
        this.failureThreshold = failureThreshold;

        ThreadFactoryDaemon threadFactory = new ThreadFactoryDaemon("velox-heartbeat-" + selfId);
        this.heartbeatScheduler = Executors.newSingleThreadScheduledExecutor(threadFactory);
        this.heartbeatScheduler.scheduleWithFixedDelay(this::checkHealth,
                heartbeatInterval.toMillis(), heartbeatInterval.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** Adds (or re-adds) a node: known, healthy, and on the ring immediately. */
    public void addNode(NodeInfo node) {
        allKnownNodes.put(node.id(), node);
        healthy.put(node.id(), true);
        consecutiveFailures.put(node.id(), 0);
        ring.addNode(node.id());
    }

    /** @return this node's own current view of who is healthy -- what routing actually uses */
    public boolean isHealthy(String nodeId) {
        return nodeId.equals(selfId) || healthy.getOrDefault(nodeId, false);
    }

    /** @return every node ever added, healthy or not */
    public List<NodeInfo> allNodes() {
        return List.copyOf(allKnownNodes.values());
    }

    public NodeInfo info(String nodeId) {
        return allKnownNodes.get(nodeId);
    }

    /** @return up to {@code count} distinct nodes for {@code key} -- see {@link ConsistentHashRing#nodesFor} */
    public List<NodeInfo> replicasFor(String key, int count) {
        List<String> ids = ring.nodesFor(key, count);
        List<NodeInfo> result = new ArrayList<>(ids.size());
        for (String id : ids) {
            result.add(allKnownNodes.get(id));
        }
        return result;
    }

    /** Releases the heartbeat thread. Safe to call more than once. */
    public void shutdown() {
        heartbeatScheduler.shutdownNow();
    }

    private void checkHealth() {
        for (NodeInfo node : allKnownNodes.values()) {
            if (node.id().equals(selfId)) {
                continue;
            }
            boolean alive = client.ping(node);
            if (alive) {
                consecutiveFailures.put(node.id(), 0);
                if (!healthy.getOrDefault(node.id(), true)) {
                    healthy.put(node.id(), true);
                    ring.addNode(node.id()); // repair: welcome it back
                }
            } else {
                int failures = consecutiveFailures.merge(node.id(), 1, Integer::sum);
                if (failures >= failureThreshold && healthy.getOrDefault(node.id(), true)) {
                    healthy.put(node.id(), false);
                    ring.removeNode(node.id()); // repair: stop routing to it
                }
            }
        }
    }

    private record ThreadFactoryDaemon(String name) implements java.util.concurrent.ThreadFactory {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        }
    }
}
