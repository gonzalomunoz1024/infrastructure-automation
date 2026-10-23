import { afterEach, describe, expect, it } from 'vitest'
import { MANAGED_ROLES, getSession, getSessionState, hasRole, isAdmin, meOf, primaryRole, resetSessionState, returnToOf, roleTone, rolesOf, secondsLeft, setSessionState, signInUrl, type Session } from './session'

const session: Session = {
  user: { userId: 'mia', email: 'mia@example.com', name: 'Mia Lund', groups: ['team-platform'] },
  account: { accountId: '123456789012', name: 'compute-dev', roles: ['viewer'], groups: ['team-platform', 'compute-approvers'] },
  region: 'global',
  csrfToken: 'mock-csrf-mia',
  expiresAt: '2026-10-23T16:00:00Z',
  authTime: '2026-10-23T08:00:00Z',
}

afterEach(() => resetSessionState())

describe('the session store', () => {
  it('starts loading, keeps what it is given and resets', () => {
    expect(getSessionState()).toEqual({ status: 'loading', session: null })
    expect(getSession()).toBeNull()
    setSessionState({ status: 'signed-in', session })
    expect(getSession()).toBe(session)
    setSessionState({ status: 'signed-out', session: null })
    expect(getSession()).toBeNull()
    resetSessionState()
    expect(getSessionState().status).toBe('loading')
  })
})

describe('roles', () => {
  it('lists the managed roles and ranks them', () => {
    expect([...MANAGED_ROLES]).toEqual(['viewer', 'operator', 'admin', 'auditor'])
    expect(primaryRole(['viewer', 'operator'])).toBe('operator')
    expect(primaryRole(['auditor', 'admin', 'viewer'])).toBe('admin')
    expect(primaryRole(['viewer', 'auditor'])).toBe('auditor')
    expect(primaryRole(['custom-a', 'custom-b'])).toBe('custom-a')
    expect(primaryRole([])).toBeNull()
    expect(primaryRole(undefined)).toBeNull()
  })

  it('reads them from the session and colours them', () => {
    expect(rolesOf(session)).toEqual(['viewer'])
    expect(rolesOf(null)).toEqual([])
    expect(hasRole(session, 'viewer')).toBe(true)
    expect(isAdmin(session)).toBe(false)
    expect(isAdmin({ ...session, account: { ...session.account, roles: ['admin'] } })).toBe(true)
    expect(roleTone('admin')).toBe('warning')
    expect(roleTone('operator')).toBe('accent')
    expect(roleTone('auditor')).toBe('healthy')
    expect(roleTone('viewer')).toBe('neutral')
    expect(roleTone(null)).toBe('neutral')
  })

  it('shapes the person the phase-0 pages read', () => {
    expect(meOf(session)).toEqual({ id: 'mia', name: 'Mia Lund', email: 'mia@example.com', roles: ['viewer'], teams: ['team-platform', 'compute-approvers'], admin: false })
    expect(meOf({ ...session, user: { userId: 'x', email: 'x@example.com' }, account: { accountId: '1', name: 'a', roles: ['admin'] } })).toMatchObject({ name: 'x@example.com', admin: true, teams: undefined })
  })
})

describe('signing in', () => {
  it('builds the return URL and the BFF sign-in URL', () => {
    expect(returnToOf('/requests')).toBe('/#/requests')
    expect(returnToOf('requests')).toBe('/#/requests')
    expect(returnToOf('#/iam/users')).toBe('/#/iam/users')
    expect(signInUrl()).toBe('/auth/login?returnTo=%2F%23%2F')
    expect(signInUrl('/iam', 'mia')).toBe('/auth/login?returnTo=%2F%23%2Fiam&login_hint=mia')
  })

  it('counts the seconds left', () => {
    expect(secondsLeft(session, Date.parse('2026-10-23T15:59:00Z'))).toBe(60)
    expect(secondsLeft(session, Date.parse('2026-10-23T17:00:00Z'))).toBe(0)
    expect(secondsLeft({ ...session, expiresAt: 'soon' })).toBeNull()
    expect(secondsLeft(null)).toBeNull()
  })
})
