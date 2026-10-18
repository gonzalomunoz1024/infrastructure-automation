import { useState, type ReactNode } from 'react'
import { Button, Card, ConfirmDialog, EmptyState, PageHeader, ProgressBar, SkeletonRows, StateTag, StatusDot, Stepper, Table, Tag, duration, plural, useToast, type TableColumn } from '@iaf/ui'
import type { PageProps } from '../app/router'
import { pathFor } from '../app/router'
import { api, errorMessage } from '../lib/api'
import { invalidate, useQuery } from '../lib/queries'
import type { Release, ReleaseGate } from '../lib/types'
import { ErrorState, Facts, Mono, TextLink, When, useNow } from '../components/common'
import { gateDot, isActiveRelease, releaseSubject, stageLabel } from './ReleasesPage'

type Command = 'promote' | 'pause' | 'resume' | 'rollback'

/** The same commands for both kinds; the wording says what moves: jobs for a workflow version, calls (per job) for a provider version. */
const COMMANDS: Record<Command, { title: (r: Release) => string; message: (r: Release) => string; label: string; tone?: 'warning' | 'danger'; done: string }> = {
  promote: {
    title: (r) => `Promote ${r.to}?`,
    message: (r) => (r.provider ? `Every call moves to ${r.to} now and it becomes the provider's current version, skipping the remaining stages and their gates.` : `Every new job moves to ${r.to} now, skipping the remaining stages and their gates.`),
    label: 'Promote',
    done: 'Promoted',
  },
  pause: {
    title: () => 'Pause the canary?',
    message: (r) => (r.provider ? `Every call uses ${r.from ?? 'the current version'} while paused; no stage advances until someone resumes.` : `Traffic stays at ${r.stagePercent}% on ${r.to}; no stage advances until someone resumes.`),
    label: 'Pause',
    tone: 'warning',
    done: 'Paused',
  },
  resume: {
    title: () => 'Resume the canary?',
    message: () => 'The gates are evaluated again and the next stage starts when they pass.',
    label: 'Resume',
    done: 'Resumed',
  },
  rollback: {
    title: (r) => `Roll back to ${r.from ?? 'the previous version'}?`,
    message: (r) =>
      r.provider
        ? `Every call goes back to ${r.from ?? 'the current version'}, which stays the provider's current version; the candidate is kept beside it.`
        : `Every new job goes back to ${r.from ?? 'the previous version'}. Jobs already running on ${r.to} finish where they are.`,
    label: 'Roll back',
    tone: 'danger',
    done: 'Rolled back',
  },
}

/** How a gate's figure reads: its value and threshold in the gate's own unit, and the error types as a list; `unit` is what the sample counts. */
export function gateFigures(g: ReleaseGate, unit: 'job' | 'call' = 'job'): { value: ReactNode; threshold: ReactNode; detail: ReactNode } {
  const num = (v: number | string | undefined): number | null => (typeof v === 'number' ? v : typeof v === 'string' && v.trim() !== '' && Number.isFinite(Number(v)) ? Number(v) : null)
  const raw = (v: number | string | undefined) => (v === undefined ? '—' : typeof v === 'number' ? (Number.isInteger(v) ? String(v) : v.toFixed(2)) : v)
  const id = g.id.toLowerCase()
  const label = g.label.toLowerCase()
  const dash = <span className="text-tertiary">—</span>
  if (id === 'p95' || id.includes('duration') || id.includes('latency') || /\bp\d{2}\b/.test(label) || label.includes('duration')) {
    const v = num(g.value)
    const t = num(g.threshold)
    return { value: v === null ? dash : duration(v), threshold: t === null ? dash : `at most ${duration(t)}`, detail: g.detail || dash }
  }
  if (id.includes('rate') || label.includes('rate')) {
    const v = num(g.value)
    const t = num(g.threshold)
    return { value: v === null ? dash : `${v.toFixed(1)}%`, threshold: t === null ? dash : `at least ${t.toFixed(1)}%`, detail: g.detail || dash }
  }
  if (id.includes('error-type') || id.includes('error_type') || label.includes('error type')) {
    const v = num(g.value)
    const m = /^(?:new on the candidate: )(.+)$/.exec(g.detail ?? '')
    const classes = m ? m[1].split(',').map((s) => s.trim()).filter(Boolean) : []
    const lead = g.detail && classes.length > 0 ? 'New on the candidate' : g.detail
    return {
      value: v === null ? dash : `${v} new`,
      threshold: 'none new',
      detail: classes.length > 0 ? (
        <span className="inline-flex flex-wrap items-center gap-1.5">
          <span>{lead}:</span>
          <ul className="inline-flex flex-wrap gap-1" aria-label="Error types new on the candidate">
            {classes.map((c) => (
              <li key={c}>
                <Tag size="sm" tone="critical" mono>
                  {c}
                </Tag>
              </li>
            ))}
          </ul>
        </span>
      ) : (
        g.detail || dash
      ),
    }
  }
  if (id === 'sample' || label.includes('sample')) {
    const v = num(g.value)
    const t = num(g.threshold)
    return { value: v === null ? dash : plural(v, unit), threshold: t === null ? dash : `at least ${plural(t, unit)}`, detail: g.detail || dash }
  }
  return { value: raw(g.value), threshold: raw(g.threshold), detail: g.detail || dash }
}

export default function ReleasePage({ route }: PageProps) {
  const id = route.params.id
  const toast = useToast()
  const [confirm, setConfirm] = useState<Command | null>(null)
  const [evaluating, setEvaluating] = useState(false)
  const [polling, setPolling] = useState(true)
  const now = useNow(polling ? 1000 : 30_000)
  const release = useQuery(['release', id], () => api.release(id), { refetchInterval: polling ? 5000 : undefined })
  const data = release.data
  const active = !data || isActiveRelease(data)
  if (active !== polling) setPolling(active)

  const run = async (cmd: Command) => {
    const call = { promote: api.promoteRelease, pause: api.pauseRelease, resume: api.resumeRelease, rollback: api.rollbackRelease }[cmd]
    try {
      await call(id)
      invalidate('release')
      toast.success(COMMANDS[cmd].done, data ? `${releaseSubject(data).name} ${data.to}` : undefined)
    } catch (e) {
      toast.error(`Could not ${COMMANDS[cmd].label.toLowerCase()}`, errorMessage(e))
      throw e
    }
  }

  const evaluate = async () => {
    setEvaluating(true)
    try {
      await api.evaluateRelease(id)
      invalidate('release')
      toast.success('Gates evaluated', 'The stage advances on its own when every gate passes.')
    } catch (e) {
      toast.error('Could not evaluate', errorMessage(e))
    } finally {
      setEvaluating(false)
    }
  }

  if (release.error && !data) {
    return (
      <>
        <PageHeader eyebrow="Release" title={<span className="font-mono">{id}</span>} back={{ href: `#${pathFor('releases')}`, label: 'Releases' }} />
        <ErrorState error={release.error} title="Could not load the release" onRetry={() => void release.refetch()} />
      </>
    )
  }

  const subject = data ? releaseSubject(data) : null
  const unit = subject?.unit ?? 'job'
  const gateColumns: TableColumn<ReleaseGate>[] = [
    {
      key: 'result',
      header: 'Result',
      width: '8rem',
      render: (g) => <StatusDot kind={gateDot(g)} label={g.passed === null ? 'pending' : g.passed ? 'passed' : 'failed'} />,
    },
    { key: 'label', header: 'Gate', render: (g) => <span className="text-primary">{g.label}</span> },
    { key: 'value', header: 'Value', width: '8rem', numeric: true, render: (g) => <span className="whitespace-nowrap tabular-nums text-primary">{gateFigures(g, unit).value}</span> },
    { key: 'threshold', header: 'Threshold', width: '9rem', numeric: true, render: (g) => <span className="whitespace-nowrap tabular-nums text-secondary">{gateFigures(g, unit).threshold}</span> },
    { key: 'detail', header: 'Detail', render: (g) => <span className="block min-w-56 text-secondary">{gateFigures(g, unit).detail}</span> },
  ]

  const current = data ? (data.status === 'promoted' ? data.stages.length : data.stageIndex) : 0
  const share = data ? (data.status === 'promoted' ? 100 : data.status === 'rolled-back' ? 0 : data.stagePercent) : 0
  const totalJobs = data ? data.jobs.current + data.jobs.candidate : 0

  return (
    <>
      <PageHeader
        eyebrow={subject?.kind === 'provider' ? 'Provider release' : 'Release'}
        title={
          data && subject ? (
            <span>
              {subject.name} <span className="font-mono text-secondary">{data.from ?? 'new'}</span> → <span className="font-mono">{data.to}</span>
            </span>
          ) : (
            <span className="font-mono">{id}</span>
          )
        }
        subtitle={
          data && (
            <span className="inline-flex flex-wrap items-center gap-2">
              <StateTag status={data.status} size="sm" />
              <Tag size="sm" outline>
                {data.strategy}
              </Tag>
              {subject?.kind === 'provider' && (
                <Tag size="sm" outline title="A provider version: the gates are judged over its calls, every environment but dev">
                  provider
                </Tag>
              )}
              <span>{stageLabel(data)}</span>
              {active && (
                <Tag size="sm" tone="accent" dot pulse title="Refreshing every 5 seconds">
                  live
                </Tag>
              )}
            </span>
          )
        }
        back={{ href: `#${pathFor('releases')}`, label: 'Releases' }}
        actions={
          data &&
          active && (
            <>
              <Button icon="refresh" loading={evaluating} onClick={() => void evaluate()}>
                Evaluate now
              </Button>
              {data.status === 'paused' ? (
                <Button icon="play" onClick={() => setConfirm('resume')}>
                  Resume
                </Button>
              ) : (
                <Button icon="pause" onClick={() => setConfirm('pause')}>
                  Pause
                </Button>
              )}
              <Button variant="warning-outline" icon="arrow-left" onClick={() => setConfirm('rollback')}>
                Roll back
              </Button>
              <Button variant="primary" icon="release" onClick={() => setConfirm('promote')}>
                Promote
              </Button>
            </>
          )
        }
      />

      {!data ? (
        <SkeletonRows n={5} />
      ) : (
        <div className="space-y-5">
          {data.stages.length > 0 && (
            <Card title="Stages">
              <Stepper
                steps={data.stages.map((p, i) => ({
                  id: `stage-${i}`,
                  label: `${p}%`,
                  description: i < current ? 'passed' : i === current ? (data.status === 'paused' ? 'paused' : data.status === 'rolled-back' ? 'stopped' : 'in progress') : undefined,
                }))}
                current={current}
              />
            </Card>
          )}

          <div className="grid grid-cols-1 gap-5 lg:grid-cols-[minmax(0,1fr)_22rem]">
            <div className="min-w-0 space-y-5">
              <Card title="Traffic" subtitle={totalJobs ? `${plural(totalJobs, unit)} since the release started${unit === 'call' ? ', dev left out' : ''}` : `No ${unit}s since the release started`}>
                <div className="space-y-4">
                  <ProgressBar value={share} tone={data.status === 'rolled-back' ? 'critical' : data.status === 'paused' ? 'warning' : 'accent'} label={`${data.to} (candidate) · ${plural(data.jobs.candidate, unit)}`} showLabel showValue />
                  <ProgressBar value={100 - share} label={`${data.from ?? 'previous'} (current) · ${plural(data.jobs.current, unit)}`} showLabel showValue />
                </div>
              </Card>

              <Table columns={gateColumns} rows={data.gates} rowKey={(g) => g.id} dense emptyIcon="approval" emptyTitle="No gates" emptyMessage="This release advances on time alone." caption="Gates" />
            </div>

            <aside className="min-w-0 space-y-4">
              <Card title="Details">
                <Facts
                  columns={2}
                  items={[
                    { label: subject?.kind === 'provider' ? 'Provider' : 'Workflow', value: <TextLink to={subject?.href ?? ''} mono>{subject?.name}</TextLink>, wide: true },
                    { label: 'From', value: <Mono className="text-primary">{data.from ?? '—'}</Mono> },
                    { label: 'To', value: <Mono className="text-primary">{data.to}</Mono> },
                    { label: 'Started', value: <When at={data.startedAt} now={now} /> },
                    { label: 'Ended', value: data.endedAt ? <When at={data.endedAt} now={now} /> : <span className="text-tertiary">in flight</span> },
                    { label: 'In this stage', value: active ? <span className="tabular-nums">{duration(now - Date.parse(data.stageStartedAt))}</span> : '—' },
                    ...(subject?.kind === 'provider'
                      ? [{ label: 'Judged on', value: 'the calls of every environment but dev; one version per job', wide: true }]
                      : [
                          { label: 'Versions', value: <TextLink to={pathFor('workflow-versions', { name: subject?.name ?? '' })}>all versions</TextLink> },
                          { label: 'Jobs', value: <TextLink to={pathFor('jobs', {}, { workflow: subject?.name ?? '' })}>jobs of {subject?.name}</TextLink>, wide: true },
                        ]),
                  ]}
                />
              </Card>
              <Card title="History" flush>
                {data.history.length === 0 ? (
                  <EmptyState compact icon="history" title="No events yet" />
                ) : (
                  <ol className="divide-y divide-stroke-light">
                    {data.history
                      .slice()
                      .sort((a, b) => b.at.localeCompare(a.at))
                      .map((h, i) => (
                        <li key={`${h.at}-${i}`} className="px-5 py-2.5">
                          <p className="text-sm text-primary">{h.event}</p>
                          <p className="text-caption text-tertiary">
                            <When at={h.at} now={now} />
                            {h.by && ` · ${h.by}`}
                          </p>
                        </li>
                      ))}
                  </ol>
                )}
              </Card>
            </aside>
          </div>
        </div>
      )}

      {confirm && data && (
        <ConfirmDialog
          title={COMMANDS[confirm].title(data)}
          message={COMMANDS[confirm].message(data)}
          confirmLabel={COMMANDS[confirm].label}
          tone={COMMANDS[confirm].tone}
          icon={confirm === 'rollback' ? 'arrow-left' : confirm === 'promote' ? 'release' : confirm === 'pause' ? 'pause' : 'play'}
          onConfirm={() => run(confirm)}
          onClose={() => setConfirm(null)}
        />
      )}
    </>
  )
}

