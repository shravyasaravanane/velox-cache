// Copies the Tier-4 benchmark CSVs into public/benchmarks so Vite serves them as static assets,
// with zero new backend endpoint needed just to stream files that already live in the repo.
// Runs automatically before `dev` and `build` (see package.json), so re-running a benchmark and
// regenerating its CSV is the only step needed to keep the dashboard in sync -- nothing here
// needs editing.
import { copyFileSync, existsSync, mkdirSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const source = join(here, '..', '..', 'docs', 'benchmarks', 'data')
const destination = join(here, '..', 'public', 'benchmarks')

const files = ['hit-ratio-matrix.csv', 'thread-scaling.csv', 'thread-scaling-guava-write-heavy.csv']

mkdirSync(destination, { recursive: true })

for (const file of files) {
  const from = join(source, file)
  const to = join(destination, file)
  if (!existsSync(from)) {
    console.warn(`sync-benchmark-data: ${from} not found, skipping`)
    continue
  }
  copyFileSync(from, to)
  console.log(`sync-benchmark-data: copied ${file}`)
}
