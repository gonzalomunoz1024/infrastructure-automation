import { describe, expect, it } from 'vitest'
import { cx } from '@iaf/ui'

describe('cx', () => {
  it('joins truthy parts with a space', () => {
    expect(cx('a', 'b')).toBe('a b')
  })

  it('drops false, null, undefined, 0 and empty strings', () => {
    expect(cx('a', false, null, undefined, 0, '', 'b')).toBe('a b')
  })

  it('returns an empty string for nothing', () => {
    expect(cx()).toBe('')
    expect(cx(false, undefined)).toBe('')
  })
})
