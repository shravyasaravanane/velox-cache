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

/**
 * End-to-end coverage for M5.4 (A/B toggle, hot-swap), M5.6 (stats/admin/chaos). Every admin
 * endpoint here mutates the shared primary cache (policy, capacity, or its contents), so this
 * class dirties the context once it is done rather than risk another test class observing a
 * cache left mid-experiment.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AdminChaosStatsControllerTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    // ------------------------------------------------------------------
    //  M5.4: A/B toggle
    // ------------------------------------------------------------------

    @Test
    void cacheOffServesTheSameDataAsTheDefaultPath() {
        var response = restTemplate.getForEntity(url("/api/products/1?cache=off"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"productId\":1");
    }

    @Test
    void cacheVariantByNameServesTheSameData() {
        var response = restTemplate.getForEntity(url("/api/products/1?cache=lru"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"productId\":1");
    }

    @Test
    void anUnknownVariantNameIsRejected() {
        var response = restTemplate.getForEntity(url("/api/products/1?cache=nonexistent-policy"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void aMissingIdUnderAVariantIsA404() {
        var response = restTemplate.getForEntity(url("/api/products/999999999?cache=off"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ------------------------------------------------------------------
    //  M5.6: /api/stats
    // ------------------------------------------------------------------

    @Test
    void statsReportsThePrimaryCacheAndEveryVariant() {
        restTemplate.getForEntity(url("/api/products/1"), String.class);
        restTemplate.getForEntity(url("/api/products/1?cache=lru"), String.class);

        var response = restTemplate.getForEntity(url("/api/stats"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"primary\"");
        assertThat(response.getBody()).contains("\"variants\"");
        assertThat(response.getBody()).contains("\"writeBehind\"");
        assertThat(response.getBody()).contains("\"lru\"");
        assertThat(response.getBody()).contains("\"off\"");
    }

    // ------------------------------------------------------------------
    //  M5.6: admin
    // ------------------------------------------------------------------

    @Test
    void policyHotSwapChangesThePolicyAndEmptiesTheCache() {
        restTemplate.getForEntity(url("/api/products/2"), String.class);

        var swap = restTemplate.postForEntity(url("/api/admin/policy/ARC"), null, Void.class);
        assertThat(swap.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        var stats = restTemplate.getForEntity(url("/api/stats"), String.class);
        assertThat(stats.getBody()).contains("\"policy\":\"ARC\"");
        assertThat(stats.getBody()).contains("\"size\":0");
    }

    @Test
    void anUnknownPolicyNameIsRejected() {
        var response = restTemplate.postForEntity(url("/api/admin/policy/NOT-A-POLICY"), null, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void capacityResizeChangesCapacityAndEmptiesTheCache() {
        var resize = restTemplate.postForEntity(url("/api/admin/capacity/123"), null, Void.class);
        assertThat(resize.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        var stats = restTemplate.getForEntity(url("/api/stats"), String.class);
        assertThat(stats.getBody()).contains("\"capacity\":123");
    }

    @Test
    void invalidCapacityIsRejected() {
        var response = restTemplate.postForEntity(url("/api/admin/capacity/0"), null, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void invalidateFlushesThePrimaryCache() {
        restTemplate.getForEntity(url("/api/products/3"), String.class);

        var response = restTemplate.postForEntity(url("/api/admin/invalidate"), null, Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        var stats = restTemplate.getForEntity(url("/api/stats"), String.class);
        assertThat(stats.getBody()).contains("\"size\":0");
    }

    @Test
    void invalidateTagReturnsHowManyKeysItDropped() {
        var product = restTemplate.getForEntity(url("/api/products/4"), com.velox.server.domain.ProductDetails.class);
        String tag = "category:" + product.getBody().categoryName();

        var response = restTemplate.postForEntity(url("/api/admin/invalidate-tag/" + tag), null, Integer.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isGreaterThanOrEqualTo(1);
    }

    // ------------------------------------------------------------------
    //  M5.6: chaos
    // ------------------------------------------------------------------

    @Test
    void stampedeCollapsesIntoASingleDatabaseLoad() {
        var response = restTemplate.postForEntity(url("/api/chaos/stampede?key=10&n=30"), null, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"loads\":1");
    }

    @Test
    void scanReportsAStatsDelta() {
        var response = restTemplate.postForEntity(url("/api/chaos/scan?startId=1&n=10"), null, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"misses\"");
    }

    @Test
    void expireAllEmptiesTheCache() {
        restTemplate.getForEntity(url("/api/products/1"), String.class);

        var response = restTemplate.postForEntity(url("/api/chaos/expire-all"), null, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        var stats = restTemplate.getForEntity(url("/api/stats"), String.class);
        assertThat(stats.getBody()).contains("\"size\":0");
    }

    @Test
    void penetrateMissesEveryTime() {
        var response = restTemplate.postForEntity(url("/api/chaos/penetrate?n=5"), null, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"misses\":5");
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
