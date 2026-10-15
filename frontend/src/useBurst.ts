import { useCallback, useEffect, useRef, useState } from 'react'
import { sendRequest, type EndpointInfo, type RequestResult } from './api.ts'
import { runBurst } from './burst.ts'

export interface BurstRequest {
  endpoint: Pick<EndpointInfo, 'method' | 'path'>
  userId: string
  count: number
  /** Requests per second, or null for all at once. */
  ratePerSecond: number | null
}

export interface BurstState {
  /** One slot per request, filled in as responses arrive (undefined = no response yet). */
  results: (RequestResult | undefined)[]
  running: boolean
  /** The most recent response to arrive, for the "current" header values. */
  latest?: RequestResult
  /** The most recent 429, for Retry-After. */
  latestBlocked?: RequestResult
  startedAt?: number
  finishedAt?: number
}

const IDLE: BurstState = { results: [], running: false }

/**
 * Runs a burst and exposes its live state. Starting a new burst cancels the previous one, and
 * responses from a cancelled burst are ignored.
 */
export function useBurst() {
  const [state, setState] = useState<BurstState>(IDLE)
  const controllerRef = useRef<AbortController | null>(null)

  // Stop sending if the component goes away mid-burst
  useEffect(() => () => controllerRef.current?.abort(), [])

  const start = useCallback(async ({ endpoint, userId, count, ratePerSecond }: BurstRequest) => {
    controllerRef.current?.abort()
    const controller = new AbortController()
    controllerRef.current = controller
    const isCurrent = () => controllerRef.current === controller

    setState({ results: Array.from({ length: count }, () => undefined), running: true, startedAt: performance.now() })

    await runBurst({
      count,
      ratePerSecond,
      signal: controller.signal,
      send: () => sendRequest(endpoint, userId),
      onResult: (result, index) => {
        if (!isCurrent()) {
          return
        }
        setState((previous) => {
          const results = previous.results.slice()
          results[index] = result
          return {
            ...previous,
            results,
            latest: result,
            latestBlocked: result.outcome === 'blocked' ? result : previous.latestBlocked,
          }
        })
      },
    })

    if (isCurrent()) {
      setState((previous) => ({ ...previous, running: false, finishedAt: performance.now() }))
    }
  }, [])

  /** Stops sending; requests already in flight still report back. */
  const stop = useCallback(() => controllerRef.current?.abort(), [])

  const reset = useCallback(() => {
    controllerRef.current?.abort()
    controllerRef.current = null
    setState(IDLE)
  }, [])

  return { state, start, stop, reset }
}
