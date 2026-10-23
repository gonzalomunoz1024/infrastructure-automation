import { useMemo, useState } from 'react'
import { Button, Checkbox, EmptyState, Input, PageHeader } from '@iaf/ui'
import { CursorPager, ErrorState, VirtualTable, WfrnText, type VirtualColumn } from '@iaf/console-kit'
import { MenuButton } from '../../app/MenuButton'
import { useConsole } from '../../app/console'
import { Link, navigate, pathFor, resourcePath, type PageProps } from '../../app/router'
import { singularOf } from '../../lib/descriptors'
import { useKeyedPaging, useKeyedState } from '../../lib/keyed'
import { useQuery } from '../../lib/queries'
import { newTagRow, rowErrors, tagsApi, tagsText, type TaggableResource, type TagRow } from '../../lib/tags'
import { ApplyTagsDialog } from './ApplyTagsDialog'
import { TagRows } from './TagRows'

/*
 * The Tag Editor (WP 4.3), as AWS's: the resources of the account and region of the types chosen
 * (every type when none is) that carry every tag named, in one table across types, and a change
 * of tags on the ones selected. The query lives in the page's address (`type`, `tag.<name>`, and
 * `q`, a filter of the loaded page by name, which the BFF does not search), so a search can be
 * shared and survives a reload, as a list's filters do.
 */

const PAGE_SIZE = 50
const noSelection = (): Set<string> => new Set()

/** The query the address holds: the types, the tags, the name filter and the page size. */
export function editorQueryOf(query: Record<string, string>): { types: string[]; tags: Record<string, string>; q: string; limit: number } {
  const types = (query.type ?? '')
    .split(',')
    .map((t) => t.trim())
    .filter(Boolean)
  const tags = Object.fromEntries(Object.entries(query).filter(([k]) => k.startsWith('tag.') && k.length > 4).map(([k, v]) => [k.slice(4), v]))
  const limit = Number(query.limit)
  return { types, tags, q: query.q ?? '', limit: [25, 50, 100, 200].includes(limit) ? limit : PAGE_SIZE }
}

/** The address of a query. */
export function editorPath(q: { types: string[]; tags: Record<string, string>; q?: string; limit?: number }): string {
  const query: Record<string, string | undefined> = { type: q.types.length ? q.types.join(',') : undefined, q: q.q || undefined, limit: q.limit && q.limit !== PAGE_SIZE ? String(q.limit) : undefined }
  for (const [k, v] of Object.entries(q.tags)) query[`tag.${k}`] = v
  return pathFor('tag-editor', {}, query)
}

export default function TagEditorPage({ route }: PageProps) {
  const { types: catalogue, account, region } = useConsole()
  const applied = editorQueryOf(route.query)
  const listKey = JSON.stringify([account.id, region.name, applied.types, applied.tags, applied.limit])
  const [chosen, setChosen] = useKeyedState<string[]>(`${listKey}:types`, () => applied.types)
  const [rows, setRows] = useKeyedState<TagRow[]>(`${listKey}:rows`, () => (Object.keys(applied.tags).length ? Object.entries(applied.tags).map(([k, v]) => newTagRow(k, v)) : [newTagRow()]))
  const [dialog, setDialog] = useState(false)
  const paging = useKeyedPaging(listKey)
  const [selected, setSelected] = useKeyedState(`${listKey}|${paging.after ?? ''}`, noSelection)
  const list = useQuery(['tags-resources', listKey, paging.after ?? null], () => tagsApi.resources({ types: applied.types, tags: applied.tags, limit: applied.limit, after: paging.after }))
  const titleOf = useMemo(() => new Map(catalogue.map((t) => [t.name, t])), [catalogue])
  const loaded = list.data?.items ?? []
  const needle = applied.q.trim().toLowerCase()
  const shown = needle ? loaded.filter((r) => (r.name ?? '').toLowerCase().includes(needle)) : loaded
  const selectedRows = shown.filter((r) => selected.has(r.wfrn))
  const untaggable = selectedRows.filter((r) => !r.canTag).length
  const errors = rowErrors(rows, [])

  const find = () => {
    if (Object.keys(errors).length) return
    const tags = Object.fromEntries(rows.filter((r) => r.key.trim()).map((r) => [r.key.trim(), r.value]))
    navigate(editorPath({ types: chosen, tags, q: applied.q, limit: applied.limit }), { replace: true })
  }
  const setFilter = (q: string) => navigate(editorPath({ ...applied, q }), { replace: true })

  const columns: VirtualColumn<TaggableResource>[] = [
    {
      key: 'name',
      header: 'Name',
      width: 'minmax(10rem, 1.2fr)',
      accessor: (r) => r.name ?? r.wfrn,
      render: (r) => {
        const t = titleOf.get(r.type)
        return t ? (
          <Link to={resourcePath(t.service, t.name, r.wfrn)} className="truncate font-medium text-accent hover:opacity-70" onClick={(e) => e.stopPropagation()}>
            {r.name ?? r.wfrn}
          </Link>
        ) : (
          <span className="truncate">{r.name ?? r.wfrn}</span>
        )
      },
    },
    { key: 'type', header: 'Type', width: 'minmax(8rem, 0.8fr)', accessor: (r) => singularOf(titleOf.get(r.type)?.title ?? r.type) },
    { key: 'wfrn', header: 'wfrn', width: 'minmax(14rem, 1.6fr)', minRem: 14, accessor: (r) => r.wfrn, render: (r) => <WfrnText wfrn={r.wfrn} max={36} /> },
    {
      key: 'tags',
      header: 'Tags',
      width: 'minmax(14rem, 2fr)',
      minRem: 14,
      accessor: (r) => tagsText(r.tags),
      render: (r) => (
        <span className="truncate font-mono text-caption text-secondary" title={tagsText(r.tags)}>
          {tagsText(r.tags) || 'none'}
        </span>
      ),
    },
  ]

  const typeLabel = chosen.length === 0 ? 'Types: every type' : chosen.length === 1 ? `Types: ${titleOf.get(chosen[0])?.title ?? chosen[0]}` : `Types: ${chosen.length} chosen`

  return (
    <>
      <PageHeader title="Tag Editor" subtitle="Find resources of every type by their tags, and change the tags of many at once." />

      <form
        aria-label="Find resources"
        className="mb-5 space-y-4 rounded-lg border border-stroke-light p-4"
        noValidate
        onSubmit={(e) => {
          e.preventDefault()
          find()
        }}
      >
        <div className="flex flex-wrap items-center gap-2">
          <MenuButton
            label={typeLabel}
            icon="layers"
            role="dialog"
            width="w-72"
            content={
              <fieldset className="px-3 py-2">
                <legend className="text-sm font-semibold text-primary">Resource types</legend>
                <p className="mt-0.5 text-caption text-tertiary">None chosen finds every type.</p>
                <div className="mt-3 space-y-2.5">
                  {catalogue.map((t) => (
                    <Checkbox key={t.name} checked={chosen.includes(t.name)} label={t.title} onChange={(on) => setChosen((was) => (on ? [...was, t.name] : was.filter((x) => x !== t.name)))} />
                  ))}
                </div>
              </fieldset>
            }
          />
          <span className="min-w-0 text-caption text-tertiary">{chosen.length ? chosen.map((n) => titleOf.get(n)?.title ?? n).join(', ') : 'Every type of the account in this region'}</span>
        </div>
        <div>
          <p className="mb-2 text-sm font-medium text-primary">Tags they carry</p>
          <TagRows rows={rows} onChange={(r) => setRows(() => r)} errors={errors} idPrefix="tag-editor" addLabel="Add a tag to match" />
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <Button type="submit" variant="primary" icon="search">
            Find resources
          </Button>
          {(applied.types.length > 0 || Object.keys(applied.tags).length > 0) && (
            <Button variant="ghost" onClick={() => navigate(pathFor('tag-editor'), { replace: true })}>
              Clear the query
            </Button>
          )}
        </div>
      </form>

      <div className="mb-3 max-w-md">
        <label htmlFor="tag-editor-filter" className="text-caption font-medium text-secondary">
          Filter this page by name
        </label>
        <Input id="tag-editor-filter" size="sm" leading="search" className="mt-1" value={applied.q} onChange={(e) => setFilter(e.target.value)} placeholder="Part of a name" aria-describedby="tag-editor-filter-hint" />
        <p id="tag-editor-filter-hint" className="mt-1 text-caption text-tertiary">
          Filters the rows loaded on this page only; the search itself goes by type and tags.
        </p>
      </div>

      {selectedRows.length > 0 && (
        <div role="region" aria-label="Selection" className="mb-2 flex flex-wrap items-center gap-2 rounded-md bg-accent-tint px-3 py-1.5 text-sm text-primary">
          <span className="font-medium" aria-live="polite">
            {`${selectedRows.length} selected`}
          </span>
          <Button size="sm" variant="primary" icon="tag" onClick={() => setDialog(true)}>
            {`Manage tags of ${selectedRows.length}`}
          </Button>
          <Button size="sm" variant="ghost" onClick={() => setSelected(noSelection)}>
            Clear selection
          </Button>
          {untaggable > 0 && <span className="text-caption text-secondary">{`You may not tag ${untaggable} of them; they will be refused.`}</span>}
        </div>
      )}

      {list.error && !list.data ? (
        <ErrorState error={list.error} title="Could not find the resources" onRetry={list.refetch} />
      ) : (
        <VirtualTable<TaggableResource>
          columns={columns}
          rows={shown}
          rowKey={(r) => r.wfrn}
          rowLabel={(r) => r.name ?? r.wfrn}
          label="Resources found"
          loading={list.loading}
          selectable
          selectedKeys={selected}
          onSelectionChange={(next) => setSelected(() => next)}
          maxHeight="max(18rem, calc(100dvh - 30rem))"
          empty={<EmptyState compact icon="tag" title="No resources match" message={needle ? 'No row on this page has that name; clear the filter or go to another page.' : 'No resource of these types carries every tag named. Change the types or the tags.'} />}
          footer={
            <CursorPager
              count={loaded.length}
              hasNext={!!list.data?.next}
              hasPrevious={paging.hasPrevious}
              onNext={() => list.data?.next && paging.next(list.data.next)}
              onPrevious={paging.previous}
              pageSize={applied.limit}
              onPageSizeChange={(size) => navigate(editorPath({ ...applied, limit: size }), { replace: true })}
              page={paging.page}
              loading={list.fetching}
              noun="resource"
            />
          }
        />
      )}

      {dialog && (
        <ApplyTagsDialog
          resources={selectedRows.map((r) => ({ wfrn: r.wfrn, type: r.type, name: r.name, tags: r.tags }))}
          onClose={() => setDialog(false)}
          onApplied={(result) => {
            // what took the change leaves the selection; what did not stays, to try again
            const done = new Set(result.applied.map((a) => a.wfrn))
            setSelected((was) => new Set([...was].filter((k) => !done.has(k))))
          }}
        />
      )}
    </>
  )
}
