import { useId, useState } from 'react'
import { Button, Drawer, Field, Icon, OverlayBody, OverlayFooter, OverlayHeader, SkeletonRows, Textarea, cx, useOverlayClose, useToast } from '@iaf/ui'
import { ConfirmDialog, displayWfrn } from '@iaf/console-kit'
import { useConsole } from '../../app/console'
import { FailureNote } from '../../app/commands'
import { ActionButton, ActionMenuItem, useRefusal } from '../../app/Refusal'
import { Link, navigate, resourcePath } from '../../app/router'
import { formatAccountId } from '../../lib/accounts'
import { isApiError, stepUpReturn } from '../../lib/api'
import { beginSignIn, bff, switchAccount } from '../../lib/bff'
import { singularOf, type ConsoleType } from '../../lib/descriptors'
import { denialTitle } from '../../lib/permissions'
import { explain, type Explanation } from '../../lib/plain'
import { invalidate, useQuery } from '../../lib/queries'
import { MOCK } from '../../lib/session'
import { useSubmissionKey } from '../../lib/submission'
import { TRANSFER_CODES, WHAT_MOVES, WHAT_STAYS, accountOfWfrn, readTransferDraft, transferApi, transferCodeOf, writeTransferDraft, type MovedTo, type TransferInput } from '../../lib/transfer'

/*
 * Move to another account (WP 7.7), from the resource page's Actions (`?action=transfer`): the
 * accounts the person works in, each offered only where they may create the type; why it moves;
 * what goes with it and what does not; the resource's name typed to confirm; then a fresh
 * sign-in when the BFF asks for one. The form is kept in sessionStorage across that sign-in, so
 * the drawer opens again filled in. Once moved, the session switches to the other account and the
 * page is the resource under its new wfrn.
 */

export interface TransferDrawerProps {
  type: ConsoleType
  wfrn: string
  name: string
  onClose: () => void
}

function CancelButton() {
  const close = useOverlayClose()
  return <Button onClick={close}>Cancel</Button>
}

/** A refusal of the move in the console's words: its own codes first, then the general explainer. */
export function explainTransfer(e: unknown): Explanation {
  const code = transferCodeOf(e)
  const words = code ? TRANSFER_CODES[code] : undefined
  const base = explain(e, 'Move this resource')
  return words ? { ...base, ...words, code: code ?? base.code, retryable: false } : base
}

export function TransferDrawer({ type, wfrn, name, onClose }: TransferDrawerProps) {
  const { account, session } = useConsole()
  const refusal = useRefusal()
  const toast = useToast()
  const keyFor = useSubmissionKey()
  const noun = singularOf(type.title).toLowerCase()
  const options = useQuery(['console-transfer-targets', type.name, wfrn], () => transferApi.targets(type.name, wfrn))
  const kept = readTransferDraft(wfrn)
  const [to, setTo] = useState(kept?.toAccountId ?? '')
  const [reason, setReason] = useState(kept?.reason ?? '')
  const [confirming, setConfirming] = useState(false)
  const [failure, setFailure] = useState<Explanation | null>(null)
  const [stepUp, setStepUp] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [tried, setTried] = useState(false)
  const legendId = useId()

  const others = (options.data?.targets ?? []).filter((t) => !t.current)
  const choice = others.find((t) => t.accountId === to && t.allowed)
  const source = options.data?.source ?? { allowed: true }
  const reasonMissing = !reason.trim()

  const submit = () => {
    setTried(true)
    if (!choice || reasonMissing) return
    setConfirming(true)
  }

  const send = async () => {
    if (!choice) return
    const body: TransferInput = { toAccountId: choice.accountId, reason: reason.trim(), confirm: true }
    // kept for the way back from a fresh sign-in, which live mode takes through the identity provider
    writeTransferDraft(wfrn, { toAccountId: choice.accountId, reason })
    setBusy(true)
    setFailure(null)
    setStepUp(null)
    try {
      const moved = await transferApi.move(type.name, wfrn, body, keyFor(body))
      writeTransferDraft(wfrn, null)
      const there = moved.accountId ?? accountOfWfrn(moved.wfrn) ?? choice.accountId
      for (const prefix of ['resources', 'console-resources', 'console-resource-counts', `console-resource/${type.name}`, 'console-transfer-targets']) invalidate(prefix)
      toast.success(`Moved to ${choice.name}`, `${name} is now in ${choice.name}. Its old link points here.`)
      await switchAccount(there).catch(() => undefined)
      navigate(resourcePath(type.service, type.name, moved.wfrn))
    } catch (e) {
      if (isApiError(e) && e.stepUp) setStepUp(e.stepUp)
      else if (!refusal.showError(e, `Move ${name} to another account`)) setFailure(explainTransfer(e))
    } finally {
      setBusy(false)
    }
  }

  const signInAgain = async () => {
    if (!stepUp) return
    if (!MOCK) {
      window.location.assign(stepUpReturn(stepUp, window.location.hash))
      return
    }
    // the mock's identity provider signs the same person in again, in place
    await beginSignIn(window.location.hash.replace(/^#/, '') || '/', session.user.userId)
    setStepUp(null)
  }

  return (
    <>
      <Drawer onClose={onClose} width="max-w-xl" label={`Move to another account: ${name}`}>
        <OverlayHeader
          title="Move to another account"
          icon="arrow-right"
          subtitle={
            <span className="flex min-w-0 flex-wrap items-center gap-x-1.5">
              <span className="font-medium text-primary">{name}</span>
              <span className="truncate font-mono text-caption text-tertiary" title={wfrn}>
                {displayWfrn(wfrn, 36)}
              </span>
            </span>
          }
        />
        <OverlayBody>
          <p className="mb-5 text-sm text-secondary">
            Moves this {noun} out of {account.name} into another account you work in. It keeps running while it moves.
          </p>
          {options.loading ? (
            <SkeletonRows n={3} rowClassName="h-12" />
          ) : options.error ? (
            <FailureNote failure={explain(options.error, 'Read the accounts you work in')} compact onRetry={() => void options.refetch()} />
          ) : (
            <form
              id="transfer-form"
              noValidate
              onSubmit={(e) => {
                e.preventDefault()
                submit()
              }}
            >
              <fieldset aria-labelledby={legendId} className="mb-5" data-transfer-targets={others.length}>
                <legend id={legendId} className="mb-2 text-sm font-medium text-primary">
                  Move it to
                </legend>
                {others.length === 0 ? (
                  <p className="rounded-lg border border-dashed border-stroke-light px-3 py-2.5 text-caption text-secondary">You work in no other account, so there is nowhere to move it. An admin of another account can add you there first.</p>
                ) : (
                  <ul className="space-y-2">
                    {others.map((t) => {
                      const id = `transfer-to-${t.accountId}`
                      const why = t.allowed ? undefined : t.denied ? `${denialTitle(t.denied)} there.` : 'You cannot create it there.'
                      return (
                        <li key={t.accountId}>
                          <label htmlFor={id} className={cx('flex min-h-12 items-start gap-3 rounded-lg border px-3 py-2.5', t.allowed ? 'cursor-pointer border-stroke-light hover:border-stroke' : 'cursor-not-allowed border-stroke-light bg-surface-secondary', to === t.accountId && t.allowed && 'border-accent ring-1 ring-accent')} data-target={t.accountId} data-allowed={t.allowed}>
                            <input id={id} type="radio" name="transfer-target" value={t.accountId} checked={to === t.accountId} disabled={!t.allowed} onChange={() => setTo(t.accountId)} className="mt-1 size-4 accent-[var(--color-accent,#0066cc)]" aria-describedby={why ? `${id}-why` : undefined} />
                            <span className="min-w-0 flex-1">
                              <span className="block text-sm font-medium text-primary">{t.name}</span>
                              <span className="block font-mono text-caption text-secondary">{formatAccountId(t.accountId)}</span>
                              {why && (
                                <span id={`${id}-why`} className="mt-0.5 flex items-center gap-1 text-caption text-secondary">
                                  <Icon name="lock" size={12} /> {why}
                                </span>
                              )}
                            </span>
                          </label>
                        </li>
                      )
                    })}
                  </ul>
                )}
                {tried && !choice && others.some((t) => t.allowed) && (
                  <p className="mt-2 text-caption text-critical" role="alert">
                    Pick the account to move it to.
                  </p>
                )}
              </fieldset>
              <Field label="Why it moves" htmlFor="transfer-reason" hint="Kept with the resource’s history in both accounts. At most 500 characters." error={tried && reasonMissing ? 'Say why it moves.' : undefined} required>
                <Textarea id="transfer-reason" rows={2} maxLength={500} value={reason} onChange={(e) => setReason(e.target.value)} invalid={tried && reasonMissing} />
              </Field>
            </form>
          )}

          <section aria-labelledby="transfer-moves" className="mt-6 grid gap-4 sm:grid-cols-2">
            <div>
              <h3 id="transfer-moves" className="mb-1.5 text-sm font-semibold text-primary">
                What moves
              </h3>
              <ul className="list-disc space-y-1 pl-5 text-caption text-secondary">
                {WHAT_MOVES.map((line) => (
                  <li key={line}>{line}</li>
                ))}
              </ul>
            </div>
            <div>
              <h3 className="mb-1.5 text-sm font-semibold text-primary">What does not</h3>
              <ul className="list-disc space-y-1 pl-5 text-caption text-secondary">
                {WHAT_STAYS.map((line) => (
                  <li key={line}>{line}</li>
                ))}
              </ul>
            </div>
          </section>

          {stepUp && (
            <div role="alert" className="mt-5 rounded-lg border border-accent/40 bg-accent-tint/40 p-4 text-sm" data-step-up="true">
              <p className="font-semibold text-primary">Sign in again to move it</p>
              <p className="mt-1 text-secondary">Moving a resource needs a fresh sign-in. What you filled in here is kept; confirm the move again once you are back.</p>
              <Button className="mt-3" variant="primary" icon="key" onClick={() => void signInAgain()}>
                Sign in again
              </Button>
            </div>
          )}
          <FailureNote failure={failure} onDismiss={() => setFailure(null)} className="mt-5" />
        </OverlayBody>
        <OverlayFooter>
          <CancelButton />
          <ActionButton decision={source} what={`Move ${name} to another account`} verb="transfer" variant="primary" icon="arrow-right" loading={busy} disabled={!others.some((t) => t.allowed)} onClick={submit}>
            {choice ? `Move to ${choice.name}` : 'Move'}
          </ActionButton>
        </OverlayFooter>
      </Drawer>
      {confirming && choice && (
        <ConfirmDialog
          title={`Move ${name} to ${choice.name}?`}
          tone="danger"
          icon="arrow-right"
          requireText={name}
          confirmLabel="Move it"
          cancelLabel="Keep it here"
          message={
            <>
              <span className="font-medium text-primary">{name}</span> leaves {account.name} and gets a new wfrn in {choice.name}. Who may use it, and who pays for it, follows {choice.name} from then on. Moving it back is another move.
            </>
          }
          onConfirm={send}
          onClose={() => setConfirming(false)}
        />
      )}
    </>
  )
}

/** The page of a moved resource's old wfrn: where it went, with a link to it there. */
export function MovedNotice({ type, name, moved, className }: { type: ConsoleType; name: string; moved: MovedTo; className?: string }) {
  const { session } = useConsole()
  const accounts = useQuery(['accounts', session.user.userId], () => bff.accounts())
  const where = moved.accountName ?? accounts.data?.items.find((a) => a.accountId === moved.accountId)?.name ?? formatAccountId(moved.accountId)
  const member = accounts.data?.items.some((a) => a.accountId === moved.accountId) ?? true
  // the link is built from the new wfrn's own parts, never taken from the answer as a URL
  const to = resourcePath(type.service, type.name, moved.transferredTo)
  return (
    <div className={cx('rounded-lg border border-stroke-light bg-surface-secondary px-4 py-4 text-sm', className)} data-moved-to={moved.accountId}>
      <div className="flex items-start gap-3">
        <Icon name="arrow-right" size={18} className="mt-0.5 shrink-0 text-accent" />
        <div className="min-w-0">
          <h2 className="text-title3 font-semibold text-primary">This resource moved to {where}</h2>
          <p className="mt-1 text-secondary">
            {name} was moved to another account{moved.at ? ` on ${new Date(moved.at).toLocaleDateString(undefined, { day: 'numeric', month: 'short', year: 'numeric' })}` : ''}. It has a new wfrn there; this link only points to it.
          </p>
          {member ? (
            <Link to={`/account/${moved.accountId}${to}`} className="mt-3 inline-flex min-h-11 items-center gap-1.5 font-medium text-accent transition-opacity hover:opacity-70">
              Open it in {where}
              <Icon name="arrow-right" size={14} />
            </Link>
          ) : (
            <p className="mt-3 text-secondary">You do not work in {where}, so you cannot open it there. Its admins can add you.</p>
          )}
        </div>
      </div>
    </div>
  )
}

/** The Actions menu's item: the source's decision as the BFF answers it, read when the menu opens. */
export function TransferMenuItem({ type, wfrn, unavailable, onSelect }: { type: ConsoleType; wfrn: string; unavailable?: string; onSelect: () => void }) {
  const { decide } = useConsole()
  const options = useQuery(['console-transfer-targets', type.name, wfrn], () => transferApi.targets(type.name, wfrn))
  const decision = options.data?.source ?? decide(type, 'transfer')
  return (
    <ActionMenuItem decision={decision} what="Move to another account" verb="transfer" icon="arrow-right" description="Into another account you work in" unavailable={unavailable} onSelect={onSelect}>
      Move to another account
    </ActionMenuItem>
  )
}
