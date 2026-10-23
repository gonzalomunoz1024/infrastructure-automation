import { useEffect, useRef, useState, type KeyboardEvent, type PointerEvent } from 'react'
import { Button, EmptyState, IconButton, Icon, Input, PageHeader, Segmented, Skeleton, cx } from '@iaf/ui'
import { When } from '@iaf/console-kit'
import { useConsole } from '../../app/console'
import { ExplainedError, FailureNote, useCommand } from '../../app/commands'
import { navigate, pathFor, type PageProps } from '../../app/router'
import { isApiError } from '../../lib/api'
import { DASHBOARD_QUERIES, HEIGHTS, MAX_NAME, MAX_WIDGETS, WIDTHS, accessWords, dashboardsApi, heightWords, moveTarget, moveWidget, resizeWidget, sharingWords, sizeWords, widthWords, type Widget, type WidgetHeight, type WidgetWidth } from '../../lib/dashboards'
import { DEFAULT_RANGE, RANGES, rangeOf } from '../../lib/monitoring'
import { useQuery } from '../../lib/queries'
import { AddWidgetDialog, DeleteDashboardDialog, PermitButton, ShareDialog } from './DashboardDialogs'
import { WidgetBody } from './WidgetBody'

/*
 * One dashboard (WP 9.19), like a CloudWatch dashboard: a grid of widgets over one time range
 * for the whole board (kept in the address), read again every minute. Edit (`?edit=1`) changes a
 * copy in the page: add a widget, move one (drag its handle, or focus the handle and press the
 * arrows, Home or End), resize it a step at a time (its buttons, or Shift and the arrows), remove
 * it; Save sends the copy with the version it started from, and someone else's save in between
 * is said in words and can be overwritten knowingly. At phone width the widgets stack.
 */

const SPAN: Record<WidgetWidth, string> = {
  3: 'md:col-span-6 xl:col-span-3',
  4: 'md:col-span-6 xl:col-span-4',
  6: 'md:col-span-6',
  8: 'md:col-span-12 xl:col-span-8',
  12: 'md:col-span-12',
}
const ROWS: Record<WidgetHeight, string> = { 1: 'row-span-1', 2: 'row-span-2', 3: 'row-span-3', 4: 'row-span-4' }

interface Draft {
  version: number
  name: string
  widgets: Widget[]
}

export default function DashboardPage({ route }: PageProps) {
  const { account } = useConsole()
  const id = route.params.id
  const q = useQuery(['dashboard', id], () => dashboardsApi.get(id), { refetchInterval: 60_000 })
  const d = q.data
  const range = rangeOf(route.query.range)
  const editing = route.query.edit === '1' && !!d?.can.edit.allowed
  const [draft, setDraft] = useState<Draft | null>(null)
  const [sharing, setSharing] = useState(false)
  const [deleting, setDeleting] = useState(false)
  const cmd = useCommand()
  const go = (query: Record<string, string | undefined>) => navigate(pathFor('dashboard', { id }, { ...route.query, ...query }, { accountId: route.accountId }), { replace: true })

  if (q.error && !d) {
    const gone = isApiError(q.error) && q.error.status === 404
    return gone ? <EmptyState icon="grid" title="No such dashboard" message="It was deleted, or nobody shared it with you." action={<Button size="sm" onClick={() => navigate(pathFor('dashboards'))}>Open the dashboards</Button>} /> : <ExplainedError error={q.error} what="Open the dashboard" onRetry={() => void q.refetch()} />
  }
  if (!d) {
    return (
      <div role="status" aria-label="Loading the dashboard" className="space-y-4">
        <Skeleton className="h-10 w-64" />
        <Skeleton className="h-64 w-full" />
      </div>
    )
  }

  const current: Draft = editing ? (draft ?? { version: d.version, name: d.name, widgets: d.widgets }) : { version: d.version, name: d.name, widgets: d.widgets }
  const change = (next: Partial<Draft>) => setDraft({ ...current, ...next })
  const conflict = cmd.failure?.code === 'dashboard.changed'

  const startEdit = () => {
    setDraft(null)
    cmd.clear()
    go({ edit: '1' })
  }
  const stopEdit = () => {
    setDraft(null)
    cmd.clear()
    go({ edit: undefined })
  }
  const save = async (version = current.version) => {
    const saved = await cmd.run('Save the dashboard', () => dashboardsApi.update(id, { name: current.name.trim(), ...(d.description ? { description: d.description } : {}), widgets: current.widgets }, version), { invalidates: DASHBOARD_QUERIES, quiet: true, done: (x) => ({ title: 'Dashboard saved', message: `${x.name} has ${x.widgets.length} ${x.widgets.length === 1 ? 'widget' : 'widgets'}.` }) })
    if (saved) stopEdit()
    else void q.refetch()
  }
  const viewOnly = d.can.edit.why

  return (
    <>
      <PageHeader
        title={d.name}
        subtitle={
          <>
            {d.description && <span className="block">{d.description}</span>}
            <span className="block">
              {accessWords(d)}. {d.access === 'owner' ? `Who sees it: ${sharingWords(d.sharing, account.name).toLowerCase()}.` : null} Changed <When at={d.updatedAt} />
              {d.updatedBy ? ` by ${d.updatedBy.name}` : ''}.
            </span>
          </>
        }
        actions={
          editing ? (
            <div className="flex flex-wrap items-center gap-2">
              <Button size="sm" variant="ghost" onClick={stopEdit}>
                Cancel
              </Button>
              <Button size="sm" variant="primary" icon="check" loading={cmd.busy} disabled={!current.name.trim() || current.name.length > MAX_NAME} onClick={() => void save()}>
                Save dashboard
              </Button>
            </div>
          ) : (
            <div className="flex flex-wrap items-center gap-2">
              <PermitButton permit={d.can.edit} what={`Edit ${d.name}`} verb="edit" icon="edit" onClick={startEdit}>
                Edit
              </PermitButton>
              <PermitButton permit={d.can.share} what={`Share ${d.name}`} verb="share" icon="users" onClick={() => setSharing(true)}>
                Share
              </PermitButton>
              <PermitButton permit={d.can.delete} what={`Delete ${d.name}`} verb="delete" icon="trash" variant="ghost" onClick={() => setDeleting(true)}>
                Delete
              </PermitButton>
            </div>
          )
        }
      />
      {!editing && viewOnly && (
        <p className="mb-4 flex items-start gap-2 rounded-md bg-surface-secondary px-3 py-2 text-caption text-secondary" data-view-only>
          <Icon name="eye" size={14} className="mt-0.5 shrink-0" />
          <span>You can view this dashboard. {viewOnly}</span>
        </p>
      )}

      <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
        <Segmented ariaLabel="Time range" size="sm" value={range.id} onChange={(v) => go({ range: v === DEFAULT_RANGE ? undefined : v })} options={RANGES.map((r) => ({ value: r.id, label: r.label }))} />
        {editing && (
          <div className="flex flex-wrap items-end gap-2">
            <label className="flex items-center gap-2 text-caption text-secondary" htmlFor="dashboard-name-edit">
              Name
              <Input id="dashboard-name-edit" className="w-56" value={current.name} onChange={(e) => change({ name: e.target.value })} invalid={!current.name.trim() || current.name.length > MAX_NAME} />
            </label>
            <AddWidget disabled={current.widgets.length >= MAX_WIDGETS} onAdd={(w) => change({ widgets: [...current.widgets, w] })} />
          </div>
        )}
      </div>

      {editing && (
        <div className="mb-4 space-y-2">
          <p className="text-caption text-secondary">Drag a widget by its handle, or focus the handle and press the arrow keys to move it, Home or End to send it first or last, and Shift with the arrows to resize it.</p>
          {conflict && (
            <div className="flex flex-wrap items-center gap-2 rounded-md border border-warning/40 bg-warning-tint px-3 py-2 text-caption text-primary" role="alert">
              <span className="min-w-0 flex-1">{cmd.failure?.message} Save again to replace that version with yours, or cancel to see theirs.</span>
              <Button size="sm" onClick={() => void save(d.version)}>
                Save mine over it
              </Button>
            </div>
          )}
          {!conflict && <FailureNote failure={cmd.failure} compact />}
        </div>
      )}

      <Board widgets={current.widgets} editing={editing} range={range} onChange={(widgets) => change({ widgets })} />

      {sharing && <ShareDialog dashboard={d} onClose={() => setSharing(false)} />}
      {deleting && <DeleteDashboardDialog dashboard={d} onClose={() => setDeleting(false)} />}
    </>
  )
}

function AddWidget({ onAdd, disabled }: { onAdd: (w: Widget) => void; disabled: boolean }) {
  const [open, setOpen] = useState(false)
  return (
    <>
      <Button size="sm" icon="plus" onClick={() => setOpen(true)} disabled={disabled}>
        Add widget
      </Button>
      {open && (
        <AddWidgetDialog
          onClose={() => setOpen(false)}
          onAdd={(w) => {
            onAdd(w)
            setOpen(false)
          }}
        />
      )}
    </>
  )
}

interface BoardProps {
  widgets: Widget[]
  editing: boolean
  range: ReturnType<typeof rangeOf>
  onChange: (widgets: Widget[]) => void
}

function Board({ widgets, editing, range, onChange }: BoardProps) {
  const [said, setSaid] = useState('')
  const [focusId, setFocusId] = useState<string | null>(null)
  const [drag, setDrag] = useState<{ id: string; over: string | null } | null>(null)
  const handles = useRef(new Map<string, HTMLButtonElement>())

  // a moved widget's handle keeps the focus, wherever the widget went
  useEffect(() => {
    if (!focusId) return
    const el = handles.current.get(focusId)
    if (el && document.activeElement !== el) el.focus()
  }, [widgets, focusId])

  const moveTo = (index: number, to: number) => {
    const w = widgets[index]
    if (to === index) return
    onChange(moveWidget(widgets, index, to))
    setFocusId(w.widgetId)
    setSaid(`${w.title} moved to place ${to + 1} of ${widgets.length}.`)
  }
  const resize = (index: number, change: { width?: 1 | -1; height?: 1 | -1 }) => {
    const next = resizeWidget(widgets, index, change)
    onChange(next)
    setSaid(`${widgets[index].title} is now ${sizeWords(next[index])}.`)
  }
  const remove = (index: number) => {
    const w = widgets[index]
    onChange(widgets.filter((_, i) => i !== index))
    setFocusId(null)
    setSaid(`${w.title} removed. Save the dashboard to keep the change, or cancel.`)
  }
  const onHandleKey = (e: KeyboardEvent<HTMLButtonElement>, index: number) => {
    if (e.shiftKey) {
      const change = e.key === 'ArrowRight' ? { width: 1 as const } : e.key === 'ArrowLeft' ? { width: -1 as const } : e.key === 'ArrowDown' ? { height: 1 as const } : e.key === 'ArrowUp' ? { height: -1 as const } : null
      if (!change) return
      e.preventDefault()
      resize(index, change)
      return
    }
    const to = moveTarget(e.key, index, widgets.length)
    if (to === null) return
    e.preventDefault()
    moveTo(index, to)
  }
  const overOf = (e: PointerEvent) => (document.elementFromPoint(e.clientX, e.clientY)?.closest('[data-widget]') as HTMLElement | null)?.dataset.widget ?? null

  if (widgets.length === 0) {
    return (
      <EmptyState
        icon="grid"
        title="No widgets yet"
        message={editing ? 'Add a widget: a metric chart of a resource, a count of a type’s resources, the drift findings, the open requests or the alarms.' : 'Someone who may edit this dashboard can add widgets to it.'}
      />
    )
  }

  return (
    <>
      <p className="sr-only" role="status" aria-live="polite">
        {said}
      </p>
      <ol className="grid auto-rows-[minmax(7.5rem,auto)] grid-cols-1 gap-4 md:grid-cols-12" aria-label="Widgets">
        {widgets.map((w, i) => (
          <li
            key={w.widgetId}
            data-widget={w.widgetId}
            className={cx('flex min-w-0 flex-col rounded-lg border bg-surface p-4', SPAN[w.width], ROWS[w.height], drag?.over === w.widgetId && drag.id !== w.widgetId ? 'border-accent' : 'border-stroke-light', drag?.id === w.widgetId && 'opacity-60')}
          >
            <div className="mb-2 flex min-w-0 items-start justify-between gap-2">
              <h2 className="min-w-0 truncate text-subheadline font-semibold text-primary">{w.title}</h2>
              {editing && (
                <button
                  type="button"
                  ref={(el) => {
                    if (el) handles.current.set(w.widgetId, el)
                    else handles.current.delete(w.widgetId)
                  }}
                  data-move={w.widgetId}
                  aria-label={`Move ${w.title}, place ${i + 1} of ${widgets.length}, ${sizeWords(w)}`}
                  aria-describedby="dashboard-move-hint"
                  onKeyDown={(e) => onHandleKey(e, i)}
                  onFocus={() => setFocusId(w.widgetId)}
                  onPointerDown={(e) => {
                    e.currentTarget.setPointerCapture(e.pointerId)
                    setDrag({ id: w.widgetId, over: null })
                  }}
                  onPointerMove={(e) => drag && setDrag({ id: drag.id, over: overOf(e) })}
                  onPointerUp={(e) => {
                    const over = overOf(e)
                    setDrag(null)
                    const to = widgets.findIndex((x) => x.widgetId === over)
                    if (to >= 0) moveTo(i, to)
                  }}
                  onPointerCancel={() => setDrag(null)}
                  className="flex h-7 w-7 shrink-0 cursor-grab touch-none items-center justify-center rounded-md text-secondary hover:bg-surface-secondary hover:text-primary focus-visible:outline-2 focus-visible:outline-accent"
                >
                  <Icon name="drag-handle" size={16} />
                </button>
              )}
            </div>
            {editing ? (
              <EditControls widget={w} onResize={(c) => resize(i, c)} onRemove={() => remove(i)} />
            ) : (
              <div className="min-w-0 flex-1">
                <WidgetBody widget={w} range={range} />
              </div>
            )}
          </li>
        ))}
      </ol>
      {editing && (
        <p id="dashboard-move-hint" className="sr-only">
          Arrow keys move it a place, Home and End to the start or the end; Shift with the arrows makes it wider, narrower, taller or shorter.
        </p>
      )}
    </>
  )
}

function EditControls({ widget, onResize, onRemove }: { widget: Widget; onResize: (c: { width?: 1 | -1; height?: 1 | -1 }) => void; onRemove: () => void }) {
  const wi = WIDTHS.indexOf(widget.width)
  const hi = HEIGHTS.indexOf(widget.height)
  return (
    <div className="flex flex-1 flex-col justify-between gap-3 rounded-md border border-dashed border-stroke p-3">
      <p className="text-caption text-secondary">{sizeWords(widget)}</p>
      <div className="flex flex-wrap items-center gap-x-4 gap-y-2 text-caption text-secondary">
        <span className="flex items-center gap-1">
          Width
          <IconButton size="sm" label={`Make ${widget.title} narrower than ${widthWords(widget.width)}`} onClick={() => onResize({ width: -1 })} disabled={wi <= 0}>
            <Icon name="minus" size={13} />
          </IconButton>
          <IconButton size="sm" label={`Make ${widget.title} wider than ${widthWords(widget.width)}`} onClick={() => onResize({ width: 1 })} disabled={wi >= WIDTHS.length - 1}>
            <Icon name="plus" size={13} />
          </IconButton>
        </span>
        <span className="flex items-center gap-1">
          Height
          <IconButton size="sm" label={`Make ${widget.title} shorter than ${heightWords(widget.height)}`} onClick={() => onResize({ height: -1 })} disabled={hi <= 0}>
            <Icon name="minus" size={13} />
          </IconButton>
          <IconButton size="sm" label={`Make ${widget.title} taller than ${heightWords(widget.height)}`} onClick={() => onResize({ height: 1 })} disabled={hi >= HEIGHTS.length - 1}>
            <Icon name="plus" size={13} />
          </IconButton>
        </span>
        <Button size="sm" variant="ghost" icon="trash" onClick={onRemove}>
          Remove<span className="sr-only"> {widget.title}</span>
        </Button>
      </div>
    </div>
  )
}
