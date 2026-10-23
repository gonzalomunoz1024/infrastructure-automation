import { useState } from 'react'
import { Button, Card, Icon, SkeletonText, cx } from '@iaf/ui'
import { ConfirmDialog, DotTag, When } from '@iaf/console-kit'
import { useCopilot } from '../../app/copilot/context'
import { ActionButton } from '../../app/Refusal'
import { Link, pathFor } from '../../app/router'
import { isApiError } from '../../lib/api'
import { capitalise } from '../../lib/descriptors'
import { explainApi, linkOf, retryBodyOf, type FixConfidence } from '../../lib/explain'
import type { Decision } from '../../lib/permissions'
import { useQuery } from '../../lib/queries'
import type { RetryFix } from '../../lib/types'

/*
 * What went wrong with a request, and the fix to try (WP 9.2): the cause in plain words, the step
 * that stopped, a suggested fix with how sure it is, and how often the same failure happened in
 * the account lately. "Retry with fix" hands the fix's input merge and step to the request page's
 * own retry (phase 4), after the person has read exactly what will change; a fix that only runs
 * the failed step again leaves that to the page's Retry, so the page never offers the same retry
 * twice. Without an explanation the card shows the platform's own words.
 */

const EXPLAINED = new Set(['failed', 'needs-attention'])
const TONE: Record<FixConfidence, 'healthy' | 'warning' | 'neutral'> = { high: 'healthy', medium: 'warning', low: 'neutral' }

/** The explanation of a job that failed or waits on a failed step; nothing is asked for any other. A new `since` (a second pause) asks again. */
export function useJobExplanation(jobId: string, status: string | undefined, since?: string) {
  return useQuery(['job-explanation', jobId, status ?? '', since ?? ''], () => explainApi.job(jobId), { enabled: !!status && EXPLAINED.has(status) })
}

const shown = (v: unknown) => (typeof v === 'string' ? v : JSON.stringify(v))

export interface FailureExplainerProps {
  jobId: string
  /** the job's status: the card shows for a failed job and one waiting on a failed step */
  status: string
  /** when the job paused or ended, so a second pause on the same step is explained afresh */
  since?: string
  /** runs the page's own retry with the fix (phase 4); until a page gives it, the retry waits for phase 4 */
  onRetry?: (fix: RetryFix | undefined) => unknown
  /** the page's retry is running */
  busy?: boolean
  /** the step the page's own retry runs from: a fix that changes nothing beyond it leaves the retry to the page */
  defaultStep?: string
  /** the platform's own words, shown when no explanation can be had */
  fallback?: { errorClass?: string; message?: string; step?: string }
  className?: string
}

export function FailureExplainer({ jobId, status, since, onRetry, busy, defaultStep, fallback, className }: FailureExplainerProps) {
  const q = useJobExplanation(jobId, status, since)
  const copilot = useCopilot()
  const [confirming, setConfirming] = useState(false)
  // only a failed job, or one waiting on a failed step, has an explanation; another with an error keeps the platform's words
  if (!EXPLAINED.has(status) && !fallback?.message) return null
  // a 409 means the job moved on (someone retried it) between the page's read and this one
  if (isApiError(q.error) && q.error.status === 409) return null

  const x = q.data
  const attention = status === 'needs-attention'
  const step = x?.failedStep?.id ?? fallback?.step
  const body = retryBodyOf(x?.fix, x?.source)
  const decision: Decision = x?.retry ? { allowed: x.retry.allowed, denied: x.retry.denied } : { allowed: true }
  // the page's own retry runs the failed step again as it was; the card offers its own only for a fix that changes the input or the step
  const changes = !!body?.input || (!!body?.fromStep && body.fromStep !== defaultStep)
  const offers = !!body && (!onRetry || changes)
  const label = body?.input ? 'Retry with fix' : onRetry && body?.fromStep ? `Retry from ${body.fromStep}` : attention ? 'Retry the step' : 'Retry'
  const ask = () => copilot.open({ intent: 'ask', message: `Why did request ${jobId} ${attention ? 'stop' : 'fail'}, and how do I fix it?`, context: { path: `/requests/${jobId}` } })

  // the page's retry keeps one key per pause, says how it went, and follows the request it starts
  const send = async () => {
    await onRetry?.(body)
  }

  return (
    <Card
      title="What went wrong"
      subtitle={step ? `In step ${step}` : undefined}
      actions={x ? <span className="whitespace-nowrap text-caption text-tertiary">{x.source === 'assistant' ? 'Explained by the copilot' : 'Explained by the rules'}</span> : undefined}
      className={className}
      data-explanation={x ? x.source : q.loading ? 'loading' : 'unavailable'}
    >
      <div aria-live="polite" aria-busy={q.loading}>
        {q.loading ? (
          <SkeletonText lines={3} />
        ) : (
          <div className="flex items-start gap-3 text-sm">
            <Icon name="alert-triangle" size={18} className={cx('mt-0.5 shrink-0', attention ? 'text-warning' : 'text-critical')} />
            <div className="min-w-0 space-y-1">
              <p className="font-semibold text-primary">{x?.cause.title ?? fallback?.message ?? 'The platform recorded a failure without a message.'}</p>
              {x?.cause.detail && <p className="text-secondary">{x.cause.detail}</p>}
              {!x && q.error !== undefined && q.error !== null && <p className="text-secondary">The explanation is not available right now; this is what the platform recorded.</p>}
              <p className="flex flex-wrap gap-x-3 pt-0.5 text-caption text-tertiary">
                {(x?.cause.class ?? fallback?.errorClass) && (
                  <span>
                    Class <span className="font-mono text-secondary">{x?.cause.class ?? fallback?.errorClass}</span>
                  </span>
                )}
                {x?.failedStep?.message && (
                  <span>
                    The step said <q className="text-secondary">{x.failedStep.message}</q>
                  </span>
                )}
                {x?.failedStep?.attempt !== undefined && x.failedStep.attempt > 1 && <span>After {x.failedStep.attempt} attempts</span>}
              </p>
            </div>
          </div>
        )}
      </div>

      {x?.fix && (
        <div className="mt-4 rounded-lg border border-stroke-light bg-surface-secondary p-4" data-fix-confidence={x.fix.confidence}>
          <div className="flex flex-wrap items-center justify-between gap-2">
            <h4 className="text-footnote font-semibold text-primary">Suggested fix</h4>
            <DotTag size="sm" tone={TONE[x.fix.confidence]}>
              {capitalise(x.fix.confidence)} confidence
            </DotTag>
          </div>
          <p className="mt-1 text-sm text-primary">{x.fix.summary}</p>
          {body?.input && (
            <dl className="mt-3 grid grid-cols-[auto_minmax(0,1fr)] gap-x-4 gap-y-1 text-caption" aria-label="What the retry changes">
              {Object.entries(body.input).map(([k, v]) => (
                <div key={k} className="contents">
                  <dt className="font-mono text-secondary">{k}</dt>
                  <dd className="break-all font-mono text-primary">{shown(v)}</dd>
                </div>
              ))}
            </dl>
          )}
          {body?.fromStep && (
            <p className="mt-2 text-caption text-secondary">
              Runs again from step <span className="font-mono text-primary">{body.fromStep}</span>; the steps before it are not repeated.
            </p>
          )}
          {!!x.links?.length && (
            <ul className="mt-3 space-y-1 text-sm">
              {x.links.map((l) => {
                const link = linkOf(l.url)
                if (!link) return null
                return (
                  <li key={l.url}>
                    <a href={link.href} {...(link.external ? { target: '_blank', rel: 'noreferrer noopener' } : {})} className="inline-flex items-center gap-1 font-medium text-accent transition-opacity hover:opacity-70">
                      {l.title}
                      {link.external && <Icon name="external" size={12} />}
                      {link.external && <span className="sr-only"> (opens in a new tab)</span>}
                    </a>
                  </li>
                )
              })}
            </ul>
          )}
          {onRetry && body && !changes && decision.allowed && (
            <p className="mt-3 text-caption text-secondary">{attention ? 'Retry the step, above, runs it this way.' : 'Retry, at the top of the page, runs it this way.'}</p>
          )}
          <div className="mt-4 flex flex-wrap gap-2">
            {offers && (
              <ActionButton decision={decision} what="Retry this request" phase={onRetry ? undefined : 4} variant="primary" icon="refresh" loading={busy} onClick={() => setConfirming(true)} verb="retry">
                {label}
              </ActionButton>
            )}
            <Button variant="ghost" icon="sparkles" onClick={ask}>
              Ask the copilot about this failure
            </Button>
          </div>
        </div>
      )}

      {x?.similar && (
        <p className="mt-4 text-caption text-secondary" data-similar={x.similar.count}>
          {x.similar.count === 0
            ? 'The first failure like this in this account in the last 30 days.'
            : `The same failure happened ${x.similar.count === 1 ? 'once more' : `${x.similar.count} more times`} in this account in the last 30 days.`}
          {x.similar.lastFixed?.jobId && (
            <>
              {' '}
              Last fixed{x.similar.lastFixed.by ? ` by ${x.similar.lastFixed.by}` : ''} {x.similar.lastFixed.at ? <When at={x.similar.lastFixed.at} /> : null} in{' '}
              <Link to={pathFor('request', { id: x.similar.lastFixed.jobId })} className="font-mono text-accent transition-opacity hover:opacity-70">
                {x.similar.lastFixed.jobId}
              </Link>
              .
            </>
          )}
        </p>
      )}

      {confirming && (
        <ConfirmDialog
          title={attention ? 'Retry the step that stopped?' : 'Run this request again?'}
          icon="refresh"
          tone="warning"
          confirmLabel={label}
          cancelLabel="Not now"
          message={
            <>
              {body?.input ? 'The request runs again with the changes above merged over its input' : 'The request runs again as it was'}
              {body?.fromStep ? `, from step ${body.fromStep}` : ''}. It is judged like the first time: the same policies and approvals apply.
            </>
          }
          onConfirm={send}
          onClose={() => setConfirming(false)}
        />
      )}
    </Card>
  )
}
