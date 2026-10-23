import { useState } from 'react'
import { Card, Icon, IconButton, PageHeader, Table, plural, useToast, type TableColumn } from '@iaf/ui'
import { ConfirmDialog, ErrorState, When } from '@iaf/console-kit'
import { useConsole } from '../../app/console'
import { Link, pathFor, type PageProps } from '../../app/router'
import { cli, clientShort, clientTitle, machineOf } from '../../lib/cli'
import { explain } from '../../lib/plain'
import { invalidate, useQuery } from '../../lib/queries'
import type { ApiSession } from '../../lib/types'

/*
 * CLI sign-ins (WP 7.4), beside the access keys: every CLI and SDK the person signed in through
 * `#/device`, newest first, the open ones only (`GET /console/v1/api-sessions`). Each is the
 * person's own; signing one out (`DELETE`) asks once, then its refresh token stops at once and its
 * access token within 15 minutes. Everyone manages their own, whatever their role, so nothing here
 * is greyed by role.
 */

/** A link drawn as a secondary button. */
export const LINK_BUTTON = 'inline-flex select-none items-center justify-center gap-1.5 whitespace-nowrap rounded-md bg-surface-secondary px-3 py-1.5 text-sm font-medium text-primary transition-all duration-150 hover:bg-stroke-light active:scale-[0.97]'

export default function CliSessionsPage(_props: PageProps) {
  const { me, accounts } = useSessionsContext()
  const toast = useToast()
  const sessions = useQuery(['api-sessions', me], () => cli.apiSessions.list({ limit: 200 }))
  const [confirm, setConfirm] = useState<ApiSession | null>(null)
  const rows = sessions.data?.items ?? []

  const revoke = async (s: ApiSession) => {
    try {
      await cli.apiSessions.revoke(s.id)
      invalidate('api-sessions')
      toast.success('Signed out', `${clientTitle(s.clientId)} on ${machineOf(s.userAgent)} is signed out. Its last access token stops within 15 minutes.`)
    } catch (e) {
      const x = explain(e, 'Sign the CLI out')
      toast.error(x.title, [x.message, x.next].filter(Boolean).join(' '))
      throw e
    }
  }

  const columns: TableColumn<ApiSession>[] = [
    { key: 'client', header: 'Client', sortable: true, accessor: (s) => clientShort(s.clientId), render: (s) => <span className="text-sm text-primary">{clientTitle(s.clientId)}</span> },
    { key: 'machine', header: 'Machine', accessor: (s) => machineOf(s.userAgent), render: (s) => <span className="text-sm text-primary">{machineOf(s.userAgent)}</span> },
    { key: 'ip', header: 'Address', accessor: (s) => s.ip ?? '', render: (s) => <span className="font-mono text-caption">{s.ip ?? '-'}</span>, width: '8rem' },
    { key: 'account', header: 'Account', accessor: (s) => accounts(s.accountId), render: (s) => <span className="text-caption">{accounts(s.accountId)}</span>, width: '8rem' },
    { key: 'createdAt', header: 'Signed in', sortable: true, accessor: (s) => s.createdAt, render: (s) => <When at={s.createdAt} className="text-caption" />, width: '8rem' },
    { key: 'lastUsedAt', header: 'Last used', sortable: true, accessor: (s) => s.lastUsedAt ?? '', render: (s) => (s.lastUsedAt ? <When at={s.lastUsedAt} className="text-caption" /> : <span className="text-caption text-tertiary">never</span>), width: '8rem' },
    { key: 'expiresAt', header: 'Ends', sortable: true, accessor: (s) => s.expiresAt, render: (s) => <When at={s.expiresAt} className="text-caption" />, width: '8rem' },
    {
      key: 'actions',
      header: <span className="sr-only">Actions</span>,
      width: '3.5rem',
      align: 'right',
      render: (s) => (
        <IconButton label={`Sign out ${clientTitle(s.clientId).toLowerCase()} on ${machineOf(s.userAgent)}, signed in ${new Date(s.createdAt).toLocaleString()}`} size="sm" onClick={() => setConfirm(s)}>
          <Icon name="trash" size={14} />
        </IconButton>
      ),
    },
  ]

  return (
    <>
      <PageHeader
        title="CLI sign-ins"
        subtitle="The console CLI and SDKs you signed in from a terminal. Each acts as you, with your access, for up to 12 hours."
        actions={
          <Link to={pathFor('device')} className={LINK_BUTTON}>
            <Icon name="terminal" size={14} />
            Approve a code
          </Link>
        }
      />
      <div className="grid grid-cols-1 gap-6 lg:grid-cols-3">
        <div className="min-w-0 lg:col-span-2">
          {sessions.error ? (
            <ErrorState error={sessions.error} title="Could not load your CLI sign-ins" onRetry={() => void sessions.refetch()} />
          ) : (
            <Table<ApiSession> caption="Your CLI and SDK sign-ins" columns={columns} rows={rows} rowKey={(s) => s.id} loading={sessions.loading} defaultSort={{ key: 'createdAt', dir: 'desc' }} emptyIcon="terminal" emptyTitle="No CLI signed in" emptyMessage="Run iaf login in a terminal and approve the code it shows." />
          )}
          {sessions.data && <p className="mt-2 text-caption text-secondary" data-api-session-count={rows.length}>{plural(rows.length, 'open sign-in')}</p>}
        </div>
        <Card title="How the CLI signs in" className="h-fit">
          <ol className="list-decimal space-y-2 pl-5 text-caption text-secondary">
            <li>
              Run <code className="font-mono text-primary">iaf login</code> in a terminal. It prints a code and this console’s address.
            </li>
            <li>Open the address, check the code and the machine, and approve. The console asks you to sign in again first.</li>
            <li>The CLI is signed in as you. Sign it out here when you are done, or from the terminal with <code className="font-mono text-primary">iaf logout</code>.</li>
          </ol>
          <p className="mt-3 text-caption text-secondary">
            For a script or a pipeline that runs without you, use an{' '}
            <Link to={pathFor('iam-keys')} className="text-accent underline underline-offset-2">
              access key
            </Link>{' '}
            instead.
          </p>
        </Card>
      </div>
      {confirm && (
        <ConfirmDialog
          title={`Sign out ${clientTitle(confirm.clientId).toLowerCase()}?`}
          tone="danger"
          icon="trash"
          confirmLabel="Sign out"
          onClose={() => setConfirm(null)}
          onConfirm={() => revoke(confirm)}
          message={`${machineOf(confirm.userAgent)}${confirm.ip ? ` at ${confirm.ip}` : ''} can no longer act as you. Its next call is refused within 15 minutes; sign in again from that terminal to use it.`}
        />
      )}
    </>
  )
}

function useSessionsContext() {
  const { me, account } = useConsole()
  return { me: me.id, accounts: (id?: string) => (!id ? '-' : id === account.id ? account.name : id) }
}
