import { useEffect, useState } from 'react'
import type { LiveStatsFrame } from './types'

/** 30 seconds of history at the server's 10 Hz tick -- enough for a readable sparkline
 * without letting the array (and the DOM it drives) grow unbounded for a long-lived tab. */
const HISTORY_LENGTH = 300

export interface HistoryPoint {
  t: number
  opsPerSecond: number
  hitRatePercent: number
}

export interface LiveStats {
  frame: LiveStatsFrame | null
  connected: boolean
  history: HistoryPoint[]
}

/**
 * Connects to GET /api/stats/stream and keeps the latest frame plus a rolling window of
 * {@link HistoryPoint}s for the sparkline. EventSource reconnects on its own after a drop --
 * this hook only needs to track connection state, not implement retry itself.
 */
export function useLiveStats(): LiveStats {
  const [frame, setFrame] = useState<LiveStatsFrame | null>(null)
  const [connected, setConnected] = useState(false)
  const [history, setHistory] = useState<HistoryPoint[]>([])

  useEffect(() => {
    const source = new EventSource('/api/stats/stream')

    source.onopen = () => setConnected(true)
    source.onerror = () => setConnected(false)

    source.addEventListener('stats', (event) => {
      const parsed = JSON.parse((event as MessageEvent<string>).data) as LiveStatsFrame
      setFrame(parsed)
      setHistory((prev) => {
        const next = [
          ...prev,
          { t: parsed.timestampMillis, opsPerSecond: parsed.opsPerSecond, hitRatePercent: parsed.hitRatePercent },
        ]
        return next.length > HISTORY_LENGTH ? next.slice(next.length - HISTORY_LENGTH) : next
      })
    })

    return () => source.close()
  }, [])

  return { frame, connected, history }
}
