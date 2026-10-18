import { afterEach, describe, expect, it, vi } from 'vitest'
import type { JsonObject, RegistryProvider, WorkflowDocument } from '../../lib/types'
import { terraformModuleIn, terraformModuleOf, withTerraformModules } from './registry'

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
