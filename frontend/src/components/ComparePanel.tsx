import { useState, type FormEvent } from 'react'
import type { EndpointInfo } from '../api.ts'
import { ALGORITHMS, algorithmLabel, describeRule, expectedBehaviour, pickComparisonEndpoints, randomUserId } from '../format.ts'
import { useBurst } from '../useBurst.ts'
import { BurstSettings, DEFAULT_SPEED_INDEX, SPEEDS } from './BurstSettings.tsx'
import { ResultsPanel } from './ResultsPanel.tsx'

interface Props {
  endpoints: EndpointInfo[]
}

/** Sends the same burst to the three algorithm endpoints at once and shows them side by side. */
export function ComparePanel({ endpoints }: Props) {
  const [count, setCount] = useState(50)
  const [speedIndex, setSpeedIndex] = useState(DEFAULT_SPEED_INDEX)
  const [userId, setUserId] = useState<string | null>(null)
  // One burst per algorithm; hooks must be called unconditionally and in the same order
  const bursts = {
    TOKEN_BUCKET: useBurst(),
    SLIDING_WINDOW_LOG: useBurst(),
    SLIDING_WINDOW_COUNTER: useBurst(),
  }

  const comparison = pickComparisonEndpoints(endpoints)
  if ('problem' in comparison) {
    return <section className="card"><p className="error">{comparison.problem}</p></section>
  }

  const running = ALGORITHMS.some((algorithm) => bursts[algorithm].state.running)
  const hasResults = ALGORITHMS.some((algorithm) => bursts[algorithm].state.results.length > 0)

  function onSubmit(event: FormEvent) {
    event.preventDefault()
    if ('problem' in comparison) {
      return
    }
    // A fresh user per run, so every algorithm starts with a full allowance
    const runUserId = randomUserId()
    setUserId(runUserId)
    const ratePerSecond = SPEEDS[speedIndex].value
    for (const algorithm of ALGORITHMS) {
      void bursts[algorithm].start({ endpoint: comparison.endpoints[algorithm], userId: runUserId, count, ratePerSecond })
    }
  }

  return (
    <section className="card">
      <form className="controls" onSubmit={onSubmit}>
        <p className="hint span compare-intro">
          The same burst goes to all three algorithms at the same time. They share one limit:{' '}
          <strong>{describeRule(comparison.endpoints.TOKEN_BUCKET)}</strong>,
          so any difference comes from the algorithm alone. Each run uses a new user ID.
        </p>

        <BurstSettings count={count} onCountChange={setCount} speedIndex={speedIndex} onSpeedIndexChange={setSpeedIndex} />

        <div className="actions span">
          <button type="submit" disabled={running}>Send to all three</button>
          <button type="button" className="secondary" disabled={!running}
                  onClick={() => ALGORITHMS.forEach((algorithm) => bursts[algorithm].stop())}>
            Stop
          </button>
          <button type="button" className="secondary" disabled={running || !hasResults}
                  onClick={() => {
                    ALGORITHMS.forEach((algorithm) => bursts[algorithm].reset())
                    setUserId(null)
                  }}>
            Clear
          </button>
          {userId && <span className="hint">User: <code>{userId}</code></span>}
        </div>
      </form>

      <div className="compare-columns">
        {ALGORITHMS.map((algorithm) => (
          <article key={algorithm} className="compare-column">
            <h2>{algorithmLabel(algorithm)}</h2>
            <p className="hint">
              <code>{comparison.endpoints[algorithm].path}</code>
              <br />
              {expectedBehaviour(comparison.endpoints[algorithm])}
            </p>
            <ResultsPanel state={bursts[algorithm].state} />
          </article>
        ))}
      </div>
    </section>
  )
}
