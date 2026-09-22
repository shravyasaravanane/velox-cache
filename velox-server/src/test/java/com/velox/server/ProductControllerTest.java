package com.velox.server;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The end-to-end smoke test for Tier 5's foundation: a real HTTP request, through the real
 * Spring context, hitting the real (if tiny, here) seeded database and the real multi-table
 * join in {@code CatalogQueryService}. If this passes, the whole M5.1/M5.2 pipeline works.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ProductControllerTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void aSeededProductIsReturnedWithJoinedData() {
        var response = restTemplate.getForEntity(url("/api/products/1"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"productId\":1");
        assertThat(response.getBody()).contains("categoryName");
        assertThat(response.getBody()).contains("averageRating");
    }

    @Test
    void aNonExistentProductIsA404() {
        var response = restTemplate.getForEntity(url("/api/products/999999999"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
