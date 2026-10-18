import type { Page } from '@playwright/test'
import type {
  Approval,
  AuditVerification,
  CatalogEntry,
  ChangeRequestDetail,
  Credential,
  EvidenceRecord,
  JobDetail,
  JobSummary,
  Lineage,
  Me,
  Preview,
  ProviderAlert,
  ProviderDetail,
  ProviderSummary,
  ProviderValidation,
  Release,
  Resource,
  ResourceType,
  SpecImport,
  TestConnectionResult,
  TryResult,
  WorkflowSummary,
} from '../src/lib/types'

/*
 * A mocked /api/v1 for the page specs: fixture JSON in the shapes of docs/api.md, filled with
 * the sample content (vm-restart, vsphere, the backup service, images and VMs), and a tiny router
 * that answers `METHOD /path/{param}` keys and records every call.
 */

/* ----------------------------------------------------------------------------
   The router
   ------------------------------------------------------------------------- */

const REPLY = Symbol('reply')

export interface Reply {
  [REPLY]: true
  status: number
  json?: unknown
  body?: string
  contentType?: string
}

export interface MockRequest {
  method: string
  path: string
  query: URLSearchParams
  params: Record<string, string>
  body: unknown
}

export type Handler = (req: MockRequest) => unknown
export type RouteValue = Handler | object | string | number | boolean | null
export type Routes = Record<string, RouteValue>

export interface Call {
  method: string
  path: string
  query: string
  body: unknown
}

export interface ApiMock {
  calls: Call[]
  /** the calls to one `METHOD /path` (exact path, no query) */
  callsTo: (method: string, path: string) => Call[]
}

/** A reply with a status other than 200, or no body. */
export const reply = (status: number, json?: unknown): Reply => ({ [REPLY]: true, status, json })

/** A server-sent event stream that tells the browser not to reconnect for ten minutes. */
export const sse = (events: { event: string; data: unknown; id?: number }[]): Reply => ({
  [REPLY]: true,
  status: 200,
  contentType: 'text/event-stream',
  body: 'retry: 600000\n\n' + events.map((e) => `${e.id !== undefined ? `id: ${e.id}\n` : ''}event: ${e.event}\ndata: ${JSON.stringify(e.data)}\n\n`).join(''),
})

const isReply = (v: unknown): v is Reply => typeof v === 'object' && v !== null && REPLY in v

function compile(key: string): { method: string; regex: RegExp; names: string[] } {
  const [method, pattern] = key.split(' ')
  const names: string[] = []
  const source = pattern
    .split(/(\{\w+\})/)
    .map((part) => {
      const m = /^\{(\w+)\}$/.exec(part)
      if (m) {
        names.push(m[1])
        return '([^/:]+)'
      }
      return part.replace(/[.*+?^$()|[\]\\]/g, '\\$&')
    })
    .join('')
  return { method, regex: new RegExp(`^${source}$`), names }
}

/** Routes every /api/v1 call through `routes` (over the defaults); anything unknown is a 404 that names itself. */
export async function mockApi(page: Page, routes: Routes = {}): Promise<ApiMock> {
  const table = Object.entries({ ...BASE_ROUTES, ...routes }).map(([key, value]) => ({ ...compile(key), value }))
  const calls: Call[] = []
  await page.route('**/api/v1/**', async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    const path = url.pathname.replace(/^\/api\/v1/, '')
    const method = request.method()
    let body: unknown
    try {
      body = request.postDataJSON()
    } catch {
      body = request.postData()
    }
    calls.push({ method, path, query: url.search, body })
    for (const entry of table) {
      if (entry.method !== method) continue
      const m = entry.regex.exec(path)
      if (!m) continue
      const params = Object.fromEntries(entry.names.map((n, i) => [n, decodeURIComponent(m[i + 1])]))
      const value = typeof entry.value === 'function' ? await (entry.value as Handler)({ method, path, query: url.searchParams, params, body }) : entry.value
      if (isReply(value)) {
        if (value.body !== undefined) return route.fulfill({ status: value.status, contentType: value.contentType ?? 'application/json', body: value.body })
        return value.json === undefined ? route.fulfill({ status: value.status, body: '' }) : route.fulfill({ status: value.status, json: value.json })
      }
      return route.fulfill({ status: 200, json: value })
    }
    return route.fulfill({ status: 404, json: { error: `Not mocked: ${method} ${path}` } })
  })
  return { calls, callsTo: (method, path) => calls.filter((c) => c.method === method && c.path === path) }
}

/* ----------------------------------------------------------------------------
   Fixtures
   ------------------------------------------------------------------------- */

const ago = (minutes: number) => new Date(Date.now() - minutes * 60_000).toISOString()
const day = (daysAgo: number) => new Date(Date.now() - daysAgo * 86_400_000).toISOString().slice(0, 10)
const hash = (seed: string) => seed.repeat(64).slice(0, 64)

export const ME: Me = { id: 'gonzalo', name: 'Gonzalo', email: 'gonzalo@example.com', teams: ['team-platform'], roles: ['ops', 'admin'], admin: true, kind: 'user' }

const BASE_ROUTES: Routes = {
  'GET /me': ME,
}

export const WORKFLOWS: WorkflowSummary[] = [
  { name: 'vm-restart', title: 'VM restart', noun: 'vm', verb: 'restart', persona: 'ops', folder: 'Compute', owner: 'team-compute', tags: ['vsphere', 'ops'], status: 'published', latestVersion: '1.2.0', publishedVersion: '1.2.0', stepCount: 6, updatedAt: ago(60 * 26), mcp: true },
  { name: 'create-vm', title: 'Create a VM', noun: 'vm', verb: 'create', folder: 'Compute', owner: 'team-compute', tags: ['vsphere'], status: 'published', latestVersion: '2.0.0', publishedVersion: '2.0.0', stepCount: 7, updatedAt: ago(60 * 72), mcp: true },
  { name: 'build-vm-image', title: 'Build a VM image', noun: 'image', verb: 'build', folder: 'Platform', owner: 'team-platform', tags: ['packer'], status: 'published', latestVersion: '1.1.0', publishedVersion: '1.0.0', stepCount: 5, updatedAt: ago(90), mcp: false },
  { name: 'patch-cve', title: 'Patch a CVE', noun: 'cve', verb: 'patch', folder: 'Platform', owner: 'team-platform', tags: ['ansible'], status: 'draft', latestVersion: '0.3.0', stepCount: 4, updatedAt: ago(15), mcp: false },
]

export const CATALOG: CatalogEntry[] = [
  {
    workflow: 'vm-restart',
    title: 'VM restart',
    noun: 'vm',
    verb: 'restart',
    persona: 'ops',
    method: 'POST',
    path: '/api/v1/vms/{vmId}:restart',
    batchPath: '/api/v1/vms:restart:batch',
    previewPath: '/api/v1/vms/{vmId}:restart:preview',
    input: {
      type: 'object',
      required: ['vmId'],
      properties: {
        vmId: { type: 'string', title: 'VM id', description: 'The vSphere VM id, for example vm-1042', 'x-resource': { type: 'vm' } },
        reason: { type: 'string', title: 'Reason' },
        environment: { type: 'string', title: 'Environment', enum: ['dev', 'test', 'prod'], default: 'dev' },
      },
    },
    mcpTool: 'restart_vm',
    owner: 'team-compute',
    version: '1.2.0',
    description: 'Restart a virtual machine after safety checks, with an approval for production.',
    batch: { max: 500, rollout: { waves: [5, 25, 100], maxParallel: 10, stopIfFailedPercent: 5 } },
  },
  {
    workflow: 'create-vm',
    title: 'Create a VM',
    noun: 'vm',
    verb: 'create',
    method: 'POST',
    path: '/api/v1/vms',
    batchPath: '/api/v1/vms:batch',
    input: { type: 'object', required: ['name'], properties: { name: { type: 'string', title: 'Name' }, cpu: { type: 'integer', default: 2 }, memoryGb: { type: 'integer', default: 8 } } },
    mcpTool: 'create_vm',
    owner: 'team-compute',
    version: '2.0.0',
    description: 'A VM from a hardened image, registered with its lineage.',
    batch: { max: 100, rollout: { waves: [10, 100], maxParallel: 5, stopIfFailedPercent: 10 } },
  },
]

export const RESOURCE_TYPES: ResourceType[] = [
  { name: 'image', plural: 'images', owner: 'team-platform', schema: { type: 'object', required: ['templateId', 'os'], properties: { templateId: { type: 'string' }, os: { type: 'string' } } }, states: ['building', 'ready', 'deprecated', 'retired'], producedBy: 'build-vm-image' },
  { name: 'vm', plural: 'vms', owner: 'team-compute', schema: { type: 'object', required: ['name'], properties: { name: { type: 'string' }, image: { type: 'string' } } }, states: ['building', 'ready', 'deprecated', 'retired'], producedBy: 'create-vm' },
]

const resource = (id: string, type: string, name: string, extra: Partial<Resource> = {}): Resource => ({
  id,
  type,
  name,
  status: 'ready',
  attributes: {},
  owner: type === 'vm' ? 'team-compute' : 'team-platform',
  visibility: 'org',
  createdBy: { jobId: `job-${id}`, workflow: type === 'vm' ? 'create-vm' : 'build-vm-image' },
  builtFrom: [],
  createdAt: ago(60 * 24 * 3),
  ...extra,
})

export const BASE_IMAGE = resource('img-60', 'image', 'ubuntu-2404', { attributes: { templateId: 'tpl-ubuntu-2404', os: 'ubuntu-2404' } })
export const IMAGE = resource('img-77', 'image', 'ubuntu-2404-cis1', { attributes: { templateId: 'tpl-ubuntu-2404-cis1', os: 'ubuntu-2404', hardening: 'cis1', sizeGb: 12 }, builtFrom: ['img-60'] })
export const VMS: Resource[] = [
  resource('vm-1042', 'vm', 'web-1042', { attributes: { cpu: 4, memoryGb: 16, environment: 'prod', image: 'img-77' }, builtFrom: ['img-77'] }),
  resource('vm-1043', 'vm', 'web-1043', { attributes: { cpu: 4, memoryGb: 16, environment: 'prod', image: 'img-77' }, builtFrom: ['img-77'] }),
]
export const IMAGE_LINEAGE: Lineage = { resource: IMAGE, builtFrom: [BASE_IMAGE], dependents: VMS }

export const PREVIEW: Preview = {
  workflow: 'vm-restart',
  version: '1.2.0',
  steps: [
    { id: 'get-vm', name: 'Read the VM', effect: 'read' },
    { id: 'check-backup', name: 'Check no backup is running', effect: 'read' },
    { id: 'approve', name: 'Approval from compute-approvers', effect: 'none' },
    { id: 'restart', name: 'Restart the VM', effect: 'change', detail: 'vsphere vm.restart' },
  ],
  affectedResources: [{ id: 'vm-1042', type: 'vm', name: 'web-1042', via: 'restart' }],
  policy: { decision: 'needs-approval', rules: [{ id: 'prod-approval', result: 'needs-approval', message: 'Production changes need compute-approvers' }], engine: 'opa' },
}

export const JOBS: JobSummary[] = [
  { id: 'job-7f3a2c91', workflow: 'vm-restart', title: 'Restart web-1042', version: '1.2.0', status: 'needs-attention', requester: 'gonzalo', channel: 'catalog', startedAt: ago(9), kind: 'single' },
  { id: 'job-5b10e7d2', workflow: 'patch-cve', title: 'Patch CVE-2026-1234 on 20 servers', version: '0.3.0', status: 'running', requester: 'maria', channel: 'batch', startedAt: ago(30), kind: 'batch', progress: { done: 6, total: 20, failed: 1 } },
  { id: 'job-2c44a810', workflow: 'create-vm', title: 'Create web-1043', version: '2.0.0', status: 'succeeded', requester: 'gonzalo', channel: 'mcp', startedAt: ago(60 * 5), endedAt: ago(60 * 5 - 4), durationMs: 240_000, kind: 'single' },
]

export const JOB: JobDetail = {
  ...JOBS[0],
  input: { vmId: 'vm-1042', reason: 'kernel patch', environment: 'prod' },
  steps: [
    { id: 'get-vm', name: 'Read the VM', type: 'provider', status: 'succeeded', attempt: 1, startedAt: ago(9), endedAt: ago(9), durationMs: 420, input: { id: 'vm-1042' }, output: { id: 'vm-1042', powerState: 'poweredOn' } },
    { id: 'check-backup', name: 'Check no backup is running', type: 'provider', status: 'succeeded', attempt: 1, startedAt: ago(9), endedAt: ago(9), durationMs: 380, output: { items: [], total: 0 } },
    { id: 'approve', name: 'Approval from compute-approvers', type: 'approval', status: 'succeeded', attempt: 1, startedAt: ago(9), endedAt: ago(6), durationMs: 180_000 },
    { id: 'restart', name: 'Restart the VM', type: 'provider', status: 'failed', attempt: 3, startedAt: ago(6), endedAt: ago(5), durationMs: 31_000, input: { id: 'vm-1042' }, error: { class: 'timeout', message: 'vCenter did not answer in 30s', retryable: true } },
    { id: 'wait-on', name: 'Wait for power on', type: 'provider', status: 'pending', attempt: 0 },
  ],
  approvals: [],
  preview: PREVIEW,
  attention: { step: 'restart', error: 'vCenter did not answer in 30s', options: ['retry', 'edit-input', 'skip', 'abort'], since: ago(5), timeout: 'PT4H' },
  traceId: '4bf92f3577b34da6a3ce929d0e0e4736',
}

export const APPROVALS: Approval[] = [
  {
    id: 'ap-1',
    jobId: 'job-9d21aa07',
    workflow: 'vm-restart',
    step: 'approve',
    group: 'compute-approvers',
    message: 'Restart web-1042 in prod for a kernel patch.',
    requestedAt: ago(12),
    timeout: 'PT4H',
    status: 'pending',
    preview: PREVIEW,
  },
]

const daily = Array.from({ length: 14 }, (_, i) => ({ date: day(13 - i), calls: 900 + ((i * 137) % 400), successRate: 99.1 + ((i * 7) % 8) / 10, p50Ms: 180 + ((i * 13) % 40), p95Ms: 640 + ((i * 29) % 160) }))

export const PROVIDERS: ProviderSummary[] = [
  {
    name: 'vsphere',
    title: 'VMware vSphere',
    type: 'openapi',
    owner: 'team-compute',
    version: '1.3.0',
    environments: ['dev', 'test', 'prod'],
    actionCount: 3,
    health: { status: 'healthy', successRate: 99.6, p50Ms: 190, p95Ms: 720, calls: 15_480, errorTrend: [1, 0, 2, 1, 0, 0, 1], reasons: [] },
    slo: { successRate: 99.5, p95Ms: 800 },
    alerts: { channel: '#compute-alerts' },
  },
  {
    name: 'backup',
    title: 'Backup service',
    type: 'http',
    owner: 'team-platform',
    version: '1.0.0',
    environments: ['dev', 'test', 'prod'],
    actionCount: 2,
    health: { status: 'degraded', successRate: 97.2, p50Ms: 420, p95Ms: 1900, calls: 2_210, errorTrend: [2, 3, 5, 8, 6, 9, 11], reasons: ['p95 1.9s is over the 1.2s SLO', 'rate_limited errors are rising'] },
    slo: { successRate: 99.0, p95Ms: 1200 },
    alerts: { channel: '#platform-alerts' },
  },
]

export const PROVIDER: ProviderDetail = {
  ...PROVIDERS[0],
  yaml: 'apiVersion: iaf.platform/v1\nkind: Provider\nmetadata:\n  name: vsphere\n  title: VMware vSphere\n  owner: team-compute\n  version: 1.3.0\nspec:\n  type: openapi\n',
  actions: [
    { id: 'vm.get', title: 'Get VM', operation: 'getVm', effect: 'read', retry: 'safe', input: { type: 'object', required: ['id'], properties: { id: { type: 'string' } } }, output: { type: 'object', properties: { id: { type: 'string' }, powerState: { type: 'string' } } }, sample: { id: 'vm-1042', powerState: 'poweredOn' }, overridden: false },
    { id: 'vm.restart', title: 'Restart VM', operation: 'restartVm', effect: 'change', retry: 'unsafe', input: { type: 'object', required: ['id'], properties: { id: { type: 'string' } } }, output: { type: 'object' }, overridden: false },
    { id: 'vm.delete', title: 'Delete VM', operation: 'deleteVm', effect: 'delete', retry: 'unsafe', approval: 'required', plan: 'vm.get', input: { type: 'object', required: ['id'], properties: { id: { type: 'string' } } }, output: { type: 'object' }, overridden: true },
  ],
  daily,
  perAction: [
    { action: 'vm.get', calls: 11_200, successRate: 99.9, p50Ms: 120, p95Ms: 380, topErrors: [] },
    { action: 'vm.restart', calls: 3_900, successRate: 98.9, p50Ms: 2_400, p95Ms: 9_800, topErrors: [{ class: 'timeout', count: 31 }] },
    { action: 'vm.delete', calls: 380, successRate: 99.5, p50Ms: 1_800, p95Ms: 6_200, topErrors: [{ class: 'not_found', count: 2 }] },
  ],
  changeRequests: [
    { id: 'cr-88', kind: 'provider', name: 'vsphere', version: '1.4.0', branch: 'provider/vsphere-1.4.0', title: 'vsphere: add vm.snapshot', author: 'maria', status: 'open', checks: [], strategy: 'canary', createdAt: ago(60 * 3) },
  ],
}

export const PROVIDER_ALERTS: ProviderAlert[] = [{ id: 'al-1', at: ago(40), action: 'vm.restart', message: '3 timeouts in 10 minutes', severity: 'warning', jobId: 'job-7f3a2c91' }]

export const CREDENTIALS: Credential[] = [
  { name: 'backup/api-key', type: 'api-key', path: 'vault:backup/api-key', updatedAt: ago(60 * 24 * 10) },
  { name: 'vsphere/token', type: 'token', path: 'vault:vsphere/token', updatedAt: ago(60 * 24 * 30) },
]

export const SPEC: SpecImport = {
  title: 'Backup service',
  version: '1.0.0',
  sha256: hash('9f2c1b7e'),
  auth: { type: 'apiKey', where: 'X-Api-Key' },
  servers: ['https://backup-dev.example.com/api', 'https://backup.example.com/api'],
  operations: [
    { operationId: 'listJobs', method: 'get', path: '/jobs', summary: 'List backup jobs', effect: 'read', retry: 'safe', input: { type: 'object', properties: { target: { type: 'string' } } }, output: { type: 'object', properties: { items: { type: 'array' }, total: { type: 'integer' } } } },
    { operationId: 'createJob', method: 'post', path: '/jobs', summary: 'Start a backup', effect: 'create', retry: 'unsafe', input: { type: 'object', required: ['target'], properties: { target: { type: 'string' } } }, output: { type: 'object', properties: { id: { type: 'string' }, state: { type: 'string' } } } },
  ],
}

export const CONNECTION: TestConnectionResult = {
  results: [
    { environment: 'dev', ok: true, durationMs: 140, message: '200 from GET /jobs' },
    { environment: 'prod', ok: true, durationMs: 210, message: '200 from GET /jobs' },
  ],
}

export const TRY_RESULT: TryResult = { ok: true, status: 200, durationMs: 182, response: { items: [{ id: 'bk-9001', state: 'running', target: 'vm-1042' }], total: 1 }, mismatches: [] }

export const VALIDATION: ProviderValidation = {
  checks: [
    { id: 'schema', label: 'The file follows the provider schema', status: 'passed' },
    { id: 'credentials', label: 'Credentials are Vault references', status: 'passed' },
    { id: 'reachable', label: 'Every environment answers', status: 'passed' },
  ],
  problems: [],
}

export const PROVIDER_CR: ChangeRequestDetail = {
  id: 'cr-101',
  kind: 'provider',
  name: 'backup-service',
  version: '1.0.0',
  branch: 'provider/backup-service-1.0.0',
  title: 'Add provider backup-service',
  author: 'gonzalo',
  status: 'open',
  checks: VALIDATION.checks,
  strategy: 'canary',
  createdAt: ago(0),
  diff: '',
  yaml: 'apiVersion: iaf.platform/v1\nkind: Provider\n',
}

export const RELEASES: Release[] = [
  {
    id: 'rel-1',
    workflow: 'vm-restart',
    from: '1.1.0',
    to: '1.2.0',
    strategy: 'canary',
    status: 'canary',
    stageIndex: 1,
    stages: [5, 25, 50, 100],
    stagePercent: 25,
    startedAt: ago(60 * 2),
    stageStartedAt: ago(35),
    gates: [
      { id: 'error-rate', label: 'Error rate under 2%', passed: true, value: 0.8, threshold: 2, detail: '2 failures in 240 jobs' },
      { id: 'p95', label: 'p95 under 1.5× the current version', passed: true, value: 1.1, threshold: 1.5, detail: 'p95 9.1s against 8.3s' },
      { id: 'soak', label: 'At least 30 minutes in the stage', passed: null, detail: 'Evaluated at the end of the stage' },
    ],
    jobs: { current: 180, candidate: 60 },
    history: [
      { at: ago(60 * 2), event: 'Canary started at 5%', by: 'gonzalo' },
      { at: ago(35), event: 'Promoted to 25%: every gate passed' },
    ],
  },
  {
    id: 'rel-0',
    workflow: 'create-vm',
    from: '1.9.0',
    to: '2.0.0',
    strategy: 'canary',
    status: 'promoted',
    stageIndex: 3,
    stages: [5, 25, 50, 100],
    stagePercent: 100,
    startedAt: ago(60 * 24 * 4),
    stageStartedAt: ago(60 * 24 * 3),
    endedAt: ago(60 * 24 * 3),
    gates: [],
    jobs: { current: 0, candidate: 412 },
    history: [],
  },
]

export const EVIDENCE: EvidenceRecord = {
  jobId: 'job-7f3a2c91',
  workflow: 'vm-restart',
  version: '1.2.0',
  requester: 'gonzalo',
  channel: 'catalog',
  entries: [
    { seq: 1, at: ago(9), kind: 'requested', actor: 'gonzalo', data: { channel: 'catalog', workflow: 'vm-restart', version: '1.2.0' }, hash: hash('a1'), previous: hash('0') },
    { seq: 2, at: ago(9), kind: 'preview', data: { steps: PREVIEW.steps, affectedResources: PREVIEW.affectedResources }, hash: hash('b2'), previous: hash('a1') },
    { seq: 3, at: ago(9), kind: 'policy', data: { decision: 'needs-approval', engine: 'opa' }, hash: hash('c3'), previous: hash('b2') },
    { seq: 4, at: ago(6), kind: 'approval', actor: 'maria', data: { status: 'approved', decidedBy: 'maria', comment: 'Window agreed' }, hash: hash('d4'), previous: hash('c3') },
    { seq: 5, at: ago(5), kind: 'step', data: { name: 'Restart the VM', status: 'failed' }, hash: hash('e5'), previous: hash('d4') },
  ],
  latestHash: hash('e5'),
  sealed: { at: ago(2), signature: hash('5ea1'), keyId: 'audit-2026-10' },
  retention: { until: '2033-10-17T00:00:00Z' },
  change: { id: 'CHG-4521', url: 'https://itsm.example.com/CHG-4521' },
}

export const VERIFIED: AuditVerification = { valid: true, entries: 5 }
