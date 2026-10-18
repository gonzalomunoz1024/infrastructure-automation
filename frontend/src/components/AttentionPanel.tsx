import { useState } from 'react'
import { Button, ConfirmDialog, Icon, Modal, OverlayBody, OverlayFooter, OverlayHeader, cx, useOverlayClose } from '@iaf/ui'
import type { Attention, JobStep } from '../lib/types'
import { JsonEditor, parseJsonText } from './JsonEditor'
import { When, countdown, deadlineOf, useNow } from './common'

/*
 * A paused run asks a person what to do: retry the step as it was, retry with an edited input,
 * skip it, or abort and roll back. Skip and abort confirm first; edit opens the step's input.
 */

export interface AttentionPanelProps {
  attention: Attention
  /** the step the attention is about, for its name and current input */
  step?: JobStep
  onRetry: () => Promise<void>
  onRetryWithInput: (input: Record<string, unknown>) => Promise<void>
  onSkip: () => Promise<void>
  onAbort: () => Promise<void>
  className?: string
}

export function AttentionPanel({ attention, step, onRetry, onRetryWithInput, onSkip, onAbort, className }: AttentionPanelProps) {
  const now = useNow(30_000)
  const [busy, setBusy] = useState<string | null>(null)
  const [confirm, setConfirm] = useState<'skip' | 'abort' | null>(null)
  const [editing, setEditing] = useState(false)
  const deadline = deadlineOf(attention.since, attention.timeout)
  const left = deadline ? countdown(deadline, now) : null
  const can = (o: Attention['options'][number]) => attention.options.includes(o)

  const run = async (name: string, fn: () => Promise<void>) => {
    setBusy(name)
    try {
      await fn()
    } finally {
      setBusy(null)
    }
  }

  return (
    <section className={cx('animate-pulse-once rounded-lg border border-warning/40 bg-warning-tint p-5', className)} aria-label="Needs attention">
      <div className="flex items-start gap-3">
        <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-md bg-surface text-warning shadow-sm">
          <Icon name="alert-triangle" size={19} />
        </span>
        <div className="min-w-0 flex-1">
          <h2 className="text-subheadline font-semibold text-primary">Needs attention</h2>
          <p className="mt-0.5 text-sm text-secondary">
            Step <span className="font-medium text-primary">{step?.name ?? attention.step}</span> stopped the run.
          </p>
          {attention.error && (
            <p className="mt-2 rounded-md bg-surface px-3 py-2 font-mono text-caption text-critical" data-testid="attention-error">
              <span className="font-medium">{attention.error.class}</span>
              <span className="ml-2 text-primary">{attention.error.message}</span>
              <span className="ml-2 text-tertiary">{attention.error.retryable ? 'retryable' : 'not retryable'}</span>
            </p>
          )}
          <p className="mt-2 text-caption text-tertiary">
            Waiting since <When at={attention.since} now={now} />
            {left && (
              <>
                {' · '}
                <span className={left.expired ? 'text-critical' : undefined}>{left.expired ? `the ${attention.timeout} timeout ${left.text}` : `${left.text} before it times out (${attention.timeout})`}</span>
              </>
            )}
          </p>
          <div className="mt-4 flex flex-wrap gap-2">
            {can('retry') && (
              <Button variant="primary" icon="refresh" loading={busy === 'retry'} disabled={!!busy} onClick={() => void run('retry', onRetry)}>
                Retry
              </Button>
            )}
            {can('edit-input') && (
              <Button icon="edit" disabled={!!busy} onClick={() => setEditing(true)}>
                Edit input
              </Button>
            )}
            {can('skip') && (
              <Button icon="chevron-right" disabled={!!busy} onClick={() => setConfirm('skip')}>
                Skip
              </Button>
            )}
            {can('abort') && (
              <Button variant="warning-outline" icon="stop" disabled={!!busy} onClick={() => setConfirm('abort')}>
                Abort
              </Button>
            )}
          </div>
        </div>
      </div>

      {confirm === 'skip' && (
        <ConfirmDialog
          title="Skip this step?"
          message={`The run continues after ${step?.name ?? attention.step} as if it had succeeded. Whatever it should have done stays undone.`}
          confirmLabel="Skip step"
          onConfirm={() => run('skip', onSkip)}
          onClose={() => setConfirm(null)}
        />
      )}
      {confirm === 'abort' && (
        <ConfirmDialog
          title="Abort and roll back?"
          tone="danger"
          icon="stop"
          message="The run stops here and every compensation step runs in reverse order. This cannot be undone."
          confirmLabel="Abort run"
          onConfirm={() => run('abort', onAbort)}
          onClose={() => setConfirm(null)}
        />
      )}
      {editing && (
        <EditInputModal
          stepName={step?.name ?? attention.step}
          initial={step?.input}
          onClose={() => setEditing(false)}
          onSubmit={async (input) => {
            await run('edit', () => onRetryWithInput(input))
            setEditing(false)
          }}
        />
      )}
    </section>
  )
}

function EditInputModal({ stepName, initial, onClose, onSubmit }: { stepName: string; initial: unknown; onClose: () => void; onSubmit: (input: Record<string, unknown>) => Promise<void> }) {
  const [text, setText] = useState(() => (initial === undefined ? '{\n  \n}' : JSON.stringify(initial, null, 2)))
  const parsed = parseJsonText(text)
  const valid = !parsed.error && !parsed.empty && typeof parsed.value === 'object' && parsed.value !== null && !Array.isArray(parsed.value)
  return (
    <Modal onClose={onClose} width="max-w-xl" label="Edit input and retry">
      <OverlayHeader title="Edit input and retry" subtitle={`The step ${stepName} runs again with this input.`} icon="edit" />
      <OverlayBody>
        <JsonEditor value={text} onChange={setText} rows={12} objectOnly label="Step input" hint="The with: block of the step, as JSON" />
      </OverlayBody>
      <OverlayFooter>
        <CloseButton />
        <Button variant="primary" icon="refresh" disabled={!valid} onClick={() => void onSubmit(parsed.value as Record<string, unknown>)}>
          Retry with this input
        </Button>
      </OverlayFooter>
    </Modal>
  )
}

function CloseButton() {
  const close = useOverlayClose()
  return <Button onClick={close}>Cancel</Button>
}
