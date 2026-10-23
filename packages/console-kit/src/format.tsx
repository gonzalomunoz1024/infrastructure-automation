import type { ReactNode } from 'react'
import { bytes, duration, formatNumber, longDate, percent, shortDate } from '@iaf/ui'
import type { XConsole, XConsoleFormat } from './types'
import { parseDuration, summarize } from './common'
import { WfrnText } from './WfrnText'
import { xConsoleOf } from './xconsole'
import type { Schema } from './types'

/*
 * A value as a detail page or a table cell shows it, by its `x-console.format` (P-06): bytes
 * and gibibytes with units, durations and dates in words, costs with their currency, wfrns with
 * a copy button, code in monospace. Without a format the JSON type decides.
 */

export const HIDDEN_VALUE = '••••••••'

function num(v: unknown): number | null {
  if (typeof v === 'number' && Number.isFinite(v)) return v
  if (typeof v === 'string' && v.trim() !== '' && Number.isFinite(Number(v))) return Number(v)
  return null
}

/** The plain-text form of a value, for titles, sorting and CSV. */
export function formatValueText(value: unknown, x?: XConsole | XConsoleFormat | null): string {
  const hints: XConsole = typeof x === 'string' ? { format: x } : (x ?? {})
  if (hints.sensitive) return HIDDEN_VALUE
  if (value === undefined || value === null || value === '') return 'not set'
  const n = num(value)
  switch (hints.format) {
    case 'bytes':
      return n === null ? String(value) : bytes(n)
    case 'gibibytes':
      return n === null ? String(value) : `${formatNumber(n, { digits: 1 })} GiB`
    case 'duration': {
      if (n !== null) return duration(n * 1000)
      const ms = parseDuration(String(value))
      return ms === null ? String(value) : duration(ms)
    }
    case 'percent':
      return n === null ? String(value) : percent(n)
    case 'cost':
      return n === null ? String(value) : `${formatNumber(n, { digits: 2 })} ${hints.currency ?? 'USD'}`
    case 'date': {
      const d = new Date(typeof value === 'number' ? value : String(value))
      return Number.isNaN(d.getTime()) ? String(value) : shortDate(d)
    }
    case 'wfrn':
    case 'code':
      return String(value)
    default:
      break
  }
  if (typeof value === 'boolean') return value ? 'Yes' : 'No'
  if (typeof value === 'number') return formatNumber(value, { digits: 2 })
  if (typeof value === 'string') return value
  if (Array.isArray(value) && value.every((v) => typeof v !== 'object' || v === null)) return value.map(String).join(', ')
  return summarize(value)
}

export interface FormatOptions {
  /** the wfrn's link, when the value is one */
  wfrnHref?: string
  /** the wfrn display length (default 40) */
  wfrnMax?: number
}

/** A value rendered by its format: the text above, plus the markup a format calls for. */
export function formatValue(value: unknown, x?: XConsole | XConsoleFormat | null, opts: FormatOptions = {}): ReactNode {
  const hints: XConsole = typeof x === 'string' ? { format: x } : (x ?? {})
  if (hints.sensitive) {
    return (
      <span className="font-mono text-tertiary" aria-label="hidden" data-sensitive="true">
        {HIDDEN_VALUE}
      </span>
    )
  }
  if (value === undefined || value === null || value === '') return <span className="text-tertiary">not set</span>
  const text = formatValueText(value, hints)
  switch (hints.format) {
    case 'wfrn':
      return typeof value === 'string' ? <WfrnText wfrn={value} href={opts.wfrnHref} max={opts.wfrnMax} /> : <span className="font-mono">{text}</span>
    case 'code':
      return <code className="rounded-sm bg-surface-secondary px-1 py-0.5 font-mono text-caption text-primary">{text}</code>
    case 'date': {
      const d = new Date(typeof value === 'number' ? value : String(value))
      if (Number.isNaN(d.getTime())) return <span>{text}</span>
      return (
        <time dateTime={d.toISOString()} title={longDate(d)} className="tabular-nums">
          {text}
        </time>
      )
    }
    case 'bytes':
    case 'gibibytes':
    case 'duration':
    case 'percent':
    case 'cost':
      return (
        <span className="tabular-nums" title={num(value) !== null ? String(value) : undefined}>
          {text}
        </span>
      )
    default:
      break
  }
  if (typeof value === 'object') return <span className="font-mono text-caption text-secondary">{text}</span>
  if (typeof value === 'number') return <span className="tabular-nums">{text}</span>
  return <>{text}</>
}

/** The formatted value of a schema property, by its own x-console hints. */
export function formatField(schema: Schema | undefined, name: string, value: unknown, opts?: FormatOptions): ReactNode {
  return formatValue(value, xConsoleOf(schema?.properties?.[name]), opts)
}
