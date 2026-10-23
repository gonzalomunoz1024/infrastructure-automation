import { useMemo, useState } from 'react'
import { Button, Card, EmptyState, Field, Icon, PageHeader, Select, SkeletonText, Tabs, Textarea, cx } from '@iaf/ui'
import { ErrorState, StatusBadge, When, useNow } from '@iaf/console-kit'
import { useConsole } from '../../app/console'
import { clockOf, openElevationOf, refreshRoles, ELEVATION_RULES_KEY } from '../../app/elevation'
import { ActionButton } from '../../app/Refusal'
import type { PageProps } from '../../app/router'
import { isApiError } from '../../lib/api'
import { ELEVATION_STATUS_WORDS, approvedByWords, elevationsApi, groupRolesOf, isoDurationMs, minutesWords, ruleFor, servedStatus, timeLeftWords, type Elevation } from '../../lib/elevations'
import type { Decision } from '../../lib/permissions'
import { invalidate, useQuery } from '../../lib/queries'
import { ADMIN_ROLE, MANAGED_ROLES, ROLE_SUMMARY, roleLabel } from '../../lib/session'
import { RoleTags } from './common'
import { usePeopleNames } from './accessTime'

/*
 * Elevations (WP 9.12) and break-glass access (WP 7.7): a role of the account held for a while.
 * Anyone but an auditor asks for a role they do not hold through a group, for 5 minutes to 8
 * hours, with a reason; the page says before the ask whether a rule approves it at once or an
 * admin decides. Held roles show how long is left and end by hand; an admin decides the asks that
 * name them (never their own); admins and auditors read the account's, break-glass access first
 * and loud, since it is the emergency door and the account's trail keeps it.
 */

type Tab = 'mine' | 'decide' | 'account'
const DURATIONS = [15, 30, 60, 120, 240, 480]
const READING_ROLES: readonly string[] = [ADMIN_ROLE, 'auditor']

const POLL_MS = 15_000

export default function IamElevationsPage({ route }: PageProps) {
  const { account, me, session } = useConsole()
  const roles = session.account.roles ?? []
  const reads = roles.some((r) => READING_ROLES.includes(r))
  const decides = roles.includes(ADMIN_ROLE)
  const [picked, setPicked] = useState<Tab | null>(null)
  const list = useQuery(['iam', 'elevations', account.id, reads ? 'account' : 'mine'], () => elevationsApi.list({ limit: 200 }), { refetchInterval: POLL_MS })
  const nameOf = usePeopleNames()
  const now = useNow(15_000)
  const all = useMemo(() => list.data?.items ?? [], [list.data])
  const mine = all.filter((e) => e.user.userId === me.id)
  const toDecide = all.filter((e) => servedStatus(e, now) === 'pending' && e.user.userId !== me.id && (e.approvers ?? []).includes(me.id))
  const tabs = [
    { id: 'mine' as const, label: 'Yours', icon: 'user' as const },
    ...(decides ? [{ id: 'decide' as const, label: 'To decide', icon: 'approval' as const, count: toDecide.length }] : []),
    ...(reads ? [{ id: 'account' as const, label: 'Everything in the account', icon: 'list' as const }] : []),
  ]
  const tab: Tab = picked && tabs.some((t) => t.id === picked) ? picked : decides && toDecide.length && !route.query.role ? 'decide' : 'mine'
  const rows = tab === 'mine' ? mine : tab === 'decide' ? toDecide : [...all].sort((a, b) => Number(b.kind === 'break-glass') - Number(a.kind === 'break-glass'))

  return (
    <>
      <PageHeader title="Elevations" subtitle={`Hold a role of ${account.name} for a while, approved at once by a rule or by an admin. It ends by itself when its time is up.`}>
        {tabs.length > 1 && <Tabs<Tab> ariaLabel="Elevations" value={tab} onChange={setPicked} tabs={tabs} />}
      </PageHeader>
      <div className="space-y-6">
        {tab === 'mine' && <AskForm initialRole={route.query.role} />}
        {list.error ? (
          <ErrorState error={list.error} title="Could not load the elevations" onRetry={() => void list.refetch()} />
        ) : list.loading && !list.data ? (
          <SkeletonText lines={3} />
        ) : rows.length === 0 ? (
          <EmptyState
            compact
            icon="clock"
            title={tab === 'decide' ? 'Nothing to decide' : tab === 'mine' ? 'You have not held a role for a while yet' : 'No elevations yet'}
            message={
              tab === 'decide'
                ? 'Asks that name you among their approvers land here.'
                : tab === 'mine'
                  ? 'Ask above, or from a refusal: when a rule covers the role, Ask for this role for an hour approves it at once.'
                  : 'Elevations and break-glass access in this account are listed here.'
            }
          />
        ) : (
          <ul className="space-y-3" aria-label={tab === 'mine' ? 'Your elevations' : tab === 'decide' ? 'Elevations to decide' : 'Elevations in the account'}>
            {rows.map((e) => (
              <li key={e.elevationId}>
                <ElevationCard elevation={e} now={now} nameOf={nameOf} deciding={tab === 'decide'} showPerson={tab !== 'mine'} />
              </li>
            ))}
          </ul>
        )}
      </div>
    </>
  )
}

/* ---------------------------------------------------------------- asking */

function AskForm({ initialRole }: { initialRole?: string }) {
  const { account, session } = useConsole()
  const roles = session.account.roles ?? []
  const groupRoles = groupRolesOf(roles, session.account.elevations)
  const rules = useQuery(ELEVATION_RULES_KEY, () => elevationsApi.rules())
  const choices = MANAGED_ROLES.filter((r) => !groupRoles.includes(r))
  const [role, setRole] = useState<string>(initialRole && choices.includes(initialRole as (typeof choices)[number]) ? initialRole : (choices.find((r) => r === ADMIN_ROLE) ?? choices[0] ?? ''))
  const [minutes, setMinutes] = useState('60')
  const [reason, setReason] = useState('')
  const [busy, setBusy] = useState(false)
  const [note, setNote] = useState<{ tone: 'ok' | 'error'; text: string } | null>(null)
  const max = rules.data?.maxMinutes ?? 480
  const rule = role ? ruleFor(rules.data, role, groupRoles, Number(minutes)) : undefined
  const pendingFor = isoDurationMs(rules.data?.pendingFor)
  // every managed role but auditor may ask (the managed policies' elevation:create)
  const decision: Decision =
    groupRoles.length > 0 && groupRoles.every((r) => r === 'auditor')
      ? { allowed: false, denied: { layer: 'console-iam', action: 'elevation:create', reason: 'An auditor reads, and never asks to hold a role for a while', role: 'auditor', requestable: false } }
      : { allowed: true }

  if (!choices.length) return null
  const ask = async () => {
    if (!reason.trim() || !role) return
    setBusy(true)
    setNote(null)
    try {
      const e = await elevationsApi.ask({ role, reason: reason.trim(), durationMinutes: Number(minutes) })
      setReason('')
      invalidate('iam')
      if (e.status === 'active') {
        await refreshRoles()
        setNote({ tone: 'ok', text: `You hold ${roleLabel(e.role)} until ${clockOf(e.until!)}, approved by ${approvedByWords(e.decidedBy, (id) => id)}.` })
      } else {
        setNote({ tone: 'ok', text: `Asked. An admin of ${account.name} decides; you will see it below.` })
      }
    } catch (err) {
      const open = openElevationOf(err)
      setNote({
        tone: 'error',
        text: open ? `You asked for ${roleLabel(open.role)} already, and it is ${ELEVATION_STATUS_WORDS[open.status].label.toLowerCase()}.` : isApiError(err) ? err.error : String(err),
      })
    } finally {
      setBusy(false)
    }
  }

  return (
    <Card title="Ask for a role for a while" subtitle="For work that needs more than your groups give you, for as long as it takes">
      <form
        className="space-y-4"
        onSubmit={(e) => {
          e.preventDefault()
          void ask()
        }}
      >
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          <Field label="Role" htmlFor="elevation-role" hint={role ? ROLE_SUMMARY[role as (typeof MANAGED_ROLES)[number]] : undefined}>
            <Select ariaLabel={`Role: ${roleLabel(role)}`} value={role} onChange={setRole} options={choices.map((r) => ({ value: r, label: roleLabel(r) }))} />
          </Field>
          <Field label="For" htmlFor="elevation-minutes">
            <Select
              ariaLabel={`For: ${minutesWords(Number(minutes))}`}
              value={minutes}
              onChange={setMinutes}
              options={DURATIONS.filter((d) => d <= max).map((d) => ({ value: String(d), label: minutesWords(d) }))}
            />
          </Field>
        </div>
        <Field label="Why you need it" htmlFor="elevation-reason" hint="Kept with the elevation, on the account’s trail. Up to 2,000 characters." required>
          <Textarea id="elevation-reason" rows={2} maxLength={2000} value={reason} onChange={(e) => setReason(e.target.value)} placeholder="What you are about to do" />
        </Field>
        <p className="flex gap-2 text-sm text-secondary" data-approval={rule ? 'rule' : 'person'}>
          <Icon name={rule ? 'bolt' : 'users'} size={16} className="mt-0.5 shrink-0 text-tertiary" />
          <span>
            {rule
              ? `Approved at once by the rule ${rule.name}, which gives ${roleLabel(role)} to an ${rule.requesterRoles.map(roleLabel).join(' or ')} for up to ${minutesWords(rule.maxMinutes)}.`
              : `An admin of ${account.name} who is not you decides. Nobody decided within ${pendingFor ? minutesWords(pendingFor / 60_000) : 'a day'}, it expires.`}
          </span>
        </p>
        {note && (
          <p role={note.tone === 'error' ? 'alert' : 'status'} className={cx('text-sm', note.tone === 'error' ? 'text-critical' : 'text-primary')}>
            {note.text}
          </p>
        )}
        <div className="flex justify-end">
          {decision.allowed ? (
            <Button type="submit" variant="primary" icon="clock" loading={busy} disabled={busy || !reason.trim() || !role}>
              {`Ask for ${roleLabel(role)}`}
            </Button>
          ) : (
            <ActionButton decision={decision} what="Ask for a role for a while" variant="primary">
              {`Ask for ${roleLabel(role)}`}
            </ActionButton>
          )}
        </div>
      </form>
    </Card>
  )
}

/* ---------------------------------------------------------------- one elevation */

function ElevationCard({ elevation: e, now, nameOf, deciding, showPerson }: { elevation: Elevation; now: number; nameOf: (id: string) => string; deciding: boolean; showPerson: boolean }) {
  const { me, account, admin } = useConsole()
  const [comment, setComment] = useState('')
  const [busy, setBusy] = useState<'approve' | 'reject' | 'revoke' | null>(null)
  const [failure, setFailure] = useState<string | null>(null)
  const status = servedStatus(e, now)
  const words = ELEVATION_STATUS_WORDS[status]
  const glass = e.kind === 'break-glass'
  const own = e.user.userId === me.id
  const person = e.user.name ?? nameOf(e.user.userId)
  const verb = status === 'pending' ? 'asks for' : status === 'active' ? 'holds' : 'held'
  const canEnd = (status === 'active' || status === 'pending') && (own || admin)

  const act = async (what: 'approve' | 'reject' | 'revoke') => {
    setBusy(what)
    setFailure(null)
    try {
      if (what === 'revoke') await elevationsApi.revoke(e.elevationId, comment.trim() ? { comment: comment.trim() } : undefined)
      else await elevationsApi.decide(e.elevationId, { decision: what, comment: comment.trim() || undefined })
      invalidate('iam')
      if (own) await refreshRoles()
    } catch (err) {
      setFailure(isApiError(err) ? err.error : String(err))
    } finally {
      setBusy(null)
    }
  }

  return (
    <Card className={cx(glass && 'border-critical/50')} data-elevation-card={e.elevationId} data-status={status}>
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0">
          <p className="flex flex-wrap items-center gap-2 text-sm text-primary">
            {glass && (
              <span className="inline-flex items-center gap-1 rounded-full bg-critical-tint px-2 py-0.5 text-caption font-semibold text-primary">
                <Icon name="alert-triangle" size={12} className="text-critical" /> Break-glass
              </span>
            )}
            <span>
              {showPerson ? <span className="font-medium">{person}</span> : 'You'} {showPerson ? verb : status === 'pending' ? 'asked for' : status === 'active' ? 'hold' : 'held'}
            </span>
            <RoleTags roles={[e.role]} />
            <span className="text-secondary">for {minutesWords(e.durationMinutes)}</span>
          </p>
          <p className="mt-1 text-sm text-secondary">
            “{e.reason}”{e.ticket ? ` (ticket ${e.ticket})` : ''}
          </p>
        </div>
        <StatusBadge status={words.status} label={words.label} size="sm" />
      </div>
      <div className="mt-2 space-y-1 text-caption text-secondary">
        <p>
          Asked <When at={e.requestedAt} />
          {e.decidedBy && status !== 'pending' && status !== 'cancelled' && (
            <>
              {' '}
              · {status === 'rejected' ? 'rejected' : 'approved'} by {approvedByWords(e.decidedBy, nameOf)}
            </>
          )}
          {e.comment && <> · “{e.comment}”</>}
        </p>
        {status === 'active' && e.until && (
          <p className="font-medium text-primary" data-time-left>
            {timeLeftWords(e.until, now)}, until {clockOf(e.until)}
          </p>
        )}
        {status === 'pending' && <p>Waiting for {(e.approvers ?? []).length ? (e.approvers ?? []).map(nameOf).join(', ') : `an admin of ${account.name}`}.</p>}
        {(status === 'revoked' || status === 'cancelled') && e.endedAt && (
          <p>
            Ended <When at={e.endedAt} />
            {e.endedBy ? ` by ${e.endedBy.startsWith('svc:') ? 'the console' : nameOf(e.endedBy)}` : ''}
          </p>
        )}
        {glass && (
          <p className="text-primary">
            On {account.name}’s own trail: emergency access, given by {approvedByWords(e.decidedBy, nameOf)}.
          </p>
        )}
      </div>
      {(deciding || canEnd) && (
        <div className="mt-3 space-y-2">
          {deciding && (
            <Field label="Comment" htmlFor={`elevation-comment-${e.elevationId}`} hint="Optional. The person reads it.">
              <Textarea id={`elevation-comment-${e.elevationId}`} rows={2} maxLength={2000} value={comment} onChange={(ev) => setComment(ev.target.value)} />
            </Field>
          )}
          {failure && (
            <p role="alert" className="text-sm text-critical">
              {failure}
            </p>
          )}
          <div className="flex flex-wrap gap-2">
            {deciding ? (
              <>
                <Button variant="primary" icon="check" data-decide="approve" loading={busy === 'approve'} disabled={busy !== null} onClick={() => void act('approve')}>
                  Approve
                </Button>
                <Button variant="danger" icon="close" data-decide="reject" loading={busy === 'reject'} disabled={busy !== null} onClick={() => void act('reject')}>
                  Reject
                </Button>
              </>
            ) : (
              <Button icon="stop" data-revoke loading={busy === 'revoke'} disabled={busy !== null} onClick={() => void act('revoke')}>
                {status === 'pending' ? 'Cancel the ask' : 'End it now'}
              </Button>
            )}
          </div>
        </div>
      )}
    </Card>
  )
}
