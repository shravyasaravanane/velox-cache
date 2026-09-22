import type { ReactNode } from 'react'
import {
  Area,
  Bar,
  BarChart,
  CartesianGrid,
  ComposedChart,
  Line,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts'
import { useLiveStats } from './useLiveStats'

const BLUE = '#3B6FE0'
const TEAL = '#0EA5A3'
const WARN = '#D97706'
const TEXT = '#F6F7F9'
const MUTED = '#8B95A7'
const CARD = '#1A2030'
const BORDER = '#2A3142'

function StatCard({
  label,
  value,
  unit,
  accent = BLUE,
}: {
  label: string
  value: string
  unit?: string
  accent?: string
}) {
  return (
    <div className="flex flex-1 flex-col gap-2 rounded-2xl border p-6" style={{ background: CARD, borderColor: BORDER }}>
      <p className="font-mono-tight text-xs font-medium tracking-widest uppercase" style={{ color: accent }}>
        {label}
      </p>
      <p className="text-4xl font-bold" style={{ color: TEXT }}>
        {value}
        {unit && (
          <span className="ml-1 text-lg font-medium" style={{ color: MUTED }}>
            {unit}
          </span>
        )}
      </p>
    </div>
  )
}

function Panel({ title, children }: { title: string; children: ReactNode }) {
  return (
    <div className="flex flex-1 flex-col gap-4 rounded-2xl border p-6" style={{ background: CARD, borderColor: BORDER }}>
      <p className="font-mono-tight text-xs font-medium tracking-widest uppercase" style={{ color: MUTED }}>
        {title}
      </p>
      {children}
    </div>
  )
}

function App() {
  const { frame, connected, history } = useLiveStats()

  return (
    <div className="min-h-screen w-full px-8 py-8" style={{ background: '#12161F' }}>
      <header className="mb-8 flex items-center justify-between">
        <div>
          <p className="font-mono-tight text-xs font-medium tracking-widest uppercase" style={{ color: TEAL }}>
            VeloxCache
          </p>
          <h1 className="text-3xl font-bold" style={{ color: TEXT }}>
            Live Ops
          </h1>
        </div>
        <div className="flex items-center gap-6">
          {frame && (
            <span className="font-mono-tight text-sm" style={{ color: MUTED }}>
              policy: <span style={{ color: TEXT }}>{frame.policy}</span>
            </span>
          )}
          <div className="flex items-center gap-2">
            <span
              className="h-2.5 w-2.5 rounded-full"
              style={{ background: connected ? TEAL : WARN }}
            />
            <span className="font-mono-tight text-sm" style={{ color: MUTED }}>
              {connected ? 'connected' : 'connecting…'}
            </span>
          </div>
        </div>
      </header>

      {!frame ? (
        <p className="font-mono-tight text-sm" style={{ color: MUTED }}>
          Waiting for the first frame from /api/stats/stream…
        </p>
      ) : (
        <div className="flex flex-col gap-6">
          <div className="flex gap-6">
            <StatCard label="Ops / sec" value={frame.opsPerSecond.toFixed(0)} accent={BLUE} />
            <StatCard label="Hit rate" value={frame.hitRatePercent.toFixed(1)} unit="%" accent={TEAL} />
            <StatCard label="Evictions / sec" value={frame.evictionsPerSecond.toFixed(1)} accent={WARN} />
            <StatCard label="Rejections / sec" value={frame.rejectionsPerSecond.toFixed(2)} accent={MUTED} />
          </div>

          <div className="flex gap-6">
            <Panel title="Hit rate (%) & ops/sec — last 30s">
              <ResponsiveContainer width="100%" height={220}>
                <ComposedChart data={history}>
                  <CartesianGrid strokeDasharray="3 3" stroke={BORDER} />
                  <XAxis dataKey="t" tick={false} stroke={BORDER} />
                  <YAxis yAxisId="rate" domain={[0, 100]} stroke={MUTED} fontSize={12} width={36} />
                  <YAxis yAxisId="ops" orientation="right" stroke={MUTED} fontSize={12} width={36} />
                  <Tooltip
                    contentStyle={{ background: '#0F1320', border: `1px solid ${BORDER}`, borderRadius: 8 }}
                    labelFormatter={() => ''}
                  />
                  <Area
                    yAxisId="rate"
                    type="monotone"
                    dataKey="hitRatePercent"
                    stroke={TEAL}
                    fill={TEAL}
                    fillOpacity={0.15}
                    isAnimationActive={false}
                    name="hit rate %"
                  />
                  <Line
                    yAxisId="ops"
                    type="monotone"
                    dataKey="opsPerSecond"
                    stroke={BLUE}
                    dot={false}
                    isAnimationActive={false}
                    name="ops/sec"
                  />
                </ComposedChart>
              </ResponsiveContainer>
            </Panel>

            <Panel title="Capacity fill">
              <div className="flex flex-1 flex-col justify-center gap-3">
                <p className="text-3xl font-bold" style={{ color: TEXT }}>
                  {frame.size.toLocaleString()}
                  <span className="text-lg font-medium" style={{ color: MUTED }}>
                    {' '}
                    / {frame.maximumSize.toLocaleString()}
                  </span>
                </p>
                <div className="h-3 w-full overflow-hidden rounded-full" style={{ background: '#0F1320' }}>
                  <div
                    className="h-full rounded-full transition-all duration-300"
                    style={{
                      width: `${Math.min(100, frame.capacityFillPercent)}%`,
                      background: frame.capacityFillPercent > 90 ? WARN : BLUE,
                    }}
                  />
                </div>
                <p className="font-mono-tight text-sm" style={{ color: MUTED }}>
                  {frame.capacityFillPercent.toFixed(1)}% full
                </p>
              </div>
            </Panel>
          </div>

          <div className="flex gap-6">
            <Panel title="Latency percentiles (ms)">
              <ResponsiveContainer width="100%" height={200}>
                <BarChart
                  data={[
                    { name: 'p50', ms: frame.latencyMs.p50 },
                    { name: 'p90', ms: frame.latencyMs.p90 },
                    { name: 'p99', ms: frame.latencyMs.p99 },
                    { name: 'p999', ms: frame.latencyMs.p999 },
                  ]}
                >
                  <CartesianGrid strokeDasharray="3 3" stroke={BORDER} />
                  <XAxis dataKey="name" stroke={MUTED} fontSize={12} />
                  <YAxis stroke={MUTED} fontSize={12} width={36} />
                  <Tooltip contentStyle={{ background: '#0F1320', border: `1px solid ${BORDER}`, borderRadius: 8 }} />
                  <Bar dataKey="ms" fill={BLUE} radius={[4, 4, 0, 0]} isAnimationActive={false} />
                </BarChart>
              </ResponsiveContainer>
            </Panel>

            <Panel title={`Per-shard load (${frame.shardSizes.length} shards)`}>
              <ResponsiveContainer width="100%" height={200}>
                <BarChart data={frame.shardSizes.map((entries, i) => ({ shard: i, entries }))}>
                  <CartesianGrid strokeDasharray="3 3" stroke={BORDER} />
                  <XAxis dataKey="shard" stroke={MUTED} fontSize={12} />
                  <YAxis stroke={MUTED} fontSize={12} width={36} />
                  <Tooltip contentStyle={{ background: '#0F1320', border: `1px solid ${BORDER}`, borderRadius: 8 }} />
                  <Bar dataKey="entries" fill={TEAL} radius={[4, 4, 0, 0]} isAnimationActive={false} />
                </BarChart>
              </ResponsiveContainer>
            </Panel>
          </div>
        </div>
      )}
    </div>
  )
}

export default App
