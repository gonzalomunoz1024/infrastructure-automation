import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { WorkflowDocument } from '@iaf/workflow-checks'
import { blankAction, buildProviderDocument } from '../../components/provider-editor/state'
import { emitYaml } from '../../components/yaml-emit'
import type { ProviderDetail, TryResult } from '../../lib/types'
import { draftFile, draftFromProvider, draftProvider, draftsUsedBy, draftYaml, findProviderDraft, loadProviderDrafts, newProviderDraft, saveProviderDrafts, withDefaultOwner, type ProviderDraft } from './providerDrafts'

/** A localStorage of this test's own: vitest runs these in node, which has none. */
function memoryStorage(): Storage & { data: Map<string, string> } {
  const data = new Map<string, string>()
  return {
    data,
    get length() {
      return data.size
    },
    clear: () => data.clear(),
    getItem: (k) => data.get(k) ?? null,
    key: (i) => [...data.keys()][i] ?? null,
    removeItem: (k) => void data.delete(k),
    setItem: (k, v) => void data.set(k, String(v)),
  }
}

const KEY = 'iaf-designer-provider-drafts:vm-restart'

const YAML = ['apiVersion: iaf.platform/v1', 'kind: Provider', 'metadata:', '  name: vsphere', '  title: VMware vSphere', '  owner: team-compute', '  version: 1.3.0', 'spec:', '  type: openapi', ''].join('\n')

const DETAIL: ProviderDetail = {
  name: 'vsphere',
  title: 'VMware vSphere',
  type: 'openapi',
  owner: 'team-compute',
  version: '1.3.0',
  environments: ['dev'],
  endpoints: { dev: 'https://vsphere-dev.example.com/rest' },
  auth: { type: 'bearer', credential: 'vault:vsphere/token' },
  actionCount: 1,
  health: { status: 'healthy', successRate: 99.9, p50Ms: 120, p95Ms: 400, calls: 10, errorTrend: [], reasons: [] },
  yaml: YAML,
  actions: [{ id: 'vm.get', title: 'Get VM', operation: 'getVm', effect: 'read', retry: 'safe', input: { type: 'object' }, output: { type: 'object' }, overridden: false }],
  daily: [],
  perAction: [],
  changeRequests: [],
}

let storage: ReturnType<typeof memoryStorage>

beforeEach(() => {
  storage = memoryStorage()
  vi.stubGlobal('localStorage', storage)
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('a provider draft', () => {
  it('starts a new provider as the wizard does once its type is picked', () => {
    const d = newProviderDraft('http', { title: 'Backup API', name: 'backup' })
    expect(d).toMatchObject({ origin: 'new', publishedVersion: null, type: 'http', title: 'Backup API', name: 'backup', editing: null, draftYaml: null })
    expect(d.id).toMatch(/^draft_/)
    expect(d.actions.length).toBeGreaterThan(0)
    expect(newProviderDraft().type).toBe('openapi')
    expect(newProviderDraft('ansible').auth.type).toBe('none')
    expect(newProviderDraft().id).not.toBe(newProviderDraft().id)
  })

  it("starts an edit from the published provider: its file at the next patch, and the version it follows", () => {
    const d = draftFromProvider(DETAIL)
    expect(d).toMatchObject({ origin: 'edit', publishedVersion: '1.3.0', version: '1.3.1', editing: 'vsphere', name: 'vsphere' })
    expect(d.auth.credential).toBe('vault:vsphere/token')
    expect(draftYaml(d)).toBe(YAML.replace('  version: 1.3.0\n', '  version: 1.3.1\n'))
  })

  it("its YAML is the wizard's: the file its state writes, or the file it took", () => {
    const d = newProviderDraft('http', { title: 'Backup API', name: 'backup', owner: 'team-data' })
    expect(draftYaml(d)).toBe(emitYaml(buildProviderDocument(d)))
    expect(draftYaml(d)).toContain('name: backup')
    expect(draftYaml({ ...d, draftYaml: 'kind: Provider\n' })).toBe('kind: Provider\n')
  })

  it('is found by the name of its provider', () => {
    const a = newProviderDraft('http', { name: 'backup' })
    const b = draftFromProvider(DETAIL)
    const drafts = { [a.id]: a, [b.id]: b }
    expect(findProviderDraft(drafts, 'vsphere')).toBe(b)
    expect(findProviderDraft(drafts, 'backup')).toBe(a)
    expect(findProviderDraft(drafts, 'dns')).toBeUndefined()
    expect(findProviderDraft(drafts, null)).toBeUndefined()
  })
})

describe('the drafts a workflow keeps in this browser', () => {
  it('come back as they were saved, by id, with new row keys', () => {
    const a = newProviderDraft('http', { title: 'Backup API', name: 'backup', auth: { type: 'bearer', credential: 'vault:backup/token', header: '' } })
    const b = draftFromProvider(DETAIL)
    saveProviderDrafts('vm-restart', { [a.id]: a, [b.id]: b })
    const back = loadProviderDrafts('vm-restart')
    expect(Object.keys(back)).toEqual([a.id, b.id])
    const { environments: _e, actions: _a, errors: _r, ...rest } = a
    expect(back[a.id]).toMatchObject(rest)
    expect(back[a.id].auth.credential).toBe('vault:backup/token')
    expect(back[b.id]).toMatchObject({ origin: 'edit', publishedVersion: '1.3.0', draftYaml: b.draftYaml })
    expect(back[a.id].actions.map((x) => x.id)).toEqual(a.actions.map((x) => x.id))
    // the rows made in this page load count their keys from 1, so a restored row takes a new one
    const keys = [...back[a.id].environments, ...back[a.id].actions, ...back[b.id].environments, ...back[b.id].actions].map((x) => x.key)
    expect(new Set(keys).size).toBe(keys.length)
    expect(keys.some((k) => [...a.environments, ...a.actions].some((x) => x.key === k))).toBe(false)
  })

  it('never keep a secret value: credentials only as vault: references, URLs without a user or password, no live answers', () => {
    const d: ProviderDraft = {
      ...newProviderDraft('openapi', { name: 'backup' }),
      specUrl: 'https://ana:example-password@specs.example.com/backup.json',
      specGit: { repo: 'https://example-token@git.example.com/acme/specs.git', ref: 'main', path: 'backup.yaml', credential: 'example-token' },
      environments: [{ key: 1, name: 'dev', url: 'https://ana:example-password@backup-dev.example.com' }],
      auth: { type: 'bearer', credential: 'example-token', header: '' },
      tries: { 'backup.list': { ok: true } as unknown as TryResult },
    }
    saveProviderDrafts('vm-restart', { [d.id]: d })
    const raw = storage.getItem(KEY) ?? ''
    expect(raw).not.toContain('example-token')
    expect(raw).not.toContain('example-password')
    const back = loadProviderDrafts('vm-restart')[d.id]
    expect(back.auth.credential).toBe('')
    expect(back.specGit.credential).toBe('')
    expect(back.specUrl).toBe('https://specs.example.com/backup.json')
    expect(back.specGit.repo).toBe('https://git.example.com/acme/specs.git')
    expect(back.environments[0].url).toBe('https://backup-dev.example.com')
    expect(back.tries).toEqual({})
  })

  it('scrub what was stored by hand, and leave out what is not a draft', () => {
    const good = newProviderDraft('http', { name: 'backup' })
    storage.setItem(
      KEY,
      JSON.stringify([
        { ...good, auth: { type: 'bearer', credential: 'example-token', header: '' } },
        { id: 'draft_x', origin: 'somewhere' },
        { origin: 'new' },
        { id: 'draft_y', origin: 'new', actions: 'none' },
        'draft',
        null,
      ]),
    )
    const back = loadProviderDrafts('vm-restart')
    expect(Object.keys(back)).toEqual([good.id])
    expect(back[good.id].auth.credential).toBe('')
    // a stored draft from before a field existed takes the field's default
    storage.setItem(KEY, JSON.stringify([{ id: 'draft_old', origin: 'new', publishedVersion: 3, environments: [], actions: [], errors: [], name: 'old' }]))
    expect(loadProviderDrafts('vm-restart').draft_old).toMatchObject({ name: 'old', publishedVersion: null, strategy: 'canary', specGit: { ref: 'main' } })
    storage.setItem(KEY, '{not json')
    expect(loadProviderDrafts('vm-restart')).toEqual({})
    storage.setItem(KEY, JSON.stringify({ draft_a: good }))
    expect(loadProviderDrafts('vm-restart')).toEqual({})
  })

  it('leave nothing behind when there are none, and are per workflow', () => {
    const d = newProviderDraft('http', { name: 'backup' })
    saveProviderDrafts('vm-restart', { [d.id]: d })
    expect(loadProviderDrafts('vm-create')).toEqual({})
    saveProviderDrafts('vm-restart', {})
    expect(storage.getItem(KEY)).toBeNull()
    expect(storage.length).toBe(0)
  })

  it('live for the session when storage is unavailable', () => {
    vi.stubGlobal('localStorage', {
      getItem: () => {
        throw new Error('denied')
      },
      setItem: () => {
        throw new Error('quota')
      },
      removeItem: () => {
        throw new Error('denied')
      },
    })
    const d = newProviderDraft('http', { name: 'backup' })
    expect(() => saveProviderDrafts('vm-restart', { [d.id]: d })).not.toThrow()
    expect(loadProviderDrafts('vm-restart')).toEqual({})
  })
})

/*
 * The registry's view of a draft, the file the designer sends, and which drafts a workflow calls.
 */
describe('a draft as the canvas reads it', () => {
  const http = (patch: Partial<ProviderDraft> = {}): ProviderDraft => ({
    ...newProviderDraft('http', { title: 'Backup API', name: 'backup' }),
    actions: [
      blankAction({ id: 'snapshot.create', title: 'Create snapshot', method: 'POST', path: '/snapshots', effect: 'create', retry: 'unsafe', inputText: '{"type":"object","required":["vm"],"properties":{"vm":{"type":"string"}}}', exampleText: '{"id":"snap-1"}' }),
      blankAction({ id: 'snapshot.delete', title: '', method: 'DELETE', path: '/snapshots/{id}', effect: 'delete', retry: 'unsafe', effectDetail: 'Removes the snapshot' }),
      blankAction({ id: 'snapshot.list', selected: false }),
    ],
    ...patch,
  })

  it('is a provider of the registry: its selected actions with their schemas and samples, the owner, tagged draft', () => {
    const p = draftProvider({ ...http(), samples: { 'snapshot.delete': { deleted: true } }, overrides: { 'snapshot.delete': { type: 'object', properties: { deleted: { type: 'boolean' } } } } }, 'team-compute')
    expect(p).toMatchObject({ name: 'backup', title: 'Backup API', type: 'http', owner: 'team-compute', unpublished: 'draft' })
    expect(p.actions).toEqual([
      { id: 'snapshot.create', title: 'Create snapshot', effect: 'create', retry: 'unsafe', approval: 'none', input: { type: 'object', required: ['vm'], properties: { vm: { type: 'string' } } }, output: null, sample: { id: 'snap-1' } },
      // a delete needs an approval unless it says otherwise, as the backend reads the file; the fixed schema is its output
      { id: 'snapshot.delete', title: 'snapshot.delete', effect: 'delete', retry: 'unsafe', approval: 'required', input: null, output: { type: 'object', properties: { deleted: { type: 'boolean' } } }, sample: { deleted: true } },
    ])
    expect(p.module).toBeUndefined()
    // an owner the draft names stays
    expect(draftProvider({ ...http(), owner: 'team-data' }, 'team-compute').owner).toBe('team-data')
    expect(draftProvider(http()).owner).toBeUndefined()
  })

  it("carries a terraform draft's module, and the actions an import drafted when it took the import's file", () => {
    const source = 'https://example.com/acme/tf-modules//lb'
    const tf = newProviderDraft('terraform', { name: 'lb', terraform: { source, version: '2.3.0' } })
    expect(draftProvider(tf).module).toEqual({ source, version: '2.3.0' })
    expect(draftProvider(tf).actions.map((a) => a.id)).toEqual(['plan', 'apply', 'destroy'])
    const imported: ProviderDraft = {
      ...tf,
      actions: [],
      imported: {
        kind: 'terraform',
        result: {
          source,
          version: '2.3.0',
          notes: [],
          yaml: 'kind: Provider\n',
          variables: [
            { name: 'size', type: 'number', required: false, sensitive: false, secret: false, default: 2, file: 'variables.tf' },
            { name: 'name', type: 'string', required: true, sensitive: false, secret: false, description: 'The balancer', file: 'variables.tf' },
            { name: 'token', type: 'string', required: true, sensitive: true, secret: true, file: 'variables.tf' },
          ],
          outputs: [
            { name: 'address', sensitive: false, file: 'outputs.tf' },
            { name: 'key', sensitive: true, file: 'outputs.tf' },
          ],
        },
      },
    }
    const [plan, apply] = draftProvider(imported).actions
    expect(plan).toMatchObject({ id: 'plan', effect: 'read', retry: 'safe', input: { type: 'object', required: ['name'], properties: { name: { type: 'string', description: 'The balancer' }, size: { type: 'number', default: 2 } } } })
    expect(Object.keys(plan.input?.properties ?? {})).toEqual(['name', 'size'])
    expect(apply).toMatchObject({ id: 'apply', effect: 'create', retry: 'unsafe' })
    expect(Object.keys(apply.output?.properties?.outputs?.properties ?? {})).toEqual(['address'])
    const ansible: ProviderDraft = {
      ...newProviderDraft('ansible', { name: 'patching' }),
      actions: [],
      imported: {
        kind: 'ansible',
        result: {
          repo: 'https://example.com/acme/patching',
          ref: 'main',
          commit: 'abc',
          chosen: ['hosts.patch', 'hosts.retire'],
          requirements: [],
          skipped: [],
          notes: [],
          yaml: 'kind: Provider\n',
          playbooks: [
            { path: 'patch.yml', action: 'hosts.patch', title: 'Patch hosts', effect: 'change', roles: [], vars: { type: 'object' }, sources: {}, secrets: [], notes: [] },
            { path: 'facts.yml', action: 'hosts.facts', title: 'Facts', effect: 'read', roles: [], vars: { type: 'object' }, sources: {}, secrets: [], notes: [] },
            { path: 'retire.yml', action: 'hosts.retire', title: 'Retire hosts', effect: 'delete', roles: [], vars: { type: 'object' }, sources: {}, secrets: [], notes: [] },
          ],
        },
      },
    }
    expect(draftProvider(ansible).actions.map((a) => [a.id, a.retry, a.approval])).toEqual([
      ['hosts.patch', 'unsafe', 'none'],
      ['hosts.retire', 'unsafe', 'required'],
    ])
  })

  it("its file names the workflow's owner when it names none", () => {
    const d = http()
    expect(draftFile(d, 'team-compute')).toContain('owner: team-compute')
    expect(draftFile({ ...d, owner: 'team-data' }, 'team-compute')).toContain('owner: team-data')
    expect(withDefaultOwner(d, ' ')).toBe(d)
    expect(withDefaultOwner({ ...d, owner: 'team-data' }, 'team-compute').owner).toBe('team-data')
  })
})

describe('the drafts a workflow calls', () => {
  const doc = (steps: unknown[], catchSteps: unknown[] = []): WorkflowDocument => ({ apiVersion: 'iaf.platform/v1', kind: 'Workflow', metadata: { name: 'x' }, workflow: { do: steps, ...(catchSteps.length ? { catch: { do: catchSteps } } : {}) } }) as WorkflowDocument

  it('are the ones its steps call, nested ones and the catch flow included, in the order they are first called', () => {
    const backup = newProviderDraft('http', { name: 'backup' })
    const dns = newProviderDraft('openapi', { name: 'dns' })
    const unused = newProviderDraft('http', { name: 'unused' })
    const unnamed = newProviderDraft('http')
    const drafts = { [backup.id]: backup, [dns.id]: dns, [unused.id]: unused, [unnamed.id]: unnamed }
    const steps = [
      { each: { for: { each: 'vm', in: '{{ $input.vms }}', do: [{ record: { call: 'provider', with: { provider: 'dns', action: 'record.create' } } }] } } },
      { snap: { call: 'provider', with: { provider: 'backup', action: 'snapshot.create' } } },
      { again: { call: 'provider', with: { provider: 'dns', action: 'record.get' } } },
      { other: { call: 'provider', with: { provider: 'vsphere', action: 'vm.get' } } },
    ]
    expect(draftsUsedBy(doc(steps), drafts).map((d) => d.name)).toEqual(['dns', 'backup'])
    expect(draftsUsedBy(doc([], [{ undo: { call: 'provider', with: { provider: 'backup', action: 'snapshot.delete' } } }]), drafts).map((d) => d.name)).toEqual(['backup'])
    expect(draftsUsedBy(doc([{ w: { wait: 'PT1M' } }]), drafts)).toEqual([])
  })

  it('include an ansible, terraform or IDP draft its steps run: by repository, module or tile', () => {
    const repo = 'https://example.com/acme/patching'
    const source = 'https://example.com/acme/tf-modules//lb'
    const ansible = newProviderDraft('ansible', { name: 'patching', ansible: { repo, ref: 'main', path: '', commit: '', executionEnvironment: '' } })
    const tf = newProviderDraft('terraform', { name: 'lb', terraform: { source, version: '2.3.0' } })
    const idp = { ...newProviderDraft('idp', { name: 'portal' }), actions: [blankAction({ id: 'namespace.request' })] }
    const drafts = { [ansible.id]: ansible, [tf.id]: tf, [idp.id]: idp }
    const used = (step: unknown) => draftsUsedBy(doc([{ s: step }]), drafts).map((d) => d.name)
    expect(used({ call: 'ansible', with: { playbook: { git: { repo, ref: 'main', path: 'patch.yml' } } } })).toEqual(['patching'])
    expect(used({ call: 'ansible', with: { playbook: 'patching/hosts.patch' } })).toEqual(['patching'])
    // the library playbook written as an object, as the step's parameters edit it
    expect(used({ call: 'ansible', with: { playbook: { library: 'patching/hosts.patch' } } })).toEqual(['patching'])
    expect(used({ call: 'ansible', with: { playbook: { library: 'patchingx/hosts.patch' } } })).toEqual([])
    expect(used({ call: 'ansible', with: { playbook: { git: { repo: 'https://example.com/acme/other', ref: 'main' } } } })).toEqual([])
    expect(used({ call: 'terraform', with: { module: { source, version: '2.3.0' }, action: 'plan' } })).toEqual(['lb'])
    expect(used({ call: 'terraform', with: { module: { source: 'https://example.com/acme/tf-modules//vpc' }, action: 'plan' } })).toEqual([])
    expect(used({ call: 'idp', with: { tile: 'namespace.request' } })).toEqual(['portal'])
    expect(used({ call: 'idp', with: { tile: 'other' } })).toEqual([])
  })
})
