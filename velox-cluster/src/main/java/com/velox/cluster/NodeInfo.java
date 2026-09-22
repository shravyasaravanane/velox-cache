package com.velox.cluster;

/** A cluster node's identity: a stable {@code id} used on {@link ConsistentHashRing}, plus
 * where to actually reach it. Health is tracked separately, in {@link ClusterMembership} --
 * identity does not change when a node goes down and comes back, so it does not belong here. */
public record NodeInfo(String id, String host, int port) {

    String baseUrl() {
        return "http://" + host + ":" + port;
    }
}
