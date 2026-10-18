import { describe, expect, it } from 'vitest'
import { readableDetail } from '../pages/ChangeRequestPage'

describe('change request check details', () => {
  it('lists every consumer a breaking change reaches', () => {
    const d = readableDetail('2.0.0 breaks the contract of 1.2.0 without a new major version: input.reason became required. Affected consumers: web-environment, create-vm')
    expect(d.text).toBe('2.0.0 breaks the contract of 1.2.0 without a new major version: input.reason became required.')
    expect(d.lists).toEqual([{ label: 'A breaking change reaches', items: ['web-environment', 'create-vm'], tone: 'critical' }])
  })

  it('separates the consumers that stay on the old major', () => {
    const d = readableDetail('2.0.0 changes the contract of 1.2.0. Affected consumers: web-environment. Staying on the old major: create-vm, patch-cve')
    expect(d.lists.map((l) => l.label)).toEqual(['A breaking change reaches', 'Staying on the old major'])
    expect(d.lists[1].items).toEqual(['create-vm', 'patch-cve'])
    expect(d.text).toBe('2.0.0 changes the contract of 1.2.0.')
  })

  it('reads the pin outcome of the playbooks check', () => {
    const pinned = readableDetail('2 playbook(s) pinned to a commit, 1 linted; pinned at publish: restart to 9fceb02d0ae5, rollback to 1a2b3c4d5e6f')
    expect(pinned.text).toBe('2 playbook(s) pinned to a commit, 1 linted')
    expect(pinned.lists).toEqual([{ label: 'Pinned at publish', items: ['restart to 9fceb02d0ae5', 'rollback to 1a2b3c4d5e6f'], tone: 'healthy' }])
    const unpinned = readableDetail('not pinned to a commit: restart, rollback')
    expect(unpinned.text).toBeNull()
    expect(unpinned.lists).toEqual([{ label: 'Not pinned to a commit', items: ['restart', 'rollback'], tone: 'critical' }])
    const failed = readableDetail('could not pin to a commit: github.com/acme/ops has no ref gone')
    expect(failed.text).toBe('could not pin to a commit: github.com/acme/ops has no ref gone')
    expect(failed.lists).toEqual([])
  })

  it('lists each failure of the playbooks check: unpinned steps, digests that are not one, what the linter found', () => {
    const d = readableDetail(
      'not pinned to a commit: restart, rollback; upload digest is not 64 hex digits (playbook.digest): patch; the linter found problems: restart: site.yml:3 [name] All tasks should be named | 1 failure; rollback: no playbook at undo.yml',
    )
    expect(d.text).toBeNull()
    expect(d.lists).toEqual([
      { label: 'The linter found', items: ['restart: site.yml:3 [name] All tasks should be named | 1 failure', 'rollback: no playbook at undo.yml'], tone: 'critical' },
      { label: 'Not pinned to a commit', items: ['restart', 'rollback'], tone: 'critical' },
      { label: 'Upload digest is not 64 hex digits', items: ['patch'], tone: 'critical' },
    ])
    expect(readableDetail('no ansible step')).toEqual({ text: 'no ansible step', lists: [] })
  })

  it('turns a JSON blob into readable lines', () => {
    const d = readableDetail('{"replayed":42,"divergences":[]}')
    expect(d.text).toBeNull()
    expect(d.lists[0].items).toEqual(['replayed: 42', 'divergences: []'])
    expect(readableDetail(undefined)).toEqual({ text: null, lists: [] })
    expect(readableDetail('7 steps')).toEqual({ text: '7 steps', lists: [] })
  })
})
