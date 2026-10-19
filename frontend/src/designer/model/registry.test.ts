import { check, type Problem } from '@iaf/workflow-checks'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { CatalogEntry, Dependencies, JsonObject, RegistryProvider, WorkflowDocument } from '../../lib/types'
import { newDocument } from './edits'
import { contextOf, EMPTY_REGISTRY, loadRegistry, mcpToolsOf, providerLabel, setRegistryForTests, terraformModuleIn, terraformModuleOf, withTerraformModules, withUnpublished, workflowRunsOf, type Registry } from './registry'

/*
 * A terraform provider's module, which GET /registry/providers does not carry: read from the plan
 * step the backend's import drafts, parsed by the backend.
 */

const LB = { source: 'git::https://github.com/acme/tf-modules//lb', version: '2.3.0' }

const imported = (module: JsonObject): WorkflowDocument => ({
  apiVersion: 'iaf.platform/v1',
  kind: 'Workflow',
  metadata: { name: 'terraform-modules-apply' },
  workflow: {
    do: [
      { plan: { name: 'Plan', call: 'terraform', with: { module, action: 'plan', vars: {} } } },
      { approval: { name: 'Approve the plan', approval: { group: 'your-approvers' } } },
      { apply: { name: 'Apply', call: 'terraform', with: { module, action: 'apply' } } },
    ],
  },
})

afterEach(() => {
  vi.unstubAllGlobals()
  setRegistryForTests(null)
})

describe("a terraform provider's module", () => {
  it('is the module of the first terraform step, never the placeholder of a provider without one', () => {
    expect(terraformModuleIn(imported({ ...LB, commit: 'abc123', extra: 1 }))).toEqual({ ...LB, commit: 'abc123' })
    expect(terraformModuleIn(imported({ source: 'your-module-source', version: '1.0.0' }))).toBeNull()
    expect(terraformModuleIn(imported({ version: '1.0.0' }))).toBeNull()
    expect(terraformModuleIn({ workflow: { do: [{ wait: { wait: 'PT1M' } }] } })).toBeNull()
    expect(terraformModuleIn(null)).toBeNull()
  })

  it('is read through the import and the parse of the backend', async () => {
    const calls: { url: string; body: unknown }[] = []
    vi.stubGlobal('fetch', async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      const body = JSON.parse(String(init?.body))
      calls.push({ url, body })
      const answer = url.endsWith('/workflows:import') ? { yaml: 'kind: Workflow\n', notes: [] } : { definition: imported(LB), problems: [] }
      return new Response(JSON.stringify(answer), { status: 200, headers: { 'content-type': 'application/json' } })
    })
    expect(await terraformModuleOf('terraform-modules')).toEqual(LB)
    expect(calls).toEqual([
      { url: '/api/v1/workflows:import', body: { source: 'terraform', ref: 'terraform-modules' } },
      { url: '/api/v1/workflows:parse', body: { yaml: 'kind: Workflow\n' } },
    ])
  })

  it('fills in only the terraform providers the registry gave without one, and keeps the others as they came', async () => {
    const providers: RegistryProvider[] = [
      { name: 'vsphere', title: 'vSphere', type: 'openapi', actions: [] },
      { name: 'terraform-modules', title: 'Terraform modules', type: 'terraform', actions: [] },
      { name: 'pinned', title: 'Pinned', type: 'terraform', actions: [], module: { source: 'git::https://github.com/acme/net', version: '1.0.0' } },
      { name: 'broken', title: 'Broken', type: 'terraform', actions: [] },
    ]
    const asked: string[] = []
    const out = await withTerraformModules(providers, async (name) => {
      asked.push(name)
      if (name === 'broken') throw new Error('400')
      return LB
    })
    expect(asked).toEqual(['terraform-modules', 'broken'])
    expect(out.map((p) => p.module ?? null)).toEqual([null, LB, { source: 'git::https://github.com/acme/net', version: '1.0.0' }, null])
    expect(out[0]).toBe(providers[0])
  })
})

/*
 * What the checks read from the registry besides the lists the canvas shows: the published
 * workflows' versions, what each one starts (GET /registry/dependencies) and the MCP tools they
 * hold (GET /catalog), so the designer reports run.cycle, mcp.toolName and the contract of a run
 * step as the backend's checks do.
 */

const DEPENDENCIES: Pick<Dependencies, 'workflows'> = {
  workflows: [
    { workflow: 'patch-cve', version: '1.0.0', produces: [], consumes: ['vm'], runs: [{ workflow: 'vm-restart', via: 'run', at: 'reboot', range: '^1' }], consumers: [] },
    {
      workflow: 'web-environment',
      version: '1.1.0',
      produces: [],
      consumes: ['image'],
      runs: [
        { workflow: 'create-vm', via: 'run', at: 'vm', range: '^2' },
        { workflow: 'build-vm-image', via: 'orCreateWith', at: 'image' },
        { workflow: 'create-database', via: 'run', at: 'database', range: '^1' },
        { workflow: 'create-vm', via: 'run', at: 'spare', range: '^2' },
      ],
      consumers: [],
    },
    { workflow: 'vm-restart', version: '1.2.0', produces: [], consumes: ['vm'], runs: [], consumers: [{ workflow: 'patch-cve', via: 'run', at: 'reboot', range: '^1' }] },
  ],
}

const CATALOG: Pick<CatalogEntry, 'workflow' | 'mcpTool'>[] = [
  { workflow: 'create-vm', mcpTool: 'vm_create' },
  { workflow: 'vm-restart', mcpTool: 'vm_restart' },
  { workflow: 'vm-reboot', mcpTool: 'vm_restart' },
  { workflow: 'not-exposed' },
  { workflow: 'nameless', mcpTool: '_' },
]

const registryWith = (extra: Partial<Registry> = {}): Registry => ({
  ...EMPTY_REGISTRY,
  workflows: [
    { name: 'patch-cve', title: 'Patch a CVE', version: '1.0.0', input: { type: 'object', properties: { vmId: { type: 'string' } } }, output: {} },
    { name: 'create-vm', title: 'Create VM', version: '2.1.0', input: { type: 'object', required: ['name'], properties: { name: { type: 'string' } } }, output: {} },
  ],
  ...extra,
})

/** vm-restart as a draft, exposed to MCP, with the given steps. */
const draft = (steps: NonNullable<WorkflowDocument['workflow']>['do'], verb = 'restart'): WorkflowDocument => ({
  ...newDocument({ name: 'vm-restart', title: 'VM restart', noun: 'vm', verb, owner: 'team-compute', folder: 'Compute' }),
  mcp: { expose: true },
  workflow: { do: steps },
})

const only = (problems: Problem[], ...wanted: string[]) =>
  problems.filter((p) => wanted.includes(p.code)).map((p) => ({ code: p.code, level: p.level, step: p.step ?? null, field: p.field ?? null, message: p.message }))

const toolMessage = (tool: string, other: string) => `MCP tool ${tool} is already exposed by workflow ${other}; give this workflow another noun or verb, or set mcp.expose to false`

describe('what the checks read from the registry', () => {
  it('reads what each published workflow starts from the dependencies, each once and sorted', () => {
    expect(workflowRunsOf(DEPENDENCIES)).toEqual({ 'patch-cve': ['vm-restart'], 'web-environment': ['build-vm-image', 'create-database', 'create-vm'] })
    expect(workflowRunsOf({ workflows: [] })).toEqual({})
  })

  it("reads the MCP tools from the catalog: each exposed workflow's tool and its batch tool", () => {
    expect(mcpToolsOf(CATALOG)).toEqual({
      vm_create: ['create-vm'],
      vm_create_batch: ['create-vm'],
      vm_restart: ['vm-reboot', 'vm-restart'],
      vm_restart_batch: ['vm-reboot', 'vm-restart'],
    })
  })

  it('reports a run that comes back to the workflow through the ones the registry says it starts (run.cycle)', () => {
    const doc = draft([{ reboot: { name: 'Patch first', run: { workflow: 'patch-cve', version: '^1', input: { vmId: 'vm-1' } } } }])
    const message = 'Runs go round in a cycle: vm-restart -> patch-cve -> vm-restart; a job would wait for itself and never finish'
    expect(only(check(doc, contextOf(registryWith({ workflowRuns: workflowRunsOf(DEPENDENCIES) }))), 'run.cycle')).toEqual([{ code: 'run.cycle', level: 'error', step: 'reboot', field: 'workflow', message }])
    // without the dependencies the cycle is left to the backend's checks
    expect(only(check(doc, contextOf(registryWith())), 'run.cycle')).toEqual([])
  })

  it('warns when another published workflow holds the MCP tool the noun and verb give (mcp.toolName)', () => {
    const withTools = contextOf(registryWith({ mcpTools: mcpToolsOf(CATALOG) }))
    expect(only(check(draft([], 'create'), withTools), 'mcp.toolName')).toEqual([{ code: 'mcp.toolName', level: 'warning', step: null, field: 'mcp.expose', message: toolMessage('vm_create', 'create-vm') }])
    // its own name is no clash, another workflow's under the same name is
    expect(only(check(draft([]), withTools), 'mcp.toolName').map((p) => p.message)).toEqual([toolMessage('vm_restart', 'vm-reboot')])
    expect(only(check(draft([], 'reboot'), withTools), 'mcp.toolName')).toEqual([])
    // without the catalog nothing is judged
    expect(only(check(draft([], 'create'), contextOf(registryWith())), 'mcp.toolName')).toEqual([])
  })

  it("judges a run step's contract only against the version its range starts", () => {
    const run = (range: string) => draft([{ vm: { name: 'Create VM', run: { workflow: 'create-vm', version: range, input: { size: 'small' } } } }])
    const ctx = contextOf(registryWith())
    expect(ctx.workflowVersions).toEqual({ 'patch-cve': '1.0.0', 'create-vm': '2.1.0' })
    expect(only(check(run('^2'), ctx), 'contract.missingInput', 'contract.unknownInput').map((p) => p.code)).toEqual(['contract.missingInput', 'contract.unknownInput'])
    // ^1 starts an older major the registry does not describe: the backend judges it against that one
    expect(only(check(run('^1'), ctx), 'contract.missingInput', 'contract.unknownInput')).toEqual([])
  })

  it('loads the dependencies and the catalog with the rest; when they do not answer, the canvas lists miss nothing', async () => {
    const answer = (ok: boolean) =>
      vi.stubGlobal('fetch', async (input: RequestInfo | URL) => {
        const url = String(input)
        if (url.endsWith('/registry/dependencies')) return ok ? Response.json(DEPENDENCIES) : new Response('{"error":"down"}', { status: 503 })
        if (url.endsWith('/catalog')) return ok ? Response.json(CATALOG) : new Response('{"error":"down"}', { status: 503 })
        if (url.includes('/registry/workflows')) return Response.json(registryWith().workflows)
        return Response.json([])
      })
    answer(true)
    const loaded = await loadRegistry(true)
    expect(loaded.failed).toEqual([])
    expect(loaded.workflowRuns).toEqual(workflowRunsOf(DEPENDENCIES))
    expect(loaded.mcpTools).toEqual(mcpToolsOf(CATALOG))
    expect(contextOf(loaded)).toMatchObject({ workflowRuns: loaded.workflowRuns, mcpTools: loaded.mcpTools, workflowVersions: { 'create-vm': '2.1.0' } })

    answer(false)
    const partial = await loadRegistry(true)
    expect(partial.failed).toEqual([])
    expect(partial.workflows.map((w) => w.name)).toEqual(['patch-cve', 'create-vm'])
    expect('workflowRuns' in partial || 'mcpTools' in partial).toBe(false)
    const ctx = contextOf(partial)
    expect(ctx.workflowRuns).toBeUndefined()
    expect(ctx.mcpTools).toBeUndefined()
  })
})

/*
 * The providers a workflow uses before they are published (its drafts, the ones in review), read
 * by the canvas as published ones.
 */
describe('the unpublished providers of a workflow', () => {
  const published: RegistryProvider = { name: 'backup', title: 'Backup', type: 'http', owner: 'team-data', actions: [{ id: 'snapshot.list', title: 'List', effect: 'read', retry: 'safe' }] }
  const vsphere: RegistryProvider = { name: 'vsphere', title: 'vSphere', type: 'openapi', actions: [{ id: 'vm.get', title: 'Get VM', effect: 'read', retry: 'safe' }] }
  const draftOf = (name: string, title: string): RegistryProvider => ({
    name,
    title,
    type: 'http',
    owner: 'team-compute',
    unpublished: 'draft',
    actions: [{ id: 'snapshot.create', title: 'Create snapshot', effect: 'create', retry: 'unsafe', approval: 'none', input: { type: 'object', required: ['vm'], properties: { vm: { type: 'string' } } } }],
  })

  it('join the registry, each in place of a published provider of its name, the first of a name winning', () => {
    const registry = { ...EMPTY_REGISTRY, providers: [published, vsphere] }
    const merged = withUnpublished(registry, [draftOf('backup', 'Backup next'), draftOf('backup', 'Backup again'), draftOf('', 'Unnamed'), draftOf('dns', 'DNS')])
    expect(merged.providers.map((p) => [p.name, p.title])).toEqual([
      ['vsphere', 'vSphere'],
      ['backup', 'Backup next'],
      ['dns', 'DNS'],
    ])
    expect(withUnpublished(registry, [])).toBe(registry)
  })

  it("keep the published provider's module when they name none, as the draft of a terraform provider's next patch", () => {
    const module = { source: 'https://example.com/acme/tf-modules//network', version: '1.0.0' }
    const network: RegistryProvider = { name: 'network', title: 'Network', type: 'terraform', actions: [{ id: 'plan', title: 'Plan', effect: 'read', retry: 'safe' }], module }
    const edit: RegistryProvider = { name: 'network', title: 'Network next', type: 'terraform', actions: network.actions, unpublished: 'draft' }
    const merged = withUnpublished({ ...EMPTY_REGISTRY, providers: [network] }, [edit])
    expect(merged.providers).toEqual([{ ...edit, module }])
    // a draft's own module stands
    const own = { source: 'https://example.com/acme/tf-modules//network2' }
    expect(withUnpublished({ ...EMPTY_REGISTRY, providers: [network] }, [{ ...edit, module: own }]).providers[0].module).toEqual(own)
    expect(withUnpublished(EMPTY_REGISTRY, [edit]).providers[0].module).toBeUndefined()
  })

  it('are named with their tag wherever the designer names a provider', () => {
    expect(providerLabel(draftOf('backup', 'Backup API'))).toBe('Backup API (draft)')
    expect(providerLabel({ ...draftOf('backup', 'Backup API'), unpublished: 'in review' })).toBe('Backup API (in review)')
    expect(providerLabel(vsphere)).toBe('vSphere')
  })

  it('are judged by the live checks as published ones: their actions and their input schemas', () => {
    const doc = draft([{ snap: { name: 'Snapshot', call: 'provider', with: { provider: 'backup', action: 'snapshot.create', input: {} } } }])
    const before = check(doc, contextOf({ ...EMPTY_REGISTRY, providers: [vsphere] })).map((p) => p.code)
    expect(before).toContain('step.unknownProvider')
    const after = check(doc, contextOf(withUnpublished({ ...EMPTY_REGISTRY, providers: [vsphere] }, [draftOf('backup', 'Backup API')])))
    expect(after.map((p) => p.code)).not.toContain('step.unknownProvider')
    expect(only(after, 'step.missingInput').map((p) => p.message)).toEqual(["Action snapshot.create needs 'vm'"])
  })
})
