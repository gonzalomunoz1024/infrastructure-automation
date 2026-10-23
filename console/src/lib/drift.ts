import { formatValueText, titleCase, xConsoleOf } from '@iaf/console-kit'
import { get, page, qs, request, type ListParams } from './api'
import { idempotencyKey } from './bff'
import type { ConsoleType } from './descriptors'
import type { Decision } from './permissions'
import type { Finding } from './types'

/*
 * The drift routes of the BFF (hub contracts/openapi/console-bff.yaml, tag Drift, over the drift
 * service of hub PR #17): the account's findings with their summary and stats, a finding's
 * revert, accept and ignore, discovery runs, and the unmanaged objects they find with import and
 * Assign to team. Every decision is one call with its own Idempotency-Key; a revert answers 202
 * with the update job it started, and the finding stays open until that job succeeded. The words
 * the pages use for findings, owners and costs live here too, so the pages and the tests agree.
 */

export type { Finding }

export type Severity = Finding['severity']
export type Resolution = NonNullable<Finding['resolution']>

/** `GET /console/v1/findings/summary`: the account's open findings counted. */
export interface FindingsSummary {
  open: number
  byType: Record<string, number>
  bySeverity: Partial<Record<Severity, number>>
  lastScanAt?: string
}

/** `GET /console/v1/findings/stats`: findings opened and closed in a window (the last 30 days by default). */
export interface FindingStats {
  accountId?: string
  since: string
  until: string
  opened: number
  closed: { total: number; reverted: number; accepted: number; ignored: number; vanished: number }
  open: number
  /** the findings opened in the window, by severity */
  bySeverity: Partial<Record<Severity, number>>
  meanMinutesToClose: number | null
}

/** A discovery run: one type's provider read page by page for what no account manages. */
export interface DiscoveryRun {
  runId: string
  type: string
  region: string
  status: 'running' | 'succeeded' | 'failed'
  startedAt: string
  endedAt?: string
  /** the provider's objects read so far */
  seen?: number
  /** of those, the ones no account manages */
  unmanaged?: number
  error?: string
}

export interface Money {
  /** per month */
  amount: number
  currency: string
  /** the price list the figure comes from */
  basis?: string
}

export type Confidence = 'high' | 'medium' | 'low'

/** The account an unmanaged object most likely belongs to, and why, in plain words. */
export interface OwnerGuess {
  accountId: string
  accountName?: string
  team?: string
  confidence: Confidence
  reasons: string[]
}

/** An unmanaged object handed to another team's account. */
export interface Assignment {
  accountId: string
  accountName?: string
  fromAccountId: string
  by: string
  note?: string
  at: string
}

/** Something a provider has that no account manages. */
export interface UnmanagedObject {
  providerId: string
  type: string
  region: string
  name?: string
  observed: Record<string, unknown>
  seenAt: string
  firstSeenAt?: string
  ownerGuess?: OwnerGuess
  monthlyCost?: Money
  assignment?: Assignment
  can: ('import' | 'assign')[]
}

export interface FindingListParams extends ListParams {
  status?: 'open' | 'closed' | 'all'
  severity?: Severity
  type?: string
  wfrn?: string
  openedAfter?: string
}

export interface UnmanagedListParams extends ListParams {
  type?: string
  owner?: 'any' | 'mine' | 'unknown'
}

const BASE = '/console/v1'
const enc = encodeURIComponent
const keyed = (key?: string) => ({ headers: { 'Idempotency-Key': key ?? idempotencyKey() } })

export const driftApi = {
  findings: {
    list: (params?: FindingListParams) => page<Finding>(`${BASE}/findings`, params),
    summary: () => get<FindingsSummary>(`${BASE}/findings/summary`),
    stats: (params?: { since?: string; until?: string }) => get<FindingStats>(`${BASE}/findings/stats${qs(params)}`),
    get: (findingId: string) => get<Finding>(`${BASE}/findings/${enc(findingId)}`),
    /** 202 with the update job; the finding stays open with `revertStatus: running` until it succeeded */
    revert: (findingId: string, body?: { reason?: string }, key?: string) => request<{ findingId: string; jobId: string }>('POST', `${BASE}/findings/${enc(findingId)}:revert`, body ?? {}, keyed(key)),
    /** the provider's values become the desired ones; answers the finding, closed as accepted */
    accept: (findingId: string, body?: { reason?: string }, key?: string) => request<Finding>('POST', `${BASE}/findings/${enc(findingId)}:accept`, body ?? {}, keyed(key)),
    /** silences the fields until the desired state changes, or until `until`; answers the finding, closed as ignored */
    ignore: (findingId: string, body?: { reason?: string; until?: string }, key?: string) => request<Finding>('POST', `${BASE}/findings/${enc(findingId)}:ignore`, body ?? {}, keyed(key)),
  },
  discover: {
    /** one run per type and region at a time: a second answers 409 */
    start: (type: string, key?: string) => request<DiscoveryRun>('POST', `${BASE}/discover`, { type }, keyed(key)),
    get: (runId: string) => get<DiscoveryRun>(`${BASE}/discover/${enc(runId)}`),
  },
  unmanaged: {
    list: (params?: UnmanagedListParams) => page<UnmanagedObject>(`${BASE}/unmanaged`, params),
    /** mints a wfrn; the object leaves this account's list and the one it was assigned to or by */
    import: (body: { type: string; providerId: string; name?: string }, key?: string) => request<{ wfrn: string }>('POST', `${BASE}/unmanaged:import`, body, keyed(key)),
    /** the object shows in both accounts' lists, with its assignment, until either imports it */
    assign: (body: { type: string; providerId: string; accountId: string; note?: string }, key?: string) => request<UnmanagedObject>('POST', `${BASE}/unmanaged:assign`, body, keyed(key)),
  },
}

/* ----------------------------------------------------------------------------
   Findings in words
   ------------------------------------------------------------------------- */

/** The attribute a field's path names: a JSON pointer (`/memoryGb`) or a dotted path (`attributes.memoryGb`). */
export function fieldNameOf(path: string): string {
  return path.replace(/^\//, '').replace(/^attributes[./]/, '').replace(/\//g, '.')
}

/** A drifted field's label, from the type's schema when it names the field. */
export function fieldLabel(path: string, type?: Pick<ConsoleType, 'schema'>): string {
  const name = fieldNameOf(path)
  const prop = type?.schema.properties?.[name.split('.')[0]]
  return prop?.title ?? titleCase(name)
}

/** A value as the type shows it, units and all. */
export function valueText(path: string, value: unknown, type?: Pick<ConsoleType, 'schema'>): string {
  const prop = type?.schema.properties?.[fieldNameOf(path).split('.')[0]]
  return formatValueText(value, xConsoleOf(prop))
}

/** What drifted, in words: "Memory GB is 16 GiB, asked for 8 GiB" ("was", for a closed finding); the ignored fields left out unless every field is. */
export function driftInWords(finding: Pick<Finding, 'fields'> & Partial<Pick<Finding, 'status'>>, type?: Pick<ConsoleType, 'schema'>): string {
  const live = finding.fields.filter((f) => !f.ignored)
  const shown = live.length ? live : finding.fields
  const verb = finding.status === 'closed' ? 'was' : 'is'
  return shown.map((f) => `${fieldLabel(f.path, type)} ${verb} ${valueText(f.path, f.observed, type)}, asked for ${valueText(f.path, f.expected, type)}`).join('; ')
}

export const SEVERITY_TONE = { info: 'neutral', warning: 'warning', critical: 'critical' } as const
export const SEVERITY_WORDS: Record<Severity, string> = { critical: 'Critical', warning: 'Warning', info: 'Info' }
export const SEVERITIES: Severity[] = ['critical', 'warning', 'info']

export const RESOLUTION_WORDS: Record<Resolution, { label: string; tone: 'healthy' | 'neutral' | 'accent' }> = {
  reverted: { label: 'Reverted', tone: 'healthy' },
  accepted: { label: 'Accepted', tone: 'accent' },
  ignored: { label: 'Ignored', tone: 'neutral' },
  vanished: { label: 'Settled by itself', tone: 'neutral' },
}

/** One line on how a closed finding ended: who decided and, for an ignore, until when. */
export function resolutionLine(f: Pick<Finding, 'resolution' | 'decidedBy' | 'ignoredUntil'>): string {
  switch (f.resolution) {
    case 'reverted':
      return `Put back to what was asked for${f.decidedBy ? ` by ${f.decidedBy}` : ''}`
    case 'accepted':
      return `The provider’s values were made the desired ones${f.decidedBy ? ` by ${f.decidedBy}` : ''}`
    case 'ignored':
      return `Ignored${f.decidedBy ? ` by ${f.decidedBy}` : ''} ${f.ignoredUntil ? `until ${new Date(f.ignoredUntil).toLocaleDateString(undefined, { day: 'numeric', month: 'short', year: 'numeric' })}` : 'until what was asked for changes'}`
    case 'vanished':
      return 'The provider stopped reporting the difference'
    default:
      return 'Closed'
  }
}

/** How a finding's revert stands: none asked for, running, or failed (the finding stays open after a failed one). */
export function revertStateOf(f: Pick<Finding, 'status' | 'revertStatus' | 'revertJobId'>): 'none' | 'running' | 'failed' | 'done' {
  if (f.revertStatus === 'running') return 'running'
  if (f.revertStatus === 'failed' && f.status === 'open') return 'failed'
  if (f.revertStatus === 'succeeded') return 'done'
  return 'none'
}

/** How long an ignore lasts: until the desired state changes, or a number of days. */
export const IGNORE_CHOICES = [
  { value: 'desired', label: 'Until what was asked for changes', days: null },
  { value: '1', label: 'For 1 day', days: 1 },
  { value: '7', label: 'For 7 days', days: 7 },
  { value: '30', label: 'For 30 days', days: 30 },
] as const

export type IgnoreChoice = (typeof IGNORE_CHOICES)[number]['value']

/** The `until` an ignore sends for a choice, or undefined for "until what was asked for changes". */
export function ignoreUntilOf(choice: IgnoreChoice, now = Date.now()): string | undefined {
  const days = IGNORE_CHOICES.find((c) => c.value === choice)?.days
  return days ? new Date(now + days * 86_400_000).toISOString() : undefined
}

/**
 * A decision on a finding for this person: the type's `update` as the BFF decided it, then the
 * finding's own `can`. Refused by policy, it carries the Denial; allowed but not offered here, it
 * says why in `why`.
 */
export function findingDecision(update: Decision, finding: Pick<Finding, 'status' | 'can' | 'revertStatus'>, verb: 'revert' | 'accept' | 'ignore'): Decision & { why?: string } {
  if (!update.allowed) return update
  if (finding.status === 'closed') return { allowed: false, why: 'This finding is closed.' }
  if (finding.can && !finding.can.includes(verb)) {
    if (finding.revertStatus === 'running' && verb !== 'ignore') return { allowed: false, why: 'A revert is running; the finding closes when its request succeeds.' }
    if (verb === 'revert') return { allowed: false, why: 'The type’s update workflow does not set these fields, so the console cannot put them back; accept or ignore them instead.' }
    return { allowed: false, why: `The drift service does not offer ${verb === 'accept' ? 'Accept' : 'Ignore'} on this finding.` }
  }
  return update
}

/** The query key prefixes a decision on a finding of the type changes: its findings, the account's, the resource and its lists, the requests. */
export function decisionKeys(type: Pick<ConsoleType, 'name' | 'plural'>): string[] {
  return [`console-drift/${type.name}`, 'console-findings', `console-resource/${type.name}`, `resource/${type.plural}`, 'console-resources', 'console-resource-counts', `console-activity/${type.name}`, 'jobs']
}

/** Minutes as a short duration: "45 min", "3 h 20 min", "2 days 4 h". */
export function minutesText(minutes: number | null | undefined): string {
  if (minutes === null || minutes === undefined || !Number.isFinite(minutes)) return 'Nothing closed yet'
  const m = Math.round(minutes)
  if (m < 60) return `${m} min`
  const h = Math.floor(m / 60)
  if (h < 24) return m % 60 ? `${h} h ${m % 60} min` : `${h} h`
  const d = Math.floor(h / 24)
  return h % 24 ? `${d} ${d === 1 ? 'day' : 'days'} ${h % 24} h` : `${d} ${d === 1 ? 'day' : 'days'}`
}

/* ----------------------------------------------------------------------------
   Unmanaged objects in words
   ------------------------------------------------------------------------- */

export const CONFIDENCE_WORDS: Record<Confidence, { label: string; tone: 'healthy' | 'warning' | 'neutral' }> = {
  high: { label: 'High confidence', tone: 'healthy' },
  medium: { label: 'Medium confidence', tone: 'warning' },
  low: { label: 'Low confidence', tone: 'neutral' },
}

/** "154.00 USD a month", in the person's locale. */
export function moneyText(m: Money | undefined): string {
  if (!m) return 'No estimate'
  let amount: string
  try {
    amount = new Intl.NumberFormat(undefined, { style: 'currency', currency: m.currency }).format(m.amount)
  } catch {
    amount = `${m.amount.toFixed(2)} ${m.currency}`
  }
  return `${amount} a month`
}

const BRIEF_KEYS = ['cpu', 'memoryGb', 'engine', 'version', 'tier', 'sizeGb', 'environment', 'powerState', 'state']

/** What the provider reports of an object, in brief: up to four of its telling attributes. */
export function observedBrief(observed: Record<string, unknown>, type?: Pick<ConsoleType, 'schema'>): string {
  const parts: string[] = []
  for (const key of BRIEF_KEYS) {
    const v = observed[key]
    if (v === undefined || v === null || v === '' || typeof v === 'object') continue
    parts.push(key === 'cpu' ? `${String(v)} CPU` : `${fieldLabel(key, type)} ${valueText(key, v, type)}`)
    if (parts.length === 4) break
  }
  return parts.join(' · ') || 'Nothing reported'
}
