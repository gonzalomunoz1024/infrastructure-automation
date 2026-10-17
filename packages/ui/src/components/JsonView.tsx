import { useMemo, useState } from 'react'
import { cx } from '../lib/cx'
import { Icon } from './Icon'
import { Segmented } from './Segmented'
import { CodeBlock } from './CodeBlock'

export interface JsonViewProps {
  value: unknown
  /** levels open on first render (default 1) */
  defaultExpanded?: number
  /** the Tree / Raw switch (default true) */
  rawToggle?: boolean
  /** root label */
  name?: string
  /** class for the scroll area */
  maxHeight?: string
  className?: string
}

function safeStringify(value: unknown): string {
  try {
    return JSON.stringify(value, null, 2) ?? 'undefined'
  } catch {
    return String(value)
  }
}

function Primitive({ value }: { value: unknown }) {
  if (value === null) return <span className="text-tertiary">null</span>
  if (value === undefined) return <span className="text-tertiary">undefined</span>
  if (typeof value === 'string') return <span className="text-primary">{`"${value}"`}</span>
  if (typeof value === 'number' || typeof value === 'bigint') return <span className="text-accent tabular-nums">{String(value)}</span>
  if (typeof value === 'boolean') return <span className="text-accent">{String(value)}</span>
  return <span className="text-secondary">{String(value)}</span>
}

function Node({ name, value, depth, expandDepth }: { name?: string; value: unknown; depth: number; expandDepth: number }) {
  const [open, setOpen] = useState(depth < expandDepth)
  const isObject = value !== null && typeof value === 'object'
  const indent = { paddingLeft: depth * 14 }

  if (!isObject) {
    return (
      <div className="flex items-start gap-1.5 leading-5" style={indent}>
        <span className="w-[11px] shrink-0" />
        {name !== undefined && <span className="shrink-0 text-secondary">{`${name}:`}</span>}
        <span className="break-all">
          <Primitive value={value} />
        </span>
      </div>
    )
  }

  const entries: [string, unknown][] = Array.isArray(value) ? value.map((v, i) => [String(i), v]) : Object.entries(value as Record<string, unknown>)
  const brackets = Array.isArray(value) ? ['[', ']'] : ['{', '}']
  const summary = Array.isArray(value) ? `${entries.length} ${entries.length === 1 ? 'item' : 'items'}` : `${entries.length} ${entries.length === 1 ? 'key' : 'keys'}`

  return (
    <div>
      <button
        type="button"
        onClick={() => setOpen((o) => !o)}
        aria-expanded={open}
        className="flex w-full items-center gap-1.5 text-left leading-5 transition-colors hover:text-primary"
        style={indent}
      >
        <Icon name="chevron-right" size={11} className={cx('shrink-0 text-tertiary transition-transform duration-150', open && 'rotate-90')} />
        {name !== undefined && <span className="text-secondary">{`${name}:`}</span>}
        <span className="text-tertiary">{open ? brackets[0] : `${brackets[0]} ${summary} ${brackets[1]}`}</span>
      </button>
      {open && (
        <>
          {entries.length === 0 && (
            <div className="leading-5 text-tertiary" style={{ paddingLeft: (depth + 1) * 14 + 16 }}>
              empty
            </div>
          )}
          {entries.map(([k, v]) => (
            <Node key={k} name={k} value={v} depth={depth + 1} expandDepth={expandDepth} />
          ))}
          <div className="leading-5 text-tertiary" style={{ paddingLeft: depth * 14 + 16 }}>
            {brackets[1]}
          </div>
        </>
      )}
    </div>
  )
}

/** Collapsible JSON tree with a raw toggle: inputs, outputs, attributes. */
export function JsonView({ value, defaultExpanded = 1, rawToggle = true, name, maxHeight, className }: JsonViewProps) {
  const [mode, setMode] = useState<'tree' | 'raw'>('tree')
  const raw = useMemo(() => safeStringify(value), [value])
  return (
    <div className={className}>
      {rawToggle && (
        <div className="mb-2 flex justify-end">
          <Segmented
            size="sm"
            ariaLabel="JSON view"
            value={mode}
            onChange={setMode}
            options={[
              { value: 'tree', label: 'Tree' },
              { value: 'raw', label: 'Raw' },
            ]}
          />
        </div>
      )}
      {mode === 'raw' ? (
        <CodeBlock code={raw} language="json" maxHeight={maxHeight} />
      ) : (
        <div className={cx('overflow-auto rounded-md border border-stroke-light bg-surface-secondary p-3 font-mono text-caption', maxHeight)}>
          <Node name={name} value={value} depth={0} expandDepth={defaultExpanded} />
        </div>
      )}
    </div>
  )
}
