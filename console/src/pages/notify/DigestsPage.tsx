import { Card, EmptyState, Icon, PageHeader, SkeletonRows } from '@iaf/ui'
import { DotTag, ErrorState, When } from '@iaf/console-kit'
import { useNotificationRoute } from '../../app/notifications'
import { Link, pathFor, type PageProps } from '../../app/router'
import { useConsole } from '../../app/console'
import { factLabel, notificationsApi, weekWords, type DigestSection } from '../../lib/notifications'
import { useQuery } from '../../lib/queries'

/*
 * The account's weekly digests (WP 9.13): every Monday at 06:00 UTC the BFF writes the week
 * before in words, by the copilot or by a template without it, in four parts (changes, drift,
 * cost and access), each from numbers it shows beside the text. The list is newest first; one
 * week reads as a page, its highlights leading to what they name. Every managed role reads them
 * (`digests:get`); a refusal says why.
 */

const SECTION_ICON: Record<DigestSection['kind'], 'job' | 'pulse' | 'chart' | 'shield'> = { changes: 'job', drift: 'pulse', cost: 'chart', access: 'shield' }

export default function DigestsPage(_props: PageProps) {
  const { account } = useConsole()
  const list = useQuery(['digests', account.id], () => notificationsApi.digests({ limit: 52 }))
  const items = list.data?.items ?? []
  return (
    <>
      <PageHeader title="Weekly digests" subtitle={`What happened in ${account.name} each week, in words: requests, drift, cost and access. Written every Monday for the week before.`} />
      {list.error ? (
        <ErrorState error={list.error} title="Could not load the digests" onRetry={() => void list.refetch()} />
      ) : list.loading && !list.data ? (
        <SkeletonRows n={3} rowClassName="h-16" />
      ) : items.length === 0 ? (
        <EmptyState icon="document" title="No digests yet" message="The first one is written on the Monday after the account’s first full week." />
      ) : (
        <ul aria-label={`Weekly digests of ${account.name}`} className="space-y-3">
          {items.map((d) => (
            <li key={d.weekStart} data-digest={d.weekStart}>
              <Link to={pathFor('digest', { weekStart: d.weekStart })} className="block rounded-lg border border-stroke-light bg-surface p-4 transition-colors hover:border-accent/40">
                <span className="block text-sm font-semibold text-primary">Week of {weekWords(d.weekStart)}</span>
                <span className="mt-1 block text-sm text-secondary">{d.headline}</span>
                <span className="mt-1 block text-caption text-tertiary">
                  Written <When at={d.generatedAt} />
                </span>
              </Link>
            </li>
          ))}
        </ul>
      )}
    </>
  )
}

function factValue(v: unknown): string {
  if (typeof v === 'number') return v.toLocaleString('en-GB')
  if (typeof v === 'string') return v
  if (typeof v === 'boolean') return v ? 'yes' : 'no'
  return JSON.stringify(v)
}

export function DigestPage({ route }: PageProps) {
  const { account } = useConsole()
  const weekStart = route.params.weekStart
  const routeOf = useNotificationRoute()
  const digest = useQuery(['digests', account.id, weekStart], () => notificationsApi.digest(weekStart))
  const list = useQuery(['digests', account.id], () => notificationsApi.digests({ limit: 52 }))
  const weeks = list.data?.items ?? []
  const at = weeks.findIndex((w) => w.weekStart === weekStart)
  const newer = at > 0 ? weeks[at - 1] : undefined
  const older = at >= 0 ? weeks[at + 1] : undefined

  if (digest.error) return <ErrorState error={digest.error} title="Could not load this digest" onRetry={() => void digest.refetch()} />
  const d = digest.data
  if (!d) return <SkeletonRows n={4} rowClassName="h-24" />

  return (
    <>
      <PageHeader
        title={`Week of ${weekWords(d.weekStart, d.weekEnd)}`}
        subtitle={d.headline}
        eyebrow={`${account.name} weekly digest`}
      />
      <p className="mb-6 flex flex-wrap items-center gap-2 text-caption text-secondary" data-digest-source={d.source}>
        <DotTag size="sm" tone={d.source === 'assistant' ? 'accent' : 'neutral'}>
          {d.source === 'assistant' ? 'Written by the copilot' : 'Written from the template'}
        </DotTag>
        <span>
          <When at={d.generatedAt} />, from the numbers shown with each part.
        </span>
      </p>
      <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
        {d.sections.map((s) => {
          const facts = Object.entries(s.facts ?? {})
          return (
            <Card key={s.kind} data-digest-section={s.kind}>
              <h2 className="flex items-center gap-2 text-subheadline font-semibold text-primary">
                <Icon name={SECTION_ICON[s.kind] ?? 'document'} size={16} className="text-tertiary" />
                {s.title}
              </h2>
              <p className="mt-2 text-sm text-primary">{s.text}</p>
              {!!s.highlights?.length && (
                <ul className="mt-3 space-y-1 text-sm">
                  {s.highlights.map((h) => {
                    const to = h.link ? routeOf({ id: '', kind: 'digest.ready', severity: 'info', title: h.text, accountId: d.accountId, link: h.link, createdAt: d.generatedAt }) : null
                    return (
                      <li key={h.text} className="flex gap-2">
                        <Icon name="chevron-right" size={14} className="mt-0.5 shrink-0 text-tertiary" />
                        {to ? (
                          <Link to={to} className="font-medium text-accent underline-offset-2 hover:underline">
                            {h.text}
                          </Link>
                        ) : (
                          <span className="text-primary">{h.text}</span>
                        )}
                      </li>
                    )
                  })}
                </ul>
              )}
              {facts.length > 0 && (
                <dl className="mt-3 grid grid-cols-2 gap-x-4 gap-y-1 border-t border-stroke-light pt-3 text-caption sm:grid-cols-3">
                  {facts.map(([k, v]) => (
                    <div key={k} className="min-w-0">
                      <dt className="text-secondary">{factLabel(k)}</dt>
                      <dd className="tabular-nums text-primary">{factValue(v)}</dd>
                    </div>
                  ))}
                </dl>
              )}
            </Card>
          )
        })}
      </div>
      <nav aria-label="Other weeks" className="mt-6 flex flex-wrap items-center justify-between gap-3 text-sm">
        {older ? (
          <Link to={pathFor('digest', { weekStart: older.weekStart })} className="font-medium text-accent hover:underline">
            Week before: {weekWords(older.weekStart)}
          </Link>
        ) : (
          <span />
        )}
        <Link to={pathFor('digests')} className="font-medium text-accent hover:underline">
          All weekly digests
        </Link>
        {newer && (
          <Link to={pathFor('digest', { weekStart: newer.weekStart })} className="font-medium text-accent hover:underline">
            Week after: {weekWords(newer.weekStart)}
          </Link>
        )}
      </nav>
    </>
  )
}
