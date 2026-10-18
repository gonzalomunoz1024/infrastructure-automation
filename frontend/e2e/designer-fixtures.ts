/**
 * The backend the designer spec talks to, mocked with page.route: the real vm-restart workflow
 * and the real provider, resource-type and workflow files of the repository, read with `yaml`
 * here in Node (the browser never parses YAML; the mocked render and parse endpoints do it).
 */
import { readdirSync, readFileSync } from 'node:fs'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'
import type { Locator, Page, Request, Route } from '@playwright/test'
import { parse, stringify } from 'yaml'

const ROOT = fileURLToPath(new URL('../../', import.meta.url))

/** The YAML drawer's editor once Monaco is up (its chunk loads when the drawer opens). */
export async function yamlEditor(page: Page): Promise<Locator> {
  const editor = page.getByTestId('yaml-editor')
  await editor.locator('.monaco-editor .view-lines').waitFor({ timeout: 20_000 })
  return editor
}

/** Replaces a Monaco editor's text the way a paste does: Monaco takes pasted text as it is. */
export async function pasteInto(page: Page, editor: Locator, text: string): Promise<void> {
  await editor.locator('.view-lines').click()
  await page.keyboard.press('ControlOrMeta+A')
  // as a string: this project's e2e types carry no DOM
  await page.evaluate(`(() => {
    const data = new DataTransfer()
    data.setData('text/plain', ${JSON.stringify(text)})
    document.activeElement?.dispatchEvent(new ClipboardEvent('paste', { clipboardData: data, bubbles: true, cancelable: true }))
  })()`)
}

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
      // as WorkflowImports writes it: every workflow names who may run it
      policy: { approval: { required: true, group: 'your-approvers', timeout: '4h' }, rbac: ['ops'] },
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

/** Every `{ <step id>: { run: { workflow } } }` under a node, nested steps included: the run links DependencyRules reads. */
function runSteps(node: Json | undefined, out: Obj[]): void {
  if (Array.isArray(node)) {
    for (const n of node) runSteps(n, out)
    return
  }
  if (!node || typeof node !== 'object') return
  for (const [key, value] of Object.entries(node)) {
    const run = asObj(asObj(value).run)
    if (typeof run.workflow === 'string') out.push({ workflow: run.workflow, via: 'run', at: key, ...(typeof run.version === 'string' ? { range: run.version } : {}) })
    runSteps(value, out)
  }
}

/**
 * GET /registry/dependencies over the repository's workflows, the part the designer's checks read:
 * what each workflow starts through its run steps and `orCreateWith` inputs (the types each one
 * produces and consumes are left empty).
 */
export function registryDependencies(): Obj {
  const workflows = yamlFiles('workflows').map((file) => {
    const doc = load(file)
    const meta = asObj(doc.metadata)
    const runs: Obj[] = []
    for (const [field, p] of Object.entries(asObj(asObj(asObj(doc.api).input).properties))) {
      const xr = asObj(asObj(p)['x-resource'])
      if (typeof xr.type === 'string' && typeof xr.orCreateWith === 'string') runs.push({ workflow: xr.orCreateWith, via: 'orCreateWith', at: field })
    }
    runSteps(asObj(doc.workflow).do, runs)
    return { workflow: String(meta.name), version: String(meta.version ?? '0.1.0'), produces: [], consumes: [], runs, consumers: [] }
  })
  return { resourceTypes: [], workflows, cycles: [] }
}

const slug = (v: Json | undefined) =>
  String(v ?? '')
    .toLowerCase()
    .trim()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/(^-|-$)/g, '')

/**
 * GET /catalog over the repository's workflows, the fields that name an entry and the MCP tool
 * the designer's checks read: `<noun>_<verb>` as McpTools spells it, on an exposed workflow only.
 */
export function catalogEntries(): Obj[] {
  return yamlFiles('workflows').map((file) => {
    const doc = load(file)
    const meta = asObj(doc.metadata)
    const tool = `${slug(meta.noun)}_${slug(meta.verb)}`.replace(/-/g, '_')
    return {
      workflow: String(meta.name),
      title: String(meta.title ?? meta.name),
      noun: String(meta.noun ?? ''),
      verb: String(meta.verb ?? ''),
      owner: String(meta.owner ?? ''),
      version: String(meta.version ?? '0.1.0'),
      input: asObj(doc.api).input ?? {},
      ...(asObj(doc.mcp).expose === true ? { mcpTool: tool } : {}),
    }
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
  /** YAML bodies of POST /workflows:parse */
  parses: string[]
  /** bodies of POST /credentials (the only place a value goes) */
  credentials: Obj[]
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

/** An SSE body the way the backend writes the assistant's stream: the type as the event name, the payload alone as the data. */
function sse(events: { type: string; data: unknown }[]): string {
  return events.map((e, i) => `id:${i + 1}\nevent:${e.type}\ndata:${JSON.stringify(e.data)}\n\n`).join('')
}

/** The demo agent's answer to "Explain what this workflow does": prose, and the workflow unchanged. */
export const EXPLANATION = 'VM restart takes a VM id, checks the VM exists and that no backup runs, asks compute-approvers to approve, restarts it with Ansible and waits for it to power on, then tells the owner.'

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
  const calls: MockApi = { validations: [], resolves: [], uploads: [], drafts: [], dryRuns: [], created: [], publishes: [], merged: [], assistant: [], parses: [], credentials: [] }
  // the canvas the assistant session started from, which an explanation hands back unchanged
  let sessionYaml = ''
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
    if (method === 'GET' && path === '/registry/dependencies') return json(route, registryDependencies())
    if (method === 'GET' && path === '/catalog') return json(route, catalogEntries())
    if (method === 'GET' && path === '/registry/credentials')
      return json(route, [
        { name: 'ssh/ops-automation', type: 'ssh-key' },
        { name: 'vsphere/token', type: 'token' },
        { name: 'github/read-token', type: 'token' },
      ])
    // as CredentialsHandler answers: stored at once, its reference as the id
    if (method === 'POST' && path === '/credentials') {
      const body = bodyOf(req)
      calls.credentials.push(body)
      return json(route, { id: `vault:${String(body.name ?? '')}`, status: 'stored' }, 202)
    }
    if (method === 'GET' && path === '/templates') return json(route, [])
    if (method === 'GET' && path === '/jobs') {
      const status = url.searchParams.get('status')
      return json(route, url.searchParams.get('workflow') === 'vm-restart' ? jobs.summaries.filter((j) => !status || j.status === status) : [])
    }
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
    if (method === 'POST' && path === '/assistant/sessions') {
      sessionYaml = String(bodyOf(req).draftYaml ?? '')
      return json(route, { sessionId: 'as_1' })
    }
    if (method === 'POST' && path === '/assistant/sessions/as_1/messages') {
      const text = String(bodyOf(req).text ?? '')
      calls.assistant.push(text)
      if (/^explain/i.test(text)) {
        return route.fulfill({
          status: 200,
          contentType: 'text/event-stream',
          body: sse([
            { type: 'status', data: { status: 'Reading the workflow' } },
            { type: 'draft', data: { status: 'ready', summary: EXPLANATION, workflowYaml: sessionYaml, validation: { valid: true, rounds: 1, errors: [] } } },
            { type: 'validation', data: { valid: true, problems: [] } },
            { type: 'message', data: { role: 'assistant', text: EXPLANATION } },
            { type: 'done', data: { status: 'done' } },
          ]),
        })
      }
      const draft = vmRestart()
      const flow = asObj(draft.workflow)
      const steps = Array.isArray(flow.do) ? flow.do : []
      flow.do = [{ announce: { name: 'Announce the restart', notify: { channel: '#compute-ops', message: 'Restarting {{ $input.vmId }}' } } }, ...steps]
      return route.fulfill({
        status: 200,
        contentType: 'text/event-stream',
        body: sse([
          { type: 'status', data: { status: 'Reading the workflow and the registry' } },
          { type: 'message', data: { role: 'assistant', text: 'I added a step that tells #compute-ops before anything happens.' } },
          { type: 'draft', data: { status: 'ready', summary: 'Announce the restart in #compute-ops first.', workflowYaml: stringify(draft), changes: ['Added "Announce the restart" before "Check VM exists"'], validation: { valid: true, rounds: 1, errors: [] }, dryRun: { passed: true } } },
          { type: 'validation', data: { valid: true, problems: [] } },
          { type: 'done', data: { status: 'done' } },
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
      calls.parses.push(String(bodyOf(req).yaml ?? ''))
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
          { level: 'error', code: 'publish.playbooks', step: 'restart', field: 'playbook', message: 'The playbooks check of the change request fails: pin the playbook to a commit' },
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

/* ----------------------------------------------------------------------------
   A provider draft of vm-restart, and the backend's provider review and publish
   ------------------------------------------------------------------------- */

/** Where the designer keeps vm-restart's provider drafts in this browser. */
export const VM_RESTART_DRAFTS_KEY = 'iaf-designer-provider-drafts:vm-restart'

/**
 * A draft of an HTTP provider not published yet, as the designer stores one (the Add provider
 * wizard's state): one action that creates a snapshot, its credential a vault: reference.
 */
export const SNAPSHOT_DRAFT = {
  id: 'draft_snapshots',
  origin: 'new',
  publishedVersion: null,
  type: 'http',
  title: 'Snapshot API',
  name: 'snapshots',
  nameTouched: true,
  owner: 'team-compute',
  ownerTouched: true,
  version: '1.0.0',
  environments: [{ key: 1, name: 'dev', url: 'https://snapshots-dev.example.com' }],
  auth: { type: 'bearer', credential: 'vault:snapshots/token', header: '' },
  actions: [
    {
      key: 2,
      selected: true,
      id: 'snapshot.create',
      title: 'Create snapshot',
      operation: '',
      method: 'POST',
      path: '/snapshots',
      playbook: '',
      effect: 'create',
      retry: 'unsafe',
      approval: false,
      plan: '',
      effectDetail: '',
      description: '',
      inputText: '{"type":"object","properties":{"vm":{"type":"string"}}}',
      outputText: '',
      exampleText: '{"id":"snap-1","state":"ready"}',
      sloSuccessRate: '',
      sloP95Ms: '',
    },
  ],
  errors: [],
  strategy: 'canary',
}

export interface MockProviderPublish {
  /** bodies of POST /workflows:validate that sent provider drafts */
  validations: Obj[]
  /** YAML bodies of POST /providers:validate */
  reviews: string[]
  /** bodies of POST /providers:publish */
  publishes: Obj[]
}

/**
 * On top of mockDesignerApi: the backend with draft providers. Validate with drafts finds nothing
 * to fix; the review of a draft is ready; publishing it opens change request cr_8, which the
 * workflow's change request cr_7 requires and which must merge first; once merged the registry
 * lists the provider. Everything else falls through to mockDesignerApi.
 */
export async function mockProviderPublish(page: Page, api: MockApi): Promise<MockProviderPublish> {
  const calls: MockProviderPublish = { validations: [], reviews: [], publishes: [] }
  const providers = registryProviders()
  const snapshots = { name: 'snapshots', title: 'Snapshot API', type: 'http', owner: 'team-compute', actions: [{ id: 'snapshot.create', title: 'Create snapshot', effect: 'create', retry: 'unsafe', approval: 'none', input: { type: 'object', properties: { vm: { type: 'string' } } }, output: null, sample: { id: 'snap-1', state: 'ready' } }] }
  const changeRequest = (id: string, kind: string, name: string, version: string, extra: Obj = {}): Obj => ({
    id,
    kind,
    name,
    version,
    branch: `${kind}/${name}-${version}`,
    title: `Publish ${name} ${version}`,
    author: 'gonzalo',
    status: api.merged.includes(id) ? 'merged' : 'open',
    strategy: 'canary',
    createdAt: '2026-10-18T10:00:00Z',
    checks: [
      { id: 'checks', label: kind === 'provider' ? 'Provider checks' : 'Workflow checks', status: 'passed' },
      { id: 'review', label: kind === 'provider' ? 'Review evidence' : 'Dry run', status: 'passed' },
    ],
    diff: `--- /dev/null\n+++ b/${kind}s/team-compute/${name}.yaml\n@@ -0,0 +1 @@\n+kind: ${kind === 'provider' ? 'Provider' : 'Workflow'}\n`,
    yaml: '',
    ...extra,
  })
  await page.route('**/api/v1/**', async (route) => {
    const req = route.request()
    const path = new URL(req.url()).pathname.replace(/^\/api\/v1/, '')
    const method = req.method()
    const body = bodyOf(req)
    if (method === 'POST' && path === '/workflows:validate' && Array.isArray(body.providers)) {
      calls.validations.push(body)
      return json(route, { valid: true, problems: [] })
    }
    if (method === 'POST' && path === '/providers:validate') {
      calls.reviews.push(String(body.yaml ?? ''))
      return json(route, {
        checks: [{ id: 'schema', label: 'Provider schema', status: 'passed' }],
        problems: [],
        review: { ready: true, checks: [{ id: 'connection', label: 'Connection tested', status: 'passed', detail: 'dev answered in 120 ms' }], problems: [] },
      })
    }
    if (method === 'POST' && path === '/providers:publish') {
      calls.publishes.push(body)
      return json(route, { id: 'cr_8', status: 'accepted' }, 202)
    }
    if (method === 'GET' && path === '/registry/providers') return json(route, api.merged.includes('cr_8') ? [...providers, snapshots] : providers)
    if (method === 'GET' && path === '/change-requests/cr_8') return json(route, changeRequest('cr_8', 'provider', 'snapshots', '1.0.0'))
    if (method === 'GET' && path === '/change-requests/cr_7') return json(route, changeRequest('cr_7', 'workflow', 'vm-restart', '1.3.0', { requires: ['cr_8'] }))
    if (method === 'POST' && path === '/change-requests/cr_8:merge') {
      api.merged.push('cr_8')
      return json(route, { id: 'cr_8', status: 'accepted' }, 202)
    }
    // as the backend: the workflow merges once the change requests it requires have
    if (method === 'POST' && path === '/change-requests/cr_7:merge' && !api.merged.includes('cr_8')) return json(route, { error: 'cr_7 requires cr_8, which is not merged' }, 409)
    return route.fallback()
  })
  return calls
}
