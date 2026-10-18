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
  /** POST /jobs/{id}:retry-failed-rows; offered once the batch has ended and some rows did not succeed */
  onRetryFailed?: () => Promise<unknown>
  /** the batch job has ended (the backend retries rows of an ended batch only); without it, every wave has ended */
  ended?: boolean
  className?: string
}

const isDone = (s: string) => ['succeeded', 'failed', 'cancelled', 'rolled-back', 'skipped'].includes(s)
const isFailed = (s: string) => s === 'failed' || s === 'rolled-back'
/** a wave ends done, stopped (cancelled) or skipped (the batch stopped before it) */
const waveEnded = (s: string) => ['done', 'stopped', 'skipped', 'succeeded', 'failed', 'cancelled'].includes(s)
const RETRIED = ['failed', 'cancelled', 'skipped'] as const

/** The rows `:retry-failed-rows` runs again, by status: the failed, cancelled and skipped ones. */
export function retryableRows(batch: Batch): { total: number } & Record<(typeof RETRIED)[number], number> {
  const count = (status: string) => batch.rows.filter((r) => r.status === status).length
  const by = { failed: count('failed'), cancelled: count('cancelled'), skipped: count('skipped') }
  return { ...by, total: by.failed + by.cancelled + by.skipped }
}

/** The retry button's words: "Retry 2 failed rows", or "Retry 6 rows" when some were skipped or cancelled. */
export function retryLabel(rows: ReturnType<typeof retryableRows>): { label: string; detail?: string } {
  if (rows.total === rows.failed) return { label: `Retry ${plural(rows.total, 'failed row')}` }
  const parts = RETRIED.filter((s) => rows[s] > 0).map((s) => `${rows[s]} ${s}`)
  return { label: `Retry ${plural(rows.total, 'row')}`, detail: `${parts.join(', ')}: they run again in a new wave` }
}

export function BatchPanel({ batch, onRetryFailed, ended, className }: BatchPanelProps) {
  const [busy, setBusy] = useState(false)
  const [filter, setFilter] = useState<'all' | 'failed'>('all')
  const total = batch.rows.length
  const done = batch.rows.filter((r) => isDone(r.status)).length
  const failed = batch.rows.filter((r) => isFailed(r.status)).length
  const failedPct = total ? (failed / total) * 100 : 0
  const allEnded = batch.waves.length > 0 && batch.waves.every((w) => waveEnded(w.status))
  const retryable = retryableRows(batch)
  const retry = onRetryFailed && (ended ?? allEnded) && retryable.total > 0 ? retryLabel(retryable) : null
  const currentWave = Math.max(
    0,
    batch.waves.findIndex((w) => w.status === 'running' || w.status === 'pending' || w.status === 'queued'),
  )
  const waves = batch.waves.map((w) => ({
    id: String(w.index),
    label: `Wave ${w.index + 1} · ${w.percent}%`,
    description: `${plural(w.size, 'row')} · ${w.status.replace(/-/g, ' ')}`,
  }))
  const current = allEnded ? batch.waves.length : currentWave

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
          {onRetryFailed && retry && (
            <div className="flex flex-col items-end gap-1">
              <Button
                icon="refresh"
                variant="primary"
                loading={busy}
                onClick={() => {
                  setBusy(true)
                  // the page reports a failure (toast); nothing more to do here
                  void onRetryFailed()
                    .catch(() => undefined)
                    .finally(() => setBusy(false))
                }}
              >
                {retry.label}
              </Button>
              {retry.detail && <span className="text-caption2 text-tertiary">{retry.detail}</span>}
            </div>
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
