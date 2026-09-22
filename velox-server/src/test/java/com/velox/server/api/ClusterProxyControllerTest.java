package com.velox.server.api;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests {@link ClusterProxyController} against a small, JDK-only fake node -- not a real
 * {@code com.velox.cluster.ClusterNode}, deliberately: {@code velox-server} does not depend on
 * {@code velox-cluster} even at test scope, matching the proxy's whole point (see its own class
 * Javadoc). A fake server answering {@code /cluster/nodes} with the exact tab-separated shape
 * the real one uses is enough to test the parsing and proxying, which is all this controller
 * actually does.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ClusterProxyControllerTest {

    private static HttpServer fakeSeedNode;

    @DynamicPropertySource
    static void registerSeed(DynamicPropertyRegistry registry) throws IOException {
        fakeSeedNode = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fakeSeedNode.createContext("/cluster/nodes", exchange -> {
            String body = "node-A\t127.0.0.1\t7001\ttrue\nnode-B\t127.0.0.1\t7002\tfalse\n";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        fakeSeedNode.setExecutor(null);
        fakeSeedNode.start();
        registry.add("velox.demo.cluster-seed", () -> "127.0.0.1:" + fakeSeedNode.getAddress().getPort());
    }

    @AfterAll
    static void stopFakeSeedNode() {
        fakeSeedNode.stop(0);
    }

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void nodesReflectsTheSeedsResponseIncludingUnhealthyOnes() {
        var response = restTemplate.getForEntity(url("/api/cluster/nodes"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"reachable\":true");
        assertThat(response.getBody()).contains("\"id\":\"node-A\"").contains("\"healthy\":true");
        assertThat(response.getBody()).contains("\"id\":\"node-B\"").contains("\"healthy\":false");
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
