/**
 * The registry the designer browses: step types, providers and their actions, published
 * workflows, resource types, playbooks and credential names. Loaded once per session; every
 * part fails independently so the canvas works with whatever answered.
 */
import { buildContext, EMPTY_CONTEXT, STEP_TYPES, type CheckContext, type JsonObject, type ProviderDocument, type StepType } from '@iaf/workflow-checks'
import { api } from '../../lib/api'
import type { RegistryAction, RegistryCredential, RegistryPlaybook, RegistryProvider, RegistryWorkflow, ResourceType, Schema, StepTypeInfo } from '../../lib/types'
import { FALLBACK_STEP_SCHEMAS } from './stepSchemas'

export interface Registry {
  stepTypes: StepTypeInfo[]
  providers: RegistryProvider[]
  workflows: RegistryWorkflow[]
  resourceTypes: ResourceType[]
  playbooks: RegistryPlaybook[]
  credentials: RegistryCredential[]
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

/** Loads every registry endpoint once; a later call returns the same promise. */
export function loadRegistry(force = false): Promise<Registry> {
  if (cached && !force) return cached
  cached = (async () => {
    const failed: string[] = []
    const [stepTypes, providers, workflows, resourceTypes, playbooks, credentials] = await Promise.all([
      part('step types', () => api.registryStepTypes(), failed, [] as StepTypeInfo[]),
      part('providers', () => api.registryProviders(), failed, [] as RegistryProvider[]),
      part('workflows', () => api.registryWorkflows(true), failed, [] as RegistryWorkflow[]),
      part('resource types', () => api.registryResourceTypes(), failed, [] as ResourceType[]),
      part('playbooks', () => api.registryPlaybooks(), failed, [] as RegistryPlaybook[]),
      part('credentials', () => api.registryCredentials(), failed, [] as RegistryCredential[]),
    ])
    return { stepTypes, providers, workflows, resourceTypes, playbooks, credentials, failed }
  })()
  return cached
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

/** The check context: the catalog when the providers answered, the empty one otherwise. */
export function contextOf(registry: Registry | null): CheckContext {
  if (!registry || registry.failed.includes('providers')) return EMPTY_CONTEXT
  return buildContext(
    registry.providers.map(toProviderDocument),
    registry.workflows.map((w) => w.name),
    registry.resourceTypes.map((r) => r.name),
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
