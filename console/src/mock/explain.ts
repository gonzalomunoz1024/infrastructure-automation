import { serviceOf, singularOf, titleOf } from '../lib/descriptors'
import type { FailureFix, JobExplanation } from '../lib/explain'
import { encodePrefill } from '../lib/prefill'
import type { CatalogEntry, JobSummary, Resource, ResourceType } from '../lib/types'
import { typeOf, type RouteAnswer, type RouteContext } from './commands'
import { DAY_MS } from './cost'
import type { MockData } from './data'
import { authorize } from './directory'
import { retryTargetOf, stepsOf } from './jobs'
import { accountOfResource, stateOf } from './resources'

/*
 * Phase 9 in the mock BFF: the failure explainer (hub decision 27,
 * `GET /console/v1/jobs/{jobId}/explanation`). Rules over the platform's error classes (the ones
 * the platform maps a provider's answer to, and the mock's own `provider`) say what went wrong and
 * what to try; what they cannot read, the copilot answers, here from the step's own message. A fix
 * proposes only inputs the workflow takes, never the name a create was given (the retry keeps it),
 * and only resources that exist now in the job's account.
 */

const WINDOW_MS = 30 * DAY_MS
const EXPLAINED = new Set(['failed', 'needs-attention'])
const TRANSIENT: Record<string, string> = {
  rate_limited: 'The provider asked the platform to slow down',
  unavailable: 'The provider was unavailable',
  timeout: 'The provider did not answer in time',
  unreachable: 'The platform could not reach the provider',
}

/** A failed job as the rules read it: its workflow, the type it acts on, its recorded input and the step that stopped. */
interface Failure {
  job: JobSummary
  entry?: CatalogEntry
  rt?: ResourceType
  verb: string
  /** the request's input, when the console recorded it */
  input?: Record<string, unknown>
  step: { id: string; name: string; attempt: number; message?: string }
  cls?: string
}

type Reading = Pick<JobExplanation, 'cause' | 'fix' | 'links' | 'source'>

/** The platform's error class of a job: its own, and the mock's `provider` for a failed or paused step that names none (as ./jobs draws it). */
const classOf = (j: JobSummary) => j.errorClass ?? (j.status === 'failed' || j.status === 'needs-attention' ? 'provider' : undefined)

function failureOf(data: MockData, job: JobSummary): Failure {
  const extra = data.console?.jobs[job.id]
  const entry = data.catalog.find((e) => e.workflow === job.workflow)
  const rt = (extra ? typeOf(data, extra.type) : undefined) ?? (entry ? typeOf(data, entry.noun) : undefined) ?? data.resourceTypes.find((t) => t.producedBy === job.workflow)
  // the step the request page draws as failed (./jobs stepsOf): the one a paused job waits on, else the one that failed
  const steps = stepsOf(job, extra)
  const failed = steps.find((st) => st.status === 'failed' && st.id === (job.failedStep ?? extra?.attention?.step)) ?? steps.find((st) => st.status === 'failed')
  const id = failed?.id ?? job.failedStep ?? 'apply'
  const cls = job.errorClass ?? failed?.error?.class ?? classOf(job)
  return {
    job,
    entry,
    rt,
    verb: extra?.verb ?? entry?.verb ?? 'run',
    input: extra?.input,
    step: { id, name: failed?.name ?? 'Apply the change', attempt: failed?.attempt ?? 1, message: failed?.error?.message ?? (cls ? `${id} failed: ${cls}` : undefined) },
    cls,
  }
}

const nounOf = (rt: ResourceType) => singularOf(titleOf(rt)).toLowerCase()
const aOf = (noun: string) => `${/^[aeiou]/.test(noun) ? 'an' : 'a'} ${noun}`
const createPath = (rt: ResourceType, input?: Record<string, unknown>) => `#/console/${serviceOf(rt)}/${rt.name}/create${input && Object.keys(input).length ? `?prefill=${encodePrefill(input)}` : ''}`

/** The resource an input value names: by id, wfrn, or the id a wfrn ends in. */
function named(data: MockData, type: string, v: string): Resource | undefined {
  return data.resources.find((r) => r.type === type && (r.id === v || r.wfrn === v || v.endsWith(`/${r.id}`)))
}

/** The newest resource of the type that is live in the account, preferring one not deprecated. */
function liveOf(data: MockData, accountId: string, rt: ResourceType): Resource | undefined {
  const accountIds = data.directory.accounts.map((a) => a.accountId)
  const live = data.resources
    .filter((r) => r.type === rt.name && accountOfResource(r, accountIds) === accountId && stateOf(r, rt) === 'active')
    .sort((a, b) => (b.createdAt ?? '').localeCompare(a.createdAt ?? ''))
  return live.find((r) => r.status !== 'deprecated') ?? live[0]
}

/** not_found: the input names a resource that is not there; the fix swaps in a live one of the same type. */
function notFound(data: MockData, accountId: string, f: Failure): Reading {
  const refs = Object.entries(f.entry?.input?.properties ?? {}).flatMap(([field, p]) => {
    const rt = p?.['x-resource'] ? typeOf(data, p['x-resource'].type) : undefined
    return rt ? [{ field, rt }] : []
  })
  const gone = (rt: ResourceType, v: unknown) => {
    const r = typeof v === 'string' ? named(data, rt.name, v) : undefined
    return typeof v === 'string' && (!r || stateOf(r, rt) !== 'active')
  }
  // without the recorded input, a workflow that names one resource can only have missed that one
  const missing = f.input ? refs.find(({ field, rt }) => gone(rt, f.input?.[field])) : refs.length === 1 ? refs[0] : undefined
  const from = `step ${f.step.id}`
  if (!missing) {
    return {
      cause: { class: f.cls, title: 'Something the request names does not exist', detail: `The provider answered "not found" at ${from}: something the input names is missing, or was deleted after the request started.` },
      fix: { summary: `Check the names in the request's input against the provider, then run it again from ${from}.`, fromStep: f.step.id, confidence: 'low' },
      source: 'rules',
    }
  }
  const noun = nounOf(missing.rt)
  const was = typeof f.input?.[missing.field] === 'string' ? String(f.input[missing.field]) : undefined
  const instead = liveOf(data, accountId, missing.rt)
  const detail = was
    ? `The ${noun} "${was}" the request asked for is not live in the registry, so the provider could not find it at ${from}.`
    : `The provider could not find the ${noun} the request asked for at ${from}. The request's input was not recorded, so which ${noun} it named is unknown.`
  const fix: FailureFix = instead
    ? { summary: `Use the ${noun} ${instead.name ?? instead.id}, the newest live one in this account, and run it again from ${from}.`, input: { [missing.field]: instead.id }, fromStep: f.step.id, confidence: was ? 'high' : 'medium' }
    : { summary: `No live ${noun} is in this account to use instead. Create one, then run it again from ${from} with it.`, confidence: 'medium' }
  return {
    cause: { class: f.cls, title: `The ${noun} it names does not exist`, detail },
    fix,
    links: instead ? undefined : [{ title: `Create ${aOf(noun)}`, url: createPath(missing.rt) }],
    source: 'rules',
  }
}

/** The input a new create may start from: the workflow's own fields but the name, never a sensitive one (a link is no place for a secret). */
function reusableInput(f: Failure): Record<string, unknown> {
  const props = f.entry?.input?.properties ?? {}
  const typeProps = f.rt?.schema?.properties ?? {}
  const sensitive = (k: string) => props[k]?.['x-console']?.sensitive === true || typeProps[k]?.['x-console']?.sensitive === true
  return Object.fromEntries(Object.entries(f.input ?? {}).filter(([k]) => k !== 'name' && k in props && !sensitive(k)))
}

function conflict(f: Failure): Reading {
  const cause = { class: f.cls, title: 'The provider says it already exists, or is busy', detail: 'Another change holds it, or something with the same name is already there.' }
  if (f.verb === 'create' && f.rt) {
    // a retry keeps the name a create was given (the BFF's retry.name-fixed), so the way on is a new create
    return {
      cause,
      fix: { summary: 'A retry keeps the name this create was given, so it would meet the same clash. Create it again with another name.', confidence: 'medium' },
      links: [{ title: `Create ${aOf(nounOf(f.rt))} with another name`, url: createPath(f.rt, reusableInput(f)) }],
      source: 'rules',
    }
  }
  return { cause, fix: { summary: `Wait for the other change to finish, then run it again from step ${f.step.id}.`, fromStep: f.step.id, confidence: 'medium' }, source: 'rules' }
}

/** What the rules say about the failure, or what the copilot says when no rule reads its class. */
function readingOf(data: MockData, accountId: string, f: Failure): Reading {
  const from = `step ${f.step.id}`
  const owner = f.entry?.owner ?? "the provider's team"
  switch (f.cls) {
    case 'not_found':
      return notFound(data, accountId, f)
    case 'conflict':
      return conflict(f)
    case 'rate_limited':
    case 'unavailable':
    case 'timeout':
    case 'unreachable':
      return {
        cause: { class: f.cls, title: TRANSIENT[f.cls], detail: `This kind of failure usually passes on its own. Running it again from ${from} repeats nothing before it.` },
        fix: { summary: `Run it again from ${from}.`, fromStep: f.step.id, confidence: 'high' },
        source: 'rules',
      }
    case 'server_error':
    case 'provider':
      return {
        cause: { class: f.cls, title: 'The provider failed while making the change', detail: `The provider stopped ${from} with an error of its own, not one in the request.` },
        fix: { summary: `Run it again from ${from}. If it fails the same way, ${owner} can see why on the provider's health page.`, fromStep: f.step.id, confidence: 'medium' },
        source: 'rules',
      }
    case 'unauthorized':
      return {
        cause: { class: f.cls, title: "The provider refused the platform's credentials", detail: 'The credential the platform uses for this provider has expired or lacks a permission, so running it again fails the same way until it is renewed.' },
        fix: { summary: `Ask ${owner} to renew the provider's credential, then run it again from ${from}.`, fromStep: f.step.id, confidence: 'medium' },
        source: 'rules',
      }
  }
  if (f.job.status === 'needs-attention') {
    return {
      cause: { class: f.cls, title: 'A step stopped and waits for a decision', detail: `${f.step.name} did not finish, and the workflow waits for someone to retry it, skip it or abort the request.` },
      fix: { summary: 'Retry the step. If it stops again, abort the request so its compensations undo what already ran.', confidence: 'low' },
      source: 'assistant',
    }
  }
  return {
    cause: { class: f.cls, title: 'The provider refused part of the request', detail: `The step's error reads "${f.step.message ?? f.cls}", which points at the request's input rather than the platform.` },
    fix: { summary: `Check the input against that error, then run it again from ${from}. If the input was right, ask ${owner}.`, fromStep: f.step.id, confidence: 'low' },
    source: 'assistant',
  }
}

const visibleIn = (accountId: string) => (j: JobSummary) => !j.accountId || j.accountId === accountId
/** a job that stopped on a failed step: ended failed, paused on it, or rolled back after it */
const STOPPED = new Set(['failed', 'needs-attention', 'rolled-back'])

/** The same failure in the account in the last 30 days, and the last time a rerun of one succeeded. */
function similarOf(data: MockData, accountId: string, f: Failure, now: number): JobExplanation['similar'] {
  const since = now - WINDOW_MS
  const jobs = data.jobs.filter(visibleIn(accountId))
  const alike = f.cls ? jobs.filter((j) => j.workflow === f.job.workflow && STOPPED.has(j.status) && classOf(j) === f.cls && Date.parse(j.startedAt) >= since) : []
  // a fix shows as a later run of the same workflow that succeeded: a rerun naming the failed job, or one on the same resource
  const retryOf = (k: JobSummary) => data.console?.jobs[k.id]?.retryOf
  const fixes = alike.flatMap((j) => jobs.filter((k) => k.workflow === j.workflow && k.status === 'succeeded' && Date.parse(k.startedAt) > Date.parse(j.startedAt) && (retryOf(k) === j.id || (!!j.wfrn && k.wfrn === j.wfrn))))
  const last = fixes.sort((a, b) => (b.endedAt ?? b.startedAt).localeCompare(a.endedAt ?? a.startedAt))[0]
  return { count: alike.filter((j) => j.id !== f.job.id).length, ...(last ? { lastFixed: { jobId: last.id, by: last.requester, at: last.endedAt ?? last.startedAt } } : {}) }
}

/** Whether this person may run it again: what the request page's retry asks (./jobs retryTargetOf), judged like the first request. */
function retryOf(data: MockData, s: RouteContext['session'], f: Failure): JobExplanation['retry'] {
  const target = retryTargetOf(data, s, f.job, data.console?.jobs[f.job.id])
  const d = authorize(data.directory, { userId: s.userId, accountId: s.accountId }, target.action, target.resource)
  return d.allowed ? { allowed: true } : { allowed: false, denied: d.denied }
}

/** The failure explainer's routes, or null for any other path. */
export function explanationRoutes(data: MockData, ctx: RouteContext, path: string, _params: URLSearchParams, method: string): RouteAnswer | null {
  const m = /^\/console\/v1\/jobs\/([^/:]+)\/explanation$/.exec(path)
  if (!m) return null
  if (method !== 'GET') return { status: 405, body: { error: `${method} is not allowed on ${path}` } }
  const s = ctx.session
  const jobId = decodeURIComponent(m[1])
  const job = data.jobs.find((j) => j.id === jobId)
  if (!job || !visibleIn(s.accountId)(job)) return { status: 404, body: { error: `No request ${jobId} in this account` } }
  if (!EXPLAINED.has(job.status)) return { status: 409, body: { error: 'job.not-failed', detail: { status: job.status }, message: `Request ${jobId} is ${job.status}; only a failed request, or one waiting on a failed step, has an explanation` } }
  const f = failureOf(data, job)
  const reading = readingOf(data, s.accountId, f)
  const explanation: JobExplanation = {
    jobId,
    status: job.status === 'failed' ? 'failed' : 'needs-attention',
    cause: reading.cause,
    failedStep: f.step,
    ...(reading.fix ? { fix: reading.fix } : {}),
    retry: retryOf(data, s, f),
    similar: similarOf(data, s.accountId, f, ctx.now),
    source: reading.source,
    ...(reading.links ? { links: reading.links } : {}),
  }
  return { status: 200, body: explanation }
}
