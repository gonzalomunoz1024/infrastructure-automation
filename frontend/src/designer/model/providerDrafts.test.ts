import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { buildProviderDocument } from '../../components/provider-editor/state'
import { emitYaml } from '../../components/yaml-emit'
import type { ProviderDetail, TryResult } from '../../lib/types'
import { draftFromProvider, draftYaml, findProviderDraft, loadProviderDrafts, newProviderDraft, saveProviderDrafts, type ProviderDraft } from './providerDrafts'

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
