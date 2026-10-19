import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { WorkflowDocument } from '@iaf/workflow-checks'
import { DesignerStore, isAutoLayout } from './store'
import { API_TRIGGER_ID, findStepNode, stepIds } from './graph'
import { sampleDocuments } from './graph.test'
import { EMPTY_REGISTRY, loadRegistry, setRegistryForTests } from './registry'
import { api } from '../../lib/api'
import type { RegistryProvider } from '../../lib/types'
import { blankAction } from '../../components/provider-editor/state'
import { buildCatalog } from '../creator/catalog'
import { unpublishedTag } from '../canvas/flow'
import { contextAt } from './expressions'
import { newProviderDraft } from './providerDrafts'

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

/*
 * The workflow's provider drafts and the Provider sheet: kept in this browser per workflow, as the
 * stickies are, outside the document, its undo history and the dirty flag.
 */
describe('provider drafts and the Provider sheet', () => {
  const KEY = 'iaf-designer-provider-drafts:vm-restart'
  let data: Map<string, string>

  beforeEach(() => {
    data = new Map()
    vi.stubGlobal('localStorage', { getItem: (k: string) => data.get(k) ?? null, setItem: (k: string, v: string) => void data.set(k, v), removeItem: (k: string) => void data.delete(k) })
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  it('adds, replaces and drops a draft outside undo and the dirty flag, kept per workflow', () => {
    const s = store()
    const d = newProviderDraft('http', { name: 'backup', auth: { type: 'bearer', credential: 'vault:backup/token', header: '' } })
    s.upsertProviderDraft(d)
    s.upsertProviderDraft({ ...d, title: 'Backup API' })
    expect(Object.keys(s.getState().providerDrafts)).toEqual([d.id])
    expect(s.getState().providerDrafts[d.id].title).toBe('Backup API')
    expect(s.getState()).toMatchObject({ dirty: false, canUndo: false })
    expect(JSON.parse(data.get(KEY) ?? '[]')).toHaveLength(1)
    // the next canvas of this workflow has it
    expect(store().getState().providerDrafts[d.id]).toMatchObject({ name: 'backup', title: 'Backup API', auth: { credential: 'vault:backup/token' } })
    s.removeProviderDraft(d.id)
    s.removeProviderDraft('draft_none')
    expect(s.getState().providerDrafts).toEqual({})
    expect(data.has(KEY)).toBe(false)
  })

  it('opens the sheet in place of the node creator and the menu, on the draft of the provider it names', () => {
    const s = store()
    const d = newProviderDraft('openapi', { name: 'vsphere' })
    s.upsertProviderDraft(d)
    s.openCreator()
    s.openMenu({ x: 1, y: 1, target: { kind: 'pane', flowX: 0, flowY: 0 } })
    s.openProviderSheet({ entry: 'step', provider: 'vsphere', target: { kind: 'step', step: 'restart' } })
    expect(s.getState()).toMatchObject({ creator: null, menu: null, providerSheet: { entry: 'step', provider: 'vsphere', draft: d.id, target: { kind: 'step', step: 'restart' } } })
    s.openProviderSheet({ entry: 'node', provider: 'dns' })
    expect(s.getState().providerSheet).toEqual({ entry: 'node', provider: 'dns', draft: null, target: null })
    // dropping the draft it edits closes it
    s.openProviderSheet({ entry: 'node', draft: d.id })
    s.removeProviderDraft(d.id)
    expect(s.getState().providerSheet).toBeNull()
  })

  it("a new provider's sheet takes the first draft saved from it", () => {
    const s = store()
    s.openProviderSheet({ entry: 'openapi', target: { kind: 'insert', point: null } })
    const d = newProviderDraft('openapi', { name: 'backup' })
    s.upsertProviderDraft(d)
    expect(s.getState().providerSheet?.draft).toBe(d.id)
    // a sheet on a published provider takes only that provider's draft
    s.openProviderSheet({ entry: 'node', provider: 'dns' })
    s.upsertProviderDraft(newProviderDraft('openapi', { name: 'other' }))
    expect(s.getState().providerSheet?.draft).toBeNull()
  })

  it('closes with the chosen action as a new step where it was opened for, and opens it', () => {
    const s = store()
    s.openCreator()
    s.openProviderSheet({ entry: 'curl', target: { kind: 'insert', point: { kind: 'after', step: 'approval', handle: 'main' } } })
    const id = s.closeProviderSheet({ type: 'provider', params: { provider: 'backup', action: 'snapshot.create', name: 'Create snapshot' } })
    expect(id).not.toBeNull()
    const st = s.getState()
    expect(st.providerSheet).toBeNull()
    expect(stepIds(st.graph).slice(0, 4)).toEqual(['checkVmExists', 'noBackupRunning', 'approval', id])
    expect(st.ndv).toBe(`step:${id}`)
    const entry = st.doc.workflow?.do?.find((e) => id! in e)
    expect(entry?.[id!]).toMatchObject({ name: 'Create snapshot', call: 'provider', with: { provider: 'backup', action: 'snapshot.create' } })
    s.undo()
    expect(stepIds(s.getState().graph)).not.toContain(id)
  })

  it('closes filling the step it was opened from, which keeps its id and name', () => {
    const s = store(minimal([{ a: { name: 'Back up', call: 'http', with: { method: 'GET', url: 'https://backup.example.com' } } }]))
    s.openProviderSheet({ entry: 'step', target: { kind: 'step', step: 'a' } })
    expect(s.closeProviderSheet({ type: 'provider', params: { provider: 'backup', action: 'snapshot.create', name: 'Create snapshot' } })).toBe('a')
    expect(s.getState().doc.workflow?.do?.[0]?.a).toEqual({ name: 'Back up', call: 'provider', with: { provider: 'backup', action: 'snapshot.create', input: {} } })
    expect(s.getState().canUndo).toBe(true)
  })

  it('closes without a step, or without a target, or on a step gone, and changes nothing', () => {
    const s = store()
    const version = s.getState().version
    s.openProviderSheet({ entry: 'describe', target: { kind: 'insert', point: null } })
    expect(s.closeProviderSheet()).toBeNull()
    s.openProviderSheet({ entry: 'node', provider: 'vsphere' })
    expect(s.closeProviderSheet({ type: 'provider', params: { provider: 'vsphere', action: 'vm.get' } })).toBeNull()
    s.openProviderSheet({ entry: 'step', target: { kind: 'step', step: 'gone' } })
    expect(s.closeProviderSheet({ type: 'provider', params: { provider: 'vsphere', action: 'vm.get' } })).toBeNull()
    expect(s.closeProviderSheet()).toBeNull()
    expect(s.getState()).toMatchObject({ providerSheet: null, version, canUndo: false })
  })

  it("a new workflow's drafts are kept under its name once saved", async () => {
    vi.spyOn(api, 'createWorkflow').mockResolvedValue({ id: 'cmd-1', status: 'accepted' } as Awaited<ReturnType<typeof api.createWorkflow>>)
    const s = new DesignerStore({ name: null, doc: vmRestart(), registry: null, isNew: true })
    const d = newProviderDraft('http', { name: 'backup' })
    s.upsertProviderDraft(d)
    expect(data.size).toBe(0)
    await s.save()
    expect(JSON.parse(data.get(KEY) ?? '[]').map((x: { id: string }) => x.id)).toEqual([d.id])
  })
})

/*
 * A draft on the canvas: the registry the canvas reads lists it as a published provider (tagged
 * draft), its owner defaults to the workflow's, a dry run and Validate send the files of the
 * drafts the workflow calls, and a draft Publish opened stays in review until it merges.
 */
describe('provider drafts on the canvas', () => {
  const VSPHERE: RegistryProvider = { name: 'vsphere', title: 'vSphere', type: 'openapi', actions: [{ id: 'vm.get', title: 'Get VM', effect: 'read', retry: 'safe' }] }
  const withBackupStep = (): WorkflowDocument => ({
    ...minimal([
      { snap: { name: 'Snapshot', call: 'provider', with: { provider: 'backup', action: 'snapshot.create', input: { vm: 'vm-1' } } } },
      { get: { name: 'Get VM', call: 'provider', with: { provider: 'vsphere', action: 'vm.get', input: {} } } },
    ]),
    metadata: { name: 'vm-backup', noun: 'vm', verb: 'backup', owner: 'team-compute', version: '1.0.0' },
  })
  const backup = (patch: Parameters<typeof newProviderDraft>[1] = {}) =>
    newProviderDraft('http', { title: 'Backup API', name: 'backup', actions: [blankAction({ id: 'snapshot.create', title: 'Create snapshot', method: 'POST', path: '/snapshots', effect: 'create', retry: 'unsafe', inputText: '{"type":"object","required":["vm"],"properties":{"vm":{"type":"string"}}}', exampleText: '{"id":"snap-1"}' })], ...patch })
  const onCanvas = (doc = withBackupStep()) => new DesignerStore({ name: doc.metadata?.name ?? null, doc, registry: { ...EMPTY_REGISTRY, providers: [VSPHERE] } })
  const codes = (s: DesignerStore) => s.checkNow().map((p) => p.code)

  beforeEach(() => {
    const data = new Map<string, string>()
    vi.stubGlobal('localStorage', { getItem: (k: string) => data.get(k) ?? null, setItem: (k: string, v: string) => void data.set(k, v), removeItem: (k: string) => void data.delete(k) })
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
    setRegistryForTests(null)
  })

  it('lists a draft as a provider of the registry, tagged draft, under the workflow owner, and the checks know it', () => {
    const s = onCanvas()
    expect(codes(s)).toContain('step.unknownProvider')
    const d = backup()
    s.upsertProviderDraft(d)
    const p = s.getState().registry?.providers.find((x) => x.name === 'backup')
    expect(p).toMatchObject({ title: 'Backup API', owner: 'team-compute', unpublished: 'draft', actions: [{ id: 'snapshot.create', sample: { id: 'snap-1' } }] })
    expect(codes(s)).not.toContain('step.unknownProvider')
    // the draft itself takes the owner too: the evidence its tries leave is kept under it
    expect(s.getState().providerDrafts[d.id].owner).toBe('team-compute')
    s.removeProviderDraft(d.id)
    expect(s.getState().registry?.providers.map((x) => x.name)).toEqual(['vsphere'])
    expect(codes(s)).toContain('step.unknownProvider')
  })

  it('offers the draft in the node creator, tagged draft, and its sample as the expected output', () => {
    const s = onCanvas()
    s.upsertProviderDraft(backup())
    const catalog = buildCatalog(s.getState().registry, s.getState().doc)
    expect(catalog.get('providers')?.sections[0].items.find((i) => i.id === 'provider:backup')?.tags).toEqual([{ label: 'draft', tone: 'accent' }])
    expect(catalog.get('provider:backup')?.title).toBe('Backup API (draft)')
    const action = catalog.get('provider:backup')?.sections[0].items[0]
    expect(action?.tags?.map((t) => t.label)).toEqual(['create', 'draft'])
    expect(action?.action).toEqual({ kind: 'step', type: 'provider', params: { provider: 'backup', action: 'snapshot.create', name: 'Create snapshot' } })
    expect(contextAt(s.getState().doc, 'get', {}, s.getState().registry).outputs.snap).toEqual({ id: 'snap-1' })
  })

  it("offers an Ansible draft's playbooks in the library, tagged, and tags the steps that run one", () => {
    const s = onCanvas()
    const ops = { ...newProviderDraft('ansible', { name: 'ops', title: 'Ops playbooks' }), actions: [blankAction({ id: 'hosts.patch', title: 'Patch hosts', playbook: 'patch.yml', effect: 'change', retry: 'unsafe' })] }
    s.upsertProviderDraft(ops)
    const registry = s.getState().registry
    expect(registry?.playbooks).toEqual([{ name: 'ops/hosts.patch', version: ops.version, path: 'patch.yml', owner: 'team-compute', unpublished: 'draft' }])
    const library = buildCatalog(registry, s.getState().doc).get('ansible')?.sections[0]
    expect(library?.items.map((i) => [i.title, i.tags])).toEqual([['ops/hosts.patch', [{ label: 'draft', tone: 'accent' }]]])
    const providers = registry?.providers ?? []
    expect(unpublishedTag({ type: 'ansible', with: { playbook: 'ops/hosts.patch' } }, providers)).toBe('draft')
    expect(unpublishedTag({ type: 'ansible', with: { playbook: { library: 'ops/hosts.patch' } } }, providers)).toBe('draft')
    expect(unpublishedTag({ type: 'ansible', with: { playbook: { git: { repo: 'https://example.com/ops', path: 'ops/x.yml' } } } }, providers)).toBeUndefined()
    expect(unpublishedTag({ type: 'provider', with: { provider: 'vsphere', action: 'vm.get' } }, providers)).toBeUndefined()
    s.upsertProviderDraft(backup())
    expect(unpublishedTag({ type: 'provider', with: { provider: 'backup', action: 'snapshot.create' } }, s.getState().registry?.providers ?? [])).toBe('draft')
  })

  it("keeps an owner someone set, or cleared, and follows the workflow's owner while the draft names none", () => {
    const s = onCanvas()
    const touched = backup({ name: 'dns', owner: '', ownerTouched: true })
    s.upsertProviderDraft(touched)
    s.upsertProviderDraft(backup({ name: 'storage', owner: 'team-data' }))
    expect(s.getState().providerDrafts[touched.id].owner).toBe('')
    expect(Object.values(s.getState().providerDrafts).map((d) => d.owner)).toEqual(['', 'team-data'])
    // the registry and the file still name the workflow's owner for the one left empty
    expect(s.getState().registry?.providers.find((x) => x.name === 'dns')?.owner).toBe('team-compute')
    s.update((doc) => void (doc.metadata!.owner = 'team-ops'))
    expect(s.getState().registry?.providers.find((x) => x.name === 'dns')?.owner).toBe('team-ops')
  })

  it('sends the files of the drafts the workflow calls with a dry run and Validate, and no others', async () => {
    const dryRun = vi.spyOn(api, 'dryRunWorkflow').mockResolvedValue({ passed: true, path: [], steps: [] })
    const validate = vi.spyOn(api, 'validateWorkflow').mockResolvedValue({ valid: true, problems: [] })
    const s = onCanvas()
    await s.testRun({})
    expect(dryRun.mock.calls[0][2]).toEqual([])
    s.upsertProviderDraft(backup())
    s.upsertProviderDraft(backup({ name: 'unused' }))
    await s.testRun({ vm: 'vm-1' })
    await s.validate()
    const files = dryRun.mock.calls[1][2] ?? []
    expect(files).toHaveLength(1)
    expect(files[0]).toContain('name: backup')
    expect(files[0]).toContain('owner: team-compute')
    expect(validate.mock.calls[0][1]).toEqual(files)
    expect(s.getState().problems).toEqual([])
  })

  it('drops the last Validate answer when a draft is added, edited or removed: it judged other providers', async () => {
    const needsVm = { level: 'error' as const, code: 'step.missingInput', step: 'snap', message: "Action snapshot.create needs 'vm'" }
    const validate = vi.spyOn(api, 'validateWorkflow').mockResolvedValue({ valid: false, problems: [needsVm] })
    const s = onCanvas()
    const d = backup()
    s.upsertProviderDraft(d)
    await s.validate()
    expect(s.checkNow()).toEqual([needsVm])
    // the draft is edited (vm is optional now): the live checks judge it again
    const optional = s.getState().providerDrafts[d.id].actions.map((a) => ({ ...a, inputText: '{"type":"object","properties":{"vm":{"type":"string"}}}' }))
    s.upsertProviderDraft({ ...s.getState().providerDrafts[d.id], actions: optional })
    expect(s.checkNow()).not.toContainEqual(needsVm)
    expect(codes(s)).not.toContain('step.unknownProvider')
    validate.mockResolvedValue({ valid: true, problems: [] })
    await s.validate()
    expect(s.checkNow()).toEqual([])
    s.removeProviderDraft(d.id)
    expect(codes(s)).toContain('step.unknownProvider')
  })

  it("keeps the live checks on Save when the workflow calls a draft: the backend's save knows only published providers", async () => {
    const unknown = { level: 'error' as const, code: 'step.unknownProvider', step: 'snap', field: 'with.provider', message: "Unknown provider 'backup'" }
    const saveDraft = vi.spyOn(api, 'saveDraft').mockResolvedValue({ name: 'vm-backup', version: '1.0.0', problems: [unknown] })
    const s = onCanvas()
    s.upsertProviderDraft(backup())
    s.update((doc) => void (doc.metadata!.title = 'VM backup'))
    const live = s.checkNow()
    expect(live.map((p) => p.code)).not.toContain('step.unknownProvider')
    const saved = await s.save()
    expect(saved.problems).toEqual(live)
    expect(s.getState().problems).toEqual(live)
    // a workflow without drafts (none kept for this one) takes the backend's answer, as before
    const plain = new DesignerStore({ name: 'vm-plain', doc: withBackupStep(), registry: { ...EMPTY_REGISTRY, providers: [VSPHERE] } })
    plain.update((doc) => void (doc.metadata!.title = 'VM backup'))
    expect((await plain.save()).problems).toEqual([unknown])
    expect(plain.getState().problems).toEqual([unknown])
    expect(saveDraft).toHaveBeenCalledTimes(2)
  })

  it('keeps no Validate answer that comes back after the providers changed', async () => {
    let answer: (v: { valid: boolean; problems: [] }) => void = () => undefined
    vi.spyOn(api, 'validateWorkflow').mockReturnValue(new Promise((resolve) => (answer = resolve)))
    const s = onCanvas()
    const d = backup()
    s.upsertProviderDraft(d)
    const pending = s.validate()
    s.removeProviderDraft(d.id)
    answer({ valid: true, problems: [] })
    await pending
    expect(codes(s)).toContain('step.unknownProvider')
  })

  it('keeps a published draft in review until its change request merges, then reads the published provider', async () => {
    const s = onCanvas()
    const d = backup()
    s.upsertProviderDraft(d)
    s.openProviderSheet({ entry: 'publish', draft: d.id })
    expect(s.usedProviderDrafts().map((x) => x.id)).toEqual([d.id])
    s.providerDraftPublished(d.id, 'cr_8')
    let st = s.getState()
    expect(st.providerDrafts).toEqual({})
    expect(st.providerSheet).toBeNull()
    expect(st.registry?.providers.find((x) => x.name === 'backup')?.unpublished).toBe('in review')
    expect(codes(s)).not.toContain('step.unknownProvider')
    expect(s.usedProviderDrafts()).toEqual([])
    expect(s.usedProvidersInReview().map((r) => r.changeRequest)).toEqual(['cr_8'])
    expect(s.providerFiles()).toHaveLength(1)
    expect(s.providerFiles()[0]).toContain('name: backup')
    // merged: the registry loads again and lists the provider as published
    vi.stubGlobal('fetch', async (input: RequestInfo | URL) => Response.json(String(input).endsWith('/registry/providers') ? [VSPHERE, { name: 'backup', title: 'Backup API', type: 'http', actions: [{ id: 'snapshot.create', title: 'Create snapshot', effect: 'create', retry: 'unsafe' }] }] : []))
    await s.providerChangeMerged('cr_8')
    st = s.getState()
    expect(st.providersInReview).toEqual([])
    expect(st.registry?.providers.find((x) => x.name === 'backup')?.unpublished).toBeUndefined()
    expect(s.providerFiles()).toEqual([])
    expect(codes(s)).not.toContain('step.unknownProvider')
  })
})
