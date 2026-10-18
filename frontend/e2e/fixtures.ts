import type { Page } from '@playwright/test'
import type {
  AnsibleImport,
  Approval,
  AssistantSession,
  AuditVerification,
  CatalogEntry,
  ChangeRequestDetail,
  Credential,
  Dependencies,
  EvidenceEntry,
  EvidenceRecord,
  JobDetail,
  JobReplay,
  JobSummary,
  Lineage,
  Me,
  Notification,
  PlatformStream,
  Preview,
  Problem,
  ProviderAlert,
  ProviderDetail,
  ProviderDraft,
  ProviderSummary,
  ProviderValidation,
  Release,
  Resource,
  ResourceType,
  Schema,
  ServiceAccount,
  SpecImport,
  TerraformImport,
  TestConnectionResult,
  TryResult,
  WorkflowSummary,
  WorkflowVersion,
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
  headers?: Record<string, string>
}

export interface MockRequest {
  method: string
  path: string
  query: URLSearchParams
  params: Record<string, string>
  body: unknown
  /** the request headers, lower-cased names */
  headers: Record<string, string>
}

export type Handler = (req: MockRequest) => unknown
export type RouteValue = Handler | object | string | number | boolean | null
export type Routes = Record<string, RouteValue>

export interface Call {
  method: string
  path: string
  query: string
  body: unknown
  /** the request headers, lower-cased names */
  headers: Record<string, string>
}

export interface ApiMock {
  calls: Call[]
  /** the calls to one `METHOD /path` (exact path, no query) */
  callsTo: (method: string, path: string) => Call[]
}

/** A reply with a status other than 200, or no body; `headers` for what the client reads (Retry-After). */
export const reply = (status: number, json?: unknown, headers?: Record<string, string>): Reply => ({ [REPLY]: true, status, json, headers })

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
    const headers = request.headers()
    calls.push({ method, path, query: url.search, body, headers })
    for (const entry of table) {
      if (entry.method !== method) continue
      const m = entry.regex.exec(path)
      if (!m) continue
      const params = Object.fromEntries(entry.names.map((n, i) => [n, decodeURIComponent(m[i + 1])]))
      const value = typeof entry.value === 'function' ? await (entry.value as Handler)({ method, path, query: url.searchParams, params, body, headers }) : entry.value
      if (isReply(value)) {
        if (value.body !== undefined) return route.fulfill({ status: value.status, contentType: value.contentType ?? 'application/json', body: value.body, headers: value.headers })
        return value.json === undefined ? route.fulfill({ status: value.status, body: '', headers: value.headers }) : route.fulfill({ status: value.status, json: value.json, headers: value.headers })
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
    previewPath: '/api/v1/vms:preview',
    input: {
      type: 'object',
      required: ['name', 'image'],
      properties: {
        name: { type: 'string', title: 'Name' },
        // an x-resource input with orCreateWith: the id of an existing image, or `{ create }` to build one first
        image: {
          title: 'Image',
          description: 'The image to clone from',
          'x-resource': { type: 'image', orCreateWith: 'build-vm-image' },
          anyOf: [
            { type: 'string', pattern: '^img-', description: 'An existing image id from the registry' },
            {
              type: 'object',
              description: 'Build a new image with build-vm-image first; the job continues with its id',
              required: ['create'],
              additionalProperties: false,
              properties: {
                create: {
                  type: 'object',
                  required: ['os'],
                  properties: { os: { type: 'string', title: 'Operating system', enum: ['ubuntu-2404', 'rhel-9'] }, hardening: { type: 'string', title: 'Hardening', default: 'cis1' } },
                },
              },
            },
          ],
        },
        cpu: { type: 'integer', default: 2 },
        memoryGb: { type: 'integer', default: 8 },
      },
    },
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
/** Two hops from the image: a DNS record registered for web-1042. */
export const DNS_RECORD = resource('dns-1042', 'dns-record', 'web-1042.prod', { createdBy: { jobId: 'job-dns-1042', workflow: 'register-dns' }, builtFrom: ['vm-1042'] })
/** The backend's answer: the direct lists, and the whole graph around the image as nodes and edges. */
export const IMAGE_LINEAGE: Lineage = {
  resource: IMAGE,
  builtFrom: [BASE_IMAGE],
  dependents: VMS,
  nodes: [IMAGE, BASE_IMAGE, ...VMS, DNS_RECORD],
  edges: [
    { from: BASE_IMAGE.id, to: IMAGE.id, via: 'build-vm-image' },
    ...VMS.map((vm) => ({ from: IMAGE.id, to: vm.id, via: 'create-vm' })),
    { from: 'vm-1042', to: DNS_RECORD.id, via: 'register-dns' },
  ],
}

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
  attention: { step: 'restart', error: { class: 'timeout', message: 'vCenter did not answer in 30s', retryable: true }, options: ['retry', 'edit-input', 'skip', 'abort'], since: ago(5), timeout: 'PT4H' },
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
    health: { status: 'healthy', successRate: 99.6, p50Ms: 190, p95Ms: 720, calls: 15_480, errorTrend: [1, 0, 2, 1, 0, 0, 1, 0, 0, 2, 1, 0, 1, 0], reasons: [] },
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
    health: { status: 'degraded', successRate: 97.2, p50Ms: 420, p95Ms: 1900, calls: 2_210, errorTrend: [0, 1, 0, 2, 1, 3, 2, 3, 5, 8, 6, 9, 11, 12], reasons: ['p95 1.9s is over the 1.2s SLO', 'rate_limited errors are rising'] },
    slo: { successRate: 99.0, p95Ms: 1200 },
    alerts: { channel: '#platform-alerts' },
  },
]

export const PROVIDER: ProviderDetail = {
  ...PROVIDERS[0],
  endpoints: { dev: 'https://vsphere-dev.example.com/rest', test: 'https://vsphere-test.example.com/rest', prod: 'https://vsphere.example.com/rest' },
  auth: { type: 'header', credential: 'vault:vsphere/token', header: 'X-Api-Key' },
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

/** Two service accounts: a CI one with its scopes, and an older one the API reports no scopes for. */
export const SERVICE_ACCOUNTS: ServiceAccount[] = [
  { id: 'sa-ci', name: 'ci-deployer', roles: ['ops'], teams: ['team-platform'], createdAt: ago(60 * 24 * 5), scopes: ['registry:read', 'workflows:validate', 'workflows:dry-run', 'workflows:write', 'providers:write'] },
  { id: 'sa-bot', name: 'cost-bot', roles: ['sre'], createdAt: ago(60 * 24 * 40) },
]

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

/** The id input of a vCenter operation: a VM from the registry. */
const VM_ID: Schema = { type: 'object', required: ['id'], properties: { id: { type: 'string', title: 'VM', 'x-resource': { type: 'vm' } } } }

/** A spec with more operations than a glance takes in, one a delete whose input names a registry VM. */
export const VCENTER_SPEC: SpecImport = {
  title: 'vCenter',
  version: '8.0.2',
  sha256: hash('7d1e0c9a'),
  auth: { type: 'bearer' },
  servers: ['https://vcenter-dev.example.com/rest', 'https://vcenter.example.com/rest'],
  operations: [
    { operationId: 'listVms', method: 'get', path: '/vms', summary: 'List virtual machines', effect: 'read', retry: 'safe', input: { type: 'object', properties: { folder: { type: 'string' } } }, output: { type: 'object', properties: { items: { type: 'array' } } } },
    { operationId: 'getVm', method: 'get', path: '/vms/{id}', summary: 'Get a virtual machine', effect: 'read', retry: 'safe', input: VM_ID, output: { type: 'object', properties: { id: { type: 'string' }, powerState: { type: 'string' } } } },
    { operationId: 'deleteVm', method: 'delete', path: '/vms/{id}', summary: 'Delete a virtual machine', effect: 'delete', retry: 'unsafe', input: VM_ID, output: { type: 'object' } },
    { operationId: 'listSnapshots', method: 'get', path: '/vms/{id}/snapshots', summary: 'List the snapshots of a VM', effect: 'read', retry: 'safe', input: VM_ID, output: { type: 'object' } },
    { operationId: 'createSnapshot', method: 'post', path: '/vms/{id}/snapshots', summary: 'Take a snapshot', effect: 'create', retry: 'unsafe', input: VM_ID, output: { type: 'object' } },
    { operationId: 'listDatastores', method: 'get', path: '/datastores', summary: 'List datastores', effect: 'read', retry: 'safe', input: { type: 'object' }, output: { type: 'object' } },
  ],
}

/** A VM made to be deleted by a try: the registry's test resource in dev. */
export const TEST_VM = resource('vm-dev-7', 'vm', 'scratch-7', { attributes: { cpu: 1, memoryGb: 2, environment: 'dev' } })

/** A destructive try in dev, kept as review evidence and verified gone afterwards. */
export const TRY_DELETE: TryResult = { ok: true, status: 204, durationMs: 310, response: {}, mismatches: [], verified: true, environment: 'dev', at: ago(0) }

/** Ask Devin's answer: the provider file it drafted from a description. */
export const PROVIDER_DRAFT: ProviderDraft = {
  summary: 'An HTTP provider for the backup service with two actions.',
  assumptions: ['The API key goes in the X-Api-Key header.', 'Starting a backup is not safe to retry.'],
  yaml: [
    'apiVersion: iaf.platform/v1',
    'kind: Provider',
    'metadata:',
    '  name: backup',
    '  title: Backup service',
    '  owner: team-platform',
    '  version: 1.0.0',
    'spec:',
    '  type: http',
    '  endpoints:',
    '    dev: https://backup-dev.example.com/api',
    '  auth: { type: header, header: X-Api-Key, credential: vault:backup/api-key }',
    '  actions:',
    '    - id: jobs.list',
    '      operation: { method: GET, path: /jobs }',
    '      effect: read',
    '    - id: jobs.create',
    '      operation: { method: POST, path: /jobs }',
    '      effect: create',
    '      retry: unsafe',
    '',
  ].join('\n'),
}

export const CONNECTION: TestConnectionResult = {
  results: [
    { environment: 'dev', ok: true, durationMs: 140, message: '200 from GET /jobs' },
    { environment: 'prod', ok: true, durationMs: 210, message: '200 from GET /jobs' },
  ],
}

export const TRY_RESULT: TryResult = { ok: true, status: 200, durationMs: 182, response: { items: [{ id: 'bk-9001', state: 'running', target: 'vm-1042' }], total: 1 }, mismatches: [] }

/** A try the backend kept as review evidence, with the file (at the next patch) carrying the answer as the sample. */
export const TRY_RESULT_RECORDED: TryResult = {
  ...TRY_RESULT,
  environment: 'dev',
  at: ago(1),
  yaml: [
    'apiVersion: iaf.platform/v1',
    'kind: Provider',
    'metadata:',
    '  name: vsphere',
    '  title: VMware vSphere',
    '  owner: team-compute',
    '  version: 1.3.1',
    'spec:',
    '  type: openapi',
    '  actions:',
    '    - id: vm.get',
    '      sample:',
    '        id: vm-1042',
    '        powerState: poweredOn',
    '      recorded:',
    '        environment: dev',
    '        at: 2026-10-18T10:32:00Z',
    '',
  ].join('\n'),
}

export const VALIDATION: ProviderValidation = {
  checks: [
    { id: 'schema', label: 'The file follows the provider schema', status: 'passed' },
    { id: 'credentials', label: 'Credentials are Vault references', status: 'passed' },
    { id: 'reachable', label: 'Every environment answers', status: 'passed' },
  ],
  problems: [],
  review: {
    ready: true,
    checks: [
      { id: 'connection', label: 'The connection was tested against dev and test', status: 'passed', detail: 'the endpoints and the credential reference the file has now' },
      { id: 'tried', label: 'Every action answered when tried', status: 'passed', detail: '3 of 3 actions tried in dev' },
      { id: 'errors', label: 'Error classes match what was seen', status: 'warning', detail: 'No error classes: a tried error matches nothing' },
      { id: 'preview', label: 'Destructive actions have a plan or an effect line', status: 'passed' },
    ],
    problems: [],
    slo: { successRate: 99.5, p95Ms: 800, observedP95Ms: 410, calls: 128, detail: 'The p95 of the tried calls, doubled and rounded up' },
  },
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
      { id: 'success-rate', label: 'Success rate', passed: true, value: 99.2, threshold: 99, detail: '99.2% on the candidate vs 99.6% on the current version (may trail by 1 point)' },
      { id: 'p95', label: 'p95 duration', passed: true, value: 2200, threshold: 2_200_000, detail: '2.2s on the candidate vs 1.8s on the current version (may be 20% slower)' },
      { id: 'error-types', label: 'Error types', passed: false, value: 2, threshold: 0, detail: 'new on the candidate: timeout, upstream' },
      { id: 'sample', label: 'Sample size', passed: null, value: 12, threshold: 20, detail: '12 of 20 jobs; 35m of the 1h minimum' },
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

/* ----------------------------------------------------------------------------
   A run that built its input first (POST /vms with image: { create }), its preview, its replay
   ------------------------------------------------------------------------- */

/** The dry run of create-vm with an image to build first: a step the dry run skipped, the cost's source, where each resource comes from. */
export const CREATE_PREVIEW: Preview = {
  workflow: 'create-vm',
  version: '2.0.0',
  steps: [
    { id: 'image.create', name: 'Build image', effect: 'create', detail: 'build-vm-image ^1 with os rhel-9' },
    { id: 'clone', name: 'Clone from the image', effect: 'create', detail: 'vsphere vm.clone' },
    { id: 'tag', name: 'Tag for the cost centre', effect: 'change', skipped: true, detail: 'only when a cost centre is given' },
    { id: 'register', name: 'Register the VM', effect: 'create' },
  ],
  affectedResources: [
    { id: 'img-new', type: 'image', name: 'rhel-9 cis1', via: 'built from image.create' },
    { id: 'vm-1044', type: 'vm', name: 'web-1044', via: 'step register' },
  ],
  cost: { monthly: 115.5, currency: 'USD', detail: 'list-prices: 2 vCPU × 8 GB in eu-west, on demand' },
  policy: { decision: 'allow', rules: [], engine: 'opa' },
}

const CHILD_BUILD: JobSummary = { id: 'job-child-77', workflow: 'build-vm-image', title: 'Build ubuntu-2404 cis1', version: '1.0.0', status: 'succeeded', requester: 'gonzalo', channel: 'catalog', startedAt: ago(20), endedAt: ago(16), durationMs: 240_000, kind: 'single' }
const CHILD_WARM: JobSummary = { id: 'job-child-78', workflow: 'warm-cache', title: 'Warm the cache of web-1044', version: '1.1.0', status: 'failed', requester: 'gonzalo', channel: 'catalog', startedAt: ago(15), endedAt: ago(14), durationMs: 4_000, kind: 'single' }

/** A create-vm job: the image was built by a child job first, a later run step failed and the workflow continued. */
export const BUILD_JOB: JobDetail = {
  id: 'job-9b2e4f10',
  workflow: 'create-vm',
  title: 'Create web-1044',
  version: '2.0.0',
  status: 'succeeded',
  requester: 'gonzalo',
  channel: 'catalog',
  startedAt: ago(20),
  endedAt: ago(12),
  durationMs: 480_000,
  kind: 'single',
  input: { name: 'web-1044', image: { create: { os: 'ubuntu-2404', hardening: 'cis1' } }, cpu: 2, memoryGb: 8 },
  steps: [
    { id: 'image.create', name: 'Build image', type: 'run', status: 'succeeded', attempt: 1, startedAt: ago(20), endedAt: ago(16), durationMs: 240_000, input: { os: 'ubuntu-2404', hardening: 'cis1' }, output: { jobId: 'job-child-77', status: 'succeeded', outputs: { image: 'img-78' } } },
    { id: 'clone', name: 'Clone from the image', type: 'provider', status: 'succeeded', attempt: 1, startedAt: ago(16), endedAt: ago(15), durationMs: 60_000, input: { templateId: 'tpl-ubuntu-2404-cis1', name: 'web-1044' }, output: { id: 'vm-1044', host: 'esx-07' } },
    { id: 'warm-cache', name: 'Warm the cache', type: 'run', status: 'succeeded', attempt: 1, startedAt: ago(15), endedAt: ago(14), durationMs: 4_000, input: { vmId: 'vm-1044' }, output: { jobId: 'job-child-78', status: 'failed', outputs: {}, error: { step: 'fetch', message: 'origin answered 503', class: 'upstream' } } },
    { id: 'register', name: 'Register the VM', type: 'register', status: 'succeeded', attempt: 1, startedAt: ago(14), endedAt: ago(12), durationMs: 800, output: { id: 'vm-1044' } },
  ],
  approvals: [],
  children: [CHILD_BUILD, CHILD_WARM],
  traceId: '7d1f0c2a9b3e4d5f6a7b8c9d0e1f2a3b',
}

/** GET /jobs/job-9b2e4f10/replay?compareTo=job-2c44a810: compared with the earlier create-vm run. */
export const REPLAY: JobReplay = {
  jobId: BUILD_JOB.id,
  workflow: 'create-vm',
  version: '2.0.0',
  status: 'succeeded',
  comparedTo: 'job-2c44a810',
  comparedVersion: '2.0.0',
  comparedStatus: 'succeeded',
  inputDiff: [{ field: 'input.name', before: 'web-1043', after: 'web-1044' }],
  steps: BUILD_JOB.steps.map((s) => ({ ...s, diff: s.id === 'clone' ? [{ field: 'output.host', before: 'esx-02', after: 'esx-07' }] : [] })),
}

/* ----------------------------------------------------------------------------
   A batch the backend stopped (GET /jobs/job-b4d2e9a1), as the in-memory engine leaves it: waves
   done, done, skipped; the second wave's failures crossed stopIfFailedPercent, so the third wave's
   rows were skipped and the job failed. POST /jobs/job-b4d2e9a1:retry-failed-rows runs the failed
   and skipped rows again in a fourth wave and queues the job again (BATCH_RETRIED).
   ------------------------------------------------------------------------- */

const patchRow = (index: number, host: string) => ({ index, input: { host, cve: 'CVE-2026-1234' } })

export const BATCH_JOB: JobDetail = {
  id: 'job-b4d2e9a1',
  workflow: 'patch-cve',
  title: 'Patch CVE-2026-1234 on 6 servers',
  version: '0.3.0',
  status: 'failed',
  requester: 'maria',
  channel: 'batch',
  startedAt: ago(40),
  endedAt: ago(31),
  durationMs: 540_000,
  kind: 'batch',
  progress: { done: 6, total: 6, failed: 2 },
  input: { cve: 'CVE-2026-1234' },
  output: { total: 6, succeeded: 1, failed: 2, skipped: 3 },
  steps: [],
  approvals: [],
  traceId: '4b0e6f2d9a1c3e5f7a9b1c3d5e7f9a1b',
  batch: {
    rollout: { waves: [20, 50, 100], maxParallel: 2, stopIfFailedPercent: 20 },
    waves: [
      { index: 0, percent: 20, size: 1, status: 'done', startedAt: ago(40), endedAt: ago(37) },
      { index: 1, percent: 50, size: 2, status: 'done', startedAt: ago(37), endedAt: ago(31) },
      { index: 2, percent: 100, size: 3, status: 'skipped', endedAt: ago(31) },
    ],
    rows: [
      { ...patchRow(0, 'web-01'), jobId: 'job-b4d2-r0', status: 'succeeded' },
      { ...patchRow(1, 'web-02'), jobId: 'job-b4d2-r1', status: 'failed', error: 'package_conflict at apply' },
      { ...patchRow(2, 'web-03'), jobId: 'job-b4d2-r2', status: 'failed', error: 'unreachable at gather' },
      { ...patchRow(3, 'web-04'), status: 'skipped' },
      { ...patchRow(4, 'web-05'), status: 'skipped' },
      { ...patchRow(5, 'web-06'), status: 'skipped' },
    ],
    stoppedReason: 'stopped after wave 1: 2 of 3 rows failed (more than 20%)',
  },
}

/** BATCH_JOB once :retry-failed-rows accepted: queued again, the five rows pending in wave 4. */
export const BATCH_RETRIED: JobDetail = {
  ...BATCH_JOB,
  status: 'queued',
  endedAt: undefined,
  durationMs: undefined,
  output: undefined,
  progress: { done: 1, total: 6, failed: 0 },
  batch: {
    ...BATCH_JOB.batch!,
    waves: [...BATCH_JOB.batch!.waves, { index: 3, percent: 100, size: 5, status: 'pending' }],
    rows: BATCH_JOB.batch!.rows.map((r) => (r.status === 'succeeded' ? r : { index: r.index, input: r.input, status: 'pending' })),
    stoppedReason: undefined,
  },
}

/* ----------------------------------------------------------------------------
   A workflow change request with the publish checks (GET /change-requests/cr-202)
   ------------------------------------------------------------------------- */

export const WORKFLOW_CR: ChangeRequestDetail = {
  id: 'cr-202',
  kind: 'workflow',
  name: 'vm-restart',
  version: '2.0.0',
  branch: 'workflow/vm-restart-2.0.0',
  title: 'Publish vm-restart 2.0.0',
  author: 'gonzalo',
  status: 'open',
  strategy: 'canary',
  createdAt: ago(30),
  checks: [
    { id: 'checks', label: 'Workflow checks', status: 'passed' },
    { id: 'ansible-lint', label: 'ansible-lint', status: 'passed', detail: '1 playbook pinned to a commit; pinned at publish: restart to 9fceb02d0ae5' },
    { id: 'contracts', label: 'Contracts', status: 'failed', detail: '2.0.0 breaks the contract of 1.2.0 without a new major version: input.reason became required. Affected consumers: web-environment, create-vm' },
    { id: 'replay', label: 'Replay of recent jobs', status: 'passed', detail: '{"replayed":42,"divergences":[]}' },
  ],
  diff: '--- a/workflows/team-compute/vm-restart.yaml\n+++ b/workflows/team-compute/vm-restart.yaml\n@@ -12 +12 @@\n-  version: 1.2.0\n+  version: 2.0.0\n',
  yaml: 'apiVersion: iaf.platform/v1\nkind: Workflow\nmetadata:\n  name: vm-restart\n  version: 2.0.0\n',
}

/* ----------------------------------------------------------------------------
   The versions of create-vm (GET /workflows/create-vm/versions, …/versions/2.0.0)
   ------------------------------------------------------------------------- */

/** create-vm 2.0.0 as a document: a run step with a version range, one without, one nested in a loop. */
export const CREATE_VM_DEFINITION = {
  apiVersion: 'iaf.platform/v1',
  kind: 'Workflow',
  metadata: { name: 'create-vm', title: 'Create a VM', noun: 'vm', verb: 'create', owner: 'team-compute', version: '2.0.0' },
  api: { method: 'POST', path: '/api/v1/vms', input: CATALOG[1].input },
  workflow: {
    do: [
      { build: { name: 'Build the image', run: { workflow: 'build-vm-image', version: '^1', input: { os: '{{ $input.image.create.os }}' } } } },
      { clone: { name: 'Clone from the image', call: 'provider', with: { provider: 'vsphere', action: 'vm.clone', input: { templateId: '{{ $input.image }}', name: '{{ $input.name }}' } } } },
      { wire: { name: 'Wire the environment', run: { workflow: 'web-environment', input: { vmId: '{{ $json.id }}' } } } },
      { disks: { name: 'Each disk', for: { each: 'disk', in: '{{ $input.disks }}', do: [{ attach: { name: 'Attach the disk', run: { workflow: 'attach-disk', version: '~1.4', input: {} } } }] } } },
    ],
  },
}

export const CREATE_VM_YAML = 'apiVersion: iaf.platform/v1\nkind: Workflow\nmetadata:\n  name: create-vm\n  version: 2.0.0\nworkflow:\n  do:\n    - build:\n        run: { workflow: build-vm-image, version: "^1" }\n'

export const CREATE_VM_VERSIONS: WorkflowVersion[] = [
  { version: '2.0.0', status: 'published', contentHash: 'sha256:2', publishedAt: ago(60 * 72), publishedBy: 'gonzalo', releaseId: 'rel-0' },
  { version: '1.9.0', status: 'retired', contentHash: 'sha256:1', publishedAt: ago(60 * 24 * 30), publishedBy: 'maria' },
]

/* ----------------------------------------------------------------------------
   Dependencies (GET /registry/dependencies)
   ------------------------------------------------------------------------- */

export const DEPENDENCIES: Dependencies = {
  resourceTypes: [
    {
      type: 'image',
      producers: [
        { workflow: 'build-vm-image', via: 'producedBy' },
        { workflow: 'build-vm-image', via: 'register', at: 'register-image' },
      ],
      consumers: [
        { workflow: 'create-vm', via: 'input', at: 'image' },
        { workflow: 'web-environment', via: 'orCreateWith', at: 'image' },
      ],
    },
    { type: 'vm', producers: [{ workflow: 'create-vm', via: 'register', at: 'register-vm' }], consumers: [{ workflow: 'vm-restart', via: 'input', at: 'vmId' }] },
  ],
  workflows: [
    { workflow: 'build-vm-image', version: '1.0.0', produces: ['image'], consumes: [], runs: [], consumers: [{ workflow: 'create-vm', via: 'orCreateWith', at: 'image' }] },
    {
      workflow: 'create-vm',
      version: '2.0.0',
      produces: ['vm'],
      consumes: ['image'],
      runs: [
        { workflow: 'build-vm-image', via: 'orCreateWith', at: 'image' },
        { workflow: 'web-environment', via: 'run', at: 'wire', range: '^1' },
      ],
      consumers: [{ workflow: 'web-environment', via: 'run', at: 'create', range: '^2' }],
    },
    { workflow: 'vm-restart', version: '1.2.0', produces: [], consumes: ['vm'], runs: [], consumers: [] },
    {
      workflow: 'web-environment',
      version: '1.0.0',
      produces: [],
      consumes: ['image'],
      runs: [{ workflow: 'create-vm', via: 'run', at: 'create', range: '^2' }],
      consumers: [{ workflow: 'create-vm', via: 'run', at: 'wire', range: '^1' }],
    },
  ],
  cycles: [['create-vm', 'web-environment', 'create-vm']],
}

/* ----------------------------------------------------------------------------
   The inbox, the platform audit stream, a deprecated catalog entry and provider releases
   ------------------------------------------------------------------------- */

export const NOTIFICATIONS: Notification[] = [
  { id: 'ntf-1', at: ago(4), kind: 'approval.requested', severity: 'warning', title: 'Approve the restart of web-1042', message: 'compute-approvers: a production restart waits for you', link: '/jobs/job-7f3a2c91', jobId: 'job-7f3a2c91', workflow: 'vm-restart', users: [], teams: ['compute-approvers'], read: false },
  { id: 'ntf-2', at: ago(40), kind: 'provider.alert', severity: 'critical', title: 'vsphere is failing', message: '3 timeouts in 10 minutes', link: '/providers/vsphere', channel: '#compute-alerts', provider: 'vsphere', users: [], teams: ['team-compute'], read: false },
  { id: 'ntf-3', at: ago(60 * 5), kind: 'notify', severity: 'info', title: 'Patch window done', message: '20 of 20 servers patched', link: '/jobs/job-5b10e7d2', jobId: 'job-5b10e7d2', workflow: 'patch-cve', users: ['gonzalo'], teams: [], read: true },
]

/** A mutable inbox: GET filters by `unread`, POST marks one read. */
export function inboxRoutes(items: Notification[] = NOTIFICATIONS.map((n) => ({ ...n }))): Routes {
  return {
    'GET /notifications': (req) => {
      const unread = req.query.get('unread')
      const visible = unread === 'true' ? items.filter((n) => !n.read) : unread === 'false' ? items.filter((n) => n.read) : items
      return { items: visible, unread: items.filter((n) => !n.read).length }
    },
    'POST /notifications/{id}:read': (req) => {
      const n = items.find((x) => x.id === req.params.id)
      if (!n) return reply(404, { error: `no notification ${req.params.id}` })
      n.read = true
      return n
    },
  }
}

export const PLATFORM_STREAM: PlatformStream = {
  entries: [
    { seq: 1, at: ago(60 * 30), kind: 'decision', actor: 'gonzalo', data: { event: 'change-request.opened', aggregate: 'change-request', aggregateId: 'cr-41', name: 'vm-restart', version: '1.2.0', strategy: 'canary' }, hash: hash('p1'), previous: hash('0') },
    { seq: 2, at: ago(60 * 29), kind: 'decision', actor: 'maria', data: { event: 'change-request.merged', aggregate: 'change-request', aggregateId: 'cr-41', name: 'vm-restart', version: '1.2.0' }, hash: hash('p2'), previous: hash('p1') },
    { seq: 3, at: ago(60 * 29), kind: 'decision', actor: 'maria', data: { event: 'workflow.published', aggregate: 'workflow', aggregateId: 'vm-restart', name: 'vm-restart', version: '1.2.0', contentHash: 'sha256:1' }, hash: hash('p3'), previous: hash('p2') },
    { seq: 4, at: ago(60 * 3), kind: 'decision', actor: 'ana', data: { event: 'provider.change-requested', aggregate: 'provider', aggregateId: 'vsphere', version: '2.1.1', changeRequest: 'cr-42', reason: 'slo' }, hash: hash('p4'), previous: hash('p3') },
    { seq: 5, at: ago(50), kind: 'decision', actor: 'gonzalo', data: { event: 'release.promoted', aggregate: 'release', aggregateId: 'rel-1', workflow: 'vm-restart', from: '1.1.0', to: '1.2.0' }, hash: hash('p5'), previous: hash('p4') },
    { seq: 6, at: ago(10), kind: 'decision', actor: 'gonzalo', data: { event: 'service-account.created', aggregate: 'service-account', aggregateId: 'sa-ci', name: 'ci-deployer', scopes: ['registry:read', 'workflows:validate'] }, hash: hash('p6'), previous: hash('p5') },
  ],
  total: 6,
  latestHash: hash('p6'),
  verification: { valid: true, entries: 6, seal: 'verified', seals: [{ kind: 'checkpoint', at: ago(5), seq: 6, keyId: 'audit-2026-10', status: 'verified' }] },
}

/** GET /audit/platform?aggregate=provider: the provider events of the stream, its chain intact. */
export const PROVIDER_STREAM: PlatformStream = {
  entries: [
    { seq: 4, at: ago(60 * 3), kind: 'decision', actor: 'ana', data: { event: 'provider.change-requested', aggregate: 'provider', aggregateId: 'vsphere', version: '2.1.1', changeRequest: 'cr-42', reason: 'slo' }, hash: hash('p4'), previous: hash('p3') },
    { seq: 7, at: ago(60 * 2), kind: 'decision', actor: 'maria', data: { event: 'provider.published', aggregate: 'provider', aggregateId: 'vsphere', name: 'vsphere', version: '1.3.0', contentHash: 'sha256:v13' }, hash: hash('p7'), previous: hash('p6') },
    { seq: 9, at: ago(40), kind: 'decision', actor: 'gonzalo', data: { event: 'provider.updated', aggregate: 'provider', aggregateId: 'backup', name: 'backup', reason: 'override vm.list' }, hash: hash('p9'), previous: hash('p8') },
  ],
  total: 9,
  latestHash: hash('p9'),
  verification: { valid: true, entries: 9, seal: 'verified', seals: [{ kind: 'checkpoint', at: ago(5), seq: 9, keyId: 'audit-2026-10', status: 'verified' }] },
}

/** vm-restart with a successor: the deprecation the catalog reports. */
export const DEPRECATED_CATALOG: CatalogEntry[] = [
  { ...CATALOG[0], deprecated: { since: '2026-10-01', sunset: '2027-01-31', replacement: '/api/v2/vms/{vmId}:restart', message: 'Use v2, which restarts through the maintenance window.' } },
  { ...CATALOG[0], workflow: 'vm-restart-v2', title: 'VM restart (v2)', path: '/api/v2/vms/{vmId}:restart', batchPath: '/api/v2/vms:restart:batch', previewPath: '/api/v2/vms/{vmId}:restart:preview', mcpTool: 'restart_vm_v2', version: '2.0.0', description: 'Restart a virtual machine through its maintenance window.' },
  CATALOG[1],
]

export const VM_RESTART_VERSIONS: WorkflowVersion[] = [
  { version: '1.2.0', status: 'published', contentHash: 'sha256:12', publishedAt: ago(60 * 24 * 3), publishedBy: 'maria', releaseId: 'rel-1' },
  { version: '1.1.0', status: 'retired', contentHash: 'sha256:11', publishedAt: ago(60 * 24 * 30), publishedBy: 'gonzalo' },
  { version: '1.3.0', status: 'draft', contentHash: 'sha256:13' },
]

/** A canary of vsphere 2.1.0 → 2.2.0, judged over the provider's calls. */
export const PROVIDER_RELEASE: Release = {
  id: 'rel-p1',
  provider: 'vsphere',
  from: '2.1.0',
  to: '2.2.0',
  strategy: 'canary',
  status: 'canary',
  stageIndex: 1,
  stages: [10, 50, 100],
  stagePercent: 50,
  startedAt: ago(60 * 6),
  stageStartedAt: ago(60 * 2),
  gates: [
    { id: 'success-rate', label: 'Success rate', passed: true, value: 99.4, threshold: 98.9, detail: 'candidate 99.4% vs current 99.9% over 340 calls' },
    { id: 'p95', label: 'p95 duration', passed: null, value: 410, threshold: 480, detail: 'needs 20 calls in the stage, has 12' },
    { id: 'error-types', label: 'Error types', passed: true, value: 0, detail: 'no new error class on the candidate' },
    { id: 'sample', label: 'Sample size', passed: null, value: 12, threshold: 20, detail: '12 candidate calls since the stage started' },
  ],
  jobs: { current: 328, candidate: 12 },
  history: [
    { at: ago(60 * 6), event: 'canary started at 10%', by: 'ana' },
    { at: ago(60 * 2), event: 'advanced to 50%' },
  ],
}

/** The Backup service spec read from Git: the same spec, pinned to the commit it came from. */
export const SPEC_FROM_GIT: SpecImport = { ...SPEC, commit: '4f9e2a7c1d0b5e8a9c3f6b2d7e1a4c8f0b9d2e5a' }

export const ANSIBLE_IMPORT: AnsibleImport = {
  repo: 'https://github.com/acme/ops-playbooks',
  ref: 'v3.1.0',
  commit: 'a7c3e9f1b2d4068c5e7a9b1d3f5c7e9a1b3d5f7c',
  playbooks: [
    {
      path: 'vm/restart.yml',
      action: 'vm.restart',
      title: 'Restart a VM',
      effect: 'change',
      roles: ['vmware.restart'],
      vars: { type: 'object', required: ['vm_name'], properties: { vm_name: { type: 'string' }, graceful: { type: 'boolean', default: true } } },
      sources: { vm_name: 'vars_prompt', graceful: 'vars' },
      secrets: [],
      notes: [],
    },
    {
      path: 'vm/snapshot.yml',
      action: 'vm.snapshot',
      title: 'Snapshot a VM',
      effect: 'create',
      roles: ['vmware.snapshot'],
      vars: { type: 'object', required: ['vm_name', 'label'], properties: { vm_name: { type: 'string' }, label: { type: 'string' } } },
      sources: { vm_name: 'vars_prompt', label: 'vars_prompt' },
      secrets: ['vcenter_password'],
      notes: ['vcenter_password is given from Vault, not asked as input'],
    },
  ],
  chosen: ['vm/restart.yml', 'vm/snapshot.yml'],
  requirements: ['community.vmware>=4.0.0'],
  skipped: ['site.yml: imports every play, not a single action'],
  notes: ['2 playbooks become actions; 1 skipped.', 'The inventory is the runner’s: the file names no hosts.'],
  yaml: [
    'apiVersion: platform/v1',
    'kind: Provider',
    'metadata:',
    '  name: ops-playbooks',
    '  title: ops-playbooks',
    '  owner: team-platform',
    '  version: 0.1.0',
    'spec:',
    '  type: ansible',
    '  repo: https://github.com/acme/ops-playbooks',
    '  ref: v3.1.0',
    '  commit: a7c3e9f1b2d4068c5e7a9b1d3f5c7e9a1b3d5f7c',
    '  credential: vault:ops/ssh',
    '  actions:',
    '    - id: vm.restart',
    '      playbook: vm/restart.yml',
    '      effect: change',
    '    - id: vm.snapshot',
    '      playbook: vm/snapshot.yml',
    '      effect: create',
    '',
  ].join('\n'),
}

export const TERRAFORM_IMPORT: TerraformImport = {
  source: 'acme/lb/aws',
  version: '2.3.0',
  notes: ['3 variables; db_password is sensitive and comes from Vault.', 'plan and apply share the inputs; apply needs an approval.'],
  yaml: [
    'apiVersion: platform/v1',
    'kind: Provider',
    'metadata:',
    '  name: lb',
    '  title: lb',
    '  owner: team-platform',
    '  version: 0.1.0',
    'spec:',
    '  type: terraform',
    '  source: acme/lb/aws',
    '  version: 2.3.0',
    '  credential: vault:aws/runner',
    '  actions:',
    '    - id: plan',
    '      effect: read',
    '    - id: apply',
    '      effect: change',
    '',
  ].join('\n'),
  variables: [
    { name: 'name', type: 'string', required: true, sensitive: false, secret: false, description: 'The load balancer name', file: 'variables.tf' },
    { name: 'listeners', type: 'list(object)', required: false, sensitive: false, secret: false, description: 'Ports to listen on', default: [{ port: 443 }], file: 'variables.tf' },
    { name: 'db_password', type: 'string', required: true, sensitive: true, secret: true, description: 'Given from Vault', file: 'variables.tf' },
  ],
  outputs: [
    { name: 'dns_name', description: 'The public name', sensitive: false, file: 'outputs.tf' },
    { name: 'arn', sensitive: false, file: 'outputs.tf' },
  ],
}

/* ----------------------------------------------------------------------------
   The assistant: a session's draft, what the validation found, and the session's events
   ------------------------------------------------------------------------- */

/** The assistant's draft as the backend parses it (POST /workflows:parse): two provider calls, the second on a provider that does not exist. */
export const ASSISTANT_DRAFT_DEFINITION = {
  apiVersion: 'iaf.platform/v1',
  kind: 'Workflow',
  metadata: { name: 'vm-restart-safe', title: 'Safe VM restart', noun: 'vm', verb: 'restart-safely', owner: 'team-compute', version: '0.1.0' },
  api: { method: 'POST', path: '/api/v1/vms/{vmId}:restart-safely', input: { type: 'object', required: ['vmId'], properties: { vmId: { type: 'string' } } } },
  workflow: {
    do: [
      { check: { name: 'Check the backups', call: 'provider', with: { provider: 'backup', action: 'listJobs', input: { target: '{{ $input.vmId }}' } } } },
      { restart: { name: 'Restart the VM', call: 'provider', with: { provider: 'vcenter', action: 'vm.restart', input: { id: '{{ $input.vmId }}' } } } },
    ],
  },
}

export const ASSISTANT_DRAFT_YAML = 'apiVersion: iaf.platform/v1\nkind: Workflow\nmetadata:\n  name: vm-restart-safe\n  version: 0.1.0\nworkflow:\n  do:\n    - check: { call: provider }\n    - restart: { call: provider }\n'

export const ASSISTANT_PROBLEMS: Problem[] = [
  { level: 'error', code: 'step.unknownProvider', step: 'restart', field: 'with.provider', message: 'No provider called vcenter; vsphere is the closest' },
  { level: 'warning', code: 'workflow.noApproval', message: 'A change step runs without an approval' },
]

/** The platform stream's entries for session as-77 (and one of another session, which its timeline leaves out). */
export const ASSISTANT_SESSION_EVENTS: EvidenceEntry[] = [
  { seq: 7, at: ago(3), kind: 'decision', actor: 'gonzalo', data: { event: 'assistant.started', aggregate: 'assistant-session', aggregateId: 'as-77', draftId: 'as-77', user: 'gonzalo', kind: 'workflow-draft', agent: 'devin', mode: 'create' }, hash: hash('a1'), previous: hash('p6') },
  { seq: 8, at: ago(2), kind: 'decision', actor: 'gonzalo', data: { event: 'assistant.message', aggregate: 'assistant-session', aggregateId: 'as-77', draftId: 'as-77', role: 'user', text: 'Restart a VM after checking no backup is running.' }, hash: hash('a2'), previous: hash('a1') },
  { seq: 9, at: ago(1), kind: 'decision', actor: 'gonzalo', data: { event: 'assistant.draft-updated', aggregate: 'assistant-session', aggregateId: 'as-77', draftId: 'as-77', status: 'ready', contentHash: 'sha256:d1', valid: false, errors: 1 }, hash: hash('a3'), previous: hash('a2') },
  { seq: 10, at: ago(1), kind: 'decision', actor: 'ana', data: { event: 'assistant.started', aggregate: 'assistant-session', aggregateId: 'as-78', draftId: 'as-78', user: 'ana', kind: 'workflow-draft', agent: 'devin', mode: 'create' }, hash: hash('a4'), previous: hash('a3') },
]

/** GET /assistant/sessions/as-90: an edit of vm-restart the backend kept (its SessionView), one exchange in, the draft not valid yet. */
export const ASSISTANT_SESSION: AssistantSession = {
  sessionId: 'as-90',
  mode: 'edit',
  workflow: 'vm-restart',
  status: 'done',
  draft: {
    status: 'ready',
    summary: 'Restarts the VM after a backup check, on vcenter.',
    workflowYaml: ASSISTANT_DRAFT_YAML,
    changes: ['Added "Check the backups" before the restart'],
    validation: { valid: false, rounds: 2, errors: ['step.unknownProvider @restart: No provider called vcenter'] },
    dryRun: { passed: false, path: ['check'], error: 'No provider called vcenter' },
  },
  history: [
    { role: 'user', text: 'Check the backups before restarting.', at: ago(12) },
    { role: 'assistant', text: 'I added a backup check before the restart.', at: ago(11) },
  ],
  createdAt: ago(13),
  updatedAt: ago(11),
}
