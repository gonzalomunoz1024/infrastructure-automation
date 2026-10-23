import { ALLOWED, type Decision } from './permissions'
import { ADMIN_ROLE, roleLabel } from './session'

/*
 * What the managed roles allow on the console's own actions that no type carries (blueprints:*,
 * scorecards:*, event-subscriptions:*): everyone reads, an operator also makes changes, and only
 * an admin deletes, as the managed policies say (operator: everything but `*:delete` and IAM). The
 * pages grey a write with this decision before any call, as decision 40 does for resource groups;
 * the BFF's Denial replaces it the moment a call is refused.
 */
export function roleDecision(roles: readonly string[], action: string, what: string): Decision {
  const verb = action.slice(action.indexOf(':') + 1)
  const del = verb === 'delete'
  const may = del ? roles.includes(ADMIN_ROLE) : roles.includes(ADMIN_ROLE) || roles.includes('operator')
  if (may) return ALLOWED
  const held = roles.length ? [...roles].sort().map(roleLabel).join(', ') : 'no role here'
  const who = del ? 'an Admin' : 'an Operator or an Admin'
  return {
    allowed: false,
    denied: { layer: 'console-iam', action, reason: `Only ${who} of this account can ${what.charAt(0).toLowerCase()}${what.slice(1)}; you hold ${held}`, role: roles[0], requestable: true, suggestedRole: del ? ADMIN_ROLE : 'operator' },
  }
}

/** The first refusal among several decisions, else allowed. */
export function firstRefusal(decisions: readonly Decision[]): Decision {
  return decisions.find((d) => !d.allowed) ?? ALLOWED
}
