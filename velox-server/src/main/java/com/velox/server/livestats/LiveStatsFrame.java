package com.velox.server.livestats;

/**
 * One snapshot frame, broadcast to every connected dashboard at 10 Hz by
 * {@link LiveStatsBroadcaster}. Every rate field ({@code opsPerSecond} etc.) is already
 * per-second -- computed from the delta against the previous tick, divided by the tick
 * interval -- so the dashboard can plot it directly without knowing the tick rate itself.
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
        LatencyPercentilesMs latencyMs) {

    public record LatencyPercentilesMs(double p50, double p90, double p99, double p999) {
    }
}
