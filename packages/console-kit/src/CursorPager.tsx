import { useCallback, useState } from 'react'
import { Icon, IconButton, Select, cx, plural } from '@iaf/ui'

/*
 * Paging over an opaque cursor, as the platform's lists page (P-05): `limit` rows at a time,
 * `after` the cursor the `Link: <…>; rel="next"` header carried. There is no page count and no
 * jump to a page; there is a way back, because the cursors already seen are kept.
 */

/** The `after` cursor of a `Link: <…?after=abc>; rel="next"` header, or null when there is no next page. */
export function nextCursorFromLink(link: string | null | undefined): string | null {
  if (!link) return null
  for (const part of link.split(',')) {
    const m = /<([^>]+)>\s*;([^,]*)/.exec(part)
    if (!m || !/rel\s*=\s*"?next"?/i.test(m[2])) continue
    const url = m[1]
    const query = url.includes('?') ? url.slice(url.indexOf('?') + 1) : ''
    const after = new URLSearchParams(query).get('after')
    if (after) return after
  }
  return null
}

export interface CursorPaging {
  /** the cursor of the page on screen (undefined for the first) */
  after: string | undefined
  /** 1-based */
  page: number
  hasPrevious: boolean
  /** go to the page the given cursor starts */
  next: (cursor: string) => void
  previous: () => void
  /** back to the first page */
  reset: () => void
}

/** Keeps the trail of cursors visited, so a list can go back a page. */
export function useCursorPaging(): CursorPaging {
  const [trail, setTrail] = useState<string[]>([])
  const next = useCallback((cursor: string) => setTrail((t) => [...t, cursor]), [])
  const previous = useCallback(() => setTrail((t) => t.slice(0, -1)), [])
  const reset = useCallback(() => setTrail([]), [])
  return { after: trail[trail.length - 1], page: trail.length + 1, hasPrevious: trail.length > 0, next, previous, reset }
}

export const PAGE_SIZES = [25, 50, 100, 200] as const

export interface CursorPagerProps {
  /** rows on this page */
  count: number
  hasNext: boolean
  hasPrevious: boolean
  onNext: () => void
  onPrevious: () => void
  pageSize: number
  onPageSizeChange?: (size: number) => void
  pageSizes?: readonly number[]
  /** 1-based page number, when known */
  page?: number
  loading?: boolean
  /** what is counted: 'virtual machine' (pluralised as needed) */
  noun?: string
  className?: string
}

export function CursorPager({ count, hasNext, hasPrevious, onNext, onPrevious, pageSize, onPageSizeChange, pageSizes = PAGE_SIZES, page, loading, noun = 'row', className }: CursorPagerProps) {
  const sizes = pageSizes.includes(pageSize) ? pageSizes : [...pageSizes, pageSize].sort((a, b) => a - b)
  return (
    <nav aria-label="Pagination" className={cx('flex flex-wrap items-center justify-between gap-3 text-caption text-secondary', className)}>
      <p aria-live="polite">
        {count === 0 ? `No ${plural(2, noun).replace(/^2 /, '')} on this page` : `Showing ${plural(count, noun)}${hasNext ? ', more available' : ''}`}
        {page !== undefined && count > 0 && <span className="text-tertiary"> · page {page}</span>}
      </p>
      <div className="flex items-center gap-2">
        {onPageSizeChange && (
          <Select size="sm" ariaLabel="Rows per page" value={String(pageSize)} onChange={(v) => onPageSizeChange(Number(v))} options={sizes.map((n) => ({ value: String(n), label: `${n} per page` }))} className="w-32" />
        )}
        <IconButton size="sm" label="Previous page" onClick={onPrevious} disabled={!hasPrevious || loading}>
          <Icon name="chevron-left" size={16} />
        </IconButton>
        <IconButton size="sm" label="Next page" onClick={onNext} disabled={!hasNext || loading}>
          <Icon name="chevron-right" size={16} />
        </IconButton>
      </div>
    </nav>
  )
}
