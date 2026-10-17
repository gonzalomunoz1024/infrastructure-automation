import { useState } from 'react'
import { Button, ProgressBar, StateTag, Stepper, Table, Tag, cx, percent, plural, type TableColumn } from '@iaf/ui'
import { pathFor } from '../app/router'
import type { Batch, BatchRow } from '../lib/types'
import { TextLink, When, summarize } from './common'

/*
 * A bulk run: the waves as a stepper with their percentages, the rows with their own job and
 * state, and the way to retry the ones that failed.
 */

export interface BatchPanelProps {
  batch: Batch
  onRetryFailed?: () => Promise<void>
  className?: string
}

const isDone = (s: string) => ['succeeded', 'failed', 'cancelled', 'rolled-back', 'skipped'].includes(s)
const isFailed = (s: string) => s === 'failed' || s === 'rolled-back'

export function BatchPanel({ batch, onRetryFailed, className }: BatchPanelProps) {
  const [busy, setBusy] = useState(false)
  const [filter, setFilter] = useState<'all' | 'failed'>('all')
  const total = batch.rows.length
  const done = batch.rows.filter((r) => isDone(r.status)).length
  const failed = batch.rows.filter((r) => isFailed(r.status)).length
  const failedPct = total ? (failed / total) * 100 : 0
  const currentWave = Math.max(
    0,
    batch.waves.findIndex((w) => w.status === 'running' || w.status === 'pending' || w.status === 'queued'),
  )
  const waves = batch.waves.map((w) => ({
    id: String(w.index),
    label: `Wave ${w.index + 1} · ${w.percent}%`,
    description: `${plural(w.size, 'row')} · ${w.status.replace(/-/g, ' ')}`,
  }))
  const current = batch.waves.every((w) => isDone(w.status)) ? batch.waves.length : currentWave

  const rows = filter === 'failed' ? batch.rows.filter((r) => isFailed(r.status)) : batch.rows
  const columns: TableColumn<BatchRow>[] = [
    { key: 'index', header: '#', width: '3rem', numeric: true, accessor: (r) => r.index, render: (r) => <span className="text-tertiary">{r.index + 1}</span> },
    { key: 'input', header: 'Input', render: (r) => <span className="font-mono text-caption text-primary">{summarize(r.input, 80)}</span> },
    { key: 'status', header: 'Status', sortable: true, accessor: (r) => r.status, render: (r) => <StateTag status={r.status} size="sm" /> },
    { key: 'error', header: 'Error', render: (r) => (r.error ? <span className="text-caption text-critical">{r.error}</span> : <span className="text-tertiary">—</span>) },
    {
      key: 'job',
      header: 'Job',
      render: (r) =>
        r.jobId ? (
          <TextLink to={pathFor('job', { id: r.jobId })} mono onClick={(e) => e.stopPropagation()}>
            {r.jobId}
          </TextLink>
        ) : (
          <span className="text-tertiary">—</span>
        ),
    },
  ]

  return (
    <div className={cx('space-y-5', className)}>
      <div className="rounded-lg border border-stroke-light bg-surface p-5">
        <div className="flex flex-wrap items-start justify-between gap-4">
          <div className="min-w-0 flex-1">
            <ProgressBar value={done} max={total || 1} label="Rows done" showLabel showValue tone={failed > 0 && failedPct >= batch.rollout.stopIfFailedPercent ? 'critical' : failed > 0 ? 'warning' : 'accent'} />
            <p className="mt-1.5 text-caption text-tertiary">
              {done} of {plural(total, 'row')} done
              {failed > 0 && <span className="text-critical"> · {plural(failed, 'failure')} ({percent(failedPct, { digits: 1 })})</span>}
              {' · '}
              waves {batch.rollout.waves.join(' / ')}% · {batch.rollout.maxParallel} in parallel · stop at {batch.rollout.stopIfFailedPercent}% failed
            </p>
            {batch.stoppedReason && (
              <p className="mt-2 rounded-md bg-critical-tint px-3 py-2 text-caption text-critical" role="alert">
                Stopped: {batch.stoppedReason}
              </p>
            )}
          </div>
          {onRetryFailed && failed > 0 && (
            <Button
              icon="refresh"
              variant="primary"
              loading={busy}
              onClick={() => {
                setBusy(true)
                void onRetryFailed().finally(() => setBusy(false))
              }}
            >
              Retry {plural(failed, 'failed row')}
            </Button>
          )}
        </div>
        {waves.length > 0 && <Stepper steps={waves} current={current} className="mt-5" />}
        {batch.waves.some((w) => w.startedAt) && (
          <ul className="mt-3 flex flex-wrap gap-x-5 gap-y-1 text-caption2 text-tertiary">
            {batch.waves.map((w) => (
              <li key={w.index}>
                wave {w.index + 1}: {w.startedAt ? <>started <When at={w.startedAt} /></> : 'not started'}
                {w.endedAt && (
                  <>
                    , ended <When at={w.endedAt} />
                  </>
                )}
              </li>
            ))}
          </ul>
        )}
      </div>

      <div>
        <div className="mb-2 flex items-center justify-between gap-3">
          <h3 className="text-subheadline font-semibold text-primary">Rows</h3>
          <div className="flex items-center gap-1.5">
            <Tag size="sm" tone={filter === 'all' ? 'accent' : 'neutral'} className="cursor-pointer" title="Show every row">
              <button type="button" onClick={() => setFilter('all')}>
                all {total}
              </button>
            </Tag>
            <Tag size="sm" tone={filter === 'failed' ? 'critical' : 'neutral'} title="Show only failed rows">
              <button type="button" onClick={() => setFilter('failed')}>
                failed {failed}
              </button>
            </Tag>
          </div>
        </div>
        <Table columns={columns} rows={rows} rowKey={(r) => String(r.index)} dense emptyIcon="list" emptyTitle={filter === 'failed' ? 'No failed rows' : 'No rows'} caption="Batch rows" maxHeight="max-h-[32rem]" />
      </div>
    </div>
  )
}
