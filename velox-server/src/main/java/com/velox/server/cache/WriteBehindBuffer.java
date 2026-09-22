package com.velox.server.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Write-behind's whole reason to exist: buffer writes instead of applying them immediately,
 * and if the same key is written again before the buffer is flushed, the earlier write is
 * simply replaced rather than both ever reaching the database.
 *
 * <h2>Coalescing, concretely</h2>
 *
 * A single {@code ConcurrentHashMap<Long, ProductUpdate>} keyed by product id. {@link #stage}
 * always does a plain {@code put}: writing the same key twice before a flush leaves exactly
 * one pending write (the newer one) — the first was never lost, it was superseded, which is
 * correct for write-behind precisely because the cache (not this buffer) is what callers read
 * from in the meantime; nobody observes the intermediate value going missing from the
 * database, because nobody was reading the database for this key at all.
 *
 * <h2>The number this tier's report asks for</h2>
 *
 * {@link #stats()} exposes how many logical writes were staged versus how many database
 * writes were actually issued. Under a hot key written repeatedly between flushes, the ratio
 * between the two <b>is</b> the write-amplification reduction, measured rather than assumed.
 */
@Component
public class WriteBehindBuffer {

    private static final Logger log = LoggerFactory.getLogger(WriteBehindBuffer.class);

    private final Map<Long, ProductUpdate> pending = new ConcurrentHashMap<>();
    private final AtomicLong totalStaged = new AtomicLong();
    private final AtomicLong totalFlushedWrites = new AtomicLong();
    private final AtomicLong totalFlushPasses = new AtomicLong();

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    public WriteBehindBuffer(JdbcTemplate jdbcTemplate, TransactionTemplate transactionTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
    }

    /** Buffers {@code update} for {@code productId}, replacing any earlier pending write for it. */
    public void stage(long productId, ProductUpdate update) {
        totalStaged.incrementAndGet();
        pending.put(productId, update);
    }

    /** @return how many products currently have a write waiting for the next flush */
    public int pendingCount() {
        return pending.size();
    }

    public record Stats(long staged, long flushedWrites, long flushPasses) {

        /** @return the fraction of staged writes that never reached the database, coalesced away by a later one */
        public double amplificationReduction() {
            return staged == 0 ? 0.0 : 1.0 - (double) flushedWrites / staged;
        }
    }

    public Stats stats() {
        return new Stats(totalStaged.get(), totalFlushedWrites.get(), totalFlushPasses.get());
    }

    /**
     * Applies every currently-pending write to the database in one batch, on a fixed delay
     * (2 seconds by default: {@code velox.demo.write-behind-flush-ms}).
     *
     * <p>Draining {@code pending} key by key via {@code remove} (not, say, {@code clear} after
     * copying) means a write staged <i>during</i> this flush for a key already drained is not
     * lost — it simply waits for the next pass, exactly as if it had arrived a moment later.
     */
    @Scheduled(fixedDelayString = "${velox.demo.write-behind-flush-ms:2000}")
    public void flush() {
        if (pending.isEmpty()) {
            return;
        }
        Map<Long, ProductUpdate> batch = new HashMap<>();
        for (Long productId : List.copyOf(pending.keySet())) {
            ProductUpdate update = pending.remove(productId);
            if (update != null) {
                batch.put(productId, update);
            }
        }
        if (batch.isEmpty()) {
            return;
        }

        List<Object[]> args = new ArrayList<>(batch.size());
        for (Map.Entry<Long, ProductUpdate> entry : batch.entrySet()) {
            ProductUpdate u = entry.getValue();
            args.add(new Object[] {u.name(), u.description(), u.priceCents(), entry.getKey()});
        }
        transactionTemplate.executeWithoutResult(status -> jdbcTemplate.batchUpdate(
                "UPDATE product SET name = ?, description = ?, price_cents = ? WHERE id = ?", args));

        totalFlushedWrites.addAndGet(batch.size());
        totalFlushPasses.incrementAndGet();
        log.debug("write-behind flush: {} product(s) written", batch.size());
    }
}
