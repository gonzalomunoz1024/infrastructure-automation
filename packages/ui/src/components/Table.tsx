import { useMemo, useState, type KeyboardEvent, type ReactNode } from 'react'
import { cx } from '../lib/cx'
import { Icon, type IconName } from './Icon'
import { EmptyState } from './EmptyState'
import { SkeletonRows } from './Skeleton'

export type CellValue = string | number | boolean | null | undefined

export interface TableColumn<T> {
  key: string
  header: ReactNode
  /** custom cell; defaults to the accessor value (or row[key]) as text */
  render?: (row: T, index: number) => ReactNode
  /** value used for sorting and for the default cell text */
  accessor?: (row: T) => CellValue
  align?: 'left' | 'right' | 'center'
  /** right-aligned tabular numerals */
  numeric?: boolean
  sortable?: boolean
  /** CSS width, e.g. '12rem' or '20%' */
  width?: string
  className?: string
  headerClassName?: string
}

export interface TableSort {
  key: string
  dir: 'asc' | 'desc'
}

export interface TableProps<T> {
  columns: TableColumn<T>[]
  rows: T[]
  rowKey: (row: T, index: number) => string
  onRowClick?: (row: T) => void
  /** controlled sort */
  sort?: TableSort
  defaultSort?: TableSort
  onSortChange?: (sort: TableSort) => void
  /** the server sorts: do not sort rows locally */
  serverSort?: boolean
  loading?: boolean
  skeletonRows?: number
  /** custom empty body; the default is an EmptyState from emptyTitle/emptyMessage/emptyIcon */
  empty?: ReactNode
  emptyTitle?: string
  emptyMessage?: string
  emptyIcon?: IconName
  dense?: boolean
  /** header stays visible while the table scrolls (needs maxHeight) */
  stickyHeader?: boolean
  /** hairline frame with rounded corners (default) */
  bordered?: boolean
  /** class for the scroll container, e.g. 'max-h-[60vh]' */
  maxHeight?: string
  rowClassName?: (row: T) => string | undefined
  /** key of the row to keep highlighted (the one open in a drawer) */
  selectedKey?: string | null
  footer?: ReactNode
  /** visually hidden table caption */
  caption?: string
  className?: string
}

function compare(a: CellValue, b: CellValue): number {
  if (a == null && b == null) return 0
  if (a == null) return 1
  if (b == null) return -1
  if (typeof a === 'number' && typeof b === 'number') return a - b
  if (typeof a === 'boolean' && typeof b === 'boolean') return Number(a) - Number(b)
  return String(a).localeCompare(String(b), undefined, { numeric: true, sensitivity: 'base' })
}

function valueOf<T>(col: TableColumn<T>, row: T): CellValue {
  if (col.accessor) return col.accessor(row)
  const v = (row as Record<string, unknown>)[col.key]
  return v == null ? v : typeof v === 'object' ? JSON.stringify(v) : (v as CellValue)
}

const ALIGN = { left: 'text-left', right: 'text-right', center: 'text-center' }

/** The one data table: sticky hairline header, sortable columns, hover rows, empty and loading states. */
export function Table<T>({
  columns,
  rows,
  rowKey,
  onRowClick,
  sort: controlledSort,
  defaultSort,
  onSortChange,
  serverSort = false,
  loading = false,
  skeletonRows = 4,
  empty,
  emptyTitle = 'Nothing here yet',
  emptyMessage,
  emptyIcon = 'ring',
  dense = false,
  stickyHeader = true,
  bordered = true,
  maxHeight,
  rowClassName,
  selectedKey,
  footer,
  caption,
  className,
}: TableProps<T>) {
  const [internalSort, setInternalSort] = useState<TableSort | undefined>(defaultSort)
  const sort = controlledSort ?? internalSort

  const sorted = useMemo(() => {
    if (!sort || serverSort) return rows
    const col = columns.find((c) => c.key === sort.key)
    if (!col) return rows
    const dir = sort.dir === 'asc' ? 1 : -1
    return rows
      .map((row, i) => ({ row, i }))
      .sort((a, b) => compare(valueOf(col, a.row), valueOf(col, b.row)) * dir || a.i - b.i)
      .map((x) => x.row)
  }, [rows, sort, serverSort, columns])

  const toggle = (key: string) => {
    const next: TableSort = sort?.key === key ? { key, dir: sort.dir === 'asc' ? 'desc' : 'asc' } : { key, dir: 'asc' }
    if (!controlledSort) setInternalSort(next)
    onSortChange?.(next)
  }

  const cell = (col: TableColumn<T>, row: T, i: number): ReactNode => {
    if (col.render) return col.render(row, i)
    const v = valueOf(col, row)
    return v == null ? '' : String(v)
  }

  const padding = dense ? 'px-3 py-1.5' : 'px-4 py-2.5'
  const colSpan = Math.max(1, columns.length)

  return (
    <div className={cx('relative', bordered && 'rounded-lg border border-stroke-light bg-surface', className)}>
      <div className={cx('overflow-auto', bordered && 'rounded-lg', maxHeight)}>
        <table className="w-full border-collapse text-sm">
          {caption && <caption className="sr-only">{caption}</caption>}
          <thead className={cx('text-left text-caption text-secondary', stickyHeader && 'sticky top-0 z-10')}>
            <tr className="bg-surface-secondary">
              {columns.map((col) => {
                const align = col.align ?? (col.numeric ? 'right' : 'left')
                const active = sort?.key === col.key
                return (
                  <th
                    key={col.key}
                    scope="col"
                    style={col.width ? { width: col.width } : undefined}
                    aria-sort={active ? (sort?.dir === 'asc' ? 'ascending' : 'descending') : undefined}
                    className={cx('whitespace-nowrap font-medium', padding, ALIGN[align], col.headerClassName)}
                  >
                    {col.sortable ? (
                      <button
                        type="button"
                        onClick={() => toggle(col.key)}
                        className={cx('inline-flex items-center gap-1 transition-colors hover:text-primary', align === 'right' && 'flex-row-reverse')}
                      >
                        {col.header}
                        <Icon
                          name="chevron-down"
                          size={11}
                          className={cx('text-accent transition-all', active ? (sort?.dir === 'asc' ? 'rotate-180 opacity-100' : 'opacity-100') : 'opacity-0')}
                        />
                      </button>
                    ) : (
                      col.header
                    )}
                  </th>
                )
              })}
            </tr>
          </thead>
          <tbody>
            {loading ? (
              <tr>
                <td colSpan={colSpan} className="p-4">
                  <SkeletonRows n={skeletonRows} rowClassName="h-10" />
                </td>
              </tr>
            ) : sorted.length === 0 ? (
              <tr>
                <td colSpan={colSpan}>
                  {empty ?? <EmptyState icon={emptyIcon} title={emptyTitle} message={emptyMessage} compact />}
                </td>
              </tr>
            ) : (
              sorted.map((row, i) => {
                const key = rowKey(row, i)
                const clickable = !!onRowClick
                const onKeyDown = (e: KeyboardEvent<HTMLTableRowElement>) => {
                  if (!clickable) return
                  if (e.key === 'Enter' || e.key === ' ') {
                    e.preventDefault()
                    onRowClick?.(row)
                  }
                }
                return (
                  <tr
                    key={key}
                    tabIndex={clickable ? 0 : undefined}
                    onClick={clickable ? () => onRowClick?.(row) : undefined}
                    onKeyDown={onKeyDown}
                    aria-selected={selectedKey === key || undefined}
                    className={cx(
                      'border-t border-stroke-light transition-colors hover:bg-surface-secondary',
                      clickable && 'cursor-pointer',
                      selectedKey === key && 'bg-surface-secondary',
                      rowClassName?.(row),
                    )}
                  >
                    {columns.map((col) => {
                      const align = col.align ?? (col.numeric ? 'right' : 'left')
                      return (
                        <td key={col.key} className={cx(padding, ALIGN[align], col.numeric && 'tabular-nums', 'text-secondary', col.className)}>
                          {cell(col, row, i)}
                        </td>
                      )
                    })}
                  </tr>
                )
              })
            )}
          </tbody>
        </table>
      </div>
      {footer && (
        <div className="flex items-center justify-between gap-3 border-t border-stroke-light px-4 py-2.5 text-caption text-tertiary">
          {footer}
        </div>
      )}
    </div>
  )
}
