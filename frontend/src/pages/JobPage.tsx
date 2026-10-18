import { useEffect, useMemo, useRef, useState } from 'react'
import { Button, Card, ConfirmDialog, EmptyState, Field, Icon, JsonView, PageHeader, Select, SkeletonRows, StateTag, StatusDot, Table, Tabs, Tag, cx, duration, useToast, type DotKind, type TableColumn } from '@iaf/ui'
import type { PageProps } from '../app/router'
import { navigate, pathFor } from '../app/router'
import { api, errorMessage, jobEvents } from '../lib/api'
import { apiExtra } from '../lib/api-extra'
import { invalidate, useQuery } from '../lib/queries'
import type { JobEvent, JobReplay, JobStepStatus, JobSummary, StepDiff } from '../lib/types'
import { ApprovalCard } from '../components/ApprovalCard'
import { AttentionPanel } from '../components/AttentionPanel'
import { BatchPanel } from '../components/BatchPanel'
import { EngineLinks } from '../components/EngineLinks'
import { JsonEditor, parseJsonText } from '../components/JsonEditor'
import { PreviewCard } from '../components/PreviewCard'
import { StepTimeline } from '../components/StepTimeline'
import { CopyableId, ErrorState, ExternalLink, Facts, Mono, TextLink, When, shortJobId, summarize, useNow } from '../components/common'
import { isActiveJob } from './JobsPage'

type Tab = 'steps' | 'batch' | 'replay' | 'activity'

const STEP_DOT: Record<JobStepStatus, DotKind> = { pending: 'neutral', running: 'healthy', waiting: 'warning', succeeded: 'healthy', failed: 'critical', skipped: 'neutral', compensated: 'warning' }

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
  const [tab, setTab] = useState<Tab>('steps')
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
  refetchRef.current = job.refetch
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
      after(title, message)
    } catch (e) {
      toast.error(failTitle, errorMessage(e))
      throw e
    }
  }

  const pendingApprovals = data?.approvals.filter((a) => a.status === 'pending') ?? []
  const attentionStep = data?.attention ? data.steps.find((s) => s.id === data.attention?.step) : undefined
  const failedStep = data?.steps.find((s) => s.status === 'failed')
  const highlight = data?.attention?.step ?? pendingApprovals[0]?.step ?? failedStep?.id
  const elapsed = data ? (data.durationMs ?? (data.endedAt ? Date.parse(data.endedAt) - Date.parse(data.startedAt) : now - Date.parse(data.startedAt))) : undefined
  const isBatch = !!data?.batch || data?.kind === 'batch'

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
                Open designer executions
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
                onRetry={() => act(() => api.retryJob(id, { step: data.attention?.step }), 'Retrying', 'Could not retry', `${data.attention?.step} runs again.`)}
                onRetryWithInput={(input) => act(() => api.retryJob(id, { step: data.attention?.step, input }), 'Retrying with the new input', 'Could not retry')}
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
                    <EmptyState compact icon="list" title="No steps yet" message={active ? 'The first step starts in a moment.' : 'This job recorded no steps.'} />
                  ) : (
                    <StepTimeline steps={data.steps} children={data.children} highlight={highlight} defaultOpen={failedStep ? [failedStep.id] : undefined} className="p-4" />
                  )}
                  {data.children && data.children.length > 0 && !data.batch && <ChildJobs jobs={data.children} />}
                </Card>
              )}
              {tab === 'batch' && data.batch && (
                <BatchPanel batch={data.batch} onRetryFailed={() => act(() => api.retryFailedRows(id), 'Retrying the failed rows', 'Could not retry the rows')} />
              )}
              {tab === 'replay' && <TimeTravel jobId={id} workflow={data.workflow} steps={data.steps} input={data.input} />}
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
          message={`${data.title} stops after the current step; steps that already changed something are compensated where the workflow says so.`}
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

function TimeTravel({ jobId, workflow, steps, input }: { jobId: string; workflow: string; steps: { id: string; name: string; status: string }[]; input: Record<string, unknown> }) {
  const toast = useToast()
  const [compareTo, setCompareTo] = useState('')
  const [replay, setReplay] = useState<JobReplay | null>(null)
  const [busy, setBusy] = useState<'replay' | 'rerun' | null>(null)
  const [fromStep, setFromStep] = useState(steps[0]?.id ?? '')
  const [inputText, setInputText] = useState('')
  const [confirm, setConfirm] = useState(false)
  const siblings = useQuery(['jobs', 'of', workflow], () => api.jobs({ workflow, limit: 50 }))
  const others = useMemo(() => (siblings.data ?? []).filter((j) => j.id !== jobId), [siblings.data, jobId])

  const runReplay = async () => {
    setBusy('replay')
    try {
      setReplay(await apiExtra.jobReplay(jobId, compareTo || undefined))
    } catch (e) {
      toast.error('Replay failed', errorMessage(e))
    } finally {
      setBusy(null)
    }
  }

  const parsed = parseJsonText(inputText)
  const rerun = async () => {
    setBusy('rerun')
    try {
      const accepted = await api.rerunJob(jobId, { fromStep, input: parsed.empty ? undefined : (parsed.value as Record<string, unknown>) })
      toast.success('Re-run started', `A new job starts at ${fromStep}.`)
      navigate(pathFor('job', { id: accepted.id }))
    } catch (e) {
      toast.error('Could not re-run', errorMessage(e))
    } finally {
      setBusy(null)
    }
  }

  const diffColumns: TableColumn<StepDiff>[] = [
    { key: 'field', header: 'Field', render: (d) => <Mono className="text-primary">{d.field}</Mono> },
    { key: 'before', header: 'This run', render: (d) => <Mono className="text-secondary">{summarize(d.before, 80)}</Mono> },
    { key: 'after', header: 'Compared run', render: (d) => <Mono className="text-primary">{summarize(d.after, 80)}</Mono> },
  ]

  return (
    <div className="space-y-4">
      <Card title="Replay" subtitle="Walk the run again from its recorded inputs and outputs; compare it with another run of the same workflow to see where they diverged.">
        <div className="flex flex-wrap items-end gap-3">
          <Field label="Compare with" className="min-w-64">
            <Select
              ariaLabel="Compare with"
              value={compareTo}
              onChange={setCompareTo}
              mono
              placeholder="This run only"
              options={[{ value: '', label: 'This run only' }, ...others.map((j) => ({ value: j.id, label: shortJobId(j.id), hint: `${j.status} · ${j.version} · ${j.requester}` }))]}
            />
          </Field>
          <Button variant="primary" icon="history" loading={busy === 'replay'} onClick={() => void runReplay()}>
            Replay
          </Button>
        </div>
        {replay && (
          <div className="mt-4 animate-fade-in space-y-3">
            {replay.comparedTo && (
              <p className="text-caption text-secondary">
                Compared with <TextLink to={pathFor('job', { id: replay.comparedTo })} mono>{shortJobId(replay.comparedTo)}</TextLink>
                {replay.steps.every((s) => !s.diff?.length) && '. No step diverged.'}
              </p>
            )}
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
                      <Table columns={diffColumns} rows={s.diff} rowKey={(d) => d.field} dense caption={`Differences at ${s.id}`} />
                    </div>
                  )}
                </li>
              ))}
            </ol>
          </div>
        )}
      </Card>

      <Card title="Re-run from a step" subtitle="Starts a new job with this one's input, skipping straight to the step you choose.">
        <div className="space-y-3">
          <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
            <Field label="From step">
              <Select ariaLabel="From step" value={fromStep} onChange={setFromStep} mono options={steps.map((s) => ({ value: s.id, label: s.id, hint: `${s.name} · ${s.status}` }))} />
            </Field>
            <Field label="Input" hint="Leave empty to reuse this job's input" htmlFor="rerun-input">
              <Button size="sm" variant="ghost" icon="copy" onClick={() => setInputText(JSON.stringify(input, null, 2))}>
                Start from this job's input
              </Button>
            </Field>
          </div>
          <JsonEditor id="rerun-input" value={inputText} onChange={setInputText} rows={5} placeholder='{ "name": "…" }' objectOnly />
          <div className="flex justify-end">
            <Button variant="primary" icon="play" disabled={!fromStep || !!parsed.error} loading={busy === 'rerun'} onClick={() => setConfirm(true)}>
              Re-run from {fromStep || 'step'}
            </Button>
          </div>
        </div>
      </Card>

      {confirm && (
        <ConfirmDialog
          title={`Re-run from ${fromStep}?`}
          icon="play"
          message="A new job starts now and runs every step from there, with real effects."
          confirmLabel="Start the re-run"
          onConfirm={async () => {
            await rerun()
          }}
          onClose={() => setConfirm(false)}
        />
      )}
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
