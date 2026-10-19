import { describe, expect, it } from 'vitest'
import { parse } from 'yaml'
import { emitYaml } from '../../components/yaml-emit'
import { canEditAction, errorClassesText, errorRowsOf, errorsWrittenAs, flowValue, withActionFields, withErrors, withOverride } from './providerFile'

/*
 * The node detail view edits an action of a provider file that is a file (an edit's published
 * file, a draft that took a try's or Devin's) by lines, since the browser parses no YAML: the
 * key it sets is written on one line, everything else stays as it was, and a file it cannot edit
 * that way is left alone (null).
 */

const VSPHERE = Object.values(import.meta.glob('../../../../providers/team-compute/vsphere.yaml', { query: '?raw', import: 'default', eager: true }) as Record<string, string>)[0] ?? ''

type Doc = { spec: { actions: Record<string, unknown>[]; overrides?: Record<string, { response: unknown }>; errors?: unknown[] } }
const action = (yaml: string, id: string) => (parse(yaml) as Doc).spec.actions.find((a) => a.id === id)

describe('an action of a provider file, edited by lines', () => {
  it('replaces a key it has, adds one it has not after the keys before it, and removes one', () => {
    const out = withActionFields(VSPHERE, 'vm.get', { effect: 'change', retry: 'unsafe', approval: 'required', plan: 'vm.list', effectDetail: 'Restarts it, really' })
    expect(out).not.toBeNull()
    const a = action(out!, 'vm.get')
    expect(a).toMatchObject({ id: 'vm.get', title: 'Get VM', operation: 'getVm', effect: 'change', retry: 'unsafe', approval: 'required', plan: 'vm.list', effectDetail: 'Restarts it, really' })
    // its schemas and sample are as they were
    expect(a?.input).toEqual(action(VSPHERE, 'vm.get')?.input)
    expect(a?.sample).toEqual(action(VSPHERE, 'vm.get')?.sample)
    // the new keys sit after retry, in the wizard's order
    const lines = out!.split('\n')
    const at = (s: string) => lines.findIndex((l, i) => i > lines.indexOf('    - id: vm.get') && l.trim().startsWith(s))
    expect(at('retry:')).toBeLessThan(at('approval:'))
    expect(at('approval:')).toBeLessThan(at('plan:'))
    expect(at('plan:')).toBeLessThan(at('effectDetail:'))
    expect(at('effectDetail:')).toBeLessThan(at('input:'))
    const back = withActionFields(out!, 'vm.get', { approval: undefined, plan: undefined, effectDetail: undefined })
    expect(action(back!, 'vm.get')).not.toHaveProperty('approval')
    expect(action(back!, 'vm.get')).not.toHaveProperty('plan')
  })

  it('touches only the action it names, and only its own keys', () => {
    const out = withActionFields(VSPHERE, 'vm.create', { retry: 'safe' })!
    const before = parse(VSPHERE) as Doc
    const after = parse(out) as Doc
    expect(after.spec.actions.map((a) => a.id)).toEqual(before.spec.actions.map((a) => a.id))
    after.spec.actions.forEach((a, i) => expect(a).toEqual(a.id === 'vm.create' ? { ...before.spec.actions[i], retry: 'safe' } : before.spec.actions[i]))
    expect(after.spec.errors).toEqual(before.spec.errors)
    expect(out.split('\n').filter((l, i) => l !== VSPHERE.split('\n')[i])).toEqual(['      retry: safe'])
  })

  it('writes an own SLO, a sample and where it was recorded on one line each, replacing a block', () => {
    const out = withActionFields(VSPHERE, 'vm.get', { slo: { successRate: 99.5, p95Ms: 800 }, sample: { id: 'vm-1', name: 'web, 1', tags: ['a', 'b'] }, recorded: { environment: 'dev', at: '2026-10-18T10:00:00Z' } })!
    expect(action(out, 'vm.get')).toMatchObject({ slo: { successRate: 99.5, p95Ms: 800 }, sample: { id: 'vm-1', name: 'web, 1', tags: ['a', 'b'] }, recorded: { environment: 'dev', at: '2026-10-18T10:00:00Z' } })
    expect(out).toContain('      slo: { successRate: 99.5, p95Ms: 800 }')
    expect(out.split('\n').length).toBeLessThan(VSPHERE.split('\n').length)
  })

  it('edits a file the wizard wrote, and a list written at its key’s own indentation', () => {
    const wizard = emitYaml({ apiVersion: 'iaf.platform/v1', kind: 'Provider', metadata: { name: 'backup' }, spec: { type: 'http', actions: [{ id: 'jobs.list', title: 'List', operation: { method: 'GET', path: '/jobs' }, effect: 'read', retry: 'safe' }, { id: 'jobs.delete', effect: 'delete', retry: 'unsafe' }] } })
    const out = withActionFields(wizard, 'jobs.delete', { approval: 'none', effectDetail: 'The job' })!
    expect(action(out, 'jobs.delete')).toEqual({ id: 'jobs.delete', effect: 'delete', retry: 'unsafe', approval: 'none', effectDetail: 'The job' })
    expect(action(out, 'jobs.list')).toEqual({ id: 'jobs.list', title: 'List', operation: { method: 'GET', path: '/jobs' }, effect: 'read', retry: 'safe' })
    const flat = 'kind: Provider\nspec:\n  actions:\n  - id: a\n    effect: read\n  - id: "b"\n    effect: read\n  slo: { successRate: 99, p95Ms: 900 }\n'
    const edited = withActionFields(flat, 'b', { effect: 'change' })!
    expect(parse(edited)).toEqual({ kind: 'Provider', spec: { actions: [{ id: 'a', effect: 'read' }, { id: 'b', effect: 'change' }], slo: { successRate: 99, p95Ms: 900 } } })
  })

  it('leaves alone a file it cannot edit by lines', () => {
    expect(withActionFields(VSPHERE, 'vm.missing', { effect: 'read' })).toBeNull()
    expect(withActionFields('kind: Provider\nspec:\n  actions:\n    - { id: a, effect: read }\n', 'a', { effect: 'change' })).toBeNull()
    expect(withActionFields('kind: Provider\nspec: { actions: [] }\n', 'a', { effect: 'change' })).toBeNull()
    expect(canEditAction(VSPHERE, 'vm.get')).toBe(true)
    expect(canEditAction(VSPHERE, 'vm.missing')).toBe(false)
  })
})

describe('an override of a provider file, edited by lines', () => {
  it('adds the overrides after the actions when the file has none', () => {
    const out = withOverride(VSPHERE, 'vm.get', { type: 'object', properties: { power_state: { type: 'string' } } })!
    const doc = parse(out) as Doc
    expect(doc.spec.overrides).toEqual({ 'vm.get': { response: { type: 'object', properties: { power_state: { type: 'string' } } } } })
    expect(doc.spec.actions).toEqual((parse(VSPHERE) as Doc).spec.actions)
    expect(doc.spec.errors).toEqual((parse(VSPHERE) as Doc).spec.errors)
  })

  it('replaces the action’s own override and keeps the others', () => {
    const once = withOverride(VSPHERE, 'vm.get', { type: 'object' })!
    const twice = withOverride(once, 'vm.list', { type: 'array' })!
    const again = withOverride(twice, 'vm.get', { type: 'object', properties: { id: { type: 'string' } } })!
    expect((parse(again) as Doc).spec.overrides).toEqual({ 'vm.get': { response: { type: 'object', properties: { id: { type: 'string' } } } }, 'vm.list': { response: { type: 'array' } } })
    expect(withOverride('spec:\n  actions: []\n  overrides: {}\n', 'a', { type: 'string' })).toBe('spec:\n  actions: []\n  overrides:\n    a:\n      response: { type: string }\n')
    expect(withOverride('spec:\n  overrides: { a: { response: {} } }\n', 'b', {})).toBeNull()
  })
})

describe('the error classes of a provider file, edited by lines', () => {
  const usual = [
    { match: { status: 404 }, class: 'not_found', retry: false },
    { match: { status: 409 }, class: 'conflict', retry: false },
    { match: { status: 429 }, class: 'rate_limited', retry: true },
    { match: { status: 503 }, class: 'unavailable', retry: true },
  ]

  it('reads the classes the repository’s files write, one per line, and writes them back unchanged', () => {
    const rows = errorRowsOf(VSPHERE)!
    expect(rows.map(({ status, body, class: c, retry }) => ({ status, body, class: c, retry }))).toEqual(usual.map((u) => ({ status: String(u.match.status), body: '', class: u.class, retry: u.retry })))
    expect(withErrors(VSPHERE, rows)).toBe(VSPHERE)
    expect(errorsWrittenAs(VSPHERE, rows)).toBe(true)
    expect(errorsWrittenAs(VSPHERE, rows.slice(1))).toBe(false)
    expect(errorClassesText(VSPHERE).split('\n')[0]).toBe('- { match: { status: 404 }, class: not_found, retry: false }')
  })

  it('adds, changes and removes a class, touching nothing else', () => {
    const rows = errorRowsOf(VSPHERE)!
    const added = withErrors(VSPHERE, [...rows.slice(1), { key: 99, status: '500', body: 'quota, exceeded', class: 'quota', retry: true }])!
    const doc = parse(added) as Doc
    expect(doc.spec.errors).toEqual([...usual.slice(1), { match: { status: 500, body: 'quota, exceeded' }, class: 'quota', retry: true }])
    expect(doc.spec.actions).toEqual((parse(VSPHERE) as Doc).spec.actions)
    expect(added.split('\n').filter((l) => !VSPHERE.includes(l))).toEqual(['    - { match: { status: 500, body: "quota, exceeded" }, class: quota, retry: true }'])
    expect(errorRowsOf(added)?.map((r) => r.class)).toEqual(['conflict', 'rate_limited', 'unavailable', 'quota'])
    // none left: the block goes
    expect((parse(withErrors(VSPHERE, [])!) as Doc).spec).not.toHaveProperty('errors')
  })

  it('starts the block after the actions and overrides of a file that has none', () => {
    const file = 'kind: Provider\nspec:\n  actions:\n    - id: a\n      effect: read\n  overrides:\n    a:\n      response: {}\n  slo: { successRate: 99, p95Ms: 900 }\n'
    expect(errorRowsOf(file)).toEqual([])
    const out = withErrors(file, [{ key: 1, status: '429', body: '', class: 'rate_limited', retry: true }, { key: 2, status: '', body: '', class: '', retry: false }])!
    expect(out).toBe('kind: Provider\nspec:\n  actions:\n    - id: a\n      effect: read\n  overrides:\n    a:\n      response: {}\n  errors:\n    - { match: { status: 429 }, class: rate_limited, retry: true }\n  slo: { successRate: 99, p95Ms: 900 }\n')
    expect(withErrors(file, [])).toBe(file)
    expect(withErrors('spec:\n  actions: []\n  errors: []\n', [{ key: 1, status: '404', body: '', class: 'gone', retry: false }])).toBe('spec:\n  actions: []\n  errors:\n    - { match: { status: 404 }, class: gone, retry: false }\n')
  })

  it('leaves alone classes written another way, which rows would lose', () => {
    const block = 'spec:\n  errors:\n    - match:\n        status: 404\n      class: not_found\n      retry: false\n'
    const described = 'spec:\n  errors:\n    - { match: { status: 404 }, class: not_found, retry: false, description: Gone }\n'
    const commented = 'spec:\n  errors:\n    # the usual one\n    - { match: { status: 404 }, class: not_found, retry: false }\n'
    for (const file of [block, described, commented, 'spec:\n  errors: [{ match: { status: 404 }, class: x, retry: false }]\n']) {
      expect(errorRowsOf(file)).toBeNull()
      expect(withErrors(file, [])).toBeNull()
    }
    expect(errorClassesText(block)).toBe('- match:\n    status: 404\n  class: not_found\n  retry: false')
    expect(errorClassesText('spec:\n  actions: []\n')).toBe('')
  })
})

describe('a value on one line', () => {
  it('reads back as the same value', () => {
    for (const v of [{ successRate: 99.5, p95Ms: 800 }, { a: 'x, y', b: '{{ $input.id }}', c: [1, 'two', true, null], d: {} }, 'yes', '12', 'plain', 'a: b', ['x']]) expect(parse(`k: ${flowValue(v)}`)).toEqual({ k: v })
  })
})
