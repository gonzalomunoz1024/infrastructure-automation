import { renderToString } from 'react-dom/server'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { WorkflowDocument } from '@iaf/workflow-checks'
import { blankAction, yamlActions } from '../../components/provider-editor/state'
import type { ProviderDetail } from '../../lib/types'
import { sampleDocuments } from '../model/graph.test'
import { findStepNode, stepIds } from '../model/graph'
import { draftFromProvider, newProviderDraft, type ProviderDraft } from '../model/providerDrafts'
import { EMPTY_REGISTRY } from '../model/registry'
import { DesignerContext, DesignerStore } from '../model/store'
import { actionStep, entryType, keepInDraft, leaveSheet, ProviderSheet, sectionIssues, sectionsOf, SheetBody, sheetActions, startDraft } from './ProviderSheet'

/*
 * The Provider sheet: the wizard's steps over the canvas, on a draft of the workflow's that every
 * change is kept in, and the action picked becoming a step where the sheet was opened from.
 */

const KEY = 'iaf-designer-provider-drafts:vm-restart'

const vmRestart = (): WorkflowDocument => sampleDocuments().find((s) => s.file.endsWith('vm-restart.yaml'))!.doc
const store = () => new DesignerStore({ name: 'vm-restart', doc: vmRestart(), registry: EMPTY_REGISTRY })

const text = (html: string) => html.replace(/<!--.*?-->/g, '')

function render(s: DesignerStore): string {
  return text(
    renderToString(
      <DesignerContext.Provider value={s}>
        <ProviderSheet />
      </DesignerContext.Provider>,
    ),
  )
}

/** The value of the input with that id. */
function inputValue(html: string, id: string): string | null {
  const m = new RegExp(`<input[^>]*id="${id}"[^>]*>`).exec(html)
  if (!m) return null
  return /value="([^"]*)"/.exec(m[0])?.[1] ?? ''
}

/** The labels of the rail's sections, the current one marked. */
function rail(html: string): string[] {
  const start = html.indexOf('aria-label="Provider sections"')
  const end = html.indexOf('</nav>', start)
  return [...html.slice(start, end).matchAll(/(<button[^>]*>).*?<span class="flex items-center gap-1.5[^"]*">([^<]+)/g)].map((m) => (m[1].includes('aria-current="step"') ? `[${m[2]}]` : m[2]))
}

const YAML = ['apiVersion: iaf.platform/v1', 'kind: Provider', 'metadata:', '  name: backup', '  title: Backup service', '  owner: team-platform', '  version: 1.3.0', 'spec:', '  type: http', ''].join('\n')

const DETAIL: ProviderDetail = {
  name: 'backup',
  title: 'Backup service',
  type: 'http',
  owner: 'team-platform',
  version: '1.3.0',
  environments: ['dev'],
  endpoints: { dev: 'https://backup-dev.example.com/api' },
  auth: { type: 'header', credential: 'vault:backup/api-key', header: 'X-Api-Key' },
  actionCount: 1,
  health: { status: 'healthy', successRate: 99.9, p50Ms: 120, p95Ms: 400, calls: 10, errorTrend: [], reasons: [] },
  yaml: YAML,
  actions: [{ id: 'jobs.list', title: 'List backup jobs', operation: { method: 'get', path: '/jobs' }, effect: 'read', retry: 'safe', input: { type: 'object' }, output: { type: 'object' }, overridden: false }],
  daily: [],
  perAction: [],
  changeRequests: [],
}

/** An openapi draft whose spec was imported: two operations, both selected. */
function backupDraft(patch: Partial<ProviderDraft> = {}): ProviderDraft {
  return newProviderDraft('openapi', {
    title: 'Backup service',
    name: 'backup',
    owner: 'team-platform',
    actions: [
      blankAction({ id: 'listJobs', title: 'List backup jobs', operation: 'listJobs', effect: 'read' }),
      blankAction({ id: 'createJob', title: 'Start a backup', operation: 'createJob', effect: 'create', retry: 'unsafe' }),
      blankAction({ id: 'deleteJob', operation: 'deleteJob', effect: 'delete', selected: false }),
    ],
    ...patch,
  })
}

let data: Map<string, string>

beforeEach(() => {
  data = new Map()
  vi.stubGlobal('localStorage', { getItem: (k: string) => data.get(k) ?? null, setItem: (k: string, v: string) => void data.set(k, v), removeItem: (k: string) => void data.delete(k) })
})

afterEach(() => {
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

describe('the Provider sheet opened for a new system', () => {
  it('starts empty at Connect with the type of the entry it came from, owned by the workflow team', () => {
    const s = store()
    s.openProviderSheet({ entry: 'sdk', target: { kind: 'insert', point: null } })
    const html = render(s)

    expect(rail(html)).toEqual(['[Connect]', 'Actions', 'Try it', 'Review'])
    expect(html).toContain('New provider')
    expect(html).toMatch(/role="radio" aria-checked="true" aria-label="SDK worker"/)
    expect(inputValue(html, 'prov-title')).toBe('')
    expect(inputValue(html, 'prov-owner')).toBe('team-compute')
    // the worker settings and the authentication are Connect's
    expect(html).toContain('Task queue')
    expect(html).toContain('Authentication')
    // a target: an action can be picked to use, none yet
    expect(html).toContain('aria-label="Action to use"')
    expect(html).toMatch(/<button[^>]*disabled=""[^>]*data-testid="provider-sheet-use"/)
    // nothing is kept until something changes
    expect(s.getState().providerDrafts).toEqual({})
    expect(data.has(KEY)).toBe(false)
  })

  it('maps each entry to the type it starts as', () => {
    expect(entryType('openapi')).toBe('openapi')
    expect(entryType('curl')).toBe('http')
    expect(entryType('ansible')).toBe('ansible')
    expect(entryType('terraform')).toBe('terraform')
    expect(entryType('idp')).toBe('idp')
    expect(entryType('sdk')).toBe('sdk')
    expect(entryType('describe')).toBe('openapi')
    expect(entryType('step')).toBe('openapi')
    expect(startDraft('curl', 'team-compute')).toMatchObject({ type: 'http', owner: 'team-compute', ownerTouched: false, origin: 'new', publishedVersion: null })
  })

  it('opens a repository or a module on its import', () => {
    const s = store()
    s.openProviderSheet({ entry: 'terraform', target: { kind: 'insert', point: null } })
    const terraform = render(s)
    expect(terraform).toContain('aria-label="How to start"')
    expect(terraform).toContain('Import module')
    expect(terraform).toContain('id="imp-owner"')
    expect(inputValue(terraform, 'imp-owner')).toBe('team-compute')
    // started by hand, it reopens by hand
    s.closeProviderSheet()
    const hand = newProviderDraft('terraform', { title: 'Load balancer', name: 'lb', terraform: { source: 'git::https://github.com/acme/tf-modules//lb', version: '' } })
    s.upsertProviderDraft(hand)
    s.openProviderSheet({ entry: 'terraform', draft: hand.id })
    const reopened = render(s)
    expect(reopened).toContain('id="tf-source"')
    expect(reopened).not.toContain('id="imp-owner"')
  })

  it('opens on the review when Publish sends someone to fix a draft', () => {
    const s = store()
    const d = backupDraft()
    s.upsertProviderDraft(d)
    s.openProviderSheet({ entry: 'publish', draft: d.id })
    const html = render(s)
    expect(rail(html)).toEqual(['Connect', 'Actions', 'Try it', '[Review]'])
    expect(html).toContain('Publish provider only')
    // no target: nothing to use
    expect(html).not.toContain('aria-label="Action to use"')
  })
})

describe('the Provider sheet opened on a published provider', () => {
  it('starts from a draft of its next patch: Connect, Try it and Review, the file kept', () => {
    const s = store()
    s.openProviderSheet({ entry: 'node', provider: 'backup', target: { kind: 'step', step: 'restart' } })
    const draft = draftFromProvider(DETAIL)
    const html = text(
      renderToString(
        <DesignerContext.Provider value={s}>
          <SheetBody sheet={s.getState().providerSheet!} draft={draft} saved={false} actions={sheetActions(draft)} chosen={null} onChoose={() => {}} onDropDraft={() => {}} />
        </DesignerContext.Provider>,
      ),
    )
    expect(rail(html)).toEqual(['[Connect]', 'Try it', 'Review'])
    expect(html).toContain('Edit Backup service')
    expect(html).toContain('backup 1.3.0 → 1.3.1')
    // the spec and the type are the file's: Connect shows the endpoints and the auth the calls use
    expect(html).not.toContain('aria-label="Provider type"')
    expect(html).toContain('https://backup-dev.example.com/api')
    expect(sheetActions(draft)).toEqual([{ id: 'jobs.list', title: 'List backup jobs', effect: 'read' }])
  })

  it("opens the workflow's draft of that provider when it has one", () => {
    const s = store()
    const draft = draftFromProvider(DETAIL)
    s.upsertProviderDraft({ ...draft, description: 'Backups of every VM, kept 30 days.' })
    s.openProviderSheet({ entry: 'step', provider: 'backup', target: { kind: 'step', step: 'restart' } })
    expect(s.getState().providerSheet?.draft).toBe(draft.id)
    const html = render(s)
    expect(html).toContain('Edit Backup service')
    expect(html).toContain('aria-label="Discard draft"')
  })
})

describe('a change in the sheet', () => {
  it('is kept in the draft at once, so closing and reopening loses nothing', () => {
    const s = store()
    s.openProviderSheet({ entry: 'openapi', target: { kind: 'insert', point: null } })
    const shown = startDraft('openapi', 'team-compute')
    // two changes made at once (an import, then its commit) both land
    keepInDraft(s, shown, { title: 'Backup service', name: 'backup' })
    keepInDraft(s, shown, { specCommit: 'abc123' })
    const kept = s.getState().providerDrafts[shown.id]
    expect(kept).toMatchObject({ title: 'Backup service', name: 'backup', specCommit: 'abc123', owner: 'team-compute' })
    // the sheet now edits it, outside undo and the dirty flag
    expect(s.getState().providerSheet?.draft).toBe(shown.id)
    expect(s.getState()).toMatchObject({ dirty: false, canUndo: false })
    expect(JSON.parse(data.get(KEY) ?? '[]')).toHaveLength(1)

    s.closeProviderSheet()
    s.openProviderSheet({ entry: 'step', provider: 'backup', target: null })
    const html = render(s)
    expect(inputValue(html, 'prov-title')).toBe('Backup service')
    expect(inputValue(html, 'prov-name')).toBe('backup')
    // and the next canvas of this workflow has it too
    expect(store().getState().providerDrafts[shown.id]).toMatchObject({ title: 'Backup service' })
  })

  it("is judged as the wizard's steps judge it, Connect with the Type step's fields", () => {
    const d = startDraft('openapi', 'team-compute')
    expect(sectionIssues(d, 'connect')).toEqual(expect.arrayContaining(['Give the provider a title.', 'Point at the spec: its URL.']))
    expect(sectionIssues(d, 'actions')).toEqual(['Select at least one action.'])
    expect(sectionIssues(d, 'review')).toEqual([])
    // a file taken instead (Devin's) is judged by the review's checks
    expect(sectionIssues({ ...d, draftYaml: YAML }, 'connect')).toEqual([])
    expect(sectionsOf(d)).toEqual(['connect', 'actions', 'try', 'review'])
  })
})

describe('the action picked in the sheet', () => {
  it('is inserted as a step where the sheet was opened from, on close, and opened', () => {
    const s = store()
    const d = backupDraft()
    s.upsertProviderDraft(d)
    s.openCreator()
    s.openProviderSheet({ entry: 'openapi', draft: d.id, target: { kind: 'insert', point: { kind: 'after', step: 'approval', handle: 'main' } } })
    expect(sheetActions(d).map((a) => a.id)).toEqual(['listJobs', 'createJob'])

    const id = leaveSheet(s, d, { action: sheetActions(d)[1], dropDraft: false })
    expect(id).not.toBeNull()
    const st = s.getState()
    expect(st.providerSheet).toBeNull()
    expect(stepIds(st.graph).slice(2, 4)).toEqual(['approval', id])
    expect(st.ndv).toBe(`step:${id}`)
    expect(findStepNode(st.graph, id!)?.data.body).toMatchObject({ name: 'Start a backup', call: 'provider', with: { provider: 'backup', action: 'createJob' } })
    // the draft stays: the workflow's Publish opens its change request
    expect(st.providerDrafts[d.id]).toBeDefined()
  })

  it('fills the step it was opened from, and nothing is inserted without a pick, a target or a name', () => {
    const s = store()
    const d = backupDraft()
    s.openProviderSheet({ entry: 'step', target: { kind: 'step', step: 'restart' } })
    expect(leaveSheet(s, d, { action: sheetActions(d)[0], dropDraft: false })).toBe('restart')
    expect(findStepNode(s.getState().graph, 'restart')?.data.body).toMatchObject({ call: 'provider', with: { provider: 'backup', action: 'listJobs' } })

    const steps = stepIds(s.getState().graph)
    s.openProviderSheet({ entry: 'openapi', target: { kind: 'insert', point: null } })
    expect(leaveSheet(s, d, { action: null, dropDraft: false })).toBeNull()
    s.openProviderSheet({ entry: 'node', provider: 'backup' })
    expect(leaveSheet(s, d, { action: sheetActions(d)[0], dropDraft: false })).toBeNull()
    s.openProviderSheet({ entry: 'openapi', target: { kind: 'insert', point: null } })
    expect(leaveSheet(s, { ...d, name: '' }, { action: sheetActions(d)[0], dropDraft: false })).toBeNull()
    expect(stepIds(s.getState().graph)).toEqual(steps)
  })

  it('still becomes a step when the provider was published on its own, whose draft goes', () => {
    const s = store()
    const d = backupDraft()
    s.upsertProviderDraft(d)
    s.openProviderSheet({ entry: 'openapi', draft: d.id, target: { kind: 'insert', point: null } })
    const id = leaveSheet(s, d, { action: sheetActions(d)[0], dropDraft: true })
    expect(id).not.toBeNull()
    expect(s.getState().providerDrafts).toEqual({})
    expect(data.has(KEY)).toBe(false)
  })

  it('becomes the step of its kind: a module, a library playbook, a tile, a provider call', () => {
    const tf = newProviderDraft('terraform', { title: 'Load balancer', name: 'lb', terraform: { source: 'git::https://github.com/acme/tf-modules//lb', version: '2.3.0' } })
    expect(actionStep(tf, { id: 'apply', title: 'Apply', effect: 'create' })).toEqual({ type: 'terraform', params: { name: 'Load balancer: Apply', module: { source: 'git::https://github.com/acme/tf-modules//lb', version: '2.3.0' }, terraformAction: 'apply' } })
    expect(actionStep(tf, { id: 'refresh', title: '', effect: 'read' }).params.terraformAction).toBe('plan')
    expect(actionStep(newProviderDraft('ansible', { name: 'ops-playbooks' }), { id: 'vm.restart', title: 'Restart a VM', effect: 'change' })).toEqual({ type: 'ansible', params: { playbook: 'ops-playbooks/vm.restart', name: 'Restart a VM' } })
    expect(actionStep(newProviderDraft('idp', { name: 'portal' }), { id: 'block-storage-request', title: '', effect: 'create' })).toEqual({ type: 'idp', params: { tile: 'block-storage-request', name: 'block-storage-request' } })
    expect(actionStep(newProviderDraft('sdk', { name: 'dns' }), { id: 'record.upsert', title: 'Upsert a record', effect: 'change' })).toEqual({ type: 'provider', params: { provider: 'dns', action: 'record.upsert', name: 'Upsert a record' } })
  })

  it("is one of the file's actions when the draft took a file (Devin's, an import's)", () => {
    const file = [
      'apiVersion: iaf.platform/v1',
      'kind: Provider',
      'metadata:',
      '  name: ops-playbooks',
      'spec:',
      '  type: ansible',
      '  actions:',
      '    - id: vm.restart',
      '      title: "Restart a VM"',
      '      playbook: vm/restart.yml',
      '      effect: change',
      '      vars:',
      '        required:',
      '          - id',
      '    - title: Snapshot',
      "      id: 'vm.snapshot'",
      '      effect: create # kept',
      '  errors: []',
      '',
    ].join('\n')
    expect(sheetActions(newProviderDraft('ansible', { draftYaml: file }))).toEqual([
      { id: 'vm.restart', title: 'Restart a VM', effect: 'change' },
      { id: 'vm.snapshot', title: 'Snapshot', effect: 'create' },
    ])
    // a list at the key's own indentation, and a file with no actions
    expect(yamlActions('spec:\n  actions:\n  - id: plan\n    effect: read\n  - id: apply\nmetadata:\n  name: x\n')).toEqual([
      { id: 'plan', title: '', effect: 'read' },
      { id: 'apply', title: '', effect: '' },
    ])
    expect(yamlActions('kind: Provider\nspec:\n  type: http\n')).toEqual([])
  })
})
