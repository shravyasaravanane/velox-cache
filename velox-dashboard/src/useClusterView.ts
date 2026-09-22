import { useEffect, useState } from 'react'
import type { ClusterView } from './types'

const POLL_INTERVAL_MS = 1500

/** Polls GET /api/cluster/nodes -- unlike the primary cache's Live Ops data, cluster membership
 * is orthogonal, optional infrastructure (a separate process family that may not even be
 * running), so it gets its own simple poll rather than a place in the 10 Hz SSE frame. */
export function useClusterView(): ClusterView {
  const [view, setView] = useState<ClusterView>({ reachable: false, nodes: [] })

  useEffect(() => {
    let cancelled = false

    async function poll() {
      try {
        const response = await fetch('/api/cluster/nodes')
        const data = (await response.json()) as ClusterView
        if (!cancelled) setView(data)
      } catch {
        if (!cancelled) setView({ reachable: false, nodes: [] })
      }
    }

    poll()
    const interval = setInterval(poll, POLL_INTERVAL_MS)
    return () => {
      cancelled = true
      clearInterval(interval)
    }
  }, [])

  return view
}
