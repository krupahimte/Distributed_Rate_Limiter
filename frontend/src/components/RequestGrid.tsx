import type { RequestResult } from '../api.ts'

interface Props {
  results: readonly (RequestResult | undefined)[]
  /** When the burst is over, empty slots were never sent (stopped) rather than pending. */
  finished: boolean
}

function describe(index: number, result: RequestResult | undefined, finished: boolean): string {
  if (!result) {
    return `#${index + 1}: ${finished ? 'not sent' : 'waiting'}`
  }
  const parts = [`#${index + 1}: ${result.status ?? 'network error'}`]
  if (result.remaining !== null) parts.push(`remaining ${result.remaining}`)
  if (result.retryAfterSeconds !== null) parts.push(`retry after ${result.retryAfterSeconds}s`)
  if (result.error) parts.push(result.error)
  parts.push(`${Math.round(result.durationMs)} ms`)
  return parts.join(' · ')
}

/** One cell per request, in the order they were sent; colours fill in as responses arrive. */
export function RequestGrid({ results, finished }: Props) {
  return (
    <ol className="request-grid" aria-label="Requests in send order">
      {results.map((result, index) => {
        const state = result ? result.outcome : finished ? 'skipped' : 'pending'
        const label = describe(index, result, finished)
        return <li key={index} className={`cell cell-${state}`} title={label} aria-label={label} />
      })}
    </ol>
  )
}
