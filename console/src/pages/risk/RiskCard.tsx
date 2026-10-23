import { useMemo } from 'react'
import { SkeletonRows, cx } from '@iaf/ui'
import { DotTag, Section, useNow } from '@iaf/console-kit'
import { useConsole } from '../../app/console'
import { bff } from '../../lib/bff'
import { partsOfWfrn, type ConsoleType } from '../../lib/descriptors'
import { useQuery } from '../../lib/queries'
import { RISK_WORDS, costDeltaOf, failedRecentlyOf, riskKindOf, riskOf, type Risk, type RiskTarget } from '../../lib/risk'
import type { BffJobDetail, BlastRadius, ConsolePreview, ConsoleResource } from '../../lib/types'

/*
 * The change risk of a request in words (WP 9.10): its level and score, why, and, when it is high,
 * that it needs an approver. Every preview shows it (the action, edit and bulk drawers, the create
 * review) and so does the request page. The score is lib/risk.ts's; this file only gathers what it
 * reads: the resource as the BFF holds it, its last 30 days of requests and what depends on it.
 */

export interface RiskCardProps {
  risk: Risk
  /** the preview's policy already sends it to an approver */
  policyAsks?: boolean
  compact?: boolean
  className?: string
}

export function RiskCard({ risk, policyAsks, compact, className }: RiskCardProps) {
  const words = RISK_WORDS[risk.level]
  const approver = risk.needsApprover ? (policyAsks ? 'It needs an approver: policy already asks for one, and the request waits for them.' : 'It needs an approver: the request waits for one before it runs.') : null
  return (
    <section aria-label="Change risk" data-risk={risk.level} data-risk-score={risk.score} className={cx('rounded-lg border px-3 py-2.5', risk.level === 'high' ? 'border-critical/40 bg-critical-tint/30' : risk.level === 'medium' ? 'border-warning/40 bg-warning-tint/30' : 'border-stroke-light bg-surface-secondary/60', className)}>
      <p className="flex flex-wrap items-center gap-x-2 gap-y-1">
        <DotTag tone={words.tone}>{words.title}</DotTag>
        <span className="text-caption tabular-nums text-secondary">{`Score ${risk.score} of 100`}</span>
      </p>
      <p className={cx('mt-1.5 text-primary', compact ? 'text-caption' : 'text-sm', risk.needsApprover && 'font-medium')}>{approver ?? words.line}</p>
      {risk.reasons.length > 0 && (
        <ul aria-label="Why" className={cx('mt-1.5 list-disc space-y-0.5 pl-5 text-secondary', compact ? 'text-caption' : 'text-sm')}>
          {risk.reasons.map((r) => (
            <li key={r.id}>{r.text}</li>
          ))}
        </ul>
      )}
    </section>
  )
}

/** What a resource's risk reads besides the preview: its row as the BFF holds it and its trail. */
function useResourceFacts(type: Pick<ConsoleType, 'name'> | undefined, wfrn: string | undefined, opts: { radius: boolean; skipJob?: string }) {
  const enabled = !!type && !!wfrn
  const name = type?.name ?? ''
  const detail = useQuery(['console-resource', name, wfrn ?? ''], () => bff.resources.get(name, wfrn ?? ''), { enabled })
  const activity = useQuery(['console-activity', name, wfrn ?? '', 'risk'], () => bff.resources.activity(name, wfrn ?? '', { limit: 100 }), { enabled })
  const radius = useQuery(['risk-radius', name, wfrn ?? ''], () => bff.resources.blastRadius(name, wfrn ?? ''), { enabled: enabled && opts.radius })
  const now = useNow(60_000)
  const entries = activity.data?.items.filter((e) => !opts.skipJob || e.job?.jobId !== opts.skipJob)
  return { detail: detail.data, failedRecently: failedRecentlyOf(entries, now), radius: radius.data, loading: detail.loading || activity.loading }
}

export interface ResourceRiskProps {
  type: ConsoleType
  wfrn: string
  verb: string
  destructive: boolean
  preview: ConsolePreview | undefined
  compact?: boolean
  className?: string
}

/** The risk of one verb on one resource, beside its preview. */
export function ResourceRisk({ type, wfrn, verb, destructive, preview, compact, className }: ResourceRiskProps) {
  const kind = riskKindOf(verb)
  // a destructive preview carries its blast radius; for anything else the console reads it
  const facts = useResourceFacts(type, wfrn, { radius: !preview?.blastRadius })
  const risk = useMemo(() => {
    const d = facts.detail
    const target: RiskTarget = { attributes: d?.attributes, tags: d?.tags, drift: d?.drift }
    return riskOf({ kind, destructive, targets: [target], radius: preview?.blastRadius ?? facts.radius, failedRecently: facts.failedRecently, monthlyCostDelta: costDeltaOf(preview, kind) })
  }, [facts.detail, facts.radius, facts.failedRecently, kind, destructive, preview])
  if (facts.loading && !facts.detail) return <SkeletonRows n={1} rowClassName="h-14" />
  return <RiskCard risk={risk} policyAsks={preview?.policy.decision === 'needs-approval'} compact={compact} className={className} />
}

/** The risk of a create, from its input, its tags and the preview's cost. */
export function CreateRisk({ input, tags, preview, className }: { input: Record<string, unknown>; tags: Record<string, string>; preview: ConsolePreview | undefined; className?: string }) {
  const risk = riskOf({ kind: 'create', targets: [{ attributes: input, tags }], monthlyCostDelta: costDeltaOf(preview, 'create') })
  return <RiskCard risk={risk} policyAsks={preview?.policy.decision === 'needs-approval'} className={className} />
}

/** The risk of a bulk run: its rows, what depends on them together, and how their last requests ended. */
export function BulkRisk({ verb, destructive, rows, affected, policy, className }: { verb: string; destructive: boolean; rows: readonly ConsoleResource[]; affected: BlastRadius | undefined; policy: ConsolePreview['policy'] | undefined; className?: string }) {
  const now = useNow(60_000)
  const failedRecently = rows.filter((r) => r.lastJob && ['failed', 'cancelled', 'rolled-back', 'needs-attention'].includes(String(r.lastJob.status)) && Date.parse(r.lastJob.endedAt ?? r.lastJob.startedAt ?? '') >= now - 30 * 86_400_000).length
  const risk = riskOf({ kind: riskKindOf(verb), destructive, targets: rows.map((r) => ({ attributes: r.attributes, tags: r.tags, drift: r.drift })), radius: affected, failedRecently })
  return <RiskCard risk={risk} policyAsks={policy?.decision === 'needs-approval'} compact className={className} />
}

/** The risk of the request a page shows: the verb its workflow runs, on the resource it names. */
export function RequestRisk({ job, className }: { job: BffJobDetail; className?: string }) {
  const { types } = useConsole()
  const parts = job.wfrn ? partsOfWfrn(job.wfrn) : null
  const type = parts ? types.find((t) => t.name === parts.type) : undefined
  const action = type ? [...type.actions, ...(type.create ? [type.create] : []), ...(type.update ? [type.update] : [])].find((a) => a.workflow === job.workflow) : undefined
  const verb = action?.verb ?? (/^delete-/.test(job.workflow) ? 'delete' : /^create-/.test(job.workflow) ? 'create' : 'change')
  const kind = riskKindOf(verb)
  const facts = useResourceFacts(kind === 'create' ? undefined : type, job.wfrn, { radius: !job.preview?.blastRadius, skipJob: job.jobId })
  if (job.kind === 'batch' || !type || !job.wfrn) return null
  const d = facts.detail
  const attributes = kind === 'create' ? job.input : d?.attributes
  const risk = riskOf({ kind, destructive: action?.destructive, targets: [{ attributes, tags: d?.tags, drift: d?.drift }], radius: job.preview?.blastRadius ?? facts.radius, failedRecently: facts.failedRecently, monthlyCostDelta: costDeltaOf(job.preview, kind) })
  return (
    <Section title="Change risk" description="How much could go wrong, worked out from what it does, where it runs, what depends on it and how its recent requests went." className={className}>
      <RiskCard risk={risk} policyAsks={(job.approvals ?? []).length > 0} />
    </Section>
  )
}
