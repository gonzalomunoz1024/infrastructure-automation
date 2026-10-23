import { createContext, useContext, useMemo, useState, type ReactNode } from 'react'
import { Button, Field, Icon, Modal, OverlayBody, OverlayFooter, OverlayHeader, Select, Textarea, cx, type ButtonSize, type ButtonVariant, type IconName } from '@iaf/ui'
import { DotTag, Facts, Mono } from '@iaf/console-kit'
import { isApiError } from '../lib/api'
import { bff } from '../lib/bff'
import { decidedBy, denialOf, denialTitle, layerDescription, layerLabel, type Decision, type Denial } from '../lib/permissions'
import { useMutation, useQuery } from '../lib/queries'
import { MANAGED_ROLES, ROLE_SUMMARY, roleLabel } from '../lib/session'
import type { AccessRequest } from '../lib/types'
import { useConsole } from './console'
import { Hint } from './Hint'
import { useCloseMenu } from './MenuButton'
import { LaterButton, phaseNote } from './Phase'
import { RoleTag } from './RoleTag'
import { Link, pathFor } from './router'

/*
 * The refusal view (WP 1.5). When a role denies an action the console says who decided: the
 * account's own policies (console IAM) with the statement that decided, or the platform's role
 * for the account with the scope it lacks. Request access sends the admins of the account a
 * request for the managed role that would allow it. ActionButton draws a denied action with a
 * lock and opens the view instead of pretending the action is gone.
 */

export interface RefusalRequest {
  denied: Denial
  /** what was attempted, in the words of the button: "Create virtual machine" */
  what?: string
  correlationId?: string
}

export interface RefusalApi {
  show: (request: RefusalRequest) => void
  /** shows the refusal a failed call carried; false when the error was something else */
  showError: (error: unknown, what?: string) => boolean
}

const Ctx = createContext<RefusalApi | null>(null)

export function RefusalProvider({ children }: { children: ReactNode }) {
  const [current, setCurrent] = useState<RefusalRequest | null>(null)
  const api = useMemo<RefusalApi>(
    () => ({
      show: (request) => setCurrent(request),
      showError: (error, what) => {
        const denied = denialOf(error)
        if (!denied) return false
        setCurrent({ denied, what, correlationId: isApiError(error) ? error.correlationId : undefined })
        return true
      },
    }),
    [],
  )
  return (
    <Ctx.Provider value={api}>
      {children}
      {current && <RefusalDialog request={current} onClose={() => setCurrent(null)} />}
    </Ctx.Provider>
  )
}

export function useRefusal(): RefusalApi {
  const v = useContext(Ctx)
  if (!v) throw new Error('useRefusal needs a RefusalProvider above it')
  return v
}

function lowerFirst(s: string): string {
  return s ? s.charAt(0).toLowerCase() + s.slice(1) : s
}

type Verdict = 'allowed' | 'denied' | 'not asked'

function Layer({ n, label, description, verdict, detail, deciding }: { n: number; label: string; description: string; verdict: Verdict; detail: string; deciding: boolean }) {
  const icon: IconName = verdict === 'allowed' ? 'check' : verdict === 'denied' ? 'close-circle' : 'minus'
  const tone = verdict === 'allowed' ? 'text-healthy' : verdict === 'denied' ? 'text-critical' : 'text-tertiary'
  return (
    <li className={cx('flex gap-3 rounded-lg border p-3', deciding ? 'border-critical/40 bg-critical-tint/40' : 'border-stroke-light')} data-layer-verdict={verdict}>
      <span className={cx('mt-0.5 flex size-6 shrink-0 items-center justify-center rounded-full bg-surface', tone)} aria-hidden="true">
        <Icon name={icon} size={14} />
      </span>
      <div className="min-w-0 flex-1">
        <div className="flex flex-wrap items-center gap-2">
          <span className="text-sm font-semibold text-primary">
            {n}. {label}
          </span>
          <DotTag size="sm" tone={verdict === 'denied' ? 'critical' : verdict === 'allowed' ? 'healthy' : 'neutral'}>
            {verdict}
          </DotTag>
          {deciding && (
            <DotTag size="sm" tone="critical">
              decided here
            </DotTag>
          )}
        </div>
        <p className="mt-0.5 text-caption text-tertiary">{description}</p>
        <p className="mt-1 text-sm text-primary">{detail}</p>
      </div>
    </li>
  )
}

export function RefusalDialog({ request, onClose }: { request: RefusalRequest; onClose: () => void }) {
  const { denied: d, what, correlationId } = request
  const { account } = useConsole()
  const [sent, setSent] = useState<AccessRequest | null>(null)
  const byConsole = d.layer === 'console-iam'
  const title = what ? `You can't ${lowerFirst(what)} here` : 'Not allowed'
  const rolePath = d.role && !d.role.includes(',') ? pathFor('iam-role', { id: d.role }) : null

  return (
    <Modal onClose={onClose} width="max-w-xl" raised label={title}>
      <div data-refusal={d.layer}>
        <OverlayHeader title={title} subtitle={denialTitle(d)} icon="lock" />
        <OverlayBody className="space-y-6">
          <section aria-labelledby="refusal-trail">
            <h3 id="refusal-trail" className="mb-2 text-caption font-semibold uppercase tracking-wide text-tertiary">
              Who decided
            </h3>
            <ol className="space-y-2">
              <Layer n={1} label={layerLabel('console-iam')} description={layerDescription('console-iam', account.name)} verdict={byConsole ? 'denied' : 'allowed'} detail={byConsole ? decidedBy(d) : `Your role${d.role ? ` ${d.role}` : ''} allows ${d.action}; the question went on to the platform.`} deciding={byConsole} />
              <Layer n={2} label={layerLabel('platform')} description={layerDescription('platform', account.name)} verdict={byConsole ? 'not asked' : 'denied'} detail={byConsole ? 'The platform is only asked once the account allows the action.' : decidedBy(d)} deciding={!byConsole} />
            </ol>
          </section>

          <Facts
            columns={2}
            items={[
              { label: 'Action', value: <Mono className="text-primary">{d.action}</Mono> },
              {
                label: 'Your role',
                value: d.role ? (
                  rolePath ? (
                    <Link to={rolePath} onClick={onClose} className="inline-flex rounded-full transition-opacity hover:opacity-70">
                      <RoleTag role={d.role} />
                    </Link>
                  ) : (
                    <RoleTag role={d.role} />
                  )
                ) : (
                  <span className="text-tertiary">none in this account</span>
                ),
              },
              { label: 'Resource', value: <Mono className="break-all text-primary">{d.resource ?? 'any'}</Mono>, wide: true },
              ...(d.policyId ? [{ label: 'Policy', value: <Mono className="text-primary">{d.policyId}</Mono> }] : []),
              ...(d.statement ? [{ label: 'Statement', value: <Mono className="text-primary">{d.statement}</Mono> }] : []),
              ...(d.scope ? [{ label: 'Missing platform scope', value: <Mono className="text-primary">{d.scope}</Mono> }] : []),
              ...(d.serviceAccount ? [{ label: 'Platform service account', value: <Mono className="text-primary">{d.serviceAccount}</Mono> }] : []),
              ...(correlationId ? [{ label: 'Correlation id', value: <Mono>{correlationId}</Mono> }] : []),
            ]}
          />

          {d.requestable ? (
            <RequestAccess denied={d} sent={sent} onSent={setSent} onClose={onClose} accountName={account.name} />
          ) : (
            <p className="text-sm text-secondary">Nobody can grant this from the console. Ask the platform team if you think the account should be allowed to.</p>
          )}
        </OverlayBody>
        <OverlayFooter>
          <Button onClick={onClose}>Close</Button>
        </OverlayFooter>
      </div>
    </Modal>
  )
}

/** The request a 409 answered with: the same requester's pending ask for the same role. */
function pendingOf(e: unknown): AccessRequest | null {
  if (!isApiError(e) || e.status !== 409) return null
  const body = e.body as Partial<AccessRequest> | null
  return body && typeof body.requestId === 'string' && body.status === 'pending' && typeof body.role === 'string' ? (body as AccessRequest) : null
}

function RequestAccess({ denied: d, sent, onSent, onClose, accountName }: { denied: Denial; sent: AccessRequest | null; onSent: (r: AccessRequest) => void; onClose: () => void; accountName: string }) {
  const { account } = useConsole()
  const [role, setRole] = useState(d.suggestedRole ?? 'operator')
  const [reason, setReason] = useState('')
  const people = useQuery(['iam-users', 'names', account.id], () => bff.iam.users.list({ limit: 200 }))
  const nameOf = (id: string) => people.data?.items.find((u) => u.userId === id)?.name ?? id
  const send = useMutation((input: { action: string; resource?: string; role: string; reason: string; layer: Denial['layer'] }) => bff.accessRequests.create(input), { invalidates: ['access-requests'] })
  const empty = reason.trim().length === 0
  // a second ask for the same role answers 409 with the request still pending: show that one
  const already = pendingOf(send.error)
  const errorText = send.error && !already ? (isApiError(send.error) ? send.error.error : String(send.error)) : undefined
  const shown = sent ?? already

  if (shown) {
    // the requester may be an approver too, but never decides their own
    const approvers = (shown.approvers ?? []).filter((a) => a !== shown.requester.userId).map(nameOf)
    return (
      <div role="status" className="rounded-lg border border-healthy/30 bg-healthy-tint p-4" data-request-sent={shown.requestId}>
        <div className="flex items-start gap-2">
          <Icon name="check" size={16} className="mt-0.5 shrink-0 text-healthy" />
          <div className="min-w-0 text-sm">
            <p className="font-medium text-primary">
              {sent ? 'Request sent' : 'You asked already'}
              {approvers.length ? ` to ${approvers.join(', ')}` : ''}
            </p>
            <p className="mt-0.5 text-secondary">
              {sent ? 'You asked' : `On ${new Date(shown.requestedAt).toLocaleDateString()} you asked`} for the <span className="font-medium text-primary">{roleLabel(shown.role)}</span> role in {accountName}, and it is still pending. Its approvers see it in their notifications and decide there; you will see the answer under your access requests.
            </p>
            <Link to={pathFor('iam-request', { id: shown.requestId })} onClick={onClose} className="mt-2 inline-flex items-center gap-1 font-medium text-accent transition-opacity hover:opacity-70">
              View request <span className="font-mono text-caption">{shown.requestId}</span>
            </Link>
          </div>
        </div>
      </div>
    )
  }

  return (
    <form
      aria-labelledby="request-access"
      className="rounded-lg border border-stroke-light bg-surface-secondary p-4"
      onSubmit={(e) => {
        e.preventDefault()
        if (empty) return
        void send.mutate({ action: d.action, resource: d.resource, role, reason: reason.trim(), layer: d.layer }).then((r) => r && onSent(r))
      }}
    >
      <h3 id="request-access" className="text-sm font-semibold text-primary">
        Request access
      </h3>
      <p className="mt-0.5 text-caption text-secondary">{d.layer === 'platform' ? `The platform's role for ${accountName} is widened by the platform team; the admins of the account forward your request to them.` : `Asks the approvers of ${accountName} (its admins, and any group they let decide) to put you in a group that carries the role.`}</p>
      <div className="mt-3 grid grid-cols-1 gap-3 sm:grid-cols-[12rem_1fr]">
        <Field label="Role to request" htmlFor="request-role">
          <Select ariaLabel={`Role to request: ${roleLabel(role)}`} value={role} onChange={setRole} options={MANAGED_ROLES.map((r) => ({ value: r, label: roleLabel(r), hint: ROLE_SUMMARY[r] }))} />
        </Field>
        <Field label="Why you need it" hint="The approvers read it. Up to 2,000 characters." htmlFor="request-reason" error={errorText} required>
          <Textarea id="request-reason" value={reason} onChange={(e) => setReason(e.target.value)} rows={3} required maxLength={2000} invalid={!!errorText} placeholder="What you are trying to do, and for how long you need it" />
        </Field>
      </div>
      <div className="mt-3 flex justify-end">
        <Button type="submit" variant="primary" icon="send" loading={send.loading} disabled={empty || send.loading}>
          Send request
        </Button>
      </div>
    </form>
  )
}

export interface ActionButtonProps {
  decision: Decision
  /** what the button does, for the refusal's title: "Create virtual machine" */
  what: string
  /** the phase that makes the allowed action work; until then an allowed action is a LaterButton */
  phase?: number
  variant?: ButtonVariant
  size?: ButtonSize
  icon?: IconName
  className?: string
  children: string
  onClick?: () => void
  /** a spinner in place of the icon while the action is being sent */
  loading?: boolean
  /** allowed, but not now (a form with problems, a policy that refuses) */
  disabled?: boolean
  /** a hook for tests and the menu: the verb the button runs */
  verb?: string
}

/** An action as the decision allows it: live, greyed for a later phase, or locked with the refusal behind it. */
export function ActionButton({ decision, what, phase, variant = 'secondary', size, icon, className, children, onClick, loading, disabled, verb }: ActionButtonProps) {
  const refusal = useRefusal()
  if (!decision.allowed && decision.denied) {
    const d = decision.denied
    // the headline, not the service's reason: that names the whole wfrn, too long for a tooltip
    const note = `Not allowed: ${denialTitle(d)}. Select to see why.`
    return (
      <Hint label={note} className={className}>
        <Button variant={variant} size={size} icon="lock" data-denied={d.layer} data-verb={verb} onClick={() => refusal.show({ denied: d, what })}>
          {children}
          <span className="sr-only">. {note}</span>
        </Button>
      </Hint>
    )
  }
  if (phase) {
    return (
      <LaterButton phase={phase} variant={variant} size={size} icon={icon} className={className}>
        {children}
      </LaterButton>
    )
  }
  return (
    <Button variant={variant} size={size} icon={icon} className={className} onClick={onClick} loading={loading} disabled={disabled || loading} data-verb={verb}>
      {children}
    </Button>
  )
}

export interface ActionMenuItemProps {
  decision: Decision
  /** what the item does, for the refusal's title */
  what: string
  /** the phase that brings it; until then the item says so and does nothing */
  phase?: number
  icon?: IconName
  /** destructive: drawn in red */
  danger?: boolean
  /** one quiet line under the title */
  description?: string
  children: string
  onSelect?: () => void
  verb?: string
  /** why the item cannot run right now (a change is in flight); it then says so and does nothing */
  unavailable?: string
}

/**
 * One item of a MenuButton's menu, under the same rules as ActionButton: a denied action shows
 * its lock and opens the refusal, a later phase says which phase brings it, an unavailable one
 * says why, anything else runs. Inert items stay in the menu's arrow order (aria-disabled), as
 * the menu pattern asks; the menu closes, giving the focus back to its button, before anything
 * opens, so a drawer or the refusal gives it back there too.
 */
export function ActionMenuItem({ decision, what, phase, icon, danger, description, children, onSelect, verb, unavailable }: ActionMenuItemProps) {
  const refusal = useRefusal()
  const close = useCloseMenu()
  const denied = !decision.allowed && decision.denied ? decision.denied : null
  const later = !denied && !!phase
  const waiting = !denied && !later && !!unavailable
  const inert = later || waiting
  const note = denied ? `Not allowed: ${denied.reason}` : later ? phaseNote(phase!) : waiting ? unavailable : description
  return (
    <button
      type="button"
      role="menuitem"
      tabIndex={-1}
      data-verb={verb}
      data-denied={denied?.layer}
      data-phase={later ? phase : undefined}
      data-unavailable={waiting || undefined}
      aria-disabled={inert || undefined}
      onClick={() => {
        if (denied) {
          close()
          refusal.show({ denied, what })
          return
        }
        if (inert) return
        close()
        onSelect?.()
      }}
      className={cx(
        'flex w-full items-start gap-2.5 px-3 py-2 text-left text-sm outline-none transition-colors',
        'hover:bg-surface-secondary focus:bg-surface-secondary focus:ring-2 focus:ring-inset focus:ring-accent/60',
        inert ? 'cursor-not-allowed text-secondary' : danger && !denied ? 'text-critical' : 'text-primary',
      )}
    >
      <Icon name={denied ? 'lock' : (icon ?? 'play')} size={14} className={cx('mt-0.5 shrink-0', !danger && 'text-tertiary')} />
      <span className="min-w-0 flex-1">
        <span className="block font-medium">{children}</span>
        {note && <span className="mt-0.5 block text-caption text-tertiary">{note}</span>}
      </span>
    </button>
  )
}
