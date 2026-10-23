import { useMemo } from 'react'
import { Card, Icon, PageHeader, Skeleton, SkeletonText, Table, Tag, plural, type IconName, type TableColumn } from '@iaf/ui'
import { CopyableId, DotTag, ErrorState, Facts, Section } from '@iaf/console-kit'
import { useConsole } from '../../app/console'
import { LaterButton, PHASES } from '../../app/Phase'
import { Link, href, pathFor, type PageProps } from '../../app/router'
import { bff } from '../../lib/bff'
import { useQuery } from '../../lib/queries'
import { ROLE_SUMMARY, roleLabel, roleTone, type ManagedRole } from '../../lib/session'
import type { IamPolicy, IamRole, IamStatement } from '../../lib/types'
import { GroupLinks, RoleTags, sortRoles } from './common'

/*
 * Roles: the four managed roles every account has, and any custom role the account holds (the
 * approvers' role), each a bundle of policies whose statements decide. A refusal names the
 * statement; this is where to read it in full. Writing custom roles and policies arrives with
 * phase 7.
 */

const ROLE_ICON: Record<string, IconName> = { viewer: 'eye', operator: 'play', 'account-admin': 'shield', auditor: 'audit', approver: 'check' }

function iconOf(role: string): IconName {
  return ROLE_ICON[role] ?? 'shield'
}

function summaryOf(role: IamRole): string {
  return role.description ?? ROLE_SUMMARY[role.roleId as ManagedRole] ?? ''
}

export default function IamRolesPage(_props: PageProps) {
  const { account, me } = useConsole()
  const roles = useQuery(['iam', 'roles', account.id], () => bff.iam.roles.list({ limit: 50 }))
  const groups = useQuery(['iam', 'groups', account.id], () => bff.iam.groups.list({ limit: 200 }))
  const list = useMemo(() => [...(roles.data?.items ?? [])].sort((a, b) => sortRoles([a.roleId, b.roleId])[0] === a.roleId ? -1 : 1), [roles.data])
  const mine = new Set(me.roles ?? [])

  return (
    <>
      <PageHeader
        title="Roles"
        subtitle={`What each role may do in ${account.name}, statement by statement. Groups grant roles; people never hold one directly.`}
        actions={
          <LaterButton phase={7} variant="primary" icon="plus">
            Create role
          </LaterButton>
        }
      />
      {roles.error ? (
        <ErrorState error={roles.error} title="Could not load the roles" onRetry={() => void roles.refetch()} />
      ) : roles.loading ? (
        <SkeletonText lines={6} />
      ) : (
        <ul className="grid grid-cols-1 gap-4 sm:grid-cols-2" aria-label="Roles">
          {list.map((r) => {
            const granting = (groups.data?.items ?? []).filter((g) => g.roles?.includes(r.roleId))
            return (
              <li key={r.roleId}>
                <Link to={pathFor('iam-role', { id: r.roleId })} className="block rounded-lg focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-accent" data-role-card={r.roleId}>
                  <Card interactive className="h-full">
                    <div className="flex items-start gap-3">
                      <span className="flex size-9 shrink-0 items-center justify-center rounded-md bg-accent-tint text-accent">
                        <Icon name={iconOf(r.roleId)} size={18} />
                      </span>
                      <div className="min-w-0 flex-1">
                        <div className="flex flex-wrap items-center gap-2">
                          <span className="text-sm font-semibold text-primary">{roleLabel(r.name)}</span>
                          {roleLabel(r.name) !== r.name && <span className="font-mono text-caption text-tertiary">{r.name}</span>}
                          {r.builtIn !== false && (
                            <Tag size="sm" outline>
                              managed
                            </Tag>
                          )}
                          {mine.has(r.roleId) && (
                            <DotTag size="sm" tone={roleTone(r.roleId)}>
                              you hold it
                            </DotTag>
                          )}
                        </div>
                        <p className="mt-1 text-caption text-secondary">{summaryOf(r)}</p>
                        <p className="mt-2 text-caption text-tertiary">
                          {plural(r.policies?.length ?? 0, 'policy', 'policies')} · granted by {granting.length ? granting.map((g) => g.name).join(', ') : 'no group yet'}
                        </p>
                      </div>
                      <Icon name="chevron-right" size={16} className="shrink-0 text-tertiary" />
                    </div>
                  </Card>
                </Link>
              </li>
            )
          })}
        </ul>
      )}
      <p className="mt-6 text-caption text-tertiary">Writing custom roles and policies in the console, in the same statements, arrives with {PHASES[7]}.</p>
    </>
  )
}

/* ---------------------------------------------------------------- one role */

function EffectTag({ effect }: { effect: IamStatement['effect'] }) {
  return (
    <DotTag size="sm" tone={effect === 'allow' ? 'healthy' : 'critical'} uppercase>
      {effect}
    </DotTag>
  )
}

const statementColumns: TableColumn<IamStatement>[] = [
  { key: 'sid', header: 'Statement', width: '11rem', render: (s) => <span className="font-mono text-caption text-primary">{s.sid ?? '—'}</span> },
  { key: 'effect', header: 'Effect', width: '6rem', render: (s) => <EffectTag effect={s.effect} /> },
  {
    key: 'actions',
    header: 'Actions',
    render: (s) => (
      <span className="flex flex-col gap-1">
        <span className="flex flex-wrap gap-1">
          {s.actions.map((a) => (
            <code key={a} className="rounded bg-surface-secondary px-1.5 py-0.5 font-mono text-caption text-primary">
              {a}
            </code>
          ))}
        </span>
        {!!s.notActions?.length && (
          <span className="flex flex-wrap items-center gap-1" data-not-actions>
            <span className="text-caption text-tertiary">except</span>
            {s.notActions.map((a) => (
              <code key={a} className="rounded border border-separator px-1.5 py-0.5 font-mono text-caption text-secondary">
                {a}
              </code>
            ))}
          </span>
        )}
      </span>
    ),
  },
  {
    key: 'resources',
    header: 'Resources',
    render: (s) => (
      <span className="flex flex-col gap-0.5">
        {s.resources.map((r) => (
          <span key={r} className="break-all font-mono text-caption text-secondary">
            {r}
          </span>
        ))}
      </span>
    ),
  },
]

export function IamRolePage({ route }: PageProps) {
  const roleId = route.params.id
  const { account, me } = useConsole()
  const role = useQuery(['iam', 'roles', account.id, roleId], () => bff.iam.roles.get(roleId))
  const policies = useQuery(['iam', 'policies', account.id], () => bff.iam.policies.list({ limit: 100 }))
  const groups = useQuery(['iam', 'groups', account.id], () => bff.iam.groups.list({ limit: 200 }))
  const back = { href: href(pathFor('iam-roles')), label: 'Roles' }
  const r = role.data

  if (role.error) {
    return (
      <>
        <PageHeader back={back} title={roleId} />
        <ErrorState error={role.error} title="Could not load this role" onRetry={() => void role.refetch()} />
      </>
    )
  }
  if (!r) {
    return (
      <>
        <PageHeader back={back} title={<Skeleton className="h-7 w-48" />} />
        <SkeletonText lines={4} />
      </>
    )
  }

  const attached: IamPolicy[] = (r.policies ?? []).map((id) => policies.data?.items.find((p) => p.policyId === id)).filter((p): p is IamPolicy => !!p)
  const granting = (groups.data?.items ?? []).filter((g) => g.roles?.includes(r.roleId))
  const held = (me.roles ?? []).includes(r.roleId)

  return (
    <>
      <PageHeader
        back={back}
        eyebrow={r.builtIn === false ? 'Custom role' : 'Managed role'}
        title={
          <span className="flex flex-wrap items-center gap-2">
            <span className="flex size-8 items-center justify-center rounded-md bg-accent-tint text-accent">
              <Icon name={iconOf(r.roleId)} size={16} />
            </span>
            {roleLabel(r.name)}
            {roleLabel(r.name) !== r.name && <span className="font-mono text-caption font-normal text-tertiary">{r.name}</span>}
            {held && (
              <DotTag size="sm" tone={roleTone(r.roleId)}>
                you hold it
              </DotTag>
            )}
          </span>
        }
        subtitle={summaryOf(r)}
      />
      <div className="space-y-8">
        <Section title="About">
          <Facts
            columns={3}
            items={[
              { label: 'Role id', value: <CopyableId id={r.roleId} /> },
              { label: 'Kind', value: r.builtIn === false ? 'Custom' : 'Managed by the platform' },
              { label: 'You', value: held ? <RoleTags roles={[r.roleId]} /> : <span className="text-tertiary">you do not hold it in {account.name}</span> },
              { label: 'Granted by', value: <GroupLinks groups={granting.map((g) => g.groupId)} all={granting} />, wide: true },
              { label: 'Policies', value: plural(attached.length, 'policy', 'policies') },
            ]}
          />
        </Section>

        {policies.error ? (
          <ErrorState error={policies.error} title="Could not load the policies" onRetry={() => void policies.refetch()} />
        ) : policies.loading ? (
          <SkeletonText lines={5} />
        ) : (
          attached.map((p) => (
            <Section key={p.policyId} title={<span className="flex flex-wrap items-center gap-2">{p.name}<span className="font-mono text-caption font-normal text-tertiary">{p.policyId}</span></span>} description={p.description ?? 'Explicit deny wins over allow; nothing matched is denied.'}>
              <div data-policy={p.policyId}>
                <Table<IamStatement> caption={`Statements of ${p.name}`} columns={statementColumns} rows={p.statements} rowKey={(s, i) => s.sid ?? String(i)} dense emptyIcon="shield" emptyTitle="No statements" emptyMessage="This policy allows nothing." />
              </div>
            </Section>
          ))
        )}
        <p className="text-caption text-tertiary">How a decision is made: an explicit deny in any statement wins; otherwise any allow grants, except the actions it lists after except; otherwise the action is denied. The platform then checks the account’s own scopes.</p>
      </div>
    </>
  )
}
