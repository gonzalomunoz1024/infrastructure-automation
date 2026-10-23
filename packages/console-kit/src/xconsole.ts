import { X_CONSOLE_FORMATS, X_CONSOLE_WIDGETS, type Schema, type XConsole, type XConsoleFormat, type XConsoleWidget } from './types'

/*
 * The `x-console` keywords a type author puts on a schema property (platform change P-06, item
 * 4): what they mean to a form, a list and a detail page. Unknown words are ignored, not errors,
 * so a type can carry hints for a newer console than this one.
 */

export function isXConsoleFormat(s: unknown): s is XConsoleFormat {
  return typeof s === 'string' && (X_CONSOLE_FORMATS as readonly string[]).includes(s)
}

export function isXConsoleWidget(s: unknown): s is XConsoleWidget {
  return typeof s === 'string' && (X_CONSOLE_WIDGETS as readonly string[]).includes(s)
}

/** The x-console block of a property, with an unknown format or widget dropped. */
export function xConsoleOf(prop: Schema | undefined): XConsole {
  const raw = prop?.['x-console']
  if (!raw || typeof raw !== 'object') return {}
  const x: XConsole = { ...raw }
  if (x.format !== undefined && !isXConsoleFormat(x.format)) delete x.format
  if (x.widget !== undefined && !isXConsoleWidget(x.widget)) delete x.widget
  if (x.group !== undefined && (typeof x.group !== 'string' || !x.group.trim())) delete x.group
  if (x.help !== undefined && typeof x.help !== 'string') delete x.help
  return x
}

function flagged(schema: Schema | undefined, flag: 'column' | 'searchable' | 'sensitive' | 'mutable'): string[] {
  return Object.entries(schema?.properties ?? {})
    .filter(([, prop]) => xConsoleOf(prop)[flag] === true)
    .map(([name]) => name)
}

/** The properties a type marks as list columns, in schema order. */
export function columnFields(schema: Schema | undefined): string[] {
  return flagged(schema, 'column')
}

/** The properties promoted to indexed filters. */
export function searchableFields(schema: Schema | undefined): string[] {
  return flagged(schema, 'searchable')
}

/** The properties that are masked and never indexed. */
export function sensitiveFields(schema: Schema | undefined): string[] {
  return flagged(schema, 'sensitive')
}

/** The properties the type's update workflow may change. */
export function mutableFields(schema: Schema | undefined): string[] {
  return flagged(schema, 'mutable')
}

export type FieldGroup = { group: string | null; names: string[] }

/**
 * Property names in form order, grouped: fields without a group keep their place one by one; a
 * group appears where its first member does and holds every member, in schema order.
 */
export function groupFields(schema: Schema | undefined, names = Object.keys(schema?.properties ?? {})): FieldGroup[] {
  const props = schema?.properties ?? {}
  const out: FieldGroup[] = []
  const groups = new Map<string, FieldGroup>()
  for (const name of names) {
    const group = xConsoleOf(props[name]).group ?? null
    if (group === null) {
      out.push({ group: null, names: [name] })
      continue
    }
    let g = groups.get(group)
    if (!g) {
      g = { group, names: [] }
      groups.set(group, g)
      out.push(g)
    }
    g.names.push(name)
  }
  return out
}

/** The unit written after a number of this format, if any. */
export function unitOf(format: XConsoleFormat | undefined, currency?: string): string | undefined {
  switch (format) {
    case 'bytes':
      return 'bytes'
    case 'gibibytes':
      return 'GiB'
    case 'percent':
      return '%'
    case 'duration':
      return 's'
    case 'cost':
      return currency ?? 'USD'
    default:
      return undefined
  }
}
