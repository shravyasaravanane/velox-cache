import { useEffect, useState } from 'react'
import { parseCsvAsObjects } from './csv'

export interface CsvData<T> {
  rows: T[]
  loading: boolean
  error: string | null
}

/** Fetches a CSV from a static URL (see scripts/sync-benchmark-data.mjs) and maps each row's
 * raw string fields into a typed row via `mapRow`. */
export function useCsv<T>(url: string, mapRow: (raw: Record<string, string>) => T): CsvData<T> {
  const [rows, setRows] = useState<T[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false
    setLoading(true)
    setError(null)

    fetch(url)
      .then((response) => {
        if (!response.ok) {
          throw new Error(`${url}: HTTP ${response.status}`)
        }
        return response.text()
      })
      .then((text) => {
        if (cancelled) return
        setRows(parseCsvAsObjects(text).map(mapRow))
      })
      .catch((e: unknown) => {
        if (cancelled) return
        setError(e instanceof Error ? e.message : String(e))
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })

    return () => {
      cancelled = true
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [url])

  return { rows, loading, error }
}
