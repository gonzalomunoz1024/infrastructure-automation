import { afterEach, describe, expect, it, vi } from 'vitest'
import type { WorkflowDocument } from '@iaf/workflow-checks'
import { DesignerStore, isAutoLayout } from './store'
import { API_TRIGGER_ID, findStepNode, stepIds } from './graph'
import { sampleDocuments } from './graph.test'
import { EMPTY_REGISTRY, loadRegistry, setRegistryForTests } from './registry'

const vmRestart = (): WorkflowDocument => sampleDocuments().find((s) => s.file.endsWith('vm-restart.yaml'))!.doc

const minimal = (steps: unknown[]): WorkflowDocument =>
  ({ apiVersion: 'iaf.platform/v1', kind: 'Workflow', metadata: { name: 'x', noun: 'thing', verb: 'poke', owner: 'team', version: '1.0.0' }, workflow: { do: steps } }) as WorkflowDocument

const store = (doc: WorkflowDocument = vmRestart()) => new DesignerStore({ name: doc.metadata?.name ?? null, doc, registry: null })

describe('DesignerStore', () => {
  it('builds the graph and is clean until edited', () => {
    const s = store()
    const st = s.getState()
    expect(st.dirty).toBe(false)
    expect(stepIds(st.graph)).toEqual(['checkVmExists', 'noBackupRunning', 'approval', 'restart', 'validate', 'notifyDone', 'notifyOwner'])
    expect(st.canUndo).toBe(false)
  })

  it('adds a step, opens it, and undoes and redoes', () => {
    const s = store()
    const id = s.addStep('wait', {}, { kind: 'after', step: 'approval', handle: 'main' })
    expect(id).toBe('wait')
    let st = s.getState()
    expect(stepIds(st.graph).slice(0, 5)).toEqual(['checkVmExists', 'noBackupRunning', 'approval', 'wait', 'restart'])
    expect(st.ndv).toBe('step:wait')
    expect(st.dirty).toBe(true)
    expect(st.canUndo).toBe(true)
    s.undo()
    st = s.getState()
    expect(stepIds(st.graph)).not.toContain('wait')
    expect(st.dirty).toBe(false)
    expect(st.canRedo).toBe(true)
    s.redo()
    expect(stepIds(s.getState().graph)).toContain('wait')
  })

  it('keeps at most 100 undo steps and folds coalesced edits', () => {
    const s = store(minimal([{ a: { notify: { channel: '#x', message: '' } } }]))
    for (let i = 0; i < 120; i++) s.updateStep('a', (b) => void (b.notify!.message = `m${i}`))
    let n = 0
    while (s.getState().canUndo && n < 500) {
      s.undo()
      n++
    }
    expect(n).toBe(100)
    const t = store(minimal([{ a: { notify: { channel: '#x', message: '' } } }]))
    for (const ch of 'hello') t.updateStep('a', (b) => void (b.notify!.message = (b.notify!.message ?? '') + ch), 'message:a')
    expect(t.getState().doc.workflow?.do?.[0]?.a?.notify?.message).toBe('hello')
    t.undo()
    expect(t.getState().doc.workflow?.do?.[0]?.a?.notify?.message).toBe('')
    expect(t.getState().canUndo).toBe(false)
  })

  it('reports live problems from the checks', () => {
    const s = store(minimal([{ a: { call: 'provider', with: { provider: 'vsphere', action: 'vm.get', input: {} } } }]))
    s.updateStep('a', (b) => {
      b.with = { action: 'vm.get', input: {} }
    })
    const problems = s.checkNow()
    expect(problems.some((p) => p.code === 'step.missingProvider' && p.step === 'a')).toBe(true)
  })

  it('writes every position on the first move of an auto-laid-out flow, and on tidy up', () => {
    const s = store(minimal([{ a: { wait: 'PT1M' } }, { b: { wait: 'PT1M' } }]))
    expect(isAutoLayout(s.getState().doc)).toBe(true)
    s.moveNodes(new Map([['step:b', { x: 900, y: 300 }]]))
    const doc = s.getState().doc
    expect(doc.workflow?.do?.[1]?.b?.position).toEqual({ x: 900, y: 300 })
    expect(doc.workflow?.do?.[0]?.a?.position).toBeDefined()
    expect(isAutoLayout(doc)).toBe(false)
    s.tidyUp()
    const tidied = s.getState().doc
    expect(tidied.workflow?.do?.[1]?.b?.position).toEqual({ x: 560, y: 60 })
    const trigger = s.getState().graph.nodes.find((n) => n.id === API_TRIGGER_ID)
    expect(trigger?.position).toEqual({ x: 60, y: 60 })
  })

  it('places an inserted step after its source on a positioned canvas', () => {
    const s = store(minimal([{ a: { wait: 'PT1M', position: { x: 300, y: 60 } } }]))
    s.addStep('notify', {}, { kind: 'after', step: 'a', handle: 'main' })
    expect(s.getState().doc.workflow?.do?.[1]?.notify?.position).toEqual({ x: 550, y: 60 })
  })

  it('connects switch outputs and deletes nodes of every kind', () => {
    const s = store(minimal([{ s: { switch: [{ case: { when: '{{ $input.a }}', do: [] } }] } }, { b: { wait: 'PT1M', compensate: [{ undoB: { wait: 'PT1S' } }] } }]))
    expect(s.connect('step:s', 'case:0', 'step:b')).toBeNull()
    expect(s.getState().doc.workflow?.do?.[0]?.s?.switch?.[0]).toEqual({ case: { when: '{{ $input.a }}', then: 'b' } })
    s.deleteNodes(['undo:b'])
    expect(s.getState().doc.workflow?.do?.[1]?.b?.compensate).toBeUndefined()
    s.deleteNodes(['step:b'])
    expect(stepIds(s.getState().graph)).toEqual(['s'])
    expect(s.getState().doc.workflow?.do?.[0]?.s?.switch?.[0]?.case?.then).toBe('end')
  })

  it('keeps steps after a removed main connection, at the end of the flow', () => {
    const s = store(minimal([{ a: { wait: 'PT1M' } }, { b: { wait: 'PT1M' } }, { c: { wait: 'PT1M' } }]))
    const edge = s.getState().graph.edges.find((e) => e.source === 'step:a' && e.target === 'step:b')!
    expect(s.deleteEdge(edge.id)).toMatch(/run at the end/)
    expect(stepIds(s.getState().graph)).toEqual(['a', 'b', 'c'])
    expect(s.connect('step:a', 'main', 'step:c')).toMatch(/without a connection/)
    expect(stepIds(s.getState().graph)).toEqual(['a', 'c', 'b'])
  })

  it('renames a step and its references, duplicates and pastes', () => {
    const s = store(vmRestart())
    expect(s.renameStepId('validate', 'waitForPower')).toBe(true)
    expect(s.getState().doc.workflow?.outputs?.powerState).toBe("{{ $('waitForPower').json.powerState }}")
    s.setStepName('checkVmExists', 'Lookup VM')
    expect(s.getState().doc.workflow?.do?.[0]?.checkVmExists?.name).toBe('Lookup VM')
    const copies = s.duplicateNodes(['step:approval'])
    expect(copies).toEqual(['approvalCopy'])
    expect(findStepNode(s.getState().graph, 'approvalCopy')).toBeDefined()
    const clip = s.copyNodes(['step:restart'])
    expect(clip).toHaveLength(1)
    const pasted = s.paste(clip, null)
    expect(pasted).toEqual(['restartCopy'])
    expect(stepIds(s.getState().graph).at(-2)).toBe('restartCopy')
  })

  it('applies fixes and opens the creator for connect fixes', () => {
    const s = store(minimal([{ a: { notify: { channel: '#x', message: '{{ $json.name }}' } } }]))
    const connect = s.checkNow().find((p) => p.fix?.kind === 'connect')!
    expect(s.applyFix(connect)).toBe(true)
    expect(s.getState().creator).toEqual({ point: { kind: 'before', step: 'a' }, query: '' })
    const t = store(minimal([{ wipe: { call: 'http', with: { method: 'DELETE', url: 'https://x/1' } } }]))
    const approval = t.checkNow().find((p) => p.fix?.kind === 'insert-approval')!
    expect(t.applyFix(approval)).toBe(true)
    expect(stepIds(t.getState().graph)).toEqual(['approveWipe', 'wipe'])
  })

  it('drops pins of deleted steps and merges known outputs', () => {
    const s = store(minimal([{ a: { wait: 'PT1M' } }, { b: { wait: 'PT1M' } }]))
    s.togglePin('a', { ok: true })
    expect(s.knownOutputs()).toEqual({ a: { ok: true } })
    s.deleteNodes(['step:a'])
    expect(s.getState().pins).toEqual({})
    expect(s.knownOutputs()).toEqual({})
  })
})

/*
 * A credential stored from a step's credential field (the dialog hands back its reference, name
 * and type): the registry lists it at once, outside the document and its undo history.
 */
describe('a credential stored from a credential field', () => {
  const VSPHERE = { name: 'vsphere/token', type: 'token' }
  const TOKEN = { name: 'example/api-token', type: 'token' }

  afterEach(() => setRegistryForTests(null))

  it('joins the registry at once, in place of one of the same name, and leaves the document alone', () => {
    const s = new DesignerStore({ name: 'x', doc: minimal([]), registry: { ...EMPTY_REGISTRY, credentials: [VSPHERE, { name: 'example/api-token', type: 'basic' }] } })
    s.addCredential(TOKEN)
    expect(s.getState().registry?.credentials).toEqual([VSPHERE, TOKEN])
    expect(s.getState().canUndo).toBe(false)
    expect(s.getState().dirty).toBe(false)
  })

  it('reaches a registry still loading, and the next canvas of the session lists it', async () => {
    setRegistryForTests({ ...EMPTY_REGISTRY, credentials: [VSPHERE] })
    const s = new DesignerStore({ name: 'x', doc: minimal([]) })
    expect(s.getState().registryLoading).toBe(true)
    s.addCredential(TOKEN)
    await vi.waitFor(() => expect(s.getState().registry?.credentials).toEqual([VSPHERE, TOKEN]))
    expect((await loadRegistry()).credentials).toEqual([VSPHERE, TOKEN])
  })
})
