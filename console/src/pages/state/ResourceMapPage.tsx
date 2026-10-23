import { useEffect, useMemo, useRef, useState, type KeyboardEvent, type PointerEvent } from 'react'
import { Checkbox, Icon, IconButton, PageHeader, Segmented, Select, SkeletonRows, Table, cx, plural, type TableColumn } from '@iaf/ui'
import { ErrorState, LifecycleBadge } from '@iaf/console-kit'
import { pathOfWfrn, useConsole } from '../../app/console'
import { Link, navigate, pathFor, type PageProps } from '../../app/router'
import { formatAccountId } from '../../lib/accounts'
import { bff } from '../../lib/bff'
import { LEGEND, NODE_H, NODE_W, layoutMap, moveFrom, shadeOf, type ColourBy, type MapKey, type MapTone } from '../../lib/graph'
import { useQuery } from '../../lib/queries'
import { stateApi, type GraphNode } from '../../lib/state'

/*
 * The resource map (WP 9.4): what is built from what, across every type of the account and the
 * person's other accounts they choose, from the BFF's map. Columns run from what nothing was
 * built from at the left to what was built last at the right; each resource is a card with a dot
 * coloured by its health, its drift or what it costs, explained in the legend. The map zooms with
 * its buttons, + and - or Ctrl and the wheel, pans by dragging, and is moved through with the
 * arrow keys (left and right along the links, up and down within a column), Enter opening the
 * resource, every move said aloud. The list view holds the same resources and links as a table.
 * Drawn as hand-made SVG, like the Monitoring tab's charts.
 */

type View = 'map' | 'list'
const COLOURS: readonly { value: ColourBy; label: string }[] = [
  { value: 'health', label: 'Health' },
  { value: 'drift', label: 'Drift' },
  { value: 'cost', label: 'Cost' },
]
const HEIGHT = 520
const MIN_SCALE = 0.2
const MAX_SCALE = 2
const READABLE = 0.75

const FILL: Record<MapTone, string> = {
  healthy: 'fill-healthy',
  warning: 'fill-warning',
  critical: 'fill-critical',
  accent: 'fill-accent',
  neutral: 'fill-tertiary',
  'cost-1': 'fill-accent',
  'cost-2': 'fill-accent',
  'cost-3': 'fill-accent',
  'cost-4': 'fill-accent',
}
/** cost is said by the dot's size as well as the legend, so the bands do not lean on colour alone */
const RADIUS: Partial<Record<MapTone, number>> = { 'cost-1': 3, 'cost-2': 4.5, 'cost-3': 6, 'cost-4': 7.5 }

function Dot({ tone, cx: x, cy: y }: { tone: MapTone; cx: number; cy: number }) {
  return <circle cx={x} cy={y} r={RADIUS[tone] ?? 5} className={FILL[tone]} />
}

export default function ResourceMapPage({ route }: PageProps) {
  const { account, types } = useConsole()
  const view: View = route.query.view === 'list' ? 'list' : 'map'
  const colourBy: ColourBy = COLOURS.some((c) => c.value === route.query.colour) ? (route.query.colour as ColourBy) : 'health'
  const typeName = types.some((t) => t.name === route.query.type) ? route.query.type! : ''
  const others = (route.query.accounts ?? '').split(',').filter((a) => /^\d{12}$/.test(a) && a !== account.id)
  const accounts = useQuery(['state-accounts'], () => bff.accounts())
  const map = useQuery(['state-map', account.id, typeName, others.join(',')], () => stateApi.map({ accountIds: others.length ? others : undefined, type: typeName || undefined }))
  const set = (next: Record<string, string | undefined>) => navigate(pathFor('map', {}, { ...route.query, ...next }, { accountId: route.accountId }), { replace: true })
  const accountName = (id: string) => accounts.data?.items.find((a) => a.accountId === id)?.name ?? formatAccountId(id)
  const otherAccounts = (accounts.data?.items ?? []).filter((a) => a.accountId !== account.id)
  const nodes = useMemo(() => map.data?.nodes ?? [], [map.data])
  const nameOf = useMemo(() => new Map(nodes.map((n) => [n.wfrn, n.name ?? n.wfrn])), [nodes])

  return (
    <>
      <PageHeader title="Resource map" subtitle={`What is built from what in ${account.name}${others.length ? ` and ${plural(others.length, 'other account')}` : ''}: images and the machines built from them, the volumes and environments built on those. Coloured by health, drift or cost.`} />
      <section aria-label="Map settings" className="mb-4 flex flex-wrap items-center gap-3">
        <Segmented<View> ariaLabel="View" size="sm" value={view} onChange={(v) => set({ view: v === 'map' ? undefined : v })} options={[{ value: 'map', label: 'Map' }, { value: 'list', label: 'List' }]} />
        <Segmented<ColourBy> ariaLabel="Colour by" size="sm" value={colourBy} onChange={(v) => set({ colour: v === 'health' ? undefined : v })} options={COLOURS.map((c) => ({ value: c.value, label: c.label }))} />
        <Select size="sm" ariaLabel={`Type: ${typeName ? (types.find((t) => t.name === typeName)?.title ?? typeName) : 'All types'}`} value={typeName} onChange={(v) => set({ type: v || undefined })} options={[{ value: '', label: 'All types' }, ...types.map((t) => ({ value: t.name, label: t.title }))]} />
        {otherAccounts.map((a) => (
          <Checkbox
            key={a.accountId}
            checked={others.includes(a.accountId)}
            onChange={(on) => set({ accounts: (on ? [...others, a.accountId] : others.filter((x) => x !== a.accountId)).join(',') || undefined })}
            label={`Also ${a.name}`}
          />
        ))}
      </section>

      {map.error ? (
        <ErrorState error={map.error} title="Could not draw the map" onRetry={() => void map.refetch()} />
      ) : map.loading || !map.data ? (
        <SkeletonRows n={6} />
      ) : (
        <>
          <Legend by={colourBy} />
          {view === 'map' ? <MapCanvas nodes={nodes} edges={map.data.edges} colourBy={colourBy} accountName={accountName} multi={others.length > 0} /> : <MapList nodes={nodes} edges={map.data.edges} colourBy={colourBy} nameOf={nameOf} accountName={accountName} />}
          {(map.data.external.length > 0 || map.data.unmanaged.length > 0 || map.data.truncated) && (
            <ul className="mt-4 space-y-1 text-caption text-secondary" aria-label="Beyond the map">
              {map.data.external.map((x) => (
                <li key={`${x.accountId}-${x.type}-${x.direction}`}>{`${x.direction === 'builtFrom' ? 'Built from' : 'Built on these:'} ${plural(x.count, types.find((t) => t.name === x.type)?.title.toLowerCase().replace(/s$/, '') ?? x.type)} in ${accountName(x.accountId)}, which the map does not show.`}</li>
              ))}
              {map.data.unmanaged.length > 0 && <li>{`${plural(map.data.unmanaged.length, 'parent')} the console does not manage.`}</li>}
              {map.data.truncated && <li>The map stopped at its limit; pick a type to see the rest.</li>}
            </ul>
          )}
        </>
      )}
    </>
  )
}

function Legend({ by }: { by: ColourBy }) {
  return (
    <ul className="mb-3 flex flex-wrap items-center gap-x-4 gap-y-1 text-caption text-secondary" aria-label={`Colours: ${by}`}>
      {LEGEND[by].map((l) => (
        <li key={l.tone} className="flex items-center gap-1.5">
          <svg width="16" height="16" aria-hidden="true">
            <Dot tone={l.tone} cx={8} cy={8} />
          </svg>
          {l.words}
        </li>
      ))}
    </ul>
  )
}

/* ---------------------------------------------------------------------------- the map */

interface ViewBox {
  x: number
  y: number
  scale: number
}

function MapCanvas({ nodes, edges, colourBy, accountName, multi }: { nodes: GraphNode[]; edges: { from: string; to: string; via?: string | null }[]; colourBy: ColourBy; accountName: (id: string) => string; multi: boolean }) {
  const { types } = useConsole()
  const layout = useMemo(() => layoutMap(nodes, edges), [nodes, edges])
  const box = useRef<HTMLDivElement>(null)
  const [width, setWidth] = useState(900)
  // what the person zoomed and panned to, kept while the fitted scale it started from holds
  const [moved, setMoved] = useState<{ vb: ViewBox; from: number } | null>(null)
  const [focus, setFocus] = useState<string | undefined>(undefined)
  const drag = useRef<{ x: number; y: number; vb: ViewBox } | null>(null)

  useEffect(() => {
    const el = box.current
    if (!el || typeof ResizeObserver === 'undefined') return
    const ro = new ResizeObserver(() => setWidth(el.clientWidth || 900))
    ro.observe(el)
    setWidth(el.clientWidth || 900)
    return () => ro.disconnect()
  }, [])

  // the whole map fits when it is drawn, and again when its size or the space for it changes
  const fitScale = Math.max(MIN_SCALE, Math.min(1, width / layout.width, HEIGHT / layout.height))
  // a big map starts at a size its names can be read at, from its left edge; Fit shows all of it
  const vb: ViewBox = moved && moved.from === fitScale ? moved.vb : { x: 0, y: 0, scale: Math.max(fitScale, Math.min(1, READABLE)) }
  const setVb = (next: ViewBox | ((v: ViewBox) => ViewBox)) => setMoved({ vb: typeof next === 'function' ? next(vb) : next, from: fitScale })
  const fit = () => setVb({ x: 0, y: 0, scale: fitScale })

  const zoom = (factor: number, cx = width / 2, cy = HEIGHT / 2) =>
    setVb((v) => {
      const scale = Math.max(MIN_SCALE, Math.min(MAX_SCALE, v.scale * factor))
      // keep the point under (cx, cy) where it is
      const wx = v.x + cx / v.scale
      const wy = v.y + cy / v.scale
      return { scale, x: wx - cx / scale, y: wy - cy / scale }
    })

  /** brings a resource into view when the keyboard moves to it */
  const reveal = (wfrn: string) => {
    const p = layout.byWfrn.get(wfrn)
    if (!p) return
    setVb((v) => {
      const w = width / v.scale
      const h = HEIGHT / v.scale
      const inside = p.x >= v.x && p.x + NODE_W <= v.x + w && p.y >= v.y && p.y + NODE_H <= v.y + h
      return inside ? v : { ...v, x: p.x + NODE_W / 2 - w / 2, y: p.y + NODE_H / 2 - h / 2 }
    })
  }

  const onKeyDown = (e: KeyboardEvent<HTMLDivElement>) => {
    if (e.key === '+' || e.key === '=') {
      e.preventDefault()
      zoom(1.25)
      return
    }
    if (e.key === '-' || e.key === '_') {
      e.preventDefault()
      zoom(0.8)
      return
    }
    if (e.key === '0') {
      e.preventDefault()
      fit()
      return
    }
    if (e.key === 'Enter' && focus) {
      e.preventDefault()
      const href = pathOfWfrn(focus, types)
      if (href) navigate(href)
      return
    }
    if (['ArrowUp', 'ArrowDown', 'ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(e.key)) {
      e.preventDefault()
      const next = moveFrom(layout, focus, e.key as MapKey)
      if (next) {
        setFocus(next)
        reveal(next)
      }
    }
  }

  const onPointerDown = (e: PointerEvent<SVGSVGElement>) => {
    drag.current = { x: e.clientX, y: e.clientY, vb }
    e.currentTarget.setPointerCapture?.(e.pointerId)
  }
  const onPointerMove = (e: PointerEvent<SVGSVGElement>) => {
    const d = drag.current
    if (!d) return
    setVb({ ...d.vb, x: d.vb.x - (e.clientX - d.x) / d.vb.scale, y: d.vb.y - (e.clientY - d.y) / d.vb.scale })
  }
  const onPointerUp = () => {
    drag.current = null
  }
  const onWheel = (e: React.WheelEvent<SVGSVGElement>) => {
    if (!e.ctrlKey && !e.metaKey) return
    e.preventDefault()
    const rect = e.currentTarget.getBoundingClientRect()
    zoom(e.deltaY < 0 ? 1.15 : 0.87, e.clientX - rect.left, e.clientY - rect.top)
  }

  const focused = focus ? layout.byWfrn.get(focus) : undefined
  const near = new Set(focus ? [...(layout.parents.get(focus) ?? []), ...(layout.children.get(focus) ?? [])] : [])
  const describe = (n: GraphNode) => {
    const type = types.find((t) => t.name === n.type)
    const parents = (layout.parents.get(n.wfrn) ?? []).map((w) => layout.byWfrn.get(w)?.node.name ?? w)
    const kids = layout.children.get(n.wfrn)?.length ?? 0
    return `${n.name ?? n.wfrn}, ${type?.title.replace(/s$/, '') ?? n.type}, ${shadeOf(n, colourBy).words}${multi ? `, in ${accountName(n.accountId)}` : ''}. ${parents.length ? `Built from ${parents.join(', ')}.` : 'Built from nothing on the map.'} ${kids ? `${plural(kids, 'resource')} built from it.` : 'Nothing built from it.'}${n.context ? ' Shown for context.' : ''}`
  }

  return (
    <div className="relative">
      <div className="absolute right-2 top-2 z-10 flex gap-1 rounded-lg bg-surface/90 p-1 shadow-sm">
        <IconButton label="Zoom in" onClick={() => zoom(1.25)}>
          <Icon name="plus" size={15} />
        </IconButton>
        <IconButton label="Zoom out" onClick={() => zoom(0.8)}>
          <Icon name="minus" size={15} />
        </IconButton>
        <IconButton label="Fit the whole map" onClick={fit}>
          <Icon name="expand" size={15} />
        </IconButton>
      </div>
      <div
        ref={box}
        tabIndex={0}
        role="application"
        aria-roledescription="resource map"
        aria-label={`Resource map of ${plural(nodes.length, 'resource')}. Arrow keys move along the links, Enter opens the resource, plus and minus zoom.`}
        aria-describedby="map-focus"
        onKeyDown={onKeyDown}
        onFocus={() => {
          if (!focus && layout.nodes[0]) setFocus(layout.nodes[0].node.wfrn)
        }}
        className="overflow-hidden rounded-xl border border-stroke-light bg-surface-secondary/40 outline-none focus-visible:ring-2 focus-visible:ring-accent"
        data-testid="map"
      >
        <svg width="100%" height={HEIGHT} viewBox={`${vb.x} ${vb.y} ${width / vb.scale} ${HEIGHT / vb.scale}`} onPointerDown={onPointerDown} onPointerMove={onPointerMove} onPointerUp={onPointerUp} onPointerLeave={onPointerUp} onWheel={onWheel} className="cursor-grab touch-none select-none active:cursor-grabbing" aria-hidden="true">
          <g>
            {edges.map((e) => {
              const a = layout.byWfrn.get(e.from)
              const b = layout.byWfrn.get(e.to)
              if (!a || !b) return null
              const hot = focus === e.from || focus === e.to
              const x1 = a.x + NODE_W
              const y1 = a.y + NODE_H / 2
              const x2 = b.x
              const y2 = b.y + NODE_H / 2
              const mid = (x1 + x2) / 2
              return <path key={`${e.from}>${e.to}`} d={`M${x1},${y1} C${mid},${y1} ${mid},${y2} ${x2},${y2}`} fill="none" className={hot ? 'stroke-accent' : 'stroke-stroke'} strokeWidth={hot ? 2 : 1} />
            })}
          </g>
          <g>
            {layout.nodes.map((p) => {
              const shade = shadeOf(p.node, colourBy)
              const on = p.node.wfrn === focus
              return (
                <g key={p.node.wfrn} transform={`translate(${p.x},${p.y})`} data-wfrn={p.node.wfrn} data-focused={on || undefined} onClick={() => setFocus(p.node.wfrn)} onDoubleClick={() => {
                  const href = pathOfWfrn(p.node.wfrn, types)
                  if (href) navigate(href)
                }}>
                  <rect width={NODE_W} height={NODE_H} rx={8} className={cx('fill-surface', on ? 'stroke-accent' : near.has(p.node.wfrn) ? 'stroke-accent/60' : 'stroke-stroke', p.node.context && 'opacity-70')} strokeWidth={on ? 2.5 : 1} strokeDasharray={p.node.context ? '4 3' : undefined} />
                  <Dot tone={shade.tone} cx={14} cy={NODE_H / 2} />
                  <text x={28} y={17} className="fill-primary text-[12px] font-medium">
                    {(p.node.name ?? p.node.wfrn).slice(0, 20)}
                  </text>
                  <text x={28} y={31} className="fill-secondary text-[10px]">
                    {(types.find((t) => t.name === p.node.type)?.title.replace(/s$/, '') ?? p.node.type).slice(0, 24)}
                  </text>
                </g>
              )
            })}
          </g>
        </svg>
      </div>
      <p id="map-focus" role="status" aria-live="polite" className="mt-2 min-h-[1.25rem] text-caption text-secondary" data-testid="map-focus">
        {focused ? describe(focused.node) : 'Select the map, then move through it with the arrow keys.'}
      </p>
    </div>
  )
}

/* ---------------------------------------------------------------------------- the list */

function MapList({ nodes, edges, colourBy, nameOf, accountName }: { nodes: GraphNode[]; edges: { from: string; to: string }[]; colourBy: ColourBy; nameOf: Map<string, string>; accountName: (id: string) => string }) {
  const { types } = useConsole()
  const parents = useMemo(() => {
    const m = new Map<string, string[]>()
    for (const e of edges) m.set(e.to, [...(m.get(e.to) ?? []), e.from])
    return m
  }, [edges])
  const kids = useMemo(() => {
    const m = new Map<string, number>()
    for (const e of edges) m.set(e.from, (m.get(e.from) ?? 0) + 1)
    return m
  }, [edges])
  const rows = useMemo(() => [...nodes].sort((a, b) => a.type.localeCompare(b.type) || (a.name ?? a.wfrn).localeCompare(b.name ?? b.wfrn)), [nodes])
  const link = (wfrn: string) => {
    const href = pathOfWfrn(wfrn, types)
    const name = nameOf.get(wfrn) ?? wfrn
    return href ? (
      <Link key={wfrn} to={href} className="text-accent underline-offset-2 hover:underline">
        {name}
      </Link>
    ) : (
      <span key={wfrn}>{name}</span>
    )
  }
  const columns: TableColumn<GraphNode>[] = [
    { key: 'name', header: 'Resource', render: (n) => <span className="font-medium">{link(n.wfrn)}</span> },
    { key: 'type', header: 'Type', render: (n) => <span className="text-secondary">{types.find((t) => t.name === n.type)?.title ?? n.type}</span>, width: '10rem' },
    { key: 'state', header: 'Status', render: (n) => <LifecycleBadge status={n.state} lifecycle={n.state} />, width: '8rem' },
    {
      key: 'shade',
      header: COLOURS.find((c) => c.value === colourBy)?.label ?? 'Colour',
      render: (n) => {
        const s = shadeOf(n, colourBy)
        return (
          <span className="flex items-center gap-1.5 text-sm">
            <svg width="16" height="16" aria-hidden="true">
              <Dot tone={s.tone} cx={8} cy={8} />
            </svg>
            {s.words}
          </span>
        )
      },
      width: '11rem',
    },
    {
      key: 'from',
      header: 'Built from',
      render: (n) => {
        const p = parents.get(n.wfrn) ?? []
        return p.length ? <span className="flex flex-wrap gap-x-2 text-sm">{p.map(link)}</span> : <span className="text-tertiary">Nothing on the map</span>
      },
    },
    { key: 'kids', header: 'Built from it', render: (n) => <span className="tabular-nums">{kids.get(n.wfrn) ?? 0}</span>, width: '7rem' },
    { key: 'account', header: 'Account', render: (n) => <span className="text-secondary">{accountName(n.accountId)}</span>, width: '8rem' },
  ]
  return <Table<GraphNode> caption="The resources on the map and what each was built from" columns={columns} rows={rows} rowKey={(n) => n.wfrn} serverSort emptyIcon="git-branch" emptyTitle="Nothing to map" emptyMessage="No resource matches; pick another type." />
}
