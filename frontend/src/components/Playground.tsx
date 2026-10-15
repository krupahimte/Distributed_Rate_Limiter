import { useId, useState, type FormEvent } from 'react'
import type { EndpointInfo } from '../api.ts'
import { describeLimit, endpointId, randomUserId } from '../format.ts'
import { useBurst } from '../useBurst.ts'
import { ResultsPanel } from './ResultsPanel.tsx'

export const SPEEDS: { label: string; value: number | null }[] = [
  { label: 'All at once', value: null },
  { label: '2 / second', value: 2 },
  { label: '5 / second', value: 5 },
  { label: '10 / second', value: 10 },
  { label: '20 / second', value: 20 },
  { label: '50 / second', value: 50 },
]

export const MAX_REQUESTS = 500

interface Props {
  endpoints: EndpointInfo[]
}

/** Send a burst to one endpoint and watch each request get allowed or blocked. */
export function Playground({ endpoints }: Props) {
  const id = useId()
  const [selectedId, setSelectedId] = useState(() =>
    endpointId(endpoints.find((candidate) => candidate.path.endsWith('/token-bucket')) ?? endpoints[0]))
  const [userId, setUserId] = useState(() => randomUserId())
  const [count, setCount] = useState(50)
  const [speedIndex, setSpeedIndex] = useState(3)
  const { state, start, stop, reset } = useBurst()

  const endpoint = endpoints.find((candidate) => endpointId(candidate) === selectedId) ?? endpoints[0]

  function onSubmit(event: FormEvent) {
    event.preventDefault()
    void start({ endpoint, userId, count, ratePerSecond: SPEEDS[speedIndex].value })
  }

  return (
    <section className="card">
      <form className="controls" onSubmit={onSubmit}>
        <label htmlFor={`${id}-endpoint`}>Endpoint</label>
        <div>
          <select id={`${id}-endpoint`} value={selectedId} onChange={(e) => setSelectedId(e.target.value)}>
            {endpoints.map((candidate) => (
              <option key={endpointId(candidate)} value={endpointId(candidate)}>
                {endpointId(candidate)}
              </option>
            ))}
          </select>
          <p className="hint">{describeLimit(endpoint)}</p>
        </div>

        <label htmlFor={`${id}-user`}>User ID</label>
        <div className="row">
          <input
            id={`${id}-user`}
            value={userId}
            onChange={(e) => setUserId(e.target.value)}
            disabled={endpoint.key === 'IP'}
            maxLength={64}
            pattern="[A-Za-z0-9._@\-]*"
            title="Letters, digits and . _ @ - (up to 64)"
          />
          <button type="button" className="secondary" onClick={() => setUserId(randomUserId())}
                  disabled={endpoint.key === 'IP'}>
            New user
          </button>
        </div>
        {endpoint.key === 'IP' && <p className="hint span">This endpoint is limited per IP, so the user ID is not used.</p>}

        <label htmlFor={`${id}-count`}>Requests</label>
        <input
          id={`${id}-count`}
          type="number"
          min={1}
          max={MAX_REQUESTS}
          value={count}
          onChange={(e) => setCount(Math.min(MAX_REQUESTS, Math.max(1, Number(e.target.value) || 1)))}
        />

        <label htmlFor={`${id}-speed`}>Speed</label>
        <select id={`${id}-speed`} value={speedIndex} onChange={(e) => setSpeedIndex(Number(e.target.value))}>
          {SPEEDS.map((speed, index) => (
            <option key={speed.label} value={index}>{speed.label}</option>
          ))}
        </select>

        <div className="actions span">
          <button type="submit" disabled={state.running}>Send burst</button>
          <button type="button" className="secondary" onClick={stop} disabled={!state.running}>Stop</button>
          <button type="button" className="secondary" onClick={reset} disabled={state.running || state.results.length === 0}>
            Clear
          </button>
        </div>
      </form>

      <ResultsPanel state={state} />
    </section>
  )
}
