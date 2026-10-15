import { useEffect, useState } from 'react'
import { API_BASE_URL, fetchEndpoints, type EndpointInfo } from './api.ts'

// Placeholder: lists the rate-limited endpoints to prove the API client and CORS work.
// The playground UI replaces this in the next step.
export default function App() {
  const [endpoints, setEndpoints] = useState<EndpointInfo[] | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    fetchEndpoints()
      .then(setEndpoints)
      .catch((e: unknown) => setError(e instanceof Error ? e.message : String(e)))
  }, [])

  return (
    <main>
      <h1>Rate Limiter Playground</h1>
      <p>API: {API_BASE_URL}</p>
      {error && <p role="alert">Could not reach the API: {error}</p>}
      {!endpoints && !error && <p>Loading endpoints…</p>}
      {endpoints && (
        <ul>
          {endpoints.map((endpoint) => (
            <li key={`${endpoint.method} ${endpoint.path}`}>
              {endpoint.method} {endpoint.path}: {endpoint.algorithm}, {endpoint.capacity} burst
            </li>
          ))}
        </ul>
      )}
    </main>
  )
}
