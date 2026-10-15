import { summarize } from '../format.ts'
import type { BurstState } from '../useBurst.ts'
import { RequestGrid } from './RequestGrid.tsx'

interface Props {
  state: BurstState
}

function formatValue(value: number | null | undefined, suffix = ''): string {
  return value === null || value === undefined ? '—' : `${value}${suffix}`
}

/** Counters, the latest rate limit header values, and the request grid for one burst. */
export function ResultsPanel({ state }: Props) {
  const summary = summarize(state.results)
  const finished = !state.running && state.results.length > 0
  const elapsedMs = state.startedAt !== undefined
    ? (state.finishedAt ?? performance.now()) - state.startedAt
    : undefined
  const errorMessage = state.results.find((result) => result?.outcome === 'error')?.error

  if (state.results.length === 0) {
    return <p className="muted">No burst sent yet.</p>
  }

  return (
    <div className="results">
      <dl className="stats">
        <div className="stat stat-allowed">
          <dt>Allowed</dt>
          <dd>{summary.allowed}</dd>
        </div>
        <div className="stat stat-blocked">
          <dt>Blocked (429)</dt>
          <dd>{summary.blocked}</dd>
        </div>
        <div className="stat">
          <dt>X-RateLimit-Remaining</dt>
          <dd>{formatValue(state.latest?.remaining)}</dd>
        </div>
        <div className="stat">
          <dt>Retry-After</dt>
          <dd>{formatValue(state.latestBlocked?.retryAfterSeconds, ' s')}</dd>
        </div>
      </dl>

      <RequestGrid results={state.results} finished={finished} />

      <p className="muted small">
        {state.running ? 'Sending… ' : finished ? 'Done. ' : ''}
        {summary.pending > 0 && `${summary.pending} ${finished ? 'not sent' : 'waiting'} · `}
        {summary.errors > 0 && `${summary.errors} failed · `}
        {elapsedMs !== undefined && !state.running && `${(elapsedMs / 1000).toFixed(1)} s · `}
        Limit {formatValue(state.latest?.limit)} · Remaining is from the latest response, Retry-After from the latest 429
      </p>
      {errorMessage && (
        <p className="error" role="alert">
          Some requests failed ({errorMessage}). Is the API running, and is this page's origin allowed by CORS?
        </p>
      )}
    </div>
  )
}
