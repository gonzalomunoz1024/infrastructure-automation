/*
 * Calls the pages need that lib/api.ts does not carry (yet). They live apart so api.ts and
 * types.ts merge cleanly with the designer's changes; fold them into `api` once both land.
 */
import { qs, request } from './api'
import type { JobReplay, Problem, ProviderAuth, ProviderDetail } from './types'

/** A workflow document as JSON: the backend's reading of the YAML. Pages touch only `metadata`. */
export type WorkflowJson = Record<string, unknown> & { metadata?: Record<string, unknown> }
export type ParsedWorkflow = { definition: WorkflowJson | null; problems: Problem[] }

export const apiExtra = {
  /** GET /jobs/{id}/replay?compareTo= : the recorded run, step by step, with the fields that differ from another run. */
  jobReplay: (id: string, compareTo?: string) => request<JobReplay>('GET', `/jobs/${encodeURIComponent(id)}/replay${qs({ compareTo })}`),
  /** POST /workflows:parse : the backend reads YAML so the browser never has to. `definition` is null when it does not parse. */
  parseWorkflow: (yaml: string) => request<ParsedWorkflow>('POST', '/workflows:parse', { yaml }),
  /** POST /workflows:render : the document back as YAML. */
  renderWorkflow: (definition: WorkflowJson) => request<{ yaml: string }>('POST', '/workflows:render', { definition }),
}

/**
 * Where a provider lives and how it signs in. `GET /providers/{name}` does not send these today
 * (they are only inside its YAML, which the browser does not read); the provider page shows them
 * as soon as the backend adds them to ProviderDetail.
 */
export type ProviderConnection = { endpoints?: Record<string, string>; auth?: ProviderAuth }
export type ProviderDetailWithConnection = ProviderDetail & ProviderConnection

/** A template's YAML under a new name and title, through the backend's parse and render. */
export async function renamedWorkflowYaml(yaml: string, name: string, title?: string): Promise<string> {
  const parsed = await apiExtra.parseWorkflow(yaml)
  if (!parsed.definition) throw new Error(parsed.problems[0]?.message ?? 'The template does not parse')
  const metadata = { ...(parsed.definition.metadata ?? {}), name, ...(title ? { title } : {}) }
  const { yaml: out } = await apiExtra.renderWorkflow({ ...parsed.definition, metadata })
  return out
}
