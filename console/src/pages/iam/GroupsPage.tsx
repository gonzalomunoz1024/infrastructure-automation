import { useMemo, useState, type FormEvent } from 'react'
import { Button, ConfirmDialog, EmptyState, Field, IconButton, Icon, Input, Modal, OverlayBody, OverlayFooter, OverlayHeader, PageHeader, Select, Skeleton, SkeletonText, Table, Tag, plural, type TableColumn } from '@iaf/ui'
import { CopyableId, DotTag, ErrorState, Facts, Section, When } from '@iaf/console-kit'
import { useConsole } from '../../app/console'
import { ActionButton } from '../../app/Refusal'
import { Link, href, navigate, pathFor, type PageProps } from '../../app/router'
import { bff } from '../../lib/bff'
import { useQuery } from '../../lib/queries'
import { ADMIN_ROLE, OWNERS_GROUP } from '../../lib/session'
import type { IamGroup, IamUser } from '../../lib/types'
import { RolePicker, RoleTags, UserStatusTag, personLabel, useAdminDecision, useIamWrite } from './common'

/*
 * Groups: the unit access is granted in. A group holds people and grants managed roles; an
 * identity-provider group can feed it at sign-in. Admins create groups, change their roles and
 * members, and delete the ones that are not built in.
 */

const NAME = /^[a-z0-9-]{2,64}$/

function memberCount(g: IamGroup): number {
  return g.members?.length ?? 0
}

export default function IamGroupsPage(_props: PageProps) {
  const { account } = useConsole()
  const can = useAdminDecision()
  const groups = useQuery(['iam', 'groups', account.id], () => bff.iam.groups.list({ limit: 200 }))
  const [creating, setCreating] = useState(false)
  const rows = useMemo(() => groups.data?.items ?? [], [groups.data])

  const columns: TableColumn<IamGroup>[] = [
    {
      key: 'name',
      header: 'Group',
      sortable: true,
      accessor: (g) => g.name,
      render: (g) => (
        <span className="min-w-0">
          <Link to={pathFor('iam-group', { id: g.groupId })} className="block truncate font-mono text-sm font-medium text-primary hover:underline">
            {g.name}
          </Link>
          {g.description && <span className="block truncate text-caption text-tertiary">{g.description}</span>}
        </span>
      ),
    },
    { key: 'roles', header: 'Grants', accessor: (g) => (g.roles ?? []).join(' '), render: (g) => <RoleTags roles={g.roles} link /> },
    { key: 'members', header: 'Members', sortable: true, numeric: true, accessor: (g) => memberCount(g), width: '7rem' },
    { key: 'idp', header: 'Fed by', accessor: (g) => (g.idpGroups ?? []).join(' '), render: (g) => (g.idpGroups?.length ? <span className="font-mono text-caption">{g.idpGroups.join(', ')}</span> : <span className="text-caption text-tertiary">nobody automatically</span>) },
    {
      key: 'kind',
      header: 'Kind',
      width: '6rem',
      render: (g) =>
        g.builtIn ? (
          <Tag size="sm" outline>
            built in
          </Tag>
        ) : (
          <span className="text-caption text-tertiary">custom</span>
        ),
    },
  ]

  return (
    <>
      <PageHeader
        title="Groups"
        subtitle={`Who may do what in ${account.name} is granted to groups, never to people one by one.`}
        actions={
          <ActionButton decision={can('iam:create', 'Create a group')} what="Create a group" variant="primary" icon="plus" onClick={() => setCreating(true)}>
            Create group
          </ActionButton>
        }
      />
      {groups.error ? <ErrorState error={groups.error} title="Could not load the groups" onRetry={() => void groups.refetch()} /> : <Table<IamGroup> caption={`Groups of ${account.name}`} columns={columns} rows={rows} rowKey={(g) => g.groupId} loading={groups.loading} defaultSort={{ key: 'name', dir: 'asc' }} onRowClick={(g) => navigate(pathFor('iam-group', { id: g.groupId }))} emptyIcon="grid" emptyTitle="No groups yet" emptyMessage="An admin creates the first one." />}
      {creating && <NewGroupDialog onClose={() => setCreating(false)} />}
    </>
  )
}

function NewGroupDialog({ onClose }: { onClose: () => void }) {
  const write = useIamWrite()
  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const [roles, setRoles] = useState<string[]>(['viewer'])
  const [idp, setIdp] = useState('')
  const [busy, setBusy] = useState(false)
  const nameOk = NAME.test(name)
  const valid = nameOk && roles.length > 0

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    if (!valid || busy) return
    setBusy(true)
    const idpGroups = idp
      .split(',')
      .map((s) => s.trim())
      .filter(Boolean)
    const group = await write('Create a group', () => bff.iam.groups.create({ name, description: description.trim() || undefined, roles, idpGroups }), { title: 'Group created', message: `${name} grants ${roles.join(', ')}.` })
    setBusy(false)
    if (group) {
      onClose()
      navigate(pathFor('iam-group', { id: group.groupId }))
    }
  }

  return (
    <Modal onClose={onClose} label="Create a group" width="max-w-lg">
      <form onSubmit={(e) => void submit(e)}>
        <OverlayHeader title="Create a group" subtitle="A group holds people and grants them managed roles in this account." icon="grid" />
        <OverlayBody>
          <div className="space-y-5">
            <Field label="Name" htmlFor="new-group-name" required hint="2 to 64 lower-case letters, digits and dashes." error={name && !nameOk ? 'Lower-case letters, digits and dashes only, 2 to 64 of them.' : undefined}>
              <Input id="new-group-name" mono required autoFocus value={name} onChange={(e) => setName(e.target.value.toLowerCase())} placeholder="team-storage" invalid={!!name && !nameOk} />
            </Field>
            <Field label="Description" htmlFor="new-group-description">
              <Input id="new-group-description" value={description} onChange={(e) => setDescription(e.target.value)} placeholder="Who is in it and why" />
            </Field>
            <RolePicker value={roles} onChange={setRoles} hint="What members may do. At least one." />
            <Field label="Identity-provider groups" htmlFor="new-group-idp" hint="Optional, comma separated. People in these groups at the identity provider join this group when they sign in.">
              <Input id="new-group-idp" mono value={idp} onChange={(e) => setIdp(e.target.value)} placeholder="corp-storage-engineers" />
            </Field>
          </div>
        </OverlayBody>
        <OverlayFooter>
          <Button type="button" variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          <Button type="submit" variant="primary" loading={busy} disabled={!valid}>
            Create group
          </Button>
        </OverlayFooter>
      </form>
    </Modal>
  )
}

/* ---------------------------------------------------------------- one group */

export function IamGroupPage({ route }: PageProps) {
  const groupId = route.params.id
  const { account, me, admin } = useConsole()
  const can = useAdminDecision()
  const write = useIamWrite()
  const group = useQuery(['iam', 'groups', account.id, groupId], () => bff.iam.groups.get(groupId))
  const users = useQuery(['iam', 'users', account.id], () => bff.iam.users.list({ limit: 200 }))
  const [rolesDraft, setRolesDraft] = useState<string[] | null>(null)
  const [adding, setAdding] = useState('')
  const [deleting, setDeleting] = useState(false)
  const [busy, setBusy] = useState<string | null>(null)

  const g = group.data
  const everyone = useMemo(() => users.data?.items ?? [], [users.data])
  const members = useMemo(() => {
    if (!g) return []
    const ids = new Set(g.members ?? [])
    return everyone.filter((u) => ids.has(u.userId) || u.groups?.includes(g.groupId) || u.groups?.includes(g.name))
  }, [g, everyone])
  const candidates = useMemo(() => everyone.filter((u) => !members.some((m) => m.userId === u.userId)), [everyone, members])
  const back = { href: href(pathFor('iam-groups')), label: 'Groups' }

  if (group.error) {
    return (
      <>
        <PageHeader back={back} title={groupId} />
        <ErrorState error={group.error} title="Could not load this group" onRetry={() => void group.refetch()} />
      </>
    )
  }
  if (!g) {
    return (
      <>
        <PageHeader back={back} title={<Skeleton className="h-7 w-48" />} />
        <SkeletonText lines={4} />
      </>
    )
  }

  const roles = rolesDraft ?? g.roles ?? []
  const rolesDirty = rolesDraft !== null && (rolesDraft.length !== (g.roles ?? []).length || rolesDraft.some((r) => !g.roles?.includes(r)))
  const memberIds = members.map((m) => m.userId)
  const inGroup = memberIds.includes(me.id)
  const addLabel = candidates.length ? 'Add a member…' : 'Everyone is in it'

  const saveRoles = async () => {
    if (!rolesDraft) return
    setBusy('roles')
    const next = await write('Change roles', () => bff.iam.groups.update(g.groupId, { roles: rolesDraft }, g.version), { title: 'Roles changed', message: `${g.name} now grants ${rolesDraft.join(', ') || 'nothing'}.` })
    setBusy(null)
    if (next) setRolesDraft(null)
  }
  const setMembers = async (what: string, next: string[], done: string) => {
    setBusy('members')
    await write(what, () => bff.iam.groups.update(g.groupId, { members: next }, g.version), { title: done })
    setBusy(null)
    setAdding('')
  }
  const remove = async () => {
    const ok = await write('Delete a group', () => bff.iam.groups.remove(g.groupId).then(() => true), { title: 'Group deleted', message: `${g.name} is gone; its members keep their other groups.` })
    if (ok) navigate(pathFor('iam-groups'), { replace: true })
  }

  const memberColumns: TableColumn<IamUser>[] = [
    {
      key: 'person',
      header: 'Member',
      sortable: true,
      accessor: (u) => personLabel(u),
      render: (u) => (
        <span className="min-w-0">
          <Link to={pathFor('iam-user', { id: u.userId })} className="block truncate font-medium text-primary hover:underline">
            {personLabel(u)}
          </Link>
          <span className="block truncate text-caption text-tertiary">{u.email}</span>
        </span>
      ),
    },
    { key: 'userId', header: 'User id', render: (u) => <span className="font-mono text-caption">{u.userId}</span>, width: '10rem' },
    { key: 'status', header: 'Status', render: (u) => <UserStatusTag status={u.status} />, width: '7rem' },
    { key: 'lastSignInAt', header: 'Last sign-in', render: (u) => (u.lastSignInAt ? <When at={u.lastSignInAt} className="text-caption" /> : <span className="text-caption text-tertiary">never</span>), width: '9rem' },
    ...(admin
      ? [
          {
            key: 'remove',
            header: <span className="sr-only">Remove</span>,
            width: '3rem',
            align: 'right' as const,
            render: (u: IamUser) => (
              <IconButton label={`Remove ${personLabel(u)} from ${g.name}`} size="sm" disabled={busy !== null} onClick={() => void setMembers('Remove a member', memberIds.filter((id) => id !== u.userId), `${personLabel(u)} left ${g.name}`)}>
                <Icon name="close" size={14} />
              </IconButton>
            ),
          },
        ]
      : []),
  ]

  return (
    <>
      <PageHeader
        back={back}
        eyebrow="Group"
        title={
          <span className="flex flex-wrap items-center gap-2">
            <span className="font-mono">{g.name}</span>
            {g.builtIn && (
              <Tag size="sm" outline>
                built in
              </Tag>
            )}
            {inGroup && (
              <DotTag size="sm" tone="accent">
                you are in it
              </DotTag>
            )}
          </span>
        }
        subtitle={g.description ?? 'No description.'}
        actions={
          g.name === OWNERS_GROUP ? (
            <Button variant="danger" icon="trash" disabled title="The owners group stays; change its members instead.">
              Delete group
            </Button>
          ) : (
            <ActionButton decision={can('iam:delete', 'Delete a group')} what="Delete a group" variant="danger" icon="trash" onClick={() => setDeleting(true)}>
              Delete group
            </ActionButton>
          )
        }
      />
      <div className="space-y-8">
        <Section title="About">
          <Facts
            columns={3}
            items={[
              { label: 'Group id', value: <CopyableId id={g.groupId} /> },
              { label: 'Grants', value: <RoleTags roles={g.roles} link empty="nothing yet" /> },
              { label: 'Members', value: plural(members.length, 'person', 'people') },
              { label: 'Fed by', value: g.idpGroups?.length ? <span className="font-mono text-caption">{g.idpGroups.join(', ')}</span> : <span className="text-tertiary">no identity-provider group</span>, wide: true },
              { label: 'Version', value: <span className="font-mono text-caption">{g.version}</span> },
            ]}
          />
        </Section>

        <Section
          title="Roles"
          description={admin ? 'What members of this group may do in this account.' : 'What members of this group may do in this account. Only an admin changes it.'}
          actions={
            admin && rolesDirty ? (
              <div className="flex items-center gap-2">
                <Button size="sm" variant="ghost" onClick={() => setRolesDraft(null)} disabled={busy === 'roles'}>
                  Discard
                </Button>
                <Button size="sm" variant="primary" onClick={() => void saveRoles()} loading={busy === 'roles'}>
                  Save roles
                </Button>
              </div>
            ) : undefined
          }
        >
          {admin ? <RolePicker value={roles} onChange={setRolesDraft} legend="Grants" locked={g.name === OWNERS_GROUP ? [ADMIN_ROLE] : []} hint={g.name === OWNERS_GROUP ? 'The owners group always grants Admin: the account keeps its administrators.' : inGroup && g.roles?.includes(ADMIN_ROLE) ? 'Careful: dropping Admin here may take your own Admin role away.' : undefined} /> : <RoleTags roles={g.roles} link size="md" empty="nothing yet" />}
        </Section>

        <Section
          title="Members"
          description={admin ? 'Add someone from the account, or remove them from this group.' : 'The people in this group.'}
          actions={
            admin ? (
              <div className="flex items-center gap-2">
                <Select size="sm" ariaLabel={adding ? `Add a member: ${personLabel(candidates.find((u) => u.userId === adding) ?? { userId: adding })}` : addLabel} placeholder={addLabel} value={adding} onChange={setAdding} disabled={candidates.length === 0 || busy !== null} options={candidates.map((u) => ({ value: u.userId, label: personLabel(u), hint: u.userId }))} />
                <Button size="sm" icon="plus" disabled={!adding || busy !== null} loading={busy === 'members'} onClick={() => void setMembers('Add a member', [...memberIds, adding], `${personLabel(candidates.find((u) => u.userId === adding) ?? { userId: adding })} joined ${g.name}`)}>
                  Add
                </Button>
              </div>
            ) : undefined
          }
        >
          {users.error ? <ErrorState error={users.error} compact title="Could not load the members" onRetry={() => void users.refetch()} /> : <Table<IamUser> caption={`Members of ${g.name}`} columns={memberColumns} rows={members} rowKey={(u) => u.userId} loading={users.loading} dense defaultSort={{ key: 'person', dir: 'asc' }} empty={<EmptyState compact icon="users" title="Nobody yet" message={g.idpGroups?.length ? 'People join when they sign in with a matching identity-provider group.' : 'An admin adds the first member.'} />} />}
        </Section>
      </div>
      {deleting && (
        <ConfirmDialog title={`Delete ${g.name}?`} tone="danger" icon="trash" confirmLabel="Delete group" requireText={g.name} onClose={() => setDeleting(false)} onConfirm={remove} message={`Its ${plural(members.length, 'member')} lose the roles it grants (${(g.roles ?? []).join(', ') || 'none'}). Anyone left in no group is removed from the account.`} />
      )}
    </>
  )
}
