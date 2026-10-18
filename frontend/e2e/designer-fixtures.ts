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
  /** bodies of PUT /workflows/{name}/draft */
  drafts: Obj[]
  /** bodies of POST /workflows:dry-run */
  dryRuns: Obj[]
  /** bodies of POST /workflows */
  created: Obj[]
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
  const calls: MockApi = { drafts: [], dryRuns: [], created: [] }
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
    if (method === 'GET' && path === '/jobs') return json(route, [])
    if (method === 'GET' && path === '/releases') return json(route, [])
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
    return json(route, { error: `not mocked: ${method} ${path}` }, 404)
  })
  return calls
}
