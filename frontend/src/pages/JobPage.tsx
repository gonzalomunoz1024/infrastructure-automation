import { useEffect, useMemo, useRef, useState } from 'react'
import { Button, Card, ConfirmDialog, EmptyState, Field, Icon, JsonView, PageHeader, Select, SkeletonRows, StateTag, StatusDot, Table, Tabs, Tag, cx, duration, useToast, type DotKind, type TableColumn } from '@iaf/ui'
import type { PageProps } from '../app/router'
import { navigate, pathFor } from '../app/router'
import { api, errorMessage, isApiError, jobEvents, newIdempotencyKey } from '../lib/api'
import { apiExtra } from '../lib/api-extra'
import { invalidate, useQuery } from '../lib/queries'
import type { JobEvent, JobReplay, JobStepStatus, JobSummary } from '../lib/types'
import { ApprovalCard } from '../components/ApprovalCard'
import { AttentionPanel } from '../components/AttentionPanel'
import { BatchPanel } from '../components/BatchPanel'
import { EngineLinks } from '../components/EngineLinks'
import { PreviewCard } from '../components/PreviewCard'
import { RerunCard, rerunBlocker } from '../components/RerunCard'
import { QuotaNotice, quotaMessage, useRetryAfter } from '../components/RetryAfter'
import { StepDiffTable } from '../components/StepDiffTable'
import { StepTimeline } from '../components/StepTimeline'
import { CopyableId, ErrorState, ExternalLink, Facts, Mono, TextLink, When, shortJobId, summarize, useNow } from '../components/common'
import { isActiveJob } from './JobsPage'

type Tab = 'steps' | 'batch' | 'replay' | 'activity'

const STEP_DOT: Record<JobStepStatus, DotKind> = { pending: 'neutral', running: 'healthy', waiting: 'warning', succeeded: 'healthy', failed: 'critical', skipped: 'neutral', compensated: 'warning' }

/**
 * What a cancel does, for its confirm dialog. Both engines stop the job without compensating:
 * no further step starts, a wait ends at once, a playbook, plan or worker call in progress is
 * stopped, and what the finished steps changed stays changed. Only Abort, on a run a failed step
 * paused, runs the compensation steps; a paused run that offers it says so.
 */
export function cancelMessage(job: { title: string; attention?: { step: string; options: readonly string[] } }): string {
  const stops = `${job.title} stops: no further step starts, a wait ends at once, and a playbook, plan or worker call in progress is stopped. Nothing is rolled back: what the finished steps changed stays changed.`
  if (job.attention?.options.includes('abort')) return `${stops} To roll them back instead, choose Abort for ${job.attention.step} under Needs attention.`
  return `${stops} Only Abort, offered when a failed step pauses the run, rolls back.`
}

/** One line for a live event, from the fields the backend puts in `data`. */
function eventLine(ev: JobEvent): string {
  const d = ev.data ?? {}
  const step = typeof d.step === 'string' ? d.step : typeof d.id === 'string' ? d.id : undefined
  const status = typeof d.status === 'string' ? d.status : undefined
  const message = typeof d.message === 'string' ? d.message : typeof d.text === 'string' ? d.text : undefined
  switch (ev.type) {
    case 'job':
      return status ? `job ${status}` : summarize(d)
    case 'step':
      return [step, status].filter(Boolean).join(' ') || summarize(d)
    case 'approval':
      return `approval ${status ?? 'requested'}${typeof d.group === 'string' ? ` · ${d.group}` : ''}`
    case 'attention':
      return `needs attention${step ? ` at ${step}` : ''}${message ? `: ${message}` : ''}`
    case 'batch':
      return typeof d.wave === 'number' ? `wave ${d.wave} ${status ?? ''}`.trim() : summarize(d)
    case 'log':
      return message ?? summarize(d)
  }
}

export default function JobPage({ route }: PageProps) {
  const id = route.params.id
  const toast = useToast()
  const quota = useRetryAfter()
  const [chosenTab, setTab] = useState<Tab | null>(null)
  const [confirmCancel, setConfirmCancel] = useState(false)
  const [polling, setPolling] = useState(true)
  // the clock ticks every second only while the job runs (durations, countdowns)
  const now = useNow(polling ? 1000 : 60_000)
  const [live, setLive] = useState<'connecting' | 'open'>('connecting')
  const [events, setEvents] = useState<JobEvent[]>([])

  const job = useQuery(['job', id], () => api.job(id), { refetchInterval: polling ? 5000 : undefined })
  const data = job.data
  // the stream is the fast path; the 5 s poll is the safety net while the job is active
  const active = !data || isActiveJob(data.status)
  if (active !== polling) setPolling(active)

  const refetchRef = useRef(job.refetch)
  useEffect(() => {
    refetchRef.current = job.refetch
  })
  useEffect(() => {
    let timer: ReturnType<typeof setTimeout> | undefined
    const sub = jobEvents(
      id,
      (ev) => {
        setEvents((es) => {
          const next = es.filter((e) => e.seq !== ev.seq).concat(ev).sort((a, b) => a.seq - b.seq)
          return next.length > 300 ? next.slice(-300) : next
        })
        if (timer) clearTimeout(timer)
        timer = setTimeout(() => void refetchRef.current(), 250)
      },
      { onOpen: () => setLive('open'), onError: () => setLive('connecting') },
    )
    return () => {
      sub.close()
      if (timer) clearTimeout(timer)
    }
  }, [id])

  const after = (title: string, message?: string) => {
    invalidate('job')
    invalidate('approvals')
    toast.success(title, message)
  }
  const act = async (fn: () => Promise<unknown>, title: string, failTitle: string, message?: string) => {
    try {
      await fn()
      quota.clear()
      after(title, message)
    } catch (e) {
      if (isApiError(e) && e.tooManyRequests) {
        quota.block(e)
        toast.error(failTitle, quotaMessage(e))
      } else toast.error(failTitle, errorMessage(e))
      throw e
    }
  }

  const pendingApprovals = data?.approvals.filter((a) => a.status === 'pending') ?? []
  const attentionStep = data?.attention ? data.steps.find((s) => s.id === data.attention?.step) : undefined
  const failedStep = data?.steps.find((s) => s.status === 'failed')
  const highlight = data?.attention?.step ?? pendingApprovals[0]?.step ?? failedStep?.id
  const elapsed = data ? (data.durationMs ?? (data.endedAt ? Date.parse(data.endedAt) - Date.parse(data.startedAt) : now - Date.parse(data.startedAt))) : undefined
  const isBatch = !!data?.batch || data?.kind === 'batch'
  // a batch's own work is its rows, so it opens on them
  const tab: Tab = chosenTab ?? (data?.batch ? 'batch' : 'steps')

  if (job.error && !data) {
    return (
      <>
        <PageHeader eyebrow="Job" title={<span className="font-mono">{id}</span>} back={{ href: `#${pathFor('jobs')}`, label: 'Jobs' }} />
        <ErrorState error={job.error} title="Could not load the job" onRetry={() => void job.refetch()} />
      </>
    )
  }

  return (
    <>
      <PageHeader
        eyebrow={data ? (isBatch ? 'Batch job' : 'Job') : 'Job'}
        title={data?.title ?? <span className="font-mono">{id}</span>}
        subtitle={
          data && (
            <span className="inline-flex flex-wrap items-center gap-2">
              <StateTag status={data.status} size="sm" />
              <TextLink to={pathFor('workflow', { name: data.workflow })} mono>
                {data.workflow}
              </TextLink>
              <Mono>{data.version}</Mono>
              {data.pinned && (
                <Tag size="sm" tone="accent" title="Started on exactly this version, bypassing the canary split">
                  pinned to {data.version}
                </Tag>
              )}
              <span className="text-tertiary">·</span>
              <span>{data.requester}</span>
              <Tag size="sm" outline>
                {data.channel}
              </Tag>
              {active && (
                <Tag size="sm" tone={live === 'open' ? 'healthy' : 'neutral'} dot pulse={live === 'open'} title={live === 'open' ? 'Live events connected' : 'Reconnecting to live events'}>
                  {live === 'open' ? 'live' : 'reconnecting'}
                </Tag>
              )}
            </span>
          )
        }
        back={{ href: `#${pathFor('jobs')}`, label: 'Jobs' }}
        actions={
          data && (
            <>
              <Button icon="audit" onClick={() => navigate(pathFor('audit-record', { jobId: id }))}>
                Evidence
              </Button>
              <Button icon="workflow" onClick={() => navigate(pathFor('workflow', { name: data.workflow }, { job: id }))}>
                Open in designer
              </Button>
              <EngineLinks links={data.links} />
              {active && (
                <Button variant="warning-outline" icon="stop" onClick={() => setConfirmCancel(true)}>
                  Cancel
                </Button>
              )}
            </>
          )
        }
      />

      {!data ? (
        <SkeletonRows n={5} />
      ) : (
        <div className="grid grid-cols-1 gap-5 lg:grid-cols-[minmax(0,1fr)_22rem]">
          <div className="min-w-0 space-y-5">
            {data.attention && (
              <AttentionPanel
                attention={data.attention}
                step={attentionStep}
                onRetry={() => act(() => api.retryJob(id, { step: data.attention?.step }, { idempotencyKey: newIdempotencyKey() }), 'Retrying', 'Could not retry', `${data.attention?.step} runs again.`)}
                onRetryWithInput={(input) => act(() => api.retryJob(id, { step: data.attention?.step, input }, { idempotencyKey: newIdempotencyKey() }), 'Retrying with the new input', 'Could not retry')}
                disabled={quota.blocked}
                notice={<QuotaNotice quota={quota} className="mt-3" />}
                onSkip={() => act(() => api.skipJob(id, data.attention!.step), 'Step skipped', 'Could not skip', 'The job continues with the next step.')}
                onAbort={() => act(() => api.abortJob(id), 'Job aborted', 'Could not abort')}
              />
            )}
            {pendingApprovals.map((a) => (
              <ApprovalCard
                key={a.id}
                approval={{ ...a, requester: data.requester, title: data.title }}
                jobLink={false}
                showPreview={!!a.preview && !data.preview}
                onApprove={(c) => act(() => api.approve(a.id, c || undefined), 'Approved', 'Could not approve', 'The job continues.')}
                onReject={(c) => act(() => api.reject(a.id, c || undefined), 'Rejected', 'Could not reject', 'The job stops here.')}
                className="animate-fade-in"
              />
            ))}

            <div>
              <Tabs
                ariaLabel="Job views"
                value={tab}
                onChange={setTab}
                className="mb-3"
                tabs={[
                  { id: 'steps', label: 'Steps', icon: 'list', count: data.steps.length },
                  { id: 'batch', label: 'Batch', icon: 'layers', count: data.batch?.rows.length, disabled: !data.batch },
                  { id: 'replay', label: 'Time travel', icon: 'history' },
                  { id: 'activity', label: 'Activity', icon: 'pulse', count: events.length || undefined },
                ]}
              />
              {tab === 'steps' && (
                <Card flush>
                  {data.steps.length === 0 ? (
                    <EmptyState compact icon="list" title="No steps yet" message={isBatch ? 'A batch runs each row as its own job; the Batch tab lists them.' : active ? 'The first step starts in a moment.' : 'This job recorded no steps.'} />
                  ) : (
                    <StepTimeline steps={data.steps} children={data.children} highlight={highlight} defaultOpen={failedStep ? [failedStep.id] : undefined} className="p-4" />
                  )}
                  {data.children && data.children.length > 0 && !data.batch && <ChildJobs jobs={data.children} />}
                </Card>
              )}
              {tab === 'batch' && data.batch && (
                <BatchPanel batch={data.batch} ended={!active} onRetryFailed={() => act(() => api.retryFailedRows(id), 'Retrying the rows', 'Could not retry the rows', 'They run again in a new wave.')} />
              )}
              {tab === 'replay' && <TimeTravel jobId={id} workflow={data.workflow} steps={data.steps} input={data.input} rerunBlocked={rerunBlocker({ active, batch: isBatch })} />}
              {tab === 'activity' && <Activity events={events} live={live === 'open' && active} />}
            </div>
          </div>

          <aside className="min-w-0 space-y-4">
            <Card title="Details">
              <Facts
                columns={2}
                items={[
                  { label: 'Started', value: <When at={data.startedAt} now={now} /> },
                  { label: 'Ended', value: data.endedAt ? <When at={data.endedAt} now={now} /> : <span className="text-tertiary">still running</span> },
                  { label: 'Duration', value: elapsed !== undefined && Number.isFinite(elapsed) && elapsed >= 0 ? <span className="tabular-nums">{duration(elapsed)}</span> : '—' },
                  { label: 'Job id', value: <CopyableId id={data.id} /> },
                  { label: 'Trace', value: data.traceId ? <CopyableId id={data.traceId} /> : '—' },
                  ...(data.parentId ? [{ label: 'Parent job', value: <TextLink to={pathFor('job', { id: data.parentId })} mono>{shortJobId(data.parentId)}</TextLink> }] : []),
                  ...(data.change
                    ? [
                        {
                          // the change ticket the workflow's policy opened (ITSM), not a platform change request
                          label: 'Change',
                          value: (
                            <span className="inline-flex items-center gap-1.5">
                              {data.change.url ? <ExternalLink href={data.change.url}>{data.change.id}</ExternalLink> : <Mono className="text-primary">{data.change.id}</Mono>}
                              <StateTag status={data.change.state} size="sm" />
                            </span>
                          ),
                          wide: true,
                        },
                      ]
                    : []),
                  ...(data.progress ? [{ label: 'Rows', value: `${data.progress.done} of ${data.progress.total} done${data.progress.failed ? `, ${data.progress.failed} failed` : ''}`, wide: true }] : []),
                ]}
              />
            </Card>
            {data.preview && <PreviewCard preview={data.preview} compact />}
            <Card title="Input" flush>
              <div className="p-3">
                <JsonView value={data.input} name="input" defaultExpanded={1} maxHeight="max-h-72" />
              </div>
            </Card>
            <Card title="Output" flush>
              <div className="p-3">
                {data.output && Object.keys(data.output).length > 0 ? (
                  <JsonView value={data.output} name="output" defaultExpanded={1} maxHeight="max-h-72" />
                ) : (
                  <p className="px-1 text-caption text-tertiary">{active ? 'Appears when the job ends.' : 'No output.'}</p>
                )}
              </div>
            </Card>
          </aside>
        </div>
      )}

      {confirmCancel && data && (
        <ConfirmDialog
          title="Cancel this job?"
          tone="danger"
          icon="stop"
          message={cancelMessage(data)}
          confirmLabel="Cancel job"
          cancelLabel="Keep running"
          onConfirm={async () => {
            await act(() => api.cancelJob(id), 'Cancelling', 'Could not cancel')
          }}
          onClose={() => setConfirmCancel(false)}
        />
      )}
    </>
  )
}

function ChildJobs({ jobs }: { jobs: JobSummary[] }) {
  const columns: TableColumn<JobSummary>[] = [
    { key: 'title', header: 'Child job', render: (j) => <span className="font-medium text-primary">{j.title}</span> },
    { key: 'status', header: 'Status', width: '10rem', render: (j) => <StateTag status={j.status} /> },
    { key: 'workflow', header: 'Workflow', render: (j) => <Mono className="text-primary">{j.workflow}</Mono> },
    { key: 'started', header: 'Started', width: '9rem', render: (j) => <When at={j.startedAt} /> },
  ]
  return (
    <div className="border-t border-stroke-light">
      <Table columns={columns} rows={jobs} rowKey={(j) => j.id} dense bordered={false} onRowClick={(j) => navigate(pathFor('job', { id: j.id }))} caption="Child jobs" />
    </div>
  )
}

/* ----------------------------------------------------------------------------
   Time travel: replay the recorded run, compare it with another, re-run from a step
   ------------------------------------------------------------------------- */

/** What a replay answered, compared with another run, in words: who it was compared with and how much differs. */
export function ReplayComparison({ replay }: { replay: JobReplay }) {
  if (!replay.comparedTo) {
    return <p className="text-caption text-secondary">Not compared: this workflow has no earlier successful run to compare with. Pick another job above.</p>
  }
  const stepsDiffering = replay.steps.filter((s) => s.diff?.length).length
  const inputDiff = replay.inputDiff ?? []
  const nothing = stepsDiffering === 0 && inputDiff.length === 0
  return (
    <div className="space-y-3" data-testid="replay-comparison">
      <p className="flex flex-wrap items-center gap-x-2 gap-y-1 text-caption text-secondary">
        <span>Compared with</span>
        <TextLink to={pathFor('job', { id: replay.comparedTo })} mono>
          {shortJobId(replay.comparedTo)}
        </TextLink>
        {replay.comparedVersion && <Mono title="The version the compared run used">{replay.comparedVersion}</Mono>}
        {replay.comparedStatus && <StateTag status={replay.comparedStatus} size="sm" />}
        {replay.version && replay.comparedVersion && replay.version !== replay.comparedVersion && (
          <Tag size="sm" tone="warning">
            different version
          </Tag>
        )}
        <span>
          {nothing
            ? '— nothing differs.'
            : `— ${inputDiff.length ? `${inputDiff.length} input ${inputDiff.length === 1 ? 'field' : 'fields'}` : ''}${inputDiff.length && stepsDiffering ? ' and ' : ''}${stepsDiffering ? `${stepsDiffering} ${stepsDiffering === 1 ? 'step' : 'steps'}` : ''} differ.`}
        </span>
      </p>
      {inputDiff.length > 0 && (
        <div>
          <div className="mb-1 text-caption font-medium text-secondary">Input</div>
          <StepDiffTable diff={inputDiff} caption="Differences in the input" />
        </div>
      )}
    </div>
  )
}

function TimeTravel({ jobId, workflow, steps, input, rerunBlocked }: { jobId: string; workflow: string; steps: { id: string; name: string; status: string }[]; input: Record<string, unknown>; rerunBlocked: string | null }) {
  const toast = useToast()
  const [compareTo, setCompareTo] = useState('')
  const [replay, setReplay] = useState<JobReplay | null>(null)
  const [replayError, setReplayError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const siblings = useQuery(['jobs', 'of', workflow], () => api.jobs({ workflow, limit: 50 }))
  const others = useMemo(() => (siblings.data ?? []).filter((j) => j.id !== jobId), [siblings.data, jobId])

  const runReplay = async () => {
    setBusy(true)
    setReplayError(null)
    try {
      setReplay(await apiExtra.jobReplay(jobId, compareTo || undefined))
    } catch (e) {
      // an unknown compareTo answers 404: say which job is missing rather than that the replay failed
      const message = isApiError(e) && e.status === 404 && compareTo ? `There is no job ${compareTo} to compare with.` : errorMessage(e)
      setReplayError(message)
      toast.error('Replay failed', message)
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="space-y-4">
      <Card title="Replay" subtitle="Walk the run again from its recorded inputs and outputs, compared with the last successful run of the workflow, or with any other run you pick, to see where they diverged.">
        <div className="flex flex-wrap items-end gap-3">
          <Field label="Compare with" className="min-w-64">
            <Select
              ariaLabel="Compare with"
              value={compareTo}
              onChange={setCompareTo}
              mono
              placeholder="Last successful run"
              options={[{ value: '', label: 'Last successful run' }, ...others.map((j) => ({ value: j.id, label: shortJobId(j.id), hint: `${j.status} · ${j.version} · ${j.requester}` }))]}
            />
          </Field>
          <Button variant="primary" icon="history" loading={busy} onClick={() => void runReplay()}>
            Replay
          </Button>
        </div>
        {replayError && !replay && (
          <p className="mt-3 text-caption text-critical" role="alert">
            {replayError}
          </p>
        )}
        {replay && (
          <div className="mt-4 animate-fade-in space-y-3">
            <ReplayComparison replay={replay} />
            <ol className="divide-y divide-stroke-light rounded-md border border-stroke-light">
              {replay.steps.map((s) => (
                <li key={s.id} className={cx('px-4 py-3', s.diff?.length && 'bg-warning-tint/40')}>
                  <div className="flex flex-wrap items-center gap-2">
                    <StatusDot kind={STEP_DOT[s.status] ?? 'neutral'} label={s.status} />
                    <span className="font-medium text-primary">{s.name}</span>
                    <Mono className="text-caption text-tertiary">{s.id}</Mono>
                    <StateTag status={s.status} size="sm" />
                    {s.durationMs !== undefined && <span className="text-caption tabular-nums text-tertiary">{duration(s.durationMs)}</span>}
                    {s.diff && s.diff.length > 0 && (
                      <Tag size="sm" tone="warning" className="ml-auto">
                        {s.diff.length} differ
                      </Tag>
                    )}
                  </div>
                  {s.diff && s.diff.length > 0 && (
                    <div className="mt-2">
                      <StepDiffTable diff={s.diff} caption={`Differences at ${s.id}`} />
                    </div>
                  )}
                </li>
              ))}
            </ol>
          </div>
        )}
      </Card>

      <RerunCard jobId={jobId} steps={steps} input={input} blocked={rerunBlocked} />
    </div>
  )
}

function Activity({ events, live }: { events: JobEvent[]; live: boolean }) {
  const TONE: Record<JobEvent['type'], 'neutral' | 'accent' | 'healthy' | 'warning' | 'critical'> = { job: 'accent', step: 'neutral', approval: 'warning', attention: 'critical', batch: 'accent', log: 'neutral' }
  return (
    <Card flush>
      <div className="flex items-center justify-between border-b border-stroke-light px-4 py-2.5">
        <span className="text-caption text-secondary">Events as the backend sends them, oldest first.</span>
        <Tag size="sm" tone={live ? 'healthy' : 'neutral'} dot pulse={live}>
          {live ? 'streaming' : 'closed'}
        </Tag>
      </div>
      {events.length === 0 ? (
        <EmptyState compact icon="pulse" title="No events yet" message={live ? 'They appear here as the job runs.' : 'The stream sent nothing for this job.'} />
      ) : (
        <ol className="max-h-[32rem] overflow-y-auto font-mono text-caption">
          {events
            .slice()
            .reverse()
            .map((ev) => (
              <li key={ev.seq} className="flex items-start gap-3 border-b border-stroke-light px-4 py-1.5 last:border-b-0">
                <span className="w-10 shrink-0 text-right tabular-nums text-tertiary">{ev.seq}</span>
                <span className="w-24 shrink-0 text-tertiary">
                  <When at={ev.at} />
                </span>
                <Tag size="sm" tone={TONE[ev.type] ?? 'neutral'} className="shrink-0">
                  {ev.type}
                </Tag>
                <span className="min-w-0 flex-1 break-words text-primary">{eventLine(ev)}</span>
              </li>
            ))}
        </ol>
      )}
      <p className="flex items-center gap-1.5 border-t border-stroke-light px-4 py-2 text-caption2 text-tertiary">
        <Icon name="info" size={12} />
        Also on the WebSocket twin at /ws/jobs/{'{id}'}.
      </p>
    </Card>
  )
}
