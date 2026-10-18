import { cx } from '@iaf/ui'
import { href, pathFor } from '../app/router'
import type { Lineage, Resource } from '../lib/types'

/*
 * Where a resource came from and what depends on it, as a small SVG: what it was built from at
 * the left, the resource in the middle in the accent, its dependents at the right, hairline
 * edges between them. Every node is a link to its own page.
 */

const NODE_W = 168
const NODE_H = 46
const ROW_GAP = 14
const COL_GAP = 72
const PAD = 8

export interface LineageGraphProps {
  lineage: Lineage
  /** navigate instead of following the href (hash routing still works without it) */
  onNavigate?: (id: string) => void
  className?: string
}

function label(r: Resource): string {
  const name = r.name || r.id
  return name.length > 20 ? `${name.slice(0, 19)}…` : name
}

function Node({ r, x, y, accent, onNavigate }: { r: Resource; x: number; y: number; accent?: boolean; onNavigate?: (id: string) => void }) {
  const to = pathFor('resource', { id: r.id })
  return (
    <a
      href={href(to)}
      onClick={(e) => {
        if (!onNavigate || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return
        e.preventDefault()
        onNavigate(r.id)
      }}
      className="group/node outline-none"
      aria-label={`${r.name ?? r.id}, ${r.type}, ${r.status}`}
    >
      <rect
        x={x}
        y={y}
        width={NODE_W}
        height={NODE_H}
        rx={10}
        className={cx('transition-colors', accent ? 'fill-accent-tint stroke-accent' : 'fill-surface stroke-stroke group-hover/node:stroke-accent group-focus-visible/node:stroke-accent')}
        strokeWidth={accent ? 1.5 : 1}
      />
      <text x={x + 12} y={y + 19} className={cx('text-caption font-medium', accent ? 'fill-accent' : 'fill-primary')}>
        {label(r)}
      </text>
      <text x={x + 12} y={y + 35} className="fill-tertiary text-caption2">
        {r.type} · {r.status}
      </text>
    </a>
  )
}

export function LineageGraph({ lineage, onNavigate, className }: LineageGraphProps) {
  const left = lineage.builtFrom
  const right = lineage.dependents
  const rows = Math.max(1, left.length, right.length)
  const height = PAD * 2 + rows * NODE_H + (rows - 1) * ROW_GAP
  const width = PAD * 2 + NODE_W * 3 + COL_GAP * 2
  const colX = [PAD, PAD + NODE_W + COL_GAP, PAD + (NODE_W + COL_GAP) * 2]
  const yOf = (i: number, n: number) => PAD + (height - PAD * 2 - (n * NODE_H + (n - 1) * ROW_GAP)) / 2 + i * (NODE_H + ROW_GAP)
  const midY = yOf(0, 1) + NODE_H / 2

  const edge = (x1: number, y1: number, x2: number, y2: number) => {
    const dx = (x2 - x1) / 2
    return `M${x1} ${y1} C${x1 + dx} ${y1}, ${x2 - dx} ${y2}, ${x2} ${y2}`
  }

  return (
    <svg viewBox={`0 0 ${width} ${height}`} width="100%" height={height} className={cx('block max-w-full', className)} role="img" aria-label={`Lineage of ${lineage.resource.name ?? lineage.resource.id}: built from ${left.length}, ${right.length} dependents`}>
      <g className="stroke-stroke" fill="none" strokeWidth={1}>
        {left.map((r, i) => (
          <path key={`l-${r.id}`} d={edge(colX[0] + NODE_W, yOf(i, left.length) + NODE_H / 2, colX[1], midY)} />
        ))}
        {right.map((r, i) => (
          <path key={`r-${r.id}`} d={edge(colX[1] + NODE_W, midY, colX[2], yOf(i, right.length) + NODE_H / 2)} />
        ))}
      </g>
      {left.length === 0 && (
        <text x={colX[0] + NODE_W / 2} y={midY + 4} textAnchor="middle" className="fill-tertiary text-caption">
          not built from another resource
        </text>
      )}
      {right.length === 0 && (
        <text x={colX[2] + NODE_W / 2} y={midY + 4} textAnchor="middle" className="fill-tertiary text-caption">
          nothing depends on it
        </text>
      )}
      {left.map((r, i) => (
        <Node key={r.id} r={r} x={colX[0]} y={yOf(i, left.length)} onNavigate={onNavigate} />
      ))}
      <Node r={lineage.resource} x={colX[1]} y={yOf(0, 1)} accent onNavigate={onNavigate} />
      {right.map((r, i) => (
        <Node key={r.id} r={r} x={colX[2]} y={yOf(i, right.length)} onNavigate={onNavigate} />
      ))}
    </svg>
  )
}
