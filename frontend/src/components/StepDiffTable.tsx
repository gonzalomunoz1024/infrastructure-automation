import { Table, type TableColumn } from '@iaf/ui'
import type { StepDiff } from '../lib/types'
import { Mono, summarize } from './common'

/** A diff row: the compared run's value, then this run's. */
const DIFF_COLUMNS: TableColumn<StepDiff>[] = [
  { key: 'field', header: 'Field', render: (d) => <Mono className="text-primary">{d.field}</Mono> },
  { key: 'before', header: 'Compared run', render: (d) => <Mono className="text-secondary">{summarize(d.before, 80)}</Mono> },
  { key: 'after', header: 'This run', render: (d) => <Mono className="text-primary">{summarize(d.after, 80)}</Mono> },
]

/** The fields that differ between a run and the one it is compared with: the job page's time travel and the canvas replay. */
export function StepDiffTable({ diff, caption, className }: { diff: readonly StepDiff[]; caption: string; className?: string }) {
  return <Table columns={DIFF_COLUMNS} rows={[...diff]} rowKey={(d) => d.field} dense caption={caption} className={className} />
}
