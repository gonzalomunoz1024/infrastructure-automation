/**
 * n8n's node creator: the panel on the right with a search field, the categories of "What
 * happens next?", deeper lists (a provider's actions, the playbook library, the published
 * workflows), arrow keys and Enter, and the new step added where the "+" was (or after the
 * selection) and opened right away. The providers show their health band (GET /providers) and
 * the workflow's drafts beside them; "Connect a new system" opens the Provider sheet for the same
 * place, so the action it ends on is inserted there, but for cURL: an HTTP step there, open on its
 * Import cURL dialog.
 */
import { useEffect, useMemo, useRef, useState, type KeyboardEvent } from 'react'
import { cx, Icon, SkeletonRows, Tag, useDismiss } from '@iaf/ui'
import { HealthBand } from '../../components/HealthBand'
import { api } from '../../lib/api'
import { useQuery } from '../../lib/queries'
import type { HealthStatus } from '../../lib/types'
import type { InsertPoint } from '../model/edits'
import { findStepNode, triggerNodeId, type Graph } from '../model/graph'
import { useDesignerSelector, useDesignerStore } from '../model/store'
import { importCurlOnOpen } from '../ndv/StepParameters'
import { NodeGlyph } from '../canvas/glyphs'
import { revealIn } from '../scroll'
import { buildCatalog, ROOT_VIEW, searchCatalog, type CreatorItem } from './catalog'

/** Where the step lands, in words. */
export function placementOf(point: InsertPoint | null, graph: Graph): string {
  if (!point || point.kind === 'end') return 'Adds a step at the end of the workflow'
  if (point.kind === 'start') return point.flow === 'catch' ? 'Adds the first step of the error flow' : 'Adds the first step'
  const node = findStepNode(graph, point.step)
  const name = node?.data.step.name ?? point.step
  if (point.kind === 'before') return `Adds a step before ${name}`
  const handle = point.handle
  const out = node?.data.outputs.find((o) => o.id === handle)
  if (handle === 'main' || !out) return `Adds a step after ${name}`
  if (handle === 'done') return `Adds a step after the loop ${name}`
  if (handle === 'loop') return `Adds a step to the loop of ${name}`
  if (handle === 'undo') return `Adds a compensation step to ${name}`
  return `Adds a step on “${out.label || handle}” of ${name}`
}

export function NodeCreator() {
  const store = useDesignerStore()
  const creator = useDesignerSelector((s) => s.creator)
  const registry = useDesignerSelector((s) => s.registry)
  const loading = useDesignerSelector((s) => s.registryLoading)
  const doc = useDesignerSelector((s) => s.doc)
  const graph = useDesignerSelector((s) => s.graph)
  const drafts = useDesignerSelector((s) => s.providerDrafts)
  // the providers page's list, shared through the query cache: each provider's health band
  const summaries = useQuery('providers', () => api.providers())
  const health = useMemo(() => Object.fromEntries((summaries.data ?? []).map((p) => [p.name, p.health.status])) as Record<string, HealthStatus>, [summaries.data])
  const workflowName = doc.metadata?.name
  const catalog = useMemo(() => buildCatalog(registry, { metadata: { name: workflowName } }, { drafts, health }), [registry, workflowName, drafts, health])
  const [stack, setStack] = useState<string[]>([ROOT_VIEW])
  const [query, setQuery] = useState(creator?.query ?? '')
  const [active, setActive] = useState(0)
  const ref = useRef<HTMLElement>(null)
  const inputRef = useRef<HTMLInputElement>(null)
  const listRef = useRef<HTMLDivElement>(null)
  const close = useMemo(() => () => store.closeCreator(), [store])
  useDismiss(ref, true, close)

  const view = catalog.get(stack[stack.length - 1]) ?? catalog.get(ROOT_VIEW)
  const searching = query.trim().length > 0
  const sections = searching ? searchCatalog(catalog, query) : (view?.sections ?? [])
  const flat = sections.flatMap((s) => s.items)

  useEffect(() => {
    inputRef.current?.focus({ preventScroll: true })
  }, [stack])
  useEffect(() => {
    const list = listRef.current
    const row = list?.querySelector<HTMLElement>(`[data-index="${active}"]`)
    if (list && row) revealIn(list, row)
  }, [active])

  // the active row starts at the top of every new list: a view opened or left, a query typed
  const open = (id: string) => {
    setStack((s) => [...s, id])
    setQuery('')
    setActive(0)
  }
  const back = () => {
    setStack((s) => (s.length > 1 ? s.slice(0, -1) : s))
    setActive(0)
  }

  const choose = (item: CreatorItem | undefined) => {
    if (!item || item.disabled) return
    if (item.view) return open(item.view)
    const action = item.action
    if (!action) return
    if (action.kind === 'trigger') {
      const index = store.getState().doc.triggers?.length ?? 0
      store.addTrigger(action.trigger)
      store.openNdv(triggerNodeId(index))
      return
    }
    // the sheet closes the creator; the action it ends on is inserted where this step would have gone
    if (action.kind === 'provider-sheet') return store.openProviderSheet({ entry: action.entry, target: { kind: 'insert', point: creator?.point ?? null } })
    if (action.importCurl) {
      // the new step's parameters open on the Import cURL dialog
      const id = store.addStep(action.type, action.params ?? {}, creator?.point ?? null)
      importCurlOnOpen(id)
      store.setNdvTab('parameters')
      return
    }
    store.addStep(action.type, action.params ?? {}, creator?.point ?? null)
  }

  const onKeyDown = (e: KeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'ArrowDown') {
      e.preventDefault()
      setActive((a) => Math.min(a + 1, flat.length - 1))
    } else if (e.key === 'ArrowUp') {
      e.preventDefault()
      setActive((a) => Math.max(a - 1, 0))
    } else if (e.key === 'Enter') {
      e.preventDefault()
      choose(flat[active])
    } else if (e.key === 'ArrowRight' && flat[active]?.view && e.currentTarget.selectionStart === query.length) {
      e.preventDefault()
      open(flat[active].view as string)
    } else if ((e.key === 'ArrowLeft' || e.key === 'Backspace') && query === '' && stack.length > 1) {
      e.preventDefault()
      back()
    }
  }

  let index = -1
  const failed = registry?.failed ?? []
  const showSkeleton = loading && !searching && stack.length > 1

  return (
    <aside ref={ref} aria-label="Node creator" data-testid="node-creator" className="absolute inset-y-0 right-0 z-30 flex w-[400px] max-w-full flex-col border-l border-stroke-light bg-surface shadow-lg animate-slide-in-right">
      <div className="border-b border-stroke-light px-4 pb-3 pt-4">
        <div className="mb-3 flex items-center gap-2">
          {stack.length > 1 && !searching ? (
            <button type="button" onClick={back} aria-label="Back" title="Back" className="-ml-1 flex h-7 w-7 items-center justify-center rounded-full text-secondary hover:bg-surface-secondary hover:text-primary">
              <Icon name="arrow-left" size={16} />
            </button>
          ) : null}
          <div className="min-w-0 flex-1">
            <h2 className="truncate text-subheadline font-semibold text-primary">{searching ? 'Search results' : (view?.title ?? '')}</h2>
            <p className="truncate text-caption text-tertiary">{searching || stack.length === 1 ? placementOf(creator?.point ?? null, graph) : (view?.subtitle ?? '')}</p>
          </div>
          <button type="button" onClick={close} aria-label="Close the node creator" title="Close" className="flex h-7 w-7 items-center justify-center rounded-full text-tertiary hover:bg-surface-secondary hover:text-primary">
            <Icon name="close" size={15} />
          </button>
        </div>
        <div className="relative">
          <Icon name="search" size={15} className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-tertiary" />
          <input
            ref={inputRef}
            role="combobox"
            aria-expanded="true"
            aria-controls="node-creator-list"
            aria-activedescendant={flat[active] ? `node-creator-${active}` : undefined}
            aria-label="Search steps"
            placeholder="Search steps…"
            value={query}
            onChange={(e) => {
              setQuery(e.target.value)
              setActive(0)
            }}
            onKeyDown={onKeyDown}
            className="no-focus-ring w-full rounded-md border border-stroke-light bg-surface-secondary py-2 pl-9 pr-3 text-sm text-primary placeholder:text-tertiary focus:border-accent focus:outline-none focus:ring-2 focus:ring-accent/25"
          />
        </div>
      </div>
      <div ref={listRef} id="node-creator-list" role="listbox" aria-label={searching ? 'Search results' : (view?.title ?? 'Steps')} className="min-h-0 flex-1 overflow-y-auto px-2 py-2">
        {showSkeleton ? (
          <SkeletonRows n={5} rowClassName="h-12" className="space-y-2 p-2" />
        ) : flat.length === 0 ? (
          <div className="px-4 py-10 text-center">
            <p className="text-footnote font-medium text-primary">{searching ? `Nothing matches “${query}”` : 'Nothing here yet'}</p>
            <p className="mt-1 text-caption text-tertiary">{searching ? 'Try a provider, an action or a kind of step.' : 'The registry has nothing of this kind.'}</p>
          </div>
        ) : (
          sections.map((section, si) => (
            <div key={`${section.title ?? ''}-${si}`} role="group" aria-label={section.title} className={cx(si > 0 && 'mt-2 border-t border-stroke-light pt-2')}>
              {section.title && <p className="px-3 pb-1 pt-1.5 text-caption2 font-semibold uppercase tracking-wide text-tertiary">{section.title}</p>}
              {section.items.map((item) => {
                index += 1
                const i = index
                const selected = i === active
                return (
                  <div
                    key={item.id}
                    id={`node-creator-${i}`}
                    data-index={i}
                    role="option"
                    aria-selected={selected}
                    aria-disabled={item.disabled ? true : undefined}
                    onMouseEnter={() => setActive(i)}
                    onMouseDown={(e) => e.preventDefault()}
                    onClick={() => choose(item)}
                    className={cx('flex cursor-pointer items-center gap-3 rounded-md px-3 py-2', selected && 'bg-surface-secondary', item.disabled && 'cursor-default opacity-50')}
                  >
                    <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-sm border border-stroke-light bg-surface">
                      <NodeGlyph glyph={item.glyph} size={20} />
                    </span>
                    <span className="min-w-0 flex-1">
                      <span className="block truncate text-footnote font-medium text-primary">{item.title}</span>
                      {item.description && <span className="block truncate text-caption text-tertiary">{item.description}</span>}
                    </span>
                    {item.health && <HealthBand health={{ status: item.health }} className="shrink-0" />}
                    {item.tags?.map((t) => (
                      <Tag key={t.label} size="sm" tone={t.tone}>
                        {t.label}
                      </Tag>
                    ))}
                    {item.disabled && <span className="shrink-0 text-caption2 text-tertiary">{item.disabled}</span>}
                    {item.view && <Icon name="chevron-right" size={14} className="shrink-0 text-tertiary" />}
                  </div>
                )
              })}
            </div>
          ))
        )}
      </div>
      {failed.length > 0 && (
        <p className="border-t border-stroke-light px-4 py-2 text-caption2 text-warning" role="status">
          Could not load {failed.join(', ')} from the registry; those lists may be empty.
        </p>
      )}
    </aside>
  )
}
