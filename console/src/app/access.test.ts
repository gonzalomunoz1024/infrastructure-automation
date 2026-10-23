import { describe, expect, it, vi } from 'vitest'
import { ApiError } from '../lib/api'
import type { AccessRequest } from '../lib/types'
import { PROBE_REQUESTER, mayDecide, readsQueue } from './access'

const request = (requester: string, approvers: string[], status: AccessRequest['status'] = 'pending'): AccessRequest => ({ requestId: `areq_${requester}`, accountId: '1', requester: { userId: requester }, action: 'compute:create', role: 'operator', status, requestedAt: '2026-10-23T09:00:00Z', approvers })

describe('the access request queue', () => {
  it('is readable to the managed roles that read it, without asking', async () => {
    const list = vi.fn(() => Promise.resolve({ items: [] }))
    await expect(readsQueue(['account-admin'], list)).resolves.toBe(true)
    await expect(readsQueue(['auditor', 'viewer'], list)).resolves.toBe(true)
    expect(list).not.toHaveBeenCalled()
  })

  it('asks for someone else’s requests otherwise: a 200 reads the queue, a 403 sees only their own', async () => {
    const reader = vi.fn(() => Promise.resolve({ items: [] }))
    await expect(readsQueue(['viewer', 'approver'], reader)).resolves.toBe(true)
    expect(reader).toHaveBeenCalledWith({ requester: PROBE_REQUESTER, limit: 1 })
    const refused = () => Promise.reject(new ApiError(403, { error: 'no policy allows access-request:list' }))
    await expect(readsQueue(['operator'], refused)).resolves.toBe(false)
    // anything else is an error to show, not an answer
    await expect(readsQueue(['operator'], () => Promise.reject(new ApiError(503, { error: 'identity unavailable' })))).rejects.toThrow('identity unavailable')
  })

  it('lets a person decide a pending request naming them, never their own', () => {
    expect(mayDecide(request('mia', ['dana']), 'dana')).toBe(true)
    expect(mayDecide(request('mia', ['dana', 'mia']), 'mia')).toBe(false)
    expect(mayDecide(request('mia', ['dana']), 'gonzalo')).toBe(false)
    expect(mayDecide(request('mia', ['dana'], 'approved'), 'dana')).toBe(false)
  })
})
