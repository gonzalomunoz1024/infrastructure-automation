import { describe, expect, it } from 'vitest'
import type { WorkflowDocument } from '@iaf/workflow-checks'
import { contextAt, mappingExpression, previewExpression, sampleInput, templateSegments } from './expressions'
import { EMPTY_REGISTRY, type Registry } from './registry'
import { sampleDocuments } from './graph.test'

const vmRestart = (): WorkflowDocument => sampleDocuments().find((s) => s.file.endsWith('vm-restart.yaml'))!.doc

const registry: Registry = {
  ...EMPTY_REGISTRY,
  providers: [
    {
      name: 'vsphere',
      title: 'vSphere',
      type: 'openapi',
      actions: [
        { id: 'vm.get', title: 'Get VM', effect: 'read', retry: 'safe', sample: { id: '{{ $input.id }}', name: 'web-01', powerState: 'poweredOn' } },
        { id: 'vm.waitForPowerState', title: 'Wait', effect: 'read', retry: 'safe', output: { type: 'object', properties: { powerState: { type: 'string' } } } },
      ],
    },
    { name: 'backup', title: 'Backup', type: 'http', actions: [{ id: 'jobs.list', title: 'List', effect: 'read', retry: 'safe', sample: { items: [] } }] },
  ],
}

describe('previewExpression', () => {
  it('returns a plain string as is', () => {
    expect(previewExpression('hello', {})).toEqual({ ok: true, value: 'hello', text: 'hello' })
  })
  it('evaluates templates against the context', () => {
    const p = previewExpression('Restart {{ $json.name }} ({{ $input.environment }})', { json: { name: 'web-01' }, input: { environment: 'prod' } })
    expect(p).toEqual({ ok: true, value: 'Restart web-01 (prod)', text: 'Restart web-01 (prod)' })
  })
  it('reports syntax errors instead of throwing', () => {
    const p = previewExpression('{{ $json. }}', { json: {} })
    expect(p.ok).toBe(false)
  })
  it('reports unknown steps', () => {
    const p = previewExpression("{{ $('Nope').json.x }}", { steps: {} })
    expect(p.ok).toBe(false)
    if (!p.ok) expect(p.error.length).toBeGreaterThan(0)
  })
  it('shortens long objects in the preview text', () => {
    const p = previewExpression('{{ $json }}', { json: { list: Array.from({ length: 50 }, (_, i) => `item-${i}`) } })
    expect(p.ok).toBe(true)
    if (p.ok) expect(p.text.length).toBeLessThanOrEqual(160)
  })
})

describe('mappingExpression', () => {
  it('writes the n8n forms', () => {
    expect(mappingExpression({ kind: 'input' }, 'vmId')).toBe('{{ $input.vmId }}')
    expect(mappingExpression({ kind: 'previous' }, 'items[0].id')).toBe('{{ $json.items[0].id }}')
    expect(mappingExpression({ kind: 'step', name: 'Check VM exists' }, 'name')).toBe("{{ $('Check VM exists').json.name }}")
    expect(mappingExpression({ kind: 'alias', alias: 'vm' }, '')).toBe('{{ $vm }}')
    expect(mappingExpression({ kind: 'previous' }, '[0]')).toBe('{{ $json[0] }}')
  })
})

describe('templateSegments', () => {
  it('splits text and expressions', () => {
    expect(templateSegments('a {{ $json.x }} b')).toEqual([
      { text: 'a ', expression: false },
      { text: '{{ $json.x }}', expression: true },
      { text: ' b', expression: false },
    ])
    expect(templateSegments('{{ unclosed')).toEqual([{ text: '{{ unclosed', expression: false }])
  })
})

describe('sampleInput', () => {
  it('fills the input schema with defaults, enums and resource ids', () => {
    expect(sampleInput(vmRestart())).toEqual({ vmId: 'vm-1', reason: 'reason', environment: 'dev' })
  })
})

describe('contextAt', () => {
  it('builds the context a step sees from samples and aliases', () => {
    const doc = vmRestart()
    const at = contextAt(doc, 'approval', {}, registry)
    expect(at.previous?.id).toBe('noBackupRunning')
    expect(at.ctx.aliases).toMatchObject({ vm: { id: 'vm-1', name: 'web-01', powerState: 'poweredOn' } })
    expect(at.ctx.json).toEqual({ items: [] })
    const p = previewExpression('Restart {{ $vm.name }} ({{ $vm.powerState }}) in {{ $input.environment }}', at.ctx)
    expect(p).toMatchObject({ ok: true, text: 'Restart web-01 (poweredOn) in dev' })
  })
  it('prefers known outputs (a test run) over samples', () => {
    const doc = vmRestart()
    const at = contextAt(doc, 'noBackupRunning', { checkVmExists: { name: 'db-07', powerState: 'poweredOff' } }, registry)
    expect(at.ctx.json).toEqual({ name: 'db-07', powerState: 'poweredOff' })
    expect(at.ctx.steps?.['Check VM exists']).toEqual({ name: 'db-07', powerState: 'poweredOff' })
  })
  it('exposes $error inside the catch flow and $json for the first step is null', () => {
    const doc = vmRestart()
    expect(contextAt(doc, 'checkVmExists', {}, registry).ctx.json).toBeNull()
    const inCatch = contextAt(doc, 'notifyOwner', {}, registry)
    expect(inCatch.inCatch).toBe(true)
    expect(inCatch.ctx.error).toMatchObject({ message: 'Something failed' })
  })
  it('gives the workflow outputs the context after the last step', () => {
    const doc = vmRestart()
    const at = contextAt(doc, null, {}, registry)
    expect(at.previous?.id).toBe('notifyDone')
    const p = previewExpression("{{ $('validate').json.powerState }}", at.ctx)
    expect(p).toMatchObject({ ok: true, text: 'powerState' })
  })
})
