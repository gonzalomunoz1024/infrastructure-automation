import { describe, expect, it } from 'vitest'
import { ApiError } from './api'
import { accountOfWfrn, movedOf, readTransferDraft, transferCodeOf, writeTransferDraft } from './transfer'

/* The move to another account (WP 7.7): reading where a resource went, the refusals by code, and the draft kept across a step-up. */

const OLD = 'wfrn:wf:compute:global:123456789012:vm/vm-gen-005'
const NEW = 'wfrn:wf:compute:global:210987654321:vm/vm-gen-005'

describe('a moved resource', () => {
  it('reads where it went from the 410, under detail or whole', () => {
    expect(movedOf(new ApiError(410, { error: 'resource.transferred', detail: { wfrn: OLD, transferredTo: NEW, toAccountName: 'Data' } }))).toEqual({ wfrn: OLD, transferredTo: NEW, accountId: '210987654321', accountName: 'Data', at: undefined })
    expect(movedOf(new ApiError(410, { error: 'resource.transferred', transferredTo: NEW }))?.accountId).toBe('210987654321')
  })

  it('is not a plain delete, and never follows something that is not a wfrn', () => {
    expect(movedOf(new ApiError(410, { error: 'resource.deleted', detail: { deletedAt: '2026-10-01T00:00:00Z' } }))).toBeNull()
    expect(movedOf(new ApiError(410, { error: 'resource.transferred', detail: { transferredTo: 'https://evil.example/' } }))).toBeNull()
    expect(movedOf(new ApiError(410, { error: 'resource.transferred', detail: { transferredTo: 'wfrn:wf:compute:global:not-an-account:vm/x' } }))).toBeNull()
    expect(movedOf(new ApiError(404, { error: 'resource.transferred', detail: { transferredTo: NEW } }))).toBeNull()
    expect(accountOfWfrn(NEW)).toBe('210987654321')
  })

  it('names the refusals the contract lists by their code', () => {
    expect(transferCodeOf(new ApiError(409, { error: 'resource.busy' }))).toBe('resource.busy')
    expect(transferCodeOf(new ApiError(409, { error: 'budget.exceeded: over by 12 USD' }))).toBe('budget.exceeded')
    expect(transferCodeOf(new ApiError(400, { error: 'transfer.sameAccount' }))).toBe('transfer.sameAccount')
    expect(transferCodeOf(new ApiError(400, { error: 'bad request', detail: 'the resource is already in the same account' }))).toBe('transfer.sameAccount')
    expect(transferCodeOf(new ApiError(500, { error: 'boom' }))).toBeNull()
  })

  it('keeps the form for the way back from a fresh sign-in, per wfrn', () => {
    const store: Record<string, string> = {}
    const storage = { getItem: (k: string) => store[k] ?? null, setItem: (k: string, v: string) => void (store[k] = v), removeItem: (k: string) => void delete store[k] }
    writeTransferDraft(OLD, { toAccountId: '210987654321', reason: 'Data runs it' }, storage)
    expect(readTransferDraft(OLD, storage)).toEqual({ toAccountId: '210987654321', reason: 'Data runs it' })
    expect(readTransferDraft(NEW, storage)).toBeNull()
    writeTransferDraft(OLD, null, storage)
    expect(readTransferDraft(OLD, storage)).toBeNull()
  })
})
