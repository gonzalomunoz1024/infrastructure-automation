/*
 * A small line diff (longest common subsequence) and a unified-diff writer, so two versions of a
 * workflow can be compared in the browser and rendered with the kit's DiffView, which reads
 * unified diffs. Files here are a few hundred lines; the LCS table is fine for that and falls back
 * to a plain replace for anything huge.
 */

export type DiffOpType = 'ctx' | 'add' | 'del'

export interface DiffOp {
  type: DiffOpType
  text: string
  /** 1-based line number in the old text (ctx, del) */
  oldNo?: number
  /** 1-based line number in the new text (ctx, add) */
  newNo?: number
}

const MAX_CELLS = 6_000_000

export function splitLines(text: string): string[] {
  const lines = text.replace(/\r\n/g, '\n').split('\n')
  if (lines.length && lines[lines.length - 1] === '') lines.pop()
  return lines
}

/** The edit script turning `a` into `b`, line by line. */
export function lineDiff(a: string[], b: string[]): DiffOp[] {
  // common prefix and suffix never need the table
  let start = 0
  while (start < a.length && start < b.length && a[start] === b[start]) start++
  let endA = a.length
  let endB = b.length
  while (endA > start && endB > start && a[endA - 1] === b[endB - 1]) {
    endA--
    endB--
  }

  const ops: DiffOp[] = []
  let oldNo = 1
  let newNo = 1
  const ctx = (text: string) => ops.push({ type: 'ctx', text, oldNo: oldNo++, newNo: newNo++ })
  const del = (text: string) => ops.push({ type: 'del', text, oldNo: oldNo++ })
  const add = (text: string) => ops.push({ type: 'add', text, newNo: newNo++ })

  for (let i = 0; i < start; i++) ctx(a[i])

  const midA = a.slice(start, endA)
  const midB = b.slice(start, endB)
  const n = midA.length
  const m = midB.length

  if (n === 0 || m === 0 || n * m > MAX_CELLS) {
    for (const line of midA) del(line)
    for (const line of midB) add(line)
  } else {
    // table[i][j] = length of the LCS of midA[i..] and midB[j..]
    const width = m + 1
    const table = new Uint32Array((n + 1) * width)
    for (let i = n - 1; i >= 0; i--) {
      for (let j = m - 1; j >= 0; j--) {
        table[i * width + j] = midA[i] === midB[j] ? table[(i + 1) * width + j + 1] + 1 : Math.max(table[(i + 1) * width + j], table[i * width + j + 1])
      }
    }
    let i = 0
    let j = 0
    while (i < n && j < m) {
      if (midA[i] === midB[j]) {
        ctx(midA[i])
        i++
        j++
      } else if (table[(i + 1) * width + j] >= table[i * width + j + 1]) {
        del(midA[i])
        i++
      } else {
        add(midB[j])
        j++
      }
    }
    while (i < n) del(midA[i++])
    while (j < m) add(midB[j++])
  }

  for (let i = endA; i < a.length; i++) ctx(a[i])
  return ops
}

export interface UnifiedDiffOptions {
  oldPath?: string
  newPath?: string
  /** unchanged lines kept around each change (default 3) */
  context?: number
}

export interface DiffCounts {
  additions: number
  deletions: number
}

export function countChanges(ops: DiffOp[]): DiffCounts {
  let additions = 0
  let deletions = 0
  for (const op of ops) {
    if (op.type === 'add') additions++
    else if (op.type === 'del') deletions++
  }
  return { additions, deletions }
}

/** A unified diff of two texts ("" when they are the same), as `git diff` would print it. */
export function unifiedDiff(oldText: string, newText: string, opts: UnifiedDiffOptions = {}): string {
  const context = opts.context ?? 3
  const ops = lineDiff(splitLines(oldText), splitLines(newText))
  if (!ops.some((op) => op.type !== 'ctx')) return ''

  // group changes into hunks: a hunk spans changes closer than 2*context lines
  const hunks: { from: number; to: number }[] = []
  let cursor = 0
  while (cursor < ops.length) {
    if (ops[cursor].type === 'ctx') {
      cursor++
      continue
    }
    const from = Math.max(0, cursor - context)
    let last = cursor
    let k = cursor
    while (k < ops.length) {
      if (ops[k].type !== 'ctx') last = k
      else if (k - last > 2 * context) break
      k++
    }
    const to = Math.min(ops.length, last + context + 1)
    hunks.push({ from, to })
    cursor = to
  }

  const out: string[] = []
  out.push(`--- a/${opts.oldPath ?? 'a'}`, `+++ b/${opts.newPath ?? 'b'}`)
  for (const h of hunks) {
    const slice = ops.slice(h.from, h.to)
    const oldLines = slice.filter((o) => o.type !== 'add')
    const newLines = slice.filter((o) => o.type !== 'del')
    const oldStart = oldLines[0]?.oldNo ?? 1
    const newStart = newLines[0]?.newNo ?? 1
    const range = (start: number, len: number) => (len === 1 ? `${start}` : `${len === 0 ? Math.max(0, start - 1) : start},${len}`)
    out.push(`@@ -${range(oldStart, oldLines.length)} +${range(newStart, newLines.length)} @@`)
    for (const op of slice) out.push(`${op.type === 'add' ? '+' : op.type === 'del' ? '-' : ' '}${op.text}`)
  }
  return `${out.join('\n')}\n`
}
