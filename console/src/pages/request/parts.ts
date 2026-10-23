import type { JobSummary } from '@iaf/console-kit'
import type { ConsoleAction, ConsoleType } from '../../lib/descriptors'
import { lowerFirst } from '../../lib/plain'
import type { ApprovalsDecided, BatchProgress, BffJobDetail, BffJobSummary, RetryFix } from '../../lib/types'

/*
 * What the request page says about a job, worked out from the BFF's job alone: the workflow it
 * runs as one of the console's actions, what went wrong, the step a retry starts from, the fix a
 * retry sends, and what one decision on a request's approvals did. Pure, so the words are tested.
 */

/** The type and action a job's workflow is, when the console knows it: its create, its update or one of its verbs. */
export function actionOfJob(types: ConsoleType[], workflow: string): { type: ConsoleType; action: ConsoleAction; create: boolean } | undefined {
  for (const type of types) {
    if (type.create?.workflow === workflow) return { type, action: type.create, create: true }
    if (type.update?.workflow === workflow) return { type, action: type.update, create: false }
    const action = type.actions.find((a) => a.workflow === workflow)
    if (action) return { type, action, create: false }
  }
  return undefined
}

export interface Failure {
  /** the step that failed, when the job names one */
  step?: string
  errorClass?: string
  /** the provider's or the approver's words */
  message: string
  /** the platform says running it again can help */
  retryable?: boolean
  /** an approver said no */
  rejected?: { by?: string; comment?: string }
}

/** What went wrong, from the failing step's own error, else the job's class and step; a batch's from its rows. */
export function failureOf(job: BffJobDetail): Failure | null {
  const ended = job.status === 'failed' || job.status === 'rolled-back' || job.status === 'needs-attention'
  if (!ended && !job.errorClass) return null
  if (job.errorClass === 'rejected') {
    const no = job.approvals?.find((a) => a.status === 'rejected')
    const by = no?.decidedBy ? deciderOf(no.decidedBy) : undefined
    return { step: job.failedStep ?? 'approve', errorClass: 'rejected', message: by ? `${upperFirst(by)} said no to this request.` : 'An approver said no to this request.', retryable: false, rejected: { by, comment: no?.comment } }
  }
  if (job.batch || job.kind === 'batch') {
    const failed = job.progress?.failed ?? job.batch?.rows.filter((r) => r.status === 'failed').length ?? 0
    const total = job.progress?.total ?? job.batch?.rows.length ?? 0
    if (!failed && job.status !== 'failed') return null
    return { message: failed ? `${failed} of ${total} ${total === 1 ? 'row' : 'rows'} failed. The rows below say why.` : 'The run stopped before every row ran.', retryable: failed > 0 }
  }
  const step = job.steps?.find((s) => s.status === 'failed' && s.id === job.failedStep) ?? job.steps?.find((s) => s.status === 'failed')
  return { step: job.failedStep ?? step?.id, errorClass: job.errorClass ?? step?.error?.class, message: step?.error?.message ?? (job.failedStep ? `The step ${job.failedStep} failed.` : 'The platform recorded a failure without a message.'), retryable: step?.error?.retryable }
}

/** The step a retry starts from unless the person picks another: the step a paused job waits on, a failed job's failed step, a rolled-back job's first (its compensations undid the rest). */
export function defaultFromStep(job: BffJobDetail): string | undefined {
  const first = job.steps?.[0]?.id
  if (job.status === 'needs-attention') return job.attention?.step ?? job.failedStep
  if (job.status === 'rolled-back') return first
  return job.failedStep ?? job.steps?.find((s) => s.status === 'failed')?.id ?? first
}

const same = (a: unknown, b: unknown) => JSON.stringify(a) === JSON.stringify(b)

/**
 * What a retry sends: only the inputs the person changed (the BFF merges them over the job's
 * own), and the step only when it is not the one the retry would start from anyway. Nothing
 * changed is a plain retry, with no body.
 */
export function retryFixOf(original: Record<string, unknown>, edited: Record<string, unknown>, fromStep: string | undefined, defaultStep: string | undefined): RetryFix | undefined {
  const input: Record<string, unknown> = {}
  for (const [k, v] of Object.entries(edited)) if (!same(original[k], v)) input[k] = v
  const fix: RetryFix = {}
  if (Object.keys(input).length) fix.input = input
  if (fromStep && fromStep !== defaultStep) fix.fromStep = fromStep
  return fix.input || fix.fromStep ? fix : undefined
}

/** One decision's outcome in a sentence: how many it decided, and why the rest were left. */
export function decidedLine(result: ApprovalsDecided): string {
  const verb = result.decision === 'approve' ? 'Approved' : 'Rejected'
  const n = result.decided.length
  const decided = n === 0 ? 'Nothing was decided.' : `${verb} ${n === 1 ? 'one approval' : `${n} approvals`}.`
  if (!result.left.length) return decided
  const reasons = [...new Set(result.left.map((l) => l.reason))]
  return `${decided} ${result.left.length === 1 ? 'One was' : `${result.left.length} were`} left: ${reasons.map(lowerFirst).join('; ')}.`
}

/** A batch's rows counted by status, in the order a person reads them. */
export function rowCounts(batch: BatchProgress): { status: string; count: number }[] {
  const order = ['running', 'waiting-approval', 'queued', 'pending', 'succeeded', 'failed', 'cancelled', 'skipped']
  const rank = (status: string) => (order.includes(status) ? order.indexOf(status) : order.length)
  const counts = new Map<string, number>()
  for (const r of batch.rows) counts.set(r.status, (counts.get(r.status) ?? 0) + 1)
  return [...counts].map(([status, count]) => ({ status, count })).sort((a, b) => rank(a.status) - rank(b.status))
}

/** Who decided an approval, in words: a person by their id, or the platform's own approver as the group it stands for. */
export function deciderOf(by: string): string {
  return by.startsWith('approver:') ? `an approver of ${by.slice('approver:'.length)}` : by
}

const upperFirst = (s: string) => s.charAt(0).toUpperCase() + s.slice(1)

/** What a row's status says in words, for the rows' table. */
export const ROW_WORDS: Record<string, string> = {
  pending: 'Waits for its wave',
  'waiting-approval': 'Waits for an approver',
  queued: 'Waits for a free slot',
  running: 'Running',
  succeeded: 'Succeeded',
  failed: 'Failed',
  cancelled: 'Cancelled',
  skipped: 'Not run',
}

/** A job's child jobs as the step timeline pairs them with its run steps. */
export function childrenOf(job: BffJobDetail): JobSummary[] | undefined {
  return job.children?.map((c: BffJobSummary) => ({
    id: c.jobId,
    workflow: c.workflow,
    title: c.title ?? c.workflow,
    version: c.version ?? '',
    status: c.status,
    requester: c.requester ?? '',
    channel: 'console',
    startedAt: c.startedAt ?? '',
    endedAt: c.endedAt,
    durationMs: c.durationMs,
    parentId: job.jobId,
    kind: c.kind === 'batch' ? 'batch' : 'single',
    progress: c.progress,
    wfrn: c.wfrn,
    failedStep: c.failedStep,
    errorClass: c.errorClass,
  }))
}
