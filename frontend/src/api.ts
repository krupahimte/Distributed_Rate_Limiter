// Typed client for the rate limiter API.

export const API_BASE_URL: string = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080'

export type Algorithm = 'TOKEN_BUCKET' | 'SLIDING_WINDOW_LOG' | 'SLIDING_WINDOW_COUNTER'
export type KeyType = 'USER' | 'IP'

/** One rate-limited endpoint, as reported by GET /api/playground/endpoints. */
export interface EndpointInfo {
  method: string
  path: string
  capacity: number
  refillRate: number
  refillPeriodMs: number
  key: KeyType
  algorithm: Algorithm
  /** True if the endpoint sets its own algorithm instead of following the server default. */
  algorithmOverridden: boolean
}

export type Outcome = 'allowed' | 'blocked' | 'error'

/** What one request produced, including the rate limit headers (null when absent). */
export interface RequestResult {
  outcome: Outcome
  /** HTTP status, or null if the request never got a response (network or CORS error). */
  status: number | null
  limit: number | null
  remaining: number | null
  retryAfterSeconds: number | null
  durationMs: number
  error?: string
}

export interface RateLimitHeaders {
  limit: number | null
  remaining: number | null
  retryAfterSeconds: number | null
}

/**
 * Reads the rate limit headers. The browser only lets JavaScript see them because the API lists
 * them in Access-Control-Expose-Headers.
 */
export function parseRateLimitHeaders(headers: Headers): RateLimitHeaders {
  return {
    limit: toNumber(headers.get('X-RateLimit-Limit')),
    remaining: toNumber(headers.get('X-RateLimit-Remaining')),
    retryAfterSeconds: toNumber(headers.get('Retry-After')),
  }
}

function toNumber(value: string | null): number | null {
  if (value === null || value.trim() === '') {
    return null
  }
  const parsed = Number(value)
  return Number.isFinite(parsed) ? parsed : null
}

export function outcomeOf(status: number): Outcome {
  if (status === 429) {
    return 'blocked'
  }
  return status >= 200 && status < 300 ? 'allowed' : 'error'
}

export async function fetchEndpoints(
  baseUrl: string = API_BASE_URL,
  fetchFn: typeof fetch = fetch,
): Promise<EndpointInfo[]> {
  const response = await fetchFn(`${baseUrl}/api/playground/endpoints`)
  if (!response.ok) {
    throw new Error(`Could not load endpoints: HTTP ${response.status}`)
  }
  return (await response.json()) as EndpointInfo[]
}

/** Sends one request to a rate-limited endpoint. Never throws: failures become outcome 'error'. */
export async function sendRequest(
  endpoint: Pick<EndpointInfo, 'method' | 'path'>,
  userId: string,
  baseUrl: string = API_BASE_URL,
  fetchFn: typeof fetch = fetch,
  now: () => number = () => performance.now(),
): Promise<RequestResult> {
  const started = now()
  try {
    const response = await fetchFn(`${baseUrl}${endpoint.path}`, {
      method: endpoint.method,
      headers: userId ? { 'X-User-Id': userId } : {},
    })
    // The body is not needed; reading it frees the connection
    await response.text()
    return {
      outcome: outcomeOf(response.status),
      status: response.status,
      ...parseRateLimitHeaders(response.headers),
      durationMs: now() - started,
    }
  } catch (error) {
    return {
      outcome: 'error',
      status: null,
      limit: null,
      remaining: null,
      retryAfterSeconds: null,
      durationMs: now() - started,
      error: error instanceof Error ? error.message : String(error),
    }
  }
}
