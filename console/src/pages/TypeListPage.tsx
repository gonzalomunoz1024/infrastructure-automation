import { useEffect, useMemo, useState } from 'react'
import { Button, EmptyState, Icon, IconButton, PageHeader, cx, plural } from '@iaf/ui'
import { CursorPager, DEFAULT_OPERATORS, ErrorState, LifecycleBadge, NUMERIC_OPERATORS, PropertyFilterBar, VirtualTable, WfrnText, applyFilters, formatField, useCursorPaging, type CellValue, type FilterProperty, type FilterToken, type VirtualColumn, type VirtualSort } from '@iaf/console-kit'
import { useConsole, useRouteType } from '../app/console'
import { LaterButton } from '../app/Phase'
import { navigate, pathFor, resourcePath, type PageProps } from '../app/router'
import { api } from '../lib/api'
import { capitalise, purposeOf, singularOf, wfrnOf, type ConsoleType, type WfrnContext } from '../lib/descriptors'
import { useQuery } from '../lib/queries'
import { pushRecent } from '../lib/recent'
import type { Resource, Schema } from '../lib/types'
import { MissingType, Stamp } from './parts'

/*
 * The list of one resource type: the descriptor's columns in a virtual table, a property filter
 * bar, cursor paging. Filters that the platform indexes (free text, status, owner) travel to it;
 * every token is also applied to the page on screen, so the table always agrees with the bar.
 */

type Row = Resource & { wfrn: string; derived: boolean }

function isNumeric(schema: Schema | undefined): boolean {
  const t = Array.isArray(schema?.type) ? schema.type.find((x) => x !== 'null') : schema?.type
  return t === 'number' || t === 'integer'
}

function cell(v: unknown): CellValue {
  if (v === null || v === undefined) return v
  if (typeof v === 'string' || typeof v === 'number' || typeof v === 'boolean') return v
  return JSON.stringify(v)
}

export function columnsFor(type: ConsoleType): VirtualColumn<Row>[] {
  return type.columns.map((c): VirtualColumn<Row> => {
    switch (c.kind) {
      case 'name':
        return { key: 'name', header: 'Name', sortable: true, width: 'minmax(12rem, 1.5fr)', minRem: 12, accessor: (r) => r.name ?? r.id, render: (r) => <span className="truncate font-medium text-primary">{r.name ?? r.id}</span> }
      case 'status':
        return { key: 'status', header: 'Status', sortable: true, width: '9rem', accessor: (r) => r.status, render: (r) => <LifecycleBadge status={r.status} lifecycle={type.lifecycle[r.status]} size="sm" /> }
      case 'wfrn':
        return {
          key: 'wfrn',
          header: 'wfrn',
          width: 'minmax(18rem, 2fr)',
          minRem: 18,
          accessor: (r) => r.wfrn,
          render: (r) => (
            <span className={cx('flex min-w-0', r.derived && 'opacity-75')} title={r.derived ? 'Derived by the console until the platform mints it' : undefined}>
              <WfrnText wfrn={r.wfrn} max={40} />
            </span>
          ),
        }
      case 'owner':
        return { key: 'owner', header: 'Owner', sortable: true, width: '10rem', accessor: (r) => r.owner }
      case 'created':
        return { key: 'created', header: 'Created', sortable: true, width: '8rem', accessor: (r) => r.createdAt, render: (r) => <Stamp at={r.createdAt} /> }
      case 'updated':
        return { key: 'updated', header: 'Updated', sortable: true, width: '8rem', accessor: (r) => r.updatedAt, render: (r) => <Stamp at={r.updatedAt} /> }
      default: {
        const field = c.field ?? c.key
        const numeric = isNumeric(c.schema)
        return {
          key: c.key,
          header: c.label,
          sortable: true,
          numeric,
          width: numeric ? '7rem' : 'minmax(8rem, 1fr)',
          minRem: numeric ? 6 : 8,
          accessor: (r) => cell(r.attributes?.[field]),
          render: (r) => <span className="truncate">{formatField(type.schema, field, r.attributes?.[field])}</span>,
        }
      }
    }
  })
}

export function propertiesOf(type: ConsoleType): FilterProperty[] {
  return [
    { key: 'name', label: 'Name' },
    { key: 'status', label: 'Status', values: type.states },
    { key: 'owner', label: 'Owner' },
    ...type.columns
      .filter((c) => c.kind === 'field')
      .map((c): FilterProperty => ({
        key: c.field ?? c.key,
        label: c.label,
        operators: isNumeric(c.schema) ? NUMERIC_OPERATORS : DEFAULT_OPERATORS,
        values: c.schema?.enum?.map(String),
      })),
  ]
}

/** The tokens the platform indexes, as query parameters; the rest is applied on screen. */
export function serverParamsOf(tokens: readonly FilterToken[]): Record<string, string> {
  const out: Record<string, string> = {}
  for (const t of tokens) {
    if (t.property === null) out.q = t.value
    else if (t.operator === '=' && (t.property === 'status' || t.property === 'owner')) out[t.property] = t.value
  }
  return out
}

function fieldValue(row: Row, property: string | null): unknown {
  if (property === null) {
    const attrs = Object.values(row.attributes ?? {}).map((v) => (v && typeof v === 'object' ? JSON.stringify(v) : String(v ?? '')))
    return [row.name ?? '', row.id, row.wfrn, row.status, row.owner, ...attrs].join(' ')
  }
  switch (property) {
    case 'name':
      return row.name ?? row.id
    case 'status':
      return row.status
    case 'owner':
      return row.owner
    case 'wfrn':
      return row.wfrn
    default:
      return row.attributes?.[property]
  }
}

export default function TypeListPage({ route }: PageProps) {
  const { loading, error, refetch, wfrnContext } = useConsole()
  const type = useRouteType(route.params.service, route.params.type)
  if (!type) return <MissingType name={route.params.type} service={route.params.service} loading={loading} error={error} refetch={refetch} />
  return <TypeList key={`${type.service}/${type.name}`} type={type} wfrnContext={wfrnContext} />
}

function TypeList({ type, wfrnContext }: { type: ConsoleType; wfrnContext: WfrnContext }) {
  const paging = useCursorPaging()
  const [pageSize, setPageSize] = useState(50)
  const [tokens, setTokens] = useState<FilterToken[]>([])
  const [sort, setSort] = useState<VirtualSort>({ key: type.defaultSort, dir: type.defaultSortDir })
  const server = useMemo(() => serverParamsOf(tokens), [tokens])
  const list = useQuery(['resources', type.plural, { limit: pageSize, after: paging.after ?? null, ...server }], () => api.resourcesOf(type.plural, { limit: pageSize, after: paging.after, ...server }))

  useEffect(() => {
    pushRecent({ kind: 'type', href: pathFor('type', { service: type.service, type: type.name }), label: type.title, hint: `${capitalise(type.service)} · resource type` })
  }, [type])

  const rows = useMemo<Row[]>(
    () =>
      (list.data?.items ?? []).map((r) => {
        const w = wfrnOf(r, type, wfrnContext)
        return { ...r, wfrn: w.wfrn, derived: w.derived }
      }),
    [list.data, type, wfrnContext],
  )
  const filtered = useMemo(() => applyFilters(rows, tokens, fieldValue), [rows, tokens])
  const columns = useMemo(() => columnsFor(type), [type])
  const properties = useMemo(() => propertiesOf(type), [type])
  const anyDerived = rows.some((r) => r.derived)
  const lower = type.title.toLowerCase()
  const noun = singularOf(type.title).toLowerCase()

  const onTokens = (next: FilterToken[]) => {
    setTokens(next)
    paging.reset()
  }

  const countText = list.data ? (filtered.length === rows.length ? `${plural(rows.length, noun)} on this page` : `${filtered.length} of ${plural(rows.length, noun)} on this page`) : undefined

  const empty = tokens.length ? (
    <EmptyState
      compact
      icon="filter"
      title={`No ${lower} match these filters`}
      message="Loosen a filter, or clear them all."
      action={
        <Button size="sm" onClick={() => onTokens([])}>
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
        <LaterButton phase={3} size="sm" variant="primary" icon="plus">
          Create your first one
        </LaterButton>
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
            <IconButton label="Refresh the list" onClick={() => void list.refetch()}>
              <Icon name="refresh" size={16} className={cx(list.fetching && 'animate-spin')} />
            </IconButton>
            <LaterButton phase={3} variant="primary" icon="plus">
              {type.create?.title ?? `Create ${noun}`}
            </LaterButton>
          </>
        }
      />
      <PropertyFilterBar properties={properties} tokens={tokens} onChange={onTokens} placeholder={`Filter ${lower}`} countText={countText} className="mb-3" />
      {list.error && !list.data ? (
        <ErrorState error={list.error} title={`Could not load the ${lower}`} onRetry={list.refetch} />
      ) : (
        <VirtualTable<Row>
          columns={columns}
          rows={filtered}
          rowKey={(r) => r.id}
          label={type.title}
          loading={list.loading}
          sort={sort}
          onSortChange={setSort}
          onRowClick={(r) => navigate(resourcePath(type.service, type.name, r.wfrn))}
          maxHeight="max(18rem, calc(100dvh - 24rem))"
          empty={empty}
          footer={
            <CursorPager
              count={filtered.length}
              hasNext={!!list.data?.next}
              hasPrevious={paging.hasPrevious}
              onNext={() => list.data?.next && paging.next(list.data.next)}
              onPrevious={paging.previous}
              pageSize={pageSize}
              onPageSizeChange={(size) => {
                setPageSize(size)
                paging.reset()
              }}
              page={paging.page}
              loading={list.fetching}
              noun={noun}
            />
          }
        />
      )}
      {anyDerived && <p className="mt-2 text-caption text-tertiary">Dimmed wfrns are derived by the console from the account and region in the header, until the platform mints them.</p>}
    </>
  )
}
