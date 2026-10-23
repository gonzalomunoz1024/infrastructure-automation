import { useEffect, useRef, useState, type FocusEvent, type KeyboardEvent, type ReactNode, type RefObject } from 'react'
import { cx, niceTicks } from '@iaf/ui'
import { seriesColor, type SeriesSlot } from './palette'
import { SeriesStyles, SeriesSwatch } from './SeriesSwatch'

/*
 * A stacked column chart over time, for amounts made of a few named parts (cost by service, by
 * day). Forked from the idiom of Atlas's BarChart (components/Charts.tsx: the measured width, the
 * round ticks, the hairline grid, the frosted tooltip) with what a stack needs: up to eight series
 * in the console's palette, a 2px gap of the surface between segments and between columns, the
 * data end rounded and the baseline square, columns no thicker than 24px, a legend from two
 * series up, a fainter column for a period still open, and the last frame kept, dimmed, while
 * the next loads. Every column is reachable by keyboard (Tab to the chart, then the arrow keys,
 * Home and End) and says its values to a screen reader; the same values are what the page's
 * table view shows, so the tooltip never gates a number.
 */

export interface StackedSeries {
  key: string
  label: string
  /** the palette slot (1 to 8) that colours it, or 'other' for the folded rest */
  slot: SeriesSlot
}

export interface StackedBar {
  key: string
  /** the axis label: "Oct 3", "Nov" */
  label: string
  /** the tooltip's and the screen reader's name for the column: "Friday, October 3, 2026" */
  title?: string
  /** the column's amount per series key; a missing key is 0 */
  values: Record<string, number>
  /** the column's height; the sum of its values when not given */
  total?: number
  /** the period is still open: drawn fainter, and said so */
  provisional?: boolean
}

export interface StackedBarChartProps {
  /** bottom of the stack first */
  series: StackedSeries[]
  bars: StackedBar[]
  /** a value in the tooltip and in what a screen reader hears */
  format: (v: number) => string
  /** the y axis' ticks (compact); `format` when not given */
  tickFormat?: (v: number) => string
  /** what the chart shows, for screen readers: "Daily cost by service" */
  label: string
  /** the plot and its axis labels, in pixels */
  height?: number
  /** a newer answer is on its way: the frame stays, dimmed, with no skeleton and no jump */
  stale?: boolean
  /** what an open period is called in its tooltip */
  provisionalLabel?: string
  /** shown in place of the plot when every column is 0 */
  emptyLabel?: string
  className?: string
}

const GAP = 2
const MAX_BAR = 24
const PROVISIONAL_OPACITY = 0.55

function useElementWidth<T extends HTMLElement>(fallback: number): [RefObject<T | null>, number] {
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
  return [ref, width]
}

/** Whether the focus came from the keyboard (an engine that cannot tell says yes, so the ring shows). */
function focusVisible(el: Element): boolean {
  try {
    return el.matches(':focus-visible')
  } catch {
    return true
  }
}

/** caption2 is about 6.5px a character */
const textWidth = (s: string) => s.length * 6.5

/** A column's segment path: square at the baseline, the data end rounded (4px) when it tops the stack. */
export function segmentPath(x: number, y: number, w: number, h: number, roundTop: boolean): string {
  if (h <= 0 || w <= 0) return ''
  if (!roundTop) return `M${x} ${y} H${x + w} V${y + h} H${x} Z`
  const r = Math.min(4, w / 2, h)
  return `M${x} ${y + h} V${y + r} Q${x} ${y} ${x + r} ${y} H${x + w - r} Q${x + w} ${y} ${x + w} ${y + r} V${y + h} Z`
}

export interface StackedSegment {
  key: string
  slot: SeriesSlot
  value: number
  /** top edge and height in pixels, the gap below already taken off */
  y: number
  height: number
  /** the top of the stack: its data end is rounded */
  top: boolean
}

/**
 * The segments of one column, bottom up: each part's share of the plot's height, with a gap of
 * the surface between neighbours. A part too thin to show past its gap is left out of the drawing
 * (its value is still in the tooltip and the table).
 */
export function stackSegments(bar: StackedBar, series: readonly StackedSeries[], y: (v: number) => number): StackedSegment[] {
  const out: StackedSegment[] = []
  let sum = 0
  for (const s of series) {
    const v = Math.max(0, bar.values[s.key] ?? 0)
    if (!v) continue
    const bottom = y(sum) - (out.length ? GAP : 0)
    sum += v
    const top = y(sum)
    if (bottom - top < 0.5) continue
    out.push({ key: s.key, slot: s.slot, value: v, y: top, height: bottom - top, top: false })
  }
  if (out.length) out[out.length - 1].top = true
  return out
}

/** A column's height: its total when given, else the sum of its parts. */
export function barTotal(bar: StackedBar): number {
  return bar.total ?? Object.values(bar.values).reduce((a, v) => a + Math.max(0, v), 0)
}

/** What a screen reader hears for a column: its name, its total, then each part, largest first. */
export function barDescription(bar: StackedBar, series: readonly StackedSeries[], format: (v: number) => string, provisionalLabel = 'provisional'): string {
  const parts = series
    .map((s) => ({ label: s.label, v: bar.values[s.key] ?? 0 }))
    .filter((p) => p.v > 0)
    .sort((a, b) => b.v - a.v)
    .map((p) => `${p.label} ${format(p.v)}`)
  const head = `${bar.title ?? bar.label}: ${format(barTotal(bar))}`
  return [series.length > 1 && parts.length ? `${head}. ${parts.join(', ')}` : head, bar.provisional ? provisionalLabel : ''].filter(Boolean).join('. ')
}

/** Columns stacked from named parts, with a legend, a tooltip on hover and focus, and keyboard steps from column to column. */
export function StackedBarChart({ series, bars, format, tickFormat, label, height = 240, stale, provisionalLabel = 'Provisional: the period is still open', emptyLabel = 'Nothing to show', className }: StackedBarChartProps) {
  const [ref, width] = useElementWidth<HTMLDivElement>(600)
  const [hover, setHover] = useState<number | null>(null)
  const [focused, setFocused] = useState<{ index: number; visible: boolean } | null>(null)
  const [cursor, setCursor] = useState<number | null>(null)
  const columns = useRef<(SVGGElement | null)[]>([])
  const tick = tickFormat ?? format
  const n = bars.length
  const totals = bars.map(barTotal)
  const max = Math.max(0, ...totals)
  const legend = series.length > 1 && (
    <ul className="mb-3 flex flex-wrap gap-x-4 gap-y-1 text-caption text-secondary" aria-label="Legend">
      {series.map((s) => (
        <li key={s.key} className="inline-flex items-center gap-1.5">
          <SeriesSwatch slot={s.slot} />
          {s.label}
        </li>
      ))}
    </ul>
  )

  // one measured element whatever is drawn in it, so the width follows the page from the first render
  if (n === 0 || max <= 0) {
    return (
      <div ref={ref} className={className}>
        <SeriesStyles />
        <div className={cx('flex items-center justify-center rounded-md bg-surface-secondary text-caption text-secondary', stale && 'opacity-60', 'motion-safe:transition-opacity')} style={{ height }} aria-busy={stale || undefined}>
          {emptyLabel}
        </div>
      </div>
    )
  }

  const ticks = niceTicks(0, max, 4)
  const top = ticks[ticks.length - 1] || 1
  const m = { top: 8, right: 8, bottom: 24, left: Math.round(Math.max(...ticks.map((t) => textWidth(tick(t)))) + 10) }
  const plotW = Math.max(1, width - m.left - m.right)
  const plotH = Math.max(1, height - m.top - m.bottom)
  const band = plotW / n
  const barW = Math.max(1, Math.min(MAX_BAR, band * 0.64, band - GAP))
  const y = (v: number) => m.top + (1 - v / top) * plotH
  const labelPx = Math.max(...bars.map((b) => textWidth(b.label))) + 16
  const every = Math.max(1, Math.ceil(n / Math.max(1, Math.floor(plotW / labelPx))))
  // counted back from the newest column, so the period that is still open always has its label
  const showLabel = (i: number) => (n - 1 - i) % every === 0
  const at = cursor === null ? n - 1 : Math.min(cursor, n - 1)
  const shown = focused?.index ?? hover
  const centre = (i: number) => m.left + i * band + band / 2

  const move = (e: KeyboardEvent<SVGSVGElement>) => {
    const from = focused?.index ?? at
    const next = e.key === 'ArrowRight' || e.key === 'ArrowDown' ? from + 1 : e.key === 'ArrowLeft' || e.key === 'ArrowUp' ? from - 1 : e.key === 'Home' ? 0 : e.key === 'End' ? n - 1 : null
    if (next === null) {
      if (e.key === 'Escape') setFocused(null)
      return
    }
    e.preventDefault()
    const i = Math.max(0, Math.min(n - 1, next))
    setCursor(i)
    columns.current[i]?.focus()
  }
  const onFocus = (i: number, e: FocusEvent<SVGGElement>) => {
    setCursor(i)
    setFocused({ index: i, visible: focusVisible(e.currentTarget) })
  }

  return (
    <div ref={ref} className={className}>
      <SeriesStyles />
      {legend}
      <div className={cx('relative', stale && 'opacity-60', 'motion-safe:transition-opacity')} aria-busy={stale || undefined}>
        <svg width={width} height={height} className="block overflow-visible" role="group" aria-label={label} onKeyDown={move}>
          {ticks.map((t) => (
            <g key={t} aria-hidden="true">
              <line x1={m.left} x2={width - m.right} y1={y(t)} y2={y(t)} className={t === 0 ? 'stroke-stroke' : 'stroke-stroke-light'} strokeWidth={1} />
              <text x={m.left - 6} y={y(t)} textAnchor="end" dominantBaseline="middle" className="fill-tertiary text-caption2 tabular-nums">
                {tick(t)}
              </text>
            </g>
          ))}
          {bars.map((bar, i) => {
            const bx = m.left + i * band
            const x = bx + (band - barW) / 2
            const segments = stackSegments(bar, series, y)
            const lit = shown === i
            const half = textWidth(bar.label) / 2
            return (
              <g
                key={bar.key}
                ref={(el) => {
                  columns.current[i] = el
                }}
                role="img"
                aria-label={barDescription(bar, series, format, provisionalLabel)}
                tabIndex={i === at ? 0 : -1}
                className="outline-none"
                data-bar={bar.key}
                onFocus={(e) => onFocus(i, e)}
                onBlur={() => setFocused(null)}
                onPointerEnter={() => setHover(i)}
                onPointerLeave={() => setHover(null)}
              >
                {lit && <rect x={bx} y={m.top} width={band} height={plotH} className="fill-surface-secondary" />}
                <g opacity={bar.provisional ? PROVISIONAL_OPACITY : 1}>
                  {segments.map((s) => (
                    <path key={s.key} d={segmentPath(x, s.y, barW, s.height, s.top)} style={{ fill: seriesColor(s.slot) }} />
                  ))}
                </g>
                {showLabel(i) && (
                  <text x={Math.max(half, Math.min(width - half, bx + band / 2))} y={height - 6} textAnchor="middle" className="fill-tertiary text-caption2" aria-hidden="true">
                    {bar.label}
                  </text>
                )}
                <rect x={bx} y={m.top} width={band} height={plotH} fill="transparent" />
                {focused?.index === i && focused.visible && <rect x={bx + 1} y={m.top - 4} width={Math.max(2, band - 2)} height={plotH + 4} rx={4} fill="none" className="stroke-accent" strokeWidth={2} />}
              </g>
            )
          })}
        </svg>
        {shown !== null && shown < n && (
          <Tip x={centre(shown)} width={width} top={Math.max(0, Math.min(y(totals[shown]), m.top + plotH) - 8)}>
            <TipBody bar={bars[shown]} series={series} format={format} provisionalLabel={provisionalLabel} />
          </Tip>
        )}
      </div>
    </div>
  )
}

function Tip({ x, width, top, children }: { x: number; width: number; top: number; children: ReactNode }) {
  const near = 110
  const left = x < near ? Math.max(0, x - 12) : x > width - near ? Math.min(width, x + 12) : x
  const transform = `${x < near ? 'translateX(0)' : x > width - near ? 'translateX(-100%)' : 'translateX(-50%)'} translateY(-100%)`
  return (
    <div className="material-panel pointer-events-none absolute z-10 whitespace-nowrap rounded-md px-2.5 py-1.5 text-caption" style={{ left, top, transform }} aria-hidden="true">
      {children}
    </div>
  )
}

function TipBody({ bar, series, format, provisionalLabel }: { bar: StackedBar; series: readonly StackedSeries[]; format: (v: number) => string; provisionalLabel: string }) {
  // top of the stack first, as the column reads from the top
  const rows = [...series].reverse().filter((s) => (bar.values[s.key] ?? 0) > 0)
  return (
    <div className="space-y-0.5">
      <div className="text-caption2 text-tertiary">{bar.title ?? bar.label}</div>
      <div className="font-semibold tabular-nums text-primary">{format(barTotal(bar))}</div>
      {series.length > 1 &&
        rows.map((s) => (
          <div key={s.key} className="flex items-center gap-2">
            <SeriesSwatch slot={s.slot} shape="line" />
            <span className="font-medium tabular-nums text-primary">{format(bar.values[s.key] ?? 0)}</span>
            <span className="text-secondary">{s.label}</span>
          </div>
        ))}
      {bar.provisional && <div className="text-caption2 text-secondary">{provisionalLabel}</div>}
    </div>
  )
}
