package com.velox.server.api;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * M7.7: a thin proxy so the dashboard can show {@code velox-cluster}'s state the same way it
 * reaches everything else -- one origin, {@code /api/*}, through the Vite dev proxy -- without
 * either module depending on the other.
 *
 * <h2>Why a proxy, not a shared dependency</h2>
 *
 * {@code velox-cluster} is a separate module, deliberately velox-core-only (see its own POM):
 * plain text wire protocol, no Spring, no JSON library. Rather than pull Spring into
 * {@code velox-cluster} just so the dashboard could talk to it directly (and rather than have
 * the browser make cross-origin calls to a different port, which {@code velox-cluster}'s bare
 * {@code HttpServer} sets no CORS headers for), this controller does the one small parse job --
 * turning {@code ClusterNode}'s tab-separated {@code /cluster/nodes} response into JSON -- and
 * everything else stays a pass-through. Neither module's build file changes.
 *
 * <h2>The cluster is not started by this application</h2>
 *
 * {@code velox-cluster} nodes are separate OS processes, started by hand (see
 * {@code com.velox.cluster.ClusterNode}'s own Javadoc for the exact command) -- this demo does
 * not spawn them. If {@code velox.demo.cluster-seed} is unreachable, every endpoint here reports
 * that plainly (an empty node list, {@code reachable: false}) rather than failing with a raw
 * connection-refused stack trace.
 */
@RestController
public class ClusterProxyController {

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(800)).build();
    private final String seedBaseUrl;

    public ClusterProxyController(@Value("${velox.demo.cluster-seed:127.0.0.1:7001}") String seedAddress) {
        this.seedBaseUrl = "http://" + seedAddress;
    }

    @GetMapping("/api/cluster/nodes")
    public ClusterView nodes() {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(seedBaseUrl + "/cluster/nodes"))
                    .timeout(Duration.ofSeconds(2))
                    .GET()
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return new ClusterView(false, List.of());
            }
            return new ClusterView(true, parseNodes(response.body()));
        } catch (Exception e) {
            return new ClusterView(false, List.of());
        }
    }

    /** Kills a specific node -- proxies to its own {@code POST /cluster/shutdown}, not the seed's. */
    @PostMapping("/api/cluster/kill")
    public ResponseEntity<Void> kill(@RequestParam String host, @RequestParam int port) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://" + host + ":" + port + "/cluster/shutdown"))
                    .timeout(Duration.ofSeconds(2))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();
            HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
            return ResponseEntity.status(response.statusCode()).build();
        } catch (Exception e) {
            return ResponseEntity.status(502).build();
        }
    }

    /** Proxies a write through the cluster's own routing ({@code PUT} on the seed's {@code /kv/*}),
     * so the dashboard can demonstrate the cluster surviving a kill without a second client. */
    @PostMapping("/api/cluster/demo-put")
    public ResponseEntity<Void> demoPut(@RequestParam String key, @RequestParam String value) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(seedBaseUrl + "/kv/" + encode(key)))
                    .timeout(Duration.ofSeconds(2))
                    .PUT(HttpRequest.BodyPublishers.ofString(value, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
            return ResponseEntity.status(response.statusCode()).build();
        } catch (Exception e) {
            return ResponseEntity.status(502).build();
        }
    }

    @GetMapping("/api/cluster/demo-get")
    public ResponseEntity<String> demoGet(@RequestParam String key) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(seedBaseUrl + "/kv/" + encode(key)))
                    .timeout(Duration.ofSeconds(2))
                    .GET()
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            return ResponseEntity.status(response.statusCode()).body(response.body());
        } catch (Exception e) {
            return ResponseEntity.status(502).body(null);
        }
    }

    private static List<ClusterNodeView> parseNodes(String body) {
        List<ClusterNodeView> nodes = new ArrayList<>();
        for (String line : body.split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            String[] fields = line.split("\t");
            if (fields.length != 4) {
                continue;
            }
            nodes.add(new ClusterNodeView(fields[0], fields[1], Integer.parseInt(fields[2]), Boolean.parseBoolean(fields[3])));
        }
        return nodes;
    }

    private static String encode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    public record ClusterNodeView(String id, String host, int port, boolean healthy) {
    }

    public record ClusterView(boolean reachable, List<ClusterNodeView> nodes) {
    }
}
