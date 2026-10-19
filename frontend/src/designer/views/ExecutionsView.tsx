/**
 * The Executions tab, n8n style: the jobs of this workflow on the left, the selected job on the
 * canvas of the version it ran (read-only, every step coloured by how it went, live over SSE
 * while it runs), a replay that walks the steps again beside each step's output diff against the
 * last successful run before it, a step view with what each step got and returned (and that diff),
 * "Debug in editor" (the job's outputs become pinned data) and the job page.
 */
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Button, cx, duration, Drawer, EmptyState, Icon, IconButton, JsonView, OverlayBody, OverlayHeader, relativeTime, Skeleton, SkeletonRows, StateTag, Tag, useToast, type Tone } from '@iaf/ui'
import type { JsonValue } from '@iaf/workflow-checks'
import { api, errorMessage } from '../../lib/api'
import { useQuery } from '../../lib/queries'
import type { JobDetail, JobStep, JobSummary, RegistryProvider } from '../../lib/types'
import { Link, pathFor } from '../../app/router'
import { StepDiffTable } from '../../components/StepDiffTable'
import { isStepNode } from '../model/graph'
import { placeGraph, useDesignerSelector, useDesignerStore } from '../model/store'
import { Canvas } from '../canvas/Canvas'
import { jobRunStates } from '../canvas/flow'
import { comparisonSummary, lastSuccessfulBefore, pairSteps, type StepChange, type StepPair } from './replay-compare'

const NO_PROVIDERS: RegistryProvider[] = []
const NO_PINS: Record<string, JsonValue> = {}
const REPLAY_STEP_MS = 450
const LIVE = new Set(['queued', 'running', 'waiting-approval', 'needs-attention'])

function JobRow({ job, selected, onSelect }: { job: JobSummary; selected: boolean; onSelect: () => void }) {
  return (
    <li>
      <button
        type="button"
        onClick={onSelect}
        aria-current={selected ? 'true' : undefined}
        className={cx('flex w-full flex-col gap-1 rounded-md px-3 py-2 text-left transition-colors', selected ? 'bg-accent-tint' : 'hover:bg-surface-secondary')}
      >
        <span className="flex items-center gap-2">
          <StateTag status={job.status} size="sm" />
          <span className="flex-1" />
          <span className="text-caption2 text-tertiary">{relativeTime(job.startedAt)}</span>
        </span>
        <span className="flex items-center gap-2 text-caption">
          <span className="truncate font-mono text-secondary">{job.id}</span>
          <span className="flex-1" />
          <span className="shrink-0 text-tertiary">{job.durationMs !== undefined ? duration(job.durationMs) : ''}</span>
        </span>
        <span className="truncate text-caption2 text-tertiary">
          {job.version} · {job.channel} · {job.requester}
        </span>
      </button>
    </li>
  )
}

function stepTiming(step: JobStep): string {
  const parts: string[] = []
  if (step.startedAt) parts.push(`Started ${relativeTime(step.startedAt)}`)
  else if (step.durationMs === undefined) parts.push(step.status === 'skipped' ? 'Skipped' : 'Not started')
  if (step.durationMs !== undefined) parts.push(parts.length === 0 ? `Took ${duration(step.durationMs)}` : `took ${duration(step.durationMs)}`)
  if (step.items !== undefined) parts.push(`${step.items} items`)
  return parts.join(' · ')
}

/** The comparison with the last successful run before the job: looked up only once something shows it. */
type Comparison = { state: 'loading' } | { state: 'error'; message: string } | { state: 'none' } | { state: 'ready'; run: JobDetail; pairs: StepPair[] }

function useComparison(job: JobDetail, enabled: boolean): Comparison {
  const succeeded = useQuery(['jobs', 'succeeded', job.workflow], () => api.jobs({ workflow: job.workflow, status: 'succeeded', limit: 50 }), { enabled })
  const target = useMemo(() => (succeeded.data ? lastSuccessfulBefore(job, succeeded.data) : null), [succeeded.data, job])
  const compared = useQuery(['job', target?.id ?? ''], () => api.job(target?.id ?? ''), { enabled: enabled && !!target })
  const pairs = useMemo(() => (compared.data ? pairSteps(job.steps, compared.data.steps) : null), [compared.data, job.steps])
  if (succeeded.error && !succeeded.data) return { state: 'error', message: errorMessage(succeeded.error) }
  if (!succeeded.data) return { state: 'loading' }
  if (!target) return { state: 'none' }
  if (compared.error && !compared.data) return { state: 'error', message: errorMessage(compared.error) }
  if (!compared.data || !pairs) return { state: 'loading' }
  return { state: 'ready', run: compared.data, pairs }
}

const CHANGE: Record<StepChange, { tone: Tone; label: (p: StepPair) => string }> = {
  same: { tone: 'neutral', label: () => 'same' },
  changed: { tone: 'warning', label: (p) => `${p.diff.length} differ` },
  added: { tone: 'accent', label: () => 'added' },
  removed: { tone: 'critical', label: () => 'removed' },
}

/** The line naming the run compared against, or why there is none. */
function ComparedWith({ comparison }: { comparison: Comparison }) {
  if (comparison.state === 'loading') return <span>Looking for the last successful run…</span>
  if (comparison.state === 'error') return <span className="text-critical">Could not load the last successful run: {comparison.message}</span>
  if (comparison.state === 'none') return <span>No earlier successful run</span>
  const { run, pairs } = comparison
  return (
    <span>
      Compared with the last successful run,{' '}
      <Link to={pathFor('job', { id: run.id })} className="font-mono text-accent hover:underline">
        {run.id}
      </Link>{' '}
      ({run.version}, {relativeTime(run.startedAt)}): {comparisonSummary(pairs)}.
    </span>
  )
}

/** The replay beside the canvas: the steps in order, the one being replayed in the accent, each with its output diff. */
function ReplayPanel({ job, comparison, replay, onOpenStep, onClose }: { job: JobDetail; comparison: Comparison; replay: number | null; onOpenStep: (stepId: string) => void; onClose: () => void }) {
  const list = useRef<HTMLOListElement>(null)
  const rows: StepPair[] = comparison.state === 'ready' ? comparison.pairs : job.steps.map((s, i) => ({ key: `step:${i}:${s.id}`, id: s.id, name: s.name || s.id, change: 'same', current: s, diff: [] }))
  useEffect(() => {
    list.current?.querySelector('[aria-current="step"]')?.scrollIntoView({ block: 'nearest' })
  }, [replay])
  return (
    <aside aria-label="Replay" data-testid="replay-panel" className="flex w-[380px] max-w-[50%] shrink-0 animate-fade-in flex-col border-l border-stroke-light bg-surface">
      <div className="flex items-center gap-2 border-b border-stroke-light py-1.5 pl-4 pr-1.5">
        <h3 className="flex-1 text-footnote font-semibold text-primary">Replay</h3>
        <IconButton label="Close the replay" size="sm" onClick={onClose}>
          <Icon name="close" size={14} />
        </IconButton>
      </div>
      <p className="border-b border-stroke-light px-4 py-2 text-caption text-secondary" data-testid="replay-compared">
        <ComparedWith comparison={comparison} />
      </p>
      <ol ref={list} className="min-h-0 flex-1 divide-y divide-stroke-light overflow-y-auto" aria-label="Replayed steps">
        {rows.map((p, n) => {
          // a step gone from this run takes the place of the step it followed there
          const before = rows.slice(0, n + 1).findLast((r) => r.current)?.current
          const i = before ? job.steps.indexOf(before) : -1
          const current = replay !== null && !!p.current && i === replay
          const upcoming = replay !== null && (p.current ? i > replay : i >= replay)
          return (
            <li key={p.key} aria-current={current ? 'step' : undefined} data-change={comparison.state === 'ready' ? p.change : undefined} className={cx('px-4 py-2.5 transition-colors', current && 'bg-accent-tint', upcoming && 'opacity-50')}>
              <div className="flex min-w-0 items-center gap-2">
                {p.current && <StateTag status={p.current.status} size="sm" />}
                <button
                  type="button"
                  className={cx('min-w-0 truncate text-left text-caption font-medium enabled:hover:underline', p.change === 'removed' ? 'text-tertiary line-through' : 'text-primary')}
                  onClick={() => onOpenStep(p.id)}
                  disabled={!p.current}
                  title={p.current ? `Open ${p.name}` : `Only in the compared run`}
                >
                  {p.name}
                </button>
                <span className="truncate font-mono text-caption2 text-tertiary">{p.id}</span>
                <span className="flex-1" />
                {comparison.state === 'ready' && (
                  <Tag size="sm" tone={CHANGE[p.change].tone}>
                    {CHANGE[p.change].label(p)}
                  </Tag>
                )}
              </div>
              {p.diff.length > 0 && !upcoming && <StepDiffTable diff={p.diff} caption={`Differences at ${p.id}`} className="mt-2" />}
            </li>
          )
        })}
      </ol>
    </aside>
  )
}

function StepDrawer({ step, job, comparison, onClose }: { step: JobStep; job: JobDetail; comparison: Comparison; onClose: () => void }) {
  const pair = comparison.state === 'ready' ? comparison.pairs.find((p) => p.current === step) : undefined
  return (
    <Drawer onClose={onClose} width="max-w-xl" label={`Step ${step.name}`}>
      <OverlayHeader title={step.name || step.id} subtitle={`${step.id} · ${step.type} · attempt ${step.attempt}`} actions={<StateTag status={step.status} />} />
      <OverlayBody className="space-y-5">
        <p className="text-caption text-tertiary">{stepTiming(step)}</p>
        {step.error && (
          <div className="rounded-md border border-critical/40 bg-critical-tint p-3 text-caption text-critical" role="alert">
            <p className="font-semibold">{step.error.class}</p>
            <p className="mt-1 whitespace-pre-wrap font-mono">{step.error.message}</p>
            <p className="mt-1 text-secondary">{step.error.retryable ? 'Retryable' : 'Not retryable'}</p>
          </div>
        )}
        <section>
          <h3 className="mb-1.5 text-caption2 font-semibold uppercase tracking-wide text-tertiary">Input</h3>
          {step.input !== undefined ? <JsonView value={step.input} defaultExpanded={2} /> : <p className="text-caption text-tertiary">No input recorded.</p>}
        </section>
        <section>
          <h3 className="mb-1.5 text-caption2 font-semibold uppercase tracking-wide text-tertiary">Output</h3>
          {step.output !== undefined ? <JsonView value={step.output} defaultExpanded={2} /> : <p className="text-caption text-tertiary">No output.</p>}
        </section>
        <section data-testid="step-compared">
          <h3 className="mb-1.5 text-caption2 font-semibold uppercase tracking-wide text-tertiary">Against the last successful run</h3>
          <p className="text-caption text-secondary">
            <ComparedWith comparison={comparison} />
          </p>
          {pair && comparison.state === 'ready' && (
            <div className="mt-2">
              {pair.change === 'added' ? (
                <p className="text-caption text-tertiary">
                  {comparison.run.id} has no step {step.id}.
                </p>
              ) : pair.diff.length === 0 ? (
                <p className="text-caption text-tertiary">Same output as in {comparison.run.id}.</p>
              ) : (
                <StepDiffTable diff={pair.diff} caption={`Differences at ${step.id}`} />
              )}
            </div>
          )}
        </section>
        <Link to={pathFor('job', { id: job.id }, { step: step.id })} className="inline-flex items-center gap-1 text-footnote font-medium text-accent hover:underline">
          Open in the job page
          <Icon name="external" size={12} />
        </Link>
      </OverlayBody>
    </Drawer>
  )
}

function ExecutionCanvas({ job }: { job: JobDetail }) {
  const store = useDesignerStore()
  const toast = useToast()
  const name = useDesignerSelector((s) => s.name)
  const currentDoc = useDesignerSelector((s) => s.doc)
  const providers = useDesignerSelector((s) => s.registry?.providers ?? NO_PROVIDERS)
  const version = useQuery(['workflow-version', name, job.version], () => api.workflowVersion(name ?? '', job.version), { enabled: !!name })
  const doc = version.data?.definition ?? (version.error ? currentDoc : null)
  const graph = useMemo(() => (doc ? placeGraph(doc, new Map()) : null), [doc])
  const [replay, setReplay] = useState<number | null>(null)
  const [panel, setPanel] = useState(false)
  const [openStep, setOpenStep] = useState<string | null>(null)
  const runs = useMemo(() => jobRunStates(job.steps, replay), [job.steps, replay])
  const comparison = useComparison(job, panel || openStep !== null)

  useEffect(() => {
    if (replay === null) return
    if (replay >= job.steps.length) {
      const t = setTimeout(() => setReplay(null), REPLAY_STEP_MS)
      return () => clearTimeout(t)
    }
    const t = setTimeout(() => setReplay((r) => (r === null ? null : r + 1)), REPLAY_STEP_MS)
    return () => clearTimeout(t)
  }, [replay, job.steps.length])

  const onOpen = useCallback(
    (nodeId: string) => {
      const node = graph?.nodes.find((n) => n.id === nodeId)
      if (node && isStepNode(node)) setOpenStep(node.data.stepId)
    },
    [graph],
  )

  const debug = () => {
    const outputs: Record<string, JsonValue> = {}
    for (const s of job.steps) if (s.status === 'succeeded' && s.output !== undefined) outputs[s.id] = s.output as JsonValue
    store.pinOutputs(outputs)
    store.setTab('editor')
    const n = Object.keys(outputs).length
    toast.toast({ title: 'Debugging in the editor', message: `${n} ${n === 1 ? 'step output is' : 'step outputs are'} pinned from ${job.id}; unpin them from each step`, tone: 'neutral' })
  }

  const step = openStep ? job.steps.find((s) => s.id === openStep) : undefined
  const failedStep = job.steps.find((s) => s.status === 'failed')
  return (
    <div className="flex h-full min-w-0 flex-1 flex-col">
      <div className="flex flex-wrap items-center gap-3 border-b border-stroke-light bg-surface px-4 py-2.5">
        <StateTag status={job.status} />
        <span className="font-mono text-footnote text-primary">{job.id}</span>
        <span className="text-caption text-tertiary">
          {job.version} · {relativeTime(job.startedAt)}
          {job.durationMs !== undefined ? ` · ${duration(job.durationMs)}` : ''} · {job.requester}
        </span>
        {LIVE.has(job.status) && (
          <span className="flex items-center gap-1 text-caption text-accent">
            <span className="h-1.5 w-1.5 animate-pulse-subtle rounded-full bg-accent" />
            live
          </span>
        )}
        <span className="flex-1" />
        <Button
          size="sm"
          variant="ghost"
          icon={replay === null ? 'play' : 'stop'}
          onClick={() => {
            if (replay === null) setPanel(true)
            setReplay((r) => (r === null ? 0 : null))
          }}
          disabled={job.steps.length === 0}
        >
          {replay === null ? 'Replay' : 'Stop'}
        </Button>
        <Button size="sm" variant="secondary" icon="edit" onClick={debug} data-testid="debug-in-editor">
          Debug in editor
        </Button>
        <Link to={pathFor('job', { id: job.id })} className="inline-flex items-center gap-1 rounded-md px-2 py-1 text-footnote font-medium text-accent hover:bg-accent-tint">
          Open job
          <Icon name="external" size={12} />
        </Link>
      </div>
      {failedStep?.error && (
        <div className="flex items-center gap-2 border-b border-critical/30 bg-critical-tint px-4 py-1.5 text-caption text-critical">
          <Icon name="alert-triangle" size={13} />
          <span className="truncate">
            {failedStep.name || failedStep.id}: {failedStep.error.message}
          </span>
        </div>
      )}
      {version.error !== null && version.error !== undefined && <p className="border-b border-stroke-light bg-warning-tint px-4 py-1.5 text-caption text-warning">Version {job.version} could not be loaded; the canvas shows the current draft.</p>}
      <div className="flex min-h-0 flex-1">
        <div className="relative min-w-0 flex-1">
          {graph ? (
            <Canvas graph={graph} problems={[]} runs={runs} pins={NO_PINS} stickies={[]} providers={providers} readOnly onOpen={onOpen} label={`Execution ${job.id}`} />
          ) : (
            <div className="flex h-full items-center justify-center bg-dot-grid">
              <Skeleton className="h-24 w-96" />
            </div>
          )}
        </div>
        {panel && <ReplayPanel job={job} comparison={comparison} replay={replay} onOpenStep={setOpenStep} onClose={() => setPanel(false)} />}
      </div>
      {step && <StepDrawer step={step} job={job} comparison={comparison} onClose={() => setOpenStep(null)} />}
    </div>
  )
}

export function ExecutionsView() {
  const store = useDesignerStore()
  const name = useDesignerSelector((s) => s.name)
  const execution = useDesignerSelector((s) => s.execution)
  const jobs = useQuery(['jobs', 'workflow', name], () => api.jobs({ workflow: name ?? '', limit: 50 }), { enabled: !!name, refetchInterval: 5000 })
  const list = useMemo(() => jobs.data ?? [], [jobs.data])

  // like n8n, open the latest execution when nothing is selected
  useEffect(() => {
    if (!execution && list.length > 0) store.openExecution(list[0].id)
  }, [execution, list, store])

  return (
    <div className="flex h-full min-h-0" data-testid="executions">
      <aside aria-label="Executions" className="flex w-[300px] max-w-[50%] shrink-0 flex-col border-r border-stroke-light bg-surface">
        <div className="flex items-center gap-2 px-4 py-3">
          <h2 className="flex-1 text-footnote font-semibold text-primary">Executions</h2>
          {jobs.fetching && !jobs.loading && <span className="text-caption2 text-tertiary">updating…</span>}
        </div>
        <div className="min-h-0 flex-1 overflow-y-auto px-2 pb-3">
          {jobs.loading ? (
            <SkeletonRows n={6} rowClassName="h-16" className="space-y-2 px-1" />
          ) : jobs.error ? (
            <p className="px-3 text-caption text-critical">{errorMessage(jobs.error)}</p>
          ) : list.length === 0 ? (
            <EmptyState compact icon="job" title="No executions yet" message="Jobs of this workflow show up here, whether they came from the catalog, the API, MCP, a trigger or a batch." />
          ) : (
            <ul className="space-y-1">
              {list.map((j) => (
                <JobRow key={j.id} job={j} selected={execution?.jobId === j.id} onSelect={() => store.openExecution(j.id)} />
              ))}
            </ul>
          )}
        </div>
      </aside>
      <div className="flex min-w-0 flex-1 flex-col">
        {!execution ? (
          <div className="flex h-full items-center justify-center bg-dot-grid">
            {!jobs.loading && list.length === 0 ? <EmptyState icon="workflow" title="Nothing to show yet" message="Run the workflow from the catalog or test it in the editor." /> : <Skeleton className="h-24 w-96" />}
          </div>
        ) : execution.loading && !execution.job ? (
          <div className="flex h-full flex-col">
            <div className="border-b border-stroke-light px-4 py-3">
              <Skeleton className="h-5 w-72" />
            </div>
            <div className="flex flex-1 items-center justify-center bg-dot-grid">
              <Skeleton className="h-24 w-96" />
            </div>
          </div>
        ) : execution.error && !execution.job ? (
          <EmptyState icon="alert-triangle" title="Could not load the job" message={execution.error} action={<Button size="sm" onClick={() => store.openExecution(execution.jobId)}>Try again</Button>} />
        ) : execution.job ? (
          <ExecutionCanvas key={execution.job.id} job={execution.job} />
        ) : null}
      </div>
    </div>
  )
}
