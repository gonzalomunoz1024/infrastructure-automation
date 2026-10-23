import { useCallback, useState, type ReactNode } from 'react'
import { Button, EmptyState, Icon, cx, useToast } from '@iaf/ui'
import { Mono, ProblemList, type Problem } from '@iaf/console-kit'
import { invalidate } from '../lib/queries'
import { explain, type Explanation } from '../lib/plain'
import { useRefusal } from './Refusal'

/*
 * The one way a page runs a change through the BFF. A 403 with a Denial opens the refusal
 * dialog, which names the layer and the statement that decided. A 400 or a policy refusal
 * hands its problems back to the form. Anything else becomes an explanation in plain words,
 * kept for the page to show and, unless the page asks for quiet, said once as a toast. On
 * success the queries the change touched are read again.
 */

export interface CommandOptions<T> {
  /** query key prefixes to read again after success */
  invalidates?: string[]
  /** the toast on success */
  done?: (result: T) => { title: string; message?: ReactNode } | undefined
  /** the problems a 400 or a policy refusal named, for the form to show by field */
  onProblems?: (problems: Problem[]) => void
  /** no toast on failure: the page shows the explanation itself */
  quiet?: boolean
}

export interface CommandApi {
  run: <T>(what: string, fn: () => Promise<T>, opts?: CommandOptions<T>) => Promise<T | undefined>
  /** the last failure, explained; null after a success or clear() */
  failure: Explanation | null
  clear: () => void
  busy: boolean
}

export function useCommand(): CommandApi {
  const refusal = useRefusal()
  const toast = useToast()
  const [failure, setFailure] = useState<Explanation | null>(null)
  const [busy, setBusy] = useState(false)
  const run = useCallback(
    async <T,>(what: string, fn: () => Promise<T>, opts: CommandOptions<T> = {}): Promise<T | undefined> => {
      setBusy(true)
      setFailure(null)
      try {
        const result = await fn()
        for (const prefix of opts.invalidates ?? []) invalidate(prefix)
        const done = opts.done?.(result)
        if (done) toast.success(done.title, done.message)
        return result
      } catch (e) {
        if (refusal.showError(e, what)) return undefined
        const x = explain(e, what)
        if (x.problems?.length) opts.onProblems?.(x.problems)
        setFailure(x)
        if (!opts.quiet) toast.error(x.title, [x.message, x.next].filter(Boolean).join(' '))
        return undefined
      } finally {
        setBusy(false)
      }
    },
    [refusal, toast],
  )
  const clear = useCallback(() => setFailure(null), [])
  return { run, failure, clear, busy }
}

export interface FailureNoteProps {
  failure: Explanation | null
  onRetry?: () => void
  onDismiss?: () => void
  /** the fields a form already shows are left out of the list */
  shownFields?: string[]
  compact?: boolean
  className?: string
}

/** What went wrong, in words: the title, why, what to do next, and the id to quote. */
export function FailureNote({ failure, onRetry, onDismiss, shownFields = [], compact, className }: FailureNoteProps) {
  if (!failure) return null
  const problems = (failure.problems ?? []).filter((p) => {
    const root = p.field?.replace(/^\/?(input\/)?/, '').split(/[./]/)[0]
    return !root || !shownFields.includes(root)
  })
  return (
    <div role="alert" className={cx('rounded-lg border border-warning/40 bg-warning-tint/40 text-sm', compact ? 'p-3' : 'p-4', className)} data-failure={failure.code ?? failure.title}>
      <div className="flex items-start gap-3">
        <Icon name="alert-triangle" size={18} className="mt-0.5 shrink-0 text-warning" />
        <div className="min-w-0 flex-1 space-y-1">
          <p className="font-semibold text-primary">{failure.title}</p>
          {failure.message && failure.message !== failure.title && <p className="text-secondary">{failure.message}</p>}
          {failure.next && <p className="text-primary">{failure.next}</p>}
          {problems.length > 0 && <ProblemList problems={problems} compact className="mt-2" />}
          {(failure.code || failure.correlationId) && (
            <p className="flex flex-wrap gap-x-3 pt-1 text-caption text-tertiary">
              {failure.code && (
                <span>
                  Code <Mono>{failure.code}</Mono>
                </span>
              )}
              {failure.correlationId && (
                <span>
                  Correlation id <Mono>{failure.correlationId}</Mono>
                </span>
              )}
            </p>
          )}
        </div>
        <div className="flex shrink-0 items-center gap-1">
          {onRetry && failure.retryable && (
            <Button size="sm" icon="refresh" onClick={onRetry}>
              Try again
            </Button>
          )}
          {onDismiss && (
            <Button size="sm" variant="ghost" onClick={onDismiss} aria-label="Dismiss">
              Dismiss
            </Button>
          )}
        </div>
      </div>
    </div>
  )
}

export interface ExplainedErrorProps {
  error: unknown
  /** what the page was doing, in the words of its title: "Load this virtual machine" */
  what?: string
  onRetry?: () => void
  compact?: boolean
  className?: string
}

/** A failed read as an empty state in plain words: what happened, what to do next, the id to quote. */
export function ExplainedError({ error, what, onRetry, compact, className }: ExplainedErrorProps) {
  const x = explain(error, what)
  const notFound = x.code === 'resource.not-found' || x.title === 'Not found'
  return (
    <EmptyState
      compact={compact}
      className={className}
      icon={notFound ? 'search' : 'alert-triangle'}
      iconClassName={notFound ? undefined : 'text-warning'}
      title={x.title}
      message={
        <>
          {x.message}
          {x.next && x.next !== x.message && <> {x.next}</>}
          {x.correlationId && <span className="mt-1 block font-mono text-caption2">Correlation id {x.correlationId}</span>}
        </>
      }
      action={
        onRetry && x.retryable ? (
          <Button size="sm" icon="refresh" onClick={onRetry}>
            Try again
          </Button>
        ) : undefined
      }
    />
  )
}
