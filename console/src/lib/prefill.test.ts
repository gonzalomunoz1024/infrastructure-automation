import { describe, expect, it } from 'vitest'
import type { Schema } from '@iaf/console-kit'
import { decodePrefill, encodePrefill, prefillFor } from './prefill'

const schema: Schema = {
  type: 'object',
  properties: {
    name: { type: 'string' },
    size: { type: 'string', enum: ['small', 'medium'] },
    secret: { type: 'string', 'x-console': { sensitive: true } },
  },
}

describe('a form filled from its link', () => {
  it('round-trips any text through base64url, which a hash route carries as is', () => {
    const input = { name: 'web-9', note: 'é + / = ?' }
    const raw = encodePrefill(input)
    expect(raw).toMatch(/^[A-Za-z0-9_-]+$/)
    expect(decodePrefill(raw)).toEqual(input)
  })

  it('takes only the schema’s own fields, never a sensitive one', () => {
    const raw = encodePrefill({ name: 'web-9', size: 'medium', secret: 'hunter2', owner: 'someone-else' })
    expect(prefillFor(schema, raw)).toEqual({ name: 'web-9', size: 'medium' })
  })

  it('ignores a link that does not read as an object', () => {
    expect(prefillFor(schema, undefined)).toEqual({})
    expect(prefillFor(schema, 'not base64 json')).toEqual({})
    expect(prefillFor(schema, encodePrefill(['a'] as unknown as Record<string, unknown>))).toEqual({})
    expect(decodePrefill(btoa('"text"'))).toBeUndefined()
  })
})
