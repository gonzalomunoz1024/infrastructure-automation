/*
 * CSV for the list's export: RFC 4180 quoting, CRLF line ends, and a guard against formula
 * injection (a cell that would start with =, +, - or @ in a spreadsheet is prefixed with an
 * apostrophe, as the spreadsheets themselves recommend).
 */

const FORMULA = /^[=+\-@\t\r]/

export function csvCell(value: unknown): string {
  if (value === null || value === undefined) return ''
  let text = typeof value === 'string' ? value : typeof value === 'object' ? JSON.stringify(value) : String(value)
  if (FORMULA.test(text)) text = `'${text}`
  return /[",\r\n]/.test(text) ? `"${text.replace(/"/g, '""')}"` : text
}

/** Rows to CSV text; the first row is the header. */
export function toCsv(rows: readonly (readonly unknown[])[]): string {
  return rows.map((r) => r.map(csvCell).join(',')).join('\r\n') + '\r\n'
}

/** A file name for an export: `virtual-machines-2026-10-23.csv`. */
export function csvFileName(noun: string, at = new Date()): string {
  const slug = noun
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-|-$/g, '')
  return `${slug || 'export'}-${at.toISOString().slice(0, 10)}.csv`
}

/** The byte-order mark that lets spreadsheets read the file as UTF-8. */
const BOM = '\ufeff'

/** Offers the text as a download. */
export function downloadCsv(fileName: string, csv: string): void {
  const blob = new Blob([BOM, csv], { type: 'text/csv;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = fileName
  a.rel = 'noopener'
  document.body.appendChild(a)
  a.click()
  a.remove()
  setTimeout(() => URL.revokeObjectURL(url), 1_000)
}
