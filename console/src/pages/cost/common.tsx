import type { ReactNode } from 'react'
import { Button, EmptyState, Icon, cx } from '@iaf/ui'
import { useConsole } from '../../app/console'
import { useRefusal } from '../../app/Refusal'
import { isApiError } from '../../lib/api'
import { bff } from '../../lib/bff'
import { denialOf, denialTitle } from '../../lib/permissions'
import { useQuery } from '../../lib/queries'
import { formatAccountId } from '../../lib/accounts'

/*
 * What the cost pages share: the line that says every amount is an estimate from list prices,
 * the state a page shows when the person may not read what it holds (with the refusal behind a
 * button, as everywhere in the console), a small caption above a filter control, and the names
 * of the accounts an organization's report left out.
 */

/** The words every amount on these pages stands under. */
export const ESTIMATES = 'Estimates from list prices, not invoices.'

/** A quiet line that says where the amounts come from. */
export function EstimatesNote({ className, children }: { className?: string; children?: ReactNode }) {
  return (
    <p className={cx('flex items-start gap-1.5 text-caption text-secondary', className)}>
      <Icon name="info" size={13} className="mt-px shrink-0 text-tertiary" />
      <span>
        {ESTIMATES}
        {children && <> {children}</>}
      </span>
    </p>
  )
}

/** A refused read: what was refused, and the refusal itself one button away. */
export function RefusedState({ error, what, title, compact, className }: { error: unknown; what: string; title: string; compact?: boolean; className?: string }) {
  const refusal = useRefusal()
  const denied = denialOf(error)
  return (
    <EmptyState
      icon="lock"
      title={title}
      message={denied ? `${denialTitle(denied)}.` : 'Your role does not allow it.'}
      compact={compact}
      className={className}
      action={
        denied && (
          <Button size="sm" icon="info" onClick={() => refusal.show({ denied, what, correlationId: isApiError(error) ? error.correlationId : undefined })}>
            See why
          </Button>
        )
      }
    />
  )
}

/** The call was refused for the person's role (a 403 that carries a Denial). */
export function isRefused(error: unknown): boolean {
  return isApiError(error) && error.status === 403 && !!denialOf(error)
}

/** A filter control with its caption above it; the control's own name says the caption and its value. */
export function Control({ label, htmlFor, children, className }: { label: string; htmlFor?: string; children: ReactNode; className?: string }) {
  return (
    <div className={cx('flex min-w-0 flex-col gap-1', className)}>
      {htmlFor ? (
        <label htmlFor={htmlFor} className="text-caption2 font-medium text-secondary">
          {label}
        </label>
      ) : (
        <span aria-hidden="true" className="text-caption2 font-medium text-secondary">
          {label}
        </span>
      )}
      {children}
    </div>
  )
}

/** The names of the accounts the person belongs to, for the ones a report left out. */
export function useAccountNames(enabled: boolean): (accountId: string) => string {
  const { session } = useConsole()
  const accounts = useQuery(['accounts', session.user.userId], () => bff.accounts(), { enabled })
  return (accountId) => {
    const a = accounts.data?.items.find((x) => x.accountId === accountId)
    return a ? `${a.name} (${formatAccountId(accountId)})` : formatAccountId(accountId)
  }
}

/** The accounts an organization's report left out, and why. */
export function SkippedAccounts({ ids, action, className }: { ids: readonly string[] | undefined; action: string; className?: string }) {
  const name = useAccountNames(!!ids?.length)
  if (!ids?.length) return null
  return (
    <p className={cx('flex items-start gap-1.5 text-caption text-secondary', className)} role="note">
      <Icon name="alert-triangle" size={13} className="mt-px shrink-0 text-warning" />
      <span>
        Left out: {ids.map(name).join(', ')}. Your role there does not allow <span className="font-mono">{action}</span>.
      </span>
    </p>
  )
}
