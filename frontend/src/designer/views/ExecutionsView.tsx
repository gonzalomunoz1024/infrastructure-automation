/**
 * The Executions tab, n8n style: the jobs of this workflow on the left, the selected job on the
 * canvas of the version it ran (read-only, every step coloured by how it went, live over SSE
 * while it runs), a replay that walks the steps again, a step view with what each step got and
 * returned, "Debug in editor" (the job's outputs become pinned data) and the job page.
 */
import { useCallback, useEffect, useMemo, useState } from 'react'
import { Button, cx, duration, Drawer, EmptyState, Icon, JsonView, OverlayBody, OverlayHeader, relativeTime, Skeleton, SkeletonRows, StateTag, useToast } from '@iaf/ui'
import type { JsonValue } from '@iaf/workflow-checks'
import { api, errorMessage } from '../../lib/api'
import { useQuery } from '../../lib/queries'
import type { JobDetail, JobStep, JobSummary, RegistryProvider } from '../../lib/types'
import { Link, pathFor } from '../../app/router'
import { isStepNode } from '../model/graph'
import { placeGraph, useDesignerSelector, useDesignerStore } from '../model/store'
import { Canvas } from '../canvas/Canvas'
import { jobRunStates } from '../canvas/flow'

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

function StepDrawer({ step, job, onClose }: { step: JobStep; job: JobDetail; onClose: () => void }) {
  return (
    <Drawer onClose={onClose} width="max-w-xl" label={`Step ${step.name}`}>
      <OverlayHeader title={step.name || step.id} subtitle={`${step.id} · ${step.type} · attempt ${step.attempt}`} actions={<StateTag status={step.status} />} />
      <OverlayBody className="space-y-5">
        <p className="text-caption text-tertiary">
          {step.startedAt ? `Started ${relativeTime(step.startedAt)}` : 'Not started'}
          {step.durationMs !== undefined ? ` · took ${duration(step.durationMs)}` : ''}
          {step.items !== undefined ? ` · ${step.items} items` : ''}
        </p>
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
  const [openStep, setOpenStep] = useState<string | null>(null)
  const runs = useMemo(() => jobRunStates(job.steps, replay), [job.steps, replay])

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
        <Button size="sm" variant="ghost" icon={replay === null ? 'play' : 'stop'} onClick={() => setReplay((r) => (r === null ? 0 : null))} disabled={job.steps.length === 0}>
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
      <div className="relative min-h-0 flex-1">
        {graph ? (
          <Canvas graph={graph} problems={[]} runs={runs} pins={NO_PINS} stickies={[]} providers={providers} readOnly onOpen={onOpen} label={`Execution ${job.id}`} />
        ) : (
          <div className="flex h-full items-center justify-center bg-dot-grid">
            <Skeleton className="h-24 w-96" />
          </div>
        )}
      </div>
      {step && <StepDrawer step={step} job={job} onClose={() => setOpenStep(null)} />}
    </div>
  )
}

export function ExecutionsView() {
  const store = useDesignerStore()
  const name = useDesignerSelector((s) => s.name)
  const execution = useDesignerSelector((s) => s.execution)
  const jobs = useQuery(['jobs', 'workflow', name], () => api.jobs({ workflow: name ?? '', limit: 50 }), { enabled: !!name, refetchInterval: 5000 })
  const list = jobs.data ?? []

  // like n8n, open the latest execution when nothing is selected
  useEffect(() => {
    if (!execution && list.length > 0) store.openExecution(list[0].id)
  }, [execution, list, store])

  return (
    <div className="flex h-full min-h-0" data-testid="executions">
      <aside aria-label="Executions" className="flex w-[300px] shrink-0 flex-col border-r border-stroke-light bg-surface">
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
