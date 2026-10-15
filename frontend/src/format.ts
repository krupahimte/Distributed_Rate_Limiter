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

/** e.g. "burst 10, then 2/s · per user" */
export function describeRule(endpoint: EndpointInfo): string {
  const key = endpoint.key === 'USER' ? 'per user' : 'per IP'
  return `burst ${endpoint.capacity}, then ${formatRate(endpoint.refillRate, endpoint.refillPeriodMs)} · ${key}`
}

/** e.g. "burst 10, then 2/s · per user · Token bucket" */
export function describeLimit(endpoint: EndpointInfo): string {
  return `${describeRule(endpoint)} · ${algorithmLabel(endpoint.algorithm)}`
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

export const ALGORITHMS: readonly Algorithm[] = ['TOKEN_BUCKET', 'SLIDING_WINDOW_LOG', 'SLIDING_WINDOW_COUNTER']

/**
 * The endpoints to compare: one per algorithm, each choosing its own algorithm, all sharing the
 * same limit. Returns an explanation instead if the API does not offer such a set.
 */
export function pickComparisonEndpoints(
  endpoints: readonly EndpointInfo[],
): { endpoints: Record<Algorithm, EndpointInfo> } | { problem: string } {
  const picked: Partial<Record<Algorithm, EndpointInfo>> = {}
  for (const algorithm of ALGORITHMS) {
    const match = endpoints.find((endpoint) => endpoint.algorithmOverridden && endpoint.algorithm === algorithm)
    if (!match) {
      return { problem: `The API has no endpoint dedicated to the ${algorithmLabel(algorithm).toLowerCase()} algorithm.` }
    }
    picked[algorithm] = match
  }
  const all = Object.values(picked)
  const first = all[0]
  const sameLimit = all.every((endpoint) =>
    endpoint.capacity === first.capacity
    && endpoint.refillRate === first.refillRate
    && endpoint.refillPeriodMs === first.refillPeriodMs
    && endpoint.key === first.key)
  if (!sameLimit) {
    return { problem: 'The algorithm endpoints have different limits, so comparing them would not be fair.' }
  }
  return { endpoints: picked as Record<Algorithm, EndpointInfo> }
}

/** What to expect from each algorithm for this limit, in one sentence. */
export function expectedBehaviour(endpoint: EndpointInfo): string {
  const windowSeconds = (endpoint.capacity / endpoint.refillRate) * (endpoint.refillPeriodMs / 1000)
  const rate = formatRate(endpoint.refillRate, endpoint.refillPeriodMs)
  switch (endpoint.algorithm) {
    case 'TOKEN_BUCKET':
      return `Allows a burst of ${endpoint.capacity}, then refills continuously at ${rate}: a steady trickle gets through.`
    case 'SLIDING_WINDOW_LOG':
      return `Exactly ${endpoint.capacity} per rolling ${windowSeconds} s: nothing more until the oldest request leaves the window.`
    case 'SLIDING_WINDOW_COUNTER':
      return `Approximates ${endpoint.capacity} per rolling ${windowSeconds} s from two fixed-window counts: cheap, slightly stricter after bursts.`
  }
}

/** A short user id for a fresh rate limit bucket, e.g. "demo-4f9k". */
export function randomUserId(random: () => number = Math.random): string {
  return `demo-${Math.floor(random() * 36 ** 4).toString(36).padStart(4, '0')}`
}
