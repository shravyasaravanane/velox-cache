package com.velox.server;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/** End-to-end coverage for M6.3's step-mode endpoints, over real HTTP. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class VisualizerControllerTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void stepReturnsASnapshotReflectingTheStep() {
        // This @SpringBootTest context (and so InternalsVisualizer's state) is shared across
        // this class's test methods, so this checks the new key is present, not that it is
        // the only one -- method execution order is not something to depend on here.
        var response = restTemplate.postForEntity(url("/api/visualizer/step?key=777"), null, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("777");
    }

    @Test
    void pauseThenResumeRoundTrips() {
        var pause = restTemplate.postForEntity(url("/api/visualizer/pause"), null, Void.class);
        assertThat(pause.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        var afterPause = restTemplate.postForEntity(url("/api/visualizer/step?key=1"), null, String.class);
        assertThat(afterPause.getBody()).contains("\"paused\":true");

        var resume = restTemplate.postForEntity(url("/api/visualizer/resume"), null, Void.class);
        assertThat(resume.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        var afterResume = restTemplate.postForEntity(url("/api/visualizer/step?key=2"), null, String.class);
        assertThat(afterResume.getBody()).contains("\"paused\":false");
    }

    @Test
    void stepsAccumulateAcrossAllThreePolicyViews() {
        restTemplate.postForEntity(url("/api/visualizer/step?key=101"), null, String.class);
        var response = restTemplate.postForEntity(url("/api/visualizer/step?key=102"), null, String.class);

        assertThat(response.getBody()).contains("\"lru\"").contains("\"arc\"").contains("\"tinyLfu\"");
        assertThat(response.getBody()).contains("101").contains("102");
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
