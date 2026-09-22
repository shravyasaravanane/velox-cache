import { Bar, BarChart, CartesianGrid, Cell, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import { BLUE, BORDER, CARD, CHART_BG, MUTED, TEAL, TEXT, WARN } from '../theme'
import { useLiveStats } from '../useLiveStats'

const HEAT_COLORS = [TEAL, '#22B8A8', BLUE, '#5B8CE8', '#8BA4EE', MUTED]

function heatColor(rank: number, total: number): string {
  const bucket = Math.min(HEAT_COLORS.length - 1, Math.floor((rank / Math.max(1, total)) * HEAT_COLORS.length))
  return HEAT_COLORS[bucket]
}

export function HotKeysScreen() {
  const { frame, connected } = useLiveStats()

  return (
    <div className="flex flex-col gap-6">
      <div className="flex items-center justify-between">
        <p className="text-sm" style={{ color: MUTED }}>
          <span style={{ color: TEXT }}>HyperLogLog</span> (~0.8% error at the default precision) estimates distinct
          keys ever requested in fixed memory; <span style={{ color: TEXT }}>Space-Saving</span> tracks the current
          top keys by frequency, using memory for exactly K counters, not one per distinct key. Together they stand
          in for a heat map: bar height/color intensity is a key's relative heat.
        </p>
        <div className="flex items-center gap-2">
          <span className="h-2.5 w-2.5 rounded-full" style={{ background: connected ? TEAL : WARN }} />
          <span className="font-mono-tight text-sm" style={{ color: MUTED }}>
            {connected ? 'connected' : 'connecting…'}
          </span>
        </div>
      </div>

      {!frame ? (
        <p className="font-mono-tight text-sm" style={{ color: MUTED }}>
          Waiting for the first frame…
        </p>
      ) : (
        <>
          <div className="flex gap-6">
            <div className="flex flex-1 flex-col gap-2 rounded-2xl border p-6" style={{ background: CARD, borderColor: BORDER }}>
              <p className="font-mono-tight text-xs font-medium tracking-widest uppercase" style={{ color: TEAL }}>
                Distinct keys seen (HyperLogLog estimate)
              </p>
              <p className="text-4xl font-bold" style={{ color: TEXT }}>
                {frame.distinctKeysEstimate.toLocaleString()}
              </p>
            </div>
            <div className="flex flex-1 flex-col gap-2 rounded-2xl border p-6" style={{ background: CARD, borderColor: BORDER }}>
              <p className="font-mono-tight text-xs font-medium tracking-widest uppercase" style={{ color: BLUE }}>
                Tracked hot keys
              </p>
              <p className="text-4xl font-bold" style={{ color: TEXT }}>
                {frame.topKeys.length}
              </p>
            </div>
          </div>

          <div className="flex flex-col gap-4 rounded-2xl border p-6" style={{ background: CARD, borderColor: BORDER }}>
            <p className="font-mono-tight text-xs font-medium tracking-widest uppercase" style={{ color: MUTED }}>
              Hot keys — heat map / top-K (M6.5)
            </p>
            {frame.topKeys.length === 0 ? (
              <p className="font-mono-tight text-sm" style={{ color: MUTED }}>
                No requests recorded yet — hit GET /api/products/{'{id}'} a few times.
              </p>
            ) : (
              <ResponsiveContainer width="100%" height={Math.max(200, frame.topKeys.length * 36)}>
                <BarChart data={frame.topKeys} layout="vertical" margin={{ left: 24 }}>
                  <CartesianGrid strokeDasharray="3 3" stroke={BORDER} horizontal={false} />
                  <XAxis type="number" stroke={MUTED} fontSize={12} />
                  <YAxis type="category" dataKey="key" stroke={MUTED} fontSize={12} width={80} />
                  <Tooltip
                    contentStyle={{ background: CHART_BG, border: `1px solid ${BORDER}`, borderRadius: 8 }}
                    formatter={(v) => [String(v), 'requests (Space-Saving estimate)']}
                    labelFormatter={(label) => `product id ${label}`}
                  />
                  <Bar dataKey="count" radius={[0, 4, 4, 0]} isAnimationActive={false}>
                    {frame.topKeys.map((entry, i) => (
                      <Cell key={entry.key} fill={heatColor(i, frame.topKeys.length)} />
                    ))}
                  </Bar>
                </BarChart>
              </ResponsiveContainer>
            )}
          </div>
        </>
      )}
    </div>
  )
}
