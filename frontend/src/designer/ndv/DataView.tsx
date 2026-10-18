/**
 * The data of the Input and Output panes in n8n's three views: Schema (the fields as pills with
 * their values), Table and JSON. In the Input pane every field can be dragged onto a parameter,
 * which maps it as an expression; Enter on a field copies that expression instead.
 */
import { useMemo, type DragEvent, type ReactNode } from 'react'
import { cx, useToast } from '@iaf/ui'
import type { JsonValue } from '@iaf/workflow-checks'
import type { Schema } from '../../lib/types'
import { cellText, flattenPaths, schemaPaths, tableOf, type PathEntry } from '../model/schema'
import { MAPPING_MIME, useNdv } from './context'

export type DataMode = 'schema' | 'table' | 'json'

export const DATA_MODES: { value: DataMode; label: string }[] = [
  { value: 'schema', label: 'Schema' },
  { value: 'table', label: 'Table' },
  { value: 'json', label: 'JSON' },
]

export interface DataViewProps {
  value: JsonValue | undefined
  /** shown as paths when there is no value yet */
  schema?: Schema | null
  mode: DataMode
  /** the expression a field maps to; makes fields draggable */
  mapping?: (path: string) => string
  label: string
}

const TYPE_MARK: Record<string, string> = { string: 'T', number: '#', integer: '#', boolean: '◐', object: '{ }', array: '[ ]', null: '∅', unknown: '?' }

function MapPill({ path, children, mapping, className }: { path: string; children: ReactNode; mapping?: (path: string) => string; className?: string }) {
  const ndv = useNdv()
  const toast = useToast()
  if (!mapping) return <span className={cx('rounded-sm border border-stroke-light bg-surface px-1.5 font-mono text-caption text-primary', className)}>{children}</span>
  const expr = mapping(path)
  const onDragStart = (e: DragEvent<HTMLSpanElement>) => {
    e.dataTransfer.setData('text/plain', expr)
    e.dataTransfer.setData(MAPPING_MIME, expr)
    e.dataTransfer.effectAllowed = 'copy'
    ndv.setDragging(true)
  }
  const copy = () => {
    void navigator.clipboard?.writeText(expr).then(
      () => toast.toast({ title: 'Expression copied', message: expr, tone: 'neutral', duration: 2500 }),
      () => {},
    )
  }
  return (
    <span
      role="button"
      tabIndex={0}
      draggable
      title={`Drag onto a parameter to map ${expr}`}
      aria-label={`${path}: drag onto a parameter, or press Enter to copy ${expr}`}
      onDragStart={onDragStart}
      onDragEnd={() => ndv.setDragging(false)}
      onKeyDown={(e) => {
        if (e.key === 'Enter' || e.key === ' ') {
          e.preventDefault()
          copy()
        }
      }}
      className={cx('iaf-map-pill rounded-sm border border-stroke-light bg-surface px-1.5 font-mono text-caption text-primary hover:border-accent hover:text-accent', className)}
    >
      {children}
    </span>
  )
}

function SchemaView({ entries, mapping }: { entries: PathEntry[]; mapping?: (path: string) => string }) {
  return (
    <ul className="space-y-1 py-1">
      {entries.map((e) => (
        <li key={e.path} className="flex min-w-0 items-center gap-2" style={{ paddingLeft: e.depth * 16 }}>
          <span className="w-6 shrink-0 text-center font-mono text-caption2 text-tertiary" title={e.type}>
            {TYPE_MARK[e.type] ?? '?'}
          </span>
          <MapPill path={e.path} mapping={mapping} className="shrink-0">
            {e.key}
          </MapPill>
          {e.value !== undefined && e.type !== 'object' && e.type !== 'array' && (
            <span className="min-w-0 truncate font-mono text-caption text-tertiary" title={cellText(e.value)}>
              {e.value === null ? 'null' : cellText(e.value) || '(empty)'}
            </span>
          )}
          {e.type === 'array' && Array.isArray(e.value) && <span className="shrink-0 text-caption2 text-tertiary">{e.value.length} items</span>}
        </li>
      ))}
    </ul>
  )
}

function TableView({ value, mapping }: { value: JsonValue | undefined; mapping?: (path: string) => string }) {
  const { columns, rows } = tableOf(value)
  const isList = Array.isArray(value)
  const shown = rows.slice(0, 100)
  const items = Array.isArray(value) ? value : [value]
  const objectRows = items.some((r) => r !== null && r !== undefined && typeof r === 'object' && !Array.isArray(r))
  const pathOf = (row: number, col: string) => (objectRows ? (isList ? `[${row}].${col}` : col) : isList ? `[${row}]` : '')
  return (
    <div className="overflow-auto rounded-md border border-stroke-light">
      <table className="w-full border-collapse text-caption">
        <thead className="sticky top-0 bg-surface-secondary">
          <tr>
            {columns.map((c) => (
              <th key={c} scope="col" className="border-b border-stroke-light px-2 py-1.5 text-left font-medium">
                <MapPill path={isList ? `[0].${c}` : c} mapping={objectRows ? mapping : undefined}>
                  {c}
                </MapPill>
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {shown.map((r, i) => (
            <tr key={i} className="border-b border-stroke-light last:border-0">
              {r.map((cell, j) => {
                const text = cellText(cell)
                const path = pathOf(i, columns[j])
                return (
                  <td key={j} className="max-w-[240px] truncate px-2 py-1.5 align-top font-mono text-secondary" title={text}>
                    {mapping && path ? (
                      <MapPill path={path} mapping={mapping} className="border-transparent bg-transparent px-0 text-secondary">
                        {text || ' '}
                      </MapPill>
                    ) : (
                      text
                    )}
                  </td>
                )
              })}
            </tr>
          ))}
        </tbody>
      </table>
      {rows.length > shown.length && <p className="px-2 py-1.5 text-caption2 text-tertiary">{rows.length - shown.length} more rows</p>}
    </div>
  )
}

function jsonScalar(v: JsonValue): ReactNode {
  if (v === null) return <span className="text-tertiary">null</span>
  if (typeof v === 'string') return <span className="text-primary">{JSON.stringify(v)}</span>
  return <span className="text-accent">{String(v)}</span>
}

function JsonLines({ value, path, depth, mapping, keyLabel, last }: { value: JsonValue; path: string; depth: number; mapping?: (path: string) => string; keyLabel?: string; last: boolean }) {
  const indent = { paddingLeft: depth * 14 }
  const key =
    keyLabel !== undefined ? (
      <>
        <MapPill path={path} mapping={mapping} className="border-transparent bg-transparent px-0 text-secondary">
          {JSON.stringify(keyLabel)}
        </MapPill>
        <span className="text-tertiary">: </span>
      </>
    ) : null
  const comma = last ? '' : ','
  if (value === null || typeof value !== 'object') {
    return (
      <div style={indent} className="whitespace-pre-wrap break-all">
        {key}
        {jsonScalar(value)}
        <span className="text-tertiary">{comma}</span>
      </div>
    )
  }
  const entries: [string, JsonValue][] = Array.isArray(value) ? value.map((v, i) => [String(i), v]) : Object.entries(value).filter((e): e is [string, JsonValue] => e[1] !== undefined)
  const [open, close] = Array.isArray(value) ? ['[', ']'] : ['{', '}']
  if (entries.length === 0) {
    return (
      <div style={indent}>
        {key}
        <span className="text-tertiary">
          {open}
          {close}
          {comma}
        </span>
      </div>
    )
  }
  return (
    <>
      <div style={indent}>
        {key}
        <span className="text-tertiary">{open}</span>
      </div>
      {entries.map(([k, v], i) => (
        <JsonLines
          key={k}
          value={v}
          path={Array.isArray(value) ? `${path}[${k}]` : path ? `${path}.${k}` : k}
          depth={depth + 1}
          mapping={mapping}
          keyLabel={Array.isArray(value) ? undefined : k}
          last={i === entries.length - 1}
        />
      ))}
      <div style={indent} className="text-tertiary">
        {close}
        {comma}
      </div>
    </>
  )
}

export function DataView({ value, schema, mode, mapping, label }: DataViewProps) {
  const entries = useMemo(() => (value !== undefined ? flattenPaths(value) : schemaPaths(schema)), [value, schema])
  if (value === undefined && entries.length === 0) return null
  return (
    <div aria-label={label} className="min-w-0 text-caption">
      {mode === 'schema' &&
        (entries.length > 0 ? (
          <SchemaView entries={entries} mapping={mapping} />
        ) : (
          <p className="py-2 font-mono text-caption text-secondary">{value === null || value === undefined ? 'null' : cellText(value)}</p>
        ))}
      {mode === 'table' && (value !== undefined ? <TableView value={value} mapping={mapping} /> : <SchemaView entries={entries} mapping={mapping} />)}
      {mode === 'json' && (
        <div className="overflow-x-auto py-1 font-mono text-caption leading-5">
          {value !== undefined ? <JsonLines value={value} path="" depth={0} mapping={mapping} last /> : <SchemaView entries={entries} mapping={mapping} />}
        </div>
      )}
    </div>
  )
}
