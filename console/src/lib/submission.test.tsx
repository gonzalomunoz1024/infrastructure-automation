import { describe, expect, it } from 'vitest'
import { renderToString } from 'react-dom/server'
import { cleanInput, useSubmissionKey } from './submission'

/* One Idempotency-Key per submission (P-21): the same body sent again keeps its key, a changed one gets a new key. */

function keyMinter(): (body: unknown) => string {
  let mint: ((body: unknown) => string) | null = null
  function Probe() {
    mint = useSubmissionKey()
    return null
  }
  renderToString(<Probe />)
  if (!mint) throw new Error('the hook did not render')
  return mint
}

describe('the submission key', () => {
  it('keeps the key while the body stays the same, and mints a new one when it changes', () => {
    const keyFor = keyMinter()
    const first = keyFor({ input: { name: 'web-09' } })
    expect(first).toMatch(/\S{8,}/)
    expect(keyFor({ input: { name: 'web-09' } })).toBe(first)
    const second = keyFor({ input: { name: 'web-10' } })
    expect(second).not.toBe(first)
    // going back to the first body is a new submission, not a replay of the first
    expect(keyFor({ input: { name: 'web-09' } })).not.toBe(first)
  })

  it('gives every form its own keys', () => {
    expect(keyMinter()({ input: {} })).not.toBe(keyMinter()({ input: {} }))
  })
})

describe('cleaning a form’s value', () => {
  it('drops what a form leaves empty and keeps everything else', () => {
    expect(cleanInput({ name: 'web-09', note: '', owner: null, gone: undefined, disks: [], size: 0, backups: false, image: { create: { os: 'rhel-9' } }, zones: ['a'] })).toEqual({ name: 'web-09', size: 0, backups: false, image: { create: { os: 'rhel-9' } }, zones: ['a'] })
  })
})
