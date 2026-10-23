import type { Problem } from '@iaf/console-kit'
import { isApiError, type ApiError } from './api'

/*
 * Plain language (WP 3.7): what went wrong, in words, and what to do next. Every failed call
 * the console shows passes through here, so a 409 reads "someone changed this first" rather
 * than "Conflict", and a 429 says how long to wait. A refusal (403 with a Denial) is not
 * explained here: the refusal dialog names the layer and the statement that decided.
 */

export interface Explanation {
  /** one line, what happened */
  title: string
  /** a sentence or two, why */
  message: string
  /** what to do next, when there is a next step */
  next?: string
  /** the server's own code, when it had one */
  code?: string
  /** the id to quote when asking for help */
  correlationId?: string
  /** a 429: seconds to wait */
  retryAfter?: number
  /** the fields the server named */
  problems?: Problem[]
  /** the person can try again as is */
  retryable: boolean
}

const CODES: Record<string, { title: string; message?: string; next?: string; retryable?: boolean }> = {
  'validation.failed': { title: 'Some fields need attention', message: 'The platform checked the request against the workflow’s schema and found problems.', next: 'Fix the highlighted fields and try again.' },
  'confirm.required': { title: 'This change needs your confirmation', message: 'The verb is destructive: the platform runs it only once you confirm.', next: 'Type the resource’s name to confirm.' },
  'policy.denied': { title: 'Policy refuses this change', message: 'A rule of the account or the platform says no to this run as it stands.', next: 'Read the rules below; change the request, or ask the account’s admins.' },
  'idempotency.mismatch': { title: 'This request was already sent with other values', message: 'The same submission key arrived with a different body, so the platform kept the first.', next: 'Start the form again to send a new request.' },
  'dependents.exist': { title: 'Other resources are built from this one', message: 'Deleting it would leave them without what they were built from.', next: 'Delete what depends on it first; the preview and the Relationships tab name them.' },
  'resource.busy': { title: 'Another change is in flight', message: 'A request is already changing this resource; the platform takes one at a time.', next: 'Wait for it to finish, then try again.', retryable: true },
  'resource.deleted': { title: 'This resource was deleted', message: 'Its record is kept for the activity trail, but nothing can run on it any more.' },
  'resource.not-found': { title: 'Not found', message: 'Nothing lives at this address: it may have been deleted or renamed.', next: 'Go back to the list and look for it there.' },
  'verb.unknown': { title: 'No such action', message: 'The provider publishes no workflow for this verb on this type.' },
  'create.unavailable': { title: 'Nothing creates this type yet', message: 'The provider has not published a create workflow for it.' },
  'job.not-open': { title: 'This request has already ended', message: 'Only a queued, running or waiting request can be cancelled.' },
  'csrf.missing': { title: 'Your session changed', message: 'The page was signed in as someone else, or the session was renewed.', next: 'Reload the page and try again.', retryable: true },
  'step-up.required': { title: 'Sign in again for this', message: 'A sensitive action needs a fresh sign-in.', next: 'Sign in again and come back.' },
  'budget.exceeded': { title: 'A budget blocks this create', message: 'The account’s budget for this month is used up, and the budget is set to stop new resources.', next: 'Open Budgets to see which one; ask a budget owner to raise it, or delete what you no longer need.' },
  'quota.exceeded': { title: 'The account’s quota is used up', message: 'The account holds as many resources of this type as its quota allows.', next: 'Delete what you no longer need, or request an increase from the platform team on IAM, Access keys, Quotas.' },
  'batch.not-implemented': { title: 'Bulk runs are not here yet', message: 'The console runs a verb on one resource at a time for now.' },
}

const STATUS: Record<number, { title: string; message: string; next?: string; retryable?: boolean }> = {
  0: { title: 'The platform did not answer', message: 'The request never reached a server, or the answer was lost on the way.', next: 'Check your connection and try again in a moment.', retryable: true },
  400: { title: 'The request was not accepted', message: 'Something in it did not match what the platform expects.', next: 'Check the fields and try again.' },
  401: { title: 'You are signed out', message: 'The session has ended.', next: 'Sign in again; the console brings you back here.' },
  403: { title: 'Not allowed', message: 'The platform refused this for your role or account.', next: 'Ask the account’s admins for access.' },
  404: { title: 'Not found', message: 'Nothing lives at this address any more.', next: 'Go back to the list and look for it there.' },
  405: { title: 'That cannot be done this way', message: 'The platform does not accept this call on this address.' },
  409: { title: 'Something changed first', message: 'The resource is no longer as it was when the page was loaded.', next: 'Reload to see the latest state, then try again.', retryable: true },
  410: { title: 'This resource was deleted', message: 'Its record is kept for the activity trail, but nothing can run on it any more.' },
  412: { title: 'Someone changed this first', message: 'The version you edited is no longer the current one.', next: 'Reload, check their change, and apply yours again.', retryable: true },
  413: { title: 'The request is too large', message: 'The platform limits how much one request may carry.', next: 'Send less at a time.' },
  422: { title: 'The request does not make sense to the platform', message: 'It was well formed but could not be carried out as written.', next: 'Check the fields and try again.' },
  429: { title: 'Too many requests', message: 'The account is sending more than its rate allows.', next: 'Wait a moment and try again.', retryable: true },
  500: { title: 'The platform had a problem', message: 'Something failed on the platform’s side, not in your request.', next: 'Try again; if it keeps failing, quote the correlation id to the platform team.', retryable: true },
  502: { title: 'The platform is not reachable', message: 'The console could not reach the platform behind it.', next: 'Try again in a moment.', retryable: true },
  503: { title: 'The platform is busy or restarting', message: 'It asked the console to come back later.', next: 'Try again in a moment.', retryable: true },
  504: { title: 'The platform took too long', message: 'The request may still be running; check the requests page before sending it again.', retryable: true },
}

function detailOf(e: ApiError): string | undefined {
  if (typeof e.detail === 'string' && e.detail.trim()) return e.detail.trim()
  return undefined
}

/** A failed call as words: what happened, why, and what to do next. */
export function explain(error: unknown, what?: string): Explanation {
  if (isApiError(error)) {
    const byCode = CODES[error.stepUp ? 'step-up.required' : error.error]
    const byStatus = STATUS[error.status] ?? (error.status >= 500 ? STATUS[500] : error.status >= 400 ? STATUS[400] : undefined)
    const detail = detailOf(error)
    const base = byCode ?? byStatus ?? { title: error.error || 'Something went wrong', message: '', retryable: false }
    const title = what && !byCode ? `Could not ${lowerFirst(what)}` : base.title
    const message = [byCode ? byCode.message : undefined, detail, !byCode && !detail ? byStatus?.message : undefined, !byCode && !detail && !byStatus && error.error ? error.error : undefined].filter(Boolean).join(' ')
    const next = error.status === 429 && error.retryAfter ? `Wait ${error.retryAfter} ${error.retryAfter === 1 ? 'second' : 'seconds'} and try again.` : (base.next ?? byStatus?.next)
    return {
      title,
      message: message || base.title,
      next,
      code: error.error && error.error !== title ? error.error : undefined,
      correlationId: error.correlationId,
      retryAfter: error.retryAfter,
      problems: error.problems?.length ? error.problems : undefined,
      retryable: !!(base.retryable ?? byStatus?.retryable),
    }
  }
  if (error instanceof DOMException && error.name === 'AbortError') return { title: 'Cancelled', message: 'The request was stopped before it finished.', retryable: true }
  const text = error instanceof Error ? error.message : String(error)
  return { title: what ? `Could not ${lowerFirst(what)}` : 'Something went wrong', message: text, next: 'Try again; if it keeps failing, reload the page.', retryable: true }
}

/** The explanation as one line, for a toast or a status row. */
export function explainLine(error: unknown, what?: string): string {
  const x = explain(error, what)
  return [x.message, x.next].filter(Boolean).join(' ')
}

const PROBLEM_WORDS: Record<string, (p: Problem, label: string) => string> = {
  required: (_p, label) => `${label} is required.`,
  pattern: (_p, label) => `${label} does not match the expected form.`,
  enum: (_p, label) => `${label} must be one of the listed values.`,
  type: (_p, label) => `${label} has the wrong kind of value.`,
  minimum: (_p, label) => `${label} is below the smallest allowed value.`,
  maximum: (_p, label) => `${label} is above the largest allowed value.`,
  minLength: (_p, label) => `${label} is too short.`,
  maxLength: (_p, label) => `${label} is too long.`,
  immutable: (_p, label) => `${label} was set when the resource was created and cannot be changed.`,
  'confirm.required': () => 'Confirm the change by typing the resource’s name.',
}

/** A problem's message in plain words, with the field named, when the server's own line is terse. */
export function explainProblem(p: Problem, fieldTitle?: (field: string) => string | undefined): string {
  const field = p.field?.replace(/^\/?(input\/)?/, '').replace(/\//g, '.')
  const label = (field && fieldTitle?.(field)) || (field ? titleOfField(field) : 'This value')
  const words = PROBLEM_WORDS[p.code]
  if (p.message && /\s/.test(p.message) && p.message.length > 12) return p.message.endsWith('.') ? p.message : `${p.message}.`
  return words ? words(p, label) : p.message || `${label} needs attention.`
}

/** A field's name in sentence case: `keepSnapshotDays` reads "Keep snapshot days", an acronym stays as it is. */
function titleOfField(field: string): string {
  const last = field.split('.').pop() ?? field
  const words = last
    .replace(/([a-z0-9])([A-Z])/g, '$1 $2')
    .replace(/[-_]+/g, ' ')
    .split(' ')
    .filter(Boolean)
    .map((w, i) => (i > 0 && !(w.length > 1 && w === w.toUpperCase()) ? w.toLowerCase() : w))
    .join(' ')
  return words.charAt(0).toUpperCase() + words.slice(1)
}

export function lowerFirst(s: string): string {
  return s ? s.charAt(0).toLowerCase() + s.slice(1) : s
}
