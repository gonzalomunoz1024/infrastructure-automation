import { useMemo, useState, type FormEvent } from 'react'
import { Button, ConfirmDialog, EmptyState, Field, Input, Modal, OverlayBody, OverlayFooter, OverlayHeader, PageHeader, Skeleton, SkeletonText, Table, plural, type TableColumn } from '@iaf/ui'
import { CopyableId, DotTag, ErrorState, Facts, Section, When } from '@iaf/console-kit'
import { useConsole } from '../../app/console'
import { ActionButton } from '../../app/Refusal'
import { Link, href, navigate, pathFor, type PageProps } from '../../app/router'
import { bff } from '../../lib/bff'
import { useQuery } from '../../lib/queries'
import { ADMIN_ROLE } from '../../lib/session'
import type { AccessKey, IamGroup, IamUser } from '../../lib/types'
import { GroupLinks, GroupPicker, KeyStatusTag, RoleTags, UserStatusTag, groupIdsOf, personLabel, rolesFromGroups, useAdminDecision, useIamWrite } from './common'

/*
 * Users: the people who are members of this account, the groups they are in and the roles
 * those groups grant. Admins add people and change their groups; everyone can see who is here.
 */

const EMAIL = /^[^@\s]+@[^@\s]+\.[^@\s]+$/

function matches(u: IamUser, q: string): boolean {
  if (!q) return true
  const needle = q.toLowerCase()
  return [u.name, u.email, u.userId, ...(u.groups ?? [])].some((v) => v?.toLowerCase().includes(needle))
}

export default function IamUsersPage(_props: PageProps) {
  const { account } = useConsole()
  const can = useAdminDecision()
  const users = useQuery(['iam', 'users', account.id], () => bff.iam.users.list({ limit: 200 }))
  const groups = useQuery(['iam', 'groups', account.id], () => bff.iam.groups.list({ limit: 200 }))
  const [q, setQ] = useState('')
  const [adding, setAdding] = useState(false)
  const all = useMemo(() => groups.data?.items ?? [], [groups.data])
  const rows = useMemo(() => (users.data?.items ?? []).filter((u) => matches(u, q)), [users.data, q])

  const columns: TableColumn<IamUser>[] = [
    {
      key: 'person',
      header: 'Person',
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
    { key: 'userId', header: 'User id', sortable: true, accessor: (u) => u.userId, render: (u) => <span className="font-mono text-caption">{u.userId}</span>, width: '10rem' },
    { key: 'groups', header: 'Groups', render: (u) => <GroupLinks groups={u.groups} all={all} /> },
    { key: 'roles', header: 'Roles', accessor: (u) => rolesFromGroups(u.groups, all).join(' '), render: (u) => <RoleTags roles={rolesFromGroups(u.groups, all)} link /> },
    { key: 'status', header: 'Status', sortable: true, accessor: (u) => u.status, render: (u) => <UserStatusTag status={u.status} />, width: '7rem' },
    { key: 'lastSignInAt', header: 'Last sign-in', sortable: true, accessor: (u) => u.lastSignInAt ?? '', render: (u) => (u.lastSignInAt ? <When at={u.lastSignInAt} className="text-caption" /> : <span className="text-caption text-tertiary">never</span>), width: '9rem' },
  ]

  return (
    <>
      <PageHeader
        title="Users"
        subtitle={`The people who are members of ${account.name}. Their roles come from the groups they are in.`}
        actions={
          <ActionButton decision={can('iam:create', 'Add a user')} what="Add a user" variant="primary" icon="plus" onClick={() => setAdding(true)}>
            Add user
          </ActionButton>
        }
      >
        <div className="flex flex-wrap items-center gap-3">
          <Input leading="search" size="sm" aria-label="Filter users" placeholder="Filter by name, email, id or group" value={q} onChange={(e) => setQ(e.target.value)} className="w-full sm:w-80" />
          {users.data && <span className="text-caption text-secondary">{rows.length === users.data.items.length ? plural(rows.length, 'member') : `${rows.length} of ${plural(users.data.items.length, 'member')}`}</span>}
        </div>
      </PageHeader>
      {users.error ? (
        <ErrorState error={users.error} title="Could not load the users" onRetry={() => void users.refetch()} />
      ) : (
        <Table<IamUser> caption={`Users of ${account.name}`} columns={columns} rows={rows} rowKey={(u) => u.userId} loading={users.loading} defaultSort={{ key: 'person', dir: 'asc' }} onRowClick={(u) => navigate(pathFor('iam-user', { id: u.userId }))} emptyIcon="users" emptyTitle={q ? 'Nobody matches' : 'Nobody is in this account yet'} emptyMessage={q ? 'Try another name, email or group.' : 'An admin adds people here; they sign in through the firm’s identity provider.'} />
      )}
      {adding && <AddUserDialog groups={all} onClose={() => setAdding(false)} />}
    </>
  )
}

function AddUserDialog({ groups, onClose }: { groups: IamGroup[]; onClose: () => void }) {
  const write = useIamWrite()
  const [email, setEmail] = useState('')
  const [name, setName] = useState('')
  const [picked, setPicked] = useState<string[]>(() => groups.filter((g) => g.roles?.includes('viewer') && !g.roles.includes(ADMIN_ROLE)).slice(0, 1).map((g) => g.groupId))
  const [busy, setBusy] = useState(false)
  const valid = EMAIL.test(email.trim()) && picked.length > 0

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    if (!valid || busy) return
    setBusy(true)
    const user = await write('Add a user', () => bff.iam.users.add({ email: email.trim(), name: name.trim() || undefined, groups: picked }), { title: 'User added', message: `${name.trim() || email.trim()} is now in ${plural(picked.length, 'group')} of this account.` })
    setBusy(false)
    if (user) {
      onClose()
      navigate(pathFor('iam-user', { id: user.userId }))
    }
  }

  return (
    <Modal onClose={onClose} label="Add a user" width="max-w-lg">
      <form onSubmit={(e) => void submit(e)}>
        <OverlayHeader title="Add a user" subtitle="They sign in through the firm’s identity provider; adding them here puts them in this account’s groups." icon="users" />
        <OverlayBody>
          <div className="space-y-5">
            <Field label="Email" htmlFor="add-user-email" required hint="The address the identity provider knows them by.">
              <Input id="add-user-email" type="email" required autoFocus value={email} onChange={(e) => setEmail(e.target.value)} placeholder="name@example.com" />
            </Field>
            <Field label="Name" htmlFor="add-user-name" hint="Optional. The identity provider’s name wins at their next sign-in.">
              <Input id="add-user-name" value={name} onChange={(e) => setName(e.target.value)} />
            </Field>
            <GroupPicker groups={groups} value={picked} onChange={setPicked} legend="Groups" hint="At least one. Their roles in the account come from these." />
          </div>
        </OverlayBody>
        <OverlayFooter>
          <Button type="button" variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          <Button type="submit" variant="primary" loading={busy} disabled={!valid}>
            Add user
          </Button>
        </OverlayFooter>
      </form>
    </Modal>
  )
}

/* ---------------------------------------------------------------- one user */

export function IamUserPage({ route }: PageProps) {
  const userId = route.params.id
  const { account, me, admin } = useConsole()
  const can = useAdminDecision()
  const write = useIamWrite()
  const user = useQuery(['iam', 'users', account.id, userId], () => bff.iam.users.get(userId))
  const groups = useQuery(['iam', 'groups', account.id], () => bff.iam.groups.list({ limit: 200 }))
  const self = userId === me.id
  const keys = useQuery(['iam', 'keys', account.id, userId], () => bff.iam.accessKeys.list({ userId, limit: 100 }), { enabled: admin || self })
  const [draft, setDraft] = useState<string[] | null>(null)
  const [removing, setRemoving] = useState(false)
  const [saving, setSaving] = useState(false)

  const u = user.data
  const all = useMemo(() => groups.data?.items ?? [], [groups.data])
  const current = useMemo(() => groupIdsOf(u, all), [u, all])
  const membership = draft ?? current
  const dirty = draft !== null && (draft.length !== current.length || draft.some((g) => !current.includes(g)))
  const roles = rolesFromGroups(membership, all)
  const back = { href: href(pathFor('iam-users')), label: 'Users' }

  if (user.error) {
    return (
      <>
        <PageHeader back={back} title={userId} />
        <ErrorState error={user.error} title="Could not load this user" onRetry={() => void user.refetch()} />
      </>
    )
  }
  if (!u) {
    return (
      <>
        <PageHeader back={back} title={<Skeleton className="h-7 w-48" />} />
        <SkeletonText lines={4} />
      </>
    )
  }

  const saveGroups = async () => {
    if (!draft) return
    setSaving(true)
    const next = await write('Change groups', () => bff.iam.users.update(userId, { groups: draft }, u.version), { title: 'Groups changed', message: `${personLabel(u)} now holds ${rolesFromGroups(draft, all).join(', ') || 'no role'} in ${account.name}.` })
    setSaving(false)
    if (next) setDraft(null)
  }
  const remove = async () => {
    const ok = await write('Remove a user', () => bff.iam.users.remove(userId).then(() => true), { title: 'Removed', message: `${personLabel(u)} is no longer a member of ${account.name}.` })
    if (ok) navigate(pathFor('iam-users'), { replace: true })
  }
  const removeDecision = can('iam:delete', 'Remove a user')
  const keyColumns: TableColumn<AccessKey>[] = [
    { key: 'keyId', header: 'Key id', render: (k) => <CopyableId id={k.keyId} /> },
    { key: 'description', header: 'Description', accessor: (k) => k.description ?? '', render: (k) => k.description ?? <span className="text-tertiary">—</span> },
    { key: 'status', header: 'Status', render: (k) => <KeyStatusTag status={k.status} />, width: '7rem' },
    { key: 'createdAt', header: 'Created', render: (k) => <When at={k.createdAt} className="text-caption" />, width: '8rem' },
    { key: 'lastUsedAt', header: 'Last used', render: (k) => (k.lastUsedAt ? <When at={k.lastUsedAt} className="text-caption" /> : <span className="text-caption text-tertiary">never</span>), width: '8rem' },
  ]

  return (
    <>
      <PageHeader
        back={back}
        eyebrow="User"
        title={
          <span className="flex flex-wrap items-center gap-2">
            {personLabel(u)}
            {self && (
              <DotTag size="sm" tone="accent">
                you
              </DotTag>
            )}
            <UserStatusTag status={u.status} />
          </span>
        }
        subtitle={u.email}
        actions={
          self ? (
            <Button variant="danger" icon="trash" disabled title="You cannot remove yourself; another admin can.">
              Remove from account
            </Button>
          ) : (
            <ActionButton decision={removeDecision} what="Remove a user" variant="danger" icon="trash" onClick={() => setRemoving(true)}>
              Remove from account
            </ActionButton>
          )
        }
      />
      <div className="space-y-8">
        <Section title="Identity" description="From the firm’s identity provider, kept by the console’s identity service.">
          <Facts
            columns={3}
            items={[
              { label: 'User id', value: <CopyableId id={u.userId} /> },
              { label: 'Email', value: u.email },
              { label: 'Roles here', value: <RoleTags roles={roles} link empty="none yet" /> },
              { label: 'Identity provider', value: u.issuer ? <span className="font-mono text-caption">{u.issuer}</span> : 'Company SSO' },
              { label: 'Subject', value: u.subject ? <span className="font-mono text-caption">{u.subject}</span> : <span className="text-tertiary">not recorded</span> },
              { label: 'Last sign-in', value: u.lastSignInAt ? <When at={u.lastSignInAt} /> : <span className="text-tertiary">never</span> },
              { label: 'Added', value: <When at={u.createdAt} /> },
              { label: 'Version', value: <span className="font-mono text-caption">{u.version}</span> },
            ]}
          />
        </Section>

        <Section
          title="Groups"
          description={admin ? 'Tick the groups this person belongs to in this account; their roles follow.' : 'The groups this person belongs to in this account. Only an admin changes them.'}
          actions={
            admin && dirty ? (
              <div className="flex items-center gap-2">
                <Button size="sm" variant="ghost" onClick={() => setDraft(null)} disabled={saving}>
                  Discard
                </Button>
                <Button size="sm" variant="primary" onClick={() => void saveGroups()} loading={saving}>
                  Save groups
                </Button>
              </div>
            ) : undefined
          }
        >
          {groups.error ? (
            <ErrorState error={groups.error} compact title="Could not load the groups" onRetry={() => void groups.refetch()} />
          ) : admin ? (
            <GroupPicker groups={all} value={membership} onChange={setDraft} legend="Member of" hint={self ? 'Careful: leaving the admin group takes your own admin role away.' : undefined} />
          ) : (
            <GroupLinks groups={u.groups} all={all} />
          )}
        </Section>

        {(admin || self) && (
          <Section
            title="Access keys"
            description={self ? 'Your keys for the platform’s API and MCP endpoints, scoped to this account.' : 'This person’s keys for the platform’s API and MCP endpoints in this account.'}
            actions={
              <Link to={pathFor('iam-keys', {}, admin && !self ? { user: userId } : undefined)} className="text-caption font-medium text-accent hover:underline">
                Manage access keys
              </Link>
            }
          >
            {keys.error ? <ErrorState error={keys.error} compact title="Could not load the access keys" onRetry={() => void keys.refetch()} /> : <Table<AccessKey> caption={`Access keys of ${personLabel(u)}`} columns={keyColumns} rows={keys.data?.items ?? []} rowKey={(k) => k.keyId} loading={keys.loading} dense empty={<EmptyState compact icon="key" title="No access keys" message="None were created for this account." />} />}
          </Section>
        )}
      </div>
      {removing && (
        <ConfirmDialog title={`Remove ${personLabel(u)} from ${account.name}?`} tone="danger" icon="trash" confirmLabel="Remove" onClose={() => setRemoving(false)} onConfirm={remove} message="They leave every group of this account and lose its roles. Their access keys for this account stop working. They keep their other accounts." />
      )}
    </>
  )
}
