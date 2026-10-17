import { useEffect, useMemo, useRef, useState, type ReactNode, type RefObject } from 'react'
import { cx } from '../lib/cx'
import { formatNumber } from '../lib/format'

/*
 * Tiny dependency-free SVG charts for the admin views.
 * One series colour: the accent. A second series is the tertiary ink, a third
 * the accent dashed — the states (healthy/warning/critical) are never series
 * colours. Hairline grid, recessive axes, a hover crosshair with a tooltip.
 */

export interface ChartSeries {
  id: string
  label?: string
  values: number[]
}

interface SeriesStyle {
  className: string
  dash?: string
}

const SERIES_STYLE: SeriesStyle[] = [
  { className: 'text-accent' },
  { className: 'text-tertiary' },
  { className: 'text-accent', dash: '4 3' },
  { className: 'text-tertiary', dash: '4 3' },
]

function seriesStyle(i: number): SeriesStyle {
  return SERIES_STYLE[i % SERIES_STYLE.length]
}

const defaultFormat = (v: number) => formatNumber(v, { compact: Math.abs(v) >= 10_000 })

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

function niceStep(range: number, count: number): number {
  const rough = range / Math.max(1, count)
  const pow = 10 ** Math.floor(Math.log10(rough || 1))
  const n = rough / pow
  const step = n <= 1 ? 1 : n <= 2 ? 2 : n <= 2.5 ? 2.5 : n <= 5 ? 5 : 10
  return step * pow
}

/** Round tick values covering [min, max]. */
export function niceTicks(min: number, max: number, count = 4): number[] {
  if (!Number.isFinite(min) || !Number.isFinite(max)) return []
  if (max === min) return [min]
  const step = niceStep(max - min, count)
  const start = Math.floor(min / step) * step
  const out: number[] = []
  for (let v = start; v <= max + step * 1e-6; v += step) out.push(Number(v.toFixed(10)))
  return out
}

function domainOf(values: number[], ticks: number) {
  const finite = values.filter((v) => Number.isFinite(v))
  const min = finite.length ? Math.min(0, ...finite) : 0
  const max = finite.length ? Math.max(...finite) : 1
  const tk = niceTicks(min, max === min ? min + 1 : max, ticks)
  return { ticks: tk, min: tk[0] ?? 0, max: tk[tk.length - 1] ?? 1 }
}

function labelWidth(labels: string[]): number {
  return Math.round(Math.max(1, ...labels.map((l) => l.length)) * 6.5 + 8)
}

function linePath(values: number[], x: (i: number) => number, y: (v: number) => number): string {
  let d = ''
  let pen = false
  values.forEach((v, i) => {
    if (!Number.isFinite(v)) {
      pen = false
      return
    }
    d += `${pen ? 'L' : 'M'}${x(i).toFixed(1)} ${y(v).toFixed(1)} `
    pen = true
  })
  return d.trim()
}

function Swatch({ style }: { style: SeriesStyle }) {
  return (
    <svg width="14" height="6" className={cx('shrink-0', style.className)} aria-hidden="true">
      <line x1="0" y1="3" x2="14" y2="3" stroke="currentColor" strokeWidth="2" strokeDasharray={style.dash} />
    </svg>
  )
}

function Legend({ series }: { series: ChartSeries[] }) {
  return (
    <div className="mb-2 flex flex-wrap gap-x-4 gap-y-1 text-caption text-secondary">
      {series.map((s, i) => (
        <span key={s.id} className="inline-flex items-center gap-1.5">
          <Swatch style={seriesStyle(i)} />
          {s.label ?? s.id}
        </span>
      ))}
    </div>
  )
}

function ChartTip({ x, width, top, children }: { x: number; width: number; top: number; children: ReactNode }) {
  const near = 96
  const left = x < near ? x + 10 : x > width - near ? x - 10 : x
  const transform = x < near ? 'none' : x > width - near ? 'translateX(-100%)' : 'translateX(-50%)'
  return (
    <div
      className="material-panel pointer-events-none absolute z-10 whitespace-nowrap rounded-md px-2.5 py-1.5 text-caption"
      style={{ left, top, transform }}
      role="status"
    >
      {children}
    </div>
  )
}

function TipRows({ label, rows }: { label: ReactNode; rows: { label: string; value: string; style?: SeriesStyle }[] }) {
  return (
    <div className="space-y-0.5">
      <div className="text-caption2 text-tertiary">{label}</div>
      {rows.map((r) => (
        <div key={r.label} className="flex items-center gap-2">
          {r.style && <Swatch style={r.style} />}
          <span className="text-secondary">{r.label}</span>
          <span className="ml-auto pl-3 font-medium tabular-nums text-primary">{r.value}</span>
        </div>
      ))}
    </div>
  )
}

export interface SparklineProps {
  values: number[]
  width?: number
  height?: number
  strokeWidth?: number
  /** soft tint under the line */
  fill?: boolean
  /** mark the last point */
  dot?: boolean
  className?: string
  ariaLabel?: string
}

/** Inline trend line, no axes. Colour follows `currentColor` (accent by default). */
export function Sparkline({ values, width = 120, height = 32, strokeWidth = 1.5, fill = false, dot = true, className, ariaLabel }: SparklineProps) {
  const pts = useMemo(() => {
    const finite = values.filter((v) => Number.isFinite(v))
    if (finite.length === 0) return []
    const min = Math.min(...finite)
    const max = Math.max(...finite)
    const span = max - min || 1
    const pad = strokeWidth + 2
    const n = values.length
    return values.map((v, i) => ({
      x: n === 1 ? width / 2 : pad + (i / (n - 1)) * (width - pad * 2),
      y: pad + (1 - ((Number.isFinite(v) ? v : min) - min) / span) * (height - pad * 2),
    }))
  }, [values, width, height, strokeWidth])

  const d = pts.map((p, i) => `${i === 0 ? 'M' : 'L'}${p.x.toFixed(1)} ${p.y.toFixed(1)}`).join(' ')
  const last = pts[pts.length - 1]
  return (
    <svg
      viewBox={`0 0 ${width} ${height}`}
      width={width}
      height={height}
      className={cx('block overflow-visible text-accent', className)}
      role="img"
      aria-label={ariaLabel ?? `Trend of ${values.length} values`}
    >
      {fill && last && pts[0] && (
        <path d={`${d} L${last.x.toFixed(1)} ${height} L${pts[0].x.toFixed(1)} ${height} Z`} className="fill-current" opacity={0.1} stroke="none" />
      )}
      {d && <path d={d} fill="none" stroke="currentColor" strokeWidth={strokeWidth} strokeLinejoin="round" strokeLinecap="round" />}
      {dot && last && <circle cx={last.x} cy={last.y} r={2.5} className="fill-current stroke-surface" strokeWidth={1.5} />}
    </svg>
  )
}

export interface LineChartProps {
  series?: ChartSeries[]
  /** shorthand for one unnamed series */
  values?: number[]
  /** x labels, one per index */
  labels?: string[]
  height?: number
  /** tint under the first series */
  area?: boolean
  /** markers on every point (default: only the hovered one) */
  dots?: boolean
  yFormat?: (v: number) => string
  xFormat?: (label: string, index: number) => string
  /** target number of y gridlines */
  ticks?: number
  /** tooltip body for an index; the default lists every series */
  tooltip?: (index: number) => ReactNode
  /** legend above the plot (default: when more than one series) */
  legend?: boolean
  className?: string
  ariaLabel?: string
}

/** Line chart over an index: hairline grid, recessive axes, crosshair tooltip on hover. */
export function LineChart({
  series,
  values,
  labels,
  height = 160,
  area = false,
  dots = false,
  yFormat,
  xFormat,
  ticks = 4,
  tooltip,
  legend,
  className,
  ariaLabel,
}: LineChartProps) {
  const all = useMemo<ChartSeries[]>(() => series ?? [{ id: 'value', values: values ?? [] }], [series, values])
  const [ref, width] = useElementWidth<HTMLDivElement>(600)
  const [hover, setHover] = useState<number | null>(null)
  const fmt = yFormat ?? defaultFormat
  const n = Math.max(0, ...all.map((s) => s.values.length))

  const geom = useMemo(() => {
    const dom = domainOf(all.flatMap((s) => s.values), ticks)
    const m = { top: 10, right: 12, bottom: labels ? 24 : 8, left: labelWidth(dom.ticks.map(fmt)) }
    const plotW = Math.max(1, width - m.left - m.right)
    const plotH = Math.max(1, height - m.top - m.bottom)
    const x = (i: number) => m.left + (n <= 1 ? plotW / 2 : (i / (n - 1)) * plotW)
    const y = (v: number) => m.top + (1 - (v - dom.min) / (dom.max - dom.min || 1)) * plotH
    return { ...dom, m, plotW, plotH, x, y }
  }, [all, width, height, labels, ticks, fmt, n])

  if (n === 0) {
    return (
      <div ref={ref} className={cx('flex items-center justify-center text-caption text-tertiary', className)} style={{ height }}>
        No data
      </div>
    )
  }

  const showLegend = legend ?? all.length > 1
  const every = Math.max(1, Math.ceil(n / Math.max(2, Math.floor(geom.plotW / 72))))
  const showLabel = (i: number) => i === n - 1 || (i % every === 0 && n - 1 - i >= every / 2)

  return (
    <div ref={ref} className={className}>
      {showLegend && <Legend series={all} />}
      <div className="relative">
        <svg width={width} height={height} className="block overflow-visible" role="img" aria-label={ariaLabel ?? `Line chart of ${all.map((s) => s.label ?? s.id).join(', ')}`}>
          {geom.ticks.map((t) => (
            <g key={t}>
              <line x1={geom.m.left} x2={width - geom.m.right} y1={geom.y(t)} y2={geom.y(t)} className={t === 0 ? 'stroke-stroke' : 'stroke-stroke-light'} strokeWidth={1} />
              <text x={geom.m.left - 6} y={geom.y(t)} textAnchor="end" dominantBaseline="middle" className="fill-tertiary text-caption2">
                {fmt(t)}
              </text>
            </g>
          ))}
          {labels &&
            labels.map(
              (l, i) =>
                i < n &&
                showLabel(i) && (
                  <text key={i} x={geom.x(i)} y={height - 6} textAnchor={i === 0 ? 'start' : i === n - 1 ? 'end' : 'middle'} className="fill-tertiary text-caption2">
                    {xFormat ? xFormat(l, i) : l}
                  </text>
                ),
            )}
          {all.map((s, si) => {
            const st = seriesStyle(si)
            const d = linePath(s.values, geom.x, geom.y)
            const first = s.values.findIndex((v) => Number.isFinite(v))
            const lastIdx = s.values.length - 1
            return (
              <g key={s.id} className={st.className}>
                {area && si === 0 && d && first >= 0 && (
                  <path
                    d={`${d} L${geom.x(lastIdx).toFixed(1)} ${(geom.m.top + geom.plotH).toFixed(1)} L${geom.x(first).toFixed(1)} ${(geom.m.top + geom.plotH).toFixed(1)} Z`}
                    className="fill-current"
                    opacity={0.08}
                    stroke="none"
                  />
                )}
                {d && <path d={d} fill="none" stroke="currentColor" strokeWidth={2} strokeLinejoin="round" strokeLinecap="round" strokeDasharray={st.dash} />}
                {dots &&
                  s.values.map((v, i) => Number.isFinite(v) && <circle key={i} cx={geom.x(i)} cy={geom.y(v)} r={3} className="fill-current stroke-surface" strokeWidth={2} />)}
              </g>
            )
          })}
          {hover !== null && (
            <g>
              <line x1={geom.x(hover)} x2={geom.x(hover)} y1={geom.m.top} y2={geom.m.top + geom.plotH} className="stroke-stroke" strokeWidth={1} strokeDasharray="3 3" />
              {all.map((s, si) => {
                const v = s.values[hover]
                if (!Number.isFinite(v)) return null
                return <circle key={s.id} cx={geom.x(hover)} cy={geom.y(v)} r={4} className={cx('fill-current stroke-surface', seriesStyle(si).className)} strokeWidth={2} />
              })}
            </g>
          )}
          <rect
            x={geom.m.left}
            y={geom.m.top}
            width={geom.plotW}
            height={geom.plotH}
            fill="transparent"
            onPointerMove={(e) => {
              const rect = e.currentTarget.getBoundingClientRect()
              const i = n <= 1 ? 0 : Math.round(((e.clientX - rect.left) / Math.max(1, rect.width)) * (n - 1))
              setHover(Math.max(0, Math.min(n - 1, i)))
            }}
            onPointerLeave={() => setHover(null)}
          />
        </svg>
        {hover !== null && (
          <ChartTip x={geom.x(hover)} width={width} top={geom.m.top}>
            {tooltip ? (
              tooltip(hover)
            ) : (
              <TipRows
                label={labels?.[hover] ?? `#${hover + 1}`}
                rows={all.map((s, si) => ({ label: s.label ?? s.id, value: Number.isFinite(s.values[hover]) ? fmt(s.values[hover]) : '—', style: all.length > 1 ? seriesStyle(si) : undefined }))}
              />
            )}
          </ChartTip>
        )}
      </div>
    </div>
  )
}

function barPath(x: number, y: number, w: number, h: number, roundTop: boolean): string {
  if (h <= 0 || w <= 0) return ''
  const r = Math.min(4, w / 2, h)
  if (roundTop) {
    return `M${x} ${y + h} V${y + r} Q${x} ${y} ${x + r} ${y} H${x + w - r} Q${x + w} ${y} ${x + w} ${y + r} V${y + h} Z`
  }
  return `M${x} ${y} V${y + h - r} Q${x} ${y + h} ${x + r} ${y + h} H${x + w - r} Q${x + w} ${y + h} ${x + w} ${y + h - r} V${y} Z`
}

export interface BarChartProps {
  values: number[]
  labels?: string[]
  height?: number
  yFormat?: (v: number) => string
  /** bars grow to the right, labels at the left (rankings) */
  horizontal?: boolean
  /** direct value labels on every bar */
  showValues?: boolean
  tooltip?: (index: number) => ReactNode
  /** index of the one bar to emphasise (the others recede) */
  highlight?: number | null
  className?: string
  ariaLabel?: string
}

/** Bar chart: thin accent bars with rounded data-ends, hairline grid, hover tooltip. */
export function BarChart({ values, labels, height = 160, yFormat, horizontal = false, showValues = false, tooltip, highlight, className, ariaLabel }: BarChartProps) {
  const [ref, width] = useElementWidth<HTMLDivElement>(600)
  const [hover, setHover] = useState<number | null>(null)
  const fmt = yFormat ?? defaultFormat
  const n = values.length
  const dom = useMemo(() => domainOf(values, 4), [values])

  if (n === 0) {
    return (
      <div ref={ref} className={cx('flex items-center justify-center text-caption text-tertiary', className)} style={{ height }}>
        No data
      </div>
    )
  }

  const label = ariaLabel ?? `Bar chart of ${n} values`
  const tipFor = (i: number) => (tooltip ? tooltip(i) : <TipRows label={labels?.[i] ?? `#${i + 1}`} rows={[{ label: 'Value', value: Number.isFinite(values[i]) ? fmt(values[i]) : '—' }]} />)
  const dim = (i: number) => (highlight != null && highlight !== i ? 0.45 : 1)

  if (horizontal) {
    const m = { top: 6, right: showValues ? 48 : 12, bottom: 20, left: Math.min(180, labels ? labelWidth(labels) : 8) }
    const plotW = Math.max(1, width - m.left - m.right)
    const plotH = Math.max(1, height - m.top - m.bottom)
    const band = plotH / n
    const barH = Math.min(22, Math.max(2, band * 0.64))
    const x = (v: number) => m.left + ((v - dom.min) / (dom.max - dom.min || 1)) * plotW
    const x0 = x(0)
    return (
      <div ref={ref} className={cx('relative', className)}>
        <svg width={width} height={height} className="block overflow-visible" role="img" aria-label={label}>
          {dom.ticks.map((t) => (
            <g key={t}>
              <line x1={x(t)} x2={x(t)} y1={m.top} y2={m.top + plotH} className={t === 0 ? 'stroke-stroke' : 'stroke-stroke-light'} strokeWidth={1} />
              <text x={x(t)} y={height - 5} textAnchor="middle" className="fill-tertiary text-caption2">
                {fmt(t)}
              </text>
            </g>
          ))}
          {values.map((v, i) => {
            const yTop = m.top + i * band
            const y = yTop + (band - barH) / 2
            const ok = Number.isFinite(v)
            const left = ok ? Math.min(x(v), x0) : x0
            const w = ok ? Math.abs(x(v) - x0) : 0
            return (
              <g key={i} opacity={dim(i)}>
                {hover === i && <rect x={m.left} y={yTop} width={plotW} height={band} className="fill-surface-secondary" />}
                {labels?.[i] != null && (
                  <text x={m.left - 8} y={yTop + band / 2} textAnchor="end" dominantBaseline="middle" className="fill-secondary text-caption2">
                    {labels[i]}
                  </text>
                )}
                {w > 0 && <rect x={left} y={y} width={w} height={barH} rx={Math.min(4, barH / 2)} className="fill-accent" />}
                {showValues && ok && (
                  <text x={x(Math.max(v, 0)) + 6} y={yTop + band / 2} dominantBaseline="middle" className="fill-secondary text-caption2 tabular-nums">
                    {fmt(v)}
                  </text>
                )}
                <rect x={m.left} y={yTop} width={plotW} height={band} fill="transparent" onPointerEnter={() => setHover(i)} onPointerLeave={() => setHover(null)} />
              </g>
            )
          })}
        </svg>
        {hover !== null && (
          <ChartTip x={Math.min(x(Math.max(values[hover] || 0, 0)) + 8, width)} width={width} top={m.top + hover * band - 4}>
            {tipFor(hover)}
          </ChartTip>
        )}
      </div>
    )
  }

  const m = { top: showValues ? 20 : 10, right: 8, bottom: labels ? 24 : 8, left: labelWidth(dom.ticks.map(fmt)) }
  const plotW = Math.max(1, width - m.left - m.right)
  const plotH = Math.max(1, height - m.top - m.bottom)
  const band = plotW / n
  const barW = Math.min(28, Math.max(2, band * 0.64))
  const y = (v: number) => m.top + (1 - (v - dom.min) / (dom.max - dom.min || 1)) * plotH
  const y0 = y(0)
  const every = Math.max(1, Math.ceil(n / Math.max(2, Math.floor(plotW / 56))))

  return (
    <div ref={ref} className={cx('relative', className)}>
      <svg width={width} height={height} className="block overflow-visible" role="img" aria-label={label}>
        {dom.ticks.map((t) => (
          <g key={t}>
            <line x1={m.left} x2={width - m.right} y1={y(t)} y2={y(t)} className={t === 0 ? 'stroke-stroke' : 'stroke-stroke-light'} strokeWidth={1} />
            <text x={m.left - 6} y={y(t)} textAnchor="end" dominantBaseline="middle" className="fill-tertiary text-caption2">
              {fmt(t)}
            </text>
          </g>
        ))}
        {values.map((v, i) => {
          const bx = m.left + i * band
          const x = bx + (band - barW) / 2
          const ok = Number.isFinite(v)
          const top = ok ? Math.min(y(v), y0) : y0
          const h = ok ? Math.abs(y(v) - y0) : 0
          return (
            <g key={i} opacity={dim(i)}>
              {hover === i && <rect x={bx} y={m.top} width={band} height={plotH} className="fill-surface-secondary" />}
              {h > 0 && <path d={barPath(x, top, barW, h, v >= 0)} className="fill-accent" />}
              {showValues && ok && (
                <text x={bx + band / 2} y={top - 5} textAnchor="middle" className="fill-secondary text-caption2 tabular-nums">
                  {fmt(v)}
                </text>
              )}
              {labels?.[i] != null && (i % every === 0 || i === n - 1) && (
                <text x={bx + band / 2} y={height - 6} textAnchor="middle" className="fill-tertiary text-caption2">
                  {labels[i]}
                </text>
              )}
              <rect x={bx} y={m.top} width={band} height={plotH} fill="transparent" onPointerEnter={() => setHover(i)} onPointerLeave={() => setHover(null)} />
            </g>
          )
        })}
      </svg>
      {hover !== null && (
        <ChartTip x={m.left + hover * band + band / 2} width={width} top={m.top}>
          {tipFor(hover)}
        </ChartTip>
      )}
    </div>
  )
}
