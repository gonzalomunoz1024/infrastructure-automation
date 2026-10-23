import type { ShellOutput } from './core'

/*
 * The core's output as a terminal prints it: text as it is, a table in aligned columns with two
 * spaces between them, JSON indented by two. The console shell draws real tables instead (so a
 * screen reader reads them as tables), and uses this for Copy and for the tests.
 */

/** A table in aligned columns; the last column is not padded. */
export function tableText(columns: readonly string[], rows: readonly (readonly string[])[]): string {
  const widths = columns.map((c, i) => Math.max(c.length, ...rows.map((r) => (r[i] ?? '').length)))
  const line = (cells: readonly string[]) =>
    cells
      .map((c, i) => (i === cells.length - 1 ? c : c.padEnd(widths[i])))
      .join('  ')
      .trimEnd()
  return [line(columns), ...rows.map(line)].join('\n')
}

export function toText(out: ShellOutput): string {
  switch (out.kind) {
    case 'text':
      return out.text
    case 'table':
      return tableText(out.columns, out.rows)
    case 'json':
      return JSON.stringify(out.value, null, 2)
    case 'clear':
      return ''
  }
}
