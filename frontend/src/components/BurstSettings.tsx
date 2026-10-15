import { useId } from 'react'

export const SPEEDS: { label: string; value: number | null }[] = [
  { label: 'All at once', value: null },
  { label: '2 / second', value: 2 },
  { label: '5 / second', value: 5 },
  { label: '10 / second', value: 10 },
  { label: '20 / second', value: 20 },
  { label: '50 / second', value: 50 },
]

export const MAX_REQUESTS = 500
export const DEFAULT_SPEED_INDEX = 3

interface Props {
  count: number
  onCountChange: (count: number) => void
  speedIndex: number
  onSpeedIndexChange: (index: number) => void
}

/** The "how many, how fast" fields shared by both modes; renders label/field pairs for the controls grid. */
export function BurstSettings({ count, onCountChange, speedIndex, onSpeedIndexChange }: Props) {
  const id = useId()
  return (
    <>
      <label htmlFor={`${id}-count`}>Requests</label>
      <input
        id={`${id}-count`}
        type="number"
        min={1}
        max={MAX_REQUESTS}
        value={count}
        onChange={(e) => onCountChange(Math.min(MAX_REQUESTS, Math.max(1, Number(e.target.value) || 1)))}
      />

      <label htmlFor={`${id}-speed`}>Speed</label>
      <select id={`${id}-speed`} value={speedIndex} onChange={(e) => onSpeedIndexChange(Number(e.target.value))}>
        {SPEEDS.map((speed, index) => (
          <option key={speed.label} value={index}>{speed.label}</option>
        ))}
      </select>
    </>
  )
}
