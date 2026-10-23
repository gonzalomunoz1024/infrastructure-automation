import resourceTypesSample from './samples/resource-types.json'
import catalogSample from './samples/catalog.json'
import resourcesSample from './samples/resources.json'
import jobsSample from './samples/jobs.json'
import { DEV_IDENTITIES } from '../lib/identity'
import { DEMO_ACCOUNTS } from '../lib/accounts'
import type { CatalogEntry, JobSummary, Me, PlatformEvent, Resource, ResourceType } from '../lib/types'

/*
 * The mock's dataset: the platform's captured samples (console/src/mock/samples, from the
 * platform-fake at c9529d5) plus what the planned platform changes add, so the shell shows both
 * paths. The vm type carries P-06 hints (service, console block, x-console keywords) and its
 * resources carry P-05 wfrns and accounts; every other type is served as captured, so the
 * defaults the descriptors compose are what you see for them.
 */

export interface MockData {
  resourceTypes: ResourceType[]
  catalog: CatalogEntry[]
  resources: Resource[]
  jobs: JobSummary[]
  regions: unknown[]
  users: Record<string, Me>
  events: PlatformEvent[]
}

export const MOCK_PARTITION = 'wf'
export const MOCK_REGION = 'global'
const COMPUTE_ACCOUNT = DEMO_ACCOUNTS[0].id
const DATA_ACCOUNT = DEMO_ACCOUNTS[1].id

function clone<T>(v: T): T {
  return JSON.parse(JSON.stringify(v)) as T
}

function withHints(types: ResourceType[]): ResourceType[] {
  return types.map((rt) => {
    if (rt.name !== 'vm') {
      if (rt.name === 'database' && rt.schema.properties?.secret) {
        const db = clone(rt)
        db.schema.properties!.secret['x-console'] = { sensitive: true }
        return db
      }
      return rt
    }
    const vm = clone(rt)
    vm.service = 'compute'
    vm.description = vm.description ?? 'A virtual machine built from an image, sized by CPU and memory, placed in an environment.'
    vm.console = {
      title: 'Virtual machines',
      icon: 'terminal',
      list: { columns: ['name', 'status', 'wfrn', 'cpu', 'memoryGb', 'environment', 'owner', 'created'], defaultSort: 'name' },
      lifecycle: { building: 'pending', ready: 'active', deprecated: 'active', retired: 'deleted' },
    }
    const props = vm.schema.properties ?? {}
    if (props.name) props.name['x-console'] = { column: true, searchable: true, help: 'https://wiki.example.test/compute/vm#name' }
    if (props.image) props.image['x-console'] = { widget: 'picker', pickerType: 'image', group: 'Boot' }
    if (props.cpu) props.cpu['x-console'] = { column: true, mutable: true, group: 'Sizing', widget: 'slider' }
    if (props.memoryGb) props.memoryGb['x-console'] = { column: true, mutable: true, group: 'Sizing', format: 'gibibytes' }
    if (props.environment) props.environment['x-console'] = { searchable: true }
    return vm
  })
}

const GENERATED_VMS = 240
const VM_STATUSES = ['ready', 'ready', 'ready', 'ready', 'building', 'ready', 'deprecated', 'retired']
const VM_ROLES = ['web', 'api', 'batch', 'cache', 'shop', 'etl']

function generatedVms(): Resource[] {
  const base = Date.UTC(2026, 9, 10, 8, 0, 0)
  return Array.from({ length: GENERATED_VMS }, (_, n) => {
    const i = n + 1
    const id = `vm-gen-${String(i).padStart(3, '0')}`
    const name = `${VM_ROLES[i % VM_ROLES.length]}-${String(10 + i).padStart(3, '0')}`
    const createdAt = new Date(base - i * 3_600_000).toISOString()
    return {
      id,
      type: 'vm',
      name,
      status: VM_STATUSES[i % VM_STATUSES.length],
      attributes: { name, image: 'image-demo01', cpu: [2, 4, 8, 16][i % 4], memoryGb: [4, 8, 16, 32, 64][i % 5], environment: ['dev', 'test', 'prod'][i % 3], owner: 'team-compute' },
      owner: 'team-compute',
      visibility: 'team',
      createdBy: { jobId: `job_gen${String(i).padStart(3, '0')}`, workflow: 'create-vm', version: '2.0.1' },
      builtFrom: ['image-demo01'],
      createdAt,
      updatedAt: createdAt,
    }
  })
}

/** vms carry P-05 wfrns and the compute account; databases fall under the data account with no wfrn yet (derived in the browser). */
function withContext(resources: Resource[]): Resource[] {
  return resources.map((r) => {
    if (r.type === 'vm') return { ...r, wfrn: `wfrn:${MOCK_PARTITION}:compute:${MOCK_REGION}:${COMPUTE_ACCOUNT}:vm/${r.id}`, accountId: COMPUTE_ACCOUNT, region: MOCK_REGION, providerId: `vsphere:${r.id}` }
    if (r.type === 'database') return { ...r, accountId: DATA_ACCOUNT, region: MOCK_REGION }
    return r
  })
}

function usersOf(): Record<string, Me> {
  const out: Record<string, Me> = {}
  for (const d of DEV_IDENTITIES) out[d.id] = { id: d.id, name: d.name, email: d.email, roles: d.roles, teams: d.teams, admin: d.admin }
  return out
}

const EVENT_TYPES = ['job.started', 'job.step', 'job.finished', 'resource.registered', 'resource.updated'] as const

function backlog(resources: Resource[], jobs: JobSummary[]): PlatformEvent[] {
  const vms = resources.filter((r) => r.type === 'vm').slice(0, 6)
  const recent = jobs.slice(0, 8)
  const start = Date.UTC(2026, 9, 23, 2, 50, 0)
  return recent.map((job, i) => {
    const vm = vms[i % vms.length]
    const type = EVENT_TYPES[i % EVENT_TYPES.length]
    const seq = i + 1
    return {
      specVersion: '1.0',
      dataVersion: '1',
      id: String(seq),
      key: `${type}:${job.id}`,
      seq,
      type,
      at: new Date(start + i * 45_000).toISOString(),
      aggregate: type.startsWith('job') ? 'job' : 'resource',
      aggregateId: type.startsWith('job') ? job.id : vm.id,
      actor: job.requester,
      context: { accountId: COMPUTE_ACCOUNT, region: MOCK_REGION, wfrn: vm.wfrn },
      data: type.startsWith('job') ? { jobId: job.id, workflow: job.workflow, status: job.status, step: type === 'job.step' ? 'apply' : undefined } : { resourceId: vm.id, type: vm.type, status: vm.status },
    }
  })
}

export function buildMockData(): MockData {
  const resourceTypes = withHints(resourcesTypesOf())
  const catalog = catalogSample as unknown as CatalogEntry[]
  const resources = withContext([...(resourcesSample as unknown as Resource[]), ...generatedVms()])
  const jobs = [...(jobsSample as unknown as JobSummary[])].sort((a, b) => b.startedAt.localeCompare(a.startedAt))
  return {
    resourceTypes,
    catalog,
    resources,
    jobs,
    regions: [{ metadata: { name: MOCK_REGION, title: 'Global' }, spec: { partition: MOCK_PARTITION, status: 'available', environments: ['dev', 'test', 'prod'] } }],
    users: usersOf(),
    events: backlog(resources, jobs),
  }
}

function resourcesTypesOf(): ResourceType[] {
  return clone(resourceTypesSample as unknown as ResourceType[])
}

/** The next live event of the feed: a job or resource event about one of the vms, with a fresh sequence number. */
export function nextMockEvent(data: MockData): PlatformEvent {
  const seq = (data.events[data.events.length - 1]?.seq ?? 0) + 1
  const type = EVENT_TYPES[seq % EVENT_TYPES.length]
  const vms = data.resources.filter((r) => r.type === 'vm')
  const vm = vms[seq % vms.length]
  const job = data.jobs[seq % data.jobs.length]
  const event: PlatformEvent = {
    specVersion: '1.0',
    dataVersion: '1',
    id: String(seq),
    key: `${type}:${type.startsWith('job') ? job.id : vm.id}`,
    seq,
    type,
    at: new Date().toISOString(),
    aggregate: type.startsWith('job') ? 'job' : 'resource',
    aggregateId: type.startsWith('job') ? job.id : vm.id,
    actor: job.requester,
    context: { accountId: vm.accountId ?? COMPUTE_ACCOUNT, region: MOCK_REGION, wfrn: vm.wfrn },
    data: type.startsWith('job') ? { jobId: job.id, workflow: job.workflow, status: type === 'job.finished' ? 'succeeded' : 'running', step: type === 'job.step' ? ['validate', 'apply', 'register'][seq % 3] : undefined } : { resourceId: vm.id, type: vm.type, status: vm.status },
  }
  data.events.push(event)
  return event
}
