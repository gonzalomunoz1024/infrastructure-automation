import { useEffect, useMemo, useRef, useState } from 'react'
import { cx } from '../lib/cx'
import { fuzzyMatch, type FuzzyResult } from '../lib/fuzzy'
import { isEditableTarget } from '../hooks/useHotkey'
import { Modal } from './Overlay'
import { Icon, type IconName } from './Icon'
import { Kbd, modKeyLabel } from './Kbd'
import { Spinner } from './Button'

export interface PaletteItem {
  id: string
  /** section heading; sections keep the order of `groups` (or first appearance) */
  group: string
  label: string
  /** quiet text at the right ("open", "job") */
  hint?: string
  icon?: IconName
  /** extra words the fuzzy matcher may hit (not highlighted) */
  keywords?: string
  run: () => void
}

export interface CommandPaletteProps {
  onClose: () => void
  /** synchronous items: pages, actions, anything already known */
  items: PaletteItem[]
  /** section order; unknown groups follow */
  groups?: string[]
  /** async items for a query (workflows, jobs…); merged after the local matches */
  search?: (query: string) => Promise<PaletteItem[]>
  placeholder?: string
  emptyMessage?: string
  /** ms to wait before calling `search` */
  searchDelay?: number
}

/** Global ⌘K / Ctrl-K (and `/` outside inputs) opener. */
export function useCommandK(onOpen: () => void) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k') {
        e.preventDefault()
        onOpen()
      } else if (e.key === '/' && !e.metaKey && !e.ctrlKey && !e.altKey && !isEditableTarget(e.target)) {
        e.preventDefault()
        onOpen()
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onOpen])
}

type Scored = { item: PaletteItem; m: FuzzyResult }

/** Spotlight-style command palette: jump to pages, run actions, find things by name. */
export function CommandPalette({
  onClose,
  items,
  groups,
  search,
  placeholder = 'Search or jump to…',
  emptyMessage = 'No matches.',
  searchDelay = 120,
}: CommandPaletteProps) {
  const [query, setQuery] = useState('')
  const [active, setActive] = useState(0)
  const [remote, setRemote] = useState<PaletteItem[]>([])
  const [searching, setSearching] = useState(false)
  const inputRef = useRef<HTMLInputElement>(null)
  const listRef = useRef<HTMLDivElement>(null)
  const searchRef = useRef(search)
  useEffect(() => {
    searchRef.current = search
  })

  useEffect(() => inputRef.current?.focus(), [])

  // async group(s)
  useEffect(() => {
    if (!query.trim() || !searchRef.current) {
      setRemote([])
      setSearching(false)
      return
    }
    let cancelled = false
    setSearching(true)
    const t = setTimeout(() => {
      const fn = searchRef.current
      if (!fn) return
      fn(query)
        .then((rs) => {
          if (!cancelled) setRemote(rs)
        })
        .catch(() => {
          if (!cancelled) setRemote([])
        })
        .finally(() => {
          if (!cancelled) setSearching(false)
        })
    }, searchDelay)
    return () => {
      cancelled = true
      clearTimeout(t)
    }
  }, [query, searchDelay])

  const results = useMemo(() => {
    const q = query.trim()
    const scored: Scored[] = []
    for (const item of items) {
      let m = fuzzyMatch(q, item.label)
      if (!m && item.keywords) {
        const km = fuzzyMatch(q, item.keywords)
        if (km) m = { score: km.score - 5, indices: [] }
      }
      if (m) scored.push({ item, m })
    }
    if (q) scored.sort((a, b) => b.m.score - a.m.score)
    const seen = new Set(scored.map((s) => s.item.id))
    const extra: Scored[] = remote.filter((r) => !seen.has(r.id)).map((item) => ({ item, m: { score: 0, indices: [] } }))
    const all = [...scored, ...extra]
    const known = groups ?? []
    const rest = Array.from(new Set(all.map((x) => x.item.group))).filter((g) => !known.includes(g))
    let order = [...known, ...rest]
    if (q) {
      // best-matching group first, so a name match surfaces above a scattered action match
      const best = (g: string) => Math.max(-1, ...all.filter((x) => x.item.group === g).map((x) => x.m.score))
      order = [...order].sort((a, b) => best(b) - best(a))
    }
    return { list: order.flatMap((g) => all.filter((x) => x.item.group === g)), groups: order }
  }, [items, remote, query, groups])

  useEffect(() => setActive(0), [query, remote])
  useEffect(() => {
    listRef.current?.querySelector<HTMLElement>(`[data-idx="${active}"]`)?.scrollIntoView({ block: 'nearest' })
  }, [active])

  const run = (i: number) => {
    const r = results.list[i]
    if (!r) return
    onClose()
    r.item.run()
  }

  return (
    <Modal onClose={onClose} align="top" width="max-w-xl" panelClassName="!max-h-[60vh]" label="Command palette">
      <div className="flex items-center gap-3 border-b border-stroke-light px-4 py-3.5">
        <Icon name="search" size={17} className="shrink-0 text-tertiary" />
        <input
          ref={inputRef}
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'ArrowDown') {
              e.preventDefault()
              setActive((a) => Math.min(a + 1, results.list.length - 1))
            } else if (e.key === 'ArrowUp') {
              e.preventDefault()
              setActive((a) => Math.max(a - 1, 0))
            } else if (e.key === 'Enter') {
              e.preventDefault()
              run(active)
            }
          }}
          placeholder={placeholder}
          aria-label={placeholder}
          aria-activedescendant={results.list[active] ? `palette-${results.list[active].item.id}` : undefined}
          className="no-focus-ring flex-1 bg-transparent text-body text-primary outline-none placeholder:text-tertiary"
        />
        {searching ? <Spinner size={14} className="text-tertiary" /> : <Kbd>esc</Kbd>}
      </div>

      <div ref={listRef} className="min-h-0 flex-1 overflow-y-auto py-1.5" role="listbox" aria-label="Results">
        {results.list.length === 0 ? (
          <p className="px-4 py-8 text-center text-sm text-tertiary">{searching ? 'Searching…' : emptyMessage}</p>
        ) : (
          results.groups.map((group) => {
            const grouped = results.list.map((r, i) => ({ ...r, i })).filter((r) => r.item.group === group)
            if (grouped.length === 0) return null
            return (
              <div key={group}>
                <div className="px-4 pb-1 pt-2.5 text-[10px] font-semibold uppercase tracking-wide text-tertiary">{group}</div>
                {grouped.map((r) => (
                  <button
                    key={r.item.id}
                    id={`palette-${r.item.id}`}
                    type="button"
                    role="option"
                    aria-selected={r.i === active}
                    data-idx={r.i}
                    onClick={() => run(r.i)}
                    onMouseEnter={() => setActive(r.i)}
                    className={cx(
                      'flex w-full items-center gap-3 px-4 py-2.5 text-left transition-colors',
                      r.i === active ? 'bg-surface-secondary' : '',
                    )}
                  >
                    <Icon name={r.item.icon ?? 'arrow-right'} size={15} className="shrink-0 text-tertiary" />
                    <span className="min-w-0 flex-1 truncate text-sm text-primary">
                      <Highlighted label={r.item.label} indices={r.m.indices} />
                    </span>
                    {r.item.hint && <span className="shrink-0 text-caption text-tertiary">{r.item.hint}</span>}
                    {r.i === active && <Kbd>↩</Kbd>}
                  </button>
                ))}
              </div>
            )
          })
        )}
      </div>

      <div className="flex items-center gap-3 border-t border-stroke-light px-4 py-2 text-[10px] text-tertiary">
        <span>↑↓ navigate</span>
        <span>↩ open</span>
        <span className="ml-auto flex items-center gap-1">
          <Kbd>{modKeyLabel()}K</Kbd> anywhere
        </span>
      </div>
    </Modal>
  )
}

function Highlighted({ label, indices }: { label: string; indices: number[] }) {
  if (indices.length === 0) return <>{label}</>
  const set = new Set(indices)
  return (
    <>
      {label.split('').map((ch, i) =>
        set.has(i) ? (
          <span key={i} className="font-semibold text-accent">
            {ch}
          </span>
        ) : (
          <span key={i}>{ch}</span>
        ),
      )}
    </>
  )
}
