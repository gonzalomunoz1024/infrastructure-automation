import { useMemo, useState } from 'react'
import { cx } from '../lib/cx'
import { Segmented } from './Segmented'

export type DiffLineType = 'add' | 'del' | 'ctx' | 'meta'

export interface DiffLine {
  type: DiffLineType
  text: string
  oldNo?: number
  newNo?: number
}

export interface DiffHunk {
  header: string
  lines: DiffLine[]
}

export interface DiffFile {
  oldPath?: string
  newPath?: string
  hunks: DiffHunk[]
}

const HUNK = /^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@(.*)$/

function stripPrefix(p: string): string | undefined {
  const path = p.trim().split('\t')[0]
  if (!path || path === '/dev/null') return undefined
  return path.replace(/^[ab]\//, '')
}

/** Parses a unified diff (git or plain). Text without hunk headers is read as a bare +/-/space listing. */
export function parseUnifiedDiff(diff: string): DiffFile[] {
  const lines = diff.replace(/\r\n/g, '\n').split('\n')
  if (lines.length && lines[lines.length - 1] === '') lines.pop()

  const files: DiffFile[] = []
  let file: DiffFile | null = null
  let hunk: DiffHunk | null = null
  let oldNo = 0
  let newNo = 0
  let oldLeft = 0
  let newLeft = 0

  const ensureFile = () => {
    if (!file) {
      file = { hunks: [] }
      files.push(file)
    }
    return file
  }

  for (const raw of lines) {
    if (hunk && (oldLeft > 0 || newLeft > 0)) {
      const c = raw[0]
      if (c === '+') {
        hunk.lines.push({ type: 'add', text: raw.slice(1), newNo: newNo++ })
        newLeft--
      } else if (c === '-') {
        hunk.lines.push({ type: 'del', text: raw.slice(1), oldNo: oldNo++ })
        oldLeft--
      } else if (c === '\\') {
        hunk.lines.push({ type: 'meta', text: raw })
      } else {
        hunk.lines.push({ type: 'ctx', text: raw.slice(c === ' ' ? 1 : 0), oldNo: oldNo++, newNo: newNo++ })
        oldLeft--
        newLeft--
      }
      continue
    }
    if (raw.startsWith('diff --git ')) {
      file = { hunks: [] }
      files.push(file)
      hunk = null
      continue
    }
    if (raw.startsWith('--- ')) {
      ensureFile().oldPath = stripPrefix(raw.slice(4))
      continue
    }
    if (raw.startsWith('+++ ')) {
      ensureFile().newPath = stripPrefix(raw.slice(4))
      continue
    }
    const m = HUNK.exec(raw)
    if (m) {
      oldNo = Number(m[1])
      newNo = Number(m[3])
      oldLeft = m[2] === undefined ? 1 : Number(m[2])
      newLeft = m[4] === undefined ? 1 : Number(m[4])
      hunk = { header: raw, lines: [] }
      ensureFile().hunks.push(hunk)
      continue
    }
    // anything else between hunks (index lines, mode changes) is ignored
  }

  if (files.length === 0 && lines.length > 0) {
    // bare listing: every line is content, +/- decide the kind
    let o = 1
    let nn = 1
    const bare: DiffHunk = { header: '', lines: [] }
    for (const raw of lines) {
      const c = raw[0]
      if (c === '+') bare.lines.push({ type: 'add', text: raw.slice(1), newNo: nn++ })
      else if (c === '-') bare.lines.push({ type: 'del', text: raw.slice(1), oldNo: o++ })
      else bare.lines.push({ type: 'ctx', text: raw.slice(c === ' ' ? 1 : 0), oldNo: o++, newNo: nn++ })
    }
    files.push({ hunks: [bare] })
  }
  return files
}

export interface DiffStats {
  additions: number
  deletions: number
}

export function diffStats(files: DiffFile[]): DiffStats {
  let additions = 0
  let deletions = 0
  for (const f of files)
    for (const h of f.hunks)
      for (const l of h.lines) {
        if (l.type === 'add') additions++
        else if (l.type === 'del') deletions++
      }
  return { additions, deletions }
}

export type DiffMode = 'unified' | 'split'

export interface DiffViewProps {
  /** a unified diff string */
  diff: string
  /** controlled view mode */
  mode?: DiffMode
  defaultMode?: DiffMode
  onModeChange?: (mode: DiffMode) => void
  /** the mode switch and the +/- counts above the diff (default true) */
  toolbar?: boolean
  /** wrap long lines instead of scrolling */
  wrap?: boolean
  /** class for the scroll area, e.g. 'max-h-[60vh]' */
  maxHeight?: string
  className?: string
}

const ROW: Record<DiffLineType, string> = {
  add: 'bg-accent-tint',
  del: 'bg-critical-tint',
  ctx: '',
  meta: 'text-tertiary',
}

const MARK: Record<DiffLineType, string> = { add: '+', del: '-', ctx: ' ', meta: ' ' }
const MARK_CLASS: Record<DiffLineType, string> = { add: 'text-accent', del: 'text-critical', ctx: 'text-tertiary', meta: 'text-tertiary' }

function Gutter({ n }: { n?: number }) {
  return <span className="select-none pr-2 text-right tabular-nums text-tertiary">{n ?? ''}</span>
}

function UnifiedHunk({ hunk, wrap }: { hunk: DiffHunk; wrap: boolean }) {
  return (
    <>
      {hunk.header && <div className="bg-surface-secondary px-3 py-1 text-tertiary">{hunk.header}</div>}
      {hunk.lines.map((l, i) => (
        <div key={i} className={cx('grid grid-cols-[3rem_3rem_1.25rem_1fr] leading-5', ROW[l.type])}>
          <Gutter n={l.oldNo} />
          <Gutter n={l.newNo} />
          <span className={cx('select-none text-center', MARK_CLASS[l.type])}>{MARK[l.type]}</span>
          <span className={cx('pr-3', wrap ? 'whitespace-pre-wrap break-all' : 'whitespace-pre', l.type === 'meta' ? 'text-tertiary' : 'text-primary')}>{l.text || ' '}</span>
        </div>
      ))}
    </>
  )
}

interface SplitRow {
  left?: DiffLine
  right?: DiffLine
}

function pairUp(lines: DiffLine[]): SplitRow[] {
  const rows: SplitRow[] = []
  let i = 0
  while (i < lines.length) {
    const l = lines[i]
    if (l.type === 'ctx' || l.type === 'meta') {
      rows.push({ left: l, right: l })
      i++
      continue
    }
    const dels: DiffLine[] = []
    const adds: DiffLine[] = []
    while (i < lines.length && lines[i].type === 'del') dels.push(lines[i++])
    while (i < lines.length && lines[i].type === 'add') adds.push(lines[i++])
    const n = Math.max(dels.length, adds.length)
    for (let k = 0; k < n; k++) rows.push({ left: dels[k], right: adds[k] })
  }
  return rows
}

function SplitCell({ line, side, wrap }: { line?: DiffLine; side: 'left' | 'right'; wrap: boolean }) {
  if (!line) return <div className="bg-surface-secondary/60" aria-hidden="true" />
  return (
    <div className={cx('grid grid-cols-[3rem_1.25rem_1fr] leading-5', ROW[line.type], side === 'left' && 'border-r border-stroke-light')}>
      <Gutter n={side === 'left' ? line.oldNo : line.newNo} />
      <span className={cx('select-none text-center', MARK_CLASS[line.type])}>{MARK[line.type]}</span>
      <span className={cx('pr-3', wrap ? 'whitespace-pre-wrap break-all' : 'whitespace-pre', line.type === 'meta' ? 'text-tertiary' : 'text-primary')}>{line.text || ' '}</span>
    </div>
  )
}

function SplitHunk({ hunk, wrap }: { hunk: DiffHunk; wrap: boolean }) {
  const rows = useMemo(() => pairUp(hunk.lines), [hunk])
  return (
    <>
      {hunk.header && <div className="bg-surface-secondary px-3 py-1 text-tertiary">{hunk.header}</div>}
      {rows.map((r, i) => (
        <div key={i} className="grid grid-cols-2">
          <SplitCell line={r.left} side="left" wrap={wrap} />
          <SplitCell line={r.right} side="right" wrap={wrap} />
        </div>
      ))}
    </>
  )
}

/** Renders a unified diff string, unified or side by side: hairlines, accent for additions, critical for deletions. */
export function DiffView({ diff, mode: controlled, defaultMode = 'unified', onModeChange, toolbar = true, wrap = false, maxHeight, className }: DiffViewProps) {
  const [internal, setInternal] = useState<DiffMode>(defaultMode)
  const mode = controlled ?? internal
  const files = useMemo(() => parseUnifiedDiff(diff), [diff])
  const stats = useMemo(() => diffStats(files), [files])
  const setMode = (m: DiffMode) => {
    if (!controlled) setInternal(m)
    onModeChange?.(m)
  }

  return (
    <div className={cx('overflow-hidden rounded-md border border-stroke-light bg-surface', className)}>
      {toolbar && (
        <div className="flex items-center justify-between gap-3 border-b border-stroke-light px-3 py-2">
          <span className="text-caption tabular-nums">
            <span className="font-medium text-accent">+{stats.additions}</span>
            <span className="mx-1 text-tertiary">·</span>
            <span className="font-medium text-critical">−{stats.deletions}</span>
          </span>
          <Segmented
            size="sm"
            ariaLabel="Diff layout"
            value={mode}
            onChange={setMode}
            options={[
              { value: 'unified', label: 'Unified' },
              { value: 'split', label: 'Split' },
            ]}
          />
        </div>
      )}
      <div className={cx('overflow-auto font-mono text-caption', maxHeight)}>
        {files.length === 0 && <p className="px-3 py-4 text-tertiary">No changes.</p>}
        {files.map((f, fi) => (
          <div key={fi} className={cx(fi > 0 && 'border-t border-stroke-light')}>
            {(f.newPath || f.oldPath) && (
              <div className="flex items-center gap-2 border-b border-stroke-light bg-surface-secondary px-3 py-1.5 text-caption font-medium text-secondary">
                {f.oldPath && f.newPath && f.oldPath !== f.newPath ? `${f.oldPath} → ${f.newPath}` : (f.newPath ?? f.oldPath)}
                {!f.oldPath && <span className="text-accent">new file</span>}
                {!f.newPath && <span className="text-critical">deleted</span>}
              </div>
            )}
            {f.hunks.map((h, hi) => (mode === 'split' ? <SplitHunk key={hi} hunk={h} wrap={wrap} /> : <UnifiedHunk key={hi} hunk={h} wrap={wrap} />))}
          </div>
        ))}
      </div>
    </div>
  )
}
