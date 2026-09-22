import { useState } from 'react'
import { BenchmarkExplorerScreen } from './screens/BenchmarkExplorerScreen'
import { ChaosScreen } from './screens/ChaosScreen'
import { LiveOpsScreen } from './screens/LiveOpsScreen'
import { BORDER, MUTED, PAGE_BG, TEAL, TEXT } from './theme'

const SCREENS = [
  { id: 'live-ops', label: 'Live Ops', render: () => <LiveOpsScreen /> },
  { id: 'chaos', label: 'Chaos', render: () => <ChaosScreen /> },
  { id: 'benchmarks', label: 'Benchmarks', render: () => <BenchmarkExplorerScreen /> },
] as const

function App() {
  const [screenId, setScreenId] = useState<(typeof SCREENS)[number]['id']>('live-ops')
  const screen = SCREENS.find((s) => s.id === screenId) ?? SCREENS[0]

  return (
    <div className="min-h-screen w-full px-8 py-8" style={{ background: PAGE_BG }}>
      <header className="mb-8 flex items-center justify-between">
        <div>
          <p className="font-mono-tight text-xs font-medium tracking-widest uppercase" style={{ color: TEAL }}>
            VeloxCache
          </p>
          <h1 className="text-3xl font-bold" style={{ color: TEXT }}>
            {screen.label}
          </h1>
        </div>
        <nav className="flex gap-1 rounded-xl border p-1" style={{ borderColor: BORDER }}>
          {SCREENS.map((s) => (
            <button
              key={s.id}
              onClick={() => setScreenId(s.id)}
              className="font-mono-tight rounded-lg px-4 py-2 text-sm font-medium transition-colors"
              style={{
                background: s.id === screenId ? BORDER : 'transparent',
                color: s.id === screenId ? TEXT : MUTED,
              }}
            >
              {s.label}
            </button>
          ))}
        </nav>
      </header>

      {screen.render()}
    </div>
  )
}

export default App
