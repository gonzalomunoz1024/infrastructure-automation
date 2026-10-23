import { useMemo, useState, type FormEvent } from 'react'
import { Button, Card, ConfirmDialog, CopyButton, Field, Icon, IconButton, Input, Modal, OverlayBody, OverlayFooter, OverlayHeader, PageHeader, ProgressBar, Select, Table, plural, type ProgressTone, type TableColumn } from '@iaf/ui'
import { CopyableId, ErrorState, When } from '@iaf/console-kit'
import { useConsole } from '../../app/console'
import { Link, pathFor, type PageProps } from '../../app/router'
import { bff } from '../../lib/bff'
import { useQuery } from '../../lib/queries'
import type { AccessKey, AccessKeyCreated, IamUser, Quota } from '../../lib/types'
import { KeyStatusTag, personLabel, useIamWrite } from './common'

/*
 * Access keys: what a script or an MCP client presents to the platform's API on behalf of a
 * person, scoped to one account. Everyone manages their own keys; admins see and manage
 * everyone's. The secret part is shown once, when the key is created or rotated, and never kept
 * in clear.
 */

const EXPIRY = [
  { value: '30', label: 'In 30 days' },
  { value: '90', label: 'In 90 days' },
  { value: '365', label: 'In a year' },
  { value: 'never', label: 'Never' },
]

function expiresAtOf(choice: string, now = Date.now()): string | undefined {
  if (choice === 'never') return undefined
  return new Date(now + Number(choice) * 86_400_000).toISOString()
}

function quotaLabel(key: string): string {
  return key.replace(/[-_]/g, ' ').replace(/^./, (c) => c.toUpperCase())
}

function quotaTone(q: Quota): ProgressTone {
  if (q.limit <= 0) return 'accent'
  const ratio = q.used / q.limit
  return ratio >= 1 ? 'critical' : ratio >= 0.8 ? 'warning' : 'accent'
}

export default function IamAccessKeysPage({ route }: PageProps) {
  const { account, me, admin } = useConsole()
  const write = useIamWrite()
  const filterUser = admin ? route.query.user || '' : ''
  const keys = useQuery(['iam', 'keys', account.id, filterUser || 'all'], () => bff.iam.accessKeys.list({ limit: 200, userId: filterUser || undefined }))
  const quotas = useQuery(['iam', 'quotas', account.id], () => bff.iam.quotas.list())
  const users = useQuery(['iam', 'users', account.id], () => bff.iam.users.list({ limit: 200 }), { enabled: admin })
  const [creating, setCreating] = useState(false)
  const [shown, setShown] = useState<{ key: AccessKeyCreated; rotated: boolean } | null>(null)
  const [confirm, setConfirm] = useState<{ kind: 'revoke' | 'rotate'; key: AccessKey } | null>(null)
  const rows = useMemo(() => keys.data?.items ?? [], [keys.data])
  const people = useMemo(() => users.data?.items ?? [], [users.data])
  const nameOf = (userId: string) => personLabel(people.find((u) => u.userId === userId) ?? { userId })
  const active = rows.filter((k) => k.status === 'active' || k.status === 'rotating').length
  const mayManage = (k: AccessKey) => admin || k.userId === me.id

  const rotate = async (k: AccessKey) => {
    const next = await write('Rotate an access key', () => bff.iam.accessKeys.rotate(k.keyId))
    if (next) setShown({ key: next, rotated: true })
  }
  const revoke = async (k: AccessKey) => {
    await write('Revoke an access key', () => bff.iam.accessKeys.revoke(k.keyId), { title: 'Key revoked', message: `${k.keyId} stops working now.` })
  }

  const columns: TableColumn<AccessKey>[] = [
    { key: 'keyId', header: 'Key id', sortable: true, accessor: (k) => k.keyId, render: (k) => <CopyableId id={k.keyId} /> },
    ...(admin
      ? [
          {
            key: 'owner',
            header: 'Owner',
            sortable: true,
            accessor: (k: AccessKey) => nameOf(k.userId),
            render: (k: AccessKey) => (
              <Link to={pathFor('iam-user', { id: k.userId })} className="text-sm text-primary hover:underline">
                {nameOf(k.userId)}
                {k.userId === me.id ? ' (you)' : ''}
              </Link>
            ),
          },
        ]
      : []),
    { key: 'description', header: 'Description', accessor: (k) => k.description ?? '', render: (k) => k.description ?? <span className="text-tertiary">—</span> },
    { key: 'status', header: 'Status', sortable: true, accessor: (k) => k.status, render: (k) => <KeyStatusTag status={k.status} />, width: '7rem' },
    { key: 'createdAt', header: 'Created', sortable: true, accessor: (k) => k.createdAt, render: (k) => <When at={k.createdAt} className="text-caption" />, width: '8rem' },
    { key: 'expiresAt', header: 'Expires', sortable: true, accessor: (k) => k.expiresAt ?? '', render: (k) => (k.expiresAt ? <When at={k.expiresAt} className="text-caption" /> : <span className="text-caption text-tertiary">never</span>), width: '8rem' },
    { key: 'lastUsedAt', header: 'Last used', sortable: true, accessor: (k) => k.lastUsedAt ?? '', render: (k) => (k.lastUsedAt ? <When at={k.lastUsedAt} className="text-caption" /> : <span className="text-caption text-tertiary">never</span>), width: '8rem' },
    {
      key: 'actions',
      header: <span className="sr-only">Actions</span>,
      width: '5.5rem',
      align: 'right',
      render: (k) =>
        mayManage(k) && (k.status === 'active' || k.status === 'rotating') ? (
          <span className="flex justify-end gap-0.5">
            <IconButton label={`Rotate ${k.keyId}`} size="sm" onClick={() => setConfirm({ kind: 'rotate', key: k })}>
              <Icon name="refresh" size={14} />
            </IconButton>
            <IconButton label={`Revoke ${k.keyId}`} size="sm" onClick={() => setConfirm({ kind: 'revoke', key: k })}>
              <Icon name="trash" size={14} />
            </IconButton>
          </span>
        ) : null,
    },
  ]

  return (
    <>
      <PageHeader
        title="Access keys"
        subtitle={admin ? `Every key that acts on ${account.name} through the platform’s API and MCP endpoints.` : `Your keys for the platform’s API and MCP endpoints, scoped to ${account.name}.`}
        actions={
          <Button variant="primary" icon="plus" onClick={() => setCreating(true)}>
            Create access key
          </Button>
        }
      >
        {admin && (
          <div className="flex flex-wrap items-center gap-3">
            <Select size="sm" ariaLabel={`Owner: ${filterUser ? nameOf(filterUser) : 'Everyone'}`} value={filterUser} onChange={(v) => (window.location.hash = pathFor('iam-keys', {}, v ? { user: v } : undefined))} options={[{ value: '', label: 'Everyone' }, ...people.map((u) => ({ value: u.userId, label: personLabel(u), hint: u.userId }))]} />
            {keys.data && <span className="text-caption text-secondary">{plural(active, 'active key')} of {rows.length}</span>}
          </div>
        )}
      </PageHeader>
      <div className="grid grid-cols-1 gap-6 lg:grid-cols-3">
        <div className="lg:col-span-2">{keys.error ? <ErrorState error={keys.error} title="Could not load the access keys" onRetry={() => void keys.refetch()} /> : <Table<AccessKey> caption={`Access keys of ${account.name}`} columns={columns} rows={rows} rowKey={(k) => k.keyId} loading={keys.loading} defaultSort={{ key: 'createdAt', dir: 'desc' }} emptyIcon="key" emptyTitle="No access keys yet" emptyMessage="Create one to call the platform’s API or connect an MCP client. The secret is shown once." />}</div>
        <Card title="Quotas" subtitle="What this account may hold and how much is used" className="h-fit">
          {quotas.error ? (
            <ErrorState error={quotas.error} compact title="Could not load the quotas" onRetry={() => void quotas.refetch()} />
          ) : (
            <ul className="space-y-4">
              {(quotas.data?.items ?? []).map((q) => (
                <li key={q.key}>
                  <ProgressBar label={`${quotaLabel(q.key)}: ${q.used} of ${q.limit}${q.burst ? ` (burst ${q.burst})` : ''}`} showLabel value={q.used} max={q.limit} tone={quotaTone(q)} size="sm" />
                  <p className="mt-1 text-caption2 text-tertiary">{q.source === 'platform' ? 'Set by the platform for the account' : q.source === 'override' ? 'Raised for this account' : 'The default for every account'}</p>
                </li>
              ))}
              {quotas.data && quotas.data.items.length === 0 && <li className="text-caption text-tertiary">No quotas apply.</li>}
            </ul>
          )}
        </Card>
      </div>
      {creating && (
        <CreateKeyDialog
          people={admin ? people : []}
          self={me.id}
          onClose={() => setCreating(false)}
          onCreated={(key) => {
            setCreating(false)
            setShown({ key, rotated: false })
          }}
        />
      )}
      {shown && <SecretDialog created={shown.key} rotated={shown.rotated} onClose={() => setShown(null)} />}
      {confirm?.kind === 'revoke' && <ConfirmDialog title={`Revoke ${confirm.key.keyId}?`} tone="danger" icon="trash" confirmLabel="Revoke" onClose={() => setConfirm(null)} onConfirm={() => revoke(confirm.key)} message="Anything using this key is refused from now on. This cannot be undone; create a new key instead if it is still needed." />}
      {confirm?.kind === 'rotate' && <ConfirmDialog title={`Rotate ${confirm.key.keyId}?`} tone="warning" icon="refresh" confirmLabel="Rotate" onClose={() => setConfirm(null)} onConfirm={() => rotate(confirm.key)} message="A new secret is issued for the same key id. The old secret keeps working for a short grace period so you can switch clients over." />}
    </>
  )
}

function CreateKeyDialog({ people, self, onClose, onCreated }: { people: IamUser[]; self: string; onClose: () => void; onCreated: (key: AccessKeyCreated) => void }) {
  const write = useIamWrite()
  const [description, setDescription] = useState('')
  const [expiry, setExpiry] = useState('90')
  const [owner, setOwner] = useState(self)
  const [busy, setBusy] = useState(false)
  const ownerLabel = (userId: string) => {
    const u = people.find((p) => p.userId === userId)
    const label = personLabel(u ?? { userId })
    return userId === self ? `${label} (you)` : label
  }

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    if (busy) return
    setBusy(true)
    const key = await write('Create an access key', () => bff.iam.accessKeys.create({ userId: owner !== self ? owner : undefined, description: description.trim() || undefined, expiresAt: expiresAtOf(expiry) }))
    setBusy(false)
    if (key) onCreated(key)
  }

  return (
    <Modal onClose={onClose} label="Create an access key" width="max-w-lg">
      <form onSubmit={(e) => void submit(e)}>
        <OverlayHeader title="Create an access key" subtitle="For a script or an MCP client that acts as a person in this account." icon="key" />
        <OverlayBody>
          <div className="space-y-5">
            <Field label="Description" htmlFor="key-description" hint="What uses it, so it can be found and revoked later.">
              <Input id="key-description" autoFocus value={description} onChange={(e) => setDescription(e.target.value)} placeholder="CI pipeline for the storage team" />
            </Field>
            <Field label="Expires" hint="Shorter is safer; a key can be rotated before it expires.">
              <Select ariaLabel={`Expires: ${EXPIRY.find((e) => e.value === expiry)?.label ?? expiry}`} value={expiry} onChange={setExpiry} options={EXPIRY} />
            </Field>
            {people.length > 0 && (
              <Field label="Owner" hint="The person the key acts as. Only admins create keys for others.">
                <Select ariaLabel={`Owner: ${ownerLabel(owner)}`} value={owner} onChange={setOwner} options={people.map((u) => ({ value: u.userId, label: ownerLabel(u.userId), hint: u.userId }))} />
              </Field>
            )}
          </div>
        </OverlayBody>
        <OverlayFooter>
          <Button type="button" variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          <Button type="submit" variant="primary" loading={busy}>
            Create key
          </Button>
        </OverlayFooter>
      </form>
    </Modal>
  )
}

function SecretDialog({ created, rotated, onClose }: { created: AccessKeyCreated; rotated: boolean; onClose: () => void }) {
  const full = `${created.keyId}.${created.secretPart}`
  return (
    <Modal onClose={onClose} label={rotated ? 'Key rotated' : 'Key created'} width="max-w-lg">
      <OverlayHeader title={rotated ? 'Key rotated' : 'Key created'} subtitle="Copy the secret now. The console cannot show it again." icon="key" />
      <OverlayBody>
        <div className="space-y-4" data-secret-shown={created.keyId}>
          <div>
            <p className="text-caption font-medium text-secondary">Key id</p>
            <p className="mt-1 font-mono text-sm text-primary">{created.keyId}</p>
          </div>
          <div>
            <p className="text-caption font-medium text-secondary">Secret part</p>
            <div className="mt-1 flex items-center gap-2 rounded-md border border-stroke-light bg-surface-secondary px-3 py-2">
              <code className="min-w-0 flex-1 break-all font-mono text-sm text-primary">{created.secretPart}</code>
              <CopyButton text={created.secretPart} label="Copy secret" />
            </div>
          </div>
          <div>
            <p className="text-caption font-medium text-secondary">As one token</p>
            <div className="mt-1 flex items-center gap-2 rounded-md border border-stroke-light bg-surface-secondary px-3 py-2">
              <code className="min-w-0 flex-1 break-all font-mono text-caption text-primary">{full}</code>
              <CopyButton text={full} label="Copy token" />
            </div>
          </div>
          <p className="text-caption text-secondary">
            Send it as <code className="font-mono">Authorization: Bearer {'<token>'}</code> to the platform’s API, or paste it into your MCP client.
            {created.expiresAt && (
              <>
                {' '}
                It expires <When at={created.expiresAt} />.
              </>
            )}
            {rotated && created.graceUntil && (
              <>
                {' '}
                The previous secret keeps working until <When at={created.graceUntil} />.
              </>
            )}
          </p>
        </div>
      </OverlayBody>
      <OverlayFooter>
        <Button variant="primary" onClick={onClose}>
          I have copied it
        </Button>
      </OverlayFooter>
    </Modal>
  )
}
