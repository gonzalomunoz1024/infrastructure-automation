import { describe, expect, it } from 'vitest'
import { renderToString } from 'react-dom/server'
import fixture from './fixtures/x-console-type.json'
import {
  HIDDEN_VALUE,
  IMMUTABLE_NOTE,
  SchemaForm,
  WFRN_EXAMPLE,
  columnFields,
  formatValue,
  formatValueText,
  groupFields,
  mutableFields,
  searchableFields,
  sensitiveFields,
  validateAgainst,
  xConsoleOf,
  type ResourceType,
  type Schema,
} from '../index'

/*
 * Every x-console keyword of P-06 rendered once, from one fixture: format, column, searchable,
 * sensitive, mutable, group, help and widget.
 */

const text = (html: string) => html.replace(/<!--.*?-->/g, '')
const type = fixture as unknown as ResourceType
const schema: Schema = type.schema
const render = (props: Partial<Parameters<typeof SchemaForm>[0]> = {}) => text(renderToString(<SchemaForm schema={schema} value={{}} onChange={() => {}} idPrefix="x" {...props} />))

describe('x-console keywords', () => {
  it('reads the block, dropping words it does not know', () => {
    expect(xConsoleOf(schema.properties!.memoryGb)).toEqual({ format: 'gibibytes', group: 'Sizing', mutable: true, column: true })
    expect(xConsoleOf(schema.properties!.nickname)).toEqual({})
    expect(xConsoleOf({ type: 'string' })).toEqual({})
  })

  it('lists the columns, the searchable, the sensitive and the mutable fields', () => {
    expect(columnFields(schema)).toEqual(['name', 'cpu', 'memoryGb'])
    expect(searchableFields(schema)).toEqual(['name'])
    expect(sensitiveFields(schema)).toEqual(['passphrase'])
    expect(mutableFields(schema)).toEqual(['cpu', 'memoryGb', 'labels'])
  })

  it('groups fields where their group first appears, keeping the rest in place', () => {
    const groups = groupFields(schema)
    expect(groups[0]).toEqual({ group: null, names: ['name'] })
    expect(groups[1]).toEqual({ group: 'Boot', names: ['image', 'userData'] })
    expect(groups[2]).toEqual({ group: 'Sizing', names: ['cpu', 'memoryGb', 'diskBytes'] })
    expect(groups[3]).toEqual({ group: null, names: ['cpuBurst'] })
  })

  it('renders a fieldset per group, with its legend', () => {
    const html = render()
    expect(html).toContain('data-group="Boot"')
    expect(html).toContain('data-group="Sizing"')
    expect(html.match(/<legend[^>]*>Sizing<\/legend>/)).not.toBeNull()
    expect(html.match(/<fieldset/g)).toHaveLength(2)
    expect(render({ groups: false })).not.toContain('<fieldset')
  })

  it('renders each widget: picker, slider, code and key-value', () => {
    const html = render()
    expect(html).toContain('aria-label="Image"')
    expect(html).toContain('images from the inventory')
    expect(html).toMatch(/<input[^>]*id="x-cpu"[^>]*type="range"/)
    expect(html).toContain('max="64"')
    expect(html).toMatch(/<textarea[^>]*id="x-userData"[^>]*data-widget="code"/)
    expect(html).toContain('data-widget="key-value"')
    expect(html).toContain('No pairs yet.')
    expect(html).toContain('Add a pair')
  })

  it('renders each format: units, a date, a duration, a wfrn and code', () => {
    const html = render()
    expect(html).toContain('data-unit="GiB"')
    expect(html).toContain('data-unit="bytes"')
    expect(html).toContain('data-unit="%"')
    expect(html).toContain('data-unit="EUR"')
    expect(html).toMatch(/<input[^>]*id="x-expiresAt"[^>]*type="date"/)
    expect(html).toContain('data-format="duration"')
    expect(html).toContain('placeholder="30m, 2h, 1d"')
    expect(html).toContain('data-format="wfrn"')
    expect(html).toContain(`placeholder="${WFRN_EXAMPLE}"`)
    expect(html).toMatch(/<textarea[^>]*id="x-bootArgs"[^>]*data-widget="code"/)
  })

  it('masks a sensitive field behind a show button', () => {
    const html = render({ value: { passphrase: 'open sesame' } })
    expect(html).toMatch(/<input[^>]*id="x-passphrase"[^>]*type="password"/)
    expect(html).toContain('aria-label="Show value"')
    expect(html).toContain('data-sensitive="true"')
  })

  it('links the help page by the field', () => {
    const html = render()
    expect(html).toContain('aria-label="Help for Name"')
    expect(html).toContain('href="https://wiki.example.test/vm#name"')
    expect(html).toContain('target="_blank"')
  })

  it('lets an update change only the mutable fields', () => {
    const html = render({ mode: 'update', value: { name: 'web-01', cpu: 4, memoryGb: 8 } })
    expect(html).toMatch(/<input[^>]*id="x-name"[^>]*disabled=""/)
    expect(html).not.toMatch(/<input[^>]*id="x-cpu"[^>]*disabled=""/)
    expect(html).not.toMatch(/<input[^>]*id="x-memoryGb"[^>]*disabled=""/)
    expect(html).toContain(IMMUTABLE_NOTE)
    // a create touches everything
    const create = render({ mode: 'create' })
    expect(create).not.toMatch(/<input[^>]*id="x-name"[^>]*disabled=""/)
    expect(create).not.toContain(IMMUTABLE_NOTE)
  })

  it('checks a wfrn, a duration and a percentage before the server does', () => {
    const codes = (v: Record<string, unknown>) => validateAgainst(schema, { name: 'web', image: 'img-1', ...v }).map((p) => `${p.code}:${p.field}`)
    expect(codes({ parent: 'nope' })).toEqual(['format:parent'])
    expect(codes({ parent: WFRN_EXAMPLE })).toEqual([])
    expect(codes({ backupWindow: 'sometimes' })).toEqual(['format:backupWindow'])
    expect(codes({ backupWindow: '2h30m' })).toEqual([])
    expect(codes({ cpuBurst: 150 })).toEqual(['format:cpuBurst'])
    expect(codes({ cpuBurst: 50 })).toEqual([])
    const message = validateAgainst(schema, { name: 'web', image: 'img-1', parent: 'nope' })[0].message
    expect(message).toContain('Parent must be a wfrn')
  })
})

describe('formatValue', () => {
  it('writes every format in words', () => {
    expect(formatValueText(1536, 'bytes')).toContain('1.5')
    expect(formatValueText(8, 'gibibytes')).toBe('8 GiB')
    expect(formatValueText(90, 'duration')).toContain('1m')
    expect(formatValueText('2h30m', 'duration')).toContain('2h')
    expect(formatValueText(12.5, 'percent')).toMatch(/%$/)
    expect(formatValueText(12.5, { format: 'cost', currency: 'EUR' })).toBe('12.5 EUR')
    expect(formatValueText(1200, 'cost')).toBe('1,200 USD')
    expect(formatValueText('2026-10-01T09:30:00Z', 'date')).not.toBe('not set')
    expect(formatValueText(WFRN_EXAMPLE, 'wfrn')).toBe(WFRN_EXAMPLE)
    expect(formatValueText('--flag', 'code')).toBe('--flag')
    expect(formatValueText('anything', { sensitive: true })).toBe(HIDDEN_VALUE)
    expect(formatValueText(undefined, 'bytes')).toBe('not set')
    expect(formatValueText(true)).toBe('Yes')
    expect(formatValueText(['a', 'b'])).toBe('a, b')
    expect(formatValueText({ a: 1 })).toBe('a: 1')
  })

  it('renders a wfrn with its copy button, a date as <time>, and a secret masked', () => {
    expect(text(renderToString(<>{formatValue(WFRN_EXAMPLE, 'wfrn')}</>))).toContain('Copy wfrn')
    expect(text(renderToString(<>{formatValue('2026-10-01T09:30:00Z', 'date')}</>))).toContain('<time')
    expect(text(renderToString(<>{formatValue('hush', { sensitive: true })}</>))).toContain(HIDDEN_VALUE)
    expect(text(renderToString(<>{formatValue(null, 'cost')}</>))).toContain('not set')
    expect(text(renderToString(<>{formatValue('ls -la', 'code')}</>))).toContain('<code')
  })
})
