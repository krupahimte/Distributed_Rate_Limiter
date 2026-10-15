import { describe, expect, it } from 'vitest'
import type { EndpointInfo, RequestResult } from './api.ts'
import { describeLimit, endpointId, formatRate, randomUserId, summarize } from './format.ts'

const endpoint: EndpointInfo = {
  method: 'GET',
  path: '/api/playground/token-bucket',
  capacity: 10,
  refillRate: 2,
  refillPeriodMs: 1000,
  key: 'USER',
  algorithm: 'TOKEN_BUCKET',
  algorithmOverridden: true,
}

function result(outcome: RequestResult['outcome']): RequestResult {
  return { outcome, status: null, limit: null, remaining: null, retryAfterSeconds: null, durationMs: 1 }
}

describe('formatRate', () => {
  it.each([
    [2, 1000, '2/s'],
    [5, 60_000, '5/min'],
    [1, 3_600_000, '1/h'],
    [3, 10_000, '3 per 10 s'],
  ])('%i per %i ms is "%s"', (rate, period, expected) => {
    expect(formatRate(rate, period)).toBe(expected)
  })
})

describe('describeLimit', () => {
  it('describes burst, rate, key and algorithm', () => {
    expect(describeLimit(endpoint)).toBe('burst 10, then 2/s · per user · Token bucket')
  })

  it('describes per-IP limits', () => {
    expect(describeLimit({ ...endpoint, key: 'IP', algorithm: 'SLIDING_WINDOW_COUNTER', refillPeriodMs: 60_000 }))
      .toBe('burst 10, then 2/min · per IP · Sliding window counter')
  })
})

describe('endpointId', () => {
  it('combines method and path', () => {
    expect(endpointId(endpoint)).toBe('GET /api/playground/token-bucket')
  })
})

describe('summarize', () => {
  it('counts each outcome and pending slots', () => {
    const results = [result('allowed'), result('blocked'), undefined, result('blocked'), result('error'), result('allowed')]

    expect(summarize(results)).toEqual({ allowed: 2, blocked: 2, errors: 1, pending: 1 })
  })
})

describe('randomUserId', () => {
  it('produces a short, header-safe id', () => {
    expect(randomUserId(() => 0)).toBe('demo-0000')
    expect(randomUserId(() => 0.999999)).toMatch(/^demo-[0-9a-z]{4}$/)
  })
})
