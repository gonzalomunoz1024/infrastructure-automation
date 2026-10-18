import { describe, expect, it } from 'vitest'
import { changeRequestOutcome } from '../components/ChangeRequestToast'

/* What a provider command answers: the change request it opened, or that there was nothing to change. */

describe('changeRequestOutcome', () => {
  it('names the change request a command opened', () => {
    expect(changeRequestOutcome({ id: 'cr-90', status: 'open' })).toEqual({ kind: 'opened', id: 'cr-90' })
    expect(changeRequestOutcome({ id: 'cr-91', status: 'accepted' })).toEqual({ kind: 'opened', id: 'cr-91' })
  })
  it('says when the same document changes nothing', () => {
    expect(changeRequestOutcome({ id: 'vsphere', status: 'unchanged' })).toEqual({ kind: 'unchanged' })
  })
})
