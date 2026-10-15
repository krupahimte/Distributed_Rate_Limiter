// Pure helpers for labels and counts, kept out of components so they can be unit tested.
import type { Algorithm, EndpointInfo, RequestResult } from './api.ts'

const ALGORITHM_LABELS: Record<Algorithm, string> = {
  TOKEN_BUCKET: 'Token bucket',
  SLIDING_WINDOW_LOG: 'Sliding window log',
  SLIDING_WINDOW_COUNTER: 'Sliding window counter',
}

export function algorithmLabel(algorithm: Algorithm): string {
  return ALGORITHM_LABELS[algorithm]
}

/** "2/s", "5/min", "1/h", or "3 per 10 s" for unusual periods. */
export function formatRate(refillRate: number, refillPeriodMs: number): string {
  const units: [number, string][] = [[1000, 's'], [60_000, 'min'], [3_600_000, 'h']]
  const unit = units.find(([ms]) => ms === refillPeriodMs)
  if (unit) {
    return `${refillRate}/${unit[1]}`
  }
  return `${refillRate} per ${refillPeriodMs / 1000} s`
}

/** e.g. "burst 10, then 2/s · per user · Token bucket" */
export function describeLimit(endpoint: EndpointInfo): string {
  const key = endpoint.key === 'USER' ? 'per user' : 'per IP'
  const rate = formatRate(endpoint.refillRate, endpoint.refillPeriodMs)
  return `burst ${endpoint.capacity}, then ${rate} · ${key} · ${algorithmLabel(endpoint.algorithm)}`
}

export function endpointId(endpoint: Pick<EndpointInfo, 'method' | 'path'>): string {
  return `${endpoint.method} ${endpoint.path}`
}

export interface Summary {
  allowed: number
  blocked: number
  errors: number
  /** Sent but no response yet, or never sent because the burst was stopped. */
  pending: number
}

export function summarize(results: readonly (RequestResult | undefined)[]): Summary {
  const summary: Summary = { allowed: 0, blocked: 0, errors: 0, pending: 0 }
  for (const result of results) {
    if (!result) {
      summary.pending++
    } else if (result.outcome === 'allowed') {
      summary.allowed++
    } else if (result.outcome === 'blocked') {
      summary.blocked++
    } else {
      summary.errors++
    }
  }
  return summary
}

/** A short user id for a fresh rate limit bucket, e.g. "demo-4f9k". */
export function randomUserId(random: () => number = Math.random): string {
  return `demo-${Math.floor(random() * 36 ** 4).toString(36).padStart(4, '0')}`
}
