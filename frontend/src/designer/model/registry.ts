/**
 * The registry the designer browses: step types, providers and their actions, published
 * workflows, resource types, playbooks and credential names, and for the checks alone what each
 * published workflow starts and the MCP tools they hold. Loaded once per session; every part
 * fails independently so the canvas works with whatever answered. A credential stored from a
 * step's credential field joins the loaded list instead of loading it all again. The store merges
 * the workflow's provider drafts into what the canvas reads (`withUnpublished`).
 */
import { buildContext, EMPTY_CONTEXT, STEP_TYPES, type CheckContext, type JsonObject, type JsonSchema, type ProviderDocument, type StepType, type WorkflowDocument } from '@iaf/workflow-checks'
import { api } from '../../lib/api'
import type { CatalogEntry, Dependencies, RegistryAction, RegistryCredential, RegistryPlaybook, RegistryProvider, RegistryWorkflow, ResourceType, Schema, StepTypeInfo, TerraformModuleRef } from '../../lib/types'
import { FALLBACK_STEP_SCHEMAS } from './stepSchemas'

export interface Registry {
  stepTypes: StepTypeInfo[]
  providers: RegistryProvider[]
  workflows: RegistryWorkflow[]
  resourceTypes: ResourceType[]
  playbooks: RegistryPlaybook[]
  credentials: RegistryCredential[]
  /**
   * The workflows each published workflow starts, by name (GET /registry/dependencies); absent
   * when that did not answer, and a cycle through other workflows (`run.cycle`) is then left to
   * the backend's checks.
   */
  workflowRuns?: Record<string, string[]>
  /**
   * The published workflows each MCP tool belongs to, by tool name (GET /catalog); absent when
   * that did not answer, and `mcp.toolName` is then left to the backend's checks.
   */
  mcpTools?: Record<string, string[]>
  /** the parts that did not answer */
  failed: string[]
}

export const EMPTY_REGISTRY: Registry = { stepTypes: [], providers: [], workflows: [], resourceTypes: [], playbooks: [], credentials: [], failed: [] }

let cached: Promise<Registry> | null = null

async function part<T>(name: string, fn: () => Promise<T>, failed: string[], fallback: T): Promise<T> {
  try {
    return await fn()
  } catch {
    failed.push(name)
    return fallback
  }
}

/** A part only the checks read: undefined when it does not answer; the lists the canvas shows do not miss it. */
async function optional<T>(fn: () => Promise<T>): Promise<T | undefined> {
  try {
    return await fn()
  } catch {
    return undefined
  }
}

/**
 * What each published workflow starts, by name, as DependencyRules.runs reads it: the run steps
 * and `orCreateWith` inputs GET /registry/dependencies lists under `runs`, each once, sorted.
 */
export function workflowRunsOf(deps: Pick<Dependencies, 'workflows'>): Record<string, string[]> {
  const out: Record<string, string[]> = {}
  for (const w of deps.workflows ?? []) {
    const started = [...new Set((w.runs ?? []).map((l) => l.workflow).filter((n) => typeof n === 'string' && n !== ''))].sort()
    if (started.length > 0) out[w.workflow] = started
  }
  return out
}

/**
 * The published workflows the MCP server gives each tool, by tool name, as McpTools.toolNames
 * spells them: an exposed catalog entry's tool (`vm_restart`) and its batch tool
 * (`vm_restart_batch`). Two workflows may claim one name; the server lists the first.
 */
export function mcpToolsOf(catalog: readonly Pick<CatalogEntry, 'workflow' | 'mcpTool'>[]): Record<string, string[]> {
  const out: Record<string, string[]> = {}
  for (const e of catalog) {
    const tool = e.mcpTool?.trim()
    if (!tool || tool === '_') continue
    for (const name of [tool, `${tool}_batch`]) out[name] = [...new Set([...(out[name] ?? []), e.workflow])].sort()
  }
  return out
}

/** Loads every registry endpoint once; a later call returns the same promise. */
export function loadRegistry(force = false): Promise<Registry> {
  if (cached && !force) return cached
  cached = (async () => {
    const failed: string[] = []
    const [stepTypes, providers, workflows, resourceTypes, playbooks, credentials, dependencies, catalog] = await Promise.all([
      part('step types', () => api.registryStepTypes(), failed, [] as StepTypeInfo[]),
      part('providers', () => api.registryProviders(), failed, [] as RegistryProvider[]),
      part('workflows', () => api.registryWorkflows(true), failed, [] as RegistryWorkflow[]),
      part('resource types', () => api.registryResourceTypes(), failed, [] as ResourceType[]),
      part('playbooks', () => api.registryPlaybooks(), failed, [] as RegistryPlaybook[]),
      part('credentials', () => api.registryCredentials(), failed, [] as RegistryCredential[]),
      optional(() => api.registryDependencies()),
      optional(() => api.catalog()),
    ])
    return {
      stepTypes,
      providers: await withTerraformModules(providers),
      workflows,
      resourceTypes,
      playbooks,
      credentials,
      ...(dependencies ? { workflowRuns: workflowRunsOf(dependencies) } : {}),
      ...(catalog ? { mcpTools: mcpToolsOf(catalog) } : {}),
      failed,
    }
  })()
  return cached
}

/** The source the backend's import writes when a terraform provider has no module block. */
const PLACEHOLDER_MODULE_SOURCE = 'your-module-source'

const isObj = (v: unknown): v is Record<string, unknown> => typeof v === 'object' && v !== null && !Array.isArray(v)

/** The module of the first `call: terraform` step of a parsed workflow; null when there is none, or only the import's placeholder. */
export function terraformModuleIn(doc: WorkflowDocument | null | undefined): TerraformModuleRef | null {
  for (const entry of doc?.workflow?.do ?? []) {
    for (const body of Object.values(entry ?? {})) {
      if (!isObj(body) || body.call !== 'terraform' || !isObj(body.with)) continue
      const module = body.with.module
      if (!isObj(module) || typeof module.source !== 'string' || !module.source.trim() || module.source === PLACEHOLDER_MODULE_SOURCE) return null
      const out: TerraformModuleRef = { source: module.source }
      for (const key of ['version', 'ref', 'commit', 'credential'] as const) if (typeof module[key] === 'string') out[key] = module[key] as string
      return out
    }
  }
  return null
}

/**
 * A terraform provider's `spec.module`, read through the backend: GET /registry/providers carries
 * no spec, POST /workflows:import drafts the provider's plan and apply steps with its module, and
 * POST /workflows:parse gives them back as JSON (the browser parses no YAML).
 */
export async function terraformModuleOf(provider: string): Promise<TerraformModuleRef | null> {
  const imported = await api.importWorkflow('terraform', provider)
  const parsed = await api.parseWorkflow({ yaml: imported.yaml })
  return terraformModuleIn(parsed.definition)
}

/** The providers with each terraform provider's module filled in when the registry did not carry it; one that cannot be read stays as it was. */
export async function withTerraformModules(providers: RegistryProvider[], resolve: (provider: string) => Promise<TerraformModuleRef | null> = terraformModuleOf): Promise<RegistryProvider[]> {
  return Promise.all(
    providers.map(async (p) => {
      if (p.type !== 'terraform' || p.module) return p
      try {
        const module = await resolve(p.name)
        return module ? { ...p, module } : p
      } catch {
        return p
      }
    }),
  )
}

/** The registry with one more credential, in place of one of the same name (storing a name again replaces it). */
export function withCredential(registry: Registry, credential: RegistryCredential): Registry {
  return { ...registry, credentials: [...registry.credentials.filter((c) => c.name !== credential.name), credential] }
}

/**
 * The registry with the providers the workflow uses before they are published (its drafts, the
 * ones in review), each in place of a published provider of the same name, so the canvas reads
 * them as published; the first of a name wins. The same registry when there are none.
 */
export function withUnpublished(registry: Registry, providers: readonly RegistryProvider[]): Registry {
  const named = providers.filter((p, i) => p.name && providers.findIndex((x) => x.name === p.name) === i)
  if (named.length === 0) return registry
  const names = new Set(named.map((p) => p.name))
  return { ...registry, providers: [...registry.providers.filter((p) => !names.has(p.name)), ...named] }
}

/** How a provider is named wherever the designer shows it: its title, tagged when it is not published yet. */
export function providerLabel(p: Pick<RegistryProvider, 'title' | 'unpublished'>): string {
  return p.unpublished ? `${p.title} (${p.unpublished})` : p.title
}

/** A credential stored in this session: every registry loadRegistry hands out from now on lists it. */
export function rememberCredential(credential: RegistryCredential): void {
  if (cached) cached = cached.then((r) => withCredential(r, credential))
}

/** For tests and the executions view: a registry without the network. */
export function setRegistryForTests(r: Registry | null): void {
  cached = r ? Promise.resolve(r) : null
}

/** A registry provider as the checks read a `kind: Provider` document. */
export function toProviderDocument(p: RegistryProvider): ProviderDocument {
  return {
    apiVersion: 'iaf.platform/v1',
    kind: 'Provider',
    metadata: { name: p.name, title: p.title },
    spec: {
      type: p.type,
      actions: p.actions.map((a) => ({
        id: a.id,
        title: a.title,
        effect: a.effect,
        retry: a.retry,
        approval: a.approval === true ? 'required' : a.approval === false ? 'none' : a.approval,
        input: (a.input ?? undefined) as JsonObject | undefined,
        output: (a.output ?? undefined) as JsonObject | undefined,
        sample: (a.sample ?? undefined) as JsonObject | undefined,
      })),
    },
  } as ProviderDocument
}

const isObject = (v: unknown): v is Record<string, unknown> => typeof v === 'object' && v !== null && !Array.isArray(v)

/**
 * The check context: the catalog when the providers answered, the empty one otherwise. The
 * published workflows' inputs, outputs and versions, what each one starts, the MCP tools they
 * hold and the resource types' schemas come along, so a run step's contract, `run.cycle`,
 * `mcp.toolName` and the fields a step returns are judged as the backend judges them.
 */
export function contextOf(registry: Registry | null): CheckContext {
  if (!registry || registry.failed.includes('providers')) return EMPTY_CONTEXT
  const workflowInputs: Record<string, JsonSchema> = {}
  const workflowOutputs: Record<string, string[]> = {}
  const workflowVersions: Record<string, string> = {}
  const resourceTypeSchemas: Record<string, JsonSchema> = {}
  for (const w of registry.workflows) {
    if (isObject(w.input) && Object.keys(w.input).length > 0) workflowInputs[w.name] = w.input as unknown as JsonSchema
    // the version that input belongs to: a run step pinned to another major is judged by the backend
    if (typeof w.version === 'string' && w.version.trim()) workflowVersions[w.name] = w.version
    // only an output schema that names its properties says what the workflow publishes
    const props = isObject(w.output) ? w.output.properties : undefined
    if (isObject(props)) workflowOutputs[w.name] = Object.keys(props)
  }
  for (const r of registry.resourceTypes) if (isObject(r.schema) && Object.keys(r.schema).length > 0) resourceTypeSchemas[r.name] = r.schema as unknown as JsonSchema
  return buildContext(
    registry.providers.map(toProviderDocument),
    registry.workflows.map((w) => w.name),
    registry.resourceTypes.map((r) => r.name),
    {
      workflowInputs,
      workflowOutputs,
      workflowVersions,
      resourceTypeSchemas,
      ...(registry.workflowRuns ? { workflowRuns: registry.workflowRuns } : {}),
      ...(registry.mcpTools ? { mcpTools: registry.mcpTools } : {}),
    },
  )
}

export function findProvider(registry: Registry | null, name: string | null | undefined): RegistryProvider | undefined {
  if (!registry || !name) return undefined
  return registry.providers.find((p) => p.name === name)
}

export function findAction(registry: Registry | null, provider: string | null | undefined, action: string | null | undefined): RegistryAction | undefined {
  if (!action) return undefined
  return findProvider(registry, provider)?.actions.find((a) => a.id === action)
}

/** The step type's schema and texts: from the registry, else the built-in copy of StepTypeSchemas. */
export function stepTypeInfo(registry: Registry | null, type: StepType): StepTypeInfo {
  const fromRegistry = registry?.stepTypes.find((t) => t.type === type)
  if (fromRegistry) return fromRegistry
  const info = STEP_TYPES[type]
  return { type, title: info.title, category: info.category, description: info.description, schema: FALLBACK_STEP_SCHEMAS[type] as Schema }
}

/** The approver groups the document and the published workflows mention. */
export function knownGroups(doc: { policy?: { approval?: { group?: string } } }, extra: string[] = []): string[] {
  const out = new Set<string>()
  const g = doc.policy?.approval?.group
  if (g) out.add(g)
  extra.forEach((x) => x && out.add(x))
  return [...out]
}
