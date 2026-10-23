import { ProgressBar, Tag, plural, type Tone } from '@iaf/ui'
import { Section, StatusBadge, VirtualTable, When, WfrnText, type VirtualColumn } from '@iaf/console-kit'
import { pathOfWfrn } from '../../app/console'
import { Link, href, pathFor } from '../../app/router'
import type { ConsoleType } from '../../lib/descriptors'
import { describeRollout, rolloutOf } from '../../lib/rollout'
import type { BatchRow, BatchWave, BffJobDetail } from '../../lib/types'
import { ROW_WORDS, rowCounts } from './parts'

/*
 * A bulk run (WP 4.2): how far it got, the rollout in words, its waves in order, why it stopped
 * if it stopped early, and every row with its resource, its wave and its own request. A row names
 * its resource once its wave released it.
 */

const WAVE_TONE: Record<string, Tone> = { pending: 'neutral', 'waiting-approval': 'warning', running: 'accent', succeeded: 'healthy', failed: 'critical', stopped: 'neutral' }
const WAVE_WORDS: Record<string, string> = { pending: 'Not released yet', 'waiting-approval': 'Waits for approvers', running: 'Running', succeeded: 'Succeeded', failed: 'Rows failed', stopped: 'Stopped' }

export function BatchSection({ job, types }: { job: BffJobDetail; types: ConsoleType[] }) {
  const batch = job.batch
  if (!batch) return null
  const total = job.progress?.total ?? batch.rows.length
  const done = job.progress?.done ?? 0
  const failed = job.progress?.failed ?? 0
  const counts = rowCounts(batch)

  const columns: VirtualColumn<BatchRow>[] = [
    { key: 'index', header: '#', width: '4rem', numeric: true, accessor: (r) => r.index + 1 },
    {
      key: 'wfrn',
      header: 'Resource',
      minRem: 14,
      accessor: (r) => r.wfrn ?? '',
      render: (r) => {
        if (!r.wfrn) return <span className="text-tertiary">Named when wave {r.wave + 1} goes</span>
        const path = pathOfWfrn(r.wfrn, types)
        return <WfrnText wfrn={r.wfrn} href={path ? href(path) : undefined} />
      },
    },
    { key: 'wave', header: 'Wave', width: '5rem', numeric: true, accessor: (r) => r.wave + 1 },
    { key: 'status', header: 'Status', width: '11rem', accessor: (r) => r.status, render: (r) => <StatusBadge status={r.status} label={ROW_WORDS[r.status]} /> },
    { key: 'error', header: 'What happened', minRem: 12, accessor: (r) => r.error ?? '', render: (r) => (r.error ? <span className="text-secondary">{r.error}</span> : <span className="text-tertiary">—</span>) },
    {
      key: 'job',
      header: 'Request',
      width: '9rem',
      accessor: (r) => r.jobId ?? '',
      render: (r) =>
        r.jobId ? (
          <Link to={pathFor('request', { id: r.jobId })} className="font-mono text-caption text-accent hover:opacity-70">
            {r.jobId}
          </Link>
        ) : (
          <span className="text-tertiary">—</span>
        ),
    },
  ]

  return (
    <Section title="Rows and waves" description={`${describeRollout(rolloutOf(batch.rollout, undefined))}.`} className="mb-8">
      <div className="mb-5 space-y-2" data-batch-progress={`${done}/${total}`}>
        <ProgressBar value={done} max={Math.max(1, total)} tone={failed ? 'critical' : job.status === 'succeeded' ? 'healthy' : 'accent'} label={`${done} of ${total} rows done${failed ? `, ${failed} failed` : ''}`} showLabel showValue />
        <p className="flex flex-wrap gap-x-3 gap-y-1 text-caption text-secondary" aria-label="Rows by status">
          {counts.map((c) => (
            <span key={c.status}>
              {plural(c.count, 'row')} {(ROW_WORDS[c.status] ?? c.status).toLowerCase()}
            </span>
          ))}
        </p>
      </div>

      {batch.stoppedReason && (
        <p role="note" className="mb-5 rounded-lg border border-stroke-light bg-surface-secondary px-3 py-2.5 text-sm text-primary" data-stopped-reason="true">
          <span className="font-medium">The run stopped early. </span>
          {batch.stoppedReason}.
        </p>
      )}

      <ol className="mb-6 grid gap-2 sm:grid-cols-2 lg:grid-cols-3" aria-label="Waves">
        {batch.waves.map((w) => (
          <WaveItem key={w.index} wave={w} count={batch.waves.length} />
        ))}
      </ol>

      <VirtualTable label={`The ${plural(batch.rows.length, 'row')} of this run`} columns={columns} rows={batch.rows} rowKey={(r) => String(r.index)} maxHeight="28rem" rowHeight={44} />
    </Section>
  )
}

function WaveItem({ wave, count }: { wave: BatchWave; count: number }) {
  return (
    <li className="rounded-lg border border-stroke-light bg-surface px-3 py-2.5 text-sm" data-wave={wave.index} data-wave-status={wave.status}>
      <div className="flex items-center justify-between gap-2">
        <span className="font-medium text-primary">
          Wave {wave.index + 1} of {count}
        </span>
        <Tag size="sm" tone={WAVE_TONE[wave.status] ?? 'neutral'} dot>
          {WAVE_WORDS[wave.status] ?? wave.status}
        </Tag>
      </div>
      <p className="mt-1 text-caption text-secondary">
        {plural(wave.size, 'row')}, up to {wave.percent}% of the run
        {wave.startedAt && (
          <>
            {' '}
            · started <When at={wave.startedAt} />
          </>
        )}
        {wave.endedAt && (
          <>
            {' '}
            · ended <When at={wave.endedAt} />
          </>
        )}
      </p>
    </li>
  )
}
