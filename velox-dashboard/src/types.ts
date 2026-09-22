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
