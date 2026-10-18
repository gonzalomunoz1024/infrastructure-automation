import { renderToString } from 'react-dom/server'
import { describe, expect, it } from 'vitest'
import type { WorkflowDocument } from '@iaf/workflow-checks'
import { bodyFor } from '../model/edits'
import { documentToGraph, findStepNode, graphToDocument } from '../model/graph'
import { EMPTY_REGISTRY, type Registry } from '../model/registry'
import { DesignerContext, DesignerStore } from '../model/store'
import { checkAccessOn, checkAccessValue, writeField } from './fields'
import { credentialFieldOptions, credentialOptions, gitResolveQuery, NEW_CREDENTIAL, StepParameters } from './StepParameters'
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
