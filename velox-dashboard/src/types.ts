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

/** One row of docs/benchmarks/data/hit-ratio-matrix.csv (Tier 4, M4.3). */
export interface HitRatioRow {
  workload: string
  workloadParams: string
  keySpace: number
  capacity: number
  seed: number
  policy: string
  hits: number
  misses: number
  hitRate: number
}

/** One row of docs/benchmarks/data/thread-scaling*.csv (Tier 2, M2.9 / Tier 4, M4.4). */
export interface ThreadScalingRow {
  capacityPercent: number
  impl: string
  threads: number
  opsPerMs: number
  ci999Halfwidth: number
  hitRatioPercent: number
  droppedReads: number
}
