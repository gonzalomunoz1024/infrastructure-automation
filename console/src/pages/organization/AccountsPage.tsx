import { useMemo, useState } from 'react'
import { Button, Card, Checkbox, EmptyState, Field, Icon, Input, Modal, OverlayBody, OverlayFooter, OverlayHeader, PageHeader, Select, SkeletonText, Textarea, plural } from '@iaf/ui'
import { ErrorState, StatusBadge, When } from '@iaf/console-kit'
import { useConsole } from '../../app/console'
import { clockOf } from '../../app/elevation'
import type { PageProps } from '../../app/router'
import { formatAccountId } from '../../lib/accounts'
import { isApiError } from '../../lib/api'
import { bff } from '../../lib/bff'
import { elevationsApi, minutesWords, type Elevation } from '../../lib/elevations'
import { CLOSE_EFFECTS, organizationApi, resourcesRemainOf, type OrganizationAccount } from '../../lib/organization'
import { invalidate, useQuery } from '../../lib/queries'
import { ADMIN_ROLE, MANAGED_ROLES, roleLabel } from '../../lib/session'

/*
 * The organization's accounts (WP 7.7, hub decision 34), for its admins: every account, closed
 * ones included, with the two doors that work across accounts. Emergency access (break-glass)
 * gives a person a role in an account now, for at most four hours, approved by the admin giving
 * it, with a ticket and a reason the account's own trail keeps; the dialog says so loudly and
 * shows the line the trail records. Closing an account is for good: typed confirmation, what it
 * ends, and a refusal that names what the account still holds, since its resources stay where
 * they are and must be deleted or moved first.
 */

const GLASS_DURATIONS = [15, 30, 60, 120, 240]

function AccountStatus({ status }: { status: OrganizationAccount['status'] }) {
  return (
    <StatusBadge
      status={status === 'active' ? 'succeeded' : status === 'closed' ? 'cancelled' : 'waiting'}
      label={status === 'active' ? 'Active' : status === 'closed' ? 'Closed' : 'Suspended'}
      size="sm"
    />
  )
}

export default function OrganizationAccountsPage(_props: PageProps) {
  const { session } = useConsole()
  const accounts = useQuery(['organization', 'accounts'], () => organizationApi.accounts({ limit: 200 }), { enabled: !!session.organizationAdmin })
  const [glass, setGlass] = useState<OrganizationAccount | null>(null)
  const [closing, setClosing] = useState<OrganizationAccount | null>(null)

  if (!session.organizationAdmin || (isApiError(accounts.error) && accounts.error.status === 404)) {
    return (
      <>
        <PageHeader title="Accounts" subtitle="Every account of the organization." />
        <EmptyState
          icon="lock"
          title="For the organization’s admins"
          message="This page lists every account and opens emergency access and closing. Your sign-in groups do not make you an organization admin; the platform team can say who is."
        />
      </>
    )
  }

  return (
    <>
      <PageHeader title="Accounts" subtitle="Every account of the organization, closed ones included. Emergency access and closing work from here, and each asks you to sign in again first." />
      {accounts.error ? (
        <ErrorState error={accounts.error} title="Could not load the accounts" onRetry={() => void accounts.refetch()} />
      ) : !accounts.data ? (
        <SkeletonText lines={4} />
      ) : (
        <ul className="space-y-4" aria-label="Accounts of the organization">
          {accounts.data.items.map((a) => (
            <li key={a.accountId}>
              <Card data-account={a.accountId}>
                <div className="flex flex-wrap items-start justify-between gap-3">
                  <div className="min-w-0">
                    <p className="flex flex-wrap items-center gap-2">
                      <span className="text-headline font-semibold text-primary">{a.name}</span>
                      <AccountStatus status={a.status} />
                    </p>
                    <p className="mt-0.5 text-caption text-secondary">
                      <span className="font-mono">{formatAccountId(a.accountId)}</span> · owned by {a.owningTeam} · created <When at={a.createdAt} />
                    </p>
                    {a.status === 'closed' && (
                      <p className="mt-1 text-caption text-secondary">
                        Closed <When at={a.closedAt} />
                        {a.closeReason ? `: ${a.closeReason}` : ''}
                      </p>
                    )}
                  </div>
                  {a.status === 'active' && (
                    <div className="flex flex-wrap gap-2">
                      <Button icon="alert-triangle" onClick={() => setGlass(a)}>
                        Give emergency access
                      </Button>
                      <Button icon="trash" variant="danger" onClick={() => setClosing(a)}>
                        Close account
                      </Button>
                    </div>
                  )}
                </div>
              </Card>
            </li>
          ))}
        </ul>
      )}
      {glass && <BreakGlassDialog account={glass} onClose={() => setGlass(null)} />}
      {closing && <CloseAccountDialog account={closing} onClose={() => setClosing(null)} />}
    </>
  )
}

/* ---------------------------------------------------------------- break-glass */

function BreakGlassDialog({ account, onClose }: { account: OrganizationAccount; onClose: () => void }) {
  const { me, account: here } = useConsole()
  const people = useQuery(['iam', 'users', here.id], () => bff.iam.users.list({ limit: 200 }))
  const [person, setPerson] = useState('')
  const [role, setRole] = useState<string>(ADMIN_ROLE)
  const [minutes, setMinutes] = useState('60')
  const [ticket, setTicket] = useState('')
  const [reason, setReason] = useState('')
  const [understood, setUnderstood] = useState(false)
  const [busy, setBusy] = useState(false)
  const [failure, setFailure] = useState<string | null>(null)
  const [given, setGiven] = useState<Elevation | null>(null)
  const known = useMemo(() => (people.data?.items ?? []).filter((u) => u.userId !== me.id), [people.data, me.id])
  const match = known.find((u) => u.userId === person.trim() || u.email.toLowerCase() === person.trim().toLowerCase())
  const ready = !!person.trim() && !!reason.trim() && !!ticket.trim() && understood

  const give = async () => {
    if (!ready) return
    setBusy(true)
    setFailure(null)
    try {
      const e = await elevationsApi.breakGlass(account.accountId, { userId: match?.userId ?? person.trim(), role, durationMinutes: Number(minutes), ticket: ticket.trim(), reason: reason.trim() })
      setGiven(e)
      invalidate('iam')
    } catch (err) {
      setFailure(isApiError(err) ? err.error : String(err))
    } finally {
      setBusy(false)
    }
  }

  return (
    <Modal onClose={onClose} width="max-w-lg" label={`Emergency access to ${account.name}`}>
      <form
        onSubmit={(e) => {
          e.preventDefault()
          void give()
        }}
      >
        <OverlayHeader title={`Emergency access to ${account.name}`} subtitle="Break-glass: for support or an incident only" icon="alert-triangle" />
        <OverlayBody className="space-y-4">
          <div role="note" className="flex gap-3 rounded-lg border-2 border-critical bg-critical-tint p-3 text-sm text-primary" data-break-glass-warning>
            <Icon name="alert-triangle" size={18} className="mt-0.5 shrink-0 text-critical" />
            <div>
              <p className="font-semibold">This bypasses {account.name}’s own approvers.</p>
              <p className="mt-1">
                The person holds the role now, for at most {minutesWords(240)}, whether or not they belong to the account. You are recorded as the approver, with the ticket and the reason, on{' '}
                {account.name}’s own trail, where its admins and auditors read it.
              </p>
            </div>
          </div>
          {given ? (
            <div role="status" className="rounded-lg border border-stroke bg-surface-secondary p-3 text-sm" data-audit-line>
              <p className="font-medium text-primary">Recorded on {account.name}’s trail</p>
              <p className="mt-1 text-primary">
                {me.name} gave {given.user.name ?? given.user.userId} {roleLabel(given.role)} by emergency access from {clockOf(given.startsAt ?? given.requestedAt)} to {clockOf(given.until!)}, ticket{' '}
                {given.ticket}: “{given.reason}”.
              </p>
            </div>
          ) : (
            <>
              <Field
                label="Person"
                htmlFor="glass-person"
                hint={match ? `${match.name ?? match.email}, a member of ${here.name}` : 'Their email, or their console user id when they belong to no account you are in.'}
                required
              >
                <Input id="glass-person" list="glass-people" value={person} onChange={(e) => setPerson(e.target.value)} autoComplete="off" data-autofocus />
              </Field>
              <datalist id="glass-people">
                {known.map((u) => (
                  <option key={u.userId} value={u.email}>
                    {u.name}
                  </option>
                ))}
              </datalist>
              <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
                <Field label="Role" htmlFor="glass-role">
                  <Select ariaLabel={`Role: ${roleLabel(role)}`} value={role} onChange={setRole} options={MANAGED_ROLES.map((r) => ({ value: r, label: roleLabel(r) }))} />
                </Field>
                <Field label="For" htmlFor="glass-minutes">
                  <Select
                    ariaLabel={`For: ${minutesWords(Number(minutes))}`}
                    value={minutes}
                    onChange={setMinutes}
                    options={GLASS_DURATIONS.map((d) => ({ value: String(d), label: minutesWords(d) }))}
                  />
                </Field>
              </div>
              <Field label="Incident or support ticket" htmlFor="glass-ticket" required>
                <Input id="glass-ticket" value={ticket} maxLength={200} onChange={(e) => setTicket(e.target.value)} placeholder="INC-4521" />
              </Field>
              <Field label="Reason" htmlFor="glass-reason" hint="On the trail as you write it." required>
                <Textarea id="glass-reason" rows={2} maxLength={2000} value={reason} onChange={(e) => setReason(e.target.value)} />
              </Field>
              <Checkbox id="glass-understood" checked={understood} onChange={setUnderstood} label="I approve this emergency access, and I am not the person receiving it" />
              {failure && (
                <p role="alert" className="text-sm text-critical">
                  {failure}
                </p>
              )}
            </>
          )}
        </OverlayBody>
        <OverlayFooter>
          <Button type="button" onClick={onClose}>
            {given ? 'Done' : 'Cancel'}
          </Button>
          {!given && (
            <Button type="submit" variant="danger" icon="alert-triangle" loading={busy} disabled={busy || !ready}>
              Give emergency access
            </Button>
          )}
        </OverlayFooter>
      </form>
    </Modal>
  )
}

/* ---------------------------------------------------------------- closing */

function CloseAccountDialog({ account, onClose }: { account: OrganizationAccount; onClose: () => void }) {
  const { types } = useConsole()
  const [reason, setReason] = useState('')
  const [typed, setTyped] = useState('')
  const [busy, setBusy] = useState(false)
  const [failure, setFailure] = useState<string | null>(null)
  const [remain, setRemain] = useState<{ total: number; byType: Record<string, number> } | null>(null)
  const [closed, setClosed] = useState(false)
  const ready = typed === account.name && !!reason.trim()
  const titleOf = (type: string) => types.find((t) => t.name === type)?.title ?? type

  const close = async () => {
    if (!ready) return
    setBusy(true)
    setFailure(null)
    setRemain(null)
    try {
      await organizationApi.close(account.accountId, { reason: reason.trim() })
      setClosed(true)
      invalidate('organization')
      invalidate('accounts')
    } catch (e) {
      const left = resourcesRemainOf(e)
      if (left) setRemain(left)
      else setFailure(isApiError(e) ? e.error : String(e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <Modal onClose={onClose} width="max-w-lg" label={`Close ${account.name}`}>
      <form
        onSubmit={(e) => {
          e.preventDefault()
          void close()
        }}
      >
        <OverlayHeader title={`Close ${account.name}`} subtitle={`Account ${formatAccountId(account.accountId)}, for good`} icon="trash" />
        <OverlayBody className="space-y-4">
          {closed ? (
            <p role="status" className="text-sm text-primary">
              {account.name} is closed. Nobody can sign in to it any more.
            </p>
          ) : (
            <>
              <div>
                <p className="text-sm font-medium text-primary">What closing does</p>
                <ul className="mt-1 list-disc space-y-1 pl-5 text-sm text-secondary">
                  {CLOSE_EFFECTS.map((line) => (
                    <li key={line}>{line}</li>
                  ))}
                </ul>
              </div>
              {remain && (
                <div role="alert" className="rounded-lg border border-critical/50 bg-critical-tint p-3 text-sm text-primary" data-close-refused>
                  <p className="font-semibold">
                    {account.name} still holds {plural(remain.total, 'resource')}, so it cannot be closed.
                  </p>
                  <ul className="mt-1 list-disc pl-5">
                    {Object.entries(remain.byType)
                      .filter(([, n]) => n > 0)
                      .sort((a, b) => b[1] - a[1])
                      .map(([type, n]) => (
                        <li key={type}>
                          {n} {titleOf(type).toLowerCase()}
                        </li>
                      ))}
                  </ul>
                  <p className="mt-1">Delete them, or move them to another account, then close it.</p>
                </div>
              )}
              <Field label="Reason" htmlFor="close-reason" hint="Kept with the closed account." required>
                <Textarea id="close-reason" rows={2} maxLength={2000} value={reason} onChange={(e) => setReason(e.target.value)} data-autofocus />
              </Field>
              <Field label={`Type ${account.name} to confirm`} htmlFor="close-confirm" required>
                <Input id="close-confirm" mono value={typed} onChange={(e) => setTyped(e.target.value)} autoComplete="off" spellCheck={false} />
              </Field>
              {failure && (
                <p role="alert" className="text-sm text-critical">
                  {failure}
                </p>
              )}
            </>
          )}
        </OverlayBody>
        <OverlayFooter>
          <Button type="button" onClick={onClose}>
            {closed ? 'Done' : 'Cancel'}
          </Button>
          {!closed && (
            <Button type="submit" variant="danger" icon="trash" loading={busy} disabled={busy || !ready}>
              Close the account
            </Button>
          )}
        </OverlayFooter>
      </form>
    </Modal>
  )
}
