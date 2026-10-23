import { useId, useRef, useState, type KeyboardEvent, type ReactNode } from 'react'
import { Button, Icon, PopoverPanel, cx, fuzzyFilter } from '@iaf/ui'

/*
 * Filtering a list the way the big consoles do: tokens of property, operator and value, typed
 * or picked from suggestions, one keyboard flow (property, then operator, then value, Enter
 * commits, Backspace steps back or removes the last token, Escape closes). Free text without a
 * property searches everything. The matching itself is a pure function (applyFilters), so a
 * server can do it instead (tokensToQuery).
 */

export type FilterOperator = '=' | '!=' | ':' | '!:' | '>' | '<' | '>=' | '<='

export const OPERATOR_LABEL: Record<FilterOperator, string> = {
  '=': 'equals',
  '!=': 'does not equal',
  ':': 'contains',
  '!:': 'does not contain',
  '>': 'is greater than',
  '<': 'is less than',
  '>=': 'is at least',
  '<=': 'is at most',
}

export const ALL_OPERATORS: readonly FilterOperator[] = ['=', '!=', ':', '!:', '>', '<', '>=', '<=']
export const DEFAULT_OPERATORS: readonly FilterOperator[] = ['=', '!=', ':', '!:']
export const NUMERIC_OPERATORS: readonly FilterOperator[] = ['=', '!=', '>', '<', '>=', '<=']

export interface FilterProperty {
  /** the field name the token carries */
  key: string
  /** the word people see */
  label: string
  operators?: readonly FilterOperator[]
  /** suggested values */
  values?: readonly string[]
}

export interface FilterToken {
  /** null: free text over everything */
  property: string | null
  operator: FilterOperator
  value: string
}

/** "status = ready", "name: web", "cpu >= 4" or free text as a token; null for nothing. */
export function parseToken(text: string, properties: readonly FilterProperty[]): FilterToken | null {
  const s = text.trim()
  if (!s) return null
  const m = /^(.+?)\s*(!=|>=|<=|!:|=|:|>|<)\s*(.+)$/.exec(s)
  if (m) {
    const word = m[1].trim().toLowerCase()
    const prop = properties.find((p) => p.label.toLowerCase() === word || p.key.toLowerCase() === word)
    const op = m[2] as FilterOperator
    if (prop && (prop.operators ?? DEFAULT_OPERATORS).includes(op)) return { property: prop.key, operator: op, value: m[3].trim() }
  }
  return { property: null, operator: ':', value: s }
}

/** A token in words: "Status = ready", or the quoted text of a free-text token. */
export function formatToken(token: FilterToken, properties: readonly FilterProperty[]): string {
  if (token.property === null) return `"${token.value}"`
  const label = properties.find((p) => p.key === token.property)?.label ?? token.property
  return `${label} ${token.operator} ${token.value}`
}

function asText(v: unknown): string {
  if (v === null || v === undefined) return ''
  if (Array.isArray(v)) return v.map(asText).join(' ')
  if (typeof v === 'object') return JSON.stringify(v)
  return String(v)
}

function compare(a: unknown, b: string): number | null {
  const an = typeof a === 'number' ? a : Number(asText(a))
  const bn = Number(b)
  if (asText(a).trim() !== '' && Number.isFinite(an) && b.trim() !== '' && Number.isFinite(bn)) return an - bn
  const ad = Date.parse(asText(a))
  const bd = Date.parse(b)
  if (!Number.isNaN(ad) && !Number.isNaN(bd)) return ad - bd
  const at = asText(a)
  if (!at) return null
  return at.localeCompare(b, undefined, { numeric: true, sensitivity: 'base' })
}

/** Whether one value satisfies an operator and a typed value. */
export function matchesToken(value: unknown, operator: FilterOperator, typed: string): boolean {
  const hay = asText(value).toLowerCase()
  const needle = typed.trim().toLowerCase()
  switch (operator) {
    case '=': {
      const c = compare(value, typed)
      return c === 0 || hay === needle
    }
    case '!=':
      return !matchesToken(value, '=', typed)
    case ':':
      return hay.includes(needle)
    case '!:':
      return !hay.includes(needle)
    case '>': {
      const c = compare(value, typed)
      return c !== null && c > 0
    }
    case '<': {
      const c = compare(value, typed)
      return c !== null && c < 0
    }
    case '>=': {
      const c = compare(value, typed)
      return c !== null && c >= 0
    }
    case '<=': {
      const c = compare(value, typed)
      return c !== null && c <= 0
    }
    default:
      return false
  }
}

/**
 * The rows every token matches. `getValue(row, property)` reads a field; with `null` it returns
 * whatever free text should search (a name, an id, a joined set of fields).
 */
export function applyFilters<T>(rows: readonly T[], tokens: readonly FilterToken[], getValue: (row: T, property: string | null) => unknown): T[] {
  if (tokens.length === 0) return [...rows]
  return rows.filter((row) => tokens.every((t) => matchesToken(getValue(row, t.property), t.operator, t.value)))
}

/**
 * The tokens as query parameters: free text as `q`, equality as `<key>=<value>`, any other
 * operator as `<key>[<op>]=<value>` (so `cpu[>=]=4`). Two tokens on one key join with a comma.
 */
export function tokensToQuery(tokens: readonly FilterToken[]): Record<string, string> {
  const out: Record<string, string> = {}
  const add = (k: string, v: string) => {
    out[k] = out[k] ? `${out[k]},${v}` : v
  }
  for (const t of tokens) {
    if (t.property === null) add('q', t.value)
    else if (t.operator === '=') add(t.property, t.value)
    else add(`${t.property}[${t.operator}]`, t.value)
  }
  return out
}

type Stage = { kind: 'property' } | { kind: 'operator'; property: FilterProperty } | { kind: 'value'; property: FilterProperty; operator: FilterOperator }

interface Option {
  id: string
  label: ReactNode
  hint?: string
  run: () => void
}

export interface PropertyFilterBarProps {
  properties: readonly FilterProperty[]
  tokens: readonly FilterToken[]
  onChange: (tokens: FilterToken[]) => void
  placeholder?: string
  /** free text tokens (default true) */
  freeText?: boolean
  /** "12 matches", shown at the right */
  countText?: ReactNode
  disabled?: boolean
  className?: string
}

export function PropertyFilterBar({ properties, tokens, onChange, placeholder = 'Filter by property or search', freeText = true, countText, disabled, className }: PropertyFilterBarProps) {
  const id = useId()
  const inputRef = useRef<HTMLInputElement>(null)
  const [text, setText] = useState('')
  const [stage, setStage] = useState<Stage>({ kind: 'property' })
  const [open, setOpen] = useState(false)
  const [active, setActive] = useState(0)

  const reset = () => {
    setText('')
    setStage({ kind: 'property' })
    setActive(0)
  }
  const commit = (token: FilterToken) => {
    onChange([...tokens, token])
    reset()
    setOpen(true)
  }
  const remove = (index: number) => {
    onChange(tokens.filter((_, i) => i !== index))
    inputRef.current?.focus()
  }
  const edit = (index: number) => {
    const t = tokens[index]
    onChange(tokens.filter((_, i) => i !== index))
    setText(t.property === null ? t.value : formatToken(t, properties))
    setStage({ kind: 'property' })
    setOpen(true)
    inputRef.current?.focus()
  }

  const options: Option[] = []
  const q = text.trim()
  if (stage.kind === 'property') {
    const parsed = q ? parseToken(q, properties) : null
    if (parsed && parsed.property !== null) {
      options.push({ id: 'parsed', label: `Add filter ${formatToken(parsed, properties)}`, hint: 'filter', run: () => commit(parsed) })
    }
    const hits = q ? fuzzyFilter(q, properties, (p) => p.label).map((h) => h.item) : [...properties]
    for (const p of hits) {
      options.push({ id: `p:${p.key}`, label: p.label, hint: 'property', run: () => setStageAndText({ kind: 'operator', property: p }) })
    }
    if (freeText && q && !(parsed && parsed.property !== null)) {
      options.push({ id: 'free', label: `Search for "${q}"`, hint: 'free text', run: () => commit({ property: null, operator: ':', value: q }) })
    }
  } else if (stage.kind === 'operator') {
    const ops = stage.property.operators ?? DEFAULT_OPERATORS
    for (const op of ops) {
      if (q && !op.startsWith(q) && !OPERATOR_LABEL[op].includes(q.toLowerCase())) continue
      options.push({ id: `o:${op}`, label: `${op} ${OPERATOR_LABEL[op]}`, hint: 'operator', run: () => setStageAndText({ kind: 'value', property: stage.property, operator: op }) })
    }
  } else {
    const values = (stage.property.values ?? []).filter((v) => !q || v.toLowerCase().includes(q.toLowerCase()))
    for (const v of values) {
      options.push({ id: `v:${v}`, label: v, hint: 'value', run: () => commit({ property: stage.property.key, operator: stage.operator, value: v }) })
    }
    if (q && !values.some((v) => v === q)) {
      options.push({ id: 'typed', label: `Use "${q}"`, run: () => commit({ property: stage.property.key, operator: stage.operator, value: q }) })
    }
  }

  function setStageAndText(next: Stage) {
    setStage(next)
    setText('')
    setActive(0)
    setOpen(true)
  }

  const activeIndex = Math.min(active, Math.max(0, options.length - 1))
  const listboxId = `${id}-listbox`
  const optionId = (i: number) => `${id}-option-${i}`

  const onKeyDown = (e: KeyboardEvent<HTMLInputElement>) => {
    switch (e.key) {
      case 'ArrowDown':
        e.preventDefault()
        setOpen(true)
        setActive((a) => (options.length ? (a + 1) % options.length : 0))
        return
      case 'ArrowUp':
        e.preventDefault()
        setOpen(true)
        setActive((a) => (options.length ? (a - 1 + options.length) % options.length : 0))
        return
      case 'Enter': {
        e.preventDefault()
        if (open && options[activeIndex]) {
          options[activeIndex].run()
          return
        }
        if (stage.kind === 'value' && q) commit({ property: stage.property.key, operator: stage.operator, value: q })
        else if (stage.kind === 'property' && q) {
          const parsed = parseToken(q, properties)
          if (parsed && (parsed.property !== null || freeText)) commit(parsed)
        }
        return
      }
      case 'Escape':
        if (open) {
          e.preventDefault()
          setOpen(false)
        } else if (text || stage.kind !== 'property') {
          e.preventDefault()
          reset()
        }
        return
      case 'Backspace':
        if (text) return
        e.preventDefault()
        if (stage.kind === 'value') setStageAndText({ kind: 'operator', property: stage.property })
        else if (stage.kind === 'operator') setStageAndText({ kind: 'property' })
        else if (tokens.length) onChange(tokens.slice(0, -1))
        return
      default:
        return
    }
  }

  return (
    <div className={cx('relative', className)} data-testid="property-filter-bar">
      <div
        className={cx(
          'flex min-h-10 flex-wrap items-center gap-1.5 rounded-md border border-stroke-light bg-surface-secondary px-2 py-1 transition-colors focus-within:border-accent focus-within:ring-2 focus-within:ring-accent/25',
          disabled && 'opacity-50',
        )}
        onClick={() => inputRef.current?.focus()}
      >
        <Icon name="filter" size={14} className="ml-1 shrink-0 text-tertiary" />
        {tokens.map((t, i) => {
          const words = formatToken(t, properties)
          return (
            <span key={`${t.property ?? ''}${t.operator}${t.value}${i}`} className="inline-flex h-7 max-w-full items-center gap-1 rounded-full bg-accent-tint pl-2.5 pr-1 text-caption text-primary" data-token={words}>
              <button type="button" onClick={() => edit(i)} className="truncate rounded-sm hover:underline" title="Edit this filter" disabled={disabled}>
                {t.property === null ? (
                  <>
                    <span className="text-secondary">search </span>
                    {t.value}
                  </>
                ) : (
                  <>
                    <span className="font-medium">{properties.find((p) => p.key === t.property)?.label ?? t.property}</span> <span className="text-secondary">{t.operator}</span> {t.value}
                  </>
                )}
              </button>
              <button type="button" onClick={() => remove(i)} aria-label={`Remove filter ${words}`} disabled={disabled} className="inline-flex size-5 items-center justify-center rounded-full text-secondary transition-colors hover:bg-surface hover:text-primary">
                <Icon name="close" size={12} strokeWidth={2.2} />
              </button>
            </span>
          )
        })}
        {stage.kind !== 'property' && (
          <span className="inline-flex h-7 items-center gap-1 rounded-full border border-stroke-light bg-surface px-2 text-caption text-primary" aria-hidden="true">
            <span className="font-medium">{stage.property.label}</span>
            {stage.kind === 'value' && <span className="text-secondary">{stage.operator}</span>}
          </span>
        )}
        <input
          ref={inputRef}
          id={`${id}-input`}
          type="text"
          role="combobox"
          aria-expanded={open && options.length > 0}
          aria-controls={listboxId}
          aria-autocomplete="list"
          aria-activedescendant={open && options[activeIndex] ? optionId(activeIndex) : undefined}
          aria-label={stage.kind === 'property' ? 'Filter' : stage.kind === 'operator' ? `Operator for ${stage.property.label}` : `Value for ${stage.property.label} ${stage.operator}`}
          placeholder={tokens.length === 0 && stage.kind === 'property' ? placeholder : stage.kind === 'operator' ? 'operator' : stage.kind === 'value' ? 'value' : ''}
          value={text}
          disabled={disabled}
          autoComplete="off"
          spellCheck={false}
          onChange={(e) => {
            setText(e.target.value)
            setActive(0)
            setOpen(true)
          }}
          onFocus={() => setOpen(true)}
          onBlur={() => setOpen(false)}
          onKeyDown={onKeyDown}
          className="min-w-32 flex-1 bg-transparent py-1 text-sm text-primary outline-none placeholder:text-tertiary"
        />
        {tokens.length > 0 && (
          <Button size="sm" variant="ghost" onClick={() => onChange([])} disabled={disabled} className="shrink-0">
            Clear filters
          </Button>
        )}
        {countText && <span className="shrink-0 pr-1 text-caption text-secondary">{countText}</span>}
      </div>
      {open && options.length > 0 && (
        <PopoverPanel role="listbox" ariaLabel="Suggestions" className="absolute left-0 top-full z-50 mt-1 max-h-72 w-full max-w-md overflow-auto py-1">
          {options.map((o, i) => (
            <div
              key={o.id}
              id={optionId(i)}
              role="option"
              aria-selected={i === activeIndex}
              onMouseDown={(e) => e.preventDefault()}
              // the highlight follows the pointer only when it moves: the browser also reports the pointer
              // entering when the list appears or changes under a pointer at rest, and Enter must then still
              // pick what the keys chose, not whatever the list happened to open under
              onMouseMove={() => {
                if (i !== activeIndex) setActive(i)
              }}
              onClick={o.run}
              className={cx('flex cursor-pointer items-center justify-between gap-3 px-3 py-1.5 text-sm text-primary', i === activeIndex && 'bg-accent-tint')}
            >
              <span className="truncate">{o.label}</span>
              {o.hint && <span className="shrink-0 text-caption text-tertiary">{o.hint}</span>}
            </div>
          ))}
        </PopoverPanel>
      )}
    </div>
  )
}
