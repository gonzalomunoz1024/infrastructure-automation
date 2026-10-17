/*
 * The one place the browser writes YAML.
 *
 * docs/contributing.md says the browser never parses YAML: the backend renders and parses
 * (`/workflows:parse`, `/workflows:render`). Two things still need a `yaml` string before any
 * server-side document exists: the Add-provider wizard, whose draft is a plain object and whose
 * `/providers:validate`, `:try` and `:publish` calls take `{ yaml }`, and the "Blank" tile of
 * New workflow, which creates its first draft with `POST /workflows { yaml }`. This emitter turns
 * a plain object into deterministic block-style YAML for them. It never reads YAML back, and the
 * unit test round-trips its output through the `yaml` package to prove it is well formed.
 */

const INDENT = '  '

type Scalar = string | number | boolean | null

function isScalar(v: unknown): v is Scalar {
  return v === null || typeof v === 'string' || typeof v === 'number' || typeof v === 'boolean'
}

function isPlainObject(v: unknown): v is Record<string, unknown> {
  return typeof v === 'object' && v !== null && !Array.isArray(v)
}

/** Keys whose value is undefined are left out, so optional wizard fields never print as `null`. */
function entriesOf(obj: Record<string, unknown>): [string, unknown][] {
  return Object.entries(obj).filter(([, v]) => v !== undefined)
}

// words YAML 1.1 (SnakeYAML on the backend) or 1.2 would read as something other than a string
const RESERVED = /^(?:null|Null|NULL|~|true|True|TRUE|false|False|FALSE|yes|Yes|YES|no|No|NO|on|On|ON|off|Off|OFF|y|Y|n|N|\.inf|\.Inf|\.INF|-\.inf|-\.Inf|-\.INF|\.nan|\.NaN|\.NAN)$/
const NUMBER = /^[-+]?(?:\.[0-9]+|[0-9][0-9_]*(?:\.[0-9_]*)?)(?:[eE][-+]?[0-9]+)?$/
const SPECIAL_NUMBER = /^[-+]?(?:0x[0-9a-fA-F_]+|0o[0-7_]+|0b[01_]+|0[0-7]+|[0-9]+(?::[0-5]?[0-9])+(?:\.[0-9]*)?)$/
const TIMESTAMP = /^[0-9]{4}-[0-9]{1,2}-[0-9]{1,2}(?:[Tt ].*)?$/

/** True when the string must be quoted to come back as the same string. */
export function needsQuotes(s: string): boolean {
  if (s === '') return true
  if (/^\s|\s$/.test(s)) return true
  // eslint-disable-next-line no-control-regex
  if (/[\u0000-\u001f\u007f"\\]/.test(s)) return true
  if (/^[-?:,[\]{}#&*!|>'"%@`]/.test(s)) return true
  if (/: |\s#/.test(s)) return true
  if (s.endsWith(':')) return true
  if (RESERVED.test(s) || NUMBER.test(s) || SPECIAL_NUMBER.test(s) || TIMESTAMP.test(s)) return true
  return false
}

function quote(s: string): string {
  let out = '"'
  for (const ch of s) {
    const code = ch.codePointAt(0) ?? 0
    if (ch === '"') out += '\\"'
    else if (ch === '\\') out += '\\\\'
    else if (ch === '\n') out += '\\n'
    else if (ch === '\t') out += '\\t'
    else if (ch === '\r') out += '\\r'
    else if (code < 0x20 || code === 0x7f) out += `\\x${code.toString(16).padStart(2, '0')}`
    else out += ch
  }
  return `${out}"`
}

function scalar(v: Scalar): string {
  if (v === null) return 'null'
  if (typeof v === 'boolean') return v ? 'true' : 'false'
  if (typeof v === 'number') {
    if (Number.isNaN(v)) return '.nan'
    if (!Number.isFinite(v)) return v > 0 ? '.inf' : '-.inf'
    return String(v)
  }
  return needsQuotes(v) ? quote(v) : v
}

function key(k: string): string {
  return needsQuotes(k) ? quote(k) : k
}

/** `- ` plus the first line of a nested block, the rest indented one level deeper. */
function listItem(inner: string[], depth: number): string[] {
  const pad = INDENT.repeat(depth)
  const strip = INDENT.length * (depth + 1)
  return [`${pad}- ${inner[0].slice(strip)}`, ...inner.slice(1)]
}

function emitList(items: unknown[], depth: number): string[] {
  const pad = INDENT.repeat(depth)
  const out: string[] = []
  for (const item of items) {
    if (isScalar(item)) out.push(`${pad}- ${scalar(item)}`)
    else if (Array.isArray(item)) out.push(...(item.length === 0 ? [`${pad}- []`] : listItem(emitList(item, depth + 1), depth)))
    else if (isPlainObject(item)) {
      const entries = entriesOf(item)
      out.push(...(entries.length === 0 ? [`${pad}- {}`] : listItem(emitMap(entries, depth + 1), depth)))
    } else out.push(`${pad}- null`)
  }
  return out
}

function emitMap(entries: [string, unknown][], depth: number): string[] {
  const pad = INDENT.repeat(depth)
  const out: string[] = []
  for (const [k, v] of entries) {
    const head = `${pad}${key(k)}`
    if (isScalar(v)) out.push(`${head}: ${scalar(v)}`)
    else if (Array.isArray(v)) {
      if (v.length === 0) out.push(`${head}: []`)
      else out.push(`${head}:`, ...emitList(v, depth + 1))
    } else if (isPlainObject(v)) {
      const sub = entriesOf(v)
      if (sub.length === 0) out.push(`${head}: {}`)
      else out.push(`${head}:`, ...emitMap(sub, depth + 1))
    } else out.push(`${head}: null`)
  }
  return out
}

/** A plain object (or array, or scalar) as block-style YAML, two-space indented, ending with a newline. */
export function emitYaml(value: unknown): string {
  let lines: string[]
  if (isScalar(value)) lines = [scalar(value)]
  else if (Array.isArray(value)) lines = value.length === 0 ? ['[]'] : emitList(value, 0)
  else if (isPlainObject(value)) {
    const entries = entriesOf(value)
    lines = entries.length === 0 ? ['{}'] : emitMap(entries, 0)
  } else lines = ['null']
  return `${lines.join('\n')}\n`
}

