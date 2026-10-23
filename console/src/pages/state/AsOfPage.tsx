import { useMemo, useState } from 'react'
import { Button, Input, PageHeader, Select, Table, plural, type TableColumn } from '@iaf/ui'
import { ErrorState, LifecycleBadge, formatValueText, titleCase, xConsoleOf } from '@iaf/console-kit'
import { useConsole } from '../../app/console'
import { Link, navigate, pathFor, resourcePath, type PageProps } from '../../app/router'
import { useQuery } from '../../lib/queries'
import { stateApi } from '../../lib/state'
import type { ConsoleResource } from '../../lib/types'
import { endOfDay } from '../resource/TimeTravel'
import { dayOf } from './common'

/*
 * The account's inventory as of a date (WP 9.8): every resource of the chosen types that existed
 * at the end of that day, as it was then, from the state service's `at` on each type's list.
 * Resources made since are left out and ones deleted since are shown as they were; each row opens
 * the resource's own time travel at the same moment.
 */

const DAY = 24 * 3600_000

interface Row {
  r: ConsoleResource
  typeName: string
}

export default function AsOfPage({ route }: PageProps) {
  const { types, account } = useConsole()
  const [now] = useState(() => Date.now())
  const day = /^\d{4}-\d{2}-\d{2}$/.test(route.query.day ?? '') ? route.query.day! : dayOf(now - DAY)
  const typeName = types.some((t) => t.name === route.query.type) ? route.query.type! : ''
  const at = new Date(endOfDay(day, now)).toISOString()
  const chosen = typeName ? types.filter((t) => t.name === typeName) : types
  const list = useQuery(['state-as-of', account.id, at, typeName], async () => {
    const pages = await Promise.all(chosen.map(async (t) => ({ t, page: await stateApi.listAt(t.name, at, { limit: 200 }).catch(() => ({ items: [] as ConsoleResource[], next: null })) })))
    return pages.flatMap(({ t, page }) => page.items.map((r): Row => ({ r, typeName: t.name })))
  })
  const set = (next: Record<string, string | undefined>) => navigate(pathFor('as-of', {}, { ...route.query, ...next }, { accountId: route.accountId }), { replace: true })
  const rows = useMemo(() => [...(list.data ?? [])].sort((a, b) => a.typeName.localeCompare(b.typeName) || (a.r.name ?? a.r.wfrn).localeCompare(b.r.name ?? b.r.wfrn)), [list.data])

  const columns: TableColumn<Row>[] = [
    {
      key: 'name',
      header: 'Name',
      render: ({ r, typeName: tn }) => {
        const t = types.find((x) => x.name === tn)
        return t ? (
          <Link to={`${resourcePath(t.service, t.name, r.wfrn)}?tab=activity&at=${encodeURIComponent(at)}`} className="font-medium text-accent underline-offset-2 hover:underline">
            {r.name ?? r.wfrn}
          </Link>
        ) : (
          <span>{r.name ?? r.wfrn}</span>
        )
      },
    },
    { key: 'type', header: 'Type', render: ({ typeName: tn }) => <span className="text-sm text-secondary">{types.find((t) => t.name === tn)?.title ?? tn}</span>, width: '11rem' },
    { key: 'state', header: 'Status then', render: ({ r }) => <LifecycleBadge status={r.state} lifecycle={r.state} />, width: '9rem' },
    {
      key: 'asked',
      header: 'Asked for then',
      render: ({ r, typeName: tn }) => {
        const props = types.find((t) => t.name === tn)?.schema.properties ?? {}
        const fields = Object.entries(r.attributes).filter(([k, v]) => k !== 'name' && k !== 'owner' && (typeof v === 'number' || typeof v === 'string')).slice(0, 4)
        return <span className="block break-words text-caption text-secondary">{fields.map(([k, v]) => `${props[k]?.title ?? titleCase(k)} ${formatValueText(v, xConsoleOf(props[k]))}`).join(' · ') || 'Nothing recorded'}</span>
      },
    },
  ]

  const when = new Date(at).toLocaleDateString(undefined, { day: 'numeric', month: 'long', year: 'numeric', timeZone: 'UTC' })
  return (
    <>
      <PageHeader title="Inventory as of a date" subtitle={`The resources of ${account.name} as they were at the end of a day, replayed from their events: what existed, in what state, asked to be what.`} />
      <section aria-label="Filters" className="mb-4 flex flex-wrap items-end gap-3">
        <label>
          <span className="mb-1.5 block text-caption font-medium text-secondary">Day (UTC)</span>
          <Input type="date" size="sm" value={day} max={dayOf(now)} onChange={(e) => e.target.value && set({ day: e.target.value })} aria-label="Day" />
        </label>
        <Select size="sm" ariaLabel={`Type: ${typeName ? (types.find((t) => t.name === typeName)?.title ?? typeName) : 'All types'}`} value={typeName} onChange={(v) => set({ type: v || undefined })} options={[{ value: '', label: 'All types' }, ...types.map((t) => ({ value: t.name, label: t.title }))]} />
        <Button size="sm" variant="secondary" onClick={() => set({ day: dayOf(now - 7 * DAY) })}>
          A week ago
        </Button>
        <Button size="sm" variant="secondary" onClick={() => set({ day: dayOf(now - 30 * DAY) })}>
          A month ago
        </Button>
      </section>
      {list.error ? (
        <ErrorState error={list.error} title="Could not replay the inventory" onRetry={() => void list.refetch()} />
      ) : (
        <>
          <p className="mb-2 text-sm text-secondary" role="status">
            {list.loading ? 'Replaying…' : `${plural(rows.length, 'resource')} existed at the end of ${when}.`}
          </p>
          <Table<Row> caption={`The resources of ${account.name} at the end of ${when}`} columns={columns} rows={rows} rowKey={({ r }) => r.wfrn} loading={list.loading} serverSort emptyIcon="calendar" emptyTitle="Nothing existed yet" emptyMessage="No resource of these types existed at the end of that day. Pick a later day." />
        </>
      )}
    </>
  )
}
