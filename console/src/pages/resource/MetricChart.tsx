import { useEffect, useId, useMemo, useRef, useState, type KeyboardEvent, type PointerEvent } from 'react'
import { Button, cx } from '@iaf/ui'
import { linearScale, nearestIndex, nextIndex, pathOf, pointLabel, runsOf, summarise, timeLabel, timeTicks, valueDomain, type ChartPoint } from '../../lib/chart'
import { formatMetric, seriesTitle, speakMetric, unitWord, type MetricSeries } from '../../lib/monitoring'

/*
 * One series of a resource's metrics as a line chart (the dataviz method: one accent hue, a 2px
 * line over a faint wash, hairline grid, few ticks, the latest point marked). A threshold of an
 * alarm on the series is a dashed line with its label. The plot is a slider for the keyboard:
 * arrows, Page keys, Home and End move a crosshair point by point and the readout says the time
 * and the value; the pointer does the same on hover. Every chart says its latest, average and
 * highest value in words, and turns into a table on request.
 */

export interface ChartThreshold {
  value: number
  label: string
}

export interface MetricChartProps {
  series: MetricSeries
  /** the window drawn, in milliseconds since the epoch */
  from: number
  to: number
  /** the step between points: a longer gap breaks the line */
  stepMs: number
  thresholds?: ChartThreshold[]
  /** drawn faded while the next range loads */
  stale?: boolean
  className?: string
}

const HEIGHT = 176
const MARGIN = { top: 12, right: 12, bottom: 24, left: 44 }

/** The width of an element, followed as it resizes. */
function useWidth<T extends HTMLElement>(fallback: number) {
  const ref = useRef<T | null>(null)
  const [width, setWidth] = useState(fallback)
  useEffect(() => {
    const el = ref.current
    if (!el || typeof ResizeObserver === 'undefined') return
    const update = () => setWidth(Math.max(0, Math.round(el.getBoundingClientRect().width)) || fallback)
    update()
    const ro = new ResizeObserver(update)
    ro.observe(el)
    return () => ro.disconnect()
  }, [fallback])
  return [ref, width] as const
}

function tickText(v: number, unit: string | undefined): string {
  if (unit === 'percent' || unit === '%') return `${v}%`
  if (Math.abs(v) >= 10_000) return new Intl.NumberFormat('en-US', { notation: 'compact', maximumFractionDigits: 1 }).format(v)
  return new Intl.NumberFormat('en-US', { maximumFractionDigits: 2 }).format(v)
}

/** The title a chart carries: the series in words, then its unit. */
export function chartTitle(series: Pick<MetricSeries, 'name' | 'unit'>): string {
  const unit = unitWord(series.unit)
  return unit ? `${seriesTitle(series.name)}, ${unit}` : seriesTitle(series.name)
}

/** The sentence under a chart's title: the latest, the average and the highest, with when. */
export function summaryLine(points: readonly ChartPoint[], unit: string | undefined): string {
  const s = summarise(points)
  if (!s.count || !s.latest || !s.highest) return 'No points in this range.'
  return `Latest ${formatMetric(s.latest.v, unit)}, average ${formatMetric(s.average, unit)}, highest ${formatMetric(s.highest.v, unit)} at ${pointLabel(s.highest.t)}.`
}

export function MetricChart({ series, from, to, stepMs, thresholds = [], stale, className }: MetricChartProps) {
  const titleId = useId()
  const summaryId = useId()
  const [table, setTable] = useState(false)
  const [frameRef, width] = useWidth<HTMLDivElement>(560)
  const [active, setActive] = useState<number | null>(null)
  const [focused, setFocused] = useState(false)

  const points = useMemo<ChartPoint[]>(() => series.points.filter((p) => p.value !== null && Number.isFinite(p.value)).map((p) => ({ t: Date.parse(p.at), v: p.value as number })), [series.points])
  const unit = series.unit
  const title = chartTitle(series)
  const summary = summaryLine(points, unit)

  const plotW = Math.max(40, width - MARGIN.left - MARGIN.right)
  const plotH = HEIGHT - MARGIN.top - MARGIN.bottom
  const domain = valueDomain(
    points.map((p) => p.v),
    { unit, include: thresholds.map((t) => t.value) },
  )
  const x = linearScale(from, to, MARGIN.left, MARGIN.left + plotW)
  const y = linearScale(domain.min, domain.max, MARGIN.top + plotH, MARGIN.top)
  const offset = -new Date(from).getTimezoneOffset()
  const ticks = timeTicks(from, to, width < 420 ? 3 : 5, offset)
  const runs = runsOf(points, stepMs)
  const last = points[points.length - 1]
  const index = active !== null && active < points.length ? active : null
  const at = index !== null ? points[index] : undefined
  const readout = at ? `${pointLabel(at.t)}: ${speakMetric(at.v, unit)}` : 'No point chosen'

  const pick = (e: PointerEvent<HTMLDivElement>) => {
    if (points.length === 0) return
    const box = e.currentTarget.getBoundingClientRect()
    setActive(nearestIndex(points, x.invert(e.clientX - box.left)))
  }
  const onKey = (e: KeyboardEvent<HTMLDivElement>) => {
    const next = nextIndex(e.key, index ?? points.length - 1, points.length)
    if (next === null) return
    e.preventDefault()
    setActive(next)
  }

  return (
    <figure aria-labelledby={titleId} className={cx('min-w-0 rounded-lg border border-stroke-light bg-surface p-4', className)} data-series={series.name}>
      <div className="flex flex-wrap items-start justify-between gap-2">
        <figcaption className="min-w-0 flex-1">
          <h3 id={titleId} className="text-subheadline font-semibold text-primary">
            {title}
          </h3>
          <p id={summaryId} className="mt-0.5 text-caption text-secondary" data-summary>
            {summary}
          </p>
        </figcaption>
        <Button size="sm" variant="ghost" icon={table ? 'chart' : 'list'} aria-pressed={table} onClick={() => setTable((v) => !v)}>
          {table ? 'Show as chart' : 'Show as table'}
        </Button>
      </div>

      <div ref={frameRef} className={cx('mt-3 transition-opacity', stale && 'opacity-50')}>
        {table ? (
          <div role="region" aria-label={`${title}, as a table`} tabIndex={0} className="max-h-64 overflow-auto rounded-md border border-stroke-light focus-visible:outline-2 focus-visible:outline-accent">
            <table className="w-full text-left text-caption">
              <caption className="sr-only">
                {title} from {pointLabel(from)} to {pointLabel(to)}, newest first
              </caption>
              <thead className="sticky top-0 bg-surface-secondary text-secondary">
                <tr>
                  <th scope="col" className="px-3 py-1.5 font-medium">
                    Time
                  </th>
                  <th scope="col" className="px-3 py-1.5 text-right font-medium">
                    {seriesTitle(series.name)}
                  </th>
                </tr>
              </thead>
              <tbody>
                {points.length === 0 ? (
                  <tr>
                    <td colSpan={2} className="px-3 py-2 text-secondary">
                      No points in this range.
                    </td>
                  </tr>
                ) : (
                  [...points].reverse().map((p) => (
                    <tr key={p.t} className="border-t border-stroke-light">
                      <td className="px-3 py-1 tabular-nums text-primary">{pointLabel(p.t)}</td>
                      <td className="px-3 py-1 text-right tabular-nums text-primary">{formatMetric(p.v, unit)}</td>
                    </tr>
                  ))
                )}
              </tbody>
            </table>
          </div>
        ) : (
          <div
            role="slider"
            tabIndex={0}
            aria-label={`${title}: move through the points with the arrow keys`}
            aria-describedby={summaryId}
            aria-valuemin={0}
            aria-valuemax={Math.max(0, points.length - 1)}
            aria-valuenow={index ?? Math.max(0, points.length - 1)}
            aria-valuetext={at ? readout : last ? `Latest, ${pointLabel(last.t)}: ${speakMetric(last.v, unit)}` : 'No points in this range'}
            onKeyDown={onKey}
            onFocus={() => {
              setFocused(true)
              if (index === null && points.length) setActive(points.length - 1)
            }}
            onBlur={() => {
              setFocused(false)
              setActive(null)
            }}
            onPointerMove={pick}
            onPointerDown={pick}
            onPointerLeave={() => {
              if (!focused) setActive(null)
            }}
            className="relative touch-pan-y rounded-md outline-none focus-visible:ring-2 focus-visible:ring-accent"
            data-chart
          >
            <svg width={width} height={HEIGHT} viewBox={`0 0 ${width} ${HEIGHT}`} className="block text-accent" aria-hidden="true">
              {domain.ticks.map((t) => (
                <g key={t}>
                  <line x1={MARGIN.left} x2={MARGIN.left + plotW} y1={y(t)} y2={y(t)} className={t === domain.min ? 'stroke-stroke' : 'stroke-stroke-light'} strokeWidth={1} />
                  <text x={MARGIN.left - 6} y={y(t)} textAnchor="end" dominantBaseline="middle" className="fill-tertiary text-caption2 tabular-nums">
                    {tickText(t, unit)}
                  </text>
                </g>
              ))}
              {ticks.map((t) => {
                const label = timeLabel(t, to - from)
                // a label that would run past the chart's right edge ends at it instead
                const atEnd = x(t) + label.length * 3.5 > width - 1
                return (
                  <text key={t} x={atEnd ? width - 1 : x(t)} y={HEIGHT - 6} textAnchor={atEnd ? 'end' : 'middle'} className="fill-tertiary text-caption2 tabular-nums">
                    {label}
                  </text>
                )
              })}
              {runs.map((run) => (
                <g key={run[0].t}>
                  {run.length > 1 && <path d={`${pathOf(run, x, y)} L${x(run[run.length - 1].t).toFixed(1)} ${y(domain.min).toFixed(1)} L${x(run[0].t).toFixed(1)} ${y(domain.min).toFixed(1)} Z`} className="fill-current" opacity={0.09} stroke="none" />}
                  <path d={pathOf(run, x, y)} fill="none" className="stroke-current" strokeWidth={2} strokeLinejoin="round" strokeLinecap="round" />
                  {run.length === 1 && <circle cx={x(run[0].t)} cy={y(run[0].v)} r={2.5} className="fill-current" />}
                </g>
              ))}
              {thresholds.map((th) => (
                <g key={`${th.label}-${th.value}`} data-threshold={th.value}>
                  <line x1={MARGIN.left} x2={MARGIN.left + plotW} y1={y(th.value)} y2={y(th.value)} className="stroke-secondary" strokeWidth={1.5} strokeDasharray="5 4" />
                  <text x={MARGIN.left + plotW - 2} y={y(th.value) - MARGIN.top < 12 ? y(th.value) + 13 : y(th.value) - 5} textAnchor="end" className="fill-secondary text-caption2 font-medium">
                    {th.label}
                  </text>
                </g>
              ))}
              {last && !at && <circle cx={x(last.t)} cy={y(last.v)} r={3.5} className="fill-current stroke-surface" strokeWidth={2} />}
              {at && (
                <g>
                  <line x1={x(at.t)} x2={x(at.t)} y1={MARGIN.top} y2={MARGIN.top + plotH} className="stroke-stroke" strokeWidth={1} strokeDasharray="3 3" />
                  <circle cx={x(at.t)} cy={y(at.v)} r={4.5} className="fill-current stroke-surface" strokeWidth={2} />
                </g>
              )}
            </svg>
            {at && (
              <div
                aria-hidden="true"
                data-readout
                className="pointer-events-none absolute top-1 rounded-md border border-stroke-light bg-surface px-2 py-1 text-caption shadow-sm"
                style={x(at.t) > MARGIN.left + plotW / 2 ? { right: Math.max(0, width - x(at.t) + 8) } : { left: x(at.t) + 8 }}
              >
                <span className="block text-tertiary tabular-nums">{pointLabel(at.t)}</span>
                <span className="block font-semibold text-primary tabular-nums">{formatMetric(at.v, unit)}</span>
              </div>
            )}
          </div>
        )}
      </div>
    </figure>
  )
}
