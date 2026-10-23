import { describe, expect, it } from 'vitest'
import type { AccessRequest } from '../lib/types'
import { mayDecide, readsQueue } from './access'

const request = (requester: string, approvers: string[], status: AccessRequest['status'] = 'pending'): AccessRequest => ({ requestId: `areq_${requester}`, accountId: '1', requester: { userId: requester }, action: 'compute:create', role: 'operator', status, requestedAt: '2026-10-23T09:00:00Z', approvers })

describe('the access request queue', () => {
  it('is readable to the roles that read it, or when the answer shows more than the person’s own', () => {
    expect(readsQueue(['account-admin'], 'gonzalo', { items: [], refused: false })).toBe(true)
    expect(readsQueue(['auditor'], 'ann', { items: [], refused: false })).toBe(true)
    // the identity service answers anyone else with their own requests only
    expect(readsQueue(['operator'], 'mateo', { items: [], refused: false })).toBe(false)
    expect(readsQueue(['operator'], 'mateo', { items: [request('mateo', ['gonzalo'])], refused: false })).toBe(false)
    // someone else's request, or one naming the person among its approvers
    expect(readsQueue(['viewer', 'approver'], 'mia', { items: [request('dana', ['gonzalo', 'mia'])], refused: false })).toBe(true)
    expect(readsQueue(['viewer', 'approver'], 'mia', { items: [request('mia', ['dana', 'gonzalo', 'mia'])], refused: false })).toBe(true)
    expect(readsQueue(['account-admin'], 'gonzalo', { items: [], refused: true })).toBe(false)
  })

  it('lets a person decide a pending request naming them, never their own', () => {
    expect(mayDecide(request('mia', ['dana', 'mia']), 'dana')).toBe(true)
    expect(mayDecide(request('mia', ['dana', 'mia']), 'mia')).toBe(false)
    expect(mayDecide(request('mia', ['dana']), 'gonzalo')).toBe(false)
    expect(mayDecide(request('mia', ['dana'], 'approved'), 'dana')).toBe(false)
  })
})
