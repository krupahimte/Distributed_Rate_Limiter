import { describe, expect, it } from 'vitest'
import { runBurst } from './burst.ts'

/** A fake clock: sleep() advances time instantly and records how long each wait was. */
function fakeClock() {
  let time = 1000
  const sleeps: number[] = []
  return {
    now: () => time,
    sleep: async (ms: number) => {
      sleeps.push(ms)
      time += ms
    },
    sleeps,
  }
}

/** Lets every pending promise callback run (yields to the event loop once). */
const flush = () => new Promise<void>((resolve) => setTimeout(resolve, 0))

/** A request whose response arrives only when resolve(index) is called. */
function controllableSend() {
  const resolvers = new Map<number, (value: string) => void>()
  const started: number[] = []
  return {
    started,
    send: (index: number) =>
      new Promise<string>((resolve) => {
        started.push(index)
        resolvers.set(index, resolve)
      }),
    resolve: (index: number) => resolvers.get(index)!(`response ${index}`),
  }
}

describe('runBurst', () => {
  it('starts every request immediately when no rate is given', async () => {
    const clock = fakeClock()
    const requests = controllableSend()

    const burst = runBurst({ count: 5, ratePerSecond: null, send: requests.send, now: clock.now, sleep: clock.sleep })
    await flush()

    // All five were sent before any response arrived, with no waiting
    expect(requests.started).toEqual([0, 1, 2, 3, 4])
    expect(clock.sleeps).toEqual([])
    ;[4, 2, 0, 3, 1].forEach(requests.resolve)
    await expect(burst).resolves.toHaveLength(5)
  })

  it('spaces request starts evenly at the given rate', async () => {
    const clock = fakeClock()
    const startTimes: number[] = []

    await runBurst({
      count: 4,
      ratePerSecond: 10,
      send: async () => {
        startTimes.push(clock.now())
      },
      now: clock.now,
      sleep: clock.sleep,
    })

    expect(startTimes).toEqual([1000, 1100, 1200, 1300])
  })

  it('does not wait for a response before sending the next request', async () => {
    const clock = fakeClock()
    const requests = controllableSend()

    const burst = runBurst({ count: 3, ratePerSecond: 100, send: requests.send, now: clock.now, sleep: clock.sleep })
    await flush()

    // No response has arrived yet, but all three requests have been sent
    expect(requests.started).toEqual([0, 1, 2])
    ;[0, 1, 2].forEach(requests.resolve)
    await burst
  })

  it('reports results as they arrive and returns them in request order', async () => {
    const clock = fakeClock()
    const requests = controllableSend()
    const arrivals: string[] = []

    const burst = runBurst({
      count: 3,
      ratePerSecond: null,
      send: requests.send,
      onResult: (result, index) => arrivals.push(`${index}:${result}`),
      now: clock.now,
      sleep: clock.sleep,
    })
    await flush()
    requests.resolve(2)
    requests.resolve(0)
    requests.resolve(1)

    await expect(burst).resolves.toEqual(['response 0', 'response 1', 'response 2'])
    expect(arrivals).toEqual(['2:response 2', '0:response 0', '1:response 1'])
  })

  it('stops starting new requests when aborted but lets sent ones finish', async () => {
    const clock = fakeClock()
    const controller = new AbortController()
    const sent: number[] = []

    const results = await runBurst({
      count: 10,
      ratePerSecond: 5,
      send: async (index) => {
        sent.push(index)
        if (index === 2) {
          controller.abort()
        }
        return index
      },
      signal: controller.signal,
      now: clock.now,
      sleep: clock.sleep,
    })

    expect(sent).toEqual([0, 1, 2])
    expect(results).toEqual([0, 1, 2])
  })

  it('handles an empty burst', async () => {
    await expect(runBurst({ count: 0, ratePerSecond: 5, send: async () => 'x' })).resolves.toEqual([])
  })

  it.each([
    [-1, 5],
    [1.5, 5],
    [3, 0],
    [3, -2],
  ])('rejects invalid options (count %s, rate %s)', async (count, ratePerSecond) => {
    await expect(runBurst({ count, ratePerSecond, send: async () => 'x' })).rejects.toThrow(RangeError)
  })
})
