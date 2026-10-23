import { ADMIN_ROLE, roleLabel } from './session'
import type { BffConsoleType, Denial, DenialLayer } from './types'

/*
 * What the signed-in person may do, as the BFF decides it: every action of every type carries
 * `allowed` and, when not, a Denial that names the layer that refused (the account's console
 * IAM policies, or the platform's own role for the account), the statement that decided, and
 * whether access can be requested. The pages grey what is denied before any call is made, and
 * the refusal view explains the rest.
 */

export type { Denial, DenialLayer }

export interface Decision {
  allowed: boolean
  denied?: Denial
}

export const ALLOWED: Decision = { allowed: true }

/** `<service>/<type>` → verb → decision. */
export type PermissionMap = Record<string, Record<string, Decision>>

export function typeKey(service: string, type: string): string {
  return `${service}/${type}`
}

/** 'compute:create': the action name the policies speak. */
export function actionOf(service: string, verb: string): string {
  return `${service}:${verb}`
}

/** The decisions of the BFF's types, by type key and verb; a `deniedReason` alone becomes a console-iam denial. */
export function permissionsOf(types: readonly BffConsoleType[] | undefined): PermissionMap {
  const out: PermissionMap = {}
  for (const t of types ?? []) {
    const key = t.key || typeKey(t.service, t.type)
    const verbs: Record<string, Decision> = {}
    for (const a of t.actions ?? []) {
      if (a.allowed === false) {
        const denied: Denial = a.denied ?? { layer: 'console-iam', action: actionOf(t.service, a.verb), reason: a.deniedReason ?? `Your role does not allow ${actionOf(t.service, a.verb)}`, requestable: true }
        verbs[a.verb] = { allowed: false, denied }
      } else {
        verbs[a.verb] = ALLOWED
      }
    }
    out[key] = verbs
  }
  return out
}

/** The decision for one verb of a type; allowed when the BFF has not said (the call itself will refuse). */
export function decide(perms: PermissionMap | undefined, service: string, type: string, verb: string): Decision {
  return perms?.[typeKey(service, type)]?.[verb] ?? ALLOWED
}

export function layerLabel(layer: DenialLayer): string {
  return layer === 'platform' ? 'The platform' : 'Console IAM'
}

export function layerDescription(layer: DenialLayer, accountName?: string): string {
  return layer === 'platform' ? `The platform's own role for the account${accountName ? ` ${accountName}` : ''}, which the platform team manages.` : `The policies of the account${accountName ? ` ${accountName}` : ''}: the groups you are in, the roles they carry, and the statements of those roles.`
}

const VERB_TITLES: Record<string, string> = { create: 'Create', update: 'Update', delete: 'Delete', list: 'List', get: 'View', read: 'Read', restart: 'Restart', resize: 'Resize', upgrade: 'Upgrade', patch: 'Patch', start: 'Start', stop: 'Stop', decide: 'Decide', rotate: 'Rotate' }

/** 'restart' → 'Restart', 'add-disk' → 'Add disk'. */
export function verbTitle(verb: string): string {
  if (VERB_TITLES[verb]) return VERB_TITLES[verb]
  const words = verb.replace(/[-_]+/g, ' ').trim()
  return words.charAt(0).toUpperCase() + words.slice(1)
}

/** 'compute:create' → { service: 'compute', verb: 'create' }. */
export function splitAction(action: string): { service: string; verb: string } {
  const i = action.indexOf(':')
  return i < 0 ? { service: '', verb: action } : { service: action.slice(0, i), verb: action.slice(i + 1) }
}

/** The one-line headline of a refusal. */
export function denialTitle(d: Denial): string {
  return d.layer === 'platform' ? `The platform does not allow ${d.action} in this account` : `Your role does not allow ${d.action}`
}

/** Where the decision came from, in words that name the statement when there is one. */
export function decidedBy(d: Denial): string {
  if (d.layer === 'platform') return d.scope ? `The platform's role for this account has no ${d.scope} scope.` : 'The platform refused the account.'
  if (d.refusedBy === 'guardrail') return d.guardrailId ? `The organization's guardrail ${d.guardrailId} denies it${d.statement ? ` (statement ${d.statement})` : ''}. No role in the account can allow what a guardrail denies.` : "It is outside the organization's guardrails, and no role in the account can allow it."
  // a refusal that no role of the account could lift (the organization's own writes): its reason says who may
  if (d.refusedBy === 'policy' && d.requestable === false && !d.policyId && !d.role && d.reason) return `${d.reason}.`
  if (d.refusedBy === 'boundary') return `It is outside the permission boundary ${d.policyId ?? ''} of the role ${d.role ?? ''}, so the role's allow does not count.`
  if (d.policyId && d.statement) return `Statement ${d.statement} of policy ${d.policyId} denies it${d.role ? ` for the role ${d.role}` : ''}.`
  if (d.policyId) return `Policy ${d.policyId} denies it${d.role ? ` for the role ${d.role}` : ''}.`
  return d.role ? `No statement of the role ${d.role} allows it, and what no statement allows is denied.` : 'No statement of your roles allows it, and what no statement allows is denied.'
}

/** A Denial out of anything a failed call threw, when it carried one. */
export function denialOf(e: unknown): Denial | null {
  if (!e || typeof e !== 'object') return null
  const d = (e as { denied?: unknown }).denied
  if (d && typeof d === 'object' && typeof (d as Denial).layer === 'string' && typeof (d as Denial).action === 'string') return d as Denial
  return null
}

/**
 * The console's own rule for identity and access in phase 1: the managed roles are fixed, and
 * only account-admin (shown as Admin) writes IAM. The pages grey an IAM action for everyone else
 * with this decision; the BFF's answer, with the statement that decided, replaces it the moment
 * a call is refused.
 */
export function adminOnly(roles: readonly string[], action: string, what: string): Decision {
  if (roles.includes(ADMIN_ROLE)) return ALLOWED
  const role = roles.length ? [...roles].sort().join(', ') : null
  const held = roles.length ? [...roles].sort().map(roleLabel).join(', ') : 'no role here'
  return {
    allowed: false,
    denied: { layer: 'console-iam', action, reason: `Only an Admin of this account can ${what.charAt(0).toLowerCase()}${what.slice(1)}; you hold ${held}`, role: role ?? undefined, requestable: true, suggestedRole: ADMIN_ROLE },
  }
}
