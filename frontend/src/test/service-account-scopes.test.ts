import { describe, expect, it } from 'vitest'
import { ALL_SCOPES, PRESETS, SCOPE_GROUPS, fitsPreset, orderScopes, presetOf, scopesRequest } from '../components/service-account-scopes'

describe('service-account scopes', () => {
  it('lists every scope of the docs once, grouped as the table is', () => {
    expect(ALL_SCOPES).toEqual(['registry:read', 'workflows:validate', 'workflows:dry-run', 'workflows:write', 'jobs:start', 'jobs:read', 'providers:write', 'admin'])
    expect(SCOPE_GROUPS.map((g) => g.group)).toEqual(['Registry', 'Workflows', 'Jobs', 'Providers', 'Admin'])
    for (const p of Object.values(PRESETS)) for (const s of p) expect(ALL_SCOPES).toContain(s)
  })

  it('names the preset a selection is, and whether it fits one', () => {
    expect(presetOf(['jobs:read', 'registry:read', 'jobs:start'])).toBe('invoker')
    expect(presetOf(PRESETS.ci)).toBe('ci')
    expect(presetOf(['registry:read'])).toBeNull()
    expect(presetOf([])).toBeNull()
    expect(fitsPreset(['registry:read', 'jobs:read'], 'invoker')).toBe(true)
    expect(fitsPreset(['registry:read', 'workflows:write'], 'invoker')).toBe(false)
    expect(orderScopes(['admin', 'registry:read', 'registry:read'])).toEqual(['registry:read', 'admin'])
  })

  it('sends the preset alone for its whole set, with the scopes for a part of it, and the scopes alone otherwise', () => {
    expect(scopesRequest('ci', PRESETS.ci)).toEqual({ preset: 'ci' })
    expect(scopesRequest('ci', ['workflows:write', 'registry:read'])).toEqual({ preset: 'ci', scopes: ['registry:read', 'workflows:write'] })
    // a scope outside the preset: the preset no longer applies
    expect(scopesRequest('assistant', ['registry:read', 'jobs:start'])).toEqual({ scopes: ['registry:read', 'jobs:start'] })
    expect(scopesRequest('custom', ['admin'])).toEqual({ scopes: ['admin'] })
    expect(scopesRequest('custom', [])).toEqual({ scopes: [] })
  })
})
