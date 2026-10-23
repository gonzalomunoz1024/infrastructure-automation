import { validateAgainst, type Problem } from '@iaf/console-kit'
import { serviceOf } from '../lib/descriptors'
import { rolloutOf, rolloutProblems, waveSizesOf, type FullRollout } from '../lib/rollout'
import type { BatchAccepted, BatchPreview, BffApproval, BffJobDetail, CatalogEntry, CommandAccepted, Dependent, JobStep, JobSteer, JobSummary, Resource, ResourceType } from '../lib/types'
import type { MockData } from './data'
import { authorize, everythingIn, groupsOf, personOf, type Decision } from './directory'
import { accountOfResource, toBffJob } from './resources'
import {
  APPROVAL_MS,
  OPEN,
  RUN_MS,
  accountIdsOf,
  approvalFor,
  approverGroupOf,
  attributesOf,
  blastRadiusOf,
  busyOn,
  classOf,
  createEntryOf,
  failsOn,
  idFrom,
  inputSchemaOf,
  isRecord,
  iso,
  journaled,
  liveDependentsOf,
  needsApprovalOf,
  nounOf,
  pageOf,
  problems400,
  providerOf,
  pushEvent,
  referencedResources,
  refused,
  resourceByWfrn,
  stateFor,
  stateOfResource,
  subjectFieldOf,
  typeOf,
  verbEntryOf,
  wfrnOfResource,
  wfrnPatternOf,
  type ConsoleCommand,
  type ConsoleJobExtra,
  type InFlight,
  type RouteAnswer,
  type RouteContext,
} from './commands'
import { createTagProblems } from './tags'

/*
 * The BFF's jobs as phase 4 steers them (contracts/additions.md, "Changing resources safely"),
 * answered from the dataset: a job's view with what the person may do with it (`can`); retry,
 * skip and abort of a step that failed, since a failed step pauses its job until someone decides
 * (hub decision 24); a rerun of a job that ended badly, as a new job; one call deciding every
 * approval of a request and its rows; and bulk runs (`:batch`), judged row by row first, released
 * in waves, a few rows at a time, stopping when too many fail. Every write is journaled, and a
 * batch moves on the clock the server is given, so a reload finds it where it was.
 */

/** How long one row of a batch takes once it runs. */
export const BATCH_ROW_MS = 1_000
/** How long a job takes to finish once someone skipped the step it waited on. */
export const SKIP_MS = 2_000

export type BatchRowStatus = 'waiting-approval' | 'queued' | 'running' | 'succeeded' | 'failed' | 'skipped' | 'cancelled'

export interface MockBatchRow {
  index: number
  wfrn?: string
  resourceId?: string
  name?: string
  jobId?: string
  status: BatchRowStatus | 'pending'
  error?: string
  wave: number
  input: Record<string, unknown>
  fail?: boolean
  needsApproval?: boolean
  /** when the mock's approver says yes, unless a person decides first */
  approvalAt?: number
  /** when it may start: once released and approved */
  readyAt?: number
  dueAt?: number
}

export interface MockBatch {
  verb: string
  rollout: FullRollout
  rows: MockBatchRow[]
  waves: { index: number; percent: number; size: number; status: string; startedAt?: string; endedAt?: string }[]
  /** the wave released last */
  current: number
  /** every event up to this time has happened */
  clock: number
  stoppedReason?: string
  /** how it ends once its running rows finish, when it stops early */
  stopping?: 'failed' | 'cancelled'
}

const TERMINAL = new Set(['succeeded', 'failed', 'skipped', 'cancelled'])
/** The steps every drawn job has; a failure in any other step names the apply after it. */
const FIXED_STEPS = new Set(['validate', 'approve', 'rollout', 'apply', 'compensate', 'register'])

/* ----------------------------------------------------------------------------
   The job as the BFF answers it
   ------------------------------------------------------------------------- */

/** The steps a job ran, drawn from its status and what the console recorded of it: the attempts, a skip, a pause, a rollback. */
export function stepsOf(job: JobSummary, extra?: ConsoleJobExtra): JobStep[] {
  const at = Date.parse(job.startedAt)
  const step = (id: string, name: string, type: string, status: JobStep['status'], offset: number, more: Partial<JobStep> = {}): JobStep => ({
    id,
    name,
    type,
    status,
    attempt: 1,
    startedAt: status === 'pending' ? undefined : new Date(at + offset).toISOString(),
    durationMs: status === 'succeeded' ? 4_000 + offset / 10 : status === 'failed' ? 12_000 : undefined,
    ...more,
  })
  const status = job.status
  const rejected = status === 'failed' && job.errorClass === 'rejected'
  const failed = status === 'failed' && !rejected
  const skipped = extra?.skipped ?? []
  const failedStep = job.failedStep ?? extra?.attention?.step ?? 'apply'
  const steps: JobStep[] = [step('validate', 'Validate the input', 'set', 'succeeded', 0, { input: { workflow: job.workflow, version: job.version } })]
  // a request the console ran without an approval shows the step as passed over
  const approval: JobStep['status'] = status === 'waiting-approval' ? 'waiting' : rejected ? 'failed' : extra && extra.approvals.length === 0 ? 'skipped' : 'succeeded'
  steps.push(step('approve', 'Wait for an approval', 'approval', approval, 5_000, rejected ? { error: { class: 'rejected', message: 'An approver said no', retryable: false } } : {}))
  if (job.kind === 'batch') steps.push(step('rollout', 'Roll out in waves', 'for', status === 'running' || status === 'queued' ? 'running' : status === 'succeeded' ? 'succeeded' : 'failed', 20_000, { items: job.progress?.total ?? extra?.batch?.rows.length }))
  const apply: JobStep['status'] =
    status === 'waiting-approval' || status === 'queued' ? 'pending' : rejected || status === 'cancelled' || skipped.includes('apply') ? 'skipped' : failed || status === 'needs-attention' || status === 'rolled-back' ? 'failed' : status === 'running' ? 'running' : 'succeeded'
  const applyId = failed && !FIXED_STEPS.has(failedStep) ? failedStep : 'apply'
  const message = `${failedStep} failed: ${job.errorClass === 'not_found' ? 'the provider does not know what it was given' : (job.errorClass ?? 'the provider refused the change')}`
  steps.push(
    step(applyId, applyId === 'apply' ? 'Apply the change' : `Run ${applyId}`, 'provider', apply, 30_000, {
      attempt: extra?.attempts ?? 1,
      error: apply === 'failed' ? { class: job.errorClass ?? 'provider', message, retryable: job.errorClass !== 'not_found' } : undefined,
      output: apply === 'succeeded' ? { ok: true } : undefined,
    }),
  )
  if (status === 'rolled-back') steps.push(step('compensate', 'Undo what ran', 'try', 'succeeded', 45_000))
  steps.push(step('register', 'Register the resource', 'register', status === 'succeeded' ? 'succeeded' : status === 'failed' || status === 'cancelled' || status === 'rolled-back' ? 'skipped' : 'pending', 60_000))
  return steps
}

/** What a paused job waits on; a sample paused before the console knew it waits on its apply, with every choice. */
export function attentionOf(job: JobSummary, extra: ConsoleJobExtra | undefined): { step: string; options: ('retry' | 'skip' | 'abort')[]; since: string } {
  return extra?.attention ?? { step: job.failedStep ?? 'apply', options: ['retry', 'skip', 'abort'], since: job.startedAt }
}

/** The catalog entry a job runs: the console's record of it, else the workflow it names. */
function entryOfJob(data: MockData, job: JobSummary, extra: ConsoleJobExtra | undefined): { rt: ResourceType; entry: CatalogEntry | undefined; verb: string } | undefined {
  if (extra) {
    const rt = typeOf(data, extra.type)
    if (!rt) return undefined
    return { rt, verb: extra.verb, entry: extra.verb === 'create' ? createEntryOf(data, rt) : verbEntryOf(data, rt, extra.verb) }
  }
  const entry = data.catalog.find((e) => e.workflow === job.workflow)
  const rt = entry ? typeOf(data, entry.noun) : undefined
  return rt && entry ? { rt, entry, verb: entry.verb } : undefined
}

type Session = RouteContext['session']

function decisionFor(data: MockData, s: Session, action: string, resource: string): Decision {
  return authorize(data.directory, { accountId: s.accountId, userId: s.userId }, action, resource)
}

/** The jobs service's resources in the account, which a job naming no resource is judged on. */
function jobsPatternOf(data: MockData, s: Session): string {
  return `wfrn:${data.partition}:jobs:${s.region}:${s.accountId}:*`
}

/**
 * What running the job again asks (the BFF's JobRetries.target): the console action of its
 * workflow on its resource, on the type's resources for a create or a batch; a job whose workflow
 * is no type's action asks `jobs:retry`.
 */
export function retryTargetOf(data: MockData, s: Session, job: JobSummary, extra: ConsoleJobExtra | undefined): { action: string; resource: string; wfrn?: string; verb?: string; batch: boolean } {
  const wfrn = job.wfrn ?? extra?.wfrn
  const batch = job.kind === 'batch' || !!extra?.batch
  const of = entryOfJob(data, job, extra)
  if (!of) return { action: 'jobs:retry', resource: wfrn ?? jobsPatternOf(data, s), wfrn, batch }
  return { action: `${serviceOf(of.rt)}:${of.verb}`, resource: wfrn && of.verb !== 'create' && !batch ? wfrn : wfrnPatternOf(data, s, of.rt), wfrn, verb: of.verb, batch }
}

/** Stopping a job (cancel, abort) asks `jobs:cancel` on its resource, else on the account's jobs. */
function stopDecision(data: MockData, s: Session, job: JobSummary, extra: ConsoleJobExtra | undefined): Decision {
  return decisionFor(data, s, 'jobs:cancel', job.wfrn ?? extra?.wfrn ?? jobsPatternOf(data, s))
}

/** Whether a job can run again (JobRetries.retryable): a failed, rolled-back or paused job, or a finished batch with failed rows. */
export function retryableJob(job: JobSummary, extra: ConsoleJobExtra | undefined): boolean {
  if (job.kind === 'batch' || extra?.batch) return !OPEN.has(job.status) && (extra?.batch ? progressOf(extra.batch).failed : (job.progress?.failed ?? 0)) > 0
  return job.status === 'failed' || job.status === 'rolled-back' || job.status === 'needs-attention'
}

/** What the person may do with the job now (the BFF's `can`): what the platform offers, as far as the policies allow. */
export function canOfJob(data: MockData, s: Session, job: JobSummary, extra: ConsoleJobExtra | undefined): JobSteer[] {
  const paused = job.status === 'needs-attention'
  const offered = paused ? attentionOf(job, extra).options : (['retry', 'skip', 'abort'] as const)
  const verbs: JobSteer[] = []
  if (OPEN.has(job.status)) verbs.push('cancel')
  if (retryableJob(job, extra) && (!paused || offered.includes('retry'))) verbs.push('retry')
  if (paused && offered.includes('skip')) verbs.push('skip')
  if (paused && offered.includes('abort')) verbs.push('abort')
  if (!verbs.length) return []
  const target = retryTargetOf(data, s, job, extra)
  const stop = stopDecision(data, s, job, extra).allowed
  const run = decisionFor(data, s, target.action, target.resource).allowed
  return verbs.filter((v) => (v === 'cancel' || v === 'abort' ? stop : run))
}

/** Whether the person may decide an approval: `approvals:decide` in the account, a member of its group, and not the request's own requester. */
function decideRefusal(data: MockData, s: Session, group: string, requester: string): string | null {
  if (requester === s.userId) return 'You asked for this request, so someone else decides it'
  if (!groupsOf(data.directory, s.accountId, s.userId).some((g) => g.name === group)) return `Only the members of ${group} decide it`
  return null
}

function approvalView(data: MockData, s: Session, a: ConsoleJobExtra['approvals'][number], job: JobSummary, wfrn: string | undefined, mayDecide: boolean): BffApproval {
  return {
    approvalId: a.approvalId,
    jobId: job.id,
    workflow: job.workflow,
    step: 'approve',
    group: a.group,
    message: `Waits for an approver from ${a.group}`,
    requestedAt: a.requestedAt ?? job.startedAt,
    timeout: '4h',
    status: a.status,
    decidedBy: a.decidedBy,
    decidedAt: a.decidedAt,
    comment: a.comment,
    wfrn,
    canDecide: a.status === 'pending' && mayDecide && decideRefusal(data, s, a.group, job.requester) === null,
  }
}

/** The approvals of a job and, while a batch runs, its rows' pending ones. */
export function approvalsOf(data: MockData, s: Session, job: JobSummary, extra: ConsoleJobExtra | undefined): BffApproval[] {
  const st = data.console
  const mayDecide = decisionFor(data, s, 'approvals:decide', job.wfrn ?? everythingIn(s.accountId)).allowed
  if (!extra && job.status === 'waiting-approval') return [approvalView(data, s, sampleApprovalOf(data, job, s.accountId), job, job.wfrn, mayDecide)]
  if (!extra || !st) return []
  const own = extra.approvals.map((a) => approvalView(data, s, a, job, extra.wfrn, mayDecide))
  if (!extra.batch) return own
  const rows: BffApproval[] = []
  for (const row of extra.batch.rows) {
    if (row.status !== 'waiting-approval' || !row.jobId) continue
    const rowJob = data.jobs.find((j) => j.id === row.jobId)
    const rowExtra = st.jobs[row.jobId]
    if (!rowJob || !rowExtra) continue
    for (const a of rowExtra.approvals) if (a.status === 'pending') rows.push(approvalView(data, s, a, rowJob, row.wfrn, mayDecide))
  }
  return [...own, ...rows]
}

function progressOf(b: MockBatch): { done: number; total: number; failed: number } {
  const failed = b.rows.filter((r) => r.status === 'failed').length
  return { done: b.rows.filter((r) => r.status === 'succeeded').length + failed, total: b.rows.length, failed }
}

/** `GET /console/v1/jobs/{jobId}`: the job with its steps, approvals, what it waits on, its rows and what the person may do. */
export function bffJobOf(data: MockData, s: Session, job: JobSummary): BffJobDetail {
  const extra = data.console?.jobs[job.id]
  const b = extra?.batch
  return {
    ...toBffJob(job),
    progress: b ? progressOf(b) : job.progress,
    input: extra?.input ?? { requestedBy: job.requester, channel: job.channel },
    output: extra?.output,
    steps: stepsOf(job, extra),
    approvals: approvalsOf(data, s, job, extra),
    attention: job.status === 'needs-attention' ? attentionOf(job, extra) : undefined,
    children: b ? b.rows.map((r) => (r.jobId ? data.jobs.find((j) => j.id === r.jobId) : undefined)).filter((j): j is JobSummary => !!j).map(toBffJob) : undefined,
    can: canOfJob(data, s, job, extra),
    batch: b ? { rollout: b.rollout, waves: b.waves.map((w) => ({ ...w })), rows: b.rows.map((r) => ({ index: r.index, wfrn: r.jobId ? r.wfrn : undefined, jobId: r.jobId, status: r.status, error: r.error, wave: r.wave })), stoppedReason: b.stoppedReason } : undefined,
  }
}

/* ----------------------------------------------------------------------------
   Steering a job: retry, skip, abort, cancel, a rerun, approvals
   ------------------------------------------------------------------------- */

function accepted(job: JobSummary, extra: ConsoleJobExtra | undefined): CommandAccepted {
  return { wfrn: job.wfrn ?? extra?.wfrn ?? '', jobId: job.id, requestId: job.consoleRequestId ?? extra?.consoleRequestId ?? job.id }
}

function endJob(job: JobSummary, status: string, at: number) {
  job.status = status
  job.endedAt = iso(at)
  job.durationMs = Math.max(0, at - Date.parse(job.startedAt))
}

function resourceOf(data: MockData, id: string | undefined): Resource | undefined {
  return id ? data.resources.find((x) => x.id === id) : undefined
}

/** The approval a sample job waiting since before the console knew it waits on: the account's approver group's. */
function sampleApprovalOf(data: MockData, job: JobSummary, accountId: string): ConsoleJobExtra['approvals'][number] {
  return { approvalId: `apr_${idFrom(job.id, 8)}`, group: approverGroupOf(data, accountId), status: 'pending', requestedAt: job.startedAt }
}

/** A sample job the console did not start, taken in hand the first time someone steers or decides it: its record and its flight. */
function adoptSample(data: MockData, job: JobSummary, cmd: ConsoleCommand): ConsoleJobExtra {
  const st = data.console!
  const of = entryOfJob(data, job, undefined)
  const extra: ConsoleJobExtra = { type: of?.rt.name ?? '', verb: of?.verb ?? 'change', wfrn: job.wfrn, consoleRequestId: job.consoleRequestId ?? job.id, input: {}, approvals: [] }
  if (job.status === 'waiting-approval') extra.approvals.push(sampleApprovalOf(data, job, cmd.accountId))
  st.jobs[job.id] = extra
  // nobody but a person moves it on: no approver of the mock's decides it, no clock ends its pause
  if (!st.inFlight.some((f) => f.jobId === job.id)) st.inFlight.push({ jobId: job.id, effect: of?.verb === 'create' ? 'create' : of?.verb === 'delete' ? 'delete' : 'change', stage: job.status === 'waiting-approval' ? 'approval' : 'attention', dueAt: Number.MAX_SAFE_INTEGER })
  return extra
}

/** A paused job goes on: the step again (with the person's fix, judged again), past it, or undone back to where it started. */
function steerPaused(data: MockData, cmd: ConsoleCommand, job: JobSummary, extra: ConsoleJobExtra): RouteAnswer {
  const st = data.console!
  const at = cmd.at
  let f = st.inFlight.find((x) => x.jobId === job.id)
  if (!f) {
    f = { jobId: job.id, resourceId: extra.resourceId, effect: extra.verb === 'create' ? 'create' : extra.verb === 'update' ? 'update' : extra.verb === 'delete' ? 'delete' : 'change', stage: 'attention', dueAt: at }
    st.inFlight.push(f)
  }
  const flight = f
  const step = attentionOf(job, extra).step
  const r = resourceOf(data, flight.resourceId)
  if (cmd.kind === 'job.abort') {
    // the compensations undo what ran; a create's resource is left failed, so a retry can run it again
    endJob(job, 'rolled-back', at)
    st.inFlight = st.inFlight.filter((x) => x !== flight)
    extra.attention = undefined
    extra.output = { rolledBack: extra.approvals.length ? ['validate', 'approve'] : ['validate'] }
    const rt = typeOf(data, extra.type)
    const failed = rt ? stateFor(rt, 'failed') : undefined
    if (r && flight.effect === 'create' && failed) r.status = failed
    if (r) r.updatedAt = iso(at)
    pushEvent(data, 'job.finished', at, cmd.userId, job, r)
    return { status: 202, body: accepted(job, extra) }
  }
  if (cmd.kind === 'job.skip') {
    flight.skipped = [...(flight.skipped ?? []), step]
    extra.skipped = flight.skipped
    flight.dueAt = at + SKIP_MS
  } else {
    const body = isRecord(cmd.body) ? cmd.body : {}
    if (isRecord(body.input) && Object.keys(body.input).length) {
      extra.input = { ...extra.input, ...body.input }
      if (flight.effect === 'update') flight.patch = { ...(flight.patch ?? {}), ...body.input }
    }
    flight.attempt = (flight.attempt ?? 1) + 1
    extra.attempts = flight.attempt
    flight.fail = failsOn(flight.effect, extra.input, undefined, false)
    flight.dueAt = at + RUN_MS
  }
  flight.stage = 'running'
  job.status = 'running'
  job.failedStep = undefined
  job.errorClass = undefined
  extra.attention = undefined
  pushEvent(data, 'job.attention-resolved', at, cmd.userId, job, r)
  return { status: 202, body: accepted(job, extra) }
}

/** An ended job runs again as a new one, judged like the first; a failed create's resource goes back to pending, a finished batch runs its failed rows. */
function rerun(data: MockData, cmd: ConsoleCommand, job: JobSummary, extra: ConsoleJobExtra | undefined): RouteAnswer {
  const st = data.console!
  const at = cmd.at
  const requestId = cmd.ids.requestId ?? `creq_${idFrom(`${cmd.userId}:${at}:${job.id}`, 10)}`
  const jobId = cmd.ids.rerunJobId ?? `job_${idFrom(requestId, 10)}`
  cmd.ids = { ...cmd.ids, requestId, rerunJobId: jobId }
  const of = entryOfJob(data, job, extra)
  if (!of) return { status: 409, body: { error: 'job.not-failed', detail: { status: job.status } } }
  if (extra?.batch) {
    const b = extra.batch
    const failedRows = b.rows.filter((r) => r.status === 'failed')
    return startBatch(data, cmd, { rt: of.rt, entry: of.entry, verb: b.verb, rollout: b.rollout, reason: extra.reason, rows: failedRows.map((r) => ({ wfrn: r.wfrn, resourceId: r.resourceId, name: r.name, input: r.input })), jobId, requestId, retryOf: job.id })
  }
  const body = isRecord(cmd.body) ? cmd.body : {}
  const input = { ...(extra?.input ?? {}), ...(isRecord(body.input) ? body.input : {}) }
  const effect: InFlight['effect'] = of.verb === 'create' ? 'create' : of.verb === 'update' ? 'update' : of.verb === 'delete' ? 'delete' : 'change'
  let r = resourceOf(data, extra?.resourceId)
  let wfrn = job.wfrn ?? extra?.wfrn
  if (!r && effect === 'create') {
    // a create the console never registered (a platform job from before it) registers what its rerun makes
    const resourceId = cmd.ids.resourceId ?? `${of.rt.name}-${idFrom(requestId, 8)}`
    wfrn = cmd.ids.wfrn ?? `wfrn:${data.partition}:${serviceOf(of.rt)}:${cmd.region}:${cmd.accountId}:${of.rt.name}/${resourceId}`
    cmd.ids = { ...cmd.ids, resourceId, wfrn }
    r = mintResource(data, cmd, of.rt, of.entry, resourceId, wfrn, typeof input.name === 'string' && input.name ? input.name : resourceId, input, jobId, undefined)
  }
  const fresh: JobSummary = { id: jobId, workflow: job.workflow, title: job.title, version: job.version, status: 'running', requester: cmd.userId, channel: 'console', startedAt: iso(at), kind: 'single', accountId: cmd.accountId, region: cmd.region, wfrn, consoleRequestId: requestId }
  data.jobs.unshift(fresh)
  const next: ConsoleJobExtra = { type: of.rt.name, verb: of.verb, wfrn, resourceId: r?.id, consoleRequestId: requestId, input, reason: extra?.reason, labels: extra?.labels, approvals: [], retryOf: job.id }
  st.jobs[jobId] = next
  const needs = needsApprovalOf(of.entry, of.verb, effect === 'create' ? input : r?.attributes)
  let approval: CommandAccepted['approval']
  if (needs) {
    fresh.status = 'waiting-approval'
    approval = approvalFor(data, cmd.accountId, next, at)
  }
  st.inFlight.push({
    jobId,
    resourceId: r?.id,
    effect,
    stage: needs ? 'approval' : 'running',
    approvalAt: needs ? at + APPROVAL_MS : undefined,
    dueAt: at + (needs ? APPROVAL_MS : 0) + RUN_MS,
    patch: effect === 'update' ? input : undefined,
    fail: failsOn(effect, input, r?.name, false),
    settledState: effect === 'create' ? (stateFor(of.rt, 'active') ?? 'ready') : effect === 'delete' ? (stateFor(of.rt, 'deleted') ?? 'retired') : undefined,
  })
  if (r && effect === 'create') {
    r.status = stateFor(of.rt, 'pending') ?? r.status
    r.updatedAt = iso(at)
  }
  pushEvent(data, 'job.started', at, cmd.userId, fresh, r)
  return { status: 202, body: { wfrn: wfrn ?? '', jobId, requestId, approval } satisfies CommandAccepted }
}

/** Stops a request: a single job at once (a create stopped early leaves nothing, or a failed resource), a batch with every row not yet done. */
function cancel(data: MockData, cmd: ConsoleCommand, job: JobSummary, extra: ConsoleJobExtra | undefined): RouteAnswer {
  const st = data.console!
  const at = cmd.at
  if (extra?.batch) {
    const b = extra.batch
    b.stoppedReason = `Cancelled by ${personOf(data.directory, cmd.userId)?.name ?? cmd.userId}`
    for (const row of b.rows) if (!TERMINAL.has(row.status)) stopRow(data, row, row.jobId ? 'cancelled' : 'skipped', at, 'The request was cancelled')
    finishBatch(data, job, extra, b, 'cancelled', at)
    return { status: 202, body: { jobId: job.id, status: job.status } }
  }
  endJob(job, 'cancelled', at)
  const flight = st.inFlight.find((f) => f.jobId === job.id)
  st.inFlight = st.inFlight.filter((f) => f.jobId !== job.id)
  const target = resourceOf(data, flight?.resourceId)
  const rt = extra ? typeOf(data, extra.type) : undefined
  if (flight?.effect === 'create' && target && rt) {
    const failed = stateFor(rt, 'failed')
    if (failed) target.status = failed
    else data.resources = data.resources.filter((x) => x.id !== target.id)
  }
  pushEvent(data, 'job.finished', at, cmd.userId, job, target)
  return { status: 202, body: { jobId: job.id, status: job.status } }
}

/** Decides every pending approval of the request and its rows that the person may decide; the rest are left, with why. */
function decideAll(data: MockData, cmd: ConsoleCommand, job: JobSummary, extra: ConsoleJobExtra | undefined): RouteAnswer {
  const st = data.console!
  const at = cmd.at
  const body = isRecord(cmd.body) ? cmd.body : {}
  const decision = body.decision === 'reject' ? 'reject' : 'approve'
  const comment = typeof body.comment === 'string' && body.comment ? body.comment : undefined
  const who = personOf(data.directory, cmd.userId)?.name ?? cmd.userId
  const s: Session = { userId: cmd.userId, accountId: cmd.accountId, region: cmd.region }
  const decided: string[] = []
  const left: { approvalId: string; jobId?: string; reason: string }[] = []
  const decide = (target: JobSummary, a: ConsoleJobExtra['approvals'][number]): boolean => {
    const why = decideRefusal(data, s, a.group, target.requester)
    if (why) {
      left.push({ approvalId: a.approvalId, jobId: target.id, reason: why })
      return false
    }
    Object.assign(a, { status: decision === 'approve' ? 'approved' : 'rejected', decidedBy: cmd.userId, decidedAt: iso(at), comment })
    decided.push(a.approvalId)
    pushEvent(data, 'job.approval-decided', at, cmd.userId, target, undefined)
    return true
  }

  if (extra?.batch) {
    const b = extra.batch
    let rejected = false
    for (const row of b.rows) {
      if (row.status !== 'waiting-approval' || !row.jobId) continue
      const rowJob = data.jobs.find((j) => j.id === row.jobId)
      const rowExtra = st.jobs[row.jobId]
      const a = rowExtra?.approvals.find((x) => x.status === 'pending')
      if (!rowJob || !a || !decide(rowJob, a)) continue
      if (decision === 'approve') {
        row.status = 'queued'
        row.readyAt = at
        rowJob.status = 'queued'
      } else {
        rejected = true
        stopRow(data, row, 'cancelled', at, `Rejected by ${who}${comment ? `: ${comment}` : ''}`, 'rejected')
      }
    }
    if (rejected) {
      // a rejection stops the run: the wave's other waiting rows stop too, and no wave follows
      b.stopping = 'cancelled'
      b.stoppedReason = `Rejected by ${who}${comment ? `: ${comment}` : ''}`
      for (const row of b.rows) if (row.status === 'waiting-approval') stopRow(data, row, 'cancelled', at, b.stoppedReason)
    }
    b.clock = Math.max(b.clock, at)
    advanceBatch(data, job, extra, at)
    return { status: 202, body: { decision, decided, left } }
  }

  const a = extra?.approvals.find((x) => x.status === 'pending')
  if (extra && a && decide(job, a)) {
    const f = st.inFlight.find((x) => x.jobId === job.id)
    if (decision === 'approve') {
      if (f && f.stage === 'approval') {
        f.stage = 'running'
        f.approvalAt = at
        f.dueAt = at + RUN_MS
      }
      job.status = 'running'
    } else {
      st.inFlight = st.inFlight.filter((x) => x !== f)
      endJob(job, 'failed', at)
      job.failedStep = 'approve'
      job.errorClass = 'rejected'
      pushEvent(data, 'job.finished', at, cmd.userId, job, resourceOf(data, f?.resourceId))
    }
  }
  return { status: 202, body: { decision, decided, left } }
}

/** Applies the job module's commands, when they run and when the journal replays them. */
export function applyJobCommand(data: MockData, cmd: ConsoleCommand): RouteAnswer {
  const st = data.console!
  if (cmd.kind === 'batch.start') return startBatchCommand(data, cmd)
  const job = data.jobs.find((j) => j.id === cmd.ids.jobId)
  if (!job) return { status: 404, body: { error: `No request ${cmd.ids.jobId}` } }
  const paused = job.status === 'needs-attention'
  // a sample paused or waiting before the console knew it is taken in hand when someone steers or decides it
  const adopt = (paused && (cmd.kind === 'job.retry' || cmd.kind === 'job.skip' || cmd.kind === 'job.abort')) || (job.status === 'waiting-approval' && cmd.kind === 'job.decide')
  const extra = st.jobs[job.id] ?? (adopt ? adoptSample(data, job, cmd) : undefined)
  switch (cmd.kind) {
    case 'job.retry':
      return paused && extra ? steerPaused(data, cmd, job, extra) : rerun(data, cmd, job, extra)
    case 'job.skip':
    case 'job.abort':
      return extra ? steerPaused(data, cmd, job, extra) : { status: 409, body: { error: 'job.not-paused', detail: { status: job.status } } }
    case 'job.cancel':
      return cancel(data, cmd, job, extra)
    case 'job.decide':
      return decideAll(data, cmd, job, extra)
    default:
      return { status: 404, body: { error: `No command ${cmd.kind}` } }
  }
}

/* ----------------------------------------------------------------------------
   Bulk runs: judged row by row, released in waves
   ------------------------------------------------------------------------- */

interface BatchRowSpec {
  wfrn?: string
  resourceId?: string
  name?: string
  input: Record<string, unknown>
  tags?: Record<string, string>
}

interface BatchSpec {
  rt: ResourceType
  entry: CatalogEntry | undefined
  verb: string
  rollout: FullRollout
  reason?: string
  rows: BatchRowSpec[]
  jobId: string
  requestId: string
  retryOf?: string
  /** a create batch's minted names, one per row */
  wfrns?: string[]
}

function pluralTitleOf(rt: ResourceType, n: number): string {
  const noun = nounOf(rt).toLowerCase()
  const plural = (rt.console?.title ?? rt.plural).toLowerCase()
  return n === 1 ? noun : plural
}

/** Starts a bulk run: the parent job, one row per resource (a create's minted first), the waves, and the first wave released. */
function startBatch(data: MockData, cmd: ConsoleCommand, spec: BatchSpec): RouteAnswer {
  const st = data.console!
  const at = cmd.at
  const { rt, entry, verb } = spec
  const service = serviceOf(rt)
  const n = spec.rows.length
  const title = `${entry?.title ?? verb} on ${n} ${pluralTitleOf(rt, n)}`
  const parent: JobSummary = { id: spec.jobId, workflow: entry?.workflow ?? `${verb}-${rt.name}`, title, version: entry?.version ?? '1.0.0', status: 'running', requester: cmd.userId, channel: 'console', startedAt: iso(at), kind: 'batch', progress: { done: 0, total: n, failed: 0 }, accountId: cmd.accountId, region: cmd.region, consoleRequestId: spec.requestId }
  const sizes = waveSizesOf(n, spec.rollout.waves)
  const rows: MockBatchRow[] = []
  let index = 0
  const wfrns: string[] = []
  sizes.forEach((w, wave) => {
    for (let k = 0; k < w.size; k++, index++) {
      const row = spec.rows[index]
      let { wfrn, resourceId, name } = row
      if (verb === 'create') {
        resourceId = spec.wfrns?.[index]?.split('/').pop() ?? `${rt.name}-${idFrom(`${spec.requestId}:${index}`, 8)}`
        wfrn = spec.wfrns?.[index] ?? `wfrn:${data.partition}:${service}:${cmd.region}:${cmd.accountId}:${rt.name}/${resourceId}`
        name = typeof row.input.name === 'string' && row.input.name ? row.input.name : resourceId
        wfrns.push(wfrn)
        mintResource(data, cmd, rt, entry, resourceId, wfrn, name, row.input, spec.jobId, row.tags)
      }
      const r = resourceOf(data, resourceId)
      rows.push({
        index,
        wfrn,
        resourceId,
        name: name ?? r?.name ?? resourceId,
        status: 'pending',
        wave,
        input: row.input,
        fail: failsOn(verb === 'create' ? 'create' : 'change', row.input, name ?? r?.name, !spec.retryOf),
        needsApproval: needsApprovalOf(entry, verb, verb === 'create' ? row.input : r?.attributes),
      })
    }
  })
  data.jobs.unshift(parent)
  const batch: MockBatch = { verb, rollout: spec.rollout, rows, waves: sizes.map((w, i) => ({ index: i, percent: w.percent, size: w.size, status: 'pending' })), current: 0, clock: at }
  const extra: ConsoleJobExtra = { type: rt.name, verb, consoleRequestId: spec.requestId, input: { verb, rows: n, rollout: spec.rollout }, reason: spec.reason, approvals: [], retryOf: spec.retryOf, batch }
  st.jobs[parent.id] = extra
  st.batches = [...(st.batches ?? []), parent.id]
  pushEvent(data, 'job.started', at, cmd.userId, parent, undefined)
  releaseWave(data, parent, extra, batch, 0, at)
  advanceBatch(data, parent, extra, at)
  cmd.ids = { ...cmd.ids, jobId: cmd.kind === 'batch.start' ? spec.jobId : cmd.ids.jobId, requestId: spec.requestId, rerunJobId: cmd.kind === 'batch.start' ? undefined : spec.jobId, wfrns: verb === 'create' ? wfrns : undefined }
  if (cmd.kind !== 'batch.start') return { status: 202, body: { wfrn: '', jobId: parent.id, requestId: spec.requestId } satisfies CommandAccepted }
  const answer: BatchAccepted = { jobId: parent.id, rows: n, requestId: spec.requestId, wfrns: verb === 'create' ? wfrns : undefined }
  return { status: 202, body: answer }
}

/** A create batch's row: the resource is minted pending, carrying its tags, before its wave is released. */
function mintResource(data: MockData, cmd: ConsoleCommand, rt: ResourceType, entry: CatalogEntry | undefined, id: string, wfrn: string, name: string, input: Record<string, unknown>, jobId: string, tags: Record<string, string> | undefined): Resource {
  const owner = (typeof input.owner === 'string' && input.owner) || data.directory.accounts.find((a) => a.accountId === cmd.accountId)?.owningTeam || `team-${serviceOf(rt)}`
  const r: Resource = { id, type: rt.name, name, status: stateFor(rt, 'pending') ?? rt.states?.[0] ?? 'pending', attributes: attributesOf(rt, input, owner), owner, visibility: 'team', createdBy: { jobId, workflow: entry?.workflow ?? `create-${rt.name}`, version: entry?.version }, builtFrom: referencedResources(data, entry?.input, input).map((x) => x.id), createdAt: iso(cmd.at), updatedAt: iso(cmd.at), wfrn, accountId: cmd.accountId, region: cmd.region }
  data.resources.unshift(r)
  if (tags && Object.keys(tags).length) data.console!.tags[wfrn] = { ...tags }
  return r
}

function startBatchCommand(data: MockData, cmd: ConsoleCommand): RouteAnswer {
  const rt = typeOf(data, cmd.type)
  if (!rt) return { status: 404, body: { error: `No type ${cmd.type}` } }
  const body = (isRecord(cmd.body) ? cmd.body : {}) as Record<string, unknown>
  const verb = String(body.verb)
  const entry = verb === 'create' ? createEntryOf(data, rt) : verbEntryOf(data, rt, verb)
  const subject = entry && verb !== 'create' ? subjectFieldOf(entry, rt) : undefined
  const defaults = isRecord(body.defaults) ? body.defaults : {}
  const items = Array.isArray(body.items) ? body.items.filter(isRecord) : []
  const sharedTags = isRecord(body.tags) ? stringsOf(body.tags) : undefined
  const rows: BatchRowSpec[] = items.map((item) => {
    const input = { ...defaults, ...(isRecord(item.input) ? item.input : {}) }
    if (verb === 'create') return { input, tags: { ...(sharedTags ?? {}), ...(isRecord(item.tags) ? stringsOf(item.tags) : {}) } }
    const r = typeof item.wfrn === 'string' ? resourceByWfrn(data, rt, item.wfrn) : undefined
    return { wfrn: r ? wfrnOfResource(data, cmd, rt, r) : (item.wfrn as string | undefined), resourceId: r?.id, name: r?.name ?? r?.id, input: subject && r ? { ...input, [subject]: r.id } : input }
  })
  const requestId = cmd.ids.requestId ?? `creq_${idFrom(`${cmd.userId}:${cmd.at}:batch`, 10)}`
  const jobId = cmd.ids.jobId ?? `job_${idFrom(requestId, 10)}`
  return startBatch(data, cmd, { rt, entry, verb, rollout: rolloutOf(isRecord(body.rollout) ? body.rollout : undefined, entry?.batch?.rollout), reason: typeof body.reason === 'string' ? body.reason : undefined, rows, jobId, requestId, wfrns: cmd.ids.wfrns })
}

function stringsOf(o: Record<string, unknown>): Record<string, string> {
  return Object.fromEntries(Object.entries(o).filter((e): e is [string, string] => typeof e[1] === 'string'))
}

/** Releases a wave: each row gets its own job, waiting for its approver when its run needs one, else queued for a free slot. */
function releaseWave(data: MockData, parent: JobSummary, extra: ConsoleJobExtra, b: MockBatch, wave: number, at: number) {
  const st = data.console!
  const w = b.waves[wave]
  if (!w) return
  b.current = wave
  w.startedAt = iso(at)
  const rt = typeOf(data, extra.type)
  const entry = rt ? (b.verb === 'create' ? createEntryOf(data, rt) : verbEntryOf(data, rt, b.verb)) : undefined
  const rows = b.rows.filter((r) => r.wave === wave)
  for (const row of rows) {
    const jobId = `job_${idFrom(`${parent.id}:${row.index}`, 10)}`
    row.jobId = jobId
    const job: JobSummary = { id: jobId, workflow: parent.workflow, title: `${entry?.title ?? b.verb} ${row.name ?? row.resourceId ?? ''}`.trim(), version: parent.version, status: row.needsApproval ? 'waiting-approval' : 'queued', requester: parent.requester, channel: 'batch', startedAt: iso(at), kind: 'single', parentId: parent.id, accountId: parent.accountId, region: parent.region, wfrn: row.wfrn, consoleRequestId: parent.consoleRequestId }
    data.jobs.unshift(job)
    const rowExtra: ConsoleJobExtra = { type: extra.type, verb: b.verb, wfrn: row.wfrn, resourceId: row.resourceId, consoleRequestId: extra.consoleRequestId, input: row.input, approvals: [], parentId: parent.id }
    st.jobs[jobId] = rowExtra
    if (row.needsApproval) {
      approvalFor(data, parent.accountId ?? '', rowExtra, at, jobId)
      row.status = 'waiting-approval'
      row.approvalAt = at + APPROVAL_MS
    } else {
      row.status = 'queued'
      row.readyAt = at
    }
  }
  w.status = rows.some((r) => r.status === 'waiting-approval') ? 'waiting-approval' : 'running'
  pushEvent(data, 'batch.wave', at, parent.requester, parent, undefined)
}

/** Ends a row that will not run (or run no further): its job ends too. */
function stopRow(data: MockData, row: MockBatchRow, status: 'cancelled' | 'skipped', at: number, why: string, errorClass?: string) {
  row.status = status
  row.error = why
  const job = row.jobId ? data.jobs.find((j) => j.id === row.jobId) : undefined
  if (job && OPEN.has(job.status)) {
    endJob(job, errorClass === 'rejected' ? 'failed' : 'cancelled', at)
    if (errorClass) {
      job.failedStep = 'approve'
      job.errorClass = errorClass
    }
  }
}

/** A row's run ends: what it did lands on its resource, and the batch counts it. */
function finishRow(data: MockData, parent: JobSummary, extra: ConsoleJobExtra, b: MockBatch, row: MockBatchRow, at: number) {
  const st = data.console!
  const job = row.jobId ? data.jobs.find((j) => j.id === row.jobId) : undefined
  const rowExtra = row.jobId ? st.jobs[row.jobId] : undefined
  const rt = typeOf(data, extra.type)
  const r = resourceOf(data, row.resourceId)
  if (row.fail) {
    row.status = 'failed'
    row.error = `The provider refused the change on ${row.name ?? row.resourceId}`
    if (job) {
      endJob(job, 'failed', at)
      job.failedStep = 'apply'
      job.errorClass = 'provider'
    }
  } else {
    row.status = 'succeeded'
    if (job) endJob(job, 'succeeded', at)
    if (r && rt) {
      if (b.verb === 'create') {
        r.status = stateFor(rt, 'active') ?? 'ready'
        r.providerId = `${providerOf(serviceOf(rt))}:${r.id}`
        if (rowExtra) rowExtra.output = { [`${rt.name}Id`]: r.id, name: r.name, wfrn: r.wfrn }
      } else if (b.verb === 'delete') {
        r.status = stateFor(rt, 'deleted') ?? 'retired'
        if (rowExtra) rowExtra.output = { deleted: r.id }
      } else if (b.verb === 'update') {
        const entry = verbEntryOf(data, rt, 'update')
        const subject = entry ? subjectFieldOf(entry, rt) : undefined
        const changes = Object.fromEntries(Object.entries(row.input).filter(([k]) => k !== subject && k !== 'reason'))
        r.attributes = { ...r.attributes, ...changes }
        if (rowExtra) rowExtra.output = { changed: Object.keys(changes) }
      } else if (rowExtra) rowExtra.output = { [`${rt.name}Name`]: r.name ?? r.id, ok: true }
      r.updatedAt = iso(at)
    }
  }
  parent.progress = progressOf(b)
  if (job) pushEvent(data, 'job.finished', at, parent.requester, job, r)
}

/** Ends the batch: every row not done is skipped, the waves not run stop, the parent ends as told. */
function finishBatch(data: MockData, parent: JobSummary, extra: ConsoleJobExtra, b: MockBatch, status: string, at: number) {
  const st = data.console!
  for (const row of b.rows) if (!TERMINAL.has(row.status)) stopRow(data, row, row.jobId ? 'cancelled' : 'skipped', at, b.stoppedReason ?? 'The run stopped before this row')
  for (const w of b.waves) {
    if (w.status === 'pending') w.status = 'stopped'
    else if (!w.endedAt) {
      w.status = 'stopped'
      w.endedAt = iso(at)
    }
  }
  parent.progress = progressOf(b)
  endJob(parent, status, at)
  const counts = { succeeded: 0, failed: 0, skipped: 0, cancelled: 0 }
  for (const row of b.rows) if (row.status in counts) counts[row.status as keyof typeof counts]++
  extra.output = counts
  st.batches = (st.batches ?? []).filter((id) => id !== parent.id)
  pushEvent(data, 'job.finished', at, parent.requester, parent, undefined)
}

/** A wave's rows are all done: the next wave goes, unless too many failed, someone stopped it, or none is left. */
function endWave(data: MockData, parent: JobSummary, extra: ConsoleJobExtra, b: MockBatch, at: number) {
  const w = b.waves[b.current]
  const rows = b.rows.filter((r) => r.wave === b.current)
  w.status = rows.some((r) => r.status === 'failed') ? 'failed' : rows.some((r) => r.status === 'cancelled' || r.status === 'skipped') ? 'stopped' : 'succeeded'
  w.endedAt = iso(at)
  if (b.stopping) return finishBatch(data, parent, extra, b, b.stopping, at)
  const released = b.rows.filter((r) => r.wave <= b.current)
  const failed = released.filter((r) => r.status === 'failed').length
  const share = released.length ? (failed * 100) / released.length : 0
  const more = b.current + 1 < b.waves.length
  if (failed > 0 && share > b.rollout.stopIfFailedPercent) {
    if (more) b.stoppedReason = `${failed} of ${released.length} ${released.length === 1 ? 'row' : 'rows'} failed (${Math.round(share)}%), more than the ${b.rollout.stopIfFailedPercent}% the rollout allows, so no further wave went out`
    return finishBatch(data, parent, extra, b, 'failed', at)
  }
  if (more) return releaseWave(data, parent, extra, b, b.current + 1, at)
  finishBatch(data, parent, extra, b, failed > 0 ? 'failed' : 'succeeded', at)
}

/**
 * Moves a batch to `now`, event by event: rows due finish, the mock's approver says yes to rows
 * waiting longer than an approval takes, queued rows take the free slots (`maxParallel`), and a
 * wave whose rows are all done hands over to the next.
 */
export function advanceBatch(data: MockData, parent: JobSummary, extra: ConsoleJobExtra, now: number) {
  const b = extra.batch
  if (!b) return
  for (let guard = 0; guard < 20_000 && OPEN.has(parent.status); guard++) {
    // the free slots go to the rows ready longest, in their order
    let running = b.rows.filter((r) => r.status === 'running').length
    const ready = b.rows.filter((r) => r.wave === b.current && r.status === 'queued' && (r.readyAt ?? Infinity) <= b.clock).sort((x, y) => (x.readyAt ?? 0) - (y.readyAt ?? 0) || x.index - y.index)
    for (const row of ready) {
      if (running >= b.rollout.maxParallel) break
      row.status = 'running'
      row.dueAt = b.clock + BATCH_ROW_MS
      const job = row.jobId ? data.jobs.find((j) => j.id === row.jobId) : undefined
      if (job) job.status = 'running'
      running++
    }
    const w = b.waves[b.current]
    if (w && w.status === 'waiting-approval' && b.rows.some((r) => r.wave === b.current && r.status === 'running')) w.status = 'running'
    const inWave = b.rows.filter((r) => r.wave === b.current)
    if (inWave.every((r) => TERMINAL.has(r.status))) {
      endWave(data, parent, extra, b, b.clock)
      continue
    }
    const times = [...b.rows.filter((r) => r.status === 'running').map((r) => r.dueAt ?? Infinity), ...b.rows.filter((r) => r.status === 'waiting-approval').map((r) => r.approvalAt ?? Infinity)]
    const next = Math.min(...times)
    if (!Number.isFinite(next) || next > now) return
    b.clock = next
    for (const row of b.rows) {
      if (row.status === 'running' && row.dueAt === next) finishRow(data, parent, extra, b, row, next)
      else if (row.status === 'waiting-approval' && row.approvalAt === next) {
        const rowExtra = row.jobId ? data.console?.jobs[row.jobId] : undefined
        const a = rowExtra?.approvals.find((x) => x.status === 'pending')
        if (a) Object.assign(a, { status: 'approved', decidedBy: `approver:${a.group}`, decidedAt: iso(next) })
        row.status = 'queued'
        row.readyAt = next
        const job = row.jobId ? data.jobs.find((j) => j.id === row.jobId) : undefined
        if (job) job.status = 'queued'
      }
    }
  }
}

/** Moves every running batch to `now`. */
export function settleBatches(data: MockData, now: number) {
  const st = data.console
  if (!st?.batches?.length) return
  for (const id of [...st.batches]) {
    const parent = data.jobs.find((j) => j.id === id)
    const extra = st.jobs[id]
    if (!parent || !extra?.batch) {
      st.batches = st.batches.filter((x) => x !== id)
      continue
    }
    advanceBatch(data, parent, extra, now)
  }
}

/* ----------------------------------------------------------------------------
   Judging a batch before it runs
   ------------------------------------------------------------------------- */

interface JudgedRow {
  index: number
  wfrn?: string
  resource?: Resource
  name?: string
  state?: string
  dependents: number
  named: Dependent[]
  problems: { code: string; message: string; field?: string }[]
  needsApproval: boolean
}

interface Judged {
  /** what the body gets wrong, before anything about the rows */
  invalid?: RouteAnswer
  rows: JudgedRow[]
  entry?: CatalogEntry
  verb: string
}

const MAX_ROWS = 500

/** Every row of a `:batch` body judged as the run would judge it, without running anything. */
function judgeBatch(data: MockData, ctx: RouteContext, rt: ResourceType, body: Record<string, unknown>, correlation: () => string): Judged {
  const s = ctx.session
  const service = serviceOf(rt)
  const verb = typeof body.verb === 'string' ? body.verb : ''
  if (!verb) return { verb, rows: [], invalid: problems400([{ level: 'error', code: 'required', field: '/verb', message: 'Name the verb to run on every row' }]) }
  const entry = verb === 'create' ? createEntryOf(data, rt) : verbEntryOf(data, rt, verb)
  if (!entry?.batchPath) return { verb, rows: [], invalid: { status: 400, body: { error: 'bulk.unsupported', detail: `${rt.plural} have no batch route for ${verb}: run it on one resource at a time`, problems: [{ level: 'error', code: 'bulk.unsupported', field: '/verb', message: `${verb} does not run in bulk` }] } } }
  const items = body.items
  if (!Array.isArray(items) || items.length === 0) return { verb, entry, rows: [], invalid: problems400([{ level: 'error', code: 'required', field: '/items', message: 'Name at least one row' }]) }
  const max = Math.min(MAX_ROWS, entry.batch?.max ?? MAX_ROWS)
  if (items.length > max) return { verb, entry, rows: [], invalid: { status: 400, body: { error: 'batch.too-large', detail: `${entry.workflow} takes at most ${max} rows in one request; send ${items.length} in smaller batches`, problems: [{ level: 'error', code: 'batch.too-large', field: '/items', message: `At most ${max} rows` }] } } }
  const rollout = rolloutProblems(body.rollout)
  if (rollout.length) return { verb, entry, rows: [], invalid: problems400(rollout) }
  if (body.defaults !== undefined && !isRecord(body.defaults)) return { verb, entry, rows: [], invalid: problems400([{ level: 'error', code: 'type', field: '/defaults', message: 'Defaults are an object of inputs' }]) }

  const accounts = accountIdsOf(data)
  const subject = verb === 'create' ? undefined : subjectFieldOf(entry, rt)
  const schema = verb === 'create' ? entry.input : inputSchemaOf(entry, subject)
  const defaults = isRecord(body.defaults) ? body.defaults : {}
  const rowIds = new Set<string>()
  const rows: JudgedRow[] = []
  const shape: Problem[] = []
  items.forEach((item, index) => {
    if (!isRecord(item)) {
      shape.push({ level: 'error', code: 'type', field: `/items/${index}`, message: `Row ${index + 1} is not an object` })
      return
    }
    const input = { ...defaults, ...(isRecord(item.input) ? item.input : {}) }
    const row: JudgedRow = { index, dependents: 0, named: [], problems: [], needsApproval: false }
    rows.push(row)
    if (verb !== 'create') {
      if (typeof item.wfrn !== 'string' || !item.wfrn) {
        shape.push({ level: 'error', code: 'required', field: `/items/${index}/wfrn`, message: `Row ${index + 1} needs the wfrn of the resource it runs on` })
        return
      }
      const r = resourceByWfrn(data, rt, item.wfrn)
      row.wfrn = item.wfrn
      if (!r || accountOfResource(r, accounts) !== s.accountId) {
        row.problems.push({ code: 'not-found', field: `/items/${index}/wfrn`, message: `Nothing lives at ${item.wfrn} in this account` })
        return
      }
      row.resource = r
      row.wfrn = wfrnOfResource(data, s, rt, r)
      row.name = r.name ?? r.id
      row.state = stateOfResource(data, rt, r)
      rowIds.add(r.id)
      if (classOf(rt, r.status) === 'deleted') row.problems.push({ code: 'state', field: `/items/${index}/wfrn`, message: `${row.name} is already deleted` })
      else if (busyOn(data, r)) row.problems.push({ code: 'state', field: `/items/${index}/wfrn`, message: `A request is already changing ${row.name}` })
      else if (row.state !== 'active' && row.state !== 'failed') row.problems.push({ code: 'state', field: `/items/${index}/wfrn`, message: `${row.name} is ${row.state}; the verb runs on an active or failed resource` })
      row.needsApproval = needsApprovalOf(entry, verb, r.attributes)
    } else {
      row.name = typeof input.name === 'string' ? input.name : undefined
      const tagged = createTagProblems(data, s, rt, input, { ...(isRecord(body.tags) ? body.tags : {}), ...(isRecord(item.tags) ? item.tags : {}) })
      for (const p of tagged?.problems ?? []) row.problems.push({ code: tagged!.error, field: `/items/${index}${p.field ?? ''}`, message: p.message })
      row.needsApproval = needsApprovalOf(entry, verb, input)
    }
    for (const p of validateAgainst(schema, input).filter((x) => x.level === 'error')) row.problems.push({ code: p.code === 'required' ? 'required' : p.code, field: `/items/${index}/input${p.field ?? ''}`, message: p.message })
  })
  if (shape.length) return { verb, entry, rows, invalid: problems400(shape) }

  // every row is the person's to run, as one request asks for each
  const pattern = wfrnPatternOf(data, s, rt)
  for (const row of rows) {
    const d = authorize(data.directory, { accountId: s.accountId, userId: s.userId }, `${service}:${verb}`, row.wfrn ?? pattern)
    if (!d.allowed) return { verb, entry, rows, invalid: refused(d, correlation()) }
  }

  // a delete refuses a row other resources are built from, unless they go in the same batch
  if (verb === 'delete' || entry.destructive) {
    for (const row of rows) {
      if (!row.resource) continue
      const radius = blastRadiusOf(data, s, [row.resource], 3)
      const outside = liveDependentsOf(data, row.resource).filter((x) => !rowIds.has(x.id))
      row.dependents = radius.total
      row.named = radius.dependents
      if (verb === 'delete' && outside.length > 0) row.problems.push({ code: 'dependents.exist', field: `/items/${row.index}/wfrn`, message: `${outside.length} ${outside.length === 1 ? 'resource is' : 'resources are'} built from ${row.name}` })
    }
  }
  return { verb, entry, rows }
}

const PROBLEM_RULES: Record<string, (n: number) => string> = {
  'not-found': (n) => `${n} ${n === 1 ? 'row names' : 'rows name'} nothing in this account`,
  state: (n) => `${n} ${n === 1 ? 'row is' : 'rows are'} not in a state the verb runs on`,
  'dependents.exist': (n) => `${n} ${n === 1 ? 'row has' : 'rows have'} resources built from it; delete those first, or add them to the batch`,
  required: (n) => `${n} ${n === 1 ? 'row misses' : 'rows miss'} a required field`,
  'tags.required': (n) => `${n} ${n === 1 ? 'row misses' : 'rows miss'} a required tag`,
  'tags.invalid': (n) => `${n} ${n === 1 ? 'row has' : 'rows have'} a tag value the policy does not allow`,
}

/** The preview's policy: one deny per kind of problem, an approval when a row waits for one. */
function batchPolicy(data: MockData, s: Session, rt: ResourceType, verb: string, rows: JudgedRow[]): BatchPreview['policy'] {
  const rules: { id: string; result: string; message: string }[] = [{ id: 'rbac.role', result: 'pass', message: `Your role allows ${serviceOf(rt)}:${verb} on every row` }]
  const kinds = new Map<string, number>()
  for (const row of rows) for (const code of new Set(row.problems.map((p) => p.code))) kinds.set(code, (kinds.get(code) ?? 0) + 1)
  for (const [code, n] of kinds) rules.push({ id: code, result: 'deny', message: (PROBLEM_RULES[code] ?? ((k: number) => `${k} rows: ${code}`))(n) })
  const waiting = rows.filter((r) => r.needsApproval).length
  if (waiting) rules.push({ id: 'approval.required', result: 'needs-approval', message: `${waiting} of ${rows.length} ${rows.length === 1 ? 'row waits' : 'rows wait'} for an approver from ${approverGroupOf(data, s.accountId)}` })
  const decision = rules.some((r) => r.result === 'deny') ? 'deny' : rules.some((r) => r.result === 'needs-approval') ? 'needs-approval' : 'allow'
  return { decision, rules, engine: 'mock' }
}


/* ----------------------------------------------------------------------------
   The routes
   ------------------------------------------------------------------------- */

/** The BFF's job routes and bulk runs: `/console/v1/jobs…` and `/console/v1/r/{type}:batch[:preview]`. */
export function jobRoutes(data: MockData, ctx: RouteContext, path: string, params: URLSearchParams, method: string): RouteAnswer | null {
  const st = data.console!
  const s = ctx.session
  const correlation = () => `mock-${idFrom(`${ctx.now}:${path}`, 8)}`
  const run = (cmd: ConsoleCommand): RouteAnswer => journaled(data, ctx, cmd)
  const command = (kind: ConsoleCommand['kind'], job: JobSummary, extra: ConsoleJobExtra | undefined, body: unknown = null): ConsoleCommand => ({ at: ctx.now, kind, userId: s.userId, accountId: s.accountId, region: s.region, type: extra?.type ?? '', wfrn: job.wfrn ?? extra?.wfrn, body, ids: { jobId: job.id } })

  if (path === '/console/v1/jobs' && method === 'GET') {
    let jobs = data.jobs.filter((j) => !j.accountId || j.accountId === s.accountId)
    for (const key of ['status', 'workflow', 'requester'] as const) {
      const want = params.get(key)
      if (want) jobs = jobs.filter((j) => j[key] === want)
    }
    const wfrn = params.get('wfrn')
    if (wfrn) jobs = jobs.filter((j) => j.wfrn === wfrn || st.jobs[j.id]?.wfrn === wfrn)
    return pageOf(jobs.map(toBffJob), params)
  }

  let m = /^\/console\/v1\/jobs\/([^/:]+)(?::(cancel|retry|skip|abort)|\/approvals:decide)?$/.exec(path)
  if (m) {
    const jobId = decodeURIComponent(m[1])
    const job = data.jobs.find((j) => j.id === jobId)
    if (!job) return { status: 404, body: { error: `No request ${jobId}` } }
    if (job.accountId && job.accountId !== s.accountId) return { status: 404, body: { error: `No request ${jobId} in this account` } }
    const extra = st.jobs[jobId]
    const steer = m[2]
    const deciding = path.endsWith('/approvals:decide')
    if (method === 'GET' && !steer && !deciding) return { status: 200, body: bffJobOf(data, s, job) }
    if (method !== 'POST' || (!steer && !deciding)) return { status: 405, body: { error: `${method} is not allowed on ${path}` } }
    const body = ctx.body ?? {}

    if (deciding) {
      if (body.decision !== 'approve' && body.decision !== 'reject') return problems400([{ level: 'error', code: 'enum', field: '/decision', message: 'Decide approve or reject' }])
      if (body.comment !== undefined && (typeof body.comment !== 'string' || body.comment.length > 1000)) return problems400([{ level: 'error', code: 'maxLength', field: '/comment', message: 'A comment is text of up to 1000 characters' }])
      const d = authorize(data.directory, { accountId: s.accountId, userId: s.userId }, 'approvals:decide', job.wfrn ?? everythingIn(s.accountId))
      if (!d.allowed) return refused(d, correlation())
      return run(command('job.decide', job, extra, body))
    }

    if (steer === 'cancel') {
      if (!OPEN.has(job.status)) return { status: 409, body: { error: 'job.not-open', detail: `Request ${jobId} is ${job.status}; only a queued, running, waiting or paused request can be cancelled` } }
      const d = stopDecision(data, s, job, extra)
      if (!d.allowed) return refused(d, correlation())
      return run(command('job.cancel', job, extra))
    }

    if (steer === 'skip' || steer === 'abort') {
      if (job.status !== 'needs-attention') return { status: 409, body: { error: 'job.not-paused', message: `Request ${jobId} is ${job.status}, not paused on a failed step`, detail: { status: job.status } } }
      if (steer === 'abort') {
        const d = stopDecision(data, s, job, extra)
        if (!d.allowed) return refused(d, correlation())
        return run(command('job.abort', job, extra))
      }
      const attention = attentionOf(job, extra)
      const target = retryTargetOf(data, s, job, extra)
      const d = decisionFor(data, s, target.action, target.resource)
      if (!d.allowed) return refused(d, correlation())
      if (body.step !== undefined && body.step !== attention.step) return problems400([{ level: 'error', code: 'step.unknown', field: '/step', message: `The request waits on ${attention.step}, not ${String(body.step)}` }])
      if (!attention.options.includes('skip')) return { status: 409, body: { error: `${attention.step} cannot be skipped: retry it or abort the request` } }
      return run(command('job.skip', job, extra, { step: attention.step }))
    }

    /* retry: in the BFF's order, what may run again, who may run it, the fix, then the resource as it is now */
    if (!retryableJob(job, extra)) return { status: 409, body: { error: 'job.not-failed', message: `Request ${jobId} is ${job.status}: there is nothing to retry`, detail: { status: job.status } } }
    const paused = job.status === 'needs-attention'
    const target = retryTargetOf(data, s, job, extra)
    const d = decisionFor(data, s, target.action, target.resource)
    if (!d.allowed) return refused(d, correlation())
    const fix = { input: isRecord(body.input) ? body.input : {}, fromStep: typeof body.fromStep === 'string' && body.fromStep ? body.fromStep : undefined }
    if (body.input !== undefined && !isRecord(body.input)) return problems400([{ level: 'error', code: 'type', field: '/input', message: 'The fix is an object of inputs merged over the request’s own' }])
    if (Object.keys(fix.input).length || fix.fromStep) {
      if (target.batch) return { status: 400, body: { error: 'a batch runs its failed rows again as they were', problems: [{ level: 'error', code: 'retry.fix-unsupported', field: Object.keys(fix.input).length ? '/input' : '/fromStep', message: 'A batch’s rows run again as they were' }] } }
      if (fix.fromStep && !stepsOf(job, extra).some((x) => x.id === fix.fromStep)) return { status: 400, body: { error: `the job has no step ${fix.fromStep}`, problems: [{ level: 'error', code: 'step.unknown', field: '/fromStep', message: `The request has no step ${fix.fromStep}` }] } }
      const before = extra?.input.name
      if (target.verb === 'create' && 'name' in fix.input && fix.input.name !== before) return { status: 400, body: { error: 'a retried create keeps its name', problems: [{ level: 'error', code: 'retry.name-fixed', field: '/input/name', message: `The resource is named ${String(before ?? 'by its request')}; a retry keeps the name` }] } }
    }
    // the resource the retry runs on, checked like the first request was; a paused job is still its change underway
    const subject = target.wfrn && !target.batch && extra?.resourceId ? resourceOf(data, extra.resourceId) : undefined
    if (subject && !paused) {
      const rt = typeOf(data, subject.type)
      const state = rt ? stateOfResource(data, rt, subject) : subject.status
      const wanted = target.verb === 'create' ? ['failed'] : target.verb === 'update' || target.verb === 'delete' ? ['active', 'failed'] : undefined
      if (wanted ? !wanted.includes(state) : rt && classOf(rt, subject.status) === 'deleted') return { status: 409, body: { error: `the resource is ${state}`, detail: { state } } }
      if (target.verb === 'delete') {
        const live = liveDependentsOf(data, subject)
        if (live.length) {
          const radius = blastRadiusOf(data, s, [subject], 3)
          return { status: 409, body: { error: 'dependents.exist', message: `${live.length} ${live.length === 1 ? 'resource is' : 'resources are'} built from ${subject.name ?? subject.id}; delete or move them first`, correlationId: correlation(), detail: { dependents: radius.dependents, total: radius.total, byAccount: radius.byAccount } } }
        }
      }
    }
    return run(command('job.retry', job, extra, { input: fix.input, fromStep: fix.fromStep }))
  }

  m = /^\/console\/v1\/r\/([a-z0-9-]{1,64}):batch(?::(preview))?$/.exec(path)
  if (m) {
    const rt = typeOf(data, m[1])
    if (!rt) return { status: 404, body: { error: `No resource type ${m[1]}` } }
    if (method !== 'POST') return { status: 405, body: { error: `${method} is not allowed on ${path}` } }
    const body = ctx.body ?? {}
    const judged = judgeBatch(data, ctx, rt, body, correlation)
    if (judged.invalid) return judged.invalid
    const roots = judged.rows.map((r) => r.resource).filter((r): r is Resource => !!r)
    if (m[2] === 'preview') {
      const preview: BatchPreview = {
        verb: judged.verb,
        items: judged.rows.map((r) => ({ index: r.index, wfrn: r.wfrn, name: r.name, state: r.state, dependents: r.dependents, named: r.named, problems: r.problems })),
        affected: blastRadiusOf(data, s, roots, 3),
        policy: batchPolicy(data, s, rt, judged.verb, judged.rows),
      }
      return { status: 200, body: preview }
    }
    const fields = judged.rows.flatMap((r) => r.problems.filter((p) => p.code !== 'not-found' && p.code !== 'state' && p.code !== 'dependents.exist').map((p): Problem => ({ level: 'error', code: p.code, field: p.field, message: p.message })))
    const tagProblem = fields.find((p) => p.code === 'tags.required' || p.code === 'tags.invalid')
    if (tagProblem) return { status: 400, body: { error: tagProblem.code, detail: fields.map((p) => p.message).join('; '), problems: fields } }
    if (fields.length) return problems400(fields)
    const missing = judged.rows.flatMap((r) => r.problems.filter((p) => p.code === 'not-found'))
    if (missing.length) return problems400(missing.map((p) => ({ level: 'error' as const, code: 'not-found', field: p.field, message: p.message })))
    const blocked = judged.rows.filter((r) => r.problems.some((p) => p.code === 'dependents.exist'))
    if (blocked.length) return { status: 409, body: { error: 'dependents.exist', message: `${blocked.length} ${blocked.length === 1 ? 'row has' : 'rows have'} resources built from them; nothing ran`, problems: blocked.map((r) => ({ level: 'error', code: 'dependents.exist', field: `/items/${r.index}/wfrn`, message: r.problems.find((p) => p.code === 'dependents.exist')?.message })), correlationId: correlation(), detail: { rows: blocked.map((r) => ({ wfrn: r.wfrn, dependents: r.named, total: r.dependents })) } } }
    const busy = judged.rows.filter((r) => r.problems.some((p) => p.code === 'state'))
    if (busy.length) return { status: 409, body: { error: 'resource.busy', detail: busy.map((r) => r.problems.find((p) => p.code === 'state')?.message).join('; '), problems: busy.map((r) => ({ level: 'error', code: 'state', field: `/items/${r.index}/wfrn`, message: r.problems.find((p) => p.code === 'state')?.message })) } }
    if ((judged.verb === 'delete' || judged.entry?.destructive) && body.confirm !== true) return problems400([{ level: 'error', code: 'confirm.required', field: '/confirm', message: `${judged.entry?.title ?? judged.verb} is destructive: send confirm: true to run it on every row` }])
    if (typeof body.reason === 'string' && body.reason.length > 1000) return problems400([{ level: 'error', code: 'maxLength', field: '/reason', message: 'The reason is longer than 1000 characters' }])
    return run({ at: ctx.now, kind: 'batch.start', userId: s.userId, accountId: s.accountId, region: s.region, type: rt.name, verb: judged.verb, body, ids: {} })
  }
  return null
}
