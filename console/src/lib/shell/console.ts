import { bff, idempotencyKey, type ResourceListParams } from '../bff'
import { explainLine } from '../plain'
import { denialOf } from '../permissions'
import { getSession } from '../session'
import type { ShellClient, ShellContext } from './core'

/*
 * The adapter between the command core and the console: the core's `ShellClient` answered by the
 * BFF client with the page's own session (cookie and CSRF token, as every page sends them), and a
 * failure said in the console's words (lib/plain), a refusal with its reason. When the
 * `@iaf/console-cli-core` package lands on the `console-sdk` branch, its `execute` replaces
 * ./core here and nothing else changes.
 */

export const consoleShellClient: ShellClient = {
  session: async () => {
    const s = getSession() ?? (await bff.session())
    return { user: s.user, account: { accountId: s.account.accountId, name: s.account.name, roles: s.account.roles }, region: s.region }
  },
  types: async () => (await bff.types()).map((t) => ({ key: t.key, type: t.type, service: t.service, plural: t.plural, title: t.title, actions: t.actions.map((a) => ({ verb: a.verb, title: a.title, destructive: a.destructive, approval: a.approval, allowed: a.allowed, denied: a.denied ?? (a.deniedReason ? { reason: a.deniedReason, action: `${t.service}:${a.verb}` } : undefined) })) })),
  list: async (type, params) => {
    const page = await bff.resources.list(type, params as ResourceListParams)
    return { items: page.items, next: page.next }
  },
  get: (type, wfrn) => bff.resources.get(type, wfrn),
  run: (type, wfrn, verb, body, key) => bff.resources.run(type, wfrn, verb, body, key),
  jobs: async (params) => {
    const page = await bff.jobs.list(params as Record<string, string | number>)
    return { items: page.items, next: page.next }
  },
  job: async (jobId) => {
    const j = await bff.jobs.get(jobId)
    return { ...j, steps: j.steps?.map((s) => ({ id: s.id, name: s.name, status: s.status })), attention: j.attention }
  },
}

/** A failed call as the shell prints it: a refusal's reason, else the console's plain words. */
export function describeShellError(e: unknown): string {
  const denied = denialOf(e)
  if (denied) return `Not allowed: ${denied.reason}.`
  return explainLine(e)
}

export function consoleShellContext(extra: Pick<ShellContext, 'emit' | 'signal'>): ShellContext {
  return { client: consoleShellClient, describeError: describeShellError, newKey: idempotencyKey, ...extra }
}
