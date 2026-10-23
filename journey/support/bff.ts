import { randomUUID } from 'node:crypto'
import type { APIRequestContext, APIResponse } from '@playwright/test'

/*
 * The BFF as the console calls it (hub contracts/openapi/console-bff.yaml), with a signed-in
 * person's session cookie: every write carries X-Csrf-Token from GET /console/v1/session and an
 * Idempotency-Key, as console-web's API client sends them. The journey uses it to check what
 * the console's pages would show, and to take a step through the same route the console takes
 * when that page is blocked by a finding.
 */

export interface Session {
  user: { userId: string; email: string; name?: string }
  account: { accountId: string; name: string; roles: string[]; groups: string[] }
  region: string
  csrfToken: string
  stepUp?: { actions: string[]; freshUntil?: string }
}

export interface Accepted {
  wfrn: string
  jobId: string
  requestId: string
  approval?: unknown
}

export interface Resource {
  wfrn: string
  type: string
  name: string
  state: string
  version: number
  attributes: Record<string, unknown>
  observed?: Record<string, unknown>
  drift?: boolean
  tags?: Record<string, string>
  lastJobId?: string
}

export interface Finding {
  findingId: string
  wfrn: string
  status: 'open' | 'closed'
  severity: string
  fields: { path: string; expected: unknown; observed: unknown }[]
  resolution?: string
  revertStatus?: string
  revertJobId?: string
  decidedBy?: string
  can?: string[]
}

export interface Job {
  jobId: string
  workflow: string
  status: string
  output?: Record<string, unknown>
  approvals?: { approvalId: string; group?: string; status: string; canDecide?: boolean }[]
}

export class BffError extends Error {
  constructor(
    readonly status: number,
    readonly body: string,
    what: string,
  ) {
    super(`${what}: the BFF answered ${status} ${body.slice(0, 500)}`)
  }
}

const enc = encodeURIComponent

export class Bff {
  private csrf?: string

  constructor(private readonly api: APIRequestContext) {}

  private async read<T>(res: APIResponse, what: string): Promise<T> {
    const text = await res.text()
    if (!res.ok()) throw new BffError(res.status(), text, what)
    return (text ? JSON.parse(text) : undefined) as T
  }

  async session(): Promise<Session> {
    const s = await this.read<Session>(await this.api.get('/console/v1/session'), 'GET /console/v1/session')
    this.csrf = s.csrfToken
    return s
  }

  async get<T>(path: string): Promise<T> {
    return this.read<T>(await this.api.get(path), `GET ${path}`)
  }

  /** A GET that answers null for 404 (gone, or not yet there). */
  async find<T>(path: string): Promise<T | null> {
    const res = await this.api.get(path)
    if (res.status() === 404) return null
    return this.read<T>(res, `GET ${path}`)
  }

  async write<T>(method: 'POST' | 'PUT' | 'PATCH' | 'DELETE', path: string, body?: unknown, key: string = randomUUID()): Promise<T> {
    if (!this.csrf) await this.session()
    const res = await this.api.fetch(path, {
      method,
      headers: { 'X-Csrf-Token': this.csrf!, 'Idempotency-Key': key, ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}) },
      data: body === undefined ? undefined : JSON.stringify(body),
    })
    return this.read<T>(res, `${method} ${path}`)
  }

  resource(type: string, wfrn: string): Promise<Resource | null> {
    return this.find<Resource>(`/console/v1/r/${enc(type)}/${enc(wfrn)}`)
  }

  /** A deleted resource answers 410 with its tombstone; null while it still exists. */
  async tombstone(type: string, wfrn: string): Promise<{ wfrn: string; deletedAt: string; tombstoneUntil?: string } | null> {
    const path = `/console/v1/r/${enc(type)}/${enc(wfrn)}`
    const res = await this.api.get(path)
    if (res.status() === 410) return ((await res.json()) as { detail: { wfrn: string; deletedAt: string; tombstoneUntil?: string } }).detail
    await this.read(res, `GET ${path}`)
    return null
  }

  list(type: string, query: Record<string, string> = {}): Promise<{ items: Resource[] }> {
    return this.get(`/console/v1/r/${enc(type)}?${new URLSearchParams(query)}`)
  }

  create(type: string, body: { input: Record<string, unknown>; tags?: Record<string, string>; reason?: string }, key?: string): Promise<Accepted> {
    return this.write('POST', `/console/v1/r/${enc(type)}`, body, key)
  }

  run(type: string, wfrn: string, verb: string, body: { input: Record<string, unknown>; confirm?: boolean; reason?: string }, key?: string): Promise<Accepted> {
    return this.write('POST', `/console/v1/r/${enc(type)}/${enc(wfrn)}:${verb}`, body, key)
  }

  job(jobId: string): Promise<Job> {
    return this.get(`/console/v1/jobs/${enc(jobId)}`)
  }

  decideApprovals(jobId: string, decision: 'approve' | 'reject', comment?: string): Promise<{ decided: unknown[]; skipped?: unknown[] }> {
    return this.write('POST', `/console/v1/jobs/${enc(jobId)}/approvals:decide`, { decision, comment })
  }

  search(q: string): Promise<{ items: { wfrn: string; name?: string; type: string }[] }> {
    return this.get(`/console/v1/search?${new URLSearchParams({ q, limit: '20' })}`)
  }

  drift(type: string, wfrn: string): Promise<{ items: Finding[] }> {
    return this.get(`/console/v1/r/${enc(type)}/${enc(wfrn)}/drift`)
  }

  finding(findingId: string): Promise<Finding> {
    return this.get(`/console/v1/findings/${enc(findingId)}`)
  }

  revert(findingId: string, reason?: string): Promise<{ findingId: string; jobId: string }> {
    return this.write('POST', `/console/v1/findings/${enc(findingId)}:revert`, reason ? { reason } : {})
  }

  cost(type: string, wfrn: string): Promise<{ wfrn: string; monthly: number; monthToDate: number; currency: string; source: string; charged: boolean }> {
    return this.get(`/console/v1/r/${enc(type)}/${enc(wfrn)}/cost`)
  }
}
