export interface LatencyPercentilesMs {
  p50: number
  p90: number
  p99: number
  p999: number
}

export interface TopKeyEntry {
  key: number
  count: number
}

/** Mirrors com.velox.server.livestats.PolicyArena.Standing. */
export interface ArenaStanding {
  policy: string
  hits: number
  misses: number
  hitRatePercent: number
}

/** Mirrors com.velox.server.api.ClusterProxyController.ClusterNodeView. */
export interface ClusterNodeView {
  id: string
  host: string
  port: number
  healthy: boolean
}

/** Mirrors com.velox.server.api.ClusterProxyController.ClusterView. */
export interface ClusterView {
  reachable: boolean
  nodes: ClusterNodeView[]
}

/** Mirrors com.velox.server.livestats.InternalsVisualizer.Snapshot. */
export interface VisualizerSnapshot {
  paused: boolean
  capacity: number
  lru: { mruToLru: number[] }
  arc: { t1: number[]; t2: number[]; b1: number[]; b2: number[]; targetT1Size: number }
  tinyLfu: { window: number[]; probation: number[]; protectedKeys: number[]; frequencies: Record<string, number> }
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
  distinctKeysEstimate: number
  topKeys: TopKeyEntry[]
  arenaStandings: ArenaStanding[]
  visualizer: VisualizerSnapshot
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
