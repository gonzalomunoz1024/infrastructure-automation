import { describe, expect, it } from 'vitest'
import type { AccessReview, AccessReviewItem } from '../lib/accessReviews'
import type { Elevation, ElevationRules } from '../lib/elevations'
import type { BffConsoleType, Denial, Page } from '../lib/types'
import type { Session } from '../lib/session'
import { closeAccount, holdsGroup } from './access'
import { buildMockData } from './data'
import { buildDirectory } from './directory'
import { createMockServer, csrfTokenOf, memorySessionStore } from './server'

/*
 * Access over time in the mock (./access): a review campaign takes everyone's memberships, its
 * items are kept or removed (never by the person reviewed), the due date expires what is left
 * and takes the group away, the last owner excepted; an elevation is approved by the rule or by
 * an admin who is not the requester, decides with `elevated` while active and stops at `until`;
 * break-glass access is the organization admins'; closing an account is refused while it holds
 * resources, and otherwise ends what the account had open.
 */

const COMPUTE = '123456789012'
const DATA = '210987654321'
const START = Date.parse('2026-10-23T10:00:00Z')
const MIN = 60_000
const DAY = 86_400_000

function world(person = 'gonzalo', accountId?: string) {
  let now = START
  let who = person
  const data = buildMockData()
  const server = createMockServer(data, memorySessionStore(), () => now)
  const call = (method: string, url: string, body?: unknown) => server.handle({ method, url, headers: { 'x-csrf-token': csrfTokenOf(who) }, body })
  const as = (next: string, account?: string) => {
    who = next
    server.handle({ method: 'GET', url: `/auth/login?login_hint=${next}`, headers: {} })
    if (account) expect(call('PUT', '/console/v1/session', { accountId: account }).status).toBe(200)
  }
  as(person, accountId)
  return {
    data,
    call,
    as,
    get: (url: string) => call('GET', url),
    tick: (ms: number) => {
      now += ms
      if (ms > 3_600_000) as(who)
    },
  }
}

const body = <T>(res: { body?: unknown }) => res.body as T
const deniedOf = (res: { body?: unknown }) => (res.body as { denied?: Denial }).denied

function deleteDecision(w: ReturnType<typeof world>) {
  const types = body<BffConsoleType[]>(w.get('/console/v1/types'))
  return types.find((t) => t.type === 'vm')!.actions.find((a) => a.verb === 'delete')!
}

describe('access reviews', () => {
  it('takes every membership, is kept or removed by an admin, and closes when nothing is left', () => {
    const w = world('gonzalo', DATA)
    const started = w.call('POST', '/console/v1/iam/access-reviews', { name: 'Data, autumn', dueInDays: 7 })
    expect(started.status).toBe(201)
    const review = body<AccessReview>(started)
    expect(review.status).toBe('open')
    // owners gonzalo, members raj and ana, viewers ana, data-approvers raj
    expect(review.counts).toEqual({ total: 5, kept: 0, revoked: 0, expired: 0, pending: 5 })
    expect(Date.parse(review.dueAt) - START).toBe(7 * DAY)
    // one open campaign per account
    const again = w.call('POST', '/console/v1/iam/access-reviews', {})
    expect(again.status).toBe(409)
    expect((again.body as { detail: { reviewId: string } }).detail.reviewId).toBe(review.reviewId)

    const items = body<Page<AccessReviewItem>>(w.get(`/console/v1/iam/access-reviews/${review.reviewId}/items`)).items
    const own = items.find((i) => i.userId === 'gonzalo')!
    const refusedOwn = w.call('POST', `/console/v1/iam/access-reviews/${review.reviewId}/items/${own.itemId}:decide`, { decision: 'keep' })
    expect(refusedOwn.status).toBe(403)
    expect(deniedOf(refusedOwn)?.reason).toBe('nobody reviews their own access')

    const anaViewer = items.find((i) => i.userId === 'ana' && i.groupName === 'viewers')!
    const removed = w.call('POST', `/console/v1/iam/access-reviews/${review.reviewId}/items/${anaViewer.itemId}:decide`, { decision: 'revoke', comment: 'Ana operates; viewer is redundant' })
    expect(removed.status).toBe(200)
    expect(body<AccessReviewItem>(removed).decision).toBe('revoke')
    expect(holdsGroup(w.data.directory, DATA, 'ana', 'viewers')).toBe(false)
    expect(holdsGroup(w.data.directory, DATA, 'ana', 'members')).toBe(true)
    // a decided item is final
    expect(w.call('POST', `/console/v1/iam/access-reviews/${review.reviewId}/items/${anaViewer.itemId}:decide`, { decision: 'keep' }).status).toBe(409)

    for (const i of items.filter((x) => x.userId !== 'gonzalo' && x !== anaViewer))
      expect(w.call('POST', `/console/v1/iam/access-reviews/${review.reviewId}/items/${i.itemId}:decide`, { decision: 'keep' }).status).toBe(200)
    const pending = body<Page<AccessReviewItem>>(w.get(`/console/v1/iam/access-reviews/${review.reviewId}/items?decision=pending`)).items
    expect(pending.map((i) => i.userId)).toEqual(['gonzalo'])
  })

  it('expires what is undecided at the due date, keeping the last owner', () => {
    const w = world('gonzalo', DATA)
    const review = body<AccessReview>(w.call('POST', '/console/v1/iam/access-reviews', { dueInDays: 1 }))
    w.tick(DAY + MIN)
    const after = body<AccessReview>(w.get(`/console/v1/iam/access-reviews/${review.reviewId}`))
    expect(after.status).toBe('closed')
    expect(after.counts).toMatchObject({ expired: 4, kept: 1, pending: 0 })
    expect(holdsGroup(w.data.directory, DATA, 'raj', 'members')).toBe(false)
    expect(holdsGroup(w.data.directory, DATA, 'gonzalo', 'owners')).toBe(true)
    const items = body<Page<AccessReviewItem>>(w.get(`/console/v1/iam/access-reviews/${review.reviewId}/items`)).items
    expect(items.find((i) => i.userId === 'gonzalo')).toMatchObject({ decision: 'keep', decidedBy: 'system:access-review' })
  })

  it('is read by admins and auditors only, and a cancelled campaign never expires', () => {
    const w = world('raj', DATA)
    const refused = w.get('/console/v1/iam/access-reviews')
    expect(refused.status).toBe(403)
    expect(deniedOf(refused)).toMatchObject({ layer: 'console-iam', action: 'access-review:list', suggestedRole: 'auditor', requestable: true })
    w.as('gonzalo', DATA)
    const review = body<AccessReview>(w.call('POST', '/console/v1/iam/access-reviews', { dueInDays: 1 }))
    expect(body<AccessReview>(w.call('POST', `/console/v1/iam/access-reviews/${review.reviewId}:cancel`)).status).toBe('cancelled')
    w.tick(2 * DAY)
    expect(holdsGroup(w.data.directory, DATA, 'raj', 'members')).toBe(true)
  })
})

describe('elevations', () => {
  it('approves an operator’s ask for Admin by rule, decides with elevated, and stops at until', () => {
    const w = world('dana', COMPUTE)
    expect(deleteDecision(w).allowed).toBe(false)
    const rules = body<ElevationRules>(w.get('/console/v1/iam/elevation-rules'))
    expect(rules.items[0]).toMatchObject({ roles: ['account-admin'], requesterRoles: ['operator'], maxMinutes: 120 })
    const asked = w.call('POST', '/console/v1/iam/elevations', { role: 'admin', reason: 'Clearing the load-test machines', durationMinutes: 60 })
    expect(asked.status).toBe(201)
    const e = body<Elevation>(asked)
    expect(e).toMatchObject({ status: 'active', role: 'account-admin', decidedBy: 'rule:operators-admin-two-hours' })
    expect(Date.parse(e.until!) - START).toBe(60 * MIN)
    const del = deleteDecision(w)
    expect(del).toMatchObject({ allowed: true, elevated: true })
    const session = body<Session>(w.get('/console/v1/session'))
    expect(session.account.roles).toContain('account-admin')
    expect(session.account.elevations).toEqual([{ elevationId: e.elevationId, role: 'account-admin', kind: 'elevation', until: e.until }])
    // the same role again answers the open one
    const again = w.call('POST', '/console/v1/iam/elevations', { role: 'account-admin', reason: 'again' })
    expect(again.status).toBe(409)
    expect(body<Elevation>(again).elevationId).toBe(e.elevationId)
    w.tick(61 * MIN)
    expect(deleteDecision(w).allowed).toBe(false)
    expect(body<Elevation>(w.get(`/console/v1/iam/elevations/${e.elevationId}`)).status).toBe('expired')
  })

  it('sends an ask no rule covers to an admin who is not the requester, and the person revokes their own', () => {
    const w = world('mia', COMPUTE)
    const e = body<Elevation>(w.call('POST', '/console/v1/iam/elevations', { role: 'operator', reason: 'Patching tonight', durationMinutes: 30 }))
    expect(e.status).toBe('pending')
    expect(e.approvers).toEqual(['gonzalo'])
    // a viewer reads only their own
    expect(w.get('/console/v1/iam/elevations?userId=dana').status).toBe(403)
    expect(body<Page<Elevation>>(w.get('/console/v1/iam/elevations')).items.map((x) => x.elevationId)).toEqual([e.elevationId])
    // an operator may not decide
    w.as('dana', COMPUTE)
    expect(w.call('POST', `/console/v1/iam/elevations/${e.elevationId}:decide`, { decision: 'approve' }).status).toBe(403)
    w.as('gonzalo', COMPUTE)
    const approved = body<Elevation>(w.call('POST', `/console/v1/iam/elevations/${e.elevationId}:decide`, { decision: 'approve', comment: 'ok' }))
    expect(approved).toMatchObject({ status: 'active', decidedBy: 'gonzalo' })
    w.as('mia', COMPUTE)
    const create = body<BffConsoleType[]>(w.get('/console/v1/types'))
      .find((t) => t.type === 'vm')!
      .actions.find((a) => a.verb === 'create')!
    expect(create).toMatchObject({ allowed: true, elevated: true })
    expect(body<Elevation>(w.call('POST', `/console/v1/iam/elevations/${e.elevationId}:revoke`, { comment: 'done' })).status).toBe('revoked')
    expect(w.call('POST', `/console/v1/iam/elevations/${e.elevationId}:revoke`).status).toBe(409)
  })

  it('refuses the requester deciding their own, and an auditor-free ask for a role held already', () => {
    const w = world('gonzalo', COMPUTE)
    expect(w.call('POST', '/console/v1/iam/elevations', { role: 'account-admin', reason: 'x' }).status).toBe(400)
    const e = body<Elevation>(w.call('POST', '/console/v1/iam/elevations', { role: 'auditor', reason: 'Reading the trail' }))
    expect(e.status).toBe('pending')
    // gonzalo is the account's only admin: nobody else may decide it
    expect(e.approvers).toEqual([])
    const own = w.call('POST', `/console/v1/iam/elevations/${e.elevationId}:decide`, { decision: 'approve' })
    expect(own.status).toBe(403)
    expect(deniedOf(own)?.reason).toBe('nobody decides their own elevation')
  })
})

describe('break-glass and closing an account', () => {
  it('lets only an organization admin open break-glass access, never for themselves', () => {
    const w = world('dana', COMPUTE)
    expect(body<Session>(w.get('/console/v1/session')).organizationAdmin).toBeUndefined()
    expect(w.call('POST', `/console/v1/organization/accounts/${DATA}/break-glass`, { userId: 'dana', reason: 'x' }).status).toBe(404)
    w.as('gonzalo', COMPUTE)
    expect(body<Session>(w.get('/console/v1/session')).organizationAdmin).toBe(true)
    expect(w.call('POST', `/console/v1/organization/accounts/${DATA}/break-glass`, { userId: 'gonzalo', reason: 'x' }).status).toBe(400)
    expect(w.call('POST', `/console/v1/organization/accounts/${DATA}/break-glass`, { userId: 'mateo', reason: 'x', durationMinutes: 300 }).status).toBe(400)
    const opened = w.call('POST', `/console/v1/organization/accounts/${COMPUTE}/break-glass`, { userId: 'mateo', reason: 'Restore the payments database', ticket: 'INC-4521', durationMinutes: 30 })
    expect(opened.status).toBe(201)
    expect(body<Elevation>(opened)).toMatchObject({ kind: 'break-glass', role: 'account-admin', status: 'active', decidedBy: 'gonzalo', ticket: 'INC-4521' })
    // on the account's own trail
    expect(body<Page<Elevation>>(w.get('/console/v1/iam/elevations?kind=break-glass')).items).toHaveLength(1)
  })

  it('refuses to close an account that holds resources, naming them', () => {
    const w = world('gonzalo', COMPUTE)
    const refused = w.call('POST', `/console/v1/organization/accounts/${DATA}:close`, { reason: 'Team merged into Compute' })
    expect(refused.status).toBe(409)
    const b = refused.body as { error: string; detail: { total: number; byType: Record<string, number> } }
    expect(b.error).toBe('account.hasResources')
    expect(b.detail.total).toBeGreaterThan(0)
    const list = body<Page<{ accountId: string; status: string }>>(w.get('/console/v1/organization/accounts'))
    expect(list.items.map((a) => a.status)).toEqual(['active', 'active'])
  })

  it('ends what an account had open when it closes', () => {
    const dir = buildDirectory()
    const now = START
    closeAccount(dir, DATA, 'Merged', now)
    expect(dir.accounts.find((a) => a.accountId === DATA)).toMatchObject({ status: 'closed', closeReason: 'Merged' })
    expect(dir.accessKeys[DATA].every((k) => k.status === 'revoked')).toBe(true)
    expect(() => closeAccount(dir, DATA, 'again', now)).toThrow(/closed already/)
  })
})
