import type { ActivityEntry, BlastRadius, ConsolePreview } from './types'

/*
 * The change risk of a request (WP 9.10): low, medium or high, with the reasons in words. No hub
 * contract carries a risk score yet (the BFF's Preview has the blast radius and policy's rules, not
 * a score), so the console works it out from what the preview and the resource already say: what
 * the change does, whether it touches production, what depends on it, the data it holds, how its
 * recent requests went, whether it drifts, and what it adds to the bill. The formula lives here and
 * nowhere else, so every drawer, the create review and the request page agree, and the BFF can take
 * it over word for word (listed as a contract gap in the README, decision 102).
 *
 * Points, capped at 100:
 * - what it does: delete or another destructive verb 30, an update or another verb 10, a create 5
 * - production: any target in production 30
 * - how many: 10 or more resources in one request 10, 50 or more 20
 * - what depends on it: 1 to 4 dependents 10, 5 to 19 20, 20 or more 30, and 10 more when any is
 *   in another account
 * - its data: restricted 10, confidential 5 (the `data-class` tag)
 * - history: a request on it failed, was cancelled or rolled back in the last 30 days 10, three or
 *   more 15
 * - drift: what the provider reports differs from what was asked for 5
 * - cost: it adds 500 or more a month 10
 *
 * Under 30 is low, under 60 medium, 60 and over high. A high risk needs an approver.
 */

export type RiskLevel = 'low' | 'medium' | 'high'

export type RiskKind = 'create' | 'update' | 'change' | 'delete'

export interface RiskTarget {
  attributes?: Record<string, unknown>
  tags?: Record<string, string>
  drift?: boolean
}

export interface RiskInput {
  kind: RiskKind
  /** the verb is destructive (a delete always is) */
  destructive?: boolean
  /** what the request acts on: one resource, the rows of a bulk run, or the create's input and tags */
  targets: RiskTarget[]
  /** what depends on the targets, from the preview or the blast-radius read */
  radius?: Pick<BlastRadius, 'total' | 'byAccount'>
  /** requests on the target that failed, were cancelled or rolled back in the last 30 days */
  failedRecently?: number
  /** what the change adds to the monthly bill (negative when it saves) */
  monthlyCostDelta?: number
}

export interface RiskReason {
  id: string
  points: number
  text: string
}

export interface Risk {
  score: number
  level: RiskLevel
  reasons: RiskReason[]
  /** a high risk waits for an approver */
  needsApprover: boolean
}

export const RISK_MEDIUM = 30
export const RISK_HIGH = 60
const DAY_MS = 86_400_000

export function levelOf(score: number): RiskLevel {
  return score >= RISK_HIGH ? 'high' : score >= RISK_MEDIUM ? 'medium' : 'low'
}

/** Whether a target runs in production: its `environment` attribute or tag (or `stage`) says prod. */
export function isProduction(t: RiskTarget): boolean {
  const words = [t.attributes?.environment, t.attributes?.stage, t.tags?.environment].filter((v): v is string => typeof v === 'string')
  return words.some((w) => /^prod(uction)?$/i.test(w))
}

const plural = (n: number, one: string, many = `${one}s`) => `${n} ${n === 1 ? one : many}`

/** The score and its reasons, the largest first. */
export function riskOf(input: RiskInput): Risk {
  const reasons: RiskReason[] = []
  const add = (id: string, points: number, text: string) => reasons.push({ id, points, text })
  const n = input.targets.length

  if (input.kind === 'delete' || input.destructive) add('destructive', 30, input.kind === 'delete' ? 'It deletes, and a delete cannot be undone' : 'Its workflow is destructive: what it changes cannot be undone')
  else if (input.kind === 'create') add('create', 5, 'It creates something new; nothing that exists changes')
  else add('change', 10, 'It changes a resource that exists')

  const prod = input.targets.filter(isProduction).length
  if (prod > 0) add('production', 30, n > 1 ? `${plural(prod, 'of them runs', 'of them run')} in production` : input.kind === 'create' ? 'It will run in production' : 'It runs in production')

  if (n >= 50) add('many', 20, `It acts on ${n} resources in one request`)
  else if (n >= 10) add('many', 10, `It acts on ${n} resources in one request`)

  const total = input.radius?.total ?? 0
  if (total >= 20) add('dependents', 30, `${plural(total, 'resource depends', 'resources depend')} on it`)
  else if (total >= 5) add('dependents', 20, `${plural(total, 'resource depends', 'resources depend')} on it`)
  else if (total >= 1) add('dependents', 10, `${plural(total, 'resource depends', 'resources depend')} on it`)
  const elsewhere = (input.radius?.byAccount ?? []).filter((a) => !a.current && a.count > 0).reduce((sum, a) => sum + a.count, 0)
  if (elsewhere > 0) add('accounts', 10, `${plural(elsewhere, 'of them is', 'of them are')} in another team's account`)

  const classes = input.targets.map((t) => t.tags?.['data-class']).filter((v): v is string => typeof v === 'string')
  if (classes.includes('restricted')) add('data', 10, 'It holds restricted data')
  else if (classes.includes('confidential')) add('data', 5, 'It holds confidential data')

  const failed = input.failedRecently ?? 0
  if (failed >= 3) add('history', 15, `${failed} requests on it went wrong in the last 30 days`)
  else if (failed >= 1) add('history', 10, `${plural(failed, 'request', 'requests')} on it went wrong in the last 30 days`)

  if (input.targets.some((t) => t.drift)) add('drift', 5, 'What the provider reports already differs from what was asked for')

  if ((input.monthlyCostDelta ?? 0) >= 500) add('cost', 10, `It adds ${Math.round(input.monthlyCostDelta!).toLocaleString('en-US')} USD a month`)

  reasons.sort((a, b) => b.points - a.points)
  const score = Math.min(100, reasons.reduce((sum, r) => sum + r.points, 0))
  const level = levelOf(score)
  return { score, level, reasons, needsApprover: level === 'high' }
}

const BAD_ENDINGS = new Set(['failed', 'cancelled', 'rolled-back', 'needs-attention'])

/** How many of a resource's requests went wrong in the 30 days before `now`, from its activity trail. */
export function failedRecentlyOf(entries: readonly ActivityEntry[] | undefined, now: number): number {
  const since = now - 30 * DAY_MS
  const bad = new Set<string>()
  for (const e of entries ?? []) {
    const job = e.job
    if (e.kind !== 'job' || !job || !BAD_ENDINGS.has(String(job.status))) continue
    if (Date.parse(job.endedAt ?? job.startedAt ?? e.at) >= since) bad.add(job.jobId)
  }
  return bad.size
}

/** What a preview adds to the bill: an update's new price against the old is in its detail; a create's or a delete's is the figure. */
export function costDeltaOf(preview: Pick<ConsolePreview, 'cost'> | undefined, kind: RiskKind): number | undefined {
  const cost = preview?.cost
  if (!cost) return undefined
  if (kind === 'update') {
    const was = /was ([\d.]+)/.exec(cost.detail ?? '')
    return was ? cost.monthly - Number(was[1]) : undefined
  }
  return cost.monthly
}

/** The kind of a verb, for the score. */
export function riskKindOf(verb: string): RiskKind {
  return verb === 'create' ? 'create' : verb === 'delete' ? 'delete' : verb === 'update' ? 'update' : 'change'
}

export const RISK_WORDS: Record<RiskLevel, { title: string; tone: 'healthy' | 'warning' | 'critical'; line: string }> = {
  low: { title: 'Low risk', tone: 'healthy', line: 'It runs as its policy says.' },
  medium: { title: 'Medium risk', tone: 'warning', line: 'Check what it touches before you send it.' },
  high: { title: 'High risk', tone: 'critical', line: 'It needs an approver: it waits for one before it runs.' },
}
