import { describe, expect, it } from 'vitest'
import { renderToString } from 'react-dom/server'
import type { Credential, Me } from '../lib/types'
import { OTHER_TEAM, OwnerField, credentialRef, ownerOptions, typesOwner } from '../components/CredentialPicker'
import { CredentialsTable, OwnerTag, filterCredentials } from '../pages/CredentialsPage'

/*
 * The credentials page and the form that stores one, server-rendered like the other
 * component tests: a credential shows the team that may use it, and a new one is stored
 * for one of the caller's teams (any team, for an admin). No value is ever on screen.
 */

const text = (html: string) => html.replace(/<!--.*?-->/g, '')

const ROWS: Credential[] = [
  { name: 'vsphere/token', type: 'token', path: 'vault:vsphere/token', updatedAt: '2026-10-17T10:00:00Z' },
  { name: 'db/password', type: 'password', path: 'vault:db/password', owner: 'team-data', updatedAt: '2026-10-17T11:00:00Z' },
]
const ANA: Me = { id: 'ana', name: 'Ana', teams: ['team-data', 'data-approvers'], roles: ['ops'], admin: false }
const ROOT: Me = { id: 'gonzalo', name: 'Gonzalo', teams: ['team-compute', 'team-platform'], roles: ['admin'], admin: true }
const GUEST: Me = { id: 'guest', name: 'Guest', teams: [], roles: [], admin: false }

describe('Credentials page', () => {
  it('shows the team that may use each credential, and that one without an owner is any team\'s', () => {
    const html = text(renderToString(<CredentialsTable rows={ROWS} />))
    expect(html).toContain('Owner')
    expect(html).toContain('team-data')
    expect(html).toContain('any team')
    expect(html).toContain('vault:db/password')
    expect(html).toContain('vault:vsphere/token')
    expect(html).toContain('never shown')
    expect(text(renderToString(<OwnerTag owner="team-compute" />))).toContain('team-compute')
    expect(text(renderToString(<OwnerTag />))).toContain('any team')
  })

  it('finds a credential by its team as well as by name, type or reference', () => {
    expect(filterCredentials(ROWS, 'team-data').map((c) => c.name)).toEqual(['db/password'])
    expect(filterCredentials(ROWS, 'VSPHERE').map((c) => c.name)).toEqual(['vsphere/token'])
    expect(filterCredentials(ROWS, 'vault:').map((c) => c.name)).toEqual(['vsphere/token', 'db/password'])
    expect(filterCredentials(ROWS, '')).toHaveLength(2)
    expect(filterCredentials(ROWS, 'team-compute')).toHaveLength(0)
  })

  it('refers to a credential as a provider file does', () => {
    expect(credentialRef(ROWS[1])).toBe('vault:db/password')
    expect(credentialRef({ name: 'new/token', path: '' })).toBe('vault:new/token')
  })
})

describe('Owner of a new credential', () => {
  it("is one of the caller's teams", () => {
    expect(ownerOptions(ANA).map((o) => o.value)).toEqual(['team-data', 'data-approvers'])
    expect(ownerOptions(undefined)).toEqual([])
    expect(typesOwner(ownerOptions(ANA), 'team-data')).toBe(false)
    const html = text(renderToString(<OwnerField me={ANA} value="team-data" other="" onChange={() => {}} onOtherChange={() => {}} />))
    expect(html).toContain('Owner team')
    expect(html).toContain('aria-haspopup="listbox"')
    expect(html).toContain('team-data')
    expect(html).not.toContain('Another team')
    expect(html).not.toContain('id="cred-owner"')
  })

  it('may be any team for an admin, who types one they are not in', () => {
    expect(ownerOptions(ROOT).map((o) => o.value)).toEqual(['team-compute', 'team-platform', OTHER_TEAM])
    expect(typesOwner(ownerOptions(ROOT), OTHER_TEAM)).toBe(true)
    const html = text(renderToString(<OwnerField me={ROOT} value={OTHER_TEAM} other="team-data" onChange={() => {}} onOtherChange={() => {}} />))
    expect(html).toContain('Another team')
    expect(html).toContain('id="cred-owner"')
    expect(html).toContain('value="team-data"')
    expect(html).toContain('as an admin you may name any team')
    // a team name is a slug
    const bad = text(renderToString(<OwnerField me={ROOT} value={OTHER_TEAM} other="Team Data" onChange={() => {}} onOtherChange={() => {}} />))
    expect(bad).toContain('aria-invalid="true"')
    expect(bad).toContain('Lowercase letters, digits and dashes')
  })

  it('cannot be picked by someone in no team, who is told why', () => {
    expect(typesOwner(ownerOptions(GUEST), '')).toBe(true)
    const html = text(renderToString(<OwnerField me={GUEST} value="" other="" onChange={() => {}} onOtherChange={() => {}} />))
    expect(html).toContain('Only members of a team can store credentials')
    expect(html).not.toContain('aria-haspopup="listbox"')
  })

  it('waits for /me before offering a choice', () => {
    expect(typesOwner([], '', true)).toBe(false)
    const html = text(renderToString(<OwnerField me={undefined} loading value="" other="" onChange={() => {}} onOtherChange={() => {}} />))
    expect(html).toContain('Loading your teams')
    expect(html).toContain('disabled')
  })
})
