/**
 * For the node detail view's tests: the vsphere provider as GET /providers/{name} describes it,
 * from the repository's own file, and the step info the views take.
 */
import { parseSteps, type ParsedStep } from '@iaf/workflow-checks'
import type { ActionInfo, ProviderDetail } from '../../lib/types'
import { draftFromProvider, newProviderDraft, type ProviderDraft } from '../model/providerDrafts'
import type { ProviderStepInfo } from './providerStep'

export const VSPHERE_YAML = Object.values(import.meta.glob('../../../../providers/team-compute/vsphere.yaml', { query: '?raw', import: 'default', eager: true }) as Record<string, string>)[0] ?? ''

const ACTIONS: ActionInfo[] = [
  { id: 'vm.get', title: 'Get VM', operation: 'getVm', effect: 'read', retry: 'safe', input: { type: 'object', required: ['id'], properties: { id: { type: 'string' } } }, output: { type: 'object', properties: { id: { type: 'string' }, powerState: { type: 'string' } } }, overridden: false, slo: null },
  { id: 'vm.list', title: 'List VMs', operation: 'listVms', effect: 'read', retry: 'safe', input: { type: 'object' }, output: { type: 'object' }, overridden: false },
  { id: 'vm.delete', title: 'Delete VM', operation: 'deleteVm', effect: 'delete', retry: 'unsafe', approval: 'required', plan: 'vm.get', input: { type: 'object', required: ['id'], properties: { id: { type: 'string', 'x-resource': { type: 'vm' } } } }, output: { type: 'object' }, overridden: false },
]

export function vsphereDetail(patch: Partial<ProviderDetail> = {}): ProviderDetail {
  return {
    name: 'vsphere',
    title: 'VMware vSphere',
    type: 'openapi',
    owner: 'team-compute',
    version: '1.3.0',
    environments: ['dev', 'test', 'prod'],
    endpoints: { dev: 'https://vcenter-dev.example.com', test: 'https://vcenter-test.example.com', prod: 'https://vcenter.example.com' },
    auth: { type: 'bearer', credential: 'vault:vsphere/token' },
    actionCount: ACTIONS.length,
    health: { status: 'degraded', successRate: 97.2, p50Ms: 220, p95Ms: 1350, calls: 1240, errorTrend: [2, 3, 9], reasons: ['p95 1.35 s is above the SLO of 800 ms'] },
    slo: { successRate: 99.5, p95Ms: 800, channel: '#compute-alerts' },
    alerts: { channel: '#compute-alerts' },
    yaml: VSPHERE_YAML,
    actions: ACTIONS,
    daily: [],
    perAction: [{ action: 'vm.get', calls: 812, successRate: 96.1, p50Ms: 210, p95Ms: 1500, topErrors: [], slo: { successRate: 99.5, p95Ms: 800 }, band: 'failing' }],
    changeRequests: [
      { id: 'cr_41', kind: 'provider', name: 'vsphere', version: '1.3.1', branch: 'provider/vsphere-1.3.1', title: 'The SLO of VMware vSphere', author: 'alice', status: 'open', checks: [], strategy: 'canary', createdAt: '2026-10-18T08:00:00Z' },
      { id: 'cr_30', kind: 'provider', name: 'vsphere', version: '1.3.0', branch: 'provider/vsphere-1.3.0', title: 'vsphere 1.3.0', author: 'alice', status: 'merged', checks: [], strategy: 'canary', createdAt: '2026-10-01T08:00:00Z' },
    ],
    ...patch,
  }
}

/** The step info of a published provider: loaded, and with the workflow's draft of it when given. */
export function publishedInfo(action = 'vm.get', draft?: ProviderDraft, detail: ProviderDetail = vsphereDetail()): ProviderStepInfo {
  return { provider: 'vsphere', action, draft, published: true, registryProvider: undefined, detail, detailLoading: false, detailError: null }
}

/** The step info of a published provider whose detail has not answered yet (or failed). */
export function loadingInfo(error: unknown = null): ProviderStepInfo {
  return { provider: 'vsphere', action: 'vm.get', draft: undefined, published: true, registryProvider: undefined, detail: undefined, detailLoading: error === null, detailError: error }
}

/** An edit draft of vsphere, the published file at the next patch. */
export const editDraft = (): ProviderDraft => draftFromProvider(vsphereDetail())

/** A new http provider the sheet writes from its state, with one action. */
export function newDraft(): ProviderDraft {
  const d = newProviderDraft('http', { name: 'billing', title: 'Billing', owner: 'team-platform', environments: [{ key: 1, name: 'dev', url: 'https://billing-dev.example.com' }] })
  return { ...d, actions: d.actions.map((a, i) => (i === 0 ? { ...a, id: 'invoices.get', title: 'Get invoice', method: 'GET', path: '/invoices/{id}', inputText: '{ "type": "object", "properties": { "id": { "type": "string" } } }' } : a)) }
}

export function newDraftInfo(draft: ProviderDraft = newDraft()): ProviderStepInfo {
  return { provider: draft.name, action: 'invoices.get', draft, published: false, registryProvider: undefined, detail: undefined, detailLoading: false, detailError: null }
}

/** A provider step as the canvas parses one. */
export function providerStep(provider: string, action: string, input: Record<string, unknown>): ParsedStep {
  return parseSteps([{ check: { name: 'Check', call: 'provider', with: { provider, action, input } } }], 'workflow.do')[0]
}
