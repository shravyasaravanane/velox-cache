import { useState } from 'react'
import { motion } from 'framer-motion'
import { BLUE, BORDER, CARD, CHART_BG, MUTED, TEAL, TEXT, WARN, DANGER } from '../theme'
import { useClusterView } from '../useClusterView'
import type { ClusterNodeView } from '../types'

async function killNode(node: ClusterNodeView): Promise<void> {
  const query = new URLSearchParams({ host: node.host, port: String(node.port) }).toString()
  const response = await fetch(`/api/cluster/kill?${query}`, { method: 'POST' })
  if (!response.ok) {
    throw new Error(`kill ${node.id} failed: HTTP ${response.status}`)
  }
}

async function demoPut(key: string, value: string): Promise<void> {
  const query = new URLSearchParams({ key, value }).toString()
  const response = await fetch(`/api/cluster/demo-put?${query}`, { method: 'POST' })
  if (!response.ok) {
    throw new Error(`write failed: HTTP ${response.status}`)
  }
}

async function demoGet(key: string): Promise<{ status: number; body: string }> {
  const response = await fetch(`/api/cluster/demo-get?${new URLSearchParams({ key }).toString()}`)
  return { status: response.status, body: await response.text() }
}

function NodeCard({ node, onKill }: { node: ClusterNodeView; onKill: (node: ClusterNodeView) => void }) {
  const [busy, setBusy] = useState(false)

  return (
    <motion.div
      layout
      className="flex flex-col gap-3 rounded-2xl border p-5"
      style={{ background: CARD, borderColor: node.healthy ? BORDER : DANGER }}
    >
      <div className="flex items-center justify-between">
        <span className="font-mono-tight text-sm font-semibold" style={{ color: TEXT }}>
          {node.id}
        </span>
        <span
          className="font-mono-tight rounded-full px-2 py-0.5 text-[10px] font-medium uppercase tracking-widest"
          style={{ background: node.healthy ? '#0EA5A322' : '#DC444422', color: node.healthy ? TEAL : DANGER }}
        >
          {node.healthy ? 'healthy' : 'down'}
        </span>
      </div>
      <p className="font-mono-tight text-xs" style={{ color: MUTED }}>
        {node.host}:{node.port}
      </p>
      <button
        onClick={async () => {
          setBusy(true)
          try {
            await onKill(node)
          } finally {
            setBusy(false)
          }
        }}
        disabled={!node.healthy || busy}
        className="font-mono-tight self-start rounded-lg px-3 py-1.5 text-xs font-semibold disabled:opacity-40"
        style={{ background: DANGER, color: '#0B0E15' }}
      >
        {busy ? 'killing…' : 'kill'}
      </button>
    </motion.div>
  )
}

export function ClusterScreen() {
  const view = useClusterView()
  const [demoKey, setDemoKey] = useState('demo-key')
  const [demoValue, setDemoValue] = useState('hello cluster')
  const [readResult, setReadResult] = useState<string | null>(null)
  const [demoError, setDemoError] = useState<string | null>(null)

  async function handleKill(node: ClusterNodeView) {
    try {
      await killNode(node)
    } catch (e) {
      setDemoError(e instanceof Error ? e.message : String(e))
    }
  }

  async function handleWrite() {
    setDemoError(null)
    try {
      await demoPut(demoKey, demoValue)
      setReadResult(null)
    } catch (e) {
      setDemoError(e instanceof Error ? e.message : String(e))
    }
  }

  async function handleRead() {
    setDemoError(null)
    try {
      const { status, body } = await demoGet(demoKey)
      setReadResult(status === 200 ? body : `HTTP ${status}`)
    } catch (e) {
      setDemoError(e instanceof Error ? e.message : String(e))
    }
  }

  return (
    <div className="flex flex-col gap-6">
      <div className="flex items-center justify-between">
        <p className="text-sm" style={{ color: MUTED }}>
          <span style={{ color: TEXT }}>velox-cluster</span> is a separate process family, not started by this demo
          -- launch nodes with <code className="font-mono-tight">java -cp ... com.velox.cluster.ClusterNode &lt;id&gt;
          &lt;host&gt; &lt;port&gt;</code>, join them via <code className="font-mono-tight">POST /cluster/join</code>,
          then point <code className="font-mono-tight">velox.demo.cluster-seed</code> at any one of them.
        </p>
        <div className="flex items-center gap-2">
          <span className="h-2.5 w-2.5 rounded-full" style={{ background: view.reachable ? TEAL : WARN }} />
          <span className="font-mono-tight text-sm" style={{ color: MUTED }}>
            {view.reachable ? `${view.nodes.length} node(s)` : 'cluster unreachable'}
          </span>
        </div>
      </div>

      {!view.reachable ? (
        <div className="rounded-2xl border p-6" style={{ background: CARD, borderColor: BORDER }}>
          <p className="font-mono-tight text-sm" style={{ color: MUTED }}>
            No cluster seed answering at the configured address. Start at least one{' '}
            <code className="font-mono-tight">ClusterNode</code> and join it to itself/peers, then this screen
            picks it up automatically on the next poll (every 1.5s).
          </p>
        </div>
      ) : (
        <div className="grid grid-cols-3 gap-4">
          {view.nodes.map((node) => (
            <NodeCard key={node.id} node={node} onKill={handleKill} />
          ))}
        </div>
      )}

      <div className="flex flex-col gap-4 rounded-2xl border p-6" style={{ background: CARD, borderColor: BORDER }}>
        <p className="font-mono-tight text-xs font-medium tracking-widest uppercase" style={{ color: BLUE }}>
          Prove it survives -- write, kill a node, read again
        </p>
        <div className="flex gap-3">
          <input
            value={demoKey}
            onChange={(e) => setDemoKey(e.target.value)}
            placeholder="key"
            className="font-mono-tight rounded-lg border px-3 py-1.5 text-sm outline-none"
            style={{ background: CHART_BG, borderColor: BORDER, color: TEXT }}
          />
          <input
            value={demoValue}
            onChange={(e) => setDemoValue(e.target.value)}
            placeholder="value"
            className="font-mono-tight flex-1 rounded-lg border px-3 py-1.5 text-sm outline-none"
            style={{ background: CHART_BG, borderColor: BORDER, color: TEXT }}
          />
          <button
            onClick={handleWrite}
            className="font-mono-tight rounded-lg px-4 py-2 text-sm font-semibold"
            style={{ background: BLUE, color: '#0B0E15' }}
          >
            write
          </button>
          <button
            onClick={handleRead}
            className="font-mono-tight rounded-lg px-4 py-2 text-sm font-semibold"
            style={{ background: TEAL, color: '#0B0E15' }}
          >
            read
          </button>
        </div>
        {readResult !== null && (
          <p className="font-mono-tight text-sm" style={{ color: TEXT }}>
            read back: <span style={{ color: TEAL }}>{readResult}</span>
          </p>
        )}
        {demoError && (
          <p className="font-mono-tight text-sm" style={{ color: DANGER }}>
            {demoError}
          </p>
        )}
      </div>
    </div>
  )
}
