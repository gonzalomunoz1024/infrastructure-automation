import { useEffect, useId, useMemo, useRef, useState, type KeyboardEvent, type ReactNode } from 'react'
import { useVirtualizer } from '@tanstack/react-virtual'
import { Checkbox, EmptyState, Icon, SkeletonRows, cx, type IconName } from '@iaf/ui'

/*
 * A table that stays quick at ten thousand rows: only the rows on screen are in the DOM
 * (@tanstack/react-virtual, the one third-party UI dependency the console allows). It keeps the
 * ARIA table roles so screen readers read it as one, a keyboard moves through it (arrows, Home,
 * End, Enter opens, Space selects), the header sorts, and a column grid keeps the header and the
 * rows aligned. Wide tables scroll sideways inside their frame, never the page.
 */

export type CellValue = string | number | boolean | null | undefined

export interface VirtualColumn<T> {
  key: string
  header: ReactNode
  /** custom cell; defaults to the accessor value (or row[key]) as text */
  render?: (row: T, index: number) => ReactNode
  /** value used for sorting and for the default cell text */
  accessor?: (row: T) => CellValue
  /** the grid track, e.g. '10rem' or 'minmax(12rem, 2fr)' (default 'minmax(9rem, 1fr)') */
  width?: string
  /** the least width the column needs, in rem, for the sideways scroll floor (default 9) */
  minRem?: number
  align?: 'left' | 'right'
  /** right-aligned tabular numerals */
  numeric?: boolean
  sortable?: boolean
  className?: string
  headerClassName?: string
}

export interface VirtualSort {
  key: string
  dir: 'asc' | 'desc'
}

export interface VirtualTableProps<T> {
  columns: VirtualColumn<T>[]
  rows: T[]
  rowKey: (row: T, index: number) => string
  /** the table's accessible name */
  label: string
  onRowClick?: (row: T, index: number) => void
  /** controlled sort */
  sort?: VirtualSort
  defaultSort?: VirtualSort
  onSortChange?: (sort: VirtualSort) => void
  /** the server sorts: do not sort rows locally */
  serverSort?: boolean
  loading?: boolean
  skeletonRows?: number
  /** custom empty body; the default is an EmptyState from emptyTitle/emptyMessage/emptyIcon */
  empty?: ReactNode
  emptyTitle?: string
  emptyMessage?: ReactNode
  emptyIcon?: IconName
  /** row height in px (default 44) */
  rowHeight?: number
  /** the body's greatest height; past it, the body scrolls (default '32rem') */
  maxHeight?: string
  /** key of the row to keep highlighted (the one open in a drawer) */
  selectedKey?: string | null
  /** a checkbox column */
  selectable?: boolean
  selectedKeys?: ReadonlySet<string>
  onSelectionChange?: (keys: Set<string>) => void
  /** called once when the last rows come into view (cursor paging, infinite lists) */
  onEndReached?: () => void
  footer?: ReactNode
  /** the scroll area's size before it is measured: for server rendering and tests */
  initialRect?: { width: number; height: number }
  className?: string
}

function compareCells(a: CellValue, b: CellValue): number {
  if ((a === null || a === undefined || a === '') && (b === null || b === undefined || b === '')) return 0
  if (a === null || a === undefined || a === '') return 1
  if (b === null || b === undefined || b === '') return -1
  if (typeof a === 'number' && typeof b === 'number') return a - b
  if (typeof a === 'boolean' && typeof b === 'boolean') return Number(a) - Number(b)
  return String(a).localeCompare(String(b), undefined, { numeric: true, sensitivity: 'base' })
}

/** Rows sorted by a column's accessor (or the row's own field), stable, empties last. */
export function sortRows<T>(rows: T[], columns: VirtualColumn<T>[], sort: VirtualSort | undefined): T[] {
  if (!sort) return rows
  const col = columns.find((c) => c.key === sort.key)
  if (!col) return rows
  const read = col.accessor ?? ((row: T) => (row as Record<string, unknown>)[col.key] as CellValue)
  const sign = sort.dir === 'asc' ? 1 : -1
  return rows
    .map((row, i) => ({ row, i, v: read(row) }))
    .sort((x, y) => sign * compareCells(x.v, y.v) || x.i - y.i)
    .map((x) => x.row)
}

export function VirtualTable<T>({
  columns,
  rows,
  rowKey,
  label,
  onRowClick,
  sort: controlledSort,
  defaultSort,
  onSortChange,
  serverSort,
  loading,
  skeletonRows = 8,
  empty,
  emptyTitle = 'Nothing here yet',
  emptyMessage,
  emptyIcon = 'search',
  rowHeight = 44,
  maxHeight = '32rem',
  selectedKey,
  selectable,
  selectedKeys,
  onSelectionChange,
  onEndReached,
  footer,
  initialRect,
  className,
}: VirtualTableProps<T>) {
  const id = useId()
  const [innerSort, setInnerSort] = useState<VirtualSort | undefined>(defaultSort)
  const sort = controlledSort ?? innerSort
  const sorted = useMemo(() => (serverSort ? rows : sortRows(rows, columns, sort)), [rows, columns, sort, serverSort])
  const [active, setActive] = useState(-1)
  const bodyRef = useRef<HTMLDivElement>(null)
  const requestedFor = useRef(-1)

  // the virtualizer's functions are not memoisable; nothing from it is passed to a memoised child
  // eslint-disable-next-line react-hooks/incompatible-library
  const virtualizer = useVirtualizer({
    count: sorted.length,
    getScrollElement: () => bodyRef.current,
    estimateSize: () => rowHeight,
    overscan: 8,
    initialRect,
  })
  const items = virtualizer.getVirtualItems()

  useEffect(() => {
    if (!onEndReached || loading || sorted.length === 0) return
    const last = items[items.length - 1]
    if (last && last.index >= sorted.length - 6 && requestedFor.current !== sorted.length) {
      requestedFor.current = sorted.length
      onEndReached()
    }
  }, [items, sorted.length, loading, onEndReached])

  const toggleSort = (key: string) => {
    const next: VirtualSort = sort?.key === key ? { key, dir: sort.dir === 'asc' ? 'desc' : 'asc' } : { key, dir: 'asc' }
    if (controlledSort === undefined) setInnerSort(next)
    onSortChange?.(next)
  }

  const keys = useMemo(() => sorted.map((row, i) => rowKey(row, i)), [sorted, rowKey])
  const allSelected = selectable && keys.length > 0 && keys.every((k) => selectedKeys?.has(k))
  const someSelected = selectable && !allSelected && keys.some((k) => selectedKeys?.has(k))
  const setSelected = (key: string, on: boolean) => {
    if (!onSelectionChange) return
    const next = new Set(selectedKeys ?? [])
    if (on) next.add(key)
    else next.delete(key)
    onSelectionChange(next)
  }

  const template = [selectable ? '2.5rem' : null, ...columns.map((c) => c.width ?? 'minmax(9rem, 1fr)')].filter(Boolean).join(' ')
  const minWidth = `${(selectable ? 2.5 : 0) + columns.reduce((sum, c) => sum + (c.minRem ?? 9), 0)}rem`
  const rowId = (i: number) => `${id}-row-${i}`

  const onKeyDown = (e: KeyboardEvent<HTMLDivElement>) => {
    if (sorted.length === 0) return
    const move = (to: number) => {
      const i = Math.max(0, Math.min(sorted.length - 1, to))
      setActive(i)
      virtualizer.scrollToIndex(i, { align: 'auto' })
      e.preventDefault()
    }
    switch (e.key) {
      case 'ArrowDown':
        return move(active + 1)
      case 'ArrowUp':
        return move(active <= 0 ? 0 : active - 1)
      case 'PageDown':
        return move(active + 10)
      case 'PageUp':
        return move(active - 10)
      case 'Home':
        return move(0)
      case 'End':
        return move(sorted.length - 1)
      case 'Enter':
        if (active >= 0 && onRowClick) {
          e.preventDefault()
          onRowClick(sorted[active], active)
        }
        return
      case ' ':
        if (active >= 0 && selectable) {
          e.preventDefault()
          setSelected(keys[active], !selectedKeys?.has(keys[active]))
        }
        return
      default:
        return
    }
  }

  const cellClass = (c: VirtualColumn<T>) => cx('min-w-0 truncate px-3 text-sm text-primary', (c.numeric || c.align === 'right') && 'text-right tabular-nums', c.className)
  const showEmpty = !loading && rows.length === 0
  const showSkeleton = loading && rows.length === 0

  return (
    <div className={cx('overflow-hidden rounded-lg border border-stroke-light bg-surface', className)}>
      <div className="overflow-x-auto">
        <div role="table" aria-label={label} aria-rowcount={sorted.length + 1} aria-busy={loading || undefined} style={{ minWidth }}>
          <div role="rowgroup">
            <div role="row" aria-rowindex={1} className="grid h-10 items-center border-b border-stroke-light bg-surface-secondary/60" style={{ gridTemplateColumns: template }}>
              {selectable && (
                <div role="columnheader" className="flex items-center justify-center">
                  <Checkbox
                    checked={!!allSelected}
                    onChange={(on) => onSelectionChange?.(on ? new Set(keys) : new Set())}
                    label={<span className="sr-only">{allSelected ? 'Clear the selection' : 'Select all rows'}</span>}
                    className={cx(someSelected && 'opacity-70')}
                  />
                </div>
              )}
              {columns.map((c) => {
                const sortedBy = sort?.key === c.key
                return (
                  <div
                    key={c.key}
                    role="columnheader"
                    aria-sort={sortedBy ? (sort.dir === 'asc' ? 'ascending' : 'descending') : c.sortable ? 'none' : undefined}
                    className={cx('min-w-0 truncate px-3 text-caption font-medium text-secondary', (c.numeric || c.align === 'right') && 'text-right', c.headerClassName)}
                  >
                    {c.sortable ? (
                      <button type="button" onClick={() => toggleSort(c.key)} className={cx('inline-flex max-w-full items-center gap-1 rounded-sm transition-colors hover:text-primary', sortedBy && 'text-primary')}>
                        <span className="truncate">{c.header}</span>
                        <Icon name={sortedBy && sort.dir === 'desc' ? 'chevron-down' : 'chevron-up'} size={12} className={cx('shrink-0', !sortedBy && 'opacity-40')} />
                      </button>
                    ) : (
                      c.header
                    )}
                  </div>
                )
              })}
            </div>
          </div>

          {showSkeleton ? (
            <div role="rowgroup" className="p-3">
              <SkeletonRows n={skeletonRows} rowClassName="h-9" />
            </div>
          ) : showEmpty ? (
            <div role="rowgroup">{empty ?? <EmptyState compact icon={emptyIcon} title={emptyTitle} message={emptyMessage} />}</div>
          ) : (
            <div
              ref={bodyRef}
              role="rowgroup"
              tabIndex={0}
              aria-activedescendant={active >= 0 && active < sorted.length ? rowId(active) : undefined}
              onKeyDown={onKeyDown}
              className="relative overflow-y-auto outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-accent/40"
              style={{ maxHeight }}
            >
              <div style={{ height: virtualizer.getTotalSize(), position: 'relative', width: '100%' }}>
                {items.map((item) => {
                  const row = sorted[item.index]
                  const key = keys[item.index]
                  const isSelected = selectable ? !!selectedKeys?.has(key) : undefined
                  const isActive = item.index === active
                  return (
                    <div
                      key={key}
                      id={rowId(item.index)}
                      role="row"
                      aria-rowindex={item.index + 2}
                      aria-selected={isSelected}
                      data-index={item.index}
                      data-active={isActive || undefined}
                      onClick={onRowClick ? () => onRowClick(row, item.index) : undefined}
                      className={cx(
                        'absolute left-0 top-0 grid w-full items-center border-b border-stroke-light transition-colors',
                        onRowClick && 'cursor-pointer hover:bg-surface-secondary',
                        (selectedKey === key || isSelected) && 'bg-accent-tint/60',
                        isActive && 'ring-2 ring-inset ring-accent/50',
                      )}
                      style={{ height: item.size, transform: `translateY(${item.start}px)`, gridTemplateColumns: template }}
                    >
                      {selectable && (
                        <div role="cell" className="flex items-center justify-center" onClick={(e) => e.stopPropagation()}>
                          <Checkbox checked={!!isSelected} onChange={(on) => setSelected(key, on)} label={<span className="sr-only">Select {key}</span>} />
                        </div>
                      )}
                      {columns.map((c) => {
                        const content = c.render ? c.render(row, item.index) : c.accessor ? c.accessor(row) : ((row as Record<string, unknown>)[c.key] as CellValue)
                        return (
                          <div key={c.key} role="cell" className={cellClass(c)}>
                            {content === null || content === undefined || content === '' ? <span className="text-tertiary">not set</span> : typeof content === 'boolean' ? (content ? 'Yes' : 'No') : content}
                          </div>
                        )
                      })}
                    </div>
                  )
                })}
              </div>
              {loading && rows.length > 0 && (
                <div className="sticky bottom-0 border-t border-stroke-light bg-surface/90 px-3 py-1.5 text-caption text-tertiary" aria-live="polite">
                  Loading more…
                </div>
              )}
            </div>
          )}
        </div>
      </div>
      {footer && <div className="border-t border-stroke-light px-3 py-2">{footer}</div>}
    </div>
  )
}
