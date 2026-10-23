import { useEffect, useState } from 'react'
import { Button, Field, Icon, Textarea, cx } from '@iaf/ui'
import { useNow } from '@iaf/console-kit'
import { isApiError } from '../lib/api'
import { loadSession } from '../lib/bff'
import { earliestEnd, elevatableRole, elevationsApi, groupRolesOf, ruleFor, timeLeftWords, type Elevation } from '../lib/elevations'
import { invalidate, useQuery } from '../lib/queries'
import { roleLabel } from '../lib/session'
import type { Denial } from '../lib/types'
import { useConsole } from './console'
import { Link, pathFor } from './router'

/*
 * Roles held for a while (WP 9.12), where the shell meets them: the refusal's offer to hold the
 * role that would allow the action for an hour, when an auto-approval rule covers it; the banner
 * that says which roles the person holds for a while and how long is left, and reads the session
 * again when one ends; and the mark on an action that only such a role allows.
 */

export const ELEVATION_RULES_KEY = ['elevation-rules'] as const

/** "15:05", in the reader's clock. */
export function clockOf(at: string): string {
  return new Date(at).toLocaleTimeString('en-GB', { hour: '2-digit', minute: '2-digit' })
}

/** After a role starts or stops deciding: the session's roles, the type decisions and every page's data are read again. */
export async function refreshRoles(): Promise<void> {
  await loadSession()
  invalidate()
}

/** The elevation a 409 answered with: the same person's open ask or elevation for the role. */
export function openElevationOf(e: unknown): Elevation | null {
  if (!isApiError(e) || e.status !== 409) return null
  const body = e.body as Partial<Elevation> | null
  return body && typeof body.elevationId === 'string' && typeof body.role === 'string' ? (body as Elevation) : null
}

/**
 * The refusal's offer: hold the role the refusal suggests for an hour, when a rule approves it at
 * once for the roles the person holds through their groups. Without such a rule it points to the
 * elevations page, where an admin of the account decides the ask.
 */
export function ElevateOffer({ denied, onClose }: { denied: Denial; onClose: () => void }) {
  const { session, account } = useConsole()
  const roles = session.account.roles
  const role = elevatableRole(denied, roles)
  const rules = useQuery(ELEVATION_RULES_KEY, () => elevationsApi.rules(), { enabled: !!role })
  const [reason, setReason] = useState('')
  const [busy, setBusy] = useState(false)
  const [result, setResult] = useState<Elevation | null>(null)
  const [failure, setFailure] = useState<string | null>(null)
  // once approved the role is held, so the refusal no longer suggests it: the answer stays on screen
  if (result) return <OfferResult result={result} onClose={onClose} />
  if (!role || !rules.data) return null
  const rule = ruleFor(rules.data, role, groupRolesOf(roles, session.account.elevations), 60)
  if (!rule) {
    return (
      <p className="text-caption text-secondary">
        Need it for a short while only?{' '}
        <Link to={pathFor('iam-elevations', {}, { role })} onClick={onClose} className="font-medium text-accent hover:underline">
          Ask to hold {roleLabel(role)} for a while
        </Link>
        ; an admin of {account.name} decides.
      </p>
    )
  }

  const ask = async () => {
    if (!reason.trim()) return
    setBusy(true)
    setFailure(null)
    try {
      const e = await elevationsApi.ask({ role, reason: reason.trim(), durationMinutes: 60 })
      setResult(e)
      if (e.status === 'active') await refreshRoles()
    } catch (err) {
      const open = openElevationOf(err)
      if (open) setResult(open)
      else setFailure(isApiError(err) ? err.error : String(err))
    } finally {
      setBusy(false)
    }
  }

  return (
    <form
      aria-labelledby="elevate-offer"
      className="rounded-lg border border-accent/30 bg-accent-tint/40 p-4"
      onSubmit={(e) => {
        e.preventDefault()
        void ask()
      }}
    >
      <h3 id="elevate-offer" className="text-sm font-semibold text-primary">
        Ask for this role for an hour
      </h3>
      <p className="mt-0.5 text-caption text-secondary">
        {roleLabel(role)} is approved at once for an {rule.requesterRoles.map(roleLabel).join(' or ')} by the rule {rule.name}. It ends by itself after an hour, everything you do with it is on the
        account’s trail, and you can end it sooner.
      </p>
      <Field label="Why you need it" htmlFor="elevate-reason" hint="Kept with the elevation. Up to 2,000 characters." error={failure ?? undefined} required>
        <Textarea id="elevate-reason" rows={2} maxLength={2000} required value={reason} onChange={(e) => setReason(e.target.value)} invalid={!!failure} placeholder="What you are about to do" />
      </Field>
      <div className="mt-3 flex justify-end">
        <Button type="submit" variant="primary" icon="clock" loading={busy} disabled={busy || !reason.trim()}>
          {`Ask for ${roleLabel(role)} for an hour`}
        </Button>
      </div>
    </form>
  )
}

function OfferResult({ result, onClose }: { result: Elevation; onClose: () => void }) {
  const active = result.status === 'active' && result.until
  return (
    <div role="status" className="rounded-lg border border-healthy/30 bg-healthy-tint p-4 text-sm" data-elevation={result.status}>
      <p className="flex items-start gap-2 font-medium text-primary">
        <Icon name="check" size={16} className="mt-0.5 shrink-0 text-healthy" />
        {active ? `You hold ${roleLabel(result.role)} until ${clockOf(result.until!)}` : `You asked for ${roleLabel(result.role)} already; it waits for an admin`}
      </p>
      <p className="mt-1 text-secondary">
        {active
          ? 'Close this and try again: what the role allows is unlocked. It ends by itself at that time, and you can end it sooner on the elevations page.'
          : 'You will see the answer on the elevations page.'}
      </p>
      <Link to={pathFor('iam-elevations')} onClick={onClose} className="mt-2 inline-flex font-medium text-accent hover:underline">
        Your elevations
      </Link>
    </div>
  )
}

/**
 * The roles the person holds for a while in this account, with the time left. Break-glass access
 * is loud: it is emergency access, and it says the trail keeps everything done with it. When the
 * earliest one ends, the session is read again, so the console stops offering what it allowed.
 */
export function HeldRolesBanner() {
  const { session, account } = useConsole()
  const held = session.account.elevations ?? []
  const now = useNow(15_000)
  const end = earliestEnd(held)
  useEffect(() => {
    if (end === null) return
    const t = setTimeout(() => void refreshRoles(), Math.max(0, end - Date.now()) + 500)
    return () => clearTimeout(t)
  }, [end])
  if (!held.length) return null
  const glass = held.some((h) => h.kind === 'break-glass')
  return (
    <div
      role="status"
      data-held-roles={held.length}
      className={cx('mb-4 flex flex-wrap items-center gap-x-3 gap-y-2 rounded-lg border p-3 text-sm', glass ? 'border-critical/50 bg-critical-tint' : 'border-warning/40 bg-warning-tint')}
    >
      <Icon name={glass ? 'alert-triangle' : 'clock'} size={16} className={cx('shrink-0', glass ? 'text-critical' : 'text-warning')} />
      <span className="min-w-0 flex-1 text-primary">
        {held.map((h, i) => (
          <span key={h.elevationId} className="block">
            {i === 0 && glass && h.kind === 'break-glass' && <span className="font-semibold">Break-glass access. </span>}
            You hold <span className="font-semibold">{roleLabel(h.role)}</span> in {account.name} {h.kind === 'break-glass' ? 'by emergency access' : 'for a while'}: {timeLeftWords(h.until, now)},
            until {clockOf(h.until)}.{h.kind === 'break-glass' && ' Everything you do with it is on the account’s trail.'}
          </span>
        ))}
      </span>
      <Link to={pathFor('iam-elevations')} className="shrink-0 font-medium text-accent hover:underline">
        Elevations
      </Link>
    </div>
  )
}

/** The mark on an action only a role held for a while allows. */
export function ElevatedMark() {
  return (
    <span
      data-elevated="true"
      className="ml-1.5 inline-flex items-center rounded-full border border-stroke bg-surface px-1.5 text-caption2 font-medium text-primary"
      title="Allowed by a role you hold for a while"
    >
      elevated
    </span>
  )
}
