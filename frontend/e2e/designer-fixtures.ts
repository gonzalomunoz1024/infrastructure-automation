/**
 * The backend the designer spec talks to, mocked with page.route: the real vm-restart workflow
 * and the real provider, resource-type and workflow files of the repository, read with `yaml`
 * here in Node (the browser never parses YAML; the mocked render and parse endpoints do it).
 */
import { readdirSync, readFileSync } from 'node:fs'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'
import type { Page, Request, Route } from '@playwright/test'
import { parse, stringify } from 'yaml'

const ROOT = fileURLToPath(new URL('../../', import.meta.url))

type Json = null | boolean | number | string | Json[] | { [key: string]: Json }
type Obj = { [key: string]: Json }

function yamlFiles(dir: string): string[] {
  const out: string[] = []
  for (const entry of readdirSync(join(ROOT, dir), { withFileTypes: true })) {
    const rel = join(dir, entry.name)
    if (entry.isDirectory()) out.push(...yamlFiles(rel))
    else if (entry.name.endsWith('.yaml')) out.push(rel)
  }
  return out.sort()
}

function load(rel: string): Obj {
  return parse(readFileSync(join(ROOT, rel), 'utf8')) as Obj
}

function asObj(v: Json | undefined): Obj {
  return v && typeof v === 'object' && !Array.isArray(v) ? v : {}
}

function schemaOf(v: Json | undefined): Obj {
  if (Array.isArray(v)) return { type: 'array', items: v.length > 0 ? schemaOf(v[0]) : {} }
  if (v && typeof v === 'object') return { type: 'object', properties: Object.fromEntries(Object.entries(v).map(([k, x]) => [k, schemaOf(x)])) }
  if (typeof v === 'number') return { type: Number.isInteger(v) ? 'integer' : 'number' }
  if (typeof v === 'boolean') return { type: 'boolean' }
  return { type: 'string' }
}

/** GET /registry/providers, as the backend answers it. */
export function registryProviders(): Obj[] {
  return yamlFiles('providers').map((file) => {
    const doc = load(file)
    const meta = asObj(doc.metadata)
    const spec = asObj(doc.spec)
    const actions = (Array.isArray(spec.actions) ? spec.actions : []).map((a) => {
      const action = asObj(a)
      const effect = String(action.effect ?? 'unknown')
      return {
        id: String(action.id),
        title: String(action.title ?? action.id),
        effect,
        retry: String(action.retry ?? (effect === 'read' ? 'safe' : 'unsafe')),
        approval: String(action.approval ?? (effect === 'delete' ? 'required' : 'none')),
        input: action.input ?? action.vars ?? null,
        output: action.output ?? (action.sample !== undefined ? schemaOf(action.sample) : null),
        sample: action.sample ?? null,
      }
    })
    return { name: String(meta.name), title: String(meta.title ?? meta.name), type: String(spec.type), owner: String(meta.owner ?? ''), actions }
  })
}

/** GET /registry/playbooks: every action of an ansible provider as `<provider>/<action id>`, like the backend. */
export function registryPlaybooks(): Obj[] {
  return yamlFiles('providers').flatMap((file) => {
    const doc = load(file)
    const meta = asObj(doc.metadata)
    const spec = asObj(doc.spec)
    if (spec.type !== 'ansible') return []
    const git = asObj(asObj(spec.source).git)
    const version = String(meta.version ?? git.ref ?? '')
    return (Array.isArray(spec.actions) ? spec.actions : []).map((a) => {
      const action = asObj(a)
      return { name: `${String(meta.name)}/${String(action.id)}`, version, path: String(action.playbook ?? ''), owner: String(meta.owner ?? '') }
    })
  })
}

/**
 * POST /workflows:import for a terraform provider, as the backend drafts it: plan, approval and
 * apply steps that run the provider's own `spec.module` (the placeholder when it has none).
 */
export function terraformImport(ref: string): { yaml: string; notes: string[] } | null {
  for (const file of yamlFiles('providers')) {
    const doc = load(file)
    const meta = asObj(doc.metadata)
    const spec = asObj(doc.spec)
    if (spec.type !== 'terraform' || meta.name !== ref) continue
    const module = asObj(spec.module).source ? asObj(spec.module) : { source: 'your-module-source', version: '1.0.0' }
    const credential = String(asObj(spec.auth).credential ?? 'vault:your-credential')
    const draft = {
      apiVersion: 'iaf.platform/v1',
      kind: 'Workflow',
      metadata: { name: `${ref}-apply`, title: `Apply ${String(meta.title ?? ref)}`, owner: String(meta.owner ?? '') },
      workflow: {
        do: [
          { plan: { name: 'Plan', call: 'terraform', with: { module, action: 'plan', vars: {}, credential }, output: { as: 'plan' } } },
          { approval: { name: 'Approve the plan', approval: { group: 'your-approvers', message: 'Apply the plan?', timeout: '4h' } } },
          { apply: { name: 'Apply', call: 'terraform', with: { module, action: 'apply', vars: {}, credential }, output: { as: 'apply' } } },
        ],
      },
    }
    return { yaml: stringify(draft), notes: ['Set the approver group of the approval step and of policy.approval.'] }
  }
  return null
}

export function registryResourceTypes(): Obj[] {
  return yamlFiles('resource-types').map((file) => {
    const doc = load(file)
    const meta = asObj(doc.metadata)
    return { name: String(meta.name), plural: String(meta.plural ?? `${meta.name}s`), owner: String(meta.owner ?? ''), schema: doc.schema ?? {}, states: doc.states ?? [], producedBy: doc.producedBy ?? null }
  })
}

export function registryWorkflows(): Obj[] {
  return yamlFiles('workflows').map((file) => {
    const doc = load(file)
    const meta = asObj(doc.metadata)
    return { name: String(meta.name), title: String(meta.title ?? meta.name), version: String(meta.version ?? '0.1.0'), input: asObj(doc.api).input ?? {}, output: { type: 'object' } }
  })
}

export const VM_RESTART_FILE = 'workflows/team-compute/vm-restart.yaml'

export function vmRestartYaml(): string {
  return readFileSync(join(ROOT, VM_RESTART_FILE), 'utf8')
}

export function vmRestart(): Obj {
  return parse(vmRestartYaml()) as Obj
}

/** Every step id of a `do` list, depth first, as a dry run walks a happy path. */
function stepIds(list: Json | undefined): string[] {
  const out: string[] = []
  for (const entry of Array.isArray(list) ? list : []) {
    const [id, body] = Object.entries(asObj(entry))[0] ?? []
    if (!id) continue
    out.push(id)
    const b = asObj(body)
    if (b.for) out.push(...stepIds(asObj(b.for).do))
    if (b.try) out.push(...stepIds(asObj(b.try).do))
  }
  return out
}

export interface MockApi {
  /** bodies of POST /workflows:validate */
  validations: Obj[]
  /** query strings of GET /playbooks:resolve */
  resolves: string[]
  /** raw bodies of POST /playbooks:upload (multipart) */
  uploads: string[]
  /** bodies of PUT /workflows/{name}/draft */
  drafts: Obj[]
  /** bodies of POST /workflows:dry-run */
  dryRuns: Obj[]
  /** bodies of POST /workflows */
  created: Obj[]
  /** bodies of POST /workflows/{name}:publish */
  publishes: Obj[]
  /** ids of merged change requests */
  merged: string[]
  /** messages sent to the assistant */
  assistant: string[]
}

/** Two jobs of vm-restart: one failed at the playbook, one that went through. */
export function vmRestartJobs(): { summaries: Obj[]; details: Record<string, Obj> } {
  const base = { workflow: 'vm-restart', title: 'VM restart', version: '1.2.0', requester: 'gonzalo', kind: 'single', approvals: [], traceId: 'trace-1' }
  const ok = (id: string, name: string, type: string, input: Json, output: Json, ms: number) => ({ id, name, type, status: 'succeeded', attempt: 1, durationMs: ms, startedAt: '2026-10-16T09:00:00Z', input, output })
  const failed: Obj = {
    ...base,
    id: 'job_1042',
    status: 'failed',
    channel: 'catalog',
    startedAt: '2026-10-16T09:00:00Z',
    endedAt: '2026-10-16T09:01:24Z',
    durationMs: 84000,
    input: { vmId: 'vm-1042', reason: 'unresponsive', environment: 'prod' },
    steps: [
      ok('checkVmExists', 'Check VM exists', 'provider', { id: 'vm-1042' }, { id: 'vm-1042', name: 'web-1042', powerState: 'poweredOn', cpu: 4, memoryGb: 16, environment: 'prod' }, 420),
      ok('noBackupRunning', 'No backup running', 'provider', { target: 'vm-1042', state: 'running' }, { items: [], total: 0 }, 310),
      ok('approval', 'Approve restart', 'approval', { group: 'compute-approvers' }, { approved: true, by: 'alice', group: 'compute-approvers' }, 61000),
      { id: 'restart', name: 'Restart with Ansible', type: 'ansible', status: 'failed', attempt: 1, durationMs: 21000, input: { limit: 'web-1042' }, error: { class: 'AnsibleFailed', message: 'web-1042: host unreachable', retryable: true } },
      ok('notifyOwner', 'Tell the on-call', 'notify', { channel: '#compute-alerts' }, { sent: true }, 200),
    ],
  }
  const passed: Obj = {
    ...base,
    id: 'job_1041',
    status: 'succeeded',
    channel: 'mcp',
    startedAt: '2026-10-15T14:00:00Z',
    endedAt: '2026-10-15T14:03:00Z',
    durationMs: 180000,
    input: { vmId: 'vm-0007', reason: 'patching', environment: 'dev' },
    steps: [
      ok('checkVmExists', 'Check VM exists', 'provider', { id: 'vm-0007' }, { id: 'vm-0007', name: 'db-0007', powerState: 'poweredOn' }, 400),
      ok('noBackupRunning', 'No backup running', 'provider', {}, { items: [], total: 0 }, 300),
      ok('approval', 'Approve restart', 'approval', {}, { approved: true, by: 'bob' }, 9000),
      ok('restart', 'Restart with Ansible', 'ansible', {}, { ok: 4, changed: 2, failed: 0 }, 90000),
      ok('validate', 'Wait for power on', 'provider', {}, { id: 'vm-0007', powerState: 'poweredOn' }, 60000),
      ok('notifyDone', 'Tell the channel', 'notify', {}, { sent: true }, 150),
    ],
  }
  const summary = (j: Obj): Obj => Object.fromEntries(Object.entries(j).filter(([k]) => !['input', 'steps', 'approvals', 'traceId'].includes(k)))
  return { summaries: [summary(failed), summary(passed)], details: { job_1042: failed, job_1041: passed } }
}

const DIFF = `--- a/workflows/team-compute/vm-restart.yaml
+++ b/workflows/team-compute/vm-restart.yaml
@@ -9,7 +9,7 @@ metadata:
   folder: Compute
   tags: [vsphere, ops]
-  version: 1.2.0
+  version: 1.3.0
   description: Restart a virtual machine after safety checks, with an approval for production.
`

function sse(events: Obj[]): string {
  return events.map((e) => `event: ${String(e.type)}\ndata: ${JSON.stringify(e)}\n\n`).join('')
}

const ME = { id: 'gonzalo', name: 'Gonzalo', email: 'gonzalo@example.com', teams: ['team-compute', 'team-platform'], roles: ['ops', 'admin'], admin: true, kind: 'user' }

function json(route: Route, body: unknown, status = 200): Promise<void> {
  return route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) })
}

function bodyOf(req: Request): Obj {
  try {
    return asObj(req.postDataJSON() as Json)
  } catch {
    return {}
  }
}

/** Mocks every endpoint the designer calls; returns what the page sent. */
export async function mockDesignerApi(page: Page): Promise<MockApi> {
  const calls: MockApi = { validations: [], resolves: [], uploads: [], drafts: [], dryRuns: [], created: [], publishes: [], merged: [], assistant: [] }
  const jobs = vmRestartJobs()
  const providers = registryProviders()
  const resourceTypes = registryResourceTypes()
  const workflows = registryWorkflows()
  const definition = vmRestart()
  const meta = asObj(definition.metadata)
  const detail = {
    name: 'vm-restart',
    title: meta.title,
    noun: meta.noun,
    verb: meta.verb,
    persona: meta.persona,
    folder: meta.folder,
    owner: meta.owner,
    tags: meta.tags,
    status: 'published',
    latestVersion: meta.version,
    publishedVersion: meta.version,
    stepCount: stepIds(asObj(definition.workflow).do).length,
    updatedAt: '2026-10-01T09:00:00Z',
    mcp: true,
    yaml: vmRestartYaml(),
    definition,
    versions: [{ version: meta.version, status: 'published', contentHash: 'sha256:1', publishedAt: '2026-10-01T09:00:00Z', publishedBy: 'gonzalo' }],
    problems: [],
    changeRequests: [],
  }

  await page.route('**/api/v1/**', async (route) => {
    const req = route.request()
    const url = new URL(req.url())
    const path = url.pathname.replace(/^\/api\/v1/, '')
    const method = req.method()
    if (method === 'GET' && path === '/me') return json(route, ME)
    if (method === 'GET' && path === '/workflows/vm-restart') return json(route, detail)
    if (method === 'GET' && path === '/workflows') return json(route, [detail])
    if (method === 'GET' && path === '/registry/step-types') return json(route, [])
    if (method === 'GET' && path === '/registry/providers') return json(route, providers)
    if (method === 'GET' && path === '/registry/workflows') return json(route, workflows)
    if (method === 'GET' && path === '/registry/resource-types') return json(route, resourceTypes)
    if (method === 'GET' && path === '/registry/playbooks') return json(route, registryPlaybooks())
    if (method === 'GET' && path === '/registry/credentials')
      return json(route, [
        { name: 'ssh/ops-automation', type: 'ssh' },
        { name: 'vsphere/token', type: 'token' },
      ])
    if (method === 'GET' && path === '/templates') return json(route, [])
    if (method === 'GET' && path === '/jobs') return json(route, url.searchParams.get('workflow') === 'vm-restart' ? jobs.summaries : [])
    const jobMatch = /^\/jobs\/([^/]+)$/.exec(path)
    if (method === 'GET' && jobMatch) return jobs.details[jobMatch[1]] ? json(route, jobs.details[jobMatch[1]]) : json(route, { error: 'no such job' }, 404)
    if (method === 'GET' && /^\/jobs\/[^/]+\/events$/.test(path)) return route.fulfill({ status: 200, contentType: 'text/event-stream', body: '' })
    if (method === 'GET' && path === '/workflows/vm-restart/versions/1.2.0') return json(route, { version: '1.2.0', yaml: vmRestartYaml(), definition })
    if (method === 'POST' && path === '/workflows/vm-restart:publish') {
      calls.publishes.push(bodyOf(req))
      return json(route, { id: 'cr_7', status: 'accepted' }, 202)
    }
    if (method === 'GET' && path === '/change-requests/cr_7') {
      const merged = calls.merged.includes('cr_7')
      return json(route, {
        id: 'cr_7',
        kind: 'workflow',
        name: 'vm-restart',
        version: '1.3.0',
        branch: 'workflow/vm-restart-1.3.0',
        title: 'Publish vm-restart 1.3.0',
        author: 'gonzalo',
        status: merged ? 'merged' : 'open',
        strategy: 'canary',
        createdAt: '2026-10-17T10:00:00Z',
        checks: [
          { id: 'checks', label: 'Workflow checks', status: 'passed' },
          { id: 'dry-run', label: 'Dry run', status: 'passed', detail: '7 steps' },
          { id: 'replay', label: 'Replay of recent jobs', status: 'passed', detail: '42 jobs, no divergence' },
        ],
        diff: DIFF,
        yaml: vmRestartYaml(),
      })
    }
    if (method === 'POST' && path === '/change-requests/cr_7:merge') {
      calls.merged.push('cr_7')
      return json(route, { id: 'cr_7', status: 'accepted' }, 202)
    }
    if (method === 'GET' && path === '/releases')
      return json(
        route,
        calls.merged.includes('cr_7')
          ? [{ id: 'rel_9', workflow: 'vm-restart', from: '1.2.0', to: '1.3.0', strategy: 'canary', status: 'canary', stageIndex: 0, stages: [10, 50, 100], stagePercent: 10, startedAt: '2026-10-17T10:05:00Z', stageStartedAt: '2026-10-17T10:05:00Z', gates: [], jobs: { current: 0, candidate: 0 }, history: [] }]
          : [],
      )
    if (method === 'POST' && path === '/assistant/sessions') return json(route, { sessionId: 'as_1' })
    if (method === 'POST' && path === '/assistant/sessions/as_1/messages') {
      calls.assistant.push(String(bodyOf(req).text ?? ''))
      const draft = vmRestart()
      const flow = asObj(draft.workflow)
      const steps = Array.isArray(flow.do) ? flow.do : []
      flow.do = [{ announce: { name: 'Announce the restart', notify: { channel: '#compute-ops', message: 'Restarting {{ $input.vmId }}' } } }, ...steps]
      return route.fulfill({
        status: 200,
        contentType: 'text/event-stream',
        body: sse([
          { type: 'status', data: { text: 'Reading the workflow and the registry' } },
          { type: 'message', data: { text: 'I added a step that tells #compute-ops before anything happens.' } },
          { type: 'draft', data: { status: 'ready', summary: 'Announce the restart in #compute-ops first.', workflowYaml: stringify(draft), changes: ['Added "Announce the restart" before "Check VM exists"'], validation: { valid: true }, dryRun: { passed: true } } },
          { type: 'done', data: {} },
        ]),
      })
    }
    if (method === 'POST' && path === '/workflows:import') {
      const body = bodyOf(req)
      const imported = body.source === 'terraform' ? terraformImport(String(body.ref ?? '')) : null
      return imported ? json(route, imported) : json(route, { error: `No terraform provider '${String(body.ref ?? '')}'` }, 400)
    }
    if (method === 'POST' && path === '/workflows:render') return json(route, { yaml: stringify(bodyOf(req).definition ?? {}) })
    if (method === 'POST' && path === '/workflows:parse') {
      try {
        return json(route, { definition: parse(String(bodyOf(req).yaml ?? '')) as Json, problems: [] })
      } catch (e) {
        return json(route, { definition: null, problems: [{ level: 'error', code: 'yaml.invalid', message: e instanceof Error ? e.message : String(e) }] })
      }
    }
    if (method === 'PUT' && path === '/workflows/vm-restart/draft') {
      const body = bodyOf(req)
      calls.drafts.push(body)
      return json(route, { name: 'vm-restart', version: '1.3.0', problems: [] })
    }
    if (method === 'POST' && path === '/workflows') {
      calls.created.push(bodyOf(req))
      return json(route, { id: 'cmd_1', status: 'accepted' }, 202)
    }
    if (method === 'POST' && path === '/workflows:dry-run') {
      const body = bodyOf(req)
      calls.dryRuns.push(body)
      const ids = stepIds(asObj(asObj(body.definition).workflow).do)
      return json(route, { passed: true, path: ids, steps: ids.map((id) => ({ id, name: id, status: 'succeeded', input: {}, output: { ok: true, id } })) })
    }
    // the backend's checks plus the publish checks, as docs/config-as-code.md lists them
    if (method === 'POST' && path === '/workflows:validate') {
      const body = bodyOf(req)
      calls.validations.push(body)
      const v = String(asObj(asObj(body.definition).metadata).version ?? '')
      return json(route, {
        valid: false,
        problems: [
          { level: 'error', code: 'publish.ansibleLint', step: 'restart', field: 'playbook', message: 'The playbook is not pinned to a commit: every run would use whatever main points at' },
          { level: 'error', code: 'publish.routes', field: 'api.path', message: 'POST /api/v1/vms/{vmId}:restart is already answered by vm-reboot 1.0.0' },
          { level: 'error', code: 'publish.contracts', field: 'metadata.version', message: `${v} breaks the contract of 1.2.0 without a new major version: input.reason became required. Affected consumers: web-environment`, fix: { label: 'Publish as 2.0.0', kind: 'replace', value: '2.0.0' } },
        ],
      })
    }
    if (method === 'GET' && path === '/playbooks:resolve') {
      calls.resolves.push(url.search)
      const repo = url.searchParams.get('repo') ?? ''
      const ref = url.searchParams.get('ref') ?? ''
      if (ref === 'gone') return json(route, { error: `${repo} has no ref ${ref}` }, 400)
      return json(route, { repo, ref, commit: '9fceb02d0ae598e95dc970b74767f19372d61af8' })
    }
    if (method === 'POST' && path === '/playbooks:upload') {
      const raw = req.postData() ?? ''
      calls.uploads.push(raw)
      if (raw.includes('too-large')) return json(route, { error: 'The file is larger than 8 MB (platform.playbooks.max-upload-size)' }, 413)
      if (raw.includes('not-an-archive')) return json(route, { error: 'The file is not a tar.gz, zip or YAML playbook' }, 400)
      return json(route, { file: 'uploads/restart-3f1c.tar.gz', sha256: '3f1c7a2b'.repeat(8), size: 20480, entries: ['site.yml', 'roles/restart/tasks/main.yml'] }, 201)
    }
    return json(route, { error: `not mocked: ${method} ${path}` }, 404)
  })
  return calls
}
