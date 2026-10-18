import { describe, expect, it } from 'vitest'
import { ApiError } from '../lib/api'
import type { Me } from '../lib/types'
import { adminsOnly } from '../pages/SettingsPage'

const me = (admin: boolean): Me => ({ id: 'ana', name: 'Ana', teams: ['team-compute'], roles: admin ? ['ops', 'admin'] : ['ops'], admin })

describe('who sees the service accounts', () => {
  it('is a platform admin, as /me says, or as a 403 says before /me answers', () => {
    expect(adminsOnly(me(false), null)).toBe(true)
    expect(adminsOnly(me(true), null)).toBe(false)
    expect(adminsOnly(undefined, new ApiError(403, { error: 'Only a platform admin lists service accounts' }))).toBe(true)
  })

  it('leaves any other failure an error', () => {
    expect(adminsOnly(undefined, null)).toBe(false)
    expect(adminsOnly(undefined, new ApiError(0, null))).toBe(false)
    expect(adminsOnly(me(true), new ApiError(500, { error: 'Internal error' }))).toBe(false)
  })
})
