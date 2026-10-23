import { internal } from './support/tokens.js'
import { until } from './support/poll.js'
import { DANA, GONZALO, URLS, writeSeed, type Seed } from './support/stack.js'

/*
 * Seeds the console the way an operator sets up a new account, through the identity service's
 * own API with the harness's service token (no demo seed, no database writes):
 *
 *   - the account Compute, owned by team-compute, with Gonzalo as its first owner (owners group,
 *     account-admin); the identity service registers its platform service account with
 *     platform-fake (P-03) as it does in production
 *   - Dana, an operator in the account's members
 *   - the group compute-approvers with Dana in it: delete-vm needs an approval from that group,
 *     and the platform never lets the requester approve their own request
 *
 * Every write carries a fixed Idempotency-Key, so a second run against the same stack (./e2e.sh
 * --keep, then the journey again) answers the first run's results. Then it waits until OPA has
 * the account in the identity service's bundle, since every decision the BFF asks for reads it.
 */

const IDENTITY = 'console-identity-service'

async function post<T>(path: string, key: string, body: unknown): Promise<T> {
  const { status, body: answer } = await internal<T>(`${URLS.identity}${path}`, IDENTITY, { method: 'POST', body, headers: { 'Idempotency-Key': key } })
  if (status !== 200 && status !== 201) throw new Error(`identity service: POST ${path} answered ${status} ${JSON.stringify(answer)}`)
  return answer
}

export default async function globalSetup(): Promise<void> {
  await until('the BFF to issue service tokens', async () => (await internal(`${URLS.identity}/actuator/health`, IDENTITY)).status === 200, { timeout: 120_000 })

  const account = await post<{ accountId: string; name: string }>('/v1/accounts', 'console-e2e-seed-account-compute', {
    name: 'Compute',
    owningTeam: 'team-compute',
    ownerEmail: GONZALO.email,
    defaultRegion: 'global',
    costCentre: 'CC-4100',
  })
  const accountId = account.accountId

  const dana = await post<{ userId: string }>(`/v1/accounts/${accountId}/users`, 'console-e2e-seed-user-dana', {
    email: DANA.email,
    name: DANA.name,
    groups: ['members'],
  })

  const approverGroup = 'compute-approvers'
  await post(`/v1/accounts/${accountId}/groups`, 'console-e2e-seed-group-compute-approvers', {
    name: approverGroup,
    description: 'Decides the platform approvals of the compute workflows (delete-vm)',
    roles: ['operator'],
    members: [dana.userId],
  })

  await until(
    `OPA to load account ${accountId} with ${approverGroup}`,
    async () => {
      const res = await fetch(`${URLS.opa}/v1/data/console/accounts/${accountId}`)
      const data = (await res.json()) as { result?: { groups?: Record<string, unknown> } }
      return !!data.result?.groups?.[approverGroup]
    },
    { timeout: 60_000 },
  )

  const seed: Seed = { accountId, accountName: account.name, owningTeam: 'team-compute', costCentre: 'cc-4100', approverGroup, danaUserId: dana.userId }
  writeSeed(seed)
  console.log(`seeded account ${accountId} (Compute): Gonzalo owns it, Dana approves in ${approverGroup}`)
}
