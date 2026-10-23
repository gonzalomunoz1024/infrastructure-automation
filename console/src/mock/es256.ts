import { sha256Bytes, toHex, utf8 } from '../lib/sha256'

/*
 * ES256 (ECDSA on P-256 with SHA-256) for the mock's trail digests, written small and synchronous
 * so the mock's request handler can seal a period without waiting on Web Crypto. It signs with
 * fixed demo keys (demo-only values, never secrets) and a nonce drawn from the key and the message,
 * so the same digest always carries the same signature. The browser checks the signatures with
 * Web Crypto against the public keys `/activity/keys` serves, exactly as an auditor would with
 * the evidence pack; the unit tests do the same. Not for anything but the mock.
 */

const P = 0xffffffff00000001000000000000000000000000ffffffffffffffffffffffffn
const N = 0xffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551n
const A = P - 3n
const GX = 0x6b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c296n
const GY = 0x4fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5n

const mod = (a: bigint, m: bigint) => {
  const r = a % m
  return r < 0n ? r + m : r
}

function inverse(a: bigint, m: bigint): bigint {
  // extended Euclid
  let [r0, r1] = [mod(a, m), m]
  let [s0, s1] = [1n, 0n]
  while (r1 !== 0n) {
    const q = r0 / r1
    ;[r0, r1] = [r1, r0 - q * r1]
    ;[s0, s1] = [s1, s0 - q * s1]
  }
  return mod(s0, m)
}

/** A point in Jacobian coordinates (X/Z², Y/Z³); Z = 0 is the point at infinity. */
type Jacobian = [bigint, bigint, bigint]

function double([x, y, z]: Jacobian): Jacobian {
  if (y === 0n || z === 0n) return [0n, 1n, 0n]
  const y2 = mod(y * y, P)
  const s = mod(4n * x * y2, P)
  const z2 = mod(z * z, P)
  const m = mod(3n * x * x + A * z2 * z2, P)
  const nx = mod(m * m - 2n * s, P)
  const ny = mod(m * (s - nx) - 8n * y2 * y2, P)
  const nz = mod(2n * y * z, P)
  return [nx, ny, nz]
}

function add(p: Jacobian, q: Jacobian): Jacobian {
  if (p[2] === 0n) return q
  if (q[2] === 0n) return p
  const z1z1 = mod(p[2] * p[2], P)
  const z2z2 = mod(q[2] * q[2], P)
  const u1 = mod(p[0] * z2z2, P)
  const u2 = mod(q[0] * z1z1, P)
  const s1 = mod(p[1] * q[2] * z2z2, P)
  const s2 = mod(q[1] * p[2] * z1z1, P)
  if (u1 === u2) return s1 === s2 ? double(p) : [0n, 1n, 0n]
  const h = mod(u2 - u1, P)
  const r = mod(s2 - s1, P)
  const h2 = mod(h * h, P)
  const h3 = mod(h2 * h, P)
  const u1h2 = mod(u1 * h2, P)
  const nx = mod(r * r - h3 - 2n * u1h2, P)
  const ny = mod(r * (u1h2 - nx) - s1 * h3, P)
  const nz = mod(h * p[2] * q[2], P)
  return [nx, ny, nz]
}

function multiply(k: bigint, point: Jacobian): Jacobian {
  let result: Jacobian = [0n, 1n, 0n]
  let addend = point
  let n = k
  while (n > 0n) {
    if (n & 1n) result = add(result, addend)
    addend = double(addend)
    n >>= 1n
  }
  return result
}

function affine([x, y, z]: Jacobian): [bigint, bigint] {
  const zi = inverse(z, P)
  const zi2 = mod(zi * zi, P)
  return [mod(x * zi2, P), mod(y * zi2 * zi, P)]
}

const G: Jacobian = [GX, GY, 1n]

function bigOf(bytes: Uint8Array): bigint {
  return bytes.length ? BigInt(`0x${toHex(bytes)}`) : 0n
}

function bytes32(n: bigint): Uint8Array {
  const hex = n.toString(16).padStart(64, '0')
  const out = new Uint8Array(32)
  for (let i = 0; i < 32; i++) out[i] = parseInt(hex.slice(i * 2, i * 2 + 2), 16)
  return out
}

export function base64url(bytes: Uint8Array): string {
  let bin = ''
  for (const b of bytes) bin += String.fromCharCode(b)
  return btoa(bin).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
}

export interface SigningKey {
  kid: string
  d: bigint
  /** the public half as a JWK */
  jwk: { kty: 'EC'; crv: 'P-256'; kid: string; alg: 'ES256'; use: 'sig'; x: string; y: string }
}

/** A key from a demo seed: the private scalar is the seed's SHA-256, reduced into the group. */
export function demoKey(kid: string, seed: string): SigningKey {
  const d = mod(bigOf(sha256Bytes(utf8(`es256-demo-key:${seed}`))), N - 1n) + 1n
  const [x, y] = affine(multiply(d, G))
  return { kid, d, jwk: { kty: 'EC', crv: 'P-256', kid, alg: 'ES256', use: 'sig', x: base64url(bytes32(x)), y: base64url(bytes32(y)) } }
}

/** The ES256 signature of the text's UTF-8 bytes: r and s, 32 bytes each, base64url (JWS's form). */
export function signEs256(key: SigningKey, text: string): string {
  const e = bigOf(sha256Bytes(utf8(text)))
  for (let attempt = 0; ; attempt++) {
    // a nonce drawn from the key, the message and the attempt: the same text signs the same way
    const k = mod(bigOf(sha256Bytes(utf8(`${key.d.toString(16)}:${text}:${attempt}`))), N - 1n) + 1n
    const [x] = affine(multiply(k, G))
    const r = mod(x, N)
    if (r === 0n) continue
    const s = mod(inverse(k, N) * (e + r * key.d), N)
    if (s === 0n) continue
    const sig = new Uint8Array(64)
    sig.set(bytes32(r), 0)
    sig.set(bytes32(s), 32)
    return base64url(sig)
  }
}
