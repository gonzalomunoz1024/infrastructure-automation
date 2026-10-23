// @vitest-environment jsdom
import { describe, expect, it } from 'vitest'
import { act, useState } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { PropertyFilterBar, applyFilters, formatToken, matchesToken, parseToken, tokensToQuery, type FilterProperty, type FilterToken } from '../index'

;(globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true

const PROPS: FilterProperty[] = [
  { key: 'name', label: 'Name' },
  { key: 'status', label: 'Status', operators: ['=', '!='], values: ['ready', 'building', 'retired'] },
  { key: 'cpu', label: 'CPU', operators: ['=', '!=', '>', '<', '>=', '<='] },
]

describe('filter tokens', () => {
  it('parses what people type', () => {
    expect(parseToken('status = ready', PROPS)).toEqual({ property: 'status', operator: '=', value: 'ready' })
    expect(parseToken('Name: web', PROPS)).toEqual({ property: 'name', operator: ':', value: 'web' })
    expect(parseToken('cpu >= 4', PROPS)).toEqual({ property: 'cpu', operator: '>=', value: '4' })
    expect(parseToken('web-01', PROPS)).toEqual({ property: null, operator: ':', value: 'web-01' })
    // an operator the property does not take, or a property that does not exist, is free text
    expect(parseToken('status > ready', PROPS)).toEqual({ property: null, operator: ':', value: 'status > ready' })
    expect(parseToken('bogus = x', PROPS)).toEqual({ property: null, operator: ':', value: 'bogus = x' })
    expect(parseToken('   ', PROPS)).toBeNull()
  })

  it('writes a token in words', () => {
    expect(formatToken({ property: 'status', operator: '=', value: 'ready' }, PROPS)).toBe('Status = ready')
    expect(formatToken({ property: null, operator: ':', value: 'web' }, PROPS)).toBe('"web"')
  })

  it('matches values by operator: text, numbers and dates', () => {
    expect(matchesToken('ready', '=', 'Ready')).toBe(true)
    expect(matchesToken('ready', '!=', 'ready')).toBe(false)
    expect(matchesToken('web-01', ':', 'WEB')).toBe(true)
    expect(matchesToken('web-01', '!:', 'db')).toBe(true)
    expect(matchesToken(4, '>=', '4')).toBe(true)
    expect(matchesToken(10, '>', '9')).toBe(true)
    expect(matchesToken('10', '<', '9')).toBe(false)
    expect(matchesToken('2026-01-02', '>', '2026-01-01')).toBe(true)
    expect(matchesToken(['a', 'b'], ':', 'b')).toBe(true)
    expect(matchesToken(undefined, ':', 'x')).toBe(false)
    expect(matchesToken(undefined, '!:', 'x')).toBe(true)
    expect(matchesToken(undefined, '>', '1')).toBe(false)
  })

  it('filters rows with every token, free text over whatever the caller gives it', () => {
    const rows = [
      { name: 'web-01', status: 'ready', cpu: 2 },
      { name: 'web-02', status: 'building', cpu: 8 },
      { name: 'db-01', status: 'ready', cpu: 16 },
    ]
    const read = (row: (typeof rows)[number], property: string | null) => (property === null ? `${row.name} ${row.status}` : row[property as keyof typeof row])
    expect(applyFilters(rows, [{ property: 'status', operator: '=', value: 'ready' }], read).map((r) => r.name)).toEqual(['web-01', 'db-01'])
    expect(applyFilters(rows, [{ property: 'cpu', operator: '>=', value: '8' }], read).map((r) => r.name)).toEqual(['web-02', 'db-01'])
    expect(applyFilters(rows, [{ property: null, operator: ':', value: 'web' }, { property: 'status', operator: '!=', value: 'building' }], read).map((r) => r.name)).toEqual(['web-01'])
    expect(applyFilters(rows, [], read)).toHaveLength(3)
  })

  it('turns tokens into query parameters', () => {
    const tokens: FilterToken[] = [
      { property: 'status', operator: '=', value: 'ready' },
      { property: null, operator: ':', value: 'web' },
      { property: 'cpu', operator: '>=', value: '4' },
      { property: 'status', operator: '=', value: 'building' },
    ]
    expect(tokensToQuery(tokens)).toEqual({ status: 'ready,building', q: 'web', 'cpu[>=]': '4' })
  })
})

function Harness() {
  const [tokens, setTokens] = useState<FilterToken[]>([])
  return (
    <>
      <PropertyFilterBar properties={PROPS} tokens={tokens} onChange={setTokens} />
      <output data-testid="tokens">{JSON.stringify(tokens)}</output>
    </>
  )
}

function mount() {
  const container = document.createElement('div')
  document.body.appendChild(container)
  let root: Root
  act(() => {
    root = createRoot(container)
    root.render(<Harness />)
  })
  const input = container.querySelector<HTMLInputElement>('input[role="combobox"]')!
  const key = (k: string) => act(() => void input.dispatchEvent(new KeyboardEvent('keydown', { key: k, bubbles: true, cancelable: true })))
  const type = (value: string) =>
    act(() => {
      Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')!.set!.call(input, value)
      input.dispatchEvent(new Event('input', { bubbles: true }))
    })
  const options = () => [...container.querySelectorAll('[role="option"]')].map((o) => o.textContent ?? '')
  const tokens = () => JSON.parse(container.querySelector('[data-testid="tokens"]')!.textContent ?? '[]') as FilterToken[]
  return { container, input, key, type, options, tokens, unmount: () => act(() => root.unmount()) }
}

describe('PropertyFilterBar', () => {
  it('walks property, operator and value with the keyboard and commits a token', () => {
    const t = mount()
    act(() => t.input.focus())
    expect(t.input.getAttribute('aria-expanded')).toBe('true')
    expect(t.options()).toEqual(['Nameproperty', 'Statusproperty', 'CPUproperty'])
    t.type('stat')
    expect(t.options()).toEqual(['Statusproperty', 'Search for "stat"free text'])
    t.key('Enter')
    expect(t.input.getAttribute('aria-label')).toBe('Operator for Status')
    expect(t.options()).toEqual(['= equalsoperator', '!= does not equaloperator'])
    t.key('ArrowDown')
    t.key('Enter')
    expect(t.input.getAttribute('aria-label')).toBe('Value for Status !=')
    expect(t.options()).toEqual(['readyvalue', 'buildingvalue', 'retiredvalue'])
    t.type('re')
    expect(t.options()).toEqual(['readyvalue', 'retiredvalue', 'Use "re"'])
    t.key('Enter')
    expect(t.tokens()).toEqual([{ property: 'status', operator: '!=', value: 'ready' }])
    expect(t.container.querySelector('[aria-label="Remove filter Status != ready"]')).not.toBeNull()
    // back at the start, the input is empty and the suggestions are the properties again
    expect(t.input.value).toBe('')
    expect(t.options()[0]).toBe('Nameproperty')
    t.unmount()
  })

  it('takes a typed expression and free text on Enter', () => {
    const t = mount()
    act(() => t.input.focus())
    t.type('cpu >= 4')
    expect(t.options()[0]).toBe('Add filter CPU >= 4filter')
    t.key('Enter')
    expect(t.tokens()).toEqual([{ property: 'cpu', operator: '>=', value: '4' }])
    t.type('web')
    t.key('Escape')
    expect(t.input.getAttribute('aria-expanded')).toBe('false')
    t.key('Enter')
    expect(t.tokens()).toEqual([
      { property: 'cpu', operator: '>=', value: '4' },
      { property: null, operator: ':', value: 'web' },
    ])
    t.unmount()
  })

  it('steps back with Backspace and clears everything with the button', () => {
    const t = mount()
    act(() => t.input.focus())
    t.type('name')
    t.key('Enter')
    expect(t.input.getAttribute('aria-label')).toBe('Operator for Name')
    t.key('Backspace')
    expect(t.input.getAttribute('aria-label')).toBe('Filter')
    t.type('web')
    t.key('Enter')
    t.type('db')
    t.key('Enter')
    expect(t.tokens()).toHaveLength(2)
    t.key('Backspace')
    expect(t.tokens()).toEqual([{ property: null, operator: ':', value: 'web' }])
    act(() => t.container.querySelector<HTMLButtonElement>('[aria-label=\'Remove filter "web"\']')!.click())
    expect(t.tokens()).toEqual([])
    t.type('x')
    t.key('Enter')
    t.type('y')
    t.key('Enter')
    const clear = [...t.container.querySelectorAll('button')].find((b) => b.textContent === 'Clear filters')!
    act(() => clear.click())
    expect(t.tokens()).toEqual([])
    t.unmount()
  })
})
