import { createPrivateKey, randomUUID, sign } from 'node:crypto'
import { harnessKey, URLS } from './stack.js'

/*
 * Service tokens for the e2e harness, the way every console service gets them (hub decision 23):
 * the client signs a short JWT assertion with its own registered key (ES256, iss = sub = its
 * client id, aud console-bff) and the BFF answers an internal JWT for the audiences asked for.
 * The BFF knows the harness as the client console-e2e (config/bff/application.yaml).
 */

const CLIENT_ID = 'console-e2e'
const cache = new Map<string, { token: string; until: number }>()

function assertion(): string {
  const jwk = harnessKey()
  const key = createPrivateKey({ key: jwk, format: 'jwk' })
  const b64 = (o: object) => Buffer.from(JSON.stringify(o)).toString('base64url')
  const now = Math.floor(Date.now() / 1000)
  const head = b64({ alg: 'ES256', kid: jwk.kid, typ: 'JWT' })
  const body = b64({ iss: CLIENT_ID, sub: CLIENT_ID, aud: 'console-bff', iat: now, exp: now + 60, jti: randomUUID() })
  const sig = sign('sha256', Buffer.from(`${head}.${body}`), { key, dsaEncoding: 'ieee-p1363' }).toString('base64url')
  return `${head}.${body}.${sig}`
}

/** An internal JWT for these audiences (console-identity-service, console-drift-service, ...). */
export async function serviceToken(...audiences: string[]): Promise<string> {
  const k = audiences.slice().sort().join(' ')
  const hit = cache.get(k)
  if (hit && hit.until > Date.now()) return hit.token
  const form = new URLSearchParams({
    grant_type: 'client_credentials',
    client_assertion_type: 'urn:ietf:params:oauth:client-assertion-type:jwt-bearer',
    client_assertion: assertion(),
    client_id: CLIENT_ID,
  })
  for (const a of audiences) form.append('audience', a)
  const res = await fetch(`${URLS.bff}/internal/v1/token`, { method: 'POST', body: form, headers: { 'Content-Type': 'application/x-www-form-urlencoded' } })
  const text = await res.text()
  if (!res.ok) throw new Error(`the BFF refused a service token for ${k}: ${res.status} ${text}`)
  const { access_token: token, expires_in: ttl } = JSON.parse(text) as { access_token: string; expires_in?: number }
  cache.set(k, { token, until: Date.now() + Math.max(30, (ttl ?? 300) - 30) * 1000 })
  return token
}

/** fetch with a service token, answering the parsed JSON (or text) and the status. */
export async function internal<T = unknown>(
  url: string,
  audience: string,
  init: { method?: string; body?: unknown; headers?: Record<string, string> } = {},
): Promise<{ status: number; body: T }> {
  const token = await serviceToken(audience)
  const res = await fetch(url, {
    method: init.method ?? 'GET',
    headers: { Authorization: `Bearer ${token}`, Accept: 'application/json', ...(init.body !== undefined ? { 'Content-Type': 'application/json' } : {}), ...init.headers },
    body: init.body === undefined ? undefined : JSON.stringify(init.body),
  })
  const text = await res.text()
  let body: unknown = text
  try {
    body = text ? JSON.parse(text) : undefined
  } catch {
    /* not JSON */
  }
  return { status: res.status, body: body as T }
}
