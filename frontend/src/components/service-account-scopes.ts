/*
 * What a service account may call: the scopes of docs/api.md ("Identity"), grouped as its table
 * groups them, and the three presets. A preset names a set; scopes sent with a preset must fit
 * in it, so the form sends the preset alone when the selection is the whole set, the preset with
 * the scopes when it is a part of it, and the scopes alone otherwise.
 */
import type { CreateServiceAccount, ServiceAccountPreset, ServiceAccountScope } from '../lib/types'

export interface ScopeInfo {
  scope: ServiceAccountScope
  /** the docs' "Allows" column, shortened */
  allows: string
}

export interface ScopeGroup {
  group: string
  scopes: ScopeInfo[]
}

export const SCOPE_GROUPS: ScopeGroup[] = [
  {
    group: 'Registry',
    scopes: [{ scope: 'registry:read', allows: 'GET on the registry, catalog, OpenAPI, templates, workflows, providers, resource types, resources, change requests and releases; generated resource reads; MCP tools/list and the resource tools' }],
  },
  {
    group: 'Workflows',
    scopes: [
      { scope: 'workflows:validate', allows: '/workflows:validate, :parse, :render, :replay-check and /providers:validate' },
      { scope: 'workflows:dry-run', allows: '/workflows:dry-run' },
      { scope: 'workflows:write', allows: 'creating, importing, editing, deleting and publishing workflows; merging and closing change requests; assistant sessions and drafts; the MCP draft tool' },
    ],
  },
  {
    group: 'Jobs',
    scopes: [
      { scope: 'jobs:start', allows: 'starting jobs (/jobs, /jobs:batch, generated routes, the MCP invoke, batch and cancel tools), steering them (cancel, retry, skip, abort, rerun) and /triggers/*' },
      { scope: 'jobs:read', allows: 'jobs and their events, replay and evidence; approvals; audit trails; the job WebSocket; the MCP job status tool' },
    ],
  },
  {
    group: 'Providers',
    scopes: [{ scope: 'providers:write', allows: 'the Add provider wizard (import spec, the Ansible and Terraform imports, test connection, try, publish, draft), refresh spec, overrides, SLO and /credentials' }],
  },
  {
    group: 'Admin',
    scopes: [{ scope: 'admin', allows: 'everything, and makes the account an admin; an endpoint the table does not name needs it' }],
  },
]

export const ALL_SCOPES: ServiceAccountScope[] = SCOPE_GROUPS.flatMap((g) => g.scopes.map((s) => s.scope))

export const PRESETS: Record<ServiceAccountPreset, ServiceAccountScope[]> = {
  invoker: ['registry:read', 'jobs:start', 'jobs:read'],
  assistant: ['registry:read', 'workflows:validate', 'workflows:dry-run'],
  ci: ['registry:read', 'workflows:validate', 'workflows:dry-run', 'workflows:write', 'providers:write'],
}

export const PRESET_INFO: { preset: ServiceAccountPreset; label: string; text: string }[] = [
  { preset: 'invoker', label: 'Invoker', text: 'Starts and follows jobs; what an account created without scopes gets.' },
  { preset: 'assistant', label: 'Assistant', text: 'The builder agent’s token: reads the registry and checks drafts, changes nothing.' },
  { preset: 'ci', label: 'CI', text: 'A pipeline: checks, writes and publishes workflows and providers, starts nothing.' },
]

export type PresetChoice = ServiceAccountPreset | 'custom'

/** The scopes in the docs' order, without repeats. */
export function orderScopes(scopes: readonly ServiceAccountScope[]): ServiceAccountScope[] {
  return ALL_SCOPES.filter((s) => scopes.includes(s))
}

/** Whether every scope is in the preset's set. */
export function fitsPreset(scopes: readonly ServiceAccountScope[], preset: ServiceAccountPreset): boolean {
  return scopes.every((s) => PRESETS[preset].includes(s))
}

/** The preset whose set the scopes are, exactly, or null. */
export function presetOf(scopes: readonly ServiceAccountScope[]): ServiceAccountPreset | null {
  const sorted = orderScopes(scopes)
  for (const p of Object.keys(PRESETS) as ServiceAccountPreset[]) {
    const set = PRESETS[p]
    if (sorted.length === set.length && sorted.every((s) => set.includes(s))) return p
  }
  return null
}

/** The `preset` and `scopes` of the create request: the preset alone for its whole set, with the scopes for a part of it, the scopes alone otherwise. */
export function scopesRequest(choice: PresetChoice, scopes: readonly ServiceAccountScope[]): Pick<CreateServiceAccount, 'preset' | 'scopes'> {
  const chosen = orderScopes(scopes)
  if (choice !== 'custom' && fitsPreset(chosen, choice)) {
    return presetOf(chosen) === choice ? { preset: choice } : { preset: choice, scopes: chosen }
  }
  return { scopes: chosen }
}
