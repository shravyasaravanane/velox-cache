package com.velox.server.livestats;

import java.util.List;

/**
 * One snapshot frame, broadcast to every connected dashboard at 10 Hz by
 * {@link LiveStatsBroadcaster}. Every rate field ({@code opsPerSecond} etc.) is already
 * per-second -- computed from the delta against the previous tick, divided by the tick
 * interval -- so the dashboard can plot it directly without knowing the tick rate itself.
 *
 * @param distinctKeysEstimate {@link com.velox.core.sketch.HyperLogLog}'s running estimate of
 *                              how many distinct product ids have been requested, ever -- not a
 *                              per-tick number, since distinctness only grows
 * @param topKeys              {@link TopKTracker}'s current leaderboard, count descending
 */
public record LiveStatsFrame(
        long timestampMillis,
        String policy,
        double opsPerSecond,
        double hitRatePercent,
        double evictionsPerSecond,
        double rejectionsPerSecond,
        int size,
        int maximumSize,
        double capacityFillPercent,
        int[] shardSizes,
        LatencyPercentilesMs latencyMs,
        long distinctKeysEstimate,
        List<TopKTracker.Entry> topKeys) {

    public record LatencyPercentilesMs(double p50, double p90, double p99, double p999) {
    }
}
