import { useEffect, useRef, useState, type FormEvent } from 'react'
import { Button, Card, Field, Icon, Input, PageHeader, useToast } from '@iaf/ui'
import { Facts, StatusBadge, When, useNow } from '@iaf/console-kit'
import { useConsole } from '../../app/console'
import { Link, navigate, pathFor, type PageProps } from '../../app/router'
import { isApiError } from '../../lib/api'
import { beginSignIn } from '../../lib/bff'
import { SCOPE_WORDS, cli, clientTitle, codeLettersValid, grantStatusWords, machineOf, normalizeUserCode, timeLeft } from '../../lib/cli'
import { explain } from '../../lib/plain'
import { useQuery } from '../../lib/queries'
import { MOCK } from '../../lib/session'
import type { DeviceGrant } from '../../lib/types'

/*
 * `#/device` (WP 7.4): where the person approves a CLI or SDK sign-in. The CLI prints a code and
 * this page's address (`?code=` filled in, or typed here); the page shows what asks, from which
 * machine and address, for what, and until when, then Approve or Deny. Approving signs a client in
 * as the person, so the BFF wants a fresh sign-in as it does for a delete: live, the API client
 * follows the step-up 403 to the BFF's own sign-in (a same-origin /auth/ path, never one from data)
 * and back to this page with the code, and the page builds no redirect of its own; in mock mode the
 * page says why and signs in again in place. Either way the person presses Approve again, so a link
 * never approves anything by itself.
 */

type Outcome = { kind: 'approved' | 'denied'; grant: DeviceGrant } | null

export default function DevicePage({ route }: PageProps) {
  const raw = route.query.code ?? ''
  const code = raw ? normalizeUserCode(raw) : null
  return (
    <>
      <PageHeader title="Sign in the CLI" subtitle="Approve a sign-in that the console CLI or an SDK started in your terminal." />
      <div className="max-w-2xl space-y-6">{code ? <GrantView key={code} code={code} /> : <CodeForm initial={raw} invalid={!!raw} />}</div>
    </>
  )
}

/** The code typed by hand, as the terminal shows it. */
function CodeForm({ initial, invalid }: { initial: string; invalid: boolean }) {
  const [value, setValue] = useState(initial)
  const [tried, setTried] = useState(invalid)
  useEffect(() => document.getElementById('device-code')?.focus(), [])
  const normal = normalizeUserCode(value)
  const error = !normal ? 'The code is eight letters, like WDJB-MJHT.' : !codeLettersValid(normal) ? 'Codes have no vowels. Check each letter against your terminal.' : undefined
  const submit = (e: FormEvent) => {
    e.preventDefault()
    setTried(true)
    if (error || !normal) return
    navigate(pathFor('device', {}, { code: normal }))
  }
  return (
    <Card title="Enter the code" subtitle="Your terminal shows it after you run iaf login.">
      <form onSubmit={submit} noValidate className="space-y-4">
        <Field label="Code" htmlFor="device-code" error={tried ? error : undefined} required>
          <Input id="device-code" value={value} onChange={(e) => setValue(e.target.value)} autoComplete="off" autoCapitalize="characters" spellCheck={false} placeholder="XXXX-XXXX" className="font-mono uppercase tracking-widest" />
        </Field>
        <Button type="submit" variant="primary">
          Continue
        </Button>
      </form>
    </Card>
  )
}

function GrantView({ code }: { code: string }) {
  const { session, account } = useConsole()
  const toast = useToast()
  const now = useNow(1_000)
  const grant = useQuery(['device-grant', code], () => cli.deviceGrants.get(code))
  const [busy, setBusy] = useState<'approve' | 'deny' | 'sign-in' | null>(null)
  const [stepUp, setStepUp] = useState<string | null>(null)
  const [freshlySigned, setFreshlySigned] = useState(false)
  const [outcome, setOutcome] = useState<Outcome>(null)
  const statusRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (freshlySigned) document.getElementById('device-approve')?.focus()
  }, [freshlySigned])
  useEffect(() => {
    if (outcome) statusRef.current?.focus()
  }, [outcome])

  const decide = async (kind: 'approve' | 'deny') => {
    setBusy(kind)
    try {
      const g = kind === 'approve' ? await cli.deviceGrants.approve(code) : await cli.deviceGrants.deny(code)
      setOutcome({ kind: kind === 'approve' ? 'approved' : 'denied', grant: g })
      setStepUp(null)
    } catch (e) {
      if (isApiError(e) && e.stepUp) setStepUp(e.stepUp)
      else if (isApiError(e) && (e.status === 409 || e.status === 410)) void grant.refetch()
      else {
        const x = explain(e, kind === 'approve' ? 'Approve the sign-in' : 'Deny the sign-in')
        toast.error(x.title, [x.message, x.next].filter(Boolean).join(' '))
      }
    } finally {
      setBusy(null)
    }
  }

  const signInAgain = async () => {
    // live, the API client already took the browser to the BFF's own sign-in (a same-origin /auth/ path); the page never redirects itself
    if (!stepUp || !MOCK) return
    setBusy('sign-in')
    try {
      await beginSignIn(pathFor('device', {}, { code }), session.user.userId)
      setStepUp(null)
      setFreshlySigned(true)
    } finally {
      setBusy(null)
    }
  }

  if (grant.error) {
    const notFound = isApiError(grant.error) && (grant.error.status === 404 || grant.error.status === 400)
    return (
      <Card title={notFound ? 'No sign-in waits under this code' : 'Could not read the sign-in'} subtitle={notFound ? `Nothing asked to sign in with ${code}. Check it against your terminal, or run iaf login again for a new one.` : explain(grant.error).message}>
        <Link to={pathFor('device')} className="text-sm text-accent underline underline-offset-2">
          Enter another code
        </Link>
      </Card>
    )
  }
  if (!grant.data) return <Card title="Reading the sign-in" subtitle={`Code ${code}`} aria-busy="true" />

  const g = outcome?.grant ?? grant.data
  const left = timeLeft(g.expiresAt, now)
  const status = g.status === 'pending' && !left ? 'expired' : g.status
  const pending = status === 'pending'

  return (
    <>
      <Card
        title={`${clientTitle(g.clientId)} asks to sign in as you`}
        subtitle={`Code ${g.userCode}`}
        actions={<StatusBadge status={status === 'pending' ? 'waiting' : status === 'approved' ? 'succeeded' : status === 'denied' ? 'failed' : 'cancelled'} label={status === 'pending' ? 'Waiting' : status === 'approved' ? 'Approved' : status === 'denied' ? 'Denied' : 'Expired'} />}
        data-grant={g.userCode}
        data-grant-status={status}
      >
        <Facts
          columns={2}
          items={[
            { label: 'What asks', value: clientTitle(g.clientId) },
            { label: 'Machine', value: machineOf(g.requestedFrom?.userAgent) },
            { label: 'From the address', value: <span className="font-mono">{g.requestedFrom?.ip ?? 'not given'}</span> },
            { label: 'Asked', value: <When at={g.requestedAt} now={now} /> },
            { label: 'Access', value: SCOPE_WORDS, wide: true },
            { label: pending ? 'The code stops working in' : 'Decided', value: pending ? <span className="tabular-nums">{left}</span> : g.decidedAt ? <When at={g.decidedAt} now={now} /> : <span className="text-tertiary">not decided</span> },
            { label: 'Starts in', value: `${account.name}; the CLI can switch to any account you belong to` },
          ]}
        />
        {pending && (
          <p className="mt-5 flex gap-2 rounded-md bg-warning-tint px-3 py-2 text-caption text-primary">
            <Icon name="warning" size={14} className="mt-0.5 shrink-0 text-warning" />
            <span>Approve only if you started this sign-in yourself, just now, and the code matches your terminal. Nobody from the platform team will ask you to approve a code for them.</span>
          </p>
        )}
        {pending && (
          <div className="mt-5 flex flex-wrap gap-2">
            <Button id="device-approve" variant="primary" icon="check" loading={busy === 'approve'} disabled={!!busy || !!stepUp} onClick={() => void decide('approve')}>
              Approve
            </Button>
            <Button variant="secondary" icon="close" loading={busy === 'deny'} disabled={!!busy} onClick={() => void decide('deny')}>
              Deny
            </Button>
          </div>
        )}
      </Card>

      <div role="status" aria-live="polite" className="empty:hidden" data-device-status>
        {stepUp && pending && (
          <Card title="Sign in again to approve" subtitle="Approving signs the CLI in as you, so the console asks for a fresh sign-in first, as it does before a delete.">
            {MOCK ? (
              <Button variant="primary" icon="user" loading={busy === 'sign-in'} onClick={() => void signInAgain()}>
                Sign in again
              </Button>
            ) : (
              <p className="text-sm text-secondary">Taking you to sign in again. You come back to this code.</p>
            )}
          </Card>
        )}
        {freshlySigned && pending && !stepUp && <p className="text-sm text-secondary">You signed in again. Approve to finish signing the CLI in.</p>}
      </div>

      {(outcome || !pending) && (
        <div ref={statusRef} tabIndex={-1} className="rounded-lg border border-stroke-light px-5 py-4 outline-none" data-device-outcome={status}>
          <p className="text-sm font-medium text-primary">{grantStatusWords({ status })}</p>
          {status === 'approved' && (
            <p className="mt-1 text-caption text-secondary">
              It stays signed in for up to 12 hours. See it or sign it out under{' '}
              <Link to={pathFor('iam-cli')} className="text-accent underline underline-offset-2">
                CLI sign-ins
              </Link>
              .
            </p>
          )}
          {status === 'denied' && <p className="mt-1 text-caption text-secondary">If you did not start it, someone else has your terminal’s code. Nothing was signed in, and you need do nothing more.</p>}
        </div>
      )}
    </>
  )
}
