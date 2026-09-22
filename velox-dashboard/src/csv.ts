/**
 * A small, correct CSV parser -- not a naive {@code split(',')}, which silently corrupts this
 * project's own benchmark data: {@code docs/benchmarks/data/hit-ratio-matrix.csv}'s
 * {@code workload_params} column holds values like {@code "hot-set-shift(hot=100,period=10000)"}
 * -- a quoted field with an embedded comma. Handles quoted fields, embedded commas inside them,
 * and doubled quotes ({@code ""}) as an escaped quote, per RFC 4180.
 */
export function parseCsv(text: string): string[][] {
  const rows: string[][] = []
  let row: string[] = []
  let field = ''
  let inQuotes = false

  for (let i = 0; i < text.length; i++) {
    const c = text[i]

    if (inQuotes) {
      if (c === '"') {
        if (text[i + 1] === '"') {
          field += '"'
          i++
        } else {
          inQuotes = false
        }
      } else {
        field += c
      }
      continue
    }

    if (c === '"') {
      inQuotes = true
    } else if (c === ',') {
      row.push(field)
      field = ''
    } else if (c === '\n' || c === '\r') {
      if (c === '\r' && text[i + 1] === '\n') {
        i++
      }
      row.push(field)
      field = ''
      if (row.length > 1 || row[0] !== '') {
        rows.push(row)
      }
      row = []
    } else {
      field += c
    }
  }
  if (field !== '' || row.length > 0) {
    row.push(field)
    rows.push(row)
  }

  return rows
}

/** Parses a header row + data rows into an array of plain objects keyed by header name. */
export function parseCsvAsObjects(text: string): Record<string, string>[] {
  const rows = parseCsv(text)
  if (rows.length === 0) {
    return []
  }
  const [header, ...dataRows] = rows
  return dataRows.map((row) => Object.fromEntries(header.map((key, i) => [key, row[i] ?? ''])))
}
