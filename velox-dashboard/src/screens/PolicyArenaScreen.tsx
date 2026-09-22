import { Bar, BarChart, CartesianGrid, Cell, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import { BLUE, BORDER, CARD, CHART_BG, MUTED, TEAL, TEXT, WARN } from '../theme'
import { useLiveStats } from '../useLiveStats'

const POLICY_COLORS: Record<string, string> = {
  LRU: '#64748B',
  FIFO: '#94A3B8',
  RANDOM: '#B0B8C4',
  CLOCK: '#7C93B8',
  LFU: '#A855F7',
  LFU_AGED: '#C084FC',
  SLRU: BLUE,
  TWO_Q: '#5B8CE8',
  LRU_K: '#F59E0B',
  ARC: WARN,
  W_TINY_LFU: TEAL,
}

function colorFor(policy: string): string {
  return POLICY_COLORS[policy] ?? '#94A3B8'
}

export function PolicyArenaScreen() {
  const { frame, connected } = useLiveStats()
  const standings = frame?.arenaStandings ?? []

  return (
    <div className="flex flex-col gap-6">
      <div className="flex items-center justify-between">
        <p className="text-sm" style={{ color: MUTED }}>
          Every policy listed races against the <span style={{ color: TEXT }}>identical</span> request stream, in a
          key-only shadow cache -- not the primary cache the rest of the dashboard controls, so hot-swapping the
          primary policy elsewhere never affects this race. See{' '}
          <code className="font-mono-tight">PolicyArena</code> for why this needed no changes to the eviction
          policies themselves.
        </p>
        <div className="flex items-center gap-2">
          <span className="h-2.5 w-2.5 rounded-full" style={{ background: connected ? TEAL : WARN }} />
          <span className="font-mono-tight text-sm" style={{ color: MUTED }}>
            {connected ? 'connected' : 'connecting…'}
          </span>
        </div>
      </div>

      {!frame || standings.length === 0 ? (
        <p className="font-mono-tight text-sm" style={{ color: MUTED }}>
          Waiting for the first frame…
        </p>
      ) : (
        <div className="flex gap-6">
          <div className="flex flex-[2] flex-col gap-4 rounded-2xl border p-6" style={{ background: CARD, borderColor: BORDER }}>
            <p className="font-mono-tight text-xs font-medium tracking-widest uppercase" style={{ color: MUTED }}>
              Hit rate by policy — same traffic, {standings.length}-way race
            </p>
            <ResponsiveContainer width="100%" height={Math.max(260, standings.length * 34)}>
              <BarChart data={standings} layout="vertical" margin={{ left: 8 }}>
                <CartesianGrid strokeDasharray="3 3" stroke={BORDER} horizontal={false} />
                <XAxis type="number" domain={[0, 100]} stroke={MUTED} fontSize={12} unit="%" />
                <YAxis type="category" dataKey="policy" stroke={MUTED} fontSize={12} width={100} />
                <Tooltip
                  contentStyle={{ background: CHART_BG, border: `1px solid ${BORDER}`, borderRadius: 8 }}
                  formatter={(v) => [`${Number(v).toFixed(2)}%`, 'hit rate']}
                />
                <Bar dataKey="hitRatePercent" radius={[0, 4, 4, 0]}>
                  {standings.map((entry) => (
                    <Cell key={entry.policy} fill={colorFor(entry.policy)} />
                  ))}
                </Bar>
              </BarChart>
            </ResponsiveContainer>
          </div>

          <div className="flex flex-1 flex-col gap-3 rounded-2xl border p-6" style={{ background: CARD, borderColor: BORDER }}>
            <p className="font-mono-tight text-xs font-medium tracking-widest uppercase" style={{ color: MUTED }}>
              Leaderboard
            </p>
            <ol className="flex flex-col gap-2">
              {standings.map((entry, i) => (
                <li key={entry.policy} className="flex items-center justify-between rounded-lg px-3 py-2" style={{ background: CHART_BG }}>
                  <span className="flex items-center gap-2">
                    <span className="font-mono-tight text-xs" style={{ color: MUTED }}>
                      #{i + 1}
                    </span>
                    <span className="h-2 w-2 rounded-full" style={{ background: colorFor(entry.policy) }} />
                    <span className="font-mono-tight text-sm font-medium" style={{ color: TEXT }}>
                      {entry.policy}
                    </span>
                  </span>
                  <span className="font-mono-tight text-sm" style={{ color: TEAL }}>
                    {entry.hitRatePercent.toFixed(1)}%
                  </span>
                </li>
              ))}
            </ol>
          </div>
        </div>
      )}
    </div>
  )
}
