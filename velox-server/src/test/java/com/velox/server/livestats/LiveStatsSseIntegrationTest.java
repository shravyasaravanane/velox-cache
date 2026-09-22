package com.velox.server.livestats;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M6.1, end to end: a real client opens {@code GET /api/stats/stream} against the real
 * (randomly-ported) server and reads actual bytes off the wire, rather than testing
 * {@link LiveStatsBroadcaster} in isolation -- {@code SseEmitter#send} only behaves correctly
 * attached to a live servlet response, so the meaningful test here is "does a real SSE client
 * actually receive frames," not a unit test of the broadcast loop alone.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class LiveStatsSseIntegrationTest {

    @LocalServerPort
    private int port;

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void aRealClientReceivesAtLeastOneStatsFrame() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/stats/stream"))
                .timeout(Duration.ofSeconds(8))
                .GET()
                .build();

        HttpResponse<java.io.InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                contentType -> assertThat(contentType).contains("text/event-stream"));

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            StringBuilder seen = new StringBuilder();
            String line;
            boolean sawEvent = false;
            boolean sawData = false;
            while ((line = reader.readLine()) != null) {
                seen.append(line).append('\n');
                if (line.startsWith("event:stats")) {
                    sawEvent = true;
                }
                if (line.startsWith("data:") && line.contains("opsPerSecond")) {
                    sawData = true;
                }
                if (sawEvent && sawData) {
                    break;
                }
            }
            assertThat(sawEvent).as("expected an 'event:stats' line, got:%n%s", seen).isTrue();
            assertThat(sawData).as("expected a data line containing opsPerSecond, got:%n%s", seen).isTrue();
        }
    }
}
