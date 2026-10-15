import { describe, expect, it } from 'vitest'
import type { EndpointInfo, RequestResult } from './api.ts'
import {
  describeLimit, describeRule, endpointId, expectedBehaviour, formatRate, pickComparisonEndpoints, randomUserId, summarize,
} from './format.ts'

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

  it('describes the rule without the algorithm', () => {
    expect(describeRule(endpoint)).toBe('burst 10, then 2/s · per user')
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

describe('pickComparisonEndpoints', () => {
  const tokenBucket = endpoint
  const slidingLog: EndpointInfo = { ...endpoint, path: '/api/playground/sliding-window-log', algorithm: 'SLIDING_WINDOW_LOG' }
  const slidingCounter: EndpointInfo = { ...endpoint, path: '/api/playground/sliding-window-counter', algorithm: 'SLIDING_WINDOW_COUNTER' }
  // A demo endpoint that follows the server default must not be picked
  const followsDefault: EndpointInfo = { ...endpoint, path: '/api/ping', algorithmOverridden: false }

  it('picks one dedicated endpoint per algorithm', () => {
    expect(pickComparisonEndpoints([followsDefault, slidingCounter, slidingLog, tokenBucket])).toEqual({
      endpoints: { TOKEN_BUCKET: tokenBucket, SLIDING_WINDOW_LOG: slidingLog, SLIDING_WINDOW_COUNTER: slidingCounter },
    })
  })

  it('explains when an algorithm has no dedicated endpoint', () => {
    expect(pickComparisonEndpoints([followsDefault, tokenBucket, slidingLog]))
      .toEqual({ problem: expect.stringContaining('sliding window counter') })
  })

  it('refuses to compare endpoints with different limits', () => {
    expect(pickComparisonEndpoints([tokenBucket, slidingLog, { ...slidingCounter, capacity: 20 }]))
      .toEqual({ problem: expect.stringContaining('different limits') })
  })
})

describe('expectedBehaviour', () => {
  it('describes each algorithm using the real limit (burst 10, 2/s -> 5 s window)', () => {
    expect(expectedBehaviour(endpoint)).toContain('refills continuously at 2/s')
    expect(expectedBehaviour({ ...endpoint, algorithm: 'SLIDING_WINDOW_LOG' })).toContain('Exactly 10 per rolling 5 s')
    expect(expectedBehaviour({ ...endpoint, algorithm: 'SLIDING_WINDOW_COUNTER' })).toContain('10 per rolling 5 s')
  })
})

describe('randomUserId', () => {
  it('produces a short, header-safe id', () => {
    expect(randomUserId(() => 0)).toBe('demo-0000')
    expect(randomUserId(() => 0.999999)).toMatch(/^demo-[0-9a-z]{4}$/)
  })
})
