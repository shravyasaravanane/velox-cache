package com.velox.server.cache;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A plain unit test, deliberately not a {@code @SpringBootTest}: coalescing and flushing are
 * properties of this one class and its own tiny, private database, not of the wider
 * application context, so there is nothing to gain from paying Spring's startup cost here --
 * and a private database sidesteps any question of interference from other tests sharing a
 * bean's mutable state.
 */
class WriteBehindBufferTest {

    private JdbcTemplate jdbcTemplate;
    private WriteBehindBuffer buffer;

    @BeforeEach
    void setUp() {
        // DB_CLOSE_DELAY=-1: SimpleDriverDataSource opens a fresh connection per call rather
        // than pooling, and H2 destroys an in-memory database the moment its last connection
        // closes -- without this, the table created in this method would already be gone by
        // the time the first test method's own connection ran its first statement.
        var dataSource = new SimpleDriverDataSource(new org.h2.Driver(),
                "jdbc:h2:mem:write-behind-test-" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("CREATE TABLE product (id BIGINT PRIMARY KEY, name VARCHAR(200), "
                + "description VARCHAR(2000), price_cents BIGINT)");
        jdbcTemplate.update("INSERT INTO product (id, name, description, price_cents) VALUES (5, 'v0', 'd', 0)");
        buffer = new WriteBehindBuffer(jdbcTemplate, new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
    }

    @Test
    @DisplayName("repeated writes to the same key before a flush coalesce into exactly one database write")
    void coalescesRepeatedWrites() {
        for (int i = 0; i < 5; i++) {
            buffer.stage(5, new ProductUpdate("v" + i, "d", i));
        }
        assertEquals(1, buffer.pendingCount(), "five writes to the same key must coalesce to one pending write");

        buffer.flush();
        var stats = buffer.stats();

        assertEquals(5, stats.staged());
        assertEquals(1, stats.flushedWrites(), "only the last of the five writes should ever reach the database");
        assertEquals(1, stats.flushPasses());
        assertEquals(0.8, stats.amplificationReduction(), 0.001);
        assertEquals("v4", jdbcTemplate.queryForObject("SELECT name FROM product WHERE id = 5", String.class),
                "the LAST staged write must be the one that wins");
    }

    @Test
    @DisplayName("writes to different keys all reach the database -- coalescing only applies within one key")
    void distinctKeysAreNotCoalesced() {
        jdbcTemplate.update("INSERT INTO product (id, name, description, price_cents) VALUES (9, 'v0', 'd', 0)");
        buffer.stage(5, new ProductUpdate("a", "d", 1));
        buffer.stage(9, new ProductUpdate("b", "d", 2));

        buffer.flush();

        assertEquals(2, buffer.stats().flushedWrites());
        assertEquals("a", jdbcTemplate.queryForObject("SELECT name FROM product WHERE id = 5", String.class));
        assertEquals("b", jdbcTemplate.queryForObject("SELECT name FROM product WHERE id = 9", String.class));
    }

    @Test
    @DisplayName("flushing an empty buffer does nothing and is safe to call repeatedly")
    void flushingEmptyBufferIsANoOp() {
        buffer.flush();
        buffer.flush();

        assertEquals(0, buffer.stats().staged());
        assertEquals(0, buffer.stats().flushedWrites());
        assertEquals(0, buffer.stats().flushPasses());
    }
}
