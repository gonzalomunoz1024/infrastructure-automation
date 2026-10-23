import { afterEach, describe, expect, it, vi } from 'vitest'
import type { CloneDraft } from '../../lib/tags'
import { keepDraft, noteWords, takeDraft } from './clone'

/* A clone's draft crosses to another account's wizard once: kept under a token, read and dropped there. */

const DRAFT: CloneDraft = {
  type: 'vm',
  workflow: 'create-vm',
  source: { wfrn: 'wfrn:wf:compute:global:123456789012:vm/vm-demo02', name: 'api-01', accountId: '123456789012', region: 'global' },
  accountId: '210987654321',
  input: { name: 'api-01-copy', size: 'small' },
  tags: { owner: 'team-data' },
  notes: [{ field: 'image', code: 'reference', message: 'image names a image of this account; check that the target account can use it' }],
  targets: [],
  tagPolicy: { accountId: '210987654321', required: [] },
}

function fakeStorage() {
  const store = new Map<string, string>()
  return { getItem: (k: string) => store.get(k) ?? null, setItem: (k: string, v: string) => void store.set(k, v), removeItem: (k: string) => void store.delete(k), store }
}

afterEach(() => vi.unstubAllGlobals())

describe('a clone’s draft on its way to another account', () => {
  it('is kept under a one-time token and leaves the storage as it is read, while the page still has it', () => {
    const storage = fakeStorage()
    vi.stubGlobal('sessionStorage', storage)
    const token = keepDraft(DRAFT)
    expect(storage.store.size).toBe(1)
    expect(takeDraft(token)).toEqual(DRAFT)
    expect(storage.store.size, 'read once, then dropped').toBe(0)
    expect(takeDraft(token), 'a page that mounts again still reads it').toEqual(DRAFT)
    expect(takeDraft('no-such-token')).toBeNull()
  })

  it('still crosses within the page when there is no storage', () => {
    vi.stubGlobal('sessionStorage', {
      setItem: () => {
        throw new Error('blocked')
      },
      getItem: () => null,
      removeItem: () => undefined,
    })
    expect(takeDraft(keepDraft(DRAFT))).toEqual(DRAFT)
  })

  it('says what to check with the field’s title', () => {
    expect(noteWords(DRAFT.notes[0], (f) => (f === 'image' ? 'Image' : f))).toBe('Image: image names a image of this account; check that the target account can use it')
  })
})
