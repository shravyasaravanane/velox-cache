package com.velox.cluster;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.velox.core.Cache;
import com.velox.core.CacheBuilder;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;

/**
 * M7.4/M7.6: one cluster node -- a real, standalone HTTP server (JDK's built-in
 * {@link HttpServer}, no framework) wrapping one local {@link Cache}, that can answer a request
 * for <b>any</b> key, not just the ones it owns: it looks up the owner on its
 * {@link ClusterMembership}'s ring and forwards to that peer if the owner is not itself. Any
 * node is a valid entry point for the whole cluster -- the router, not a separate load-balancer
 * tier, is what makes that true.
 *
 * <h2>Replication: best-effort, {@code R = }{@link #REPLICATION_FACTOR}</h2>
 *
 * A write is sent to the key's primary owner and to the next distinct physical node on the
 * ring, synchronously, in the same request. It is acknowledged once <b>at least one</b> of
 * those writes lands -- not both. That is a deliberate, disclosed simplification, not a strong
 * two-phase-commit guarantee: a real system would need to decide, and document, exactly this
 * trade-off, and this one does. What it buys is real: a read that finds the primary down can
 * still return a correct, recent value from the replica (see {@link #handleGet}).
 *
 * <h2>Why {@code /internal/kv/*} exists, separate from {@code /kv/*}</h2>
 *
 * {@code /kv/*} is the routing-aware, client-facing path: it looks up the owner and may forward
 * or replicate. If a replica write used that same path on the receiving node, that node would
 * route and replicate <i>again</i>, on and on. {@code /internal/kv/*} is always handled purely
 * locally, no matter which node receives it -- what a replica write, and only a replica write,
 * is supposed to do.
 */
public final class ClusterNode {

    public static final int REPLICATION_FACTOR = 2;

    private final NodeInfo self;
    private final Cache<String, String> localCache;
    private final ClusterMembership membership;
    private final RemoteCacheClient client;
    private final HttpServer server;
    private final java.util.concurrent.ExecutorService requestExecutor;

    public ClusterNode(String id, String host, int requestedPort, int cacheCapacity) throws IOException {
        this.server = HttpServer.create(new InetSocketAddress(host, requestedPort), 0);
        int actualPort = server.getAddress().getPort();
        this.self = new NodeInfo(id, host, actualPort);
        this.localCache = CacheBuilder.<String, String>newBuilder()
                .maximumSize(cacheCapacity)
                .concurrencyLevel(4) // a real HTTP server has many request threads at once
                .build();
        this.client = new RemoteCacheClient(Duration.ofMillis(500));
        this.membership = new ClusterMembership(id, 500, client, Duration.ofMillis(500), 3);
        membership.addNode(self);

        server.createContext("/kv/", this::handleRoutedKv);
        server.createContext("/internal/kv/", this::handleInternalKv);
        server.createContext("/cluster/health", this::handleHealth);
        server.createContext("/cluster/nodes", this::handleNodes);
        server.createContext("/cluster/join", this::handleJoin);
        server.createContext("/cluster/shutdown", this::handleShutdown);
        // HttpServer#stop() does NOT shut down a custom executor -- that is the caller's job per
        // its own Javadoc. Missed the first time this was written: the cached pool's worker
        // threads are non-daemon, so a "stopped" node's JVM process never actually exited, only
        // its server did. Found live, running the real kill-node demo: two "gracefully stopped"
        // node processes kept running. stop() below now shuts this down explicitly.
        this.requestExecutor = Executors.newCachedThreadPool();
        server.setExecutor(requestExecutor);
    }

    public void start() {
        server.start();
    }

    /** Stops accepting connections and releases the heartbeat thread. Idempotent. */
    public void stop() {
        server.stop(0);
        requestExecutor.shutdownNow();
        membership.shutdown();
    }

    public NodeInfo info() {
        return self;
    }

    public ClusterMembership membership() {
        return membership;
    }

    /** Tells this node about {@code peer} directly (in-process bootstrap, no HTTP round trip). */
    public void join(NodeInfo peer) {
        membership.addNode(peer);
    }

    // ------------------------------------------------------------------
    //  /kv/* -- routing-aware: local if we own it, forwarded/replicated if not
    // ------------------------------------------------------------------

    private void handleRoutedKv(HttpExchange exchange) throws IOException {
        String key = keyFrom(exchange, "/kv/");
        List<NodeInfo> replicas = membership.replicasFor(key, REPLICATION_FACTOR);
        if (replicas.isEmpty()) {
            sendStatus(exchange, 503);
            return;
        }
        NodeInfo primary = replicas.get(0);
        NodeInfo secondary = replicas.size() > 1 ? replicas.get(1) : null;

        switch (exchange.getRequestMethod()) {
            case "GET" -> handleGet(exchange, key, primary, secondary);
            case "PUT" -> handlePut(exchange, key, primary, secondary);
            case "DELETE" -> handleDelete(exchange, key, primary, secondary);
            default -> sendStatus(exchange, 405);
        }
    }

    private void handleGet(HttpExchange exchange, String key, NodeInfo primary, NodeInfo secondary) throws IOException {
        for (NodeInfo candidate : readOrder(primary, secondary)) {
            try {
                Optional<String> value = readFrom(candidate, key);
                if (value.isPresent()) {
                    sendBody(exchange, 200, value.get());
                } else {
                    sendStatus(exchange, 404); // a genuine miss from a reachable node: stop here
                }
                return;
            } catch (Exception e) {
                // candidate unreachable despite looking healthy -- fall through to the next one
            }
        }
        sendStatus(exchange, 503); // every replica unreachable
    }

    private void handlePut(HttpExchange exchange, String key, NodeInfo primary, NodeInfo secondary) throws IOException {
        String value = readBody(exchange);
        boolean primaryOk = tryWrite(primary, key, value);
        boolean secondaryOk = secondary != null && tryWrite(secondary, key, value);
        sendStatus(exchange, primaryOk || secondaryOk ? 204 : 503);
    }

    private void handleDelete(HttpExchange exchange, String key, NodeInfo primary, NodeInfo secondary) throws IOException {
        boolean primaryOk = tryDelete(primary, key);
        boolean secondaryOk = secondary != null && tryDelete(secondary, key);
        sendStatus(exchange, primaryOk || secondaryOk ? 204 : 503);
    }

    private List<NodeInfo> readOrder(NodeInfo primary, NodeInfo secondary) {
        List<NodeInfo> order = new ArrayList<>(2);
        if (membership.isHealthy(primary.id())) {
            order.add(primary);
            if (secondary != null) {
                order.add(secondary);
            }
        } else {
            if (secondary != null) {
                order.add(secondary);
            }
            order.add(primary);
        }
        return order;
    }

    private Optional<String> readFrom(NodeInfo node, String key) throws Exception {
        if (node.id().equals(self.id())) {
            return Optional.ofNullable(localCache.getIfPresent(key));
        }
        // internal: `node` was already picked as a specific replica by THIS node's own ring view
        // (membership.replicasFor above). Routing through /kv/* on the receiving node again would
        // let that node's own, independently-maintained ring view (see ClusterMembership's class
        // Javadoc on why there is no consensus between nodes) potentially pick someone else --
        // the exact inconsistency /internal/kv/* exists to avoid on the write side already.
        return client.get(node, key, true);
    }

    private boolean tryWrite(NodeInfo node, String key, String value) {
        try {
            if (node.id().equals(self.id())) {
                localCache.put(key, value);
            } else {
                client.put(node, key, value, true);
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean tryDelete(NodeInfo node, String key) {
        try {
            if (node.id().equals(self.id())) {
                localCache.invalidate(key);
            } else {
                client.delete(node, key, true);
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ------------------------------------------------------------------
    //  /internal/kv/* -- always local, never routed or replicated further
    // ------------------------------------------------------------------

    private void handleInternalKv(HttpExchange exchange) throws IOException {
        String key = keyFrom(exchange, "/internal/kv/");
        switch (exchange.getRequestMethod()) {
            case "GET" -> {
                String value = localCache.getIfPresent(key);
                if (value == null) {
                    sendStatus(exchange, 404);
                } else {
                    sendBody(exchange, 200, value);
                }
            }
            case "PUT" -> {
                localCache.put(key, readBody(exchange));
                sendStatus(exchange, 204);
            }
            case "DELETE" -> {
                localCache.invalidate(key);
                sendStatus(exchange, 204);
            }
            default -> sendStatus(exchange, 405);
        }
    }

    // ------------------------------------------------------------------
    //  Cluster membership endpoints
    // ------------------------------------------------------------------

    private void handleHealth(HttpExchange exchange) throws IOException {
        sendBody(exchange, 200, "OK");
    }

    /** One line per known node: {@code id\thost\tport\thealthy}. Tab-separated, not JSON --
     * this module has no JSON library and does not need one for a format this simple. */
    private void handleNodes(HttpExchange exchange) throws IOException {
        StringBuilder body = new StringBuilder();
        for (NodeInfo node : membership.allNodes()) {
            body.append(node.id()).append('\t')
                    .append(node.host()).append('\t')
                    .append(node.port()).append('\t')
                    .append(membership.isHealthy(node.id()))
                    .append('\n');
        }
        sendBody(exchange, 200, body.toString());
    }

    private void handleJoin(HttpExchange exchange) throws IOException {
        Map<String, String> params = parseQuery(exchange.getRequestURI());
        String id = params.get("id");
        String host = params.get("host");
        String portStr = params.get("port");
        if (id == null || host == null || portStr == null) {
            sendStatus(exchange, 400);
            return;
        }
        membership.addNode(new NodeInfo(id, host, Integer.parseInt(portStr)));
        sendStatus(exchange, 204);
    }

    /** A graceful self-stop, indistinguishable to peers from a real crash: both simply stop
     * answering heartbeats. Used for the "kill node" demo -- see the class Javadoc on why a
     * real process kill and this look the same from every other node's point of view. */
    private void handleShutdown(HttpExchange exchange) throws IOException {
        sendStatus(exchange, 204);
        Thread stopper = new Thread(this::stop, "velox-node-" + self.id() + "-stopper");
        stopper.setDaemon(true);
        stopper.start();
    }

    // ------------------------------------------------------------------
    //  HTTP plumbing
    // ------------------------------------------------------------------

    private static String keyFrom(HttpExchange exchange, String prefix) {
        String path = exchange.getRequestURI().getPath();
        return URLDecoder.decode(path.substring(prefix.length()), StandardCharsets.UTF_8);
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void sendStatus(HttpExchange exchange, int status) throws IOException {
        exchange.sendResponseHeaders(status, -1);
        exchange.close();
    }

    private static void sendBody(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static Map<String, String> parseQuery(URI uri) {
        Map<String, String> result = new HashMap<>();
        String query = uri.getRawQuery();
        if (query == null || query.isEmpty()) {
            return result;
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) {
                continue;
            }
            String key = URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
            String value = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            result.put(key, value);
        }
        return result;
    }

    /** Standalone launcher for a live, multi-process demo:
     * {@code java -cp ... com.velox.cluster.ClusterNode <id> <host> <port> [<capacity>]} */
    public static void main(String[] args) throws IOException {
        if (args.length < 3) {
            System.err.println("usage: ClusterNode <id> <host> <port> [<capacity>]");
            System.exit(1);
        }
        String id = args[0];
        String host = args[1];
        int port = Integer.parseInt(args[2]);
        int capacity = args.length > 3 ? Integer.parseInt(args[3]) : 1000;

        ClusterNode node = new ClusterNode(id, host, port, capacity);
        node.start();
        System.out.println("velox-cluster node '" + id + "' listening on " + host + ":" + node.info().port());
    }
}
