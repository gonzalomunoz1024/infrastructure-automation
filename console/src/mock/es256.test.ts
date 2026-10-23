import { describe, expect, it } from 'vitest'
import { utf8 } from '../lib/sha256'
import { demoKey, signEs256 } from './es256'

/* The mock's ES256 digests must verify with Web Crypto against the public key it serves, as an auditor checks them. */

function fromBase64url(s: string): Uint8Array<ArrayBuffer> {
  const bin = atob(s.replace(/-/g, '+').replace(/_/g, '/'))
  return Uint8Array.from(bin, (c) => c.charCodeAt(0))
}

describe('es256', () => {
  const key = demoKey('trail-test', 'unit')

  it('signs what Web Crypto verifies, and nothing else', async () => {
    const pub = await crypto.subtle.importKey('jwk', key.jwk, { name: 'ECDSA', namedCurve: 'P-256' }, false, ['verify'])
    const text = 'sha256:ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad'
    const sig = fromBase64url(signEs256(key, text))
    expect(sig.length).toBe(64)
    expect(await crypto.subtle.verify({ name: 'ECDSA', hash: 'SHA-256' }, pub, sig, utf8(text))).toBe(true)
    expect(await crypto.subtle.verify({ name: 'ECDSA', hash: 'SHA-256' }, pub, sig, utf8(`${text}0`))).toBe(false)
  })

  it('signs the same text the same way, so a digest is stable', () => {
    expect(signEs256(key, 'a')).toBe(signEs256(key, 'a'))
    expect(signEs256(key, 'a')).not.toBe(signEs256(key, 'b'))
  })
})
