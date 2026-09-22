import { useState } from 'react'
import type { ReactNode } from 'react'
import { BORDER, BLUE, CARD, DANGER, MUTED, TEAL, TEXT, WARN } from '../theme'
import type { ChaosReport } from '../types'

async function postChaos(path: string, params: Record<string, string | number>): Promise<ChaosReport> {
  const query = new URLSearchParams(Object.entries(params).map(([k, v]) => [k, String(v)])).toString()
  const response = await fetch(`/api/chaos/${path}${query ? `?${query}` : ''}`, { method: 'POST' })
  if (!response.ok) {
    throw new Error(`${path} failed: HTTP ${response.status}`)
  }
  return (await response.json()) as ChaosReport
}

function NumberField({
  label,
  value,
  onChange,
}: {
  label: string
  value: number
  onChange: (value: number) => void
}) {
  return (
    <label className="flex flex-col gap-1 text-sm" style={{ color: MUTED }}>
      {label}
      <input
        type="number"
        value={value}
        onChange={(e) => onChange(Number(e.target.value))}
        className="font-mono-tight rounded-lg border px-3 py-1.5 text-sm outline-none"
        style={{ background: '#0F1320', borderColor: BORDER, color: TEXT }}
      />
    </label>
  )
}

function ReportView({ report }: { report: ChaosReport }) {
  const cells: [string, number | string, string][] = [
    ['hits', report.hits, TEAL],
    ['misses', report.misses, WARN],
    ['loads', report.loads, BLUE],
    ['coalesced', report.coalesced, TEAL],
    ['evictions', report.evictions, MUTED],
    ['hit rate', `${report.hitRatePercent.toFixed(1)}%`, TEAL],
  ]
  return (
    <div className="grid grid-cols-3 gap-3 rounded-xl border p-3" style={{ background: '#0F1320', borderColor: BORDER }}>
      {cells.map(([label, value, color]) => (
        <div key={label} className="flex flex-col">
          <span className="font-mono-tight text-[10px] tracking-widest uppercase" style={{ color: MUTED }}>
            {label}
          </span>
          <span className="font-mono-tight text-lg font-semibold" style={{ color }}>
            {value}
          </span>
        </div>
      ))}
    </div>
  )
}

function ChaosCard({
  title,
  description,
  accent,
  fire,
  children,
}: {
  title: string
  description: string
  accent: string
  fire: () => Promise<ChaosReport>
  children?: ReactNode
}) {
  const [report, setReport] = useState<ChaosReport | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  async function handleFire() {
    setBusy(true)
    setError(null)
    try {
      setReport(await fire())
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="flex flex-1 flex-col gap-4 rounded-2xl border p-6" style={{ background: CARD, borderColor: BORDER }}>
      <div>
        <p className="font-mono-tight text-xs font-medium tracking-widest uppercase" style={{ color: accent }}>
          {title}
        </p>
        <p className="mt-1 text-sm" style={{ color: MUTED }}>
          {description}
        </p>
      </div>
      {children && <div className="flex gap-3">{children}</div>}
      <button
        onClick={handleFire}
        disabled={busy}
        className="self-start rounded-lg px-4 py-2 text-sm font-semibold disabled:opacity-50"
        style={{ background: accent, color: '#0B0E15' }}
      >
        {busy ? 'firing…' : 'fire'}
      </button>
      {error && (
        <p className="font-mono-tight text-sm" style={{ color: DANGER }}>
          {error}
        </p>
      )}
      {report && <ReportView report={report} />}
    </div>
  )
}

export function ChaosScreen() {
  const [stampedeKey, setStampedeKey] = useState(10)
  const [stampedeN, setStampedeN] = useState(500)
  const [scanStart, setScanStart] = useState(1)
  const [scanN, setScanN] = useState(50000)
  const [penetrateN, setPenetrateN] = useState(5000)

  return (
    <div className="flex flex-col gap-6">
      <p className="text-sm" style={{ color: MUTED }}>
        Deliberately hostile traffic patterns, fired at the real read path. Switch to{' '}
        <span style={{ color: TEXT }}>Live Ops</span> in another tab (or just watch this one) to see the effect on
        the primary cache in real time -- every card here shows its own before/after delta too.
      </p>

      <div className="flex gap-6">
        <ChaosCard
          title="Stampede"
          description="Invalidate one key, then fire N concurrent reads at it at once. Single-flight loading should collapse it into exactly 1 database load, however large N is."
          accent={WARN}
          fire={() => postChaos('stampede', { key: stampedeKey, n: stampedeN })}
        >
          <NumberField label="key" value={stampedeKey} onChange={setStampedeKey} />
          <NumberField label="n" value={stampedeN} onChange={setStampedeN} />
        </ChaosCard>

        <ChaosCard
          title="Scan"
          description="Read N sequential ids once each -- the one-pass scan that defeats plain LRU by evicting the whole working set."
          accent={BLUE}
          fire={() => postChaos('scan', { startId: scanStart, n: scanN })}
        >
          <NumberField label="start id" value={scanStart} onChange={setScanStart} />
          <NumberField label="n" value={scanN} onChange={setScanN} />
        </ChaosCard>
      </div>

      <div className="flex gap-6">
        <ChaosCard
          title="Expire all"
          description="Drops every entry from the primary cache at once -- a cache-avalanche simulation. Watch Live Ops' hit rate crater and climb back."
          accent={DANGER}
          fire={() => postChaos('expire-all', {})}
        />

        <ChaosCard
          title="Penetrate"
          description="Requests N ids guaranteed not to exist. Nothing ever gets cached for these, so every single request should reach the database -- misses should equal N exactly."
          accent={MUTED}
          fire={() => postChaos('penetrate', { n: penetrateN })}
        >
          <NumberField label="n" value={penetrateN} onChange={setPenetrateN} />
        </ChaosCard>
      </div>
    </div>
  )
}
