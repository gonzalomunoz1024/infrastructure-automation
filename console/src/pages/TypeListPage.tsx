import { useCallback, useEffect, useMemo } from 'react'
import { Button, EmptyState, Icon, IconButton, PageHeader, cx, plural, useToast } from '@iaf/ui'
import { CursorPager, DotTag, ErrorState, LifecycleBadge, PropertyFilterBar, VirtualTable, WfrnText, formatField, type VirtualColumn, type VirtualSort } from '@iaf/console-kit'
import { useConsole, useRouteType } from '../app/console'
import { ActionButton, useRefusal } from '../app/Refusal'
import { navigate, pathFor, resourcePath, type PageProps, type Route } from '../app/router'
import { bff } from '../lib/bff'
import { csvFileName, downloadCsv, toCsv } from '../lib/csv'
import { capitalise, purposeOf, singularOf, type ConsoleType } from '../lib/descriptors'
import { useKeyedPaging, useKeyedState } from '../lib/keyed'
import { LIST_QUERY_KEYS, encodeSort, listStateFromQuery, type ListState } from '../lib/listState'
import { denialOf, typeKey as typeKeyOf, type Decision } from '../lib/permissions'
import { columnsOf, loadPreferences, usePreferenceWriter, usePreferences, viewsOf, withColumns } from '../lib/preferences'
import { useQuery } from '../lib/queries'
import { pushRecent } from '../lib/recent'
import { DEFAULT_PAGE_SIZE, PAGE_SIZES, SORTABLE, countLabel, csvRowsOf, filterPropertiesOf, listColumnsOf, listQueryOf, nameOf, normaliseTokens, serverFiltersOf, sortOrDefault, tagKeysOfColumns, tagOfProperty, visibleColumns, type ListColumn } from '../lib/resourceList'
import type { ConsoleResource } from '../lib/types'
import { ColumnsMenu } from './list/ColumnsMenu'
import { ViewsMenu, describeTokenWith } from './list/ViewsMenu'
import { MissingType, Stamp } from './parts'

/*
 * The list of one resource type, from the BFF (GET /console/v1/r/{type}), for a million rows: the
 * server filters, sorts and pages (cursor), the table draws only the rows on screen, and the
 * count comes from the BFF's counts ("1,000+" past its cap). What the list shows lives in the
 * page's query, so a filtered list is a link; saved views and the column choice are the person's
 * preferences. Rows can be selected and exported as CSV.
 */

export default function TypeListPage({ route }: PageProps) {
  const { loading, error, refetch, decide, account, region } = useConsole()
  const type = useRouteType(route.params.service, route.params.type)
  if (!type) return <MissingType name={route.params.type} service={route.params.service} loading={loading} error={error} refetch={refetch} />
  return <TypeList key={`${type.service}/${type.name}/${account.id}/${region.name}`} type={type} route={route} create={decide(type, 'create')} />
}

const noSelection = (): Set<string> => new Set()

function TypeList({ type, route, create }: { type: ConsoleType; route: Route; create: Decision }) {
  const { session, account, region } = useConsole()
  const toast = useToast()
  const refusal = useRefusal()
  const owner = session.user.userId
  const prefs = usePreferences(owner)
  const write = usePreferenceWriter()
  const key = typeKeyOf(type.service, type.name)

  useEffect(() => {
    void loadPreferences(owner)
  }, [owner])
  useEffect(() => {
    pushRecent({ kind: 'type', href: pathFor('type', { service: type.service, type: type.name }), label: type.title, hint: `${capitalise(type.service)} · resource type` })
  }, [type])

  /* the list's state is the page's query */
  const fromQuery = useMemo(() => listStateFromQuery(route.query, PAGE_SIZES), [route.query])
  const tokens = useMemo(() => normaliseTokens(fromQuery.tokens), [fromQuery.tokens])
  const sort = useMemo(() => sortOrDefault(type, fromQuery.sort), [type, fromQuery.sort])
  const pageSize = fromQuery.pageSize ?? DEFAULT_PAGE_SIZE
  const state = useMemo<ListState>(() => ({ tokens, sort, pageSize, view: fromQuery.view }), [tokens, sort, pageSize, fromQuery.view])
  const apply = useCallback(
    (next: ListState) => {
      const keep = Object.fromEntries(Object.entries(route.query).filter(([k]) => !(LIST_QUERY_KEYS as readonly string[]).includes(k)))
      navigate(pathFor('type', { service: type.service, type: type.name }, { ...keep, ...listQueryOf(next, type) }), { replace: true })
    },
    [route.query, type],
  )

  /* one page from the server, and the count */
  const filters = useMemo(() => serverFiltersOf(tokens), [tokens])
  const listKey = JSON.stringify([account.id, region.name, type.name, filters, encodeSort(sort), pageSize])
  const paging = useKeyedPaging(listKey)
  const list = useQuery(['console-resources', listKey, paging.after ?? null], () => bff.resources.list(type.name, { ...filters, sort: encodeSort(sort), limit: pageSize, after: paging.after }))
  const counts = useQuery(['console-resource-counts', account.id, region.name, type.name, filters], () => bff.resources.counts(type.name, filters))
  const rows = useMemo(() => list.data?.items ?? [], [list.data])
  const [selected, setSelected] = useKeyedState(`${listKey}|${paging.after ?? ''}`, noSelection)

  /* columns: the person's choice, else the type's */
  const chosen = columnsOf(prefs, key)
  const tagKeys = useMemo(() => {
    const keys = new Set<string>(tagKeysOfColumns(chosen))
    for (const r of rows) for (const k of Object.keys(r.tags ?? {})) keys.add(k)
    for (const t of tokens) {
      const tag = tagOfProperty(t.property)
      if (tag) keys.add(tag)
    }
    return [...keys]
  }, [rows, tokens, chosen])
  const allColumns = useMemo(() => listColumnsOf(type, tagKeys), [type, tagKeys])
  const defaults = useMemo(() => type.columns.map((c) => c.key), [type])
  const shown = useMemo(() => visibleColumns(allColumns, defaults, chosen), [allColumns, defaults, chosen])
  const columns = useMemo(() => shown.map((c) => tableColumn(c, type)), [shown, type])
  const properties = useMemo(() => filterPropertiesOf(rows, tagKeys), [rows, tagKeys])
  const describeToken = useMemo(() => describeTokenWith((p) => properties.find((x) => x.key === p)?.label ?? p), [properties])

  const lower = type.title.toLowerCase()
  const noun = singularOf(type.title).toLowerCase()
  const createTitle = type.create?.title ?? `Create ${noun}`
  const denied = denialOf(list.error)

  const countText = counts.data
    ? `${countLabel(counts.data.total, counts.data.cappedAt)} ${counts.data.total === 1 ? noun : lower}${tokens.length ? (counts.data.total === 1 ? ' matches' : ' match') : ''}`
    : list.data
      ? `${plural(rows.length, noun)} on this page`
      : undefined

  const exportRows = (subset: readonly ConsoleResource[]) => {
    downloadCsv(csvFileName(type.title), toCsv(csvRowsOf(subset, shown)))
    toast.success('Exported as CSV', `${plural(subset.length, noun)}, with the ${shown.length} columns on screen.`)
  }
  const selectedRows = rows.filter((r) => selected.has(r.wfrn))

  const empty = tokens.length ? (
    <EmptyState
      compact
      icon="filter"
      title={`No ${lower} match these filters`}
      message="Loosen a filter, or clear them all."
      action={
        <Button size="sm" onClick={() => apply({ ...state, tokens: [] })}>
          Clear filters
        </Button>
      }
    />
  ) : (
    <EmptyState
      compact
      icon={type.icon}
      title={`No ${lower} yet`}
      message={purposeOf(type)}
      action={
        <ActionButton decision={create} what={createTitle} phase={3} size="sm" variant="primary" icon="plus">
          Create your first one
        </ActionButton>
      }
    />
  )

  return (
    <>
      <PageHeader
        eyebrow={capitalise(type.service)}
        title={type.title}
        subtitle={purposeOf(type)}
        actions={
          <>
            <IconButton
              label="Refresh the list"
              onClick={() => {
                void list.refetch()
                void counts.refetch()
              }}
            >
              <Icon name="refresh" size={16} className={cx(list.fetching && 'animate-spin')} />
            </IconButton>
            <Button icon="download" disabled={rows.length === 0} onClick={() => exportRows(rows)}>
              Export page
            </Button>
            <ActionButton decision={create} what={createTitle} phase={3} variant="primary" icon="plus">
              {createTitle}
            </ActionButton>
          </>
        }
      />

      <div className="mb-3 flex flex-wrap items-start gap-2">
        <ViewsMenu type={type} typeKey={key} views={viewsOf(prefs, key)} state={state} apply={apply} describeToken={describeToken} />
        <PropertyFilterBar
          properties={properties}
          tokens={tokens}
          onChange={(next) => apply({ ...state, tokens: normaliseTokens(next) })}
          placeholder={`Filter ${lower} by status, drift, tag or text`}
          countText={countText}
          className="min-w-0 flex-[1_1_20rem]"
        />
        <ColumnsMenu all={allColumns} defaults={defaults} shown={shown} onChange={(choice) => write(withColumns(key, choice))} />
      </div>

      {selectedRows.length > 0 && (
        <div role="region" aria-label="Selection" className="mb-2 flex flex-wrap items-center gap-2 rounded-md bg-accent-tint px-3 py-1.5 text-sm text-primary">
          <span className="font-medium" aria-live="polite">
            {plural(selectedRows.length, noun)} selected
          </span>
          <Button size="sm" icon="download" onClick={() => exportRows(selectedRows)}>
            Export selected
          </Button>
          <Button size="sm" variant="ghost" onClick={() => setSelected(noSelection)}>
            Clear selection
          </Button>
        </div>
      )}

      {denied ? (
        <EmptyState
          icon="lock"
          title={`You can’t list ${lower} here`}
          message={denied.reason}
          action={
            <Button size="sm" variant="primary" onClick={() => refusal.show({ denied, what: `List ${lower}` })}>
              See why
            </Button>
          }
        />
      ) : list.error && !list.data ? (
        <ErrorState error={list.error} title={`Could not load the ${lower}`} onRetry={list.refetch} />
      ) : (
        <VirtualTable<ConsoleResource>
          columns={columns}
          rows={rows}
          rowKey={(r) => r.wfrn}
          rowLabel={nameOf}
          label={type.title}
          loading={list.loading}
          sort={sort}
          serverSort
          onSortChange={(next: VirtualSort) => apply({ ...state, sort: next })}
          selectable
          selectedKeys={selected}
          onSelectionChange={(next) => setSelected(() => next)}
          onRowClick={(r) => navigate(resourcePath(type.service, type.name, r.wfrn))}
          maxHeight="max(18rem, calc(100dvh - 24rem))"
          empty={empty}
          footer={
            <CursorPager
              count={rows.length}
              hasNext={!!list.data?.next}
              hasPrevious={paging.hasPrevious}
              onNext={() => list.data?.next && paging.next(list.data.next)}
              onPrevious={paging.previous}
              pageSize={pageSize}
              onPageSizeChange={(size) => apply({ ...state, pageSize: size })}
              page={paging.page}
              loading={list.fetching}
              noun={noun}
            />
          }
        />
      )}
    </>
  )
}

/** One shown column as the table draws it; only the state service's orders sort. */
function tableColumn(c: ListColumn, type: ConsoleType): VirtualColumn<ConsoleResource> {
  const sortable = SORTABLE.has(c.key)
  switch (c.kind) {
    case 'name':
      return { key: 'name', header: 'Name', sortable, width: 'minmax(12rem, 1.5fr)', minRem: 12, accessor: nameOf, render: (r) => <span className="truncate font-medium text-primary">{nameOf(r)}</span> }
    case 'status':
      return { key: 'status', header: 'Status', width: '9rem', accessor: (r) => r.state, render: (r) => <LifecycleBadge status={r.state} lifecycle={r.state} size="sm" /> }
    case 'wfrn':
      return {
        key: 'wfrn',
        header: 'wfrn',
        width: 'minmax(18rem, 2fr)',
        minRem: 18,
        accessor: (r) => r.wfrn,
        render: (r) => (
          <span className="flex min-w-0">
            <WfrnText wfrn={r.wfrn} max={40} />
          </span>
        ),
      }
    case 'owner':
      return { key: 'owner', header: 'Owner', width: '10rem', accessor: (r) => r.tags?.owner }
    case 'created':
      return { key: 'created', header: 'Created', sortable, width: '8rem', accessor: (r) => r.createdAt, render: (r) => <Stamp at={r.createdAt} /> }
    case 'updated':
      return { key: 'updated', header: 'Updated', sortable, width: '8rem', accessor: (r) => r.updatedAt, render: (r) => <Stamp at={r.updatedAt} /> }
    case 'region':
      return { key: 'region', header: 'Region', width: '7rem', minRem: 6, accessor: (r) => r.region }
    case 'drift':
      return {
        key: 'drift',
        header: 'Drift',
        width: '7rem',
        minRem: 6,
        accessor: (r) => (r.drift === undefined ? undefined : r.drift ? 'drifted' : 'in sync'),
        render: (r) =>
          r.drift === undefined ? undefined : r.drift ? (
            <DotTag tone="warning" size="sm">
              drifted
            </DotTag>
          ) : (
            <span className="text-secondary">in sync</span>
          ),
      }
    case 'tag':
      return { key: c.key, header: c.label, width: 'minmax(8rem, 1fr)', minRem: 8, accessor: (r) => (c.tag ? r.tags?.[c.tag] : undefined) }
    default: {
      const field = c.field ?? c.key
      const t = Array.isArray(c.schema?.type) ? c.schema.type.find((x) => x !== 'null') : c.schema?.type
      const numeric = t === 'number' || t === 'integer'
      return {
        key: c.key,
        header: c.label,
        numeric,
        width: numeric ? '7rem' : 'minmax(8rem, 1fr)',
        minRem: numeric ? 6 : 8,
        accessor: (r) => {
          const v = r.attributes?.[field]
          return v === null || v === undefined || typeof v === 'string' || typeof v === 'number' || typeof v === 'boolean' ? v : JSON.stringify(v)
        },
        render: (r) => <span className="truncate">{formatField(type.schema, field, r.attributes?.[field])}</span>,
      }
    }
  }
}
