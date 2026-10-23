import { useState, type ReactNode } from 'react'
import { Button, Field, Icon, Textarea, cx } from '@iaf/ui'
import { ConfirmDialog } from '@iaf/console-kit'
import { useConsole } from '../../app/console'
import { BrandMark } from '../../app/BrandMark'
import { Link, pathFor } from '../../app/router'

/*
 * The pieces the phone view (WP 9.18) shares: its own top bar (the person, the account and the
 * way back to the full console), and the approve or reject block with a comment, its buttons at
 * least 48 px tall. A rejection asks once, as the request page's does.
 */

export function MobileBar({ title, back }: { title: string; back?: { to: string; label: string } }) {
  const { account, session } = useConsole()
  return (
    <header className="sticky top-0 z-10 border-b border-stroke-light bg-surface/95 px-4 pb-2 pt-[max(0.5rem,env(safe-area-inset-top))] backdrop-blur">
      <div className="flex min-h-12 items-center gap-3">
        {back ? (
          <Link to={back.to} className="-ml-2 flex size-12 shrink-0 items-center justify-center rounded-full text-accent hover:bg-surface-secondary" aria-label={back.label}>
            <Icon name="chevron-left" size={22} />
          </Link>
        ) : (
          <BrandMark />
        )}
        <div className="min-w-0 flex-1">
          <h1 className="truncate text-title3 font-semibold text-primary">{title}</h1>
          <p className="truncate text-caption text-secondary">
            {session.user.name} · {account.name}
          </p>
        </div>
        <Link to={pathFor('home')} className="flex min-h-12 shrink-0 items-center rounded-md px-2 text-sm font-medium text-accent hover:bg-surface-secondary">
          Full console
        </Link>
      </div>
    </header>
  )
}

export interface DecideBlockProps {
  /** names the comment field and the buttons for a screen reader: "Restart virtual machine vm-gen-004" */
  subject: string
  idPrefix: string
  busy?: boolean
  rejectMessage: string
  onDecide: (decision: 'approve' | 'reject', comment: string | undefined) => Promise<unknown>
}

export function DecideBlock({ subject, idPrefix, busy, rejectMessage, onDecide }: DecideBlockProps) {
  const [comment, setComment] = useState('')
  const [rejecting, setRejecting] = useState(false)
  const said = comment.trim() || undefined
  return (
    <div className="mt-3 border-t border-stroke-light pt-3">
      <Field label="Comment" htmlFor={`${idPrefix}-comment`} hint="Optional. It stays with the request.">
        <Textarea id={`${idPrefix}-comment`} rows={2} maxLength={1000} value={comment} onChange={(e) => setComment(e.target.value)} className="text-base" />
      </Field>
      <div className="mt-3 grid grid-cols-2 gap-2">
        <Button variant="primary" icon="check" loading={busy} className="h-12 justify-center text-base" onClick={() => void onDecide('approve', said)} aria-label={`Approve: ${subject}`}>
          Approve
        </Button>
        <Button variant="danger" icon="close" disabled={busy} className="h-12 justify-center text-base" onClick={() => setRejecting(true)} aria-label={`Reject: ${subject}`}>
          Reject
        </Button>
      </div>
      {rejecting && (
        <ConfirmDialog
          title="Reject this?"
          tone="danger"
          icon="close"
          confirmLabel="Reject"
          cancelLabel="Keep it waiting"
          message={rejectMessage}
          onConfirm={async () => {
            await onDecide('reject', said)
          }}
          onClose={() => setRejecting(false)}
        />
      )}
    </div>
  )
}

export function MobileCard({ children, className, ...rest }: { children: ReactNode; className?: string } & Record<`data-${string}`, string | number | boolean | undefined>) {
  return (
    <li className={cx('rounded-xl border border-stroke-light bg-surface p-4 shadow-sm', className)} {...rest}>
      {children}
    </li>
  )
}
