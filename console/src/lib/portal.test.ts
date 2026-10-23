import { describe, expect, it } from 'vitest'
import { CONSOLE_API_DOCUMENT } from '../mock/cli'
import { apiGroupsOf, isSafeLink, exampleInput, exampleTags, exampleValue, readShortcuts, toggleShortcut, writeShortcuts } from './portal'

/* The developer portal's workings: the API by tag from its document, sample inputs from a workflow's schema, and the shortcuts. */

describe('the console API by tag', () => {
  it('groups every operation under its first tag, in the document’s tag order', () => {
    const groups = apiGroupsOf(CONSOLE_API_DOCUMENT)
    expect(groups[0].tag).toBe('Session')
    const device = groups.find((g) => g.tag === 'Device sign-in')!
    expect(device.operations.map((o) => `${o.method} ${o.path}`)).toContain('POST /console/v1/device-grants/{userCode}:approve')
    expect(apiGroupsOf({ paths: { '/x': { get: { summary: 'untagged' } } } })).toEqual([{ tag: 'Other', operations: [{ method: 'GET', path: '/x', summary: 'untagged' }] }])
  })
})

describe('sample inputs from a schema', () => {
  const schema = {
    type: 'object',
    required: ['vmId', 'name', 'size'],
    properties: {
      vmId: { type: 'string', 'x-resource': { type: 'vm' } },
      name: { type: 'string' },
      size: { type: 'string', enum: ['s', 'm'] },
      reason: { type: 'string', examples: ['stuck after a kernel update'] },
      environment: { type: 'string', default: 'dev' },
      note: { type: 'string' },
      password: { type: 'string', 'x-console': { sensitive: true }, default: 'x' },
    },
  }

  it('takes an example, the default or the first choice, leaves out the subject, secrets and bare optional fields', () => {
    expect(exampleInput(schema, 'vmId')).toEqual({ name: '<name>', size: 's', reason: 'stuck after a kernel update', environment: 'dev' })
    expect(exampleValue('image', { type: 'string', 'x-resource': { type: 'image' } })).toBe('<wfrn of a image>')
    expect(exampleValue('count', { type: 'integer', minimum: 2 })).toBe(2)
  })

  it('names the required tags from the policy, copying a field where it says so', () => {
    expect(
      exampleTags(
        [
          { key: 'environment', title: 'Environment', defaultFrom: 'input.environment' },
          { key: 'data-class', title: 'Data class', values: ['public', 'internal'] },
          { key: 'cost-centre', title: 'Cost centre', default: 'cc-1042' },
          { key: 'owner', title: 'Owner' },
        ],
        { environment: 'dev' },
      ),
    ).toEqual({ environment: 'dev', 'data-class': 'public', 'cost-centre': 'cc-1042', owner: '<owner>' })
  })
})

describe('shortcuts', () => {
  it('toggles and keeps them per person', () => {
    const store = new Map<string, string>()
    const storage = { getItem: (k: string) => store.get(k) ?? null, setItem: (k: string, v: string) => void store.set(k, v) }
    writeShortcuts('dana', toggleShortcut(toggleShortcut([], 'drift'), 'cost'), storage)
    expect(readShortcuts('dana', storage)).toEqual(['drift', 'cost'])
    expect(readShortcuts('mia', storage)).toEqual([])
    expect(toggleShortcut(['drift', 'cost'], 'drift')).toEqual(['cost'])
  })
})

describe('links from data', () => {
  it('draws a console route or an https URL, nothing else', () => {
    expect(isSafeLink('/operations/drift')).toBe(true)
    expect(isSafeLink('https://portal.example.test/x')).toBe(true)
    expect(isSafeLink('//evil.example')).toBe(false)
    expect(isSafeLink('/\\evil.example')).toBe(false)
    expect(isSafeLink('javascript:alert(1)')).toBe(false)
    expect(isSafeLink('http://plain.example')).toBe(false)
  })
})
