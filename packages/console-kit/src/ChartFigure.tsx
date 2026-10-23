import type { ReactNode } from 'react'
import { Icon, Table, cx, type TableColumn } from '@iaf/ui'

/*
 * A chart with its numbers. Atlas draws the chart as one image a screen reader names but cannot
 * read and a keyboard cannot walk; the same values as a table sit one click away under it, for
 * them and for anyone who wants the exact figures. The disclosure is a native <details>, so it
 * opens with Enter or Space and says whether it is open, with no script.
 */

export interface ChartFigureProps<T> {
  /** what the chart shows: the figure's caption and the table's */
  title: string
  /** a quiet line under the title */
  description?: ReactNode
  /** the chart: an Atlas LineChart or BarChart */
  children: ReactNode
  /** the same values as rows */
  columns: TableColumn<T>[]
  rows: T[]
  rowKey: (row: T, index: number) => string
  /** the disclosure's words */
  tableLabel?: string
  /** under the table, as the Atlas table's footer: a total, a note */
  tableFooter?: ReactNode
  className?: string
}

export function ChartFigure<T>({ title, description, children, columns, rows, rowKey, tableLabel = 'Show as a table', tableFooter, className }: ChartFigureProps<T>) {
  return (
    <figure className={cx('m-0 min-w-0', className)}>
      <figcaption className="mb-2">
        <span className="block text-caption font-medium text-secondary">{title}</span>
        {description && <span className="mt-0.5 block text-caption text-tertiary">{description}</span>}
      </figcaption>
      {/* a chart draws at a guess of its width until it has measured its box: clip that first frame rather than let the page scroll sideways */}
      <div className="min-w-0 overflow-hidden">{children}</div>
      <details className="group mt-2">
        <summary className="inline-flex cursor-pointer list-none items-center gap-1 rounded-md py-1 text-caption font-medium text-accent transition-opacity hover:opacity-70 [&::-webkit-details-marker]:hidden">
          <Icon name="chevron-right" size={12} className="transition-transform group-open:rotate-90" />
          {tableLabel}
        </summary>
        <Table<T> columns={columns} rows={rows} rowKey={rowKey} caption={title} footer={tableFooter} dense stickyHeader={false} className="mt-2" />
      </details>
    </figure>
  )
}
