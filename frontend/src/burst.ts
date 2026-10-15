// Sends N requests at a fixed rate, independent of React so it can be unit tested.

export interface BurstOptions<R> {
  /** Number of requests to send. */
  count: number
  /** Requests started per second, or null to start them all at once. */
  ratePerSecond: number | null
  /** Sends request number `index` (0-based). Should not throw. */
  send: (index: number) => Promise<R>
  /** Called as each response arrives, in arrival order. */
  onResult?: (result: R, index: number) => void
  /** Aborting stops new requests from being started; requests already sent still complete. */
  signal?: AbortSignal
  /** Injectable for tests. */
  now?: () => number
  sleep?: (ms: number) => Promise<void>
}

const defaultSleep = (ms: number) => new Promise<void>((resolve) => setTimeout(resolve, ms))

/**
 * Starts request i at i / ratePerSecond seconds after the burst begins, without waiting for
 * earlier responses (like k6's constant-arrival-rate), so the request rate stays what was asked
 * for even when responses are slow.
 *
 * @returns results in request order; requests never started because of an abort are left out
 */
export async function runBurst<R>(options: BurstOptions<R>): Promise<R[]> {
  const { count, ratePerSecond, send, onResult, signal } = options
  const now = options.now ?? (() => performance.now())
  const sleep = options.sleep ?? defaultSleep

  if (!Number.isInteger(count) || count < 0) {
    throw new RangeError(`count must be a non-negative integer, got ${count}`)
  }
  if (ratePerSecond !== null && !(ratePerSecond > 0)) {
    throw new RangeError(`ratePerSecond must be positive or null, got ${ratePerSecond}`)
  }

  const intervalMs = ratePerSecond === null ? 0 : 1000 / ratePerSecond
  const start = now()
  const inFlight: Promise<{ index: number; result: R }>[] = []

  for (let index = 0; index < count; index++) {
    const waitMs = start + index * intervalMs - now()
    if (waitMs > 0) {
      await sleep(waitMs)
    }
    if (signal?.aborted) {
      break
    }
    inFlight.push(
      send(index).then((result) => {
        onResult?.(result, index)
        return { index, result }
      }),
    )
  }

  const settled = await Promise.all(inFlight)
  return settled.sort((a, b) => a.index - b.index).map((entry) => entry.result)
}
