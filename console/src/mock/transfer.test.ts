import { describe, expect, it } from 'vitest'
import type { TransferAnswer, TransferOptions } from '../lib/transfer'
import type { BffApproval, ConsoleResourceDetail, Page } from '../lib/types'
import { buildMockData } from './data'
import { restoreConsole, saveConsole } from './install'
import { createMockServer, csrfTokenOf, memorySessionStore } from './server'
import { STEP_UP_MAX_AGE_MS } from './transfer'

/*
 * Moving a resource to another account in the mock (./transfer, hub decision 30): the targets a
 * person may pick, the move under its new wfrn, the old wfrn's 410 naming where it went, every
 * refusal the contract lists, the step-up, and the journal replaying a move after a reload. And
 * the approvals list the phone view reads (./approvals).
 */

const COMPUTE = '123456789012'
const DATA = '210987654321'
const vm = (id: string, account = COMPUTE) => `wfrn:wf:compute:global:${account}:vm/${id}`
const START = Date.parse('2026-10-23T10:00:00Z')
const enc = encodeURIComponent
const body = <T>(res: { body?: unknown }) => res.body as T
const errorOf = (res: { body?: unknown }) => (res.body as { error?: string }).error

function world(person = 'dana') {
  let now = START
  let who = person
  const data = buildMockData()
  const server = createMockServer(data, memorySessionStore(), () => now)
  const call = (method: string, url: string, b?: unknown) => server.handle({ method, url, headers: { 'x-csrf-token': csrfTokenOf(who), 'idempotency-key': `k-${now}-${url}-${JSON.stringify(b ?? null)}` }, body: b })
  const as = (next: string, accountId?: string) => {
    who = next
    server.handle({ method: 'GET', url: `/auth/login?login_hint=${next}`, headers: {} })
    if (accountId) expect(call('PUT', '/console/v1/session', { accountId }).status).toBe(200)
  }
  as(person)
  return { data, server, call, as, tick: (ms: number) => (now += ms) }
}

/** Gonzalo, an admin of both accounts, adds Dana to Data's members (operator) */
function danaInData(w: ReturnType<typeof world>) {
  w.as('gonzalo', DATA)
  expect(w.call('POST', '/console/v1/iam/users', { userId: 'dana', groups: ['members'] }).status).toBeLessThan(300)
  w.as('dana', COMPUTE)
}

const move = (w: ReturnType<typeof world>, id: string, b: Record<string, unknown> = { toAccountId: DATA, reason: 'The data team runs it now', confirm: true }) => w.call('POST', `/console/v1/r/vm/${enc(vm(id))}:transfer`, b)

describe('moving a resource to another account in the mock', () => {
  it('offers the accounts the person works in, each with the decision to create the type there', () => {
    const w = world()
    let opts = body<TransferOptions>(w.call('GET', `/console/v1/r/vm/${enc(vm('vm-gen-005'))}/transfer-targets`))
    expect(opts.source).toEqual({ allowed: true })
    expect(opts.targets.map((t) => [t.name, t.current, t.allowed])).toEqual([['Compute', true, true]])
    danaInData(w)
    opts = body<TransferOptions>(w.call('GET', `/console/v1/r/vm/${enc(vm('vm-gen-005'))}/transfer-targets`))
    // the platform's role for Data reads compute only, but a move asks the account's own policies
    expect(opts.targets.find((t) => t.accountId === DATA)).toMatchObject({ name: 'Data', current: false, allowed: true })
  })

  it('moves the VM under a new wfrn in Data, and the old wfrn answers 410 with where it went', () => {
    const w = world()
    danaInData(w)
    const res = move(w, 'vm-gen-005')
    expect(res.status).toBe(201)
    expect(body<TransferAnswer>(res)).toMatchObject({ wfrn: vm('vm-gen-005', DATA), accountId: DATA, transferredFrom: vm('vm-gen-005') })
    const gone = w.call('GET', `/console/v1/r/vm/${enc(vm('vm-gen-005'))}`)
    expect(gone.status).toBe(410)
    expect(gone.body).toMatchObject({ error: 'resource.transferred', detail: { transferredTo: vm('vm-gen-005', DATA), toAccountId: DATA, toAccountName: 'Data' } })
    // it left Compute's list and lives in Data's
    expect(body<Page<{ wfrn: string }>>(w.call('GET', '/console/v1/r/vm?limit=200')).items.some((r) => r.wfrn.endsWith('vm-gen-005'))).toBe(false)
    w.as('dana', DATA)
    expect(body<ConsoleResourceDetail>(w.call('GET', `/console/v1/r/vm/${enc(vm('vm-gen-005', DATA))}`)).wfrn).toBe(vm('vm-gen-005', DATA))
    // a move back makes the first name live again
    const back = w.call('POST', `/console/v1/r/vm/${enc(vm('vm-gen-005', DATA))}:transfer`, { toAccountId: COMPUTE, confirm: true })
    expect(back.body).toMatchObject({ wfrn: vm('vm-gen-005') })
    w.as('dana', COMPUTE)
    expect(w.call('GET', `/console/v1/r/vm/${enc(vm('vm-gen-005'))}`).status).toBe(200)
  })

  it('refuses what the contract refuses: no confirm, the same account, a viewer, a stranger to the target, a busy resource', () => {
    const w = world()
    expect(move(w, 'vm-gen-005', { toAccountId: DATA, reason: 'x' }).status).toBe(400)
    expect(errorOf(move(w, 'vm-gen-005', { toAccountId: COMPUTE, confirm: true }))).toBe('transfer.sameAccount')
    // Dana is no member of Data yet
    const stranger = move(w, 'vm-gen-005')
    expect(stranger.status).toBe(403)
    expect(stranger.body).toMatchObject({ denied: { action: 'compute:create', layer: 'console-iam' } })
    danaInData(w)
    // a request in flight on the machine
    expect(w.call('POST', `/console/v1/r/vm/${enc(vm('vm-gen-003'))}:restart`, { input: {} }).status).toBe(202)
    expect(errorOf(move(w, 'vm-gen-003'))).toBe('resource.busy')
    expect(errorOf(move(w, 'vm-gen-004'))).toBe('resource.notActive')
    // the viewer may not move anything
    w.as('mia')
    const viewer = move(w, 'vm-gen-005')
    expect(viewer.status).toBe(403)
    expect(viewer.body).toMatchObject({ denied: { action: 'compute:transfer' } })
    expect(body<TransferOptions>(w.call('GET', `/console/v1/r/vm/${enc(vm('vm-gen-005'))}/transfer-targets`)).source.allowed).toBe(false)
  })

  it('asks for a fresh sign-in past the step-up age, and moves once the person signed in again', () => {
    const w = world()
    danaInData(w)
    w.tick(STEP_UP_MAX_AGE_MS + 1_000)
    const stale = move(w, 'vm-gen-005')
    expect(stale.status).toBe(403)
    expect(stale.body).toMatchObject({ error: 'step-up.required', detail: { stepUp: true, action: 'compute:transfer', login: '/auth/login?stepUp=true' } })
    w.as('dana')
    expect(move(w, 'vm-gen-005').status).toBe(201)
  })

  it('replays a move from the journal after a reload', () => {
    const w = world()
    danaInData(w)
    expect(move(w, 'vm-gen-005').status).toBe(201)
    const kept: Record<string, string> = {}
    saveConsole(w.data, { setItem: (k, v) => (kept[k] = v) })
    const fresh = buildMockData()
    restoreConsole(fresh, { getItem: (k) => kept[k] ?? null }, START + 60_000)
    const r = fresh.resources.find((x) => x.id === 'vm-gen-005')!
    expect(r).toMatchObject({ accountId: DATA, wfrn: vm('vm-gen-005', DATA) })
    expect(fresh.console?.transfers?.moves[0]).toMatchObject({ from: vm('vm-gen-005'), to: vm('vm-gen-005', DATA), by: 'dana' })
  })
})

describe('the approvals waiting for the person in the mock', () => {
  it('lists what the person may decide, and nothing for someone outside the approver group', () => {
    const w = world('dana')
    const mine = body<Page<BffApproval>>(w.call('GET', '/console/v1/approvals?status=pending'))
    expect(mine.items.length).toBeGreaterThan(0)
    expect(mine.items.every((a) => a.canDecide && a.status === 'pending' && a.jobId)).toBe(true)
    w.as('gonzalo')
    expect(body<Page<BffApproval>>(w.call('GET', '/console/v1/approvals?status=pending')).items.filter((a) => a.canDecide)).toEqual([])
    expect(w.call('GET', '/console/v1/approvals?status=later').status).toBe(400)
  })

  it('drops an approval once it is decided through the request', () => {
    const w = world('dana')
    const [first] = body<Page<BffApproval>>(w.call('GET', '/console/v1/approvals?status=pending')).items
    expect(w.call('POST', `/console/v1/jobs/${enc(first.jobId!)}/approvals:decide`, { decision: 'approve', comment: 'Fine by me' }).status).toBe(202)
    const after = body<Page<BffApproval>>(w.call('GET', '/console/v1/approvals?status=pending')).items
    expect(after.some((a) => a.approvalId === first.approvalId)).toBe(false)
  })
})
