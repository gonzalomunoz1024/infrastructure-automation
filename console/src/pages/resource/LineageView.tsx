import { EmptyState, Icon, SkeletonRows, plural } from '@iaf/ui'
import { LifecycleBadge, Section, StatusBadge } from '@iaf/console-kit'
import { pathOfWfrn, useConsole } from '../../app/console'
import { ExplainedError } from '../../app/commands'
import { Link, pathFor } from '../../app/router'
import { singularOf, type ConsoleType } from '../../lib/descriptors'
import { useQuery } from '../../lib/queries'
import { stateApi, type StateLineage, type StateRef } from '../../lib/state'

/*
 * Lineage on the Relationships tab: what made the resource (the image and anything else it was
 * built from, further back in columns to the left), the request and workflow that built it, and
 * what was built from it (to the right), all from the BFF's lineage and the resource's own
 * events. Each resource is a link to its page; another account's are counted, never named, and a
 * parent the console does not manage is said so. On a phone the columns stack.
 */

export interface LineageColumn {
  depth: number
  nodes: StateRef[]
}

/** Columns by hops from the resource: what it was built from at -1, -2…, what was built from it at 1, 2…; each node in its nearest column. */
export function lineageColumns(lineage: Pick<StateLineage, 'resource' | 'nodes' | 'edges'>): LineageColumn[] {
  const root = lineage.resource.wfrn
  const depth = new Map<string, number>([[root, 0]])
  const walk = (dir: 1 | -1) => {
    let frontier = [root]
    for (let d = 1; frontier.length && d <= 32; d++) {
      const next: string[] = []
      for (const w of frontier) {
        for (const e of lineage.edges) {
          const other = dir === 1 ? (e.from === w ? e.to : null) : e.to === w ? e.from : null
          if (!other || depth.has(other)) continue
          depth.set(other, dir * d)
          next.push(other)
        }
      }
      frontier = next
    }
  }
  walk(-1)
  walk(1)
  const byDepth = new Map<number, StateRef[]>()
  for (const n of lineage.nodes) {
    const d = depth.get(n.wfrn)
    if (d === undefined) continue
    byDepth.set(d, [...(byDepth.get(d) ?? []), n])
  }
  return [...byDepth.entries()].sort((a, b) => a[0] - b[0]).map(([d, nodes]) => ({ depth: d, nodes: nodes.sort((a, b) => (a.name ?? a.wfrn).localeCompare(b.name ?? b.wfrn)) }))
}

function columnTitle(depth: number): string {
  if (depth === 0) return 'This resource'
  if (depth === -1) return 'Built from'
  if (depth === 1) return 'Built from it'
  return depth < 0 ? `${-depth} steps back` : `${depth} steps on`
}

function NodeCard({ node, via, root, types }: { node: StateRef; via?: string | null; root: boolean; types: ConsoleType[] }) {
  const type = types.find((t) => t.name === node.type)
  const href = pathOfWfrn(node.wfrn, types)
  const name = node.name ?? node.wfrn
  return (
    <li className={root ? 'rounded-lg border border-accent bg-accent-tint/30 p-3' : 'rounded-lg border border-stroke-light p-3'} data-wfrn={node.wfrn}>
      <span className="flex items-start justify-between gap-2">
        <span className="min-w-0">
          {href && !root ? (
            <Link to={`${href}?tab=relationships`} className="block truncate font-medium text-accent underline-offset-2 hover:underline">
              {name}
            </Link>
          ) : (
            <span className="block truncate font-medium text-primary">{name}</span>
          )}
          <span className="block text-caption text-tertiary">{type ? singularOf(type.title) : node.type}</span>
        </span>
        <LifecycleBadge status={node.state} lifecycle={node.state} />
      </span>
      {via && <span className="mt-1.5 block text-caption text-secondary">through <span className="font-mono">{via}</span></span>}
      {node.drift && <span className="mt-1 block text-caption text-secondary">Drifted from what was asked for</span>}
    </li>
  )
}

export function LineageView({ type, wfrn }: { type: ConsoleType; wfrn: string }) {
  const { types } = useConsole()
  const noun = singularOf(type.title).toLowerCase()
  const lineage = useQuery(['console-lineage', type.name, wfrn], () => stateApi.lineage(type.name, wfrn))
  const events = useQuery(['console-activity', type.name, wfrn, 'events'], () => stateApi.eventsOf(type.name, wfrn))
  if (lineage.error) return <ExplainedError error={lineage.error} what={`Load what this ${noun} was built from`} onRetry={() => void lineage.refetch()} compact />
  if (lineage.loading || !lineage.data) return <SkeletonRows n={3} />
  const l = lineage.data
  const columns = lineageColumns(l)
  const viaOf = (w: string) => l.edges.find((e) => e.to === w && (e.from === l.resource.wfrn || columns.some((c) => c.nodes.some((n) => n.wfrn === e.from))))?.via
  const made = (events.data ?? []).find((e) => e.type === 'ResourceRequested' || e.type === 'Imported')
  const built = (events.data ?? []).find((e) => e.type === 'ProvisioningSucceeded')
  const jobId = typeof built?.data.jobId === 'string' ? built.data.jobId : typeof made?.data.jobId === 'string' ? made.data.jobId : undefined
  const workflow = typeof made?.data.workflow === 'string' ? made.data.workflow : typeof built?.data.workflow === 'string' ? built.data.workflow : undefined
  const images = l.builtFrom.filter((n) => n.type === 'image')
  const alone = columns.length === 1 && l.hidden === 0 && l.unmanaged.length === 0

  return (
    <div className="space-y-6">
      <Section title="What made it" description={`The request and workflow that built this ${noun}, and what it was built from.`}>
        <ul className="space-y-1.5 text-sm text-primary" aria-label="What made it">
          <li className="flex flex-wrap items-center gap-x-2">
            <Icon name="workflow" size={14} className="text-tertiary" />
            {workflow ? (
              <>
                Built by <span className="font-mono">{workflow}</span>
                {jobId && (
                  <>
                    in request{' '}
                    <Link to={pathFor('request', { id: jobId })} className="font-mono text-accent underline-offset-2 hover:underline">
                      {jobId}
                    </Link>
                  </>
                )}
                {built?.data.workflowVersion ? <span className="text-tertiary">v{String(built.data.workflowVersion)}</span> : null}
                {built && <StatusBadge status="succeeded" size="sm" />}
              </>
            ) : (
              <span className="text-secondary">{events.loading ? 'Reading its history…' : 'The console has no record of the request that built it.'}</span>
            )}
          </li>
          <li className="flex flex-wrap items-center gap-x-2">
            <Icon name="layers" size={14} className="text-tertiary" />
            {images.length ? <>From the image {images.map((i) => i.name).join(', ')}</> : l.builtFrom.length ? <>From {plural(l.builtFrom.length, 'resource')}, no image</> : <>Built from nothing the console manages</>}
          </li>
        </ul>
      </Section>

      <Section title="Lineage" description="What it was built from, to the left, and what was built from it, to the right. Each one links to its page.">
        {alone ? (
          <EmptyState compact icon="git-branch" title="Nothing linked" message={`This ${noun} was built from nothing the console manages, and nothing was built from it.`} />
        ) : (
          <div className="flex flex-col gap-4 md:flex-row md:items-start" data-testid="lineage">
            {columns.map((c, i) => (
              <div key={c.depth} className="min-w-0 md:flex-1">
                <h3 className="mb-2 flex items-center gap-1.5 text-caption font-medium text-secondary">
                  {i > 0 && <Icon name="arrow-right" size={12} className="hidden text-tertiary md:inline" />}
                  {columnTitle(c.depth)}
                </h3>
                <ul className="space-y-2" aria-label={columnTitle(c.depth)}>
                  {c.nodes.map((n) => (
                    <NodeCard key={n.wfrn} node={n} via={c.depth > 0 ? viaOf(n.wfrn) : undefined} root={c.depth === 0} types={types} />
                  ))}
                </ul>
              </div>
            ))}
          </div>
        )}
        {(l.hidden > 0 || l.unmanaged.length > 0 || l.truncated) && (
          <ul className="mt-3 space-y-1 text-caption text-secondary">
            {l.hidden > 0 && <li>{`${plural(l.hidden, 'more resource')} in accounts you cannot read ${l.hidden === 1 ? 'is' : 'are'} linked to these; the console counts them and never names them.`}</li>}
            {l.unmanaged.length > 0 && <li>{`${plural(l.unmanaged.length, 'parent')} the console does not manage: ${l.unmanaged.join(', ')}.`}</li>}
            {l.truncated && <li>The walk stopped at its limit; open a resource further out to see beyond it.</li>}
          </ul>
        )}
      </Section>
    </div>
  )
}
