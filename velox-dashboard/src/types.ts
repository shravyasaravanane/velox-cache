export interface LatencyPercentilesMs {
  p50: number
  p90: number
  p99: number
  p999: number
}

/** Mirrors com.velox.server.livestats.LiveStatsFrame, field for field. */
export interface LiveStatsFrame {
  timestampMillis: number
  policy: string
  opsPerSecond: number
  hitRatePercent: number
  evictionsPerSecond: number
  rejectionsPerSecond: number
  size: number
  maximumSize: number
  capacityFillPercent: number
  shardSizes: number[]
  latencyMs: LatencyPercentilesMs
}

/** Mirrors com.velox.server.api.ChaosController.Report -- the before/after CacheStats delta
 * every chaos endpoint returns. */
export interface ChaosReport {
  hits: number
  misses: number
  loads: number
  evictions: number
  coalesced: number
  hitRatePercent: number
}
