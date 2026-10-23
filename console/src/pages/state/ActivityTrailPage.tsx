import { useMemo, useState } from 'react'
import { Button, Icon, PageHeader, Segmented, Select, Table, formatNumber, plural, type TableColumn } from '@iaf/ui'
import { CursorPager, ErrorState, StatusBadge, When, useCursorPaging } from '@iaf/console-kit'
import { useConsole, pathOfWfrn } from '../../app/console'
import { FailureNote } from '../../app/commands'
import { ActionButton } from '../../app/Refusal'
import { Link, navigate, pathFor, type PageProps } from '../../app/router'
import { explain } from '../../lib/plain'
import { useQuery } from '../../lib/queries'
import { EVENT_TYPE_WORDS, OUTCOME_WORDS, eventSummary, isStateEventType, outcomeOf, stateApi, type Outcome, type TrailParams } from '../../lib/state'
import { STATE_EVENT_TYPES, checkPack, periodWords, problemWords, type EvidencePack, type PackCheck, type TrailEntry } from '../../lib/trail'
import { actorName, nameOfWfrn, typeOfWfrn, useEvidenceDecision, usePeople, useResourceNames } from './common'

/*
 * The account's activity trail (WP 7.1), like CloudTrail: every event of every resource, newest
 * first, filtered by person, resource type, what was done, its outcome and the time, each linked
 * to its resource and the request behind it. The trail is a hash chain sealed day by day; Verify
 * reads the evidence pack of the window and checks it here, in the browser, as an auditor would
 * away from the console: every event's hash, every link, every seal and every signature. The
 * answer says in words whether the chain holds and, when it does not, where it breaks. The pack
 * itself downloads as JSON for the auditors. Both are for the account's admins and auditors.
 */

type Period = '1d' | '7d' | '30d'
const PERIODS: readonly { value: Period; label: string; days: number }[] = [
  { value: '1d', label: '24 hours', days: 1 },
  { value: '7d', label: '7 days', days: 7 },
  { value: '30d', label: '30 days', days: 30 },
]
const DAY = 24 * 3600_000

/** The window a period names, ending now, kept to the minute so a page's reads agree. */
function windowOf(period: Period, now: number): { from: string; to: string } {
  const end = Math.ceil(now / 60_000) * 60_000
  const days = PERIODS.find((p) => p.value === period)?.days ?? 7
  return { from: new Date(end - days * DAY).toISOString(), to: new Date(end).toISOString() }
}

type Verified = { pack: EvidencePack; check: PackCheck }

export default function ActivityTrailPage({ route }: PageProps) {
  const { account, types } = useConsole()
  const q = route.query
  const period: Period = PERIODS.some((p) => p.value === q.period) ? (q.period as Period) : '7d'
  const actor = q.actor ?? ''
  const rtype = q.rtype ?? ''
  const what = q.what && isStateEventType(q.what) ? q.what : ''
  const outcome = (q.outcome && q.outcome in OUTCOME_WORDS ? q.outcome : '') as Outcome | ''
  const wfrn = q.wfrn ?? ''
  const [now] = useState(() => Date.now())
  const window = useMemo(() => windowOf(period, now), [period, now])
  const paging = useCursorPaging()
  const [pageSize, setPageSize] = useState(50)
  const people = usePeople()
  const names = useResourceNames()

  const params: TrailParams & { outcome?: string } = {
    ...window,
    actor: actor || undefined,
    type: what || undefined,
    outcome: outcome || undefined,
    wfrn: wfrn || (rtype ? `wfrn:*:*:*:*:${rtype}/*` : undefined),
    limit: pageSize,
    after: paging.after,
  }
  const list = useQuery(['state-trail', account.id, JSON.stringify(params)], () => stateApi.activity(params))
  const rows = useMemo(() => list.data?.items ?? [], [list.data])
  const set = (next: Record<string, string | undefined>) => {
    paging.reset()
    navigate(pathFor('activity', {}, { ...q, ...next }, { accountId: route.accountId }), { replace: true })
  }

  const personOptions = useMemo(() => {
    const seen = new Map<string, string>(people)
    for (const e of rows) if (e.actor && !seen.has(e.actor)) seen.set(e.actor, actorName(e.actor, people, e.onBehalfOf?.name))
    const unique = new Map<string, string>()
    for (const [id, name] of seen) if (!id.startsWith('user_')) unique.set(id, name)
    if (actor && !unique.has(actor)) unique.set(actor, actorName(actor, people))
    unique.set('drift-service', 'Drift service')
    return [...unique].sort((a, b) => a[1].localeCompare(b[1]))
  }, [people, rows, actor])

  const columns: TableColumn<TrailEntry>[] = [
    { key: 'at', header: 'When', render: (e) => <When at={e.at} className="text-caption text-secondary" />, width: '8rem' },
    {
      key: 'what',
      header: 'What',
      render: (e) => {
        const summary = eventSummary(e)
        const o = outcomeOf(e.type)
        return (
          <span className="block min-w-0">
            <span className="flex flex-wrap items-center gap-2">
              <span className="font-medium text-primary">{EVENT_TYPE_WORDS[e.type]}</span>
              {(o === 'succeeded' || o === 'failed') && <StatusBadge status={o} size="sm" />}
            </span>
            {summary && <span className="block break-words text-caption text-secondary">{summary}</span>}
          </span>
        )
      },
    },
    {
      key: 'resource',
      header: 'Resource',
      render: (e) => {
        const href = pathOfWfrn(e.aggregateId, types)
        const name = nameOfWfrn(e.aggregateId, names)
        const type = types.find((t) => t.name === typeOfWfrn(e.aggregateId))
        return (
          <span className="block min-w-0">
            {href ? (
              <Link to={`${href}?tab=activity`} className="block truncate font-medium text-accent underline-offset-2 hover:underline">
                {name}
              </Link>
            ) : (
              <span className="block truncate text-primary">{name}</span>
            )}
            <span className="block text-caption text-tertiary">
              {type?.title ?? typeOfWfrn(e.aggregateId)} · version {e.data.version}
            </span>
          </span>
        )
      },
      width: '13rem',
    },
    { key: 'who', header: 'Who', render: (e) => <span className="text-sm text-primary">{actorName(e.actor, people, e.onBehalfOf?.name)}</span>, width: '9rem' },
    {
      key: 'request',
      header: 'Request',
      render: (e) =>
        typeof e.data.jobId === 'string' ? (
          <Link to={pathFor('request', { id: e.data.jobId })} className="font-mono text-caption text-accent underline-offset-2 hover:underline">
            {e.data.jobId}
          </Link>
        ) : (
          <span className="text-caption text-tertiary">None</span>
        ),
      width: '9rem',
    },
  ]

  const periodLabel = PERIODS.find((p) => p.value === period)?.label ?? ''
  return (
    <>
      <PageHeader
        title="Activity trail"
        subtitle={`Every change to the resources of ${account.name}: what happened, who did it and through which request. Each event is chained to the one before it and each day is sealed and signed, so a change made afterwards shows.`}
      />

      <Integrity window={window} periodLabel={periodLabel} names={names} />

      <section aria-label="Filters" className="mb-4 flex flex-wrap items-center gap-2">
        <Segmented<Period> ariaLabel="Period" size="sm" value={period} onChange={(v) => set({ period: v === '7d' ? undefined : v })} options={PERIODS.map((p) => ({ value: p.value, label: p.label }))} />
        <Select size="sm" ariaLabel={`Person: ${actor ? actorName(actor, people) : 'Anyone'}`} value={actor} onChange={(v) => set({ actor: v || undefined })} options={[{ value: '', label: 'Anyone' }, ...personOptions.map(([value, label]) => ({ value, label }))]} />
        <Select size="sm" ariaLabel={`Resource type: ${rtype ? (types.find((t) => t.name === rtype)?.title ?? rtype) : 'All types'}`} value={rtype} onChange={(v) => set({ rtype: v || undefined, wfrn: undefined })} options={[{ value: '', label: 'All types' }, ...types.map((t) => ({ value: t.name, label: t.title }))]} />
        <Select size="sm" ariaLabel={`What: ${what ? EVENT_TYPE_WORDS[what] : 'Anything'}`} value={what} onChange={(v) => set({ what: v || undefined })} options={[{ value: '', label: 'Anything' }, ...STATE_EVENT_TYPES.map((t) => ({ value: t, label: EVENT_TYPE_WORDS[t] }))]} />
        <Select size="sm" ariaLabel={`Outcome: ${outcome ? OUTCOME_WORDS[outcome] : 'Any outcome'}`} value={outcome} onChange={(v) => set({ outcome: v || undefined })} options={[{ value: '', label: 'Any outcome' }, ...(Object.keys(OUTCOME_WORDS) as Outcome[]).map((o) => ({ value: o, label: OUTCOME_WORDS[o] }))]} />
        {wfrn && (
          <Button size="sm" variant="ghost" icon="close" onClick={() => set({ wfrn: undefined })}>
            {`Only ${nameOfWfrn(wfrn, names)}: show all`}
          </Button>
        )}
      </section>

      {list.error ? (
        <ErrorState error={list.error} title="Could not load the trail" onRetry={() => void list.refetch()} />
      ) : (
        <>
          <Table<TrailEntry>
            caption={`Activity of ${account.name} in the last ${periodLabel}, newest first`}
            columns={columns}
            rows={rows}
            rowKey={(e) => e.key}
            loading={list.loading}
            serverSort
            emptyIcon="history"
            emptyTitle="Nothing happened that matches"
            emptyMessage="No event of the account matches these filters in this period. Widen the period or clear a filter."
          />
          <CursorPager
            className="mt-3"
            count={rows.length}
            hasNext={!!list.data?.next}
            hasPrevious={paging.hasPrevious}
            onNext={() => list.data?.next && paging.next(list.data.next)}
            onPrevious={paging.previous}
            page={paging.page}
            pageSize={pageSize}
            onPageSizeChange={(n) => {
              paging.reset()
              setPageSize(n)
            }}
            loading={list.fetching}
            noun="event"
          />
        </>
      )}
    </>
  )
}

/* ---------------------------------------------------------------------------- the seals */

function Integrity({ window, periodLabel, names }: { window: { from: string; to: string }; periodLabel: string; names: Map<string, string> }) {
  const { account, types } = useConsole()
  const decision = useEvidenceDecision()
  const digests = useQuery(['state-digests', account.id, window.from], () => stateApi.digests({ from: window.from, to: window.to }))
  const keys = useQuery(['state-keys', account.id], () => stateApi.keys())
  const [verified, setVerified] = useState<{ window: string; result: Verified } | null>(null)
  const [busy, setBusy] = useState<'verify' | 'download' | null>(null)
  const [failure, setFailure] = useState<unknown>(null)
  const sealed = digests.data?.items ?? []
  const current = verified?.window === window.from ? verified.result : null

  const verify = async () => {
    setBusy('verify')
    setFailure(null)
    try {
      const pack = await stateApi.evidence(window.from, window.to)
      setVerified({ window: window.from, result: { pack, check: await checkPack(pack) } })
    } catch (e) {
      setFailure(e)
    } finally {
      setBusy(null)
    }
  }

  const download = async () => {
    setBusy('download')
    setFailure(null)
    try {
      const pack = current?.pack ?? (await stateApi.evidence(window.from, window.to))
      const blob = new Blob([JSON.stringify(pack, null, 2)], { type: 'application/json' })
      const url = URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = url
      a.download = `evidence-${account.id}-${pack.from.slice(0, 10)}-to-${pack.to.slice(0, 10)}.json`
      document.body.appendChild(a)
      a.click()
      a.remove()
      setTimeout(() => URL.revokeObjectURL(url), 1000)
    } catch (e) {
      setFailure(e)
    } finally {
      setBusy(null)
    }
  }

  const lastSeal = sealed[sealed.length - 1]
  return (
    <section aria-labelledby="trail-integrity" className="mb-6 rounded-xl border border-stroke-light p-4">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0">
          <h2 id="trail-integrity" className="text-subheadline font-semibold text-primary">
            Sealed and signed
          </h2>
          <p className="mt-1 text-sm text-secondary">
            {digests.loading
              ? 'Reading the seals…'
              : `${plural(sealed.length, 'day')} of the last ${periodLabel} sealed${lastSeal ? `, the last on ${periodWords(lastSeal)}` : ''}. Each seal counts its day’s events, hashes them together, points to the seal before it and is signed with a published key${keys.data ? ` (${keys.data.keys.length} kept, rotated ones included)` : ''}. Today is sealed shortly after midnight UTC.`}
          </p>
        </div>
        <div className="flex flex-wrap gap-2">
          <ActionButton decision={decision} what="Verify the trail" verb="verify" variant="primary" icon="shield" loading={busy === 'verify'} onClick={() => void verify()}>
            Verify the chain
          </ActionButton>
          <ActionButton decision={decision} what="Download the evidence pack" verb="evidence" icon="download" loading={busy === 'download'} onClick={() => void download()}>
            Download evidence pack
          </ActionButton>
        </div>
      </div>
      <div role="status" aria-live="polite" data-verify={current ? (current.check.valid ? 'holds' : 'broken') : 'none'}>
        {current && <VerifyResult result={current} names={names} typesHref={(w) => pathOfWfrn(w, types)} />}
      </div>
      {failure !== null && <FailureNote failure={explain(failure, 'Read the evidence pack')} compact className="mt-3" onDismiss={() => setFailure(null)} />}
      {sealed.length > 0 && (
        <details className="mt-3 text-sm">
          <summary className="cursor-pointer rounded-sm text-accent">Show the {plural(sealed.length, 'seal')}</summary>
          <div className="mt-2 overflow-x-auto rounded-lg border border-stroke-light" tabIndex={0} role="region" aria-label="The seals">
            <table className="w-full text-left text-caption">
              <caption className="sr-only">The seals of the period, oldest first</caption>
              <thead className="text-tertiary">
                <tr>
                  <th scope="col" className="px-3 py-2 font-medium">
                    Day
                  </th>
                  <th scope="col" className="px-3 py-2 font-medium">
                    Events
                  </th>
                  <th scope="col" className="px-3 py-2 font-medium">
                    Seal
                  </th>
                  <th scope="col" className="px-3 py-2 font-medium">
                    Signed with
                  </th>
                </tr>
              </thead>
              <tbody className="divide-y divide-stroke-light">
                {sealed.map((d) => (
                  <tr key={d.id}>
                    <td className="px-3 py-1.5 text-primary">{periodWords(d)}</td>
                    <td className="px-3 py-1.5 tabular-nums text-primary">{formatNumber(d.count)}</td>
                    <td className="px-3 py-1.5 font-mono text-secondary" title={d.hash}>
                      {d.hash.slice(7, 19)}…
                    </td>
                    <td className="px-3 py-1.5 font-mono text-secondary">{d.keyId}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </details>
      )}
    </section>
  )
}

function VerifyResult({ result, names, typesHref }: { result: Verified; names: Map<string, string>; typesHref: (wfrn: string) => string | null }) {
  const { pack, check } = result
  const span = `${new Date(pack.from).toLocaleDateString(undefined, { day: 'numeric', month: 'short', timeZone: 'UTC' })} to ${new Date(Date.parse(pack.to) - 1).toLocaleDateString(undefined, { day: 'numeric', month: 'short', year: 'numeric', timeZone: 'UTC' })}`
  const counted = `${plural(check.events, 'event')} of ${plural(check.resources, 'resource')}, ${plural(check.digests, 'sealed day')} and ${plural(check.signatures.valid, 'signature')}`
  const unsealed = check.unsealed ? ` ${plural(check.unsealed, 'event')} of today ${check.unsealed === 1 ? 'is' : 'are'} not sealed yet; ${check.unsealed === 1 ? 'it is' : 'they are'} checked by their links until the seal.` : ''
  if (check.valid) {
    return (
      <div className="mt-4 flex items-start gap-3 rounded-lg bg-healthy-tint/50 p-3 text-sm" data-testid="verify-result">
        <Icon name="check" size={18} className="mt-0.5 shrink-0 text-healthy" />
        <p className="min-w-0 text-primary">
          <span className="font-semibold">The chain holds from {span}.</span> Checked here, in your browser: {counted}. Every event matches the hash written with it and points to the one before, every seal adds up, and every signature matches a published key.{unsealed}
        </p>
      </div>
    )
  }
  const first = check.problems[0]
  const name = first.wfrn ? nameOfWfrn(first.wfrn, names) : first.subject
  const href = first.wfrn ? typesHref(first.wfrn) : null
  const when = first.at ? new Date(first.at).toLocaleString(undefined, { day: 'numeric', month: 'short', year: 'numeric', hour: '2-digit', minute: '2-digit' }) : ''
  return (
    <div className="mt-4 flex items-start gap-3 rounded-lg bg-critical-tint/50 p-3 text-sm" data-testid="verify-result">
      <Icon name="alert-triangle" size={18} className="mt-0.5 shrink-0 text-critical" />
      <div className="min-w-0 space-y-1 text-primary">
        <p>
          <span className="font-semibold">The chain breaks at {first.digestId && first.at ? `the seal of ${periodWords({ from: first.at, to: new Date(Date.parse(first.at) + DAY).toISOString() })}` : `${name}, version ${first.version}`}.</span>{' '}
          {first.type && <>Its {EVENT_TYPE_WORDS[first.type as keyof typeof EVENT_TYPE_WORDS]?.toLowerCase() ?? 'event'} of {when}: </>}
          {problemWords(first)}.
        </p>
        {check.problems.length > 1 && <p className="text-secondary">{plural(check.problems.length - 1, 'other problem')} found too, listed in the evidence pack’s check.</p>}
        <p className="text-secondary">
          Everything else from {span} holds. Download the evidence pack for the auditors; the state service’s own check of it says the same.
          {href && (
            <>
              {' '}
              <Link to={`${href}?tab=activity`} className="text-accent underline underline-offset-2">
                Open {name}’s activity
              </Link>
            </>
          )}
        </p>
      </div>
    </div>
  )
}
