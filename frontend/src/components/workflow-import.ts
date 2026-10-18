/*
 * What the New workflow dialog's Import offers for each source, and what it sends: the `ref` of
 * POST /workflows:import is what the backend resolves (docs/api.md, Workflows), so each option's
 * value is exactly that: an IDP tile's id (an action of an idp provider), a library playbook as
 * `<provider>/<playbook>`, a terraform provider's name, an openapi provider's name.
 */
import type { SelectOption } from '@iaf/ui'
import type { ImportSource, RegistryPlaybook, RegistryProvider } from '../lib/types'

export const IMPORT_SOURCES: { value: ImportSource; label: string; field: string }[] = [
  { value: 'idp', label: 'IDP tile', field: 'Tile' },
  { value: 'ansible', label: 'Ansible playbook', field: 'Playbook' },
  { value: 'terraform', label: 'Terraform module', field: 'Provider' },
  { value: 'openapi', label: 'OpenAPI provider', field: 'Provider' },
]

/** The label of the reference field for a source: Tile, Playbook or Provider. */
export function importField(source: ImportSource): string {
  return IMPORT_SOURCES.find((s) => s.value === source)?.field ?? 'Reference'
}

/** The registry entries a source can start from, as options whose value is the import's `ref`. */
export function importChoices(source: ImportSource, playbooks: readonly RegistryPlaybook[], providers: readonly RegistryProvider[]): SelectOption[] {
  if (source === 'ansible') return playbooks.map((p) => ({ value: p.name, label: p.name, hint: [p.path, p.version].filter(Boolean).join(' · ') }))
  if (source === 'idp') {
    // the backend looks the tile up among the actions of every idp provider
    const seen = new Set<string>()
    return providers
      .filter((p) => p.type === 'idp')
      .flatMap((p) => p.actions.map((a) => ({ provider: p, action: a })))
      .filter(({ action }) => (seen.has(action.id) ? false : (seen.add(action.id), true)))
      .map(({ provider, action }) => ({ value: action.id, label: action.title || action.id, hint: `${provider.title} · ${action.id}` }))
  }
  return providers.filter((p) => p.type === source).map((p) => ({ value: p.name, label: p.title, hint: `${p.name} · ${p.actions.length} ${p.actions.length === 1 ? 'action' : 'actions'}` }))
}

/** The body of POST /workflows:import for a source and a chosen option. */
export function importRequest(source: ImportSource, ref: string): { source: ImportSource; ref: string } {
  return { source, ref: ref.trim() }
}
