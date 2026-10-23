import { useMemo } from 'react'
import { Card, Icon, PageHeader, Stat, type IconName } from '@iaf/ui'
import { DotTag, Facts } from '@iaf/console-kit'
import { useAccessRequestQueue } from '../app/access'
import { useConsole } from '../app/console'
import { Link, pathFor, type PageProps, type RouteName } from '../app/router'
import { formatAccountId } from '../lib/accounts'
import { bff } from '../lib/bff'
import { layerDescription } from '../lib/permissions'
import { useQuery } from '../lib/queries'
import { ADMIN_ROLE, MANAGED_ROLES, ROLE_SUMMARY, roleLabel, roleTone } from '../lib/session'
import { GroupLinks, RoleTags, personLabel, rolesFromGroups } from './iam/common'

/*
 * Identity and access, the overview: who you are in this account, what is in it (people, groups,
 * keys, requests), the four managed roles, and how a decision is made, so the refusal dialog's
 * words have a home.
 */

const ROLE_ICON: Record<string, IconName> = { viewer: 'eye', operator: 'play', 'account-admin': 'shield', auditor: 'audit' }

export default function IamPage(_props: PageProps) {
  const { me, account, admin, session } = useConsole()
  const users = useQuery(['iam', 'users', account.id], () => bff.iam.users.list({ limit: 200 }))
  const groups = useQuery(['iam', 'groups', account.id], () => bff.iam.groups.list({ limit: 200 }))
  const keys = useQuery(['iam', 'keys', account.id, 'all'], () => bff.iam.accessKeys.list({ limit: 200 }))
  const queue = useAccessRequestQueue()
  const mine = useQuery(['access-requests', 'list', account.id, 'mine', me.id], () => bff.accessRequests.list({ requester: me.id, limit: 50 }))
  const all = useMemo(() => groups.data?.items ?? [], [groups.data])
  const admins = useMemo(() => (users.data?.items ?? []).filter((u) => rolesFromGroups(u.groups, all).includes(ADMIN_ROLE)), [users.data, all])
  const activeKeys = (keys.data?.items ?? []).filter((k) => k.status === 'active' || k.status === 'rotating').length
  const myPending = (mine.data?.items ?? []).filter((r) => r.status === 'pending').length

  const tiles: { route: RouteName; label: string; icon: IconName; value: number | undefined; hint: string }[] = [
    { route: 'iam-users', label: 'Users', icon: 'users', value: users.data?.items.length, hint: 'members of this account' },
    { route: 'iam-groups', label: 'Groups', icon: 'grid', value: groups.data?.items.length, hint: 'grant the roles' },
    { route: 'iam-keys', label: 'Access keys', icon: 'key', value: keys.data ? activeKeys : undefined, hint: admin ? 'active, across the account' : 'active, yours' },
    { route: 'iam-requests', label: 'Access requests', icon: 'approval', value: queue.readable ? queue.pending.length : mine.data ? myPending : undefined, hint: queue.readable ? 'waiting for a decision' : 'of yours still waiting' },
  ]

  return (
    <>
      <PageHeader title="Identity and access" subtitle={`Who may do what in ${account.name}: people, the groups they are in, the managed roles those groups grant, and the keys for the API.`} />
      <div className="grid grid-cols-1 gap-6 lg:grid-cols-3">
        <Card title="You" subtitle="From the firm’s identity provider, through the console’s session" className="lg:col-span-1">
          <Facts
            columns={2}
            items={[
              { label: 'Name', value: me.name },
              { label: 'Id', value: <span className="font-mono">{me.id}</span> },
              { label: 'Email', value: me.email ?? 'Not set', wide: true },
              { label: 'Roles here', value: <RoleTags roles={me.roles} link empty="none yet" />, wide: true },
              { label: 'Groups', value: <GroupLinks groups={session.account.groups} all={all} />, wide: true },
              { label: 'Account', value: `${account.name} (${formatAccountId(account.id)})`, wide: true },
            ]}
          />
        </Card>

        <div className="grid grid-cols-2 gap-4 lg:col-span-2">
          {tiles.map((t) => (
            <Link key={t.route} to={pathFor(t.route)} className="block rounded-lg focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-accent">
              <Card interactive className="h-full">
                <Stat label={t.label} icon={t.icon} value={t.value ?? '…'} hint={t.hint} />
              </Card>
            </Link>
          ))}
        </div>

        <Card title="Managed roles" subtitle="Every account has these four. Groups grant them." className="lg:col-span-2">
          <ul className="grid grid-cols-1 gap-4 sm:grid-cols-2">
            {MANAGED_ROLES.map((r) => (
              <li key={r}>
                <Link to={pathFor('iam-role', { id: r })} className="flex h-full items-start gap-3 rounded-md bg-surface-secondary p-4 transition-colors hover:bg-stroke-light/60">
                  <span className="flex size-7 shrink-0 items-center justify-center rounded-md bg-accent-tint text-accent">
                    <Icon name={ROLE_ICON[r] ?? 'shield'} size={15} />
                  </span>
                  <span className="min-w-0 flex-1">
                    <span className="flex items-center gap-2">
                      <span className="text-sm font-semibold text-primary">{roleLabel(r)}</span>
                      {roleLabel(r) !== r && <span className="font-mono text-caption text-tertiary">{r}</span>}
                      {(me.roles ?? []).includes(r) && (
                        <DotTag size="sm" tone={roleTone(r)}>
                          you
                        </DotTag>
                      )}
                    </span>
                    <span className="mt-1 block text-caption text-secondary">{ROLE_SUMMARY[r]}</span>
                  </span>
                  <Icon name="chevron-right" size={14} className="mt-1 shrink-0 text-tertiary" />
                </Link>
              </li>
            ))}
          </ul>
          <p className="mt-4 text-caption text-tertiary">
            Write custom <Link to={pathFor('iam-policies')} className="text-accent hover:underline">policies</Link>, check them in the <Link to={pathFor('iam-simulator')} className="text-accent hover:underline">policy simulator</Link>, and read the organization’s <Link to={pathFor('iam-guardrails')} className="text-accent hover:underline">guardrails</Link>.
          </p>
        </Card>

        <Card title="How a decision is made" subtitle="Two layers, in this order" className="lg:col-span-1">
          <ol className="space-y-4 text-sm">
            <li className="flex gap-3">
              <span className="flex size-6 shrink-0 items-center justify-center rounded-full bg-accent-tint font-mono text-caption font-semibold text-accent">1</span>
              <span>
                <span className="font-medium text-primary">Console IAM.</span> <span className="text-secondary">{layerDescription('console-iam', account.name)}</span>
              </span>
            </li>
            <li className="flex gap-3">
              <span className="flex size-6 shrink-0 items-center justify-center rounded-full bg-accent-tint font-mono text-caption font-semibold text-accent">2</span>
              <span>
                <span className="font-medium text-primary">The platform.</span> <span className="text-secondary">{layerDescription('platform', account.name)}</span>
              </span>
            </li>
          </ol>
          <p className="mt-4 text-caption text-secondary">When something is refused, the console names the layer that decided and the statement. Request access sends it to the account’s approvers: its admins{admins.length ? ` (${admins.map(personLabel).join(', ')})` : ''} and the members of any group they let decide.</p>
        </Card>
      </div>
    </>
  )
}
