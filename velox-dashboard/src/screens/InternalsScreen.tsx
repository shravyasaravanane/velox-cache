import { useState } from 'react'
import { AnimatePresence, motion } from 'framer-motion'
import { BLUE, BORDER, CARD, CHART_BG, MUTED, TEAL, TEXT, WARN } from '../theme'
import { useLiveStats } from '../useLiveStats'

async function postVisualizer(path: string, params: Record<string, string | number> = {}): Promise<void> {
  const query = new URLSearchParams(Object.entries(params).map(([k, v]) => [k, String(v)])).toString()
  const response = await fetch(`/api/visualizer/${path}${query ? `?${query}` : ''}`, { method: 'POST' })
  if (!response.ok) {
    throw new Error(`${path} failed: HTTP ${response.status}`)
  }
}

function KeyBox({ label, freq, accent = BLUE }: { label: number; freq?: number; accent?: string }) {
  return (
    <motion.div
      layout
      initial={{ opacity: 0, scale: 0.7 }}
      animate={{ opacity: 1, scale: 1 }}
      exit={{ opacity: 0, scale: 0.5, backgroundColor: '#DC4444' }}
      transition={{ type: 'spring', stiffness: 420, damping: 32 }}
      className="font-mono-tight flex h-12 min-w-12 items-center justify-center rounded-lg px-2 text-sm font-semibold"
      style={{ background: accent, color: '#0B0E15' }}
    >
      {label}
      {freq !== undefined && (
        <span className="ml-1 rounded-full px-1 text-[10px]" style={{ background: 'rgba(0,0,0,0.25)' }}>
          f{freq}
        </span>
      )}
    </motion.div>
  )
}

function Lane({
  label,
  keys,
  accent,
  ghost = false,
  frequencies,
}: {
  label: string
  keys: number[]
  accent: string
  ghost?: boolean
  frequencies?: Record<string, number>
}) {
  return (
    <div
      className="flex flex-col gap-2 rounded-xl p-3"
      style={{ background: CHART_BG, border: ghost ? `1px dashed ${BORDER}` : `1px solid ${BORDER}` }}
    >
      <p className="font-mono-tight text-[10px] font-medium tracking-widest uppercase" style={{ color: MUTED }}>
        {label} ({keys.length})
      </p>
      <div className="flex min-h-14 flex-wrap gap-2">
        <AnimatePresence>
          {keys.map((k) => (
            <KeyBox key={k} label={k} accent={accent} freq={frequencies?.[String(k)]} />
          ))}
        </AnimatePresence>
      </div>
    </div>
  )
}

export function InternalsScreen() {
  const { frame, connected } = useLiveStats()
  const [stepKey, setStepKey] = useState(1)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const viz = frame?.visualizer

  async function act(action: () => Promise<void>) {
    setBusy(true)
    setError(null)
    try {
      await action()
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="flex flex-col gap-6">
      <div className="flex items-center justify-between">
        <p className="text-sm" style={{ color: MUTED }}>
          LRU, ARC and W-TinyLFU are driven directly, at a small capacity ({viz?.capacity ?? '…'}) so the structure
          stays legible -- real Tier 3 policy classes, not a simplified re-implementation. Live traffic feeds them
          automatically; pause to freeze the view and step through operations by hand to narrate the algorithm.
        </p>
        <div className="flex items-center gap-2">
          <span className="h-2.5 w-2.5 rounded-full" style={{ background: connected ? TEAL : WARN }} />
          <span className="font-mono-tight text-sm" style={{ color: MUTED }}>
            {connected ? 'connected' : 'connecting…'}
          </span>
        </div>
      </div>

      <div className="flex items-center gap-3 rounded-2xl border p-4" style={{ background: CARD, borderColor: BORDER }}>
        <button
          onClick={() => act(() => postVisualizer(viz?.paused ? 'resume' : 'pause'))}
          disabled={busy}
          className="font-mono-tight rounded-lg px-4 py-2 text-sm font-semibold disabled:opacity-50"
          style={{ background: viz?.paused ? TEAL : WARN, color: '#0B0E15' }}
        >
          {viz?.paused ? 'resume live traffic' : 'pause (step mode)'}
        </button>
        <label className="flex items-center gap-2 text-sm" style={{ color: MUTED }}>
          key
          <input
            type="number"
            value={stepKey}
            onChange={(e) => setStepKey(Number(e.target.value))}
            className="font-mono-tight w-24 rounded-lg border px-2 py-1.5 text-sm outline-none"
            style={{ background: CHART_BG, borderColor: BORDER, color: TEXT }}
          />
        </label>
        <button
          onClick={() => act(() => postVisualizer('step', { key: stepKey }))}
          disabled={busy}
          className="font-mono-tight rounded-lg px-4 py-2 text-sm font-semibold disabled:opacity-50"
          style={{ background: BLUE, color: '#0B0E15' }}
        >
          step
        </button>
        {error && (
          <span className="font-mono-tight text-sm" style={{ color: '#DC4444' }}>
            {error}
          </span>
        )}
      </div>

      {!viz ? (
        <p className="font-mono-tight text-sm" style={{ color: MUTED }}>
          Waiting for the first frame…
        </p>
      ) : (
        <div className="flex flex-col gap-6">
          <div className="flex flex-col gap-3 rounded-2xl border p-6" style={{ background: CARD, borderColor: BORDER }}>
            <p className="font-mono-tight text-xs font-medium tracking-widest uppercase" style={{ color: TEAL }}>
              LRU -- the intrusive doubly linked list, MRU left
            </p>
            <Lane label="recency (MRU → LRU)" keys={viz.lru.mruToLru} accent={TEAL} />
          </div>

          <div className="flex flex-col gap-3 rounded-2xl border p-6" style={{ background: CARD, borderColor: BORDER }}>
            <div className="flex items-center justify-between">
              <p className="font-mono-tight text-xs font-medium tracking-widest uppercase" style={{ color: BLUE }}>
                ARC -- four lanes, adaptive p = {viz.arc.targetT1Size.toFixed(2)}
              </p>
            </div>
            <div className="grid grid-cols-2 gap-3">
              <Lane label="T1 (recency)" keys={viz.arc.t1} accent={BLUE} />
              <Lane label="T2 (frequency)" keys={viz.arc.t2} accent="#5B8CE8" />
              <Lane label="B1 ghost (recency)" keys={viz.arc.b1} accent={MUTED} ghost />
              <Lane label="B2 ghost (frequency)" keys={viz.arc.b2} accent={MUTED} ghost />
            </div>
          </div>

          <div className="flex flex-col gap-3 rounded-2xl border p-6" style={{ background: CARD, borderColor: BORDER }}>
            <p className="font-mono-tight text-xs font-medium tracking-widest uppercase" style={{ color: WARN }}>
              W-TinyLFU -- window / probation / protected
            </p>
            <div className="grid grid-cols-3 gap-3">
              <Lane label="window" keys={viz.tinyLfu.window} accent={WARN} frequencies={viz.tinyLfu.frequencies} />
              <Lane
                label="main: probation"
                keys={viz.tinyLfu.probation}
                accent="#C08A2E"
                frequencies={viz.tinyLfu.frequencies}
              />
              <Lane
                label="main: protected"
                keys={viz.tinyLfu.protectedKeys}
                accent={TEAL}
                frequencies={viz.tinyLfu.frequencies}
              />
            </div>
            <p className="text-xs" style={{ color: MUTED }}>
              No admission-duel card here: on this count-bounded demo cache the duel is provably unreachable --
              window's excess and main's spare room are always exact complements, so free admission absorbs
              everything before a duel check is ever reached. See <code className="font-mono-tight">TinyLfuPolicy</code>
              's own Javadoc ("An honest finding") for the full argument; the duel only becomes reachable on a
              weighted cache, which this demo does not use.
            </p>
          </div>
        </div>
      )}
    </div>
  )
}
