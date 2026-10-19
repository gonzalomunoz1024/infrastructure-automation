/**
 * Line edits of a provider file, for the node detail view: an action's own fields (its effect,
 * retry, approval, plan, effectDetail, own SLO, sample and where that sample was recorded), an
 * action's override and the error classes. The browser never parses YAML (docs/contributing.md),
 * so, like the wizard's `withVersion`, these find the keys by their indentation in block-style
 * YAML, the way the repository's files and `emitYaml` write them, and write each value on one line
 * in flow style. Nothing else in the file is touched. A file they cannot edit that way (no `spec`
 * block, the action missing or written in flow style, a flow-style `overrides`, error classes not
 * written one per line as the designer writes them) comes back as null.
 */
import { blankError, errorDocument, type ErrorRow } from '../../components/provider-editor/state'
import { emitYaml, needsQuotes } from '../../components/yaml-emit'

/** The fields of an action `withActionFields` writes; undefined removes one. */
export type ActionField = 'effect' | 'retry' | 'approval' | 'plan' | 'effectDetail' | 'slo' | 'sample' | 'recorded'

/** The order the wizard writes an action's keys in: a key added goes after the last one before it that the action has. */
const ORDER = ['id', 'title', 'description', 'operation', 'playbook', 'activity', 'effect', 'retry', 'approval', 'plan', 'effectDetail', 'verify', 'slo', 'input', 'vars', 'output', 'example', 'sample', 'recorded']

const blank = (line: string) => line.trim() === '' || line.trimStart().startsWith('#')
const indentOf = (line: string) => line.length - line.trimStart().length
const pad = (n: number) => ' '.repeat(n)
const escape = (s: string) => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')

/** `key:` at the start of a text (plain, single- or double-quoted), followed by a space or the end. */
function keyRe(key: string): RegExp {
  const k = escape(key)
  return new RegExp(`^(?:${k}|"${k}"|'${k}')\\s*:(?:\\s|$)`)
}

/** What follows `key:` on its line, without a comment: empty when the value is a block below. */
function valueAfterKey(text: string): string {
  const colon = /:(\s|$)/.exec(text)
  if (!colon) return ''
  const rest = text.slice(colon.index + 1)
  const comment = /(^|\s)#/.exec(rest)
  return (comment ? rest.slice(0, comment.index) : rest).trim()
}

/** A scalar as written: unquoted. */
function unquote(text: string): string {
  const t = text.trim()
  if (t.startsWith('"') && t.endsWith('"') && t.length >= 2) {
    try {
      return JSON.parse(t) as string
    } catch {
      return t.slice(1, -1)
    }
  }
  if (t.startsWith("'") && t.endsWith("'") && t.length >= 2) return t.slice(1, -1).replace(/''/g, "'")
  return t
}

/** A string as a flow scalar: quoted when YAML would read it otherwise, or when it holds a flow indicator. */
function flowString(s: string): string {
  return needsQuotes(s) || /[,[\]{}]/.test(s) ? JSON.stringify(s) : s
}

/** A value on one line, in flow style: `{ successRate: 99.5, p95Ms: 800 }`. */
export function flowValue(v: unknown): string {
  if (v === null || v === undefined) return 'null'
  if (typeof v === 'string') return flowString(v)
  if (typeof v === 'number' || typeof v === 'boolean') return emitYaml(v).trim()
  if (Array.isArray(v)) return `[${v.map(flowValue).join(', ')}]`
  if (typeof v === 'object') {
    const entries = Object.entries(v as Record<string, unknown>).filter(([, x]) => x !== undefined)
    return entries.length === 0 ? '{}' : `{ ${entries.map(([k, x]) => `${flowString(k)}: ${flowValue(x)}`).join(', ')} }`
  }
  return 'null'
}

/**
 * The index after the last line of the entry that starts at `start` at `indent`: the lines below
 * it indented deeper, and a list written at the key's own indentation (`actions:` then `- id:`).
 * Blank lines and comments after it are left out.
 */
function entryEnd(lines: readonly string[], start: number, indent: number): number {
  const first = lines[start].trimStart()
  const opensBlock = !first.startsWith('-') && valueAfterKey(first) === ''
  let end = start + 1
  let sameIndentList = false
  for (let i = start + 1; i < lines.length; i++) {
    const line = lines[i]
    if (blank(line)) continue
    const n = indentOf(line)
    if (n > indent) {
      end = i + 1
      continue
    }
    const item = n === indent && /^-(\s|$)/.test(line.trimStart())
    if (item && opensBlock && (sameIndentList || end === start + 1)) {
      sameIndentList = true
      end = i + 1
      continue
    }
    break
  }
  return end
}

interface Entry {
  at: number
  end: number
  indent: number
}

/** A top-level key whose value is a block. */
function topEntry(lines: readonly string[], key: string): Entry | null {
  const re = keyRe(key)
  const at = lines.findIndex((l) => indentOf(l) === 0 && re.test(l) && valueAfterKey(l) === '')
  return at < 0 ? null : { at, end: entryEnd(lines, at, 0), indent: 0 }
}

/** The indentation of an entry's children: that of its first line below it. */
function childIndent(lines: readonly string[], parent: Entry): number | null {
  for (let i = parent.at + 1; i < parent.end; i++) if (!blank(lines[i])) return indentOf(lines[i])
  return null
}

/** A key directly under a block entry. */
function childEntry(lines: readonly string[], parent: Entry, key: string): Entry | null {
  const indent = childIndent(lines, parent)
  if (indent === null) return null
  const re = keyRe(key)
  for (let i = parent.at + 1; i < parent.end; i++) {
    const line = lines[i]
    if (blank(line) || indentOf(line) !== indent) continue
    const text = line.trimStart()
    if (!text.startsWith('-') && re.test(text)) return { at: i, end: entryEnd(lines, i, indent), indent }
  }
  return null
}

interface Item {
  /** the `- ` line */
  at: number
  end: number
  /** the indentation of its keys */
  keys: number
}

/** The action of that id in `spec.actions`: its lines, or 'flow' when it is written on one line, or null when it is not there. */
function findAction(lines: readonly string[], id: string): Item | 'flow' | null {
  const spec = topEntry(lines, 'spec')
  const actions = spec && childEntry(lines, spec, 'actions')
  if (!actions) return null
  if (valueAfterKey(lines[actions.at].trimStart()) !== '') return 'flow'
  const idRe = keyRe('id')
  const flowRe = new RegExp(`[{,]\\s*id\\s*:\\s*(?:${escape(id)}|"${escape(id)}"|'${escape(id)}')\\s*[,}]`)
  let dash: number | null = null
  for (let i = actions.at + 1; i < actions.end; i++) {
    const line = lines[i]
    if (blank(line)) continue
    const text = line.trimStart()
    const n = indentOf(line)
    dash ??= n
    if (n !== dash || !/^-(\s|$)/.test(text)) continue
    const head = text.replace(/^-\s*/, '')
    const end = entryEnd(lines, i, n)
    if (head.startsWith('{')) {
      if (flowRe.test(head)) return 'flow'
      continue
    }
    const keys = n + (text.length - head.length)
    let found = idRe.test(head) && unquote(valueAfterKey(head)) === id
    for (let j = i + 1; !found && j < end; j++) {
      const l = lines[j]
      if (!blank(l) && indentOf(l) === keys && idRe.test(l.trimStart())) found = unquote(valueAfterKey(l.trimStart())) === id
    }
    if (found) return { at: i, end, keys }
  }
  return null
}

/** Where a key of the action is written: its line and the lines below it, or null when the action has none. */
function keyIn(lines: readonly string[], item: Item, key: string): { at: number; end: number; first: boolean } | null {
  const re = keyRe(key)
  const head = lines[item.at].trimStart().replace(/^-\s*/, '')
  if (re.test(head)) return { at: item.at, end: nestedEnd(lines, item.at, item.keys, item.end), first: true }
  for (let i = item.at + 1; i < item.end; i++) {
    const line = lines[i]
    if (!blank(line) && indentOf(line) === item.keys && re.test(line.trimStart())) return { at: i, end: entryEnd(lines, i, item.keys), first: false }
  }
  return null
}

/** The lines below the first line of an item that belong to its first key: deeper than the item's keys. */
function nestedEnd(lines: readonly string[], at: number, keys: number, limit: number): number {
  let end = at + 1
  for (let i = at + 1; i < limit; i++) {
    if (blank(lines[i])) continue
    if (indentOf(lines[i]) <= keys) break
    end = i + 1
  }
  return end
}

/** Sets one key of an action; false when it cannot (the action is gone, or the key to remove is its first). */
function setKey(lines: string[], id: string, key: string, value: unknown): boolean {
  const item = findAction(lines, id)
  if (item === null || item === 'flow') return false
  const at = keyIn(lines, item, key)
  const line = `${pad(item.keys)}${key}: ${flowValue(value)}`
  if (at) {
    if (at.first) {
      if (value === undefined) return false
      lines.splice(at.at, at.end - at.at, `${lines[item.at].slice(0, item.keys)}${key}: ${flowValue(value)}`)
      return true
    }
    lines.splice(at.at, at.end - at.at, ...(value === undefined ? [] : [line]))
    return true
  }
  if (value === undefined) return true
  // after the last key the wizard writes before this one, else after the item's first key
  const rank = ORDER.indexOf(key)
  let after = nestedEnd(lines, item.at, item.keys, item.end)
  for (const k of ORDER.slice(0, rank < 0 ? ORDER.length : rank)) {
    const found = keyIn(lines, item, k)
    if (found && found.end > after) after = found.end
  }
  lines.splice(after, 0, line)
  return true
}

/**
 * The file with these fields of one action set (undefined removes a field), each written on one
 * line; null when the action cannot be edited this way, and then nothing is changed.
 */
export function withActionFields(yaml: string, actionId: string, fields: Partial<Record<ActionField, unknown>>): string | null {
  const lines = yaml.split('\n')
  if (!canEdit(lines, actionId)) return null
  for (const [key, value] of Object.entries(fields)) if (!setKey(lines, actionId, key, value)) return null
  return lines.join('\n')
}

/**
 * The file with `spec.overrides.<action>.response` set to this schema, the rest of the overrides
 * as they were; null when the file cannot be edited this way.
 */
export function withOverride(yaml: string, actionId: string, schema: unknown): string | null {
  const lines = yaml.split('\n')
  const spec = topEntry(lines, 'spec')
  if (!spec) return null
  const step = childIndent(lines, spec)
  if (step === null) return null
  const entry = (indent: number) => [`${pad(indent)}${flowString(actionId)}:`, `${pad(indent + step)}response: ${flowValue(schema)}`]
  const overrides = childEntry(lines, spec, 'overrides')
  if (!overrides) {
    const actions = childEntry(lines, spec, 'actions')
    lines.splice(actions ? actions.end : spec.end, 0, `${pad(step)}overrides:`, ...entry(step * 2))
    return lines.join('\n')
  }
  const value = valueAfterKey(lines[overrides.at].trimStart())
  if (value === '{}') {
    lines.splice(overrides.at, 1, `${pad(step)}overrides:`, ...entry(step * 2))
    return lines.join('\n')
  }
  if (value !== '') return null
  const inner = childIndent(lines, overrides) ?? step * 2
  const own = childEntry(lines, overrides, actionId)
  if (own) lines.splice(own.at, own.end - own.at, ...entry(inner))
  else lines.splice(overrides.end, 0, ...entry(inner))
  return lines.join('\n')
}

/** Whether the file has the action in block style, so `withActionFields` can edit it. */
export function canEditAction(yaml: string, actionId: string): boolean {
  return canEdit(yaml.split('\n'), actionId)
}

function canEdit(lines: readonly string[], actionId: string): boolean {
  const item = findAction(lines, actionId)
  return item !== null && item !== 'flow'
}

/* ----------------------------------------------------------------------------
   Error classes (`spec.errors`)
   ------------------------------------------------------------------------- */

/** One error class as the designer writes it: a list item on one line, in flow style, as the repository's files write them. */
const errorLine = (indent: number, e: Record<string, unknown>) => `${pad(indent)}- ${flowValue(e)}`

/** The rows' entries of `spec.errors`: a row left blank is none. */
const errorEntries = (rows: readonly ErrorRow[]) => rows.map(errorDocument).filter((e): e is Record<string, unknown> => e !== undefined)

/** A scalar of a one-line flow mapping: what follows `key:` up to the next `,` or `}`, a quoted value whole. */
function flowField(text: string, key: string): string | undefined {
  const m = new RegExp(`(?:^|[{,\\s])${escape(key)}\\s*:\\s*("(?:[^"\\\\]|\\\\.)*"|'(?:[^']|'')*'|[^,}]*)`).exec(text)
  return m ? unquote(m[1]) : undefined
}

/**
 * The provider file's error classes as the Settings tab's rows: none when it has no `spec.errors`,
 * and null when its block is not written as the designer writes it (one flow-style item per line
 * holding a status, the text the body contains, a class and retry, and nothing else), so that
 * writing the rows back (`withErrors`) loses nothing. Each item is read, then written again and
 * compared with its line, so a field the rows do not hold makes the block null.
 */
export function errorRowsOf(yaml: string): ErrorRow[] | null {
  const lines = yaml.split('\n')
  const spec = topEntry(lines, 'spec')
  if (!spec) return null
  const errors = childEntry(lines, spec, 'errors')
  if (!errors) return []
  const value = valueAfterKey(lines[errors.at].trimStart())
  if (value === '[]') return []
  if (value !== '') return null
  const rows: ErrorRow[] = []
  let indent: number | null = null
  for (let i = errors.at + 1; i < errors.end; i++) {
    const line = lines[i]
    if (line.trim() === '') continue
    const n = indentOf(line)
    indent ??= n
    const item = /^-\s+(\{.*\})\s*$/.exec(line.trimStart())
    if (n !== indent || !item) return null
    const flow = item[1]
    const row = blankError({ status: flowField(flow, 'status') ?? '', body: flowField(flow, 'body') ?? '', class: flowField(flow, 'class') ?? '', retry: flowField(flow, 'retry') === 'true' })
    const entry = errorDocument(row)
    if (!entry || errorLine(n, entry) !== line.trimEnd()) return null
    rows.push(row)
  }
  return rows
}

/** Whether the file's error classes are the ones these rows write, so the rows can stand for them. */
export function errorsWrittenAs(yaml: string, rows: readonly ErrorRow[]): boolean {
  const own = errorRowsOf(yaml)
  return own !== null && JSON.stringify(errorEntries(own)) === JSON.stringify(errorEntries(rows))
}

/**
 * The file with `spec.errors` written from these rows, one item per line (the block left out when
 * no row names a class), placed after the actions and overrides when the file had none; null when
 * the file has no `spec` block, or error classes the rows cannot stand for (`errorRowsOf`).
 */
export function withErrors(yaml: string, rows: readonly ErrorRow[]): string | null {
  const lines = yaml.split('\n')
  const spec = topEntry(lines, 'spec')
  if (!spec || errorRowsOf(yaml) === null) return null
  const step = childIndent(lines, spec)
  if (step === null) return null
  const errors = childEntry(lines, spec, 'errors')
  // the items where the file has them, else indented under the key as the repository's files write them
  const first = errors ? lines.slice(errors.at + 1, errors.end).find((l) => l.trim() !== '') : undefined
  const items = first !== undefined ? indentOf(first) : step * 2
  const entries = errorEntries(rows)
  const block = entries.length === 0 ? [] : [`${pad(step)}errors:`, ...entries.map((e) => errorLine(items, e))]
  if (errors) lines.splice(errors.at, errors.end - errors.at, ...block)
  else if (block.length > 0) {
    const after = childEntry(lines, spec, 'overrides') ?? childEntry(lines, spec, 'actions')
    lines.splice(after ? after.end : spec.end, 0, ...block)
  }
  return lines.join('\n')
}

/** The error classes as the file writes them, for a section that shows them without editing them: the block's lines, or the rows' as the designer writes them. */
export function errorClassesText(source: string | readonly ErrorRow[]): string {
  if (typeof source !== 'string') return errorEntries(source).map((e) => errorLine(0, e)).join('\n')
  const lines = source.split('\n')
  const spec = topEntry(lines, 'spec')
  const errors = spec && childEntry(lines, spec, 'errors')
  if (!errors) return ''
  const own = lines.slice(errors.at + 1, errors.end).filter((l) => l.trim() !== '')
  const head = valueAfterKey(lines[errors.at].trimStart())
  if (own.length === 0) return head === '[]' ? '' : head
  const least = Math.min(...own.map(indentOf))
  return own.map((l) => l.slice(least)).join('\n')
}
