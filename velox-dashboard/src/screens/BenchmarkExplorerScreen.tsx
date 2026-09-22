import { useMemo, useState } from 'react'
import type { ReactNode } from 'react'
import { Bar, BarChart, CartesianGrid, Cell, Line, LineChart, Legend, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import { BLUE, BORDER, CARD, CHART_BG, MUTED, TEAL, TEXT, WARN } from '../theme'
import { useCsv } from '../useCsv'
import type { HitRatioRow, ThreadScalingRow } from '../types'

const IMPL_COLORS: Record<string, string> = {
  VELOX_SHARDED_BUFFERED: TEAL,
  VELOX_SHARDED_EXCLUSIVE: BLUE,
  VELOX_GLOBAL_LOCK: WARN,
  CAFFEINE: '#A855F7',
  GUAVA: '#F472B6',
  CONCURRENT_HASH_MAP: MUTED,
  SYNC_LINKED_HASH_MAP: '#64748B',
}

function implColor(impl: string): string {
  return IMPL_COLORS[impl] ?? '#94A3B8'
}

function toHitRatioRow(raw: Record<string, string>): HitRatioRow {
  return {
    workload: raw.workload,
    workloadParams: raw.workload_params,
    keySpace: Number(raw.key_space),
    capacity: Number(raw.capacity),
    seed: Number(raw.seed),
    policy: raw.policy,
    hits: Number(raw.hits),
    misses: Number(raw.misses),
    hitRate: Number(raw.hit_rate),
  }
}

function toThreadScalingRow(raw: Record<string, string>): ThreadScalingRow {
  return {
    capacityPercent: Number(raw.capacity_percent),
    impl: raw.impl,
    threads: Number(raw.threads),
    opsPerMs: Number(raw.ops_per_ms),
    ci999Halfwidth: Number(raw.ci999_halfwidth),
    hitRatioPercent: Number(raw.hit_ratio_percent),
    droppedReads: Number(raw.dropped_reads),
  }
}

function Panel({ title, caption, children }: { title: string; caption?: string; children: ReactNode }) {
  return (
    <div className="flex flex-1 flex-col gap-4 rounded-2xl border p-6" style={{ background: CARD, borderColor: BORDER }}>
      <div>
        <p className="font-mono-tight text-xs font-medium tracking-widest uppercase" style={{ color: MUTED }}>
          {title}
        </p>
        {caption && (
          <p className="mt-1 text-xs" style={{ color: MUTED }}>
            {caption}
          </p>
        )}
      </div>
      {children}
    </div>
  )
}

function Select({
  label,
  value,
  options,
  onChange,
}: {
  label: string
  value: string
  options: string[]
  onChange: (value: string) => void
}) {
  return (
    <label className="flex flex-col gap-1 text-sm" style={{ color: MUTED }}>
      {label}
      <select
        value={value}
        onChange={(e) => onChange(e.target.value)}
        className="font-mono-tight rounded-lg border px-3 py-1.5 text-sm outline-none"
        style={{ background: '#0F1320', borderColor: BORDER, color: TEXT }}
      >
        {options.map((o) => (
          <option key={o} value={o}>
            {o}
          </option>
        ))}
      </select>
    </label>
  )
}

function HitRatioExplorer() {
  const { rows, loading, error } = useCsv('/benchmarks/hit-ratio-matrix.csv', toHitRatioRow)

  const workloads = useMemo(() => [...new Set(rows.map((r) => r.workload))].sort(), [rows])
  const capacities = useMemo(() => [...new Set(rows.map((r) => r.capacity))].sort((a, b) => a - b), [rows])

  const [workload, setWorkload] = useState('')
  const [capacity, setCapacity] = useState('')

  const effectiveWorkload = workload || workloads[0] || ''
  const effectiveCapacity = capacity ? Number(capacity) : capacities[0] ?? 0

  const chartData = useMemo(() => {
    const matching = rows.filter((r) => r.workload === effectiveWorkload && r.capacity === effectiveCapacity)
    const bySeedGroup = new Map<string, number[]>()
    for (const row of matching) {
      const list = bySeedGroup.get(row.policy) ?? []
      list.push(row.hitRate)
      bySeedGroup.set(row.policy, list)
    }
    return [...bySeedGroup.entries()]
      .map(([policy, rates]) => ({
        policy,
        hitRatePercent: (rates.reduce((a, b) => a + b, 0) / rates.length) * 100,
        isCeiling: policy === 'BELADY-OPTIMAL',
      }))
      .sort((a, b) => b.hitRatePercent - a.hitRatePercent)
  }, [rows, effectiveWorkload, effectiveCapacity])

  return (
    <Panel
      title="Hit ratio by policy (Tier 4, M4.3)"
      caption="864-row matrix -- 11 policies x 3 capacities x 8 workloads x 3 seeds. Averaged over seeds here. BELADY-OPTIMAL is the offline ceiling, not a real online policy."
    >
      {loading && (
        <p className="font-mono-tight text-sm" style={{ color: MUTED }}>
          loading hit-ratio-matrix.csv…
        </p>
      )}
      {error && (
        <p className="font-mono-tight text-sm" style={{ color: WARN }}>
          {error}
        </p>
      )}
      {!loading && !error && (
        <>
          <div className="flex gap-3">
            <Select label="workload" value={effectiveWorkload} options={workloads} onChange={setWorkload} />
            <Select
              label="capacity"
              value={String(effectiveCapacity)}
              options={capacities.map(String)}
              onChange={setCapacity}
            />
          </div>
          <ResponsiveContainer width="100%" height={280}>
            <BarChart data={chartData} margin={{ bottom: 24 }}>
              <CartesianGrid strokeDasharray="3 3" stroke={BORDER} />
              <XAxis dataKey="policy" stroke={MUTED} fontSize={11} angle={-35} textAnchor="end" interval={0} />
              <YAxis stroke={MUTED} fontSize={12} width={40} domain={[0, 100]} />
              <Tooltip
                contentStyle={{ background: CHART_BG, border: `1px solid ${BORDER}`, borderRadius: 8 }}
                formatter={(v) => [`${Number(v).toFixed(2)}%`, 'hit rate']}
              />
              <Bar dataKey="hitRatePercent" radius={[4, 4, 0, 0]} isAnimationActive={false}>
                {chartData.map((entry) => (
                  <Cell key={entry.policy} fill={entry.isCeiling ? WARN : TEAL} />
                ))}
              </Bar>
            </BarChart>
          </ResponsiveContainer>
        </>
      )}
    </Panel>
  )
}

function ThreadScalingExplorer() {
  const [dataset, setDataset] = useState<'default' | 'write-heavy'>('default')
  const url = dataset === 'default' ? '/benchmarks/thread-scaling.csv' : '/benchmarks/thread-scaling-guava-write-heavy.csv'
  const { rows, loading, error } = useCsv(url, toThreadScalingRow)

  const capacityPercents = useMemo(() => [...new Set(rows.map((r) => r.capacityPercent))].sort((a, b) => a - b), [rows])
  const [capacityPercent, setCapacityPercent] = useState('')
  const effectiveCapacityPercent = capacityPercent ? Number(capacityPercent) : capacityPercents[0] ?? 0

  const impls = useMemo(
    () => [...new Set(rows.filter((r) => r.capacityPercent === effectiveCapacityPercent).map((r) => r.impl))],
    [rows, effectiveCapacityPercent],
  )

  const chartData = useMemo(() => {
    const threads = [...new Set(rows.map((r) => r.threads))].sort((a, b) => a - b)
    return threads.map((t) => {
      const point: Record<string, number> = { threads: t }
      for (const row of rows) {
        if (row.capacityPercent === effectiveCapacityPercent && row.threads === t) {
          point[row.impl] = row.opsPerMs
        }
      }
      return point
    })
  }, [rows, effectiveCapacityPercent])

  return (
    <Panel
      title="Thread scaling: ops/ms vs threads (Tier 2, M2.9 / Tier 4, M4.4)"
      caption="velox-core's sharded cache against ConcurrentHashMap, a synchronized LinkedHashMap, Guava and Caffeine."
    >
      {loading && (
        <p className="font-mono-tight text-sm" style={{ color: MUTED }}>
          loading…
        </p>
      )}
      {error && (
        <p className="font-mono-tight text-sm" style={{ color: WARN }}>
          {error}
        </p>
      )}
      {!loading && !error && (
        <>
          <div className="flex gap-3">
            <Select
              label="dataset"
              value={dataset}
              options={['default', 'write-heavy']}
              onChange={(v) => setDataset(v as 'default' | 'write-heavy')}
            />
            <Select
              label="capacity %"
              value={String(effectiveCapacityPercent)}
              options={capacityPercents.map(String)}
              onChange={setCapacityPercent}
            />
          </div>
          <ResponsiveContainer width="100%" height={280}>
            <LineChart data={chartData}>
              <CartesianGrid strokeDasharray="3 3" stroke={BORDER} />
              <XAxis dataKey="threads" stroke={MUTED} fontSize={12} />
              <YAxis stroke={MUTED} fontSize={12} width={50} />
              <Tooltip contentStyle={{ background: CHART_BG, border: `1px solid ${BORDER}`, borderRadius: 8 }} />
              <Legend wrapperStyle={{ fontSize: 11 }} />
              {impls.map((impl) => (
                <Line
                  key={impl}
                  type="monotone"
                  dataKey={impl}
                  stroke={implColor(impl)}
                  dot={false}
                  isAnimationActive={false}
                  connectNulls
                />
              ))}
            </LineChart>
          </ResponsiveContainer>
        </>
      )}
    </Panel>
  )
}

export function BenchmarkExplorerScreen() {
  return (
    <div className="flex flex-col gap-6">
      <p className="text-sm" style={{ color: MUTED }}>
        Loads the checked-in Tier 4 result CSVs directly (see{' '}
        <code className="font-mono-tight">scripts/sync-benchmark-data.mjs</code>) -- this is historical, already-measured
        data, not a live view of this server.
      </p>
      <HitRatioExplorer />
      <ThreadScalingExplorer />
    </div>
  )
}
