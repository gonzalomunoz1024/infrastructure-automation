import { Fragment, useState, type ReactNode } from 'react'
import { CopyIconButton, Segmented, cx } from '@iaf/ui'

/*
 * View as code: the same request, or the same record, in every language a person might take
 * it to. The samples are built by the app (it knows its endpoints and its CLI); the kit shows
 * them under one segmented control with a copy button each, and holds the helpers that turn a
 * value into YAML, a request into a curl command, and words into a shell-safe line.
 */

export interface CodeSample {
  id: string
  /** the segment's label: "CLI", "REST", "YAML" */
  label: string
  /** shown in the block's title bar */
  language: string
  code: string
  title?: ReactNode
  /** one line under the block: where to run it, what to fill in */
  note?: ReactNode
}

export interface CodeViewProps {
  samples: CodeSample[]
  /** the selected sample's id; uncontrolled when left out */
  value?: string
  onChange?: (id: string) => void
  defaultId?: string
  ariaLabel?: string
  maxHeight?: string
  lineNumbers?: boolean
  wrap?: boolean
  size?: 'sm' | 'md'
  className?: string
}

export function CodeView({ samples, value, onChange, defaultId, ariaLabel = 'View as code', maxHeight = 'max-h-96', lineNumbers, wrap, size = 'sm', className }: CodeViewProps) {
  const [own, setOwn] = useState(defaultId ?? samples[0]?.id ?? '')
  const selected = value ?? own
  const current = samples.find((s) => s.id === selected) ?? samples[0]
  const pick = (id: string) => {
    setOwn(id)
    onChange?.(id)
  }
  if (!current) return null
  return (
    <div className={cx('min-w-0 space-y-2', className)} data-testid="code-view">
      {samples.length > 1 && <Segmented<string> ariaLabel={ariaLabel} size={size} value={current.id} onChange={pick} options={samples.map((s) => ({ value: s.id, label: s.label }))} />}
      <CodeBox key={current.id} code={current.code} language={current.language} title={current.title} label={`${current.label} code`} lineNumbers={lineNumbers} wrap={wrap} maxHeight={maxHeight} />
      {current.note && <p className="text-caption text-tertiary">{current.note}</p>}
    </div>
  )
}

/**
 * Atlas's CodeBlock, drawn the same, with a scroll area a keyboard can reach: a long curl line
 * scrolls sideways, so the area takes focus and names itself (axe scrollable-region-focusable).
 */
function CodeBox({ code, language, title, label, lineNumbers, wrap, maxHeight }: { code: string; language: string; title?: ReactNode; label: string; lineNumbers?: boolean; wrap?: boolean; maxHeight?: string }) {
  const lines = code.replace(/\n$/, '').split('\n')
  return (
    <div className="relative overflow-hidden rounded-md border border-stroke-light bg-surface-secondary">
      <div className="flex h-8 items-center justify-between gap-3 border-b border-stroke-light pl-3 pr-1 text-caption2 text-tertiary">
        <span className="min-w-0 truncate font-medium text-secondary">{title}</span>
        <span className="flex items-center gap-1">
          <span className="font-mono uppercase">{language}</span>
          <CopyIconButton text={code} />
        </span>
      </div>
      <pre
        tabIndex={0}
        role="region"
        aria-label={label}
        className={cx('overflow-auto p-3 font-mono text-caption leading-relaxed text-primary outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-accent/40', wrap ? 'whitespace-pre-wrap break-words' : 'whitespace-pre', maxHeight)}
      >
        {lineNumbers ? (
          <code className="grid grid-cols-[auto_1fr] gap-x-3">
            {lines.map((l, i) => (
              <Fragment key={i}>
                <span className="select-none text-right tabular-nums text-tertiary">{i + 1}</span>
                <span>{l || ' '}</span>
              </Fragment>
            ))}
          </code>
        ) : (
          <code>{code}</code>
        )}
      </pre>
    </div>
  )
}

/* ----------------------------------------------------------------------------
   YAML
   ------------------------------------------------------------------------- */

const RESERVED = new Set(['true', 'false', 'null', 'yes', 'no', 'on', 'off', '~', 'y', 'n'])
const PLAIN = /^[A-Za-z_][A-Za-z0-9_./@+-]*(?: [A-Za-z0-9_./@+-]+)*$/

function yamlScalar(v: unknown): string {
  if (v === null || v === undefined) return 'null'
  if (typeof v === 'number') return Number.isFinite(v) ? String(v) : 'null'
  if (typeof v === 'boolean') return v ? 'true' : 'false'
  const s = typeof v === 'string' ? v : JSON.stringify(v)
  if (s === '') return "''"
  if (PLAIN.test(s) && !RESERVED.has(s.toLowerCase()) && !/^(\d|[+-]?\.?\d)/.test(s)) return s
  return JSON.stringify(s)
}

function isPlainObject(v: unknown): v is Record<string, unknown> {
  return !!v && typeof v === 'object' && !Array.isArray(v)
}

/**
 * A value as YAML, the way a person would write it: plain words unquoted, anything else
 * double-quoted (valid JSON is valid YAML), multi-line text as a `|` block, nesting by two
 * spaces, empty collections as `{}` and `[]`.
 */
export function toYaml(value: unknown, indent = 0): string {
  const pad = '  '.repeat(indent)
  if (Array.isArray(value)) {
    if (value.length === 0) return `${pad}[]`
    return value
      .map((item) => {
        if (isPlainObject(item) && Object.keys(item).length) {
          // the item's first key sits on the dash; the rest keep the item's indentation
          const inner = toYaml(item, indent + 1).split('\n')
          inner[0] = inner[0].trimStart()
          return `${pad}- ${inner.join('\n')}`
        }
        if (Array.isArray(item) && item.length) return `${pad}-\n${toYaml(item, indent + 1)}`
        return `${pad}- ${yamlScalar(item)}`
      })
      .join('\n')
  }
  if (isPlainObject(value)) {
    const entries = Object.entries(value).filter(([, v]) => v !== undefined)
    if (entries.length === 0) return `${pad}{}`
    return entries
      .map(([k, v]) => {
        const key = yamlScalar(k)
        if (typeof v === 'string' && v.includes('\n')) return `${pad}${key}: |\n${v.split('\n').map((line) => `${pad}  ${line}`).join('\n')}`
        if ((isPlainObject(v) && Object.keys(v).length) || (Array.isArray(v) && v.length)) return `${pad}${key}:\n${toYaml(v, indent + 1)}`
        if (isPlainObject(v)) return `${pad}${key}: {}`
        if (Array.isArray(v)) return `${pad}${key}: []`
        return `${pad}${key}: ${yamlScalar(v)}`
      })
      .join('\n')
  }
  return `${pad}${yamlScalar(value)}`
}

/* ----------------------------------------------------------------------------
   Shell lines
   ------------------------------------------------------------------------- */

/** A word a POSIX shell takes as written, else single-quoted. */
export function shellQuote(s: string): string {
  if (s === '') return "''"
  if (/^[A-Za-z0-9_./:=@%+,-]+$/.test(s)) return s
  return `'${s.replace(/'/g, `'\\''`)}'`
}

export interface CurlRequest {
  method: string
  url: string
  headers?: Record<string, string>
  /** JSON, written as a heredoc so it copies as one piece */
  body?: unknown
}

/** A curl command, one option per line, the body as a quoted heredoc. */
export function curlCommand({ method, url, headers = {}, body }: CurlRequest): string {
  const lines = [`curl -X ${method} ${shellQuote(url)}`]
  const all = { Accept: 'application/json', ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}), ...headers }
  for (const [k, v] of Object.entries(all)) lines.push(`  -H ${shellQuote(`${k}: ${v}`)}`)
  if (body === undefined) return lines.join(' \\\n')
  lines.push(`  --data @- <<'JSON'`)
  return `${lines.join(' \\\n')}\n${JSON.stringify(body, null, 2)}\nJSON`
}

/** `--name value` flags from an object: booleans as `--flag` / `--no-flag`, lists comma-joined, objects as JSON. */
export function cliFlags(values: Record<string, unknown>): string[] {
  const out: string[] = []
  for (const [k, v] of Object.entries(values)) {
    if (v === undefined || v === null || v === '') continue
    const flag = `--${k.replace(/([a-z0-9])([A-Z])/g, '$1-$2').toLowerCase()}`
    if (typeof v === 'boolean') out.push(v ? flag : `--no-${flag.slice(2)}`)
    else if (Array.isArray(v) && v.every((x) => typeof x !== 'object')) out.push(`${flag} ${shellQuote(v.map(String).join(','))}`)
    else if (typeof v === 'object') out.push(`${flag} ${shellQuote(JSON.stringify(v))}`)
    else out.push(`${flag} ${shellQuote(String(v))}`)
  }
  return out
}

/** A command and its flags, wrapped one flag per line when it would run long. */
export function cliCommand(words: string[], flags: string[] = []): string {
  const head = words.map(shellQuote).join(' ')
  if (flags.length === 0) return head
  const oneLine = `${head} ${flags.join(' ')}`
  if (oneLine.length <= 100) return oneLine
  return [head, ...flags].join(' \\\n  ')
}
