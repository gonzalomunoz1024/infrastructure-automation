import { EmptyState, SkeletonRows } from '@iaf/ui'
import { DotTag, Section, StatusBadge, When } from '@iaf/console-kit'
import { ExplainedError } from '../../app/commands'
import { Link, pathFor } from '../../app/router'
import { useQuery } from '../../lib/queries'
import { SEVERITY_WORDS, STATUS_WORDS, scoreOf, scorecardsApi } from '../../lib/scorecards'
import { ScoreTag } from '../compliance/ScorecardPage'
import type { TabProps } from './tabs'

/*
 * The resource page's Compliance tab (WP 9.6): every rule that applies to it, failing first,
 * with why and since when; a failing rule with a fix links to its list, where Remediate runs it.
 */

export function ComplianceTab({ type, wfrn }: TabProps) {
  const q = useQuery(['scorecards', 'resource', type.name, wfrn], () => scorecardsApi.resource(type.name, wfrn))
  const c = q.data
  if (q.loading && !c) return <SkeletonRows n={4} />
  if (!c) return <ExplainedError error={q.error} what="Load its compliance" onRetry={() => void q.refetch()} />
  if (!c.results.length) return <EmptyState icon="shield" title="No rules apply" message="None of the account's rule packs judge this type." />
  return (
    <Section title="Compliance" description={`${c.failed} failing, ${c.passed} passing.`} actions={<ScoreTag score={scoreOf(c.results)} />}>
      {c.evaluatedAt && (
        <p className="-mt-2 mb-3 text-caption text-secondary">
          Last judged <When at={c.evaluatedAt} />
        </p>
      )}
      <ul aria-label="Rules" className="divide-y divide-stroke-light rounded-lg border border-stroke-light">
        {c.results.map((r) => (
          <li key={`${r.pack}/${r.ruleId}`} className="flex flex-wrap items-center gap-x-3 gap-y-1 px-4 py-3 text-sm" data-rule={`${r.pack}/${r.ruleId}`} data-rule-status={r.status}>
            <StatusBadge status={STATUS_WORDS[r.status].status} label={STATUS_WORDS[r.status].label} size="sm" />
            <span className="font-medium text-primary">{r.title}</span>
            <DotTag tone={SEVERITY_WORDS[r.severity].tone} size="sm">
              {SEVERITY_WORDS[r.severity].label}
            </DotTag>
            {r.status === 'fail' && r.message && <span className="basis-full text-secondary">{r.message}</span>}
            {r.status === 'fail' && (
              <span className="basis-full text-caption text-secondary">
                {'Failing since '}
                <When at={r.since} />
                {r.remediable && (
                  <>
                    {'. '}
                    <Link to={pathFor('compliance-rule', { pack: r.pack, rule: r.ruleId })} className="text-accent underline hover:opacity-70">
                      Fix it from the rule
                    </Link>
                  </>
                )}
              </span>
            )}
          </li>
        ))}
      </ul>
    </Section>
  )
}
