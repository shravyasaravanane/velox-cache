package com.velox.cluster;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

/**
 * M7.4: the client half of node-to-node communication -- plain HTTP, plain text bodies, no
 * framework and no serialization library, matching {@code velox-cluster}'s
 * velox-core-only dependency discipline. A node uses this exactly the way an external caller
 * uses {@link ClusterNode}'s own {@code /kv/*} endpoints; the only difference is which paths it
 * calls ({@code /internal/kv/*} to write a replica without triggering further routing, and
 * {@code /cluster/health} for heartbeats).
 */
public final class RemoteCacheClient {

    private final HttpClient http;
    private final Duration timeout;

    public RemoteCacheClient(Duration timeout) {
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    /** @return whether {@code node} answered {@code GET /cluster/health} with 200, within {@link #timeout} */
    public boolean ping(NodeInfo node) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(node.baseUrl() + "/cluster/health"))
                    .timeout(timeout)
                    .GET()
                    .build();
            HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
            return response.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    /** @return the value at {@code key} on {@code node}, or empty if that node reports a genuine miss
     * @param internal true to read via {@code /internal/kv/*} -- this node's own local cache only,
     *                 not the routed, cluster-wide view. Used to verify replication directly:
     *                 {@code get(node, key, false)} always returns the owner's value regardless of
     *                 which node you ask, so checking "does this specific node hold a copy" needs
     *                 the internal, unrouted path instead. */
    public Optional<String> get(NodeInfo node, String key, boolean internal) throws IOException, InterruptedException {
        String path = (internal ? "/internal/kv/" : "/kv/") + encode(key);
        HttpRequest request = HttpRequest.newBuilder(URI.create(node.baseUrl() + path))
                .timeout(timeout)
                .GET()
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 404) {
            return Optional.empty();
        }
        if (response.statusCode() != 200) {
            throw new IOException("GET " + key + " on " + node.id() + " failed: HTTP " + response.statusCode());
        }
        return Optional.of(response.body());
    }

    /** @param internal true to write via {@code /internal/kv/*} -- store locally on {@code node}
     *                  without that node routing or replicating it further */
    public void put(NodeInfo node, String key, String value, boolean internal) throws IOException, InterruptedException {
        String path = (internal ? "/internal/kv/" : "/kv/") + encode(key);
        HttpRequest request = HttpRequest.newBuilder(URI.create(node.baseUrl() + path))
                .timeout(timeout)
                .PUT(HttpRequest.BodyPublishers.ofString(value, StandardCharsets.UTF_8))
                .build();
        HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
        if (response.statusCode() != 204) {
            throw new IOException("PUT " + key + " on " + node.id() + " failed: HTTP " + response.statusCode());
        }
    }

    /** @param internal true to delete via {@code /internal/kv/*}, without further routing */
    public void delete(NodeInfo node, String key, boolean internal) throws IOException, InterruptedException {
        String path = (internal ? "/internal/kv/" : "/kv/") + encode(key);
        HttpRequest request = HttpRequest.newBuilder(URI.create(node.baseUrl() + path))
                .timeout(timeout)
                .DELETE()
                .build();
        HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
        if (response.statusCode() != 204) {
            throw new IOException("DELETE " + key + " on " + node.id() + " failed: HTTP " + response.statusCode());
        }
    }

    /** Tells {@code target} about {@code newPeer}, via {@code POST /cluster/join}. */
    public void join(NodeInfo target, NodeInfo newPeer) throws IOException, InterruptedException {
        String query = "id=" + encode(newPeer.id()) + "&host=" + encode(newPeer.host()) + "&port=" + newPeer.port();
        HttpRequest request = HttpRequest.newBuilder(URI.create(target.baseUrl() + "/cluster/join?" + query))
                .timeout(timeout)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
        if (response.statusCode() != 204) {
            throw new IOException("join " + newPeer.id() + " -> " + target.id() + " failed: HTTP " + response.statusCode());
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
