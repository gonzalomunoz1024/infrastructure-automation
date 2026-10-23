import { useConsole } from '../../app/console'
import { bff } from '../../lib/bff'
import { idOfWfrn, partsOfWfrn } from '../../lib/descriptors'
import { hasRole } from '../../lib/session'
import { useQuery } from '../../lib/queries'
import type { Decision } from '../../lib/permissions'
import { stateApi } from '../../lib/state'

/*
 * What the trail, time travel, lineage and map pages share: the names of the people and services
 * that act (never their ids where a name exists), the names of the account's resources from the
 * map, and the decision on the evidence pack, which only the account's admins and auditors read.
 */

const SERVICE_ACTORS: Record<string, string> = {
  'drift-service': 'Drift service',
  'state-service': 'State service',
  platform: 'The platform',
  console: 'The console',
}

/** The account's people by user id (`dana` or `user_dana`), from the BFF's users; empty until it answers or when it may not. */
export function usePeople(): Map<string, string> {
  const { account } = useConsole()
  const users = useQuery(['state-people', account.id], () => bff.iam.users.list({ limit: 200 }))
  const out = new Map<string, string>()
  for (const u of users.data?.items ?? []) {
    if (!u.name) continue
    out.set(u.userId, u.name)
    out.set(u.userId.replace(/^user_/, ''), u.name)
  }
  return out
}

/** Who acted, in words: the person's name, a service's name, else what the event says. */
export function actorName(actor: string | undefined, people: Map<string, string>, given?: string): string {
  if (!actor) return 'Unknown'
  return given ?? people.get(actor) ?? SERVICE_ACTORS[actor] ?? actor
}

/** The names of the account's live resources by wfrn, from the resource map. */
export function useResourceNames(): Map<string, string> {
  const { account } = useConsole()
  const map = useQuery(['state-map', account.id, ''], () => stateApi.map())
  return new Map((map.data?.nodes ?? []).map((n) => [n.wfrn, n.name ?? idOfWfrn(n.wfrn) ?? n.wfrn]))
}

/** A resource's name, else the id at the end of its wfrn. */
export function nameOfWfrn(wfrn: string, names: Map<string, string>): string {
  return names.get(wfrn) ?? idOfWfrn(wfrn) ?? wfrn
}

/** The type a wfrn names. */
export function typeOfWfrn(wfrn: string): string | undefined {
  return partsOfWfrn(wfrn)?.type
}

/** Whether the person may read the evidence pack: the account's admins and auditors (hub decision 34). */
export function useEvidenceDecision(): Decision {
  const { session, account, region } = useConsole()
  if (hasRole(session, 'account-admin') || hasRole(session, 'auditor')) return { allowed: true }
  return {
    allowed: false,
    denied: {
      layer: 'console-iam',
      action: 'activity:evidence',
      resource: `wfrn:*:*:${region.name}:${account.id}:*`,
      reason: `The evidence pack of ${account.name} is for its admins and auditors. Ask for the auditor role to check the whole trail, or check one resource from its Activity tab.`,
      requestable: true,
      suggestedRole: 'auditor',
    },
  }
}

/** A day as the date inputs write it, `2026-10-22`, in UTC. */
export function dayOf(ms: number): string {
  return new Date(ms).toISOString().slice(0, 10)
}
