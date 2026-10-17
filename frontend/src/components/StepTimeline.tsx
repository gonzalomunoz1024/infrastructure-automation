import { useState } from 'react'
import { Icon, JsonView, StateTag, StatusDot, Tag, cx, duration, type DotKind } from '@iaf/ui'
import { pathFor } from '../app/router'
import type { JobStep, JobStepStatus, JobSummary } from '../lib/types'
import { TextLink, When } from './common'

/*
 * A job, step by step: a vertical list with a status dot per step, the kind, attempt and
 * duration at a glance, and the input, output or error of a step one click away. Child jobs of
 * `run` steps fold under their step with a link.
 */

const DOT: Record<JobStepStatus, { kind?: DotKind; varName?: string; pulse?: boolean }> = {
  pending: { kind: 'neutral' },
  running: { varName: '--color-state-running', pulse: true },
  waiting: { kind: 'warning', pulse: true },
  succeeded: { kind: 'healthy' },
  failed: { kind: 'critical' },
  skipped: { kind: 'neutral' },
  compensated: { kind: 'warning' },
}

const KIND_LABEL: Record<string, string> = {
  provider: 'provider',
  http: 'http',
  ansible: 'ansible',
  terraform: 'terraform',
  idp: 'idp tile',
  activity: 'activity',
  run: 'workflow',
  approval: 'approval',
  switch: 'switch',
  fork: 'fork',
  for: 'loop',
  try: 'try',
  wait: 'wait',
  set: 'set',
  raise: 'raise',
  use: 'use',
  register: 'register',
  notify: 'notify',
  script: 'script',
}

export interface StepTimelineProps {
  steps: JobStep[]
  /** child jobs (of `run` steps and batches) */
  children?: JobSummary[]
  /** the step to highlight (the one needing attention) */
  highlight?: string
  /** step ids open at first render */
  defaultOpen?: string[]
  className?: string
}

/** Pairs a `run` step with its child job: by an id in its output, else by order. */
function childOf(step: JobStep, runIndex: number, children: JobSummary[] | undefined): JobSummary | undefined {
  if (!children?.length) return undefined
  const out = step.output as Record<string, unknown> | undefined
  const wanted = out && typeof out === 'object' ? (out.jobId ?? out.childJobId ?? out.id) : undefined
  if (typeof wanted === 'string') {
    const hit = children.find((c) => c.id === wanted)
    if (hit) return hit
  }
  return step.type === 'run' ? children[runIndex] : undefined
}

export function StepTimeline({ steps, children, highlight, defaultOpen = [], className }: StepTimelineProps) {
  const [open, setOpen] = useState<Set<string>>(() => new Set(defaultOpen))
  const toggle = (id: string) =>
    setOpen((s) => {
      const next = new Set(s)
      if (next.has(id)) next.delete(id)
      else next.add(id)
      return next
    })

  if (steps.length === 0) {
    return <p className={cx('text-caption text-tertiary', className)}>No steps have started yet.</p>
  }

  let runIndex = 0
  return (
    <ol className={cx('relative', className)} aria-label="Steps">
      {steps.map((step, i) => {
        const dot = DOT[step.status] ?? DOT.pending
        const child = childOf(step, step.type === 'run' ? runIndex : -1, children)
        if (step.type === 'run') runIndex++
        const isOpen = open.has(step.id)
        const hasDetail = step.input !== undefined || step.output !== undefined || step.error !== undefined
        const last = i === steps.length - 1
        const highlighted = highlight === step.id
        return (
          <li key={step.id} className="relative flex gap-3">
            <div className="flex w-4 shrink-0 flex-col items-center">
              <div className="flex h-10 items-center">
                <StatusDot kind={dot.kind} varName={dot.varName} pulse={dot.pulse} />
              </div>
              {!last && <div className="w-px flex-1 bg-stroke-light" aria-hidden="true" />}
            </div>
            <div className={cx('min-w-0 flex-1', !last && 'pb-2')}>
              <button
                type="button"
                onClick={hasDetail ? () => toggle(step.id) : undefined}
                aria-expanded={hasDetail ? isOpen : undefined}
                disabled={!hasDetail}
                className={cx(
                  'flex w-full items-center gap-3 rounded-md px-2 py-2 text-left transition-colors disabled:cursor-default',
                  hasDetail && 'hover:bg-surface-secondary',
                  highlighted && 'bg-warning-tint',
                )}
              >
                <div className="min-w-0 flex-1">
                  <div className="flex flex-wrap items-center gap-x-2 gap-y-1">
                    <span className="text-sm font-medium text-primary">{step.name || step.id}</span>
                    <Tag size="sm" outline mono>
                      {KIND_LABEL[step.type] ?? step.type}
                    </Tag>
                    {step.attempt > 1 && (
                      <Tag size="sm" tone="warning">
                        attempt {step.attempt}
                      </Tag>
                    )}
                    {step.items !== undefined && (
                      <Tag size="sm">
                        {step.items} items
                      </Tag>
                    )}
                  </div>
                  <div className="mt-0.5 flex flex-wrap items-center gap-x-3 text-caption text-tertiary">
                    <span className="font-mono">{step.id}</span>
                    {step.startedAt && (
                      <span>
                        started <When at={step.startedAt} />
                      </span>
                    )}
                    {step.durationMs !== undefined && <span className="tabular-nums">{duration(step.durationMs)}</span>}
                    {step.error && <span className="truncate text-critical">{step.error.message}</span>}
                  </div>
                </div>
                <StateTag status={step.status} size="sm" />
                {hasDetail && <Icon name="chevron-down" size={14} className={cx('shrink-0 text-tertiary transition-transform duration-150', isOpen && 'rotate-180')} />}
              </button>
              {child && (
                <div className="flex flex-wrap items-center gap-2 px-2 pb-1 text-caption">
                  <Icon name="job" size={13} className="text-tertiary" />
                  <span className="text-secondary">Child job</span>
                  <TextLink to={pathFor('job', { id: child.id })} mono>
                    {child.id}
                  </TextLink>
                  <StateTag status={child.status} size="sm" />
                  <span className="text-tertiary">
                    {child.title} · {child.version}
                  </span>
                </div>
              )}

              {isOpen && (
                <div className="mt-1 space-y-3 rounded-md border border-stroke-light p-3">
                  {step.error && (
                    <div className="rounded-md bg-critical-tint px-3 py-2 text-caption">
                      <span className="font-mono font-medium text-critical">{step.error.class}</span>
                      <span className="ml-2 text-primary">{step.error.message}</span>
                      <span className="ml-2 text-tertiary">{step.error.retryable ? 'retryable' : 'not retryable'}</span>
                    </div>
                  )}
                  {step.input !== undefined && (
                    <div>
                      <div className="mb-1 text-caption font-medium text-secondary">Input</div>
                      <JsonView value={step.input} rawToggle={false} defaultExpanded={2} maxHeight="max-h-64" />
                    </div>
                  )}
                  {step.output !== undefined && (
                    <div>
                      <div className="mb-1 text-caption font-medium text-secondary">Output</div>
                      <JsonView value={step.output} rawToggle={false} defaultExpanded={2} maxHeight="max-h-64" />
                    </div>
                  )}
                </div>
              )}
            </div>
          </li>
        )
      })}
    </ol>
  )
}
