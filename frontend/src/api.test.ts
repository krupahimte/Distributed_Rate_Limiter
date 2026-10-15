import { describe, expect, it, vi } from 'vitest'
import { fetchEndpoints, outcomeOf, parseRateLimitHeaders, sendRequest } from './api.ts'

const BASE = 'http://api.test'

function fakeFetch(response: Response | Error) {
  return vi.fn<typeof fetch>(async () => {
    if (response instanceof Error) {
      throw response
    }
    return response
  })
}

describe('parseRateLimitHeaders', () => {
  it('reads limit, remaining and Retry-After as numbers', () => {
    const headers = new Headers({ 'X-RateLimit-Limit': '10', 'X-RateLimit-Remaining': '0', 'Retry-After': '3' })

    expect(parseRateLimitHeaders(headers)).toEqual({ limit: 10, remaining: 0, retryAfterSeconds: 3 })
  })

  it('returns null for missing or malformed headers', () => {
    const headers = new Headers({ 'X-RateLimit-Limit': 'abc', 'X-RateLimit-Remaining': ' ' })

    expect(parseRateLimitHeaders(headers)).toEqual({ limit: null, remaining: null, retryAfterSeconds: null })
  })
})

describe('outcomeOf', () => {
  it.each([
    [200, 'allowed'],
    [202, 'allowed'],
    [429, 'blocked'],
    [403, 'error'],
    [500, 'error'],
  ] as const)('HTTP %i is %s', (status, outcome) => {
    expect(outcomeOf(status)).toBe(outcome)
  })
})

describe('fetchEndpoints', () => {
  it('returns the catalog', async () => {
    const catalog = [{ method: 'GET', path: '/api/ping' }]
    const fetchFn = fakeFetch(Response.json(catalog))

    await expect(fetchEndpoints(BASE, fetchFn)).resolves.toEqual(catalog)
    expect(fetchFn).toHaveBeenCalledWith(`${BASE}/api/playground/endpoints`)
  })

  it('throws on an error status', async () => {
    await expect(fetchEndpoints(BASE, fakeFetch(new Response('', { status: 503 })))).rejects.toThrow('HTTP 503')
  })
})

describe('sendRequest', () => {
  const endpoint = { method: 'POST', path: '/api/reports' }
  let clock = 0
  const now = () => (clock += 5)

  it('sends method and X-User-Id, and reports an allowed response with its headers', async () => {
    const fetchFn = fakeFetch(new Response('{}', {
      status: 202,
      headers: { 'X-RateLimit-Limit': '2', 'X-RateLimit-Remaining': '1' },
    }))

    const result = await sendRequest(endpoint, 'alice', BASE, fetchFn, now)

    expect(fetchFn).toHaveBeenCalledWith(`${BASE}/api/reports`, {
      method: 'POST',
      headers: { 'X-User-Id': 'alice' },
    })
    expect(result).toEqual({
      outcome: 'allowed', status: 202, limit: 2, remaining: 1, retryAfterSeconds: null, durationMs: 5,
    })
  })

  it('reports a 429 as blocked with Retry-After', async () => {
    const fetchFn = fakeFetch(new Response('{}', {
      status: 429,
      headers: { 'X-RateLimit-Limit': '2', 'X-RateLimit-Remaining': '0', 'Retry-After': '60' },
    }))

    const result = await sendRequest(endpoint, 'alice', BASE, fetchFn, now)

    expect(result).toMatchObject({ outcome: 'blocked', status: 429, remaining: 0, retryAfterSeconds: 60 })
  })

  it('omits X-User-Id when no user is given', async () => {
    const fetchFn = fakeFetch(new Response('{}'))

    await sendRequest({ method: 'GET', path: '/api/ping' }, '', BASE, fetchFn, now)

    expect(fetchFn).toHaveBeenCalledWith(`${BASE}/api/ping`, { method: 'GET', headers: {} })
  })

  it('turns network and CORS failures into an error result instead of throwing', async () => {
    const result = await sendRequest(endpoint, 'alice', BASE, fakeFetch(new TypeError('Failed to fetch')), now)

    expect(result).toMatchObject({ outcome: 'error', status: null, error: 'Failed to fetch' })
  })
})
