package com.velox.server.livestats;

import com.velox.core.sketch.HyperLogLog;
import com.velox.core.stats.CacheStats;
import com.velox.server.cache.HotSwappableCache;
import com.velox.server.domain.ProductDetails;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Drives the live dashboard's Screen 1: on a fixed 100 ms tick, snapshots the primary cache and
 * the request-latency ring buffer, turns the delta since the previous tick into per-second
 * rates, and pushes one {@link LiveStatsFrame} to every connected {@code GET /api/stats/stream}
 * client.
 *
 * <h2>Why per-second rates, not raw counters</h2>
 *
 * {@code docs/SYSTEM.md} calls for "one snapshot frame at 10 Hz" specifically so a browser never
 * has to reconstruct a rate from a stream of raw cumulative counters -- each frame already says
 * "X ops/sec right now," ready to plot.
 *
 * <h2>Why the tick still runs with nobody connected</h2>
 *
 * If the tick simply skipped work while {@link #emitters} was empty, the moment the dashboard
 * reconnects after being closed for a while, the next tick's "since the previous tick" delta
 * would include everything that happened during the entire gap, reported as one enormous
 * instantaneous spike. Rolling the baseline forward every tick regardless of whether anyone is
 * watching keeps the first frame after a reconnect honest.
 */
@Component
public class LiveStatsBroadcaster {

    private final HotSwappableCache<Long, ProductDetails> primaryCache;
    private final LatencyRingBuffer latencyRingBuffer;
    private final HyperLogLog cardinalityEstimator;
    private final TopKTracker topKTracker;
    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    private CacheStats previousStats;
    private long previousTickNanos;

    public LiveStatsBroadcaster(HotSwappableCache<Long, ProductDetails> primaryCache,
            LatencyRingBuffer latencyRingBuffer, HyperLogLog cardinalityEstimator, TopKTracker topKTracker) {
        this.primaryCache = primaryCache;
        this.latencyRingBuffer = latencyRingBuffer;
        this.cardinalityEstimator = cardinalityEstimator;
        this.topKTracker = topKTracker;
        this.previousStats = primaryCache.stats();
        this.previousTickNanos = System.nanoTime();
    }

    /** Registers a newly-opened SSE connection; deregisters itself on completion, timeout, or error. */
    public void subscribe(SseEmitter emitter) {
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));
    }

    @Scheduled(fixedRate = 100)
    void tick() {
        long now = System.nanoTime();
        CacheStats current = primaryCache.stats();
        CacheStats delta = current.minus(previousStats);
        double elapsedSeconds = Math.max(0.001, (now - previousTickNanos) / 1_000_000_000.0);
        previousStats = current;
        previousTickNanos = now;

        if (emitters.isEmpty()) {
            return;
        }

        int size = primaryCache.size();
        int maximumSize = primaryCache.currentCapacity();

        LiveStatsFrame frame = new LiveStatsFrame(
                System.currentTimeMillis(),
                primaryCache.currentPolicy().name(),
                (delta.hitCount() + delta.missCount()) / elapsedSeconds,
                delta.hitRate() * 100.0,
                delta.evictionCount() / elapsedSeconds,
                delta.rejectionCount() / elapsedSeconds,
                size,
                maximumSize,
                maximumSize == 0 ? 0.0 : (size * 100.0) / maximumSize,
                primaryCache.shardSizes(),
                new LiveStatsFrame.LatencyPercentilesMs(
                        latencyRingBuffer.percentileMillis(0.50),
                        latencyRingBuffer.percentileMillis(0.90),
                        latencyRingBuffer.percentileMillis(0.99),
                        latencyRingBuffer.percentileMillis(0.999)),
                cardinalityEstimator.estimate(),
                topKTracker.topK());

        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name("stats").data(frame));
            } catch (IOException | IllegalStateException e) {
                // A failed send means this connection is already gone or already being torn
                // down by the container (a disconnected client, a completed test context).
                // completeWithError() itself can throw IllegalStateException if the container
                // got there first -- the emitter's own onError/onCompletion callbacks (wired in
                // subscribe()) already deregister it, so all that is needed here is to stop
                // sending to it and swallow whatever completeWithError adds on top.
                emitters.remove(emitter);
                try {
                    emitter.completeWithError(e);
                } catch (RuntimeException ignored) {
                    // Already completed by the container; nothing left to do.
                }
            }
        }
    }
}
