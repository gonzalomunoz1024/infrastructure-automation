import { useId, useState, type ReactNode } from 'react'
import { Button, Icon, Input, Modal, cx, useOverlayClose, type IconName } from '@iaf/ui'

/*
 * Atlas's ConfirmDialog (platform c9529d5), forked so its type-to-confirm input is named by its
 * label: Atlas's label is not tied to the input, so a screen reader announced an unnamed field
 * and axe reports it. Atlas stays verbatim (README decision 1); a note for P-14.
 */

export interface ConfirmDialogProps {
  title: string
  message?: ReactNode
  confirmLabel?: string
  cancelLabel?: string
  /** warning: outlined amber confirm · danger: filled red confirm */
  tone?: 'warning' | 'danger'
  icon?: IconName
  /** the user must type this exact text to enable the confirm button */
  requireText?: string
  /** may return a promise; the dialog shows a spinner and closes when it resolves */
  onConfirm: () => void | Promise<void>
  onClose: () => void
  children?: ReactNode
}

function Body({ title, message, confirmLabel, cancelLabel, tone, icon, requireText, onConfirm, children }: Omit<ConfirmDialogProps, 'onClose'>) {
  const close = useOverlayClose()
  const [typed, setTyped] = useState('')
  const inputId = useId()
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const ready = !requireText || typed === requireText

  const confirm = async () => {
    if (!ready || busy) return
    setBusy(true)
    setError(null)
    try {
      await onConfirm()
      close()
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
      setBusy(false)
    }
  }

  return (
    <>
      <div className="flex items-start gap-4 p-5">
        <span
          className={cx(
            'flex h-10 w-10 shrink-0 items-center justify-center rounded-md',
            tone === 'danger' ? 'bg-critical-tint text-critical' : 'bg-warning-tint text-warning',
          )}
        >
          <Icon name={icon ?? (tone === 'danger' ? 'trash' : 'alert-triangle')} size={20} />
        </span>
        <div className="min-w-0 flex-1">
          <h2 className="text-title3 font-semibold text-primary">{title}</h2>
          {message && <div className="mt-1 text-sm leading-relaxed text-secondary">{message}</div>}
          {children && <div className="mt-3">{children}</div>}
          {requireText && (
            <div className="mt-4">
              <label htmlFor={inputId} className="mb-1.5 block text-caption text-secondary">
                Type <span className="font-mono font-medium text-primary">{requireText}</span> to confirm
              </label>
              <Input
                id={inputId}
                mono
                value={typed}
                onChange={(e) => setTyped(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === 'Enter') void confirm()
                }}
                autoComplete="off"
                spellCheck={false}
                data-autofocus
              />
            </div>
          )}
          {error && (
            <p className="mt-3 text-caption text-critical" role="alert">
              {error}
            </p>
          )}
        </div>
      </div>
      <div className="flex items-center justify-end gap-2 border-t border-stroke-light px-5 py-3">
        <Button onClick={close} disabled={busy} data-autofocus={requireText ? undefined : true}>
          {cancelLabel}
        </Button>
        <Button variant={tone === 'danger' ? 'danger' : 'warning-outline'} onClick={() => void confirm()} disabled={!ready} loading={busy}>
          {confirmLabel}
        </Button>
      </div>
    </>
  )
}

/** Modal confirmation for anything irreversible: cancel, retire, delete, roll back. */
export function ConfirmDialog({ onClose, tone = 'warning', confirmLabel = 'Confirm', cancelLabel = 'Cancel', ...rest }: ConfirmDialogProps) {
  return (
    <Modal onClose={onClose} width="max-w-md" raised label={rest.title}>
      <Body tone={tone} confirmLabel={confirmLabel} cancelLabel={cancelLabel} {...rest} />
    </Modal>
  )
}
