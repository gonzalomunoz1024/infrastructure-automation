import { useState } from 'react'
import { Icon, JsonView, StateTag, StatusDot, Tag, cx, duration, type DotKind } from '@iaf/ui'
import { useConsoleKit } from './provider'
import type { JobStep, JobStepStatus, JobSummary } from './types'
import { TextLink, When } from './common'

/*
 * A job, step by step: a vertical list with a status dot per step, the kind, attempt and
 * duration at a glance, and the input, output or error of a step one click away. Child jobs of
 * `run` steps fold under their step with a link. Forked from the platform's
 * components/StepTimeline.tsx at c9529d5; job links come from the provider.
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

/** `image.create`: the build of the input `image` that a requester asked for with `{ create }`. */
export function buildOf(step: Pick<JobStep, 'id' | 'name'>): { input: string; type: string } | null {
  const m = /^(.+)\.create$/.exec(step.id)
  if (!m) return null
  // the interpreter names the step "Build <type>"
  const type = /^Build (.+)$/.exec(step.name ?? '')?.[1] ?? 'resource'
  return { input: m[1], type }
}

/** The child job id a run step's output carries. */
export function childJobIdOf(step: Pick<JobStep, 'output'>): string | undefined {
  const out = step.output
  if (!out || typeof out !== 'object') return undefined
  const o = out as Record<string, unknown>
  const id = o.jobId ?? o.childJobId
  return typeof id === 'string' ? id : undefined
}

/**
 * A run step whose child failed while the step itself went on (`onFailure: continue`): the child's
 * failure is the step's output, with its status and error.
 */
export function continuedAfterFailure(step: Pick<JobStep, 'type' | 'status' | 'output'>): { status: string; message?: string; errorClass?: string } | null {
  if (step.type !== 'run' || step.status !== 'succeeded') return null
  const out = step.output
  if (!out || typeof out !== 'object') return null
  const o = out as Record<string, unknown>
  if (typeof o.status !== 'string' || o.status === 'succeeded') return null
  const err = o.error && typeof o.error === 'object' ? (o.error as Record<string, unknown>) : null
  return { status: o.status, message: typeof err?.message === 'string' ? err.message : undefined, errorClass: typeof err?.class === 'string' ? err.class : undefined }
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
  const { links } = useConsoleKit()
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
        const build = step.type === 'run' ? buildOf(step) : null
        const childId = child?.id ?? childJobIdOf(step)
        const continued = continuedAfterFailure(step)
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
                    <span className="text-sm font-medium text-primary">{build ? `Built ${build.type} for ${build.input}` : step.name || step.id}</span>
                    <Tag size="sm" outline mono>
                      {build ? 'build' : (KIND_LABEL[step.type] ?? step.type)}
                    </Tag>
                    {continued && (
                      <Tag size="sm" tone="warning" title={continued.message ? `${continued.errorClass ? `${continued.errorClass}: ` : ''}${continued.message}` : undefined}>
                        failed, the workflow continued
                      </Tag>
                    )}
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
                    {!step.error && continued?.message && <span className="truncate text-warning">{continued.message}</span>}
                  </div>
                </div>
                <StateTag status={step.status} size="sm" />
                {hasDetail && <Icon name="chevron-down" size={14} className={cx('shrink-0 text-tertiary transition-transform duration-150', isOpen && 'rotate-180')} />}
              </button>
              {(child || childId) && (
                <div className="flex flex-wrap items-center gap-2 px-2 pb-1 text-caption">
                  <Icon name="job" size={13} className="text-tertiary" />
                  <span className="text-secondary">{build ? `The ${build.type} was built by job` : 'Child job'}</span>
                  <TextLink to={links.job(child?.id ?? childId!)} mono>
                    {child?.id ?? childId}
                  </TextLink>
                  {child && <StateTag status={child.status} size="sm" />}
                  {child && (
                    <span className="text-tertiary">
                      {child.title} · {child.version}
                    </span>
                  )}
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
