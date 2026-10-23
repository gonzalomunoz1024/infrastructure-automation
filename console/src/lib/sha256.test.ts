import { describe, expect, it } from 'vitest'
import { sha256Hex, sha256Tagged, toHex, utf8 } from './sha256'

/* The pure SHA-256 the trail is chained with: the standard's vectors, and Web Crypto's answers. */

describe('sha256', () => {
  it('answers the FIPS 180-4 vectors', () => {
    expect(sha256Hex('')).toBe('e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855')
    expect(sha256Hex('abc')).toBe('ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad')
    expect(sha256Hex('abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq')).toBe('248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1')
  })

  it('agrees with Web Crypto on text of every padding length, multibyte text included', async () => {
    for (const text of [...Array.from({ length: 130 }, (_, n) => 'x'.repeat(n)), 'Größe ändern: 4 → 8 CPUs', '{"a":1}\n']) {
      const want = toHex(new Uint8Array(await crypto.subtle.digest('SHA-256', utf8(text))))
      expect(sha256Hex(text), JSON.stringify(text)).toBe(want)
    }
  })

  it('tags a digest the way the trail writes it', () => {
    expect(sha256Tagged('abc')).toMatch(/^sha256:[0-9a-f]{64}$/)
  })
})
