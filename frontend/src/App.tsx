import { useEffect, useState } from 'react'
import { API_BASE_URL, fetchEndpoints, type EndpointInfo } from './api.ts'
import { ComparePanel } from './components/ComparePanel.tsx'
import { Playground } from './components/Playground.tsx'

type Mode = 'single' | 'compare'

const MODES: { id: Mode; label: string }[] = [
  { id: 'single', label: 'Single endpoint' },
  { id: 'compare', label: 'Compare algorithms' },
]

export default function App() {
  const [endpoints, setEndpoints] = useState<EndpointInfo[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [mode, setMode] = useState<Mode>('single')

  useEffect(() => {
    fetchEndpoints()
      .then(setEndpoints)
      .catch((e: unknown) => setError(e instanceof Error ? e.message : String(e)))
  }, [])

  return (
    <main>
      <header className="page-header">
        <h1>Rate Limiter Playground</h1>
        <p className="muted">
          Fire a burst of requests at a rate-limited endpoint and watch each one get allowed (200) or
          blocked (429). API: <code>{API_BASE_URL}</code>
        </p>
      </header>

      {error && (
        <p className="error" role="alert">
          Could not reach the API ({error}). Start it with <code>docker compose up</code> or{' '}
          <code>./mvnw spring-boot:run</code>.
        </p>
      )}
      {!endpoints && !error && <p className="muted">Loading endpoints…</p>}
      {endpoints && endpoints.length === 0 && <p className="error">The API reports no rate-limited endpoints.</p>}
      {endpoints && endpoints.length > 0 && (
        <>
          <nav className="tabs" role="tablist" aria-label="Mode">
            {MODES.map(({ id, label }) => (
              <button
                key={id}
                type="button"
                role="tab"
                aria-selected={mode === id}
                className={mode === id ? 'tab active' : 'tab'}
                onClick={() => setMode(id)}
              >
                {label}
              </button>
            ))}
          </nav>
          {mode === 'single' ? <Playground endpoints={endpoints} /> : <ComparePanel endpoints={endpoints} />}
        </>
      )}
    </main>
  )
}
