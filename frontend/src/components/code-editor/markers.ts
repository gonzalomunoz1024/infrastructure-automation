/**
 * Where the platform's check problems sit in the text of a workflow file, as editor markers.
 * The browser does not parse YAML (docs/contributing.md): a problem names a step and a dotted
 * field (`with.input.id`, `switch[1].when`), so this finds its line by the keys and their
 * indentation in the YAML the backend rendered (sequences indented or not), and a YAML syntax
 * error by the line and column its message gives. What it cannot place lands on the deepest line
 * it found: the step, or line 1. Nothing here loads Monaco, so the mapping is tested as is.
 */
import type { Problem } from '@iaf/workflow-checks'
import { templateSegments } from '../../designer/model/expressions'

export type MarkerSeverity = 'error' | 'warning' | 'info'

/** A marker in Monaco's terms: 1-based lines and columns, the end column exclusive. */
export interface EditorMarker {
  startLineNumber: number
  startColumn: number
  endLineNumber: number
  endColumn: number
  message: string
  severity: MarkerSeverity
  code?: string
}

type Segment = string | number

/** `switch[1].when` → ['switch', 1, 'when']. */
export function pathSegments(field: string): Segment[] {
  const out: Segment[] = []
  for (const part of field.split('.')) {
    const m = /^([^[\]]*)((?:\[\d+\])*)$/.exec(part)
    if (!m) {
      out.push(part)
      continue
    }
    if (m[1]) out.push(m[1])
    for (const i of m[2].matchAll(/\[(\d+)\]/g)) out.push(Number(i[1]))
  }
  return out
}

interface Line {
  text: string
  /** the column of the first character; -1 for a blank or comment line */
  indent: number
  /** where the content starts: after the `- ` of a list item */
  keyColumn: number
  key: string | null
  dash: boolean
}

const KEY = /^(['"]?)([^'"#:\s][^'"#:]*?)\1\s*:(?:\s|$)/

function readLine(text: string): Line {
  const indent = text.search(/\S/)
  if (indent < 0 || text[indent] === '#') return { text, indent: -1, keyColumn: -1, key: null, dash: false }
  let rest = text.slice(indent)
  let keyColumn = indent
  const item = /^-(\s+|$)/.exec(rest)
  if (item) {
    keyColumn = indent + item[0].length
    rest = rest.slice(item[0].length)
  }
  const key = KEY.exec(rest)
  return { text, indent, keyColumn, key: key ? key[2].trim() : null, dash: !!item }
}

/**
 * A node of the file: the root, a key, or a list item (whose first key shares its line). `end`
 * is the line after its block.
 */
interface Node {
  line: number
  kind: 'root' | 'key' | 'item'
  /** a key's column, or the column an item's keys start at */
  column: number
  end: number
}

function skipTo(lines: Line[], from: number, inside: (l: Line) => boolean): number {
  let i = from + 1
  while (i < lines.length && (lines[i].indent < 0 || inside(lines[i]))) i++
  return i
}

/** A key's block: the deeper lines, and the list items at its own column (an unindented sequence). */
function keyNode(lines: Line[], i: number): Node {
  const column = lines[i].keyColumn
  return { line: i, kind: 'key', column, end: skipTo(lines, i, (l) => l.indent > column || (l.dash && l.indent === column)) }
}

/** An item's block: the lines deeper than its dash. */
function itemNode(lines: Line[], i: number): Node {
  const dash = lines[i].indent
  return { line: i, kind: 'item', column: lines[i].keyColumn, end: skipTo(lines, i, (l) => l.indent > dash) }
}

function child(lines: Line[], node: Node, segment: Segment): Node | null {
  if (node.kind === 'item') {
    if (typeof segment === 'number') return null
    if (lines[node.line].key === segment) return keyNode(lines, node.line)
    for (let i = node.line + 1; i < node.end; i++) {
      const l = lines[i]
      if (l.indent === node.column && !l.dash && l.key === segment) return keyNode(lines, i)
    }
    return null
  }
  // the children sit at the column of the first line under the node
  let column = -1
  let items = 0
  for (let i = node.line + 1; i < node.end; i++) {
    const l = lines[i]
    if (l.indent < 0) continue
    if (column < 0) column = l.indent
    if (l.indent !== column) continue
    if (typeof segment === 'number') {
      if (l.dash && items++ === segment) return itemNode(lines, i)
    } else if (!l.dash && l.key === segment) {
      return keyNode(lines, i)
    }
  }
  return null
}

/** The list item whose key is the step id: steps are `- <id>:` wherever they nest. */
function stepNode(lines: Line[], step: string): Node | null {
  const i = lines.findIndex((l) => l.dash && l.key === step)
  return i < 0 ? null : keyNode(lines, i)
}

/** The 1-based line of a problem: its step, then its field key by key, as deep as found. */
export function lineOfProblem(yaml: string, problem: Pick<Problem, 'step' | 'field'>): number {
  const lines = yaml.split('\n').map(readLine)
  let node: Node | null = problem.step ? stepNode(lines, problem.step) : { line: -1, kind: 'root', column: 0, end: lines.length }
  if (!node) return 1
  const segments = problem.field ? pathSegments(problem.field) : []
  for (let k = 0; k < segments.length; k++) {
    let next = child(lines, node, segments[k])
    // a step's parameter sits under its `with`, where the checks read it, more often than beside it
    if (!next && k === 0 && problem.step) {
      const params = child(lines, node, 'with')
      next = params && child(lines, params, segments[k])
    }
    if (!next) break
    node = next
  }
  return node.line < 0 ? 1 : node.line + 1
}

/** `line 4, column 3` or `line: 4, column: 3` in a parser's message, 1-based. */
export function syntaxPosition(message: string): { line: number; column: number } | null {
  const m = /\bline:?\s*(\d+)(?:,\s*column:?\s*(\d+))?/i.exec(message)
  if (!m) return null
  return { line: Math.max(1, Number(m[1])), column: Math.max(1, Number(m[2] ?? 1)) }
}

/** One marker per problem, from its key (or the column a syntax error names) to the end of the line. */
export function problemMarkers(yaml: string, problems: readonly Problem[]): EditorMarker[] {
  const lines = yaml.split('\n')
  return problems.map((p) => {
    const at = p.code === 'yaml.invalid' ? syntaxPosition(p.message) : null
    const line = Math.min(lines.length, at ? at.line : lineOfProblem(yaml, p))
    const text = lines[line - 1] ?? ''
    const content = readLine(text)
    const first = content.indent < 0 ? 1 : content.keyColumn + 1
    const startColumn = at ? Math.min(at.column, text.length + 1) : first
    return {
      startLineNumber: line,
      startColumn,
      endLineNumber: line,
      endColumn: Math.max(startColumn + 1, text.length + 1),
      message: p.field ? `${p.field}: ${p.message}` : p.message,
      severity: p.level === 'error' ? 'error' : 'warning',
      code: p.code,
    }
  })
}

function position(text: string, offset: number): { line: number; column: number } {
  const before = text.slice(0, offset).split('\n')
  return { line: before.length, column: before[before.length - 1].length + 1 }
}

/** The `{{ … }}` of a text that do not resolve, as markers: `reason` says why one fails, or null. */
export function expressionMarkers(text: string, reason: (expression: string) => string | null): EditorMarker[] {
  const markers: EditorMarker[] = []
  let offset = 0
  for (const s of templateSegments(text)) {
    const why = s.expression ? reason(s.text) : null
    if (why !== null) {
      const start = position(text, offset)
      const end = position(text, offset + s.text.length)
      markers.push({ startLineNumber: start.line, startColumn: start.column, endLineNumber: end.line, endColumn: end.column, message: why, severity: 'error', code: 'expr' })
    }
    offset += s.text.length
  }
  return markers
}
