/*
 * CSV in and out for the Many (bulk) mode of the run page: the header row names the input
 * fields, every other row is one item. RFC 4180 quoting; tabs are accepted as the delimiter when
 * the header has no comma (pasted from a spreadsheet).
 */

/** Rows of cells; blank lines are skipped. */
export function parseCsv(text: string, delimiter?: string): string[][] {
  const src = text.replace(/^\uFEFF/, '')
  const firstLine = src.split(/\r?\n/, 1)[0] ?? ''
  const sep = delimiter ?? (firstLine.includes(',') || !firstLine.includes('\t') ? ',' : '\t')
  const rows: string[][] = []
  let row: string[] = []
  let cell = ''
  let quoted = false
  let i = 0
  const endCell = () => {
    row.push(cell)
    cell = ''
  }
  const endRow = () => {
    endCell()
    if (row.some((c) => c !== '')) rows.push(row)
    row = []
  }
  while (i < src.length) {
    const ch = src[i]
    if (quoted) {
      if (ch === '"') {
        if (src[i + 1] === '"') {
          cell += '"'
          i += 2
          continue
        }
        quoted = false
        i++
        continue
      }
      cell += ch
      i++
      continue
    }
    if (ch === '"' && cell === '') {
      quoted = true
      i++
      continue
    }
    if (ch === sep) {
      endCell()
      i++
      continue
    }
    if (ch === '\r') {
      i++
      continue
    }
    if (ch === '\n') {
      endRow()
      i++
      continue
    }
    cell += ch
    i++
  }
  if (cell !== '' || row.length > 0) endRow()
  return rows
}

export interface CsvTable {
  header: string[]
  rows: Record<string, string>[]
}

/** The first row as the header, the rest as objects keyed by it. Blank cells are left out. */
export function csvToRows(text: string): CsvTable {
  const [header = [], ...body] = parseCsv(text)
  const keys = header.map((h) => h.trim())
  const rows = body.map((cells) => {
    const r: Record<string, string> = {}
    keys.forEach((k, i) => {
      if (!k) return
      const v = cells[i] ?? ''
      if (v !== '') r[k] = v
    })
    return r
  })
  return { header: keys.filter(Boolean), rows }
}

function cellText(v: unknown): string {
  if (v === undefined || v === null) return ''
  if (typeof v === 'string') return v
  if (typeof v === 'number' || typeof v === 'boolean') return String(v)
  return JSON.stringify(v)
}

function escapeCell(s: string): string {
  return /[",\r\n]/.test(s) || /^\s|\s$/.test(s) ? `"${s.replace(/"/g, '""')}"` : s
}

/** Objects back to CSV; the header is the given one or the union of keys in first-seen order. */
export function emitCsv(rows: Record<string, unknown>[], header?: string[]): string {
  const keys =
    header ??
    Array.from(
      rows.reduce((set, r) => {
        Object.keys(r).forEach((k) => set.add(k))
        return set
      }, new Set<string>()),
    )
  const lines = [keys.map(escapeCell).join(',')]
  for (const r of rows) lines.push(keys.map((k) => escapeCell(cellText(r[k]))).join(','))
  return `${lines.join('\n')}\n`
}
