import { PageHeader, SkeletonRows, Stat } from '@iaf/ui'
import { DotTag, Section, When } from '@iaf/console-kit'
import { ExplainedError } from '../../app/commands'
import { useConsole } from '../../app/console'
import { Link, pathFor } from '../../app/router'
import { useQuery } from '../../lib/queries'
import { SEVERITY_WORDS, bandOf, scoreText, scorecardsApi, type PackScore, type Severity } from '../../lib/scorecards'
import { MetricChart } from '../resource/MetricChart'

/*
 * Compliance scorecards (WP 9.6): the account's score against every rule pack, its trend over 30
 * days, how it stands by severity, and each pack's rules with how many resources fail them; a
 * rule opens the list of failing resources, where one can be fixed. The organization page is the
 * heat map: every account the person may read, by pack, each cell a band in words and colour.
 */

const DAY = 86_400_000
const SEVERITIES: Severity[] = ['critical', 'warning', 'info']

export function ScoreTag({ score }: { score: number | null | undefined }) {
  const band = bandOf(score)
  return <DotTag tone={band.tone}>{`${scoreText(score)}, ${band.label.toLowerCase()}`}</DotTag>
}

export default function ScorecardPage() {
  const { account } = useConsole()
  const q = useQuery(['scorecards', 'card', account.id], () => scorecardsApi.scorecard(30))
  const card = q.data
  const header = (
    <PageHeader
      title="Scorecard"
      subtitle={`How ${account.name} stands against the rule packs. Critical rules weigh 10, warnings 3 and info 1.`}
      actions={
        <Link to={pathFor('compliance-org')} className="text-sm font-medium text-accent hover:opacity-70">
          Organization heat map
        </Link>
      }
    />
  )
  if (q.loading && !card)
    return (
      <>
        {header}
        <SkeletonRows n={4} rowClassName="h-20" />
      </>
    )
  if (!card)
    return (
      <>
        {header}
        <ExplainedError error={q.error} what="Load the scorecard" onRetry={() => void q.refetch()} />
      </>
    )
  const days = card.trend.filter((d) => d.score !== null)
  const from = days.length ? Date.parse(`${days[0].day}T00:00:00Z`) : 0
  const to = days.length ? Date.parse(`${days[days.length - 1].day}T00:00:00Z`) : 0
  return (
    <>
      {header}
      <div className="mb-8 grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4" data-score={card.score ?? ''}>
        <Stat label="Score" value={card.score === null ? 'None' : String(card.score)} hint={bandOf(card.score).label} />
        <Stat label="Checks failing" value={String(card.failed)} hint={`of ${card.passed + card.failed}`} />
        <Stat label="Resources failing" value={String(card.failingResources ?? 0)} hint={`of ${card.resources}`} />
        <Stat label="Judged" value={card.evaluatedAt ? <When at={card.evaluatedAt} /> : 'Not yet'} />
      </div>
      <div className="mb-8 grid grid-cols-1 gap-8 lg:grid-cols-[minmax(0,2fr)_minmax(0,1fr)]">
        <Section title="Last 30 days">
          {days.length > 1 ? <MetricChart series={{ name: 'Score', points: days.map((d) => ({ at: `${d.day}T00:00:00Z`, value: d.score })) }} from={from} to={to} stepMs={DAY} /> : <p className="text-sm text-secondary">Not enough days judged yet.</p>}
        </Section>
        <Section title="By severity">
          <ul aria-label="By severity" className="space-y-2 text-sm">
            {SEVERITIES.map((s) => (
              <li key={s} className="flex flex-wrap items-center justify-between gap-3">
                <DotTag tone={SEVERITY_WORDS[s].tone}>{SEVERITY_WORDS[s].label}</DotTag>
                <span className="tabular-nums text-primary">{`${card.bySeverity[s].failed} failing, ${card.bySeverity[s].passed} passing`}</span>
              </li>
            ))}
          </ul>
        </Section>
      </div>
      <div className="space-y-8">
        {card.packs.map((p) => (
          <PackSection key={p.name} pack={p} />
        ))}
      </div>
    </>
  )
}

function PackSection({ pack: p }: { pack: PackScore }) {
  return (
    <Section title={p.title} description={`Version ${p.version}. ${p.failed} failing, ${p.passed} passing.`} actions={<ScoreTag score={p.score} />}>
      <ul aria-label={`${p.title} rules`} className="divide-y divide-stroke-light rounded-lg border border-stroke-light">
        {p.rules.map((r) => (
          <li key={r.id} className="flex flex-wrap items-center gap-x-3 gap-y-1 px-4 py-3 text-sm" data-rule={`${p.name}/${r.id}`}>
            <DotTag tone={SEVERITY_WORDS[r.severity].tone} size="sm">
              {SEVERITY_WORDS[r.severity].label}
            </DotTag>
            {r.failed > 0 ? (
              <Link to={pathFor('compliance-rule', { pack: p.name, rule: r.id })} className="font-medium text-accent hover:opacity-70">
                {r.title}
              </Link>
            ) : (
              <span className="font-medium text-primary">{r.title}</span>
            )}
            <span className="ml-auto tabular-nums text-secondary">{r.failed ? `${r.failed} failing of ${r.failed + r.passed}` : r.passed ? `All ${r.passed} pass` : 'Nothing it applies to'}</span>
            {r.failed > 0 && r.remediable && <span className="text-caption text-secondary">has a fix</span>}
          </li>
        ))}
      </ul>
    </Section>
  )
}

/** Every account the person may read, by pack. */
export function HeatmapPage() {
  const q = useQuery(['scorecards', 'heatmap'], () => scorecardsApi.heatmap())
  const map = q.data
  return (
    <>
      <PageHeader title="Organization heat map" subtitle="Every account you may read, scored by pack. Good is 90 and over, needs work 70 and over, poor below that." />
      {q.loading && !map ? (
        <SkeletonRows n={3} />
      ) : !map ? (
        <ExplainedError error={q.error} what="Load the heat map" onRetry={() => void q.refetch()} />
      ) : (
        <div className="max-w-full overflow-x-auto rounded-lg border border-stroke-light" role="region" aria-label="Heat map" tabIndex={0}>
          <table className="w-full min-w-[36rem] text-sm">
            <caption className="sr-only">Scores by account and pack</caption>
            <thead>
              <tr className="border-b border-stroke-light text-left text-caption text-secondary">
                <th scope="col" className="px-4 py-2 font-medium">
                  Account
                </th>
                <th scope="col" className="px-4 py-2 font-medium">
                  Overall
                </th>
                {map.packs.map((p) => (
                  <th key={p.name} scope="col" className="px-4 py-2 font-medium">
                    {p.title}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {map.rows.map((r) => (
                <tr key={r.accountId} className="border-b border-stroke-light last:border-0">
                  <th scope="row" className="px-4 py-3 text-left font-medium text-primary">
                    {r.accountName ?? r.accountId}
                    <span className="block text-caption font-normal text-secondary">{`${r.failed} failing checks`}</span>
                  </th>
                  <td className="px-4 py-3">
                    <HeatCell score={r.score} />
                  </td>
                  {map.packs.map((p) => (
                    <td key={p.name} className="px-4 py-3">
                      <HeatCell score={r.packs[p.name]} />
                    </td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </>
  )
}

const CELL: Record<string, string> = { healthy: 'bg-healthy-tint', warning: 'bg-warning-tint', critical: 'bg-critical-tint', neutral: 'bg-surface-secondary' }

function HeatCell({ score }: { score: number | null | undefined }) {
  const band = bandOf(score)
  return (
    <span className={`inline-flex min-w-[7rem] flex-col rounded-md px-2 py-1 text-primary ${CELL[band.tone]}`} data-band={band.tone}>
      <span className="font-semibold tabular-nums">{score === null || score === undefined ? 'None' : score}</span>
      <span className="text-caption">{band.label}</span>
    </span>
  )
}
