import { platformToken, URLS } from './stack.js'

/*
 * platform-fake, reached directly with the console-internal service account's token: to find
 * a public image to build from, and for step 6, to change what the recorded vSphere answers for
 * a VM (P-09 POST /api/v1/demo/world:mutate), as if someone had resized it outside the console.
 */

async function call<T>(method: string, path: string, body?: unknown): Promise<T> {
  const res = await fetch(`${URLS.platform}${path}`, {
    method,
    headers: { Authorization: `Bearer ${platformToken()}`, Accept: 'application/json', ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}) },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  const text = await res.text()
  if (!res.ok) throw new Error(`platform-fake answered ${method} ${path} with ${res.status}: ${text.slice(0, 400)}`)
  return (text ? JSON.parse(text) : undefined) as T
}

interface PlatformResource {
  id: string
  name?: string
  type: string
  status?: string
  visibility?: string
}

/** A ready image every account may build from (visibility public), as the platform lists it. */
export async function publicImage(): Promise<PlatformResource> {
  const page = await call<{ items?: PlatformResource[] } | PlatformResource[]>('GET', '/api/v1/resources?type=image&limit=100')
  const items = Array.isArray(page) ? page : (page.items ?? [])
  const image = items.find((r) => r.visibility === 'public' && r.status === 'ready')
  if (!image) throw new Error(`platform-fake lists no public, ready image: ${JSON.stringify(items).slice(0, 400)}`)
  return image
}

/** Out-of-band change: what vSphere's reads of the VM answer from now on. */
export async function resizeOutOfBand(vmId: string, set: Record<string, unknown>): Promise<unknown> {
  return call('POST', '/api/v1/demo/world:mutate', { provider: 'vsphere', match: { id: vmId }, set })
}
