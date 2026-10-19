import { renderToString } from 'react-dom/server'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { WorkflowDocument } from '@iaf/workflow-checks'
import { blankAction } from '../../components/provider-editor/state'
import { api } from '../../lib/api'
import { providersWithDrafts } from '../creator/catalog'
import { bodyFor } from '../model/edits'
import { documentToGraph, findStepNode, graphToDocument } from '../model/graph'
import { draftYaml, newProviderDraft, type ProviderDraft } from '../model/providerDrafts'
import { EMPTY_REGISTRY, type Registry } from '../model/registry'
import { DesignerContext, DesignerStore } from '../model/store'
import { parseCurl } from './curl'
import { checkAccessOn, checkAccessValue, writeField } from './fields'
import { actionIdFor, httpActionPlan, lastOutputOf, planProviderAction, providerNameFor, saveAsProviderAction } from './httpProvider'
import { credentialFieldOptions, credentialOptions, curlCredentialNote, editProviderOptions, gitResolveQuery, importCurl, NEW_CREDENTIAL, NEW_PROVIDER, newProviderOptions, providerFieldOptions, startProviderActionSave, StepParameters } from './StepParameters'
import { hostsValue, withGroupKey } from './inventory'

/*
 * The git playbook of an ansible step: the repository's read token (`playbook.git.credential`) is
 * its own field and is what the pin sends to GET /playbooks:resolve; the step's `credential` stays
 * the machine credential the playbook reaches the hosts with.
 */

const CREDENTIALS = [
  { name: 'ssh/ops-automation', type: 'ssh-key' },
  { name: 'vsphere/token', type: 'token' },
  { name: 'github/read-token', type: 'token' },
]

const REGISTRY: Registry = { ...EMPTY_REGISTRY, credentials: CREDENTIALS }

const GIT = { repo: 'github.com/acme/ops-playbooks', ref: 'v3.1.0', path: 'vm/restart.yml' }

const restartWith = (playbook: unknown): WorkflowDocument =>
  ({
    apiVersion: 'iaf.platform/v1',
    kind: 'Workflow',
    metadata: { name: 'vm-restart', noun: 'vm', verb: 'restart', owner: 'team-compute', version: '1.0.0' },
    workflow: { do: [{ restart: { name: 'Restart with Ansible', call: 'ansible', with: { playbook, inventory: 'dynamic', credential: 'vault:ssh/ops-automation' } } }] },
  }) as WorkflowDocument

/** The markup of one parameter, from its data-field to the next parameter. */
function field(html: string, name: string): string {
  const start = html.indexOf(`data-field="${name}"`)
  expect(start, `no field ${name}`).toBeGreaterThanOrEqual(0)
  const next = html.indexOf('data-field="', start + 1)
  return html.slice(start, next < 0 ? undefined : next)
}

/** The Parameters form of a step as the store holds it, with the store's registry. */
function renderStep(store: DesignerStore, stepId: string): string {
  const node = findStepNode(store.getState().graph, stepId)
  if (!node) throw new Error(`no ${stepId} step`)
  return renderToString(
    <DesignerContext.Provider value={store}>
      <StepParameters stepId={stepId} body={node.data.body} step={node.data.step} registry={store.getState().registry} doc={store.getState().doc} />
    </DesignerContext.Provider>,
  )
}

function render(doc: WorkflowDocument, stepId = 'restart'): string {
  return renderStep(new DesignerStore({ name: 'vm-restart', doc, registry: REGISTRY }), stepId)
}

/** The checked option of a segmented control, by its aria-label. */
function checked(html: string, group: string): string | null {
  const start = html.indexOf(`aria-label="${group}"`)
  if (start < 0) return null
  const end = html.indexOf('</div>', start)
  const m = /<button[^>]*role="radio" aria-checked="true"[^>]*>(?:<[^>]+>)*([^<]+)/.exec(html.slice(start, end))
  return m ? m[1] : null
}

const withInventory = (inventory: unknown, limit?: unknown): WorkflowDocument => {
  const doc = restartWith({ git: GIT, commit: 'abc' }) as WorkflowDocument & { workflow: { do: { restart: { with: Record<string, unknown> } }[] } }
  const w = doc.workflow.do[0].restart.with
  if (inventory === undefined) delete w.inventory
  else w.inventory = inventory
  if (limit !== undefined) w.limit = limit
  return doc
}

describe("an ansible step's inventory and limit", () => {
  it('shows the kind the inventory is and its own fields', () => {
    const dynamic = render(withInventory('dynamic', '{{ $vm.name }}'))
    expect(checked(dynamic, 'Inventory')).toBe('Dynamic')
    expect(dynamic).toContain('The dynamic inventory the platform is configured with')
    expect(dynamic).not.toContain('Inventory path')

    const path = render(withInventory('inventories/prod.ini'))
    expect(checked(path, 'Inventory')).toBe('Path')
    expect(path).toContain('Inventory path')
    expect(path).toContain('value="inventories/prod.ini"')

    const hosts = render(withInventory(['web1', '{{ $vm.name }}']))
    expect(checked(hosts, 'Inventory')).toBe('Hosts')
    expect(hosts).toContain('value="web1, {{ $vm.name }}"')
    expect(hosts).not.toContain('[object Object]')

    const group = render(withInventory({ hosts: 'web1,web2', group: 'web', vars: { http_port: 8080 } }))
    expect(checked(group, 'Inventory')).toBe('Group')
    expect(field(group, 'inventory.hosts')).toContain('value="web1,web2"')
    expect(field(group, 'inventory.group')).toContain('value="web"')
    expect(field(group, 'inventory.vars')).toContain('value="http_port"')
    expect(group).not.toContain('[object Object]')

    const none = render(withInventory(undefined))
    expect(checked(none, 'Inventory')).toBe('None')
    expect(none).toContain("the playbook runs on ansible&#x27;s implicit localhost")
  })

  it('shows a limit as the pattern it is, or as the list a file wrote', () => {
    expect(field(render(withInventory('dynamic', 'web*:!web3')), 'limit')).toContain('value="web*:!web3"')
    expect(field(render(withInventory('dynamic', ['web1', 'web2'])), 'limit')).toContain('value="web1, web2"')
  })

  it('writes what the form edits in the shapes the runner reads, and reads them back', () => {
    const store = new DesignerStore({ name: 'vm-restart', doc: withInventory('dynamic'), registry: REGISTRY })
    const withOf = () => findStepNode(store.getState().graph, 'restart')?.data.body.with as Record<string, unknown>
    // Hosts: the typed names as a list
    store.updateStep('restart', (b) => writeField(b, 'inventory', hostsValue('web1, web2', 'dynamic')))
    expect(withOf().inventory).toEqual(['web1', 'web2'])
    // Group: the hosts move into it, then its name and variables
    store.updateStep('restart', (b) => writeField(b, 'inventory', withGroupKey(['web1', 'web2'], 'group', 'web')))
    store.updateStep('restart', (b) => writeField(b, 'inventory', withGroupKey(withOf().inventory as never, 'vars', { http_port: 8080 })))
    expect(withOf().inventory).toEqual({ hosts: ['web1', 'web2'], group: 'web', vars: { http_port: 8080 } })
    // a list limit stays a list
    store.updateStep('restart', (b) => writeField(b, 'limit', hostsValue('web1', ['web1', 'web2'])))
    expect(withOf().limit).toEqual(['web1'])
    const doc = store.getState().doc
    expect(graphToDocument(documentToGraph(doc), doc)).toEqual(doc)
    expect(checked(render(doc), 'Inventory')).toBe('Group')
  })
})

describe('the git credential of a playbook', () => {
  it('is what the pin sends to the resolve call, never the machine credential', () => {
    expect(gitResolveQuery({ git: { ...GIT, credential: 'vault:github/read-token' }, commit: '' })).toEqual({ repo: GIT.repo, ref: GIT.ref, credential: 'vault:github/read-token' })
    const open = gitResolveQuery({ git: { ...GIT }, commit: '' })
    expect(open).toEqual({ repo: GIT.repo, ref: GIT.ref })
    expect(open && 'credential' in open).toBe(false)
    expect(gitResolveQuery({ git: { ...GIT, credential: '  ' } })).toEqual({ repo: GIT.repo, ref: GIT.ref })
    expect(gitResolveQuery({ git: { repo: GIT.repo, ref: ' ', path: GIT.path } })).toBeNull()
    expect(gitResolveQuery('ops/vm-restart')).toBeNull()
    expect(gitResolveQuery({ upload: { file: 'x', sha256: 'y' } })).toBeNull()
    expect(gitResolveQuery(undefined)).toBeNull()
  })

  it('offers the vault references of the credentials, the kinds that fit first', () => {
    expect(credentialOptions(CREDENTIALS, ['token', 'basic']).map((o) => o.value)).toEqual(['vault:github/read-token', 'vault:vsphere/token', 'vault:ssh/ops-automation'])
    expect(credentialOptions(CREDENTIALS, ['ssh-key']).map((o) => o.value)).toEqual(['vault:ssh/ops-automation', 'vault:github/read-token', 'vault:vsphere/token'])
    expect(credentialOptions(CREDENTIALS).map((o) => o.label)).toEqual(['github/read-token', 'ssh/ops-automation', 'vsphere/token'])
    expect(credentialOptions(CREDENTIALS)[0]).toEqual({ value: 'vault:github/read-token', label: 'github/read-token', hint: 'token' })
  })

  it('has a picker of its own on a git playbook, beside the machine credential of the hosts', () => {
    const html = render(restartWith({ git: { ...GIT, credential: 'vault:github/read-token' }, commit: '4e1d9a2' }))
    const git = field(html, 'playbook.git.credential')
    expect(git).toContain('Git credential')
    expect(git).toContain('aria-label="Git credential"')
    expect(git).toContain('github/read-token')
    expect(git).not.toContain('ssh/ops-automation')
    const machine = field(html, 'credential')
    expect(machine).toContain('Machine credential')
    expect(machine).toContain('ssh/ops-automation')
    expect(machine).not.toContain('github/read-token')
  })

  it('is left empty for a public repository and is not offered for the other sources', () => {
    const open = field(render(restartWith({ git: { ...GIT }, commit: '4e1d9a2' })), 'playbook.git.credential')
    expect(open).not.toContain('github/read-token')
    expect(open).toContain('Leave it empty for a public repository')
    expect(render(restartWith('ops/vm-restart'))).not.toContain('data-field="playbook.git.credential"')
    expect(render(restartWith({ upload: { file: 'playbooks/abc.tar.gz', sha256: 'abc' } }))).not.toContain('data-field="playbook.git.credential"')
  })
})

/*
 * Every credential field ends its options with "New credential…", as n8n's credential select ends
 * with "Create new credential": the dialog stores the value, and the field takes the reference.
 */
const fetchThings = (credential?: string): WorkflowDocument =>
  ({
    apiVersion: 'iaf.platform/v1',
    kind: 'Workflow',
    metadata: { name: 'things-sync', noun: 'thing', verb: 'sync', owner: 'team-compute', version: '1.0.0' },
    workflow: { do: [{ fetch: { name: 'Fetch the things', call: 'http', with: { method: 'GET', url: 'https://api.example.com/things', ...(credential ? { credential } : {}) } } }] },
  }) as WorkflowDocument

describe('a new credential from a credential field', () => {
  const NEW = { value: NEW_CREDENTIAL, label: 'New credential…' }

  it('is offered last, after a reference the registry does not list, and never on a read-only step', () => {
    expect(credentialFieldOptions(CREDENTIALS, ['token', 'basic', 'bearer'], '', true).map((o) => o.value)).toEqual(['vault:github/read-token', 'vault:vsphere/token', 'vault:ssh/ops-automation', NEW_CREDENTIAL])
    expect(credentialFieldOptions(CREDENTIALS, ['token'], '', true).at(-1)).toEqual(NEW)
    expect(credentialFieldOptions(CREDENTIALS, ['ssh-key'], 'vault:gone/key', true).slice(-2)).toEqual([{ value: 'vault:gone/key', label: 'vault:gone/key', hint: 'not in the list' }, NEW])
    expect(credentialFieldOptions(CREDENTIALS, ['ssh-key'], 'vault:ssh/ops-automation', true).filter((o) => o.value === 'vault:ssh/ops-automation')).toHaveLength(1)
    expect(credentialFieldOptions(CREDENTIALS, ['token'], '', false).map((o) => o.value)).not.toContain(NEW_CREDENTIAL)
    // with no credential in the registry yet it is the only choice
    expect(credentialFieldOptions([], ['token'], '', true)).toEqual([NEW])
  })

  it('becomes the reference the field takes, shown by its name at once; one undo takes the field back', () => {
    // a reference the registry does not list shows as it is written
    expect(field(render(fetchThings('vault:example/api-token'), 'fetch'), 'credential')).toContain('>vault:example/api-token<')
    // what storing it in the dialog does: the registry lists its name and type, the field takes the reference
    const store = new DesignerStore({ name: 'things-sync', doc: fetchThings(), registry: REGISTRY })
    store.addCredential({ name: 'example/api-token', type: 'token' })
    store.updateStep('fetch', (b) => writeField(b, 'credential', 'vault:example/api-token'))
    const credential = field(renderStep(store, 'fetch'), 'credential')
    expect(credential).toContain('>example/api-token<')
    expect(credential).not.toContain('vault:example/api-token')
    expect(store.getState().doc.workflow?.do?.[0]).toEqual({ fetch: { name: 'Fetch the things', call: 'http', with: { method: 'GET', url: 'https://api.example.com/things', credential: 'vault:example/api-token' } } })
    store.undo()
    expect(JSON.stringify(store.getState().doc)).not.toContain('credential')
    expect(store.getState().registry?.credentials).toContainEqual({ name: 'example/api-token', type: 'token' })
  })
})

/*
 * A use step asks policy whether the requester may use its resource unless it opts out
 * (`checkAccess: false`): the switch is on for a step that says nothing, and the canvas never
 * writes `checkAccess: true`.
 */
const useVm = (use: Record<string, unknown>): WorkflowDocument =>
  ({
    apiVersion: 'iaf.platform/v1',
    kind: 'Workflow',
    metadata: { name: 'vm-use', noun: 'vm', verb: 'use', owner: 'team-compute', version: '1.0.0' },
    workflow: { do: [{ vm: { name: 'Use the VM', use } }] },
  }) as WorkflowDocument

describe('the check access of a use step', () => {
  it('is on unless the step opts out', () => {
    expect(checkAccessOn(undefined)).toBe(true)
    expect(checkAccessOn(true)).toBe(true)
    expect(checkAccessOn(false)).toBe(false)
    expect(checkAccessValue(false)).toBe(false)
    expect(checkAccessValue(true)).toBeUndefined()
    expect(checkAccessValue(undefined)).toBeUndefined()
  })

  it('shows the switch on for a step that says nothing and off for checkAccess: false', () => {
    const quiet = field(render(useVm({ type: 'vm', id: '{{ $input.vmId }}' }), 'vm'), 'checkAccess')
    expect(quiet).toContain('Check access')
    expect(quiet).toContain('role="switch" aria-checked="true"')
    const out = field(render(useVm({ type: 'vm', id: '{{ $input.vmId }}', checkAccess: false }), 'vm'), 'checkAccess')
    expect(out).toContain('role="switch" aria-checked="false"')
  })

  it('writes checkAccess: false to opt out and removes the key to opt back in', () => {
    const store = new DesignerStore({ name: 'vm-use', doc: useVm({ type: 'vm', id: '{{ $input.vmId }}' }), registry: REGISTRY })
    store.updateStep('vm', (b) => writeField(b, 'checkAccess', checkAccessValue(false)))
    expect(store.getState().doc.workflow?.do?.[0]).toEqual({ vm: { name: 'Use the VM', use: { type: 'vm', id: '{{ $input.vmId }}', checkAccess: false } } })
    store.updateStep('vm', (b) => writeField(b, 'checkAccess', checkAccessValue(true)))
    expect(store.getState().doc.workflow?.do?.[0]).toEqual({ vm: { name: 'Use the VM', use: { type: 'vm', id: '{{ $input.vmId }}' } } })
  })

  it('round-trips an opt-out through the canvas, and a new use step carries no checkAccess', () => {
    const doc = useVm({ type: 'vm', id: '{{ $input.vmId }}', checkAccess: false })
    expect(graphToDocument(documentToGraph(doc), doc)).toEqual(doc)
    expect(JSON.stringify(bodyFor('use', { resourceType: 'vm' }))).not.toContain('checkAccess')
  })
})

/*
 * The provider field, as n8n has a credential field: its options end with "New provider…" and a
 * pencil beside it edits the provider, both through the Provider sheet for this step. The
 * workflow's drafts are listed with the published providers, marked draft.
 */
const PROVIDERS_REGISTRY: Registry = {
  ...REGISTRY,
  providers: [
    { name: 'vsphere', title: 'VMware vSphere', type: 'openapi', owner: 'team-compute', actions: [{ id: 'vm.get', title: 'Get VM', effect: 'read', retry: 'safe' }] },
    { name: 'tf', title: 'Terraform modules', type: 'terraform', actions: [{ id: 'plan', title: 'Plan', effect: 'read', retry: 'safe' }] },
  ],
}

const providerStep = (provider: string, action: string): WorkflowDocument =>
  ({
    apiVersion: 'iaf.platform/v1',
    kind: 'Workflow',
    metadata: { name: 'vm-check', noun: 'vm', verb: 'check', owner: 'team-compute', version: '1.0.0' },
    workflow: { do: [{ check: { name: 'Check the VM', call: 'provider', with: { provider, action, input: {} } } }] },
  }) as WorkflowDocument

const backupDraft = (): ProviderDraft => newProviderDraft('http', { title: 'Backup API', name: 'backup', actions: [blankAction({ id: 'jobs.list', title: 'List jobs', path: '/jobs' })] })

describe('the provider field of a provider step', () => {
  it('lists the providers a step calls, the drafts marked, then "New provider…" unless read-only', () => {
    const listed = providersWithDrafts(PROVIDERS_REGISTRY, [backupDraft()])
    expect(providerFieldOptions(listed, true)).toEqual([
      { value: 'vsphere', label: 'VMware vSphere', hint: 'vsphere' },
      { value: 'backup', label: 'Backup API', hint: 'backup · draft' },
      { value: NEW_PROVIDER, label: 'New provider…' },
    ])
    expect(providerFieldOptions(listed, false).map((o) => o.value)).toEqual(['vsphere', 'backup'])
    expect(providerFieldOptions([], true)).toEqual([{ value: NEW_PROVIDER, label: 'New provider…' }])
  })

  it('opens the Provider sheet on a new provider for this step, and the action it ends on fills the step', () => {
    const store = new DesignerStore({ name: 'vm-check', doc: providerStep('', ''), registry: PROVIDERS_REGISTRY })
    store.openProviderSheet(newProviderOptions('check'))
    expect(store.getState().providerSheet).toEqual({ entry: 'step', draft: null, provider: null, target: { kind: 'step', step: 'check' } })
    expect(store.closeProviderSheet({ type: 'provider', params: { provider: 'backup', action: 'jobs.list' } })).toBe('check')
    expect(store.getState().doc.workflow?.do?.[0]).toEqual({ check: { name: 'Check the VM', call: 'provider', with: { provider: 'backup', action: 'jobs.list', input: {} } } })
  })

  it('edits a published provider through a draft of its next patch, and a draft as it is', () => {
    const store = new DesignerStore({ name: 'vm-check', doc: providerStep('vsphere', 'vm.get'), registry: PROVIDERS_REGISTRY })
    // published, no draft yet: the sheet starts the next patch's draft from the provider
    expect(editProviderOptions({}, 'check', 'vsphere')).toEqual({ entry: 'step', provider: 'vsphere', target: { kind: 'step', step: 'check' } })
    store.updateStep('check', (b) => writeField(b, 'input.id', '{{ $input.vmId }}'))
    store.openProviderSheet(editProviderOptions({}, 'check', 'vsphere'))
    expect(store.getState().providerSheet).toEqual({ entry: 'step', draft: null, provider: 'vsphere', target: { kind: 'step', step: 'check' } })
    // closing on the action the step already calls keeps what the step passes it
    expect(store.closeProviderSheet({ type: 'provider', params: { provider: 'vsphere', action: 'vm.get' } })).toBe('check')
    expect(store.getState().doc.workflow?.do?.[0]).toEqual({ check: { name: 'Check the VM', call: 'provider', with: { provider: 'vsphere', action: 'vm.get', input: { id: '{{ $input.vmId }}' } } } })
    // the workflow's draft of it: opened on that draft
    const next: ProviderDraft = { ...newProviderDraft('openapi', { name: 'vsphere', title: 'VMware vSphere' }), origin: 'edit', publishedVersion: '1.3.0' }
    store.upsertProviderDraft(next)
    expect(editProviderOptions(store.getState().providerDrafts, 'check', 'vsphere')).toEqual({ entry: 'step', provider: 'vsphere', draft: next.id, target: { kind: 'step', step: 'check' } })
    // a provider that is only a draft: no published provider to fetch
    const backup = backupDraft()
    expect(editProviderOptions({ [backup.id]: backup }, 'check', 'backup')).toEqual({ entry: 'step', draft: backup.id, provider: null, target: { kind: 'step', step: 'check' } })
  })

  it('shows the pencil beside a chosen provider, and the draft tag on a draft', () => {
    const store = new DesignerStore({ name: 'vm-check', doc: providerStep('vsphere', 'vm.get'), registry: PROVIDERS_REGISTRY })
    const published = renderStep(store, 'check')
    expect(published).toContain('aria-label="Edit provider"')
    expect(published).toContain('>VMware vSphere<')
    expect(published).not.toMatch(/>draft</)
    store.upsertProviderDraft(backupDraft())
    store.updateStep('check', (b) => {
      b.with = { provider: 'backup', action: 'jobs.list', input: {} }
    })
    const draft = renderStep(store, 'check')
    expect(draft).toContain('>Backup API<')
    expect(draft).toMatch(/>draft</)
    expect(draft).toContain('>List jobs<')
    // nothing to edit before a provider is chosen, nor on a read-only step
    expect(renderStep(new DesignerStore({ name: 'vm-check', doc: providerStep('', ''), registry: PROVIDERS_REGISTRY }), 'check')).not.toContain('Edit provider')
    const node = findStepNode(store.getState().graph, 'check')
    if (!node) throw new Error('no check step')
    const readOnly = renderToString(
      <DesignerContext.Provider value={store}>
        <StepParameters stepId="check" body={node.data.body} step={node.data.step} registry={store.getState().registry} doc={store.getState().doc} readOnly />
      </DesignerContext.Provider>,
    )
    expect(readOnly).not.toContain('Edit provider')
  })
})

/*
 * The HTTP step's Import cURL and Save as provider action. The fixtures' secrets are fake; each
 * test checks they reach neither the step, the workflow, the undo history nor stored drafts.
 */
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

describe("the HTTP step's Import cURL", () => {
  it('offers Import cURL and Save as provider action, never on a read-only step', () => {
    const html = render(fetchThings(), 'fetch')
    expect(html).toContain('Import cURL')
    expect(html).toContain('Save as provider action')
    const store = new DesignerStore({ name: 'things-sync', doc: fetchThings(), registry: REGISTRY })
    const node = findStepNode(store.getState().graph, 'fetch')
    if (!node) throw new Error('no fetch step')
    const readOnly = renderToString(
      <DesignerContext.Provider value={store}>
        <StepParameters stepId="fetch" body={node.data.body} step={node.data.step} registry={REGISTRY} doc={store.getState().doc} readOnly />
      </DesignerContext.Provider>,
    )
    expect(readOnly).not.toContain('Import cURL')
  })

  it('fills the step in one undo step, keeps its credential, and the dropped secret is nowhere', () => {
    const store = new DesignerStore({ name: 'things-sync', doc: fetchThings('vault:example/api-token'), registry: REGISTRY })
    const parsed = parseCurl(`curl -X POST 'https://api.example.com/things?api_key=example-key' -H 'Authorization: Bearer example-token' -H 'X-Trace: 1' -u ops:example-password -d '{"name":"web","password":"example-pw"}'`)
    if (!parsed.ok) throw new Error(parsed.error)
    importCurl((fn) => store.updateStep('fetch', fn), parsed.request)
    expect(store.getState().doc.workflow?.do?.[0]).toEqual({
      fetch: { name: 'Fetch the things', call: 'http', with: { method: 'POST', url: 'https://api.example.com/things', headers: { 'X-Trace': '1' }, body: { name: 'web' }, credential: 'vault:example/api-token' } },
    })
    const secrets = ['example-token', 'example-key', 'example-password', 'example-pw']
    const seen = [JSON.stringify(store.getState().doc)]
    store.undo()
    seen.push(JSON.stringify(store.getState().doc))
    expect(store.getState().doc.workflow?.do?.[0]).toEqual(fetchThings('vault:example/api-token').workflow?.do?.[0])
    store.redo()
    seen.push(JSON.stringify(store.getState().doc), renderStep(store, 'fetch'))
    for (const s of secrets) for (const text of seen) expect(text).not.toContain(s)
    // the note at the credential field names what was dropped, never a value
    const note = curlCredentialNote({ dropped: parsed.request.dropped, kind: parsed.request.credentialKind })
    expect(note).toBe(
      'The command signed in with a bearer token (Authorization header, the user and password (-u), api_key in the URL, password in the body); it was not imported and is not kept anywhere. Pick a credential, or create one, for the step to sign in with.',
    )
    for (const s of secrets) expect(note).not.toContain(s)
  })
})

describe('saving an HTTP step as a provider action', () => {
  let storage: ReturnType<typeof memoryStorage>
  beforeEach(() => {
    storage = memoryStorage()
    vi.stubGlobal('localStorage', storage)
  })
  afterEach(() => {
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  const vmPower = (w: Record<string, unknown>): WorkflowDocument =>
    ({
      apiVersion: 'iaf.platform/v1',
      kind: 'Workflow',
      metadata: { name: 'vm-power', noun: 'vm', verb: 'power', owner: 'team-compute', version: '1.0.0' },
      workflow: {
        do: [
          { getVm: { name: 'Get the VM', call: 'http', with: w } },
          { powerOn: { name: 'Power it on', call: 'http', with: { method: 'POST', url: 'https://api.example.com/vms/{{ $input.vmId }}/power', body: { state: 'on', force: false }, credential: 'vault:example/api-token' } } },
        ],
      },
    }) as WorkflowDocument

  const GET_VM = { method: 'GET', url: 'https://api.example.com/vms/{{ $input.vmId }}?verbose=true', credential: 'vault:example/api-token', headers: { Accept: 'application/json', 'X-Trace': '{{ $input.trace }}' } }

  it("plans the action from the step: the origin, the path with the expressions as parameters, the body's fields", () => {
    const plan = httpActionPlan(GET_VM)
    expect(plan).toEqual({
      ok: true,
      plan: {
        endpoint: 'https://api.example.com',
        method: 'GET',
        path: '/vms/{vmId}?verbose=true',
        input: { vmId: '{{ $input.vmId }}' },
        effect: 'read',
        retry: 'safe',
        auth: { type: 'bearer', credential: 'vault:example/api-token', header: '' },
        notes: ['The header X-Trace is not part of a provider action and was left out.'],
      },
    })
    const post = httpActionPlan({ method: 'POST', url: 'https://api.example.com/vms/{{ $json.id }}/disks/{{ $json.id }}', body: { size: 20, id: 'x' }, headers: { 'X-API-Key': 'vault:example/key' } })
    expect(post.ok && post.plan).toMatchObject({ path: '/vms/{id}/disks/{id2}', input: { id: '{{ $json.id }}', id2: '{{ $json.id }}', size: 20 }, effect: 'create', retry: 'unsafe', auth: { type: 'header', header: 'X-API-Key', credential: 'vault:example/key' } })
    expect(post.ok && post.plan.notes).toEqual(["The body's id has the name of a path parameter: it was left out."])
    expect(httpActionPlan({ method: 'DELETE', url: 'https://api.example.com/x', headers: { Authorization: 'Basic vault:example/basic' } })).toMatchObject({ ok: true, plan: { effect: 'delete', auth: { type: 'basic', credential: 'vault:example/basic' } } })
    expect(httpActionPlan({ url: 'http://legacy.example.com' })).toMatchObject({ ok: true, plan: { path: '/', notes: ['http://legacy.example.com is not https: a provider\'s endpoints are https URLs (plain http only to localhost).'] } })
    expect(httpActionPlan({ url: '' })).toEqual({ ok: false, error: 'Fill in the URL first.' })
    expect(httpActionPlan({ url: '{{ $vars.base }}/things' })).toEqual({ ok: false, error: 'The URL has to start with the address of the system (https://…), not an expression.' })
    expect(httpActionPlan({ method: 'HEAD', url: 'https://h.example.com' })).toEqual({ ok: false, error: "A provider action is a GET, POST, PUT, PATCH or DELETE; this step's method is HEAD." })
  })

  it('names the provider after the host and the action after the route, never twice', () => {
    expect(providerNameFor('api.example.com', new Set())).toBe('example')
    expect(providerNameFor('www.status.example.io:8443', new Set())).toBe('status-example')
    expect(providerNameFor('api.example.com', new Set(['example']))).toBe('example-api')
    expect(providerNameFor('api.example.com', new Set(['example', 'example-api']))).toBe('example-2')
    expect(providerNameFor('localhost:8080', new Set())).toBe('localhost')
    expect(providerNameFor('10.0.0.7', new Set())).toBe('api-10-0-0-7')
    expect(actionIdFor('GET', '/vms/{id}', new Set())).toBe('vms.get')
    expect(actionIdFor('GET', '/vms?limit=5', new Set())).toBe('vms.list')
    expect(actionIdFor('POST', '/vms/{id}/power', new Set())).toBe('power.create')
    expect(actionIdFor('PATCH', '/vms/{id}', new Set(['vms.update']))).toBe('vms.update-2')
    expect(actionIdFor('DELETE', '/', new Set())).toBe('delete')
  })

  /** A test run of the workflow in which the step answered this. */
  async function tested(store: DesignerStore, step: string, output: unknown) {
    vi.spyOn(api, 'dryRunWorkflow').mockResolvedValue({ passed: true, path: [step], steps: [{ id: step, name: step, status: 'succeeded', output }] })
    await store.testRun({})
  }

  it('makes an http provider draft, turns the step into a call of it (one undo), and opens the sheet on it', async () => {
    const store = new DesignerStore({ name: 'vm-power', doc: vmPower(GET_VM), registry: REGISTRY })
    // the last test output: an HTTP answer, whose body is the example
    await tested(store, 'getVm', { status: 200, body: { id: 'vm-1', name: 'web-01', powerState: 'off' } })
    store.dispose()
    const saved = saveAsProviderAction(store, 'getVm')
    if (!saved.ok) throw new Error(saved.error)
    expect(saved).toMatchObject({ provider: 'example', action: 'vms.get', notes: ['The header X-Trace is not part of a provider action and was left out.'] })
    const d = store.getState().providerDrafts[saved.draft.id]
    expect(d).toMatchObject({ origin: 'new', type: 'http', name: 'example', title: 'api.example.com', owner: 'team-compute', auth: { type: 'bearer', credential: 'vault:example/api-token', header: '' } })
    expect(d.environments.map((e) => [e.name, e.url])).toEqual([['dev', 'https://api.example.com']])
    expect(d.actions).toHaveLength(1)
    expect(d.actions[0]).toMatchObject({ id: 'vms.get', title: 'Get the VM', method: 'GET', path: '/vms/{vmId}?verbose=true', effect: 'read', retry: 'safe', approval: false })
    expect(JSON.parse(d.actions[0].exampleText)).toEqual({ id: 'vm-1', name: 'web-01', powerState: 'off' })
    expect(JSON.parse(d.actions[0].inputText)).toEqual({ type: 'object', properties: { vmId: { type: 'string' } } })
    // the step calls the action, keeping its id and name
    expect(store.getState().doc.workflow?.do?.[0]).toEqual({ getVm: { name: 'Get the VM', call: 'provider', with: { provider: 'example', action: 'vms.get', input: { vmId: '{{ $input.vmId }}' } } } })
    expect(store.getState().providerSheet).toEqual({ entry: 'step', draft: d.id, provider: null, target: { kind: 'step', step: 'getVm' } })
    // the provider file has the endpoint and the route, and the step's form lists the draft
    const yaml = draftYaml(d)
    expect(yaml).toContain('dev: https://api.example.com')
    expect(yaml).toMatch(/method: GET/)
    expect(yaml).toContain('path: /vms/{vmId}?verbose=true')
    const html = renderStep(store, 'getVm')
    expect(html).toContain('>api.example.com<')
    expect(html).toMatch(/>draft</)
    // kept in this browser as a draft, with only the credential's reference
    expect(storage.getItem('iaf-designer-provider-drafts:vm-power')).toContain('vault:example/api-token')
    // one undo gives the HTTP step back; the draft stays for the sheet
    store.undo()
    expect(store.getState().doc.workflow?.do?.[0]).toEqual({ getVm: { name: 'Get the VM', call: 'http', with: GET_VM } })
    expect(Object.keys(store.getState().providerDrafts)).toEqual([d.id])
  })

  it("adds a second request to the same endpoint's draft, with the body as its input", () => {
    const store = new DesignerStore({ name: 'vm-power', doc: vmPower(GET_VM), registry: REGISTRY })
    const first = saveAsProviderAction(store, 'getVm')
    store.closeProviderSheet()
    const second = saveAsProviderAction(store, 'powerOn')
    if (!first.ok || !second.ok) throw new Error('not saved')
    expect(second.draft.id).toBe(first.draft.id)
    // the sheet closing on the action the step already calls keeps the step's input
    expect(store.closeProviderSheet({ type: 'provider', params: { provider: 'example', action: 'power.create' } })).toBe('powerOn')
    expect(store.getState().providerSheet).toBeNull()
    const d = store.getState().providerDrafts[first.draft.id]
    expect(d.actions.map((a) => [a.id, a.method, a.path, a.effect])).toEqual([
      ['vms.get', 'GET', '/vms/{vmId}?verbose=true', 'read'],
      ['power.create', 'POST', '/vms/{vmId}/power', 'create'],
    ])
    // no test output yet: no example
    expect(d.actions[1].exampleText).toBe('')
    expect(store.getState().doc.workflow?.do?.[1]).toEqual({ powerOn: { name: 'Power it on', call: 'provider', with: { provider: 'example', action: 'power.create', input: { vmId: '{{ $input.vmId }}', state: 'on', force: false } } } })
  })

  it('takes the example from a test or a run that succeeded, never from pinned data', async () => {
    const store = new DesignerStore({ name: 'vm-power', doc: vmPower(GET_VM), registry: REGISTRY })
    // a mock pinned to try expressions downstream is the designer's own, not the system's answer
    store.setPin('getVm', { id: 'fake-1' })
    expect(lastOutputOf(store.getState(), 'getVm')).toBeUndefined()
    const pinned = saveAsProviderAction(store, 'getVm')
    if (!pinned.ok) throw new Error(pinned.error)
    expect(store.getState().providerDrafts[pinned.draft.id].actions[0].exampleText).toBe('')
    expect(JSON.stringify(store.getState().providerDrafts)).not.toContain('fake-1')
    // a run that succeeded, as the open execution shows it
    const run = { status: 'succeeded', steps: [{ id: 'powerOn', status: 'succeeded', output: { status: 202, body: { task: 't-1' } } }] }
    expect(lastOutputOf({ testRun: null, execution: { jobId: 'j-1', job: run as never, loading: false, error: null } }, 'powerOn')).toEqual({ task: 't-1' })
    // a test run's answer comes before it
    await tested(store, 'powerOn', { status: 200, body: { task: 't-2' } })
    store.dispose()
    expect(lastOutputOf({ ...store.getState(), execution: { jobId: 'j-1', job: run as never, loading: false, error: null } }, 'powerOn')).toEqual({ task: 't-2' })
  })

  it('lists what does not come along before it saves, and saves at once when nothing is lost', () => {
    const store = new DesignerStore({ name: 'vm-power', doc: vmPower(GET_VM), registry: REGISTRY })
    // GET_VM sends X-Trace, which a provider action does not carry: the user is asked first
    expect(startProviderActionSave(store, 'getVm')).toEqual({ confirm: ['The header X-Trace is not part of a provider action and was left out.'] })
    expect(store.getState().doc.workflow?.do?.[0]).toEqual({ getVm: { name: 'Get the VM', call: 'http', with: GET_VM } })
    expect(store.getState().providerDrafts).toEqual({})
    // the dialog lists it
    expect(planProviderAction(store.getState(), 'getVm')).toMatchObject({ ok: true, save: { notes: ['The header X-Trace is not part of a provider action and was left out.'] } })
    // powerOn loses nothing: saved without asking
    expect(startProviderActionSave(store, 'powerOn')).toEqual({ saved: true })
    expect(store.getState().doc.workflow?.do?.[1]).toMatchObject({ powerOn: { call: 'provider' } })
    expect(startProviderActionSave(store, 'nope')).toEqual({ error: 'Only an HTTP request step can be saved as a provider action.' })
  })

  it('points a step at the action the draft has for the same request, never a second copy', () => {
    const doc = vmPower(GET_VM)
    const steps = doc.workflow?.do ?? []
    // a check before and after the change: two steps with the same request
    steps.push({ getVmAfter: { name: 'Get the VM again', call: 'http', with: { ...GET_VM } } } as never)
    const store = new DesignerStore({ name: 'vm-power', doc, registry: REGISTRY })
    const first = saveAsProviderAction(store, 'getVm')
    store.closeProviderSheet()
    const second = saveAsProviderAction(store, 'getVmAfter')
    if (!first.ok || !second.ok) throw new Error('not saved')
    expect(second.draft.id).toBe(first.draft.id)
    expect(second.action).toBe('vms.get')
    expect(store.getState().providerDrafts[first.draft.id].actions.map((a) => a.id)).toEqual(['vms.get'])
    expect(store.getState().doc.workflow?.do?.[2]).toEqual({ getVmAfter: { name: 'Get the VM again', call: 'provider', with: { provider: 'example', action: 'vms.get', input: { vmId: '{{ $input.vmId }}' } } } })
    // undo gives the HTTP step back and the draft stays; saving it again reuses the action
    store.closeProviderSheet()
    store.undo()
    expect(store.getState().doc.workflow?.do?.[2]).toMatchObject({ getVmAfter: { call: 'http' } })
    const again = saveAsProviderAction(store, 'getVmAfter')
    if (!again.ok) throw new Error(again.error)
    expect(again.action).toBe('vms.get')
    expect(store.getState().providerDrafts[first.draft.id].actions.map((a) => a.id)).toEqual(['vms.get'])
  })

  it("gives the draft the step's credential when it signs in with none, and starts a new draft when it signs in with another", () => {
    const doc = vmPower({ method: 'GET', url: 'https://api.example.com/health' })
    const steps = doc.workflow?.do ?? []
    steps.push({ listVms: { name: 'List the VMs', call: 'http', with: { method: 'GET', url: 'https://api.example.com/vms', credential: 'vault:example/other-token' } } } as never)
    const store = new DesignerStore({ name: 'vm-power', doc, registry: REGISTRY })
    // the health check signs in with nothing: the draft has no auth
    const health = saveAsProviderAction(store, 'getVm')
    if (!health.ok) throw new Error(health.error)
    expect(store.getState().providerDrafts[health.draft.id].auth).toEqual({ type: 'none', credential: '', header: '' })
    store.closeProviderSheet()
    // powerOn signs in with vault:example/api-token: the draft takes it, and keeps both actions
    const power = saveAsProviderAction(store, 'powerOn')
    if (!power.ok) throw new Error(power.error)
    expect(power.draft.id).toBe(health.draft.id)
    expect(store.getState().providerDrafts[health.draft.id]).toMatchObject({ auth: { type: 'bearer', credential: 'vault:example/api-token' } })
    expect(store.getState().providerDrafts[health.draft.id].actions.map((a) => a.id)).toEqual(['health.list', 'power.create'])
    store.closeProviderSheet()
    // listVms signs in with another credential: never dropped, a new draft of the endpoint, and the notes say so
    const planned = planProviderAction(store.getState(), 'listVms')
    expect(planned).toMatchObject({ ok: true, save: { notes: ['The draft example of https://api.example.com signs in with another credential than this step, so the request goes into a new draft, example-api.'] } })
    const list = saveAsProviderAction(store, 'listVms')
    if (!list.ok) throw new Error(list.error)
    expect(list.draft.id).not.toBe(health.draft.id)
    expect(store.getState().providerDrafts[list.draft.id]).toMatchObject({ name: 'example-api', auth: { type: 'bearer', credential: 'vault:example/other-token' } })
    expect(store.getState().providerDrafts[health.draft.id].auth.credential).toBe('vault:example/api-token')
  })

  it("never adds an action to a draft whose file is its own: a new draft has it, in the file it publishes", () => {
    const store = new DesignerStore({ name: 'vm-power', doc: vmPower(GET_VM), registry: REGISTRY })
    const first = saveAsProviderAction(store, 'getVm')
    if (!first.ok) throw new Error(first.error)
    store.closeProviderSheet()
    // Try it's "Use this" (or a Devin draft) fixes the draft's file
    const fixed = { ...store.getState().providerDrafts[first.draft.id] }
    fixed.draftYaml = draftYaml(fixed)
    store.upsertProviderDraft(fixed)
    expect(fixed.draftYaml).toMatch(/id: vms\.get/)
    // the same request again is the action the file already has: the draft stays as it is
    const steps = store.getState().doc.workflow?.do ?? []
    expect(steps).toHaveLength(2)
    store.undo()
    const same = planProviderAction(store.getState(), 'getVm')
    expect(same).toMatchObject({ ok: true, save: { changed: false, action: 'vms.get' } })
    // another request to the endpoint goes into a new draft, whose file has it
    const second = saveAsProviderAction(store, 'powerOn')
    if (!second.ok) throw new Error(second.error)
    expect(second.draft.id).not.toBe(first.draft.id)
    expect(second.notes).toEqual([`The draft example of https://api.example.com keeps a file of its own (a try's answer or a draft it took), which a new action would not reach, so the request goes into a new draft, example-api.`])
    expect(draftYaml(store.getState().providerDrafts[second.draft.id])).toContain('id: power.create')
    expect(store.getState().providerDrafts[first.draft.id].draftYaml).toBe(fixed.draftYaml)
    expect(store.getState().providerDrafts[first.draft.id].actions.map((a) => a.id)).toEqual(['vms.get'])
  })

  it('says why a step cannot be saved, and changes nothing', () => {
    const store = new DesignerStore({ name: 'vm-power', doc: vmPower({ method: 'GET', url: '{{ $vars.base }}/vms' }), registry: REGISTRY })
    const before = JSON.stringify(store.getState().doc)
    expect(saveAsProviderAction(store, 'getVm')).toEqual({ ok: false, error: 'The URL has to start with the address of the system (https://…), not an expression.' })
    expect(JSON.stringify(store.getState().doc)).toBe(before)
    expect(store.getState().providerDrafts).toEqual({})
    expect(store.getState().providerSheet).toBeNull()
    expect(saveAsProviderAction(store, 'nope')).toEqual({ ok: false, error: 'Only an HTTP request step can be saved as a provider action.' })
  })
})
