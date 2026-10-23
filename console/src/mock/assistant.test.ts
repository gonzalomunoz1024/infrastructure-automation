import { describe, expect, it } from 'vitest'
import type { AssistantConversation, AssistantProposal, AssistantTurn, SearchInterpretation } from '../lib/assistant'
import type { ConsoleResource, ResourceCounts } from '../lib/types'
import { KEPT_MS, THINKING_MS, TURNS_PER_MINUTE, paramsOf, splitTimeTerms, timeBoundOf, typoAway } from './assistant'
import { buildMockData } from './data'
import { createMockServer, csrfTokenOf, memorySessionStore } from './server'

/*
 * The copilot in the mock BFF (WP 9.1): plain English read as the list's own filter, with the
 * list's own count; turns that think on the server's clock and answer in words and proposals; and
 * what a person may not read, ask or do.
 */

const NOW = Date.parse('2026-10-23T10:00:00Z')
const DAY = 86_400_000
const WEB01 = 'wfrn:wf:compute:global:123456789012:vm/vm-demo01'
const API01 = 'wfrn:wf:compute:global:123456789012:vm/vm-demo02'

function bff(person: string, start = NOW) {
  let now = start
  const data = buildMockData()
  const server = createMockServer(data, memorySessionStore(), () => now)
  const login = (who: string) => server.handle({ method: 'GET', url: `/auth/login?login_hint=${who}`, headers: {} })
  login(person)
  let as = person
  const call = (method: string, url: string, body?: unknown) => server.handle({ method, url, headers: { 'x-csrf-token': csrfTokenOf(as) }, body })
  return {
    data,
    call,
    get: (url: string) => call('GET', url),
    tick: (ms: number) => void (now += ms),
    /** signs another person in on the same server */
    switchTo: (who: string) => {
      login(who)
      as = who
    },
  }
}

type Bff = ReturnType<typeof bff>

function interpret(b: Bff, q: string, type?: string): SearchInterpretation {
  const res = b.get(`/console/v1/search:interpret?q=${encodeURIComponent(q)}${type ? `&type=${type}` : ''}`)
  expect(res.status).toBe(200)
  return res.body as SearchInterpretation
}

/** What the list shows for a reading: its own count and its own page, through the same routes the list page calls. */
function listed(b: Bff, type: string, it: Pick<SearchInterpretation, 'filter' | 'sort'>) {
  const params = paramsOf(it.filter)
  if (it.sort) params.set('sort', it.sort)
  const counts = b.get(`/console/v1/r/${type}:counts?${params}`)
  const page = b.get(`/console/v1/r/${type}?${params}&limit=200`)
  expect(counts.status).toBe(200)
  expect(page.status).toBe(200)
  return { total: (counts.body as ResourceCounts).total, rows: (page.body as { items: ConsoleResource[] }).items, header: Number(page.headers?.['X-Total-Count']) }
}

/** Asks, lets the copilot think, and returns its answer. */
function askAndWait(b: Bff, body: Record<string, unknown>): { conversationId: string; turn: AssistantTurn; conversation: AssistantConversation } {
  const res = b.call('POST', '/console/v1/assistant/turns', body)
  expect(res.status).toBe(202)
  const { conversationId, turnId } = res.body as { conversationId: string; turnId: string }
  b.tick(THINKING_MS)
  const conversation = b.get(`/console/v1/assistant/conversations/${conversationId}`).body as AssistantConversation
  const turn = conversation.turns.find((t) => t.turnId === turnId)!
  return { conversationId, turn, conversation }
}

const only = (turn: AssistantTurn): AssistantProposal => {
  expect(turn.proposals).toHaveLength(1)
  return turn.proposals![0]
}

describe('reading plain English (search:interpret)', () => {
  it('reads types, everyday state names and tag values as the list’s own filter', () => {
    const b = bff('gonzalo')
    const it = interpret(b, 'Find stopped virtual machines in prod')
    expect(it).toMatchObject({ type: 'vm', filter: { state: 'deleted', tags: { environment: 'prod' } }, confidence: 'high', q: 'type:vm state:deleted tag:environment=prod', total: 10 })
    // the console has no stopped state: the explanation says what it was read as
    expect(it.explanation).toBe('Virtual machines that are deleted (the console\'s state for "stopped"), tagged environment=prod.')
    expect(it.unparsed).toBeUndefined()
  })

  it('reads the contract’s own example, times and sorts included', () => {
    const b = bff('gonzalo')
    const it = interpret(b, 'failed vms tagged environment=prod created this week, newest first')
    expect(it).toMatchObject({ type: 'vm', filter: { state: 'failed', tags: { environment: 'prod' }, q: 'created:>=2026-10-19' }, sort: '-created', confidence: 'high' })
    expect(it.explanation).toBe('Virtual machines that failed, tagged environment=prod, created this week (since 2026-10-19), newest first.')
  })

  it.each([
    ['vms created in the last 14 days', 'created:14d', 'created in the last 14 days'],
    ['vms created today', 'created:2026-10-23', 'created today (2026-10-23)'],
    ['vms since October 1', 'created:>=2026-10-01', 'created since 2026-10-01'],
    ['vms older than 90 days', 'created:<90d', 'created more than 90 days ago'],
    ['vms not updated in 30 days', 'updated:<30d', 'not updated in 30 days'],
    ['vms changed in the past week', 'updated:1w', 'updated in the last week'],
    ['vms created:2026-10-01..2026-10-07', 'created:2026-10-01..2026-10-07', 'created between 2026-10-01 and 2026-10-07'],
  ])('reads “%s” as the term %s', (text, term, label) => {
    const it = interpret(bff('gonzalo'), text)
    expect(it.filter.q).toBe(term)
    expect(it.explanation).toContain(label)
    expect(it.confidence).toBe('high')
  })

  it.each([
    ['vms by name', 'name'],
    ['vms oldest first', 'created'],
    ['vms most recently updated', '-updated'],
    ['vms z to a', '-name'],
  ])('reads the order in “%s”', (text, sort) => {
    expect(interpret(bff('gonzalo'), text).sort).toBe(sort)
  })

  it('counts what the list shows, with the list’s own filtering, times included', () => {
    const b = bff('gonzalo')
    for (const text of [
      'stopped virtual machines in prod',
      'drifted vms',
      'drifted vms in the prod environment',
      'web vms',
      'vms created in the last 14 days',
      'running vms older than 20 days by name',
      'volumes in sync',
      'images',
    ]) {
      const it = interpret(b, text)
      const list = listed(b, it.type!, it)
      expect(list.total, text).toBe(it.total)
      expect(list.header, text).toBe(it.total)
      expect(list.rows.length, text).toBe(Math.min(200, it.total))
    }
  })

  it('says how sure it is: a typo or free text is medium, free text alone or a word left out is low', () => {
    const b = bff('gonzalo')
    expect(interpret(b, 'virtual machnies that are running').confidence).toBe('medium')
    expect(interpret(b, 'web vms')).toMatchObject({ filter: { q: 'web' }, confidence: 'medium' })
    expect(interpret(b, 'web')).toMatchObject({ filter: { q: 'web' }, confidence: 'low', total: 42 })
    expect(interpret(b, 'show me all')).toMatchObject({ filter: {}, confidence: 'medium' })
    const low = interpret(b, 'stopped machines in prod')
    expect(low).toMatchObject({ filter: { state: 'deleted', tags: { environment: 'prod' } }, confidence: 'low', unparsed: ['machines'] })
    expect(low.type).toBeUndefined()
  })

  it('stays in the list it is asked on: another type is left out and the reading is low', () => {
    const it = interpret(bff('gonzalo'), 'failed databases', 'vm')
    expect(it).toMatchObject({ type: 'vm', filter: { state: 'failed' }, confidence: 'low', unparsed: ['databases'] })
  })

  it('leaves out a union, a negation, a second state and a word that would list nothing', () => {
    const b = bff('gonzalo')
    expect(interpret(b, 'failed or deleted vms')).toMatchObject({ filter: { state: 'failed' }, unparsed: ['or deleted'], confidence: 'low' })
    expect(interpret(b, 'vms not in prod')).toMatchObject({ filter: {}, unparsed: ['not prod'] })
    expect(interpret(b, 'failed active vms')).toMatchObject({ filter: { state: 'failed' }, unparsed: ['active'] })
    expect(interpret(b, 'cache vms with 16 GB')).toMatchObject({ filter: { q: 'cache 16' }, unparsed: ['GB'] })
  })

  it('reads the query syntax, and leaves out “has the tag”, which the console’s list cannot hold', () => {
    const b = bff('gonzalo')
    expect(interpret(b, 'type:vm state:failed drift:true')).toMatchObject({ type: 'vm', filter: { state: 'failed', drift: true }, confidence: 'high' })
    expect(interpret(b, 'tag:owner=team-compute vms')).toMatchObject({ filter: { tags: { owner: 'team-compute' } } })
    expect(interpret(b, 'tag:owner vms')).toMatchObject({ filter: {}, unparsed: ['tag:owner'], confidence: 'low' })
    expect(interpret(b, '"web-01"').filter.q).toBe('web-01')
  })

  it('refuses what it cannot read', () => {
    const b = bff('gonzalo')
    expect(b.get('/console/v1/search:interpret').status).toBe(400)
    expect(b.get(`/console/v1/search:interpret?q=${'a'.repeat(501)}`).status).toBe(400)
    expect(b.get('/console/v1/search:interpret?q=vms&type=spaceship').status).toBe(400)
  })

  it('counts only what the person may list', () => {
    const b = bff('mia')
    expect(interpret(b, 'vms').total).toBe(216)
    // take the reads away: the same words now list nothing she may see
    const viewer = b.data.directory.policies['123456789012'].find((p) => p.policyId === 'managed-viewer')!
    viewer.statements[0].notActions = [...(viewer.statements[0].notActions ?? []), 'compute:list', 'compute:get']
    expect(interpret(b, 'vms').total).toBe(0)
  })
})

describe('the list keeps the indexer’s time terms', () => {
  it('reads a day, an age, a comparison and a range, and refuses a time alone', () => {
    const d = (s: string) => Date.parse(`${s}T00:00:00Z`)
    expect(timeBoundOf('created:2026-10-01', NOW)).toEqual({ field: 'created', from: d('2026-10-01'), to: d('2026-10-02') })
    expect(timeBoundOf('created:7d', NOW)).toEqual({ field: 'created', from: NOW - 7 * DAY })
    expect(timeBoundOf('updated:<30d', NOW)).toEqual({ field: 'updated', to: NOW - 30 * DAY })
    expect(timeBoundOf('created:>=2026-10-19', NOW)).toEqual({ field: 'created', from: d('2026-10-19') })
    expect(timeBoundOf('created:2026-10-01..2026-10-07', NOW)).toEqual({ field: 'created', from: d('2026-10-01'), to: d('2026-10-08') })
    expect(timeBoundOf('created:2026-10-01T12:00Z', NOW)).toBeNull()
    expect(timeBoundOf('created:soon', NOW)).toBeNull()
    expect(splitTimeTerms('web created:7d updated:<1d', NOW)).toMatchObject({ rest: 'web', bounds: [{ field: 'created' }, { field: 'updated' }] })
  })

  it('lists and counts only the rows in the range, with every other filter as before', () => {
    const b = bff('gonzalo')
    const { rows, total } = listed(b, 'vm', { filter: { q: 'created:14d web' } })
    expect(total).toBeGreaterThan(0)
    for (const r of rows) {
      expect(Date.parse(r.createdAt)).toBeGreaterThanOrEqual(NOW - 14 * DAY)
      expect(r.name).toContain('web')
    }
    const all = listed(b, 'vm', { filter: { q: 'web' } })
    expect(all.total).toBeGreaterThan(total)
  })

  it('refuses a list the person may not read, as the list does', () => {
    const b = bff('mia')
    const viewer = b.data.directory.policies['123456789012'].find((p) => p.policyId === 'managed-viewer')!
    viewer.statements[0].notActions = [...(viewer.statements[0].notActions ?? []), 'compute:list']
    expect(b.get('/console/v1/r/vm?q=created%3A7d').status).toBe(403)
    expect(b.get('/console/v1/r/vm?q=web').status).toBe(403)
  })
})

describe('typos', () => {
  it('forgives one up to six letters and two from seven, never below four, the first letter as typed', () => {
    expect(typoAway('faild', 'failed')).toBe(true)
    expect(typoAway('machnies', 'machines')).toBe(true)
    expect(typoAway('vns', 'vms')).toBe(false)
    expect(typoAway('dailed', 'failed')).toBe(false)
    expect(typoAway('fxxled', 'failed')).toBe(false)
  })
})

describe('a turn', () => {
  it('thinks for a moment on the server’s clock, then answers', () => {
    const b = bff('gonzalo')
    const res = b.call('POST', '/console/v1/assistant/turns', { intent: 'ask', message: 'Find stopped virtual machines in prod' })
    expect(res.status).toBe(202)
    const { conversationId, turnId } = res.body as { conversationId: string; turnId: string }
    const read = () => b.get(`/console/v1/assistant/conversations/${conversationId}`).body as AssistantConversation
    expect(read()).toMatchObject({
      conversationId,
      status: 'thinking',
      turns: [
        { role: 'person', text: 'Find stopped virtual machines in prod' },
        { turnId, role: 'assistant', status: 'thinking' },
      ],
    })
    expect(read().turns[1].text).toBeUndefined()
    b.tick(THINKING_MS - 1)
    expect(read().status).toBe('thinking')
    b.tick(1)
    expect(read()).toMatchObject({ status: 'ready', turns: [{}, { status: 'answered' }] })
  })

  it('proposes the filtered list, saying how many it lists, the same count the list shows', () => {
    const b = bff('gonzalo')
    const { turn } = askAndWait(b, { intent: 'ask', message: 'Find stopped virtual machines in prod' })
    const p = only(turn)
    expect(p).toEqual({
      kind: 'filter',
      label: 'List the 10 deleted virtual machines tagged environment=prod',
      service: 'compute',
      type: 'vm',
      filter: { state: 'deleted', tags: { environment: 'prod' } },
    })
    expect(listed(b, 'vm', p as Required<Pick<AssistantProposal, 'filter'>>).total).toBe(10)
    expect(turn.text).toBe(
      '10 deleted virtual machines tagged environment=prod match right now.\n\nI read it as: Virtual machines that are deleted (the console\'s state for "stopped"), tagged environment=prod.',
    )
  })

  it('says plainly when it is not sure, with what it read and what it left out', () => {
    const { turn } = askAndWait(bff('gonzalo'), { intent: 'ask', message: 'stopped machines in prod' })
    expect(turn.text).toMatch(/^I am not sure I understood\./)
    expect(turn.text).toContain('I read it as: Resources that are deleted')
    expect(turn.text).toContain('I left out: machines.')
    expect(only(turn)).toMatchObject({ kind: 'filter', type: 'vm', filter: { state: 'deleted', tags: { environment: 'prod' } } })
  })

  it('keeps the list’s own filter for “these”', () => {
    const b = bff('gonzalo')
    const { turn } = askAndWait(b, {
      intent: 'ask',
      message: 'Which of these drifted?',
      context: { path: '/console/compute/vm', service: 'compute', type: 'vm', filter: { tags: { environment: 'prod' } } },
    })
    const p = only(turn)
    expect(p.filter).toEqual({ tags: { environment: 'prod' }, drift: true })
    expect(p.label).toBe('List the 6 virtual machines that drifted, tagged environment=prod')
    expect(listed(b, 'vm', { filter: p.filter! }).total).toBe(6)
  })

  it('fills the create form with what it could read and names what it still needs', () => {
    const { turn } = askAndWait(bff('gonzalo'), { intent: 'ask', message: 'Create a small VM in dev' })
    expect(only(turn)).toEqual({
      kind: 'create',
      label: 'Create a small virtual machine in dev',
      service: 'compute',
      type: 'vm',
      input: { size: 'small', environment: 'dev' },
      missing: ['name', 'image'],
    })
    expect(turn.text).toContain('It still needs: VM name, Image.')
    const named = askAndWait(bff('gonzalo'), { intent: 'ask', message: 'Create a medium ubuntu VM in prod called web-77' }).turn
    expect(only(named)).toMatchObject({ input: { size: 'medium', environment: 'prod', name: 'web-77', image: 'image-demo01' } })
    expect(only(named).missing).toBeUndefined()
  })

  it('fills an action on the resource it names, never running it', () => {
    const b = bff('gonzalo')
    const jobs = b.data.jobs.length
    const { turn } = askAndWait(b, { intent: 'ask', message: 'Restart web-01 because it is stuck after a kernel update' })
    expect(only(turn)).toEqual({ kind: 'action', label: 'Restart web-01', service: 'compute', type: 'vm', wfrn: WEB01, verb: 'restart', input: { reason: 'it is stuck after a kernel update' } })
    const patch = askAndWait(b, { intent: 'ask', message: 'Patch this for CVE-2026-1234', context: { type: 'vm', wfrn: API01 } }).turn
    expect(only(patch)).toMatchObject({ verb: 'patch', wfrn: API01, label: 'Patch api-01 for CVE-2026-1234', input: { cve: 'CVE-2026-1234' } })
    expect(b.data.jobs.length).toBe(jobs)
  })

  it('explains a resource from its record, its last job, its lineage, its drift and the cost ledger', () => {
    const b = bff('gonzalo')
    const { turn, conversation } = askAndWait(b, { intent: 'explain', context: { wfrn: WEB01, type: 'vm', service: 'compute' } })
    expect(conversation.turns[0]).toMatchObject({ role: 'person', text: 'Explain web-01' })
    const e = turn.explanation!
    expect(e.wfrn).toBe(WEB01)
    expect(e.summary).toBe('web-01 is a virtual machine in Compute. It is active, with 4 CPUs and 16 GB of memory, in prod. It costs about $77.00 a month at list prices.')
    expect(e.sections!.map((s) => s.title)).toEqual(['What it is', 'Configuration', 'Cost', 'History', 'Dependents', 'Drift'])
    expect(e.sections!.find((s) => s.title === 'Cost')!.text).toContain('not an invoice')
    expect(e.facts).toEqual({
      monthlyCost: 77,
      currency: 'USD',
      createdAt: '2026-09-28T02:05:25.746991141Z',
      createdBy: 'create-vm 2.0.1',
      lastChangeAt: '2026-09-28T02:05:25.746991141Z',
      dependents: 2,
      drift: false,
    })
    const drifted = askAndWait(b, { intent: 'explain', context: { wfrn: API01 } }).turn.explanation!
    expect(drifted.facts!.drift).toBe(true)
    expect(drifted.summary).toContain('It has drifted: memoryGb changed outside the console.')
    expect(drifted.sections!.find((s) => s.title === 'Drift')!.text).toBe('At the last scan, memoryGb was 16 where 8 was asked for. Its Drift tab can revert it or accept it.')
  })

  it('never tells a secret', () => {
    const b = bff('raj')
    const db = 'wfrn:wf:data:global:210987654321:database/database-demo01'
    const { turn } = askAndWait(b, { intent: 'explain', context: { wfrn: db } })
    expect(turn.status).toBe('answered')
    expect(turn.explanation!.summary).toContain('orders-db is a database in Data')
    expect(JSON.stringify(turn)).not.toContain('vault:')
  })

  it('answers what it can do', () => {
    const { turn } = askAndWait(bff('gonzalo'), { intent: 'ask', message: 'What can you do?' })
    expect(turn.text).toContain('I never change anything')
    expect(turn.proposals).toBeUndefined()
  })

  it('proposes a page of the console named on its own, and reads anything else as before', () => {
    const b = bff('gonzalo')
    expect(only(askAndWait(b, { intent: 'ask', message: 'Take me to the budgets' }).turn)).toEqual({ kind: 'open', label: 'Open Budgets', path: '/cost/budgets' })
    expect(only(askAndWait(b, { intent: 'ask', message: 'open cost and usage page' }).turn)).toEqual({ kind: 'open', label: 'Open Cost and usage', path: '/cost' })
    expect(only(askAndWait(b, { intent: 'ask', message: 'show me my requests' }).turn)).toMatchObject({ kind: 'open', path: '/requests' })
    expect(only(askAndWait(b, { intent: 'ask', message: 'show me failed vms' }).turn)).toMatchObject({ kind: 'filter', type: 'vm', filter: { state: 'failed' } })
  })

  it('fails once on “fail the copilot”, and answers the rest when asked again', () => {
    const b = bff('gonzalo')
    const first = askAndWait(b, { intent: 'ask', message: 'fail the copilot: find stopped virtual machines in prod' })
    expect(first.turn).toMatchObject({ status: 'failed', error: { code: 'assistant.unavailable' } })
    expect(first.conversation.status).toBe('failed')
    const again = askAndWait(b, { intent: 'ask', conversationId: first.conversationId, message: 'fail the copilot: find stopped virtual machines in prod' })
    expect(again.conversation.status).toBe('ready')
    expect(again.conversation.turns).toHaveLength(4)
    expect(only(again.turn).label).toBe('List the 10 deleted virtual machines tagged environment=prod')
  })
})

describe('what a person may not read or do', () => {
  it('proposes no create to a viewer, and says why', () => {
    const { turn } = askAndWait(bff('mia'), { intent: 'ask', message: 'Create a small VM in dev' })
    expect(turn.proposals).toBeUndefined()
    expect(turn.text).toContain('You cannot create virtual machines in Compute: none of your roles allows compute:create.')
  })

  it('proposes no delete to an operator, and says why', () => {
    const { turn } = askAndWait(bff('dana'), { intent: 'ask', message: 'Delete web-01' })
    expect(turn.proposals).toBeUndefined()
    expect(turn.text).toContain('You cannot delete web-01: none of your roles allows compute:delete.')
  })

  it('refuses to explain what the person may not read', () => {
    const b = bff('mia')
    const viewer = b.data.directory.policies['123456789012'].find((p) => p.policyId === 'managed-viewer')!
    viewer.statements[0].notActions = [...(viewer.statements[0].notActions ?? []), 'compute:get', 'compute:list']
    const { turn } = askAndWait(b, { intent: 'explain', context: { wfrn: WEB01 } })
    expect(turn).toMatchObject({ status: 'failed', error: { code: 'assistant.refused' } })
    expect(turn.explanation).toBeUndefined()
    const listing = askAndWait(b, { intent: 'ask', message: 'failed vms' }).turn
    expect(listing.proposals).toBeUndefined()
    expect(listing.text).toContain('You cannot list virtual machines in Compute')
  })

  it('cannot explain a resource of another account', () => {
    const { turn } = askAndWait(bff('dana'), { intent: 'explain', context: { wfrn: 'wfrn:wf:data:global:210987654321:database/database-demo01' } })
    expect(turn.status).toBe('answered')
    expect(turn.text).toContain('I could not find')
    expect(turn.explanation).toBeUndefined()
  })
})

describe('the contract’s refusals', () => {
  it('answers 400 to a turn it cannot take', () => {
    const b = bff('gonzalo')
    for (const body of [
      {},
      { intent: 'chat', message: 'hi' },
      { intent: 'ask' },
      { intent: 'ask', message: '   ' },
      { intent: 'ask', message: 'a'.repeat(2001) },
      { intent: 'explain' },
      { intent: 'explain', context: { wfrn: 'vm-demo01' } },
    ]) {
      expect(b.call('POST', '/console/v1/assistant/turns', body).status, JSON.stringify(body).slice(0, 60)).toBe(400)
    }
    expect(b.call('POST', '/console/v1/assistant/turns', { intent: 'ask', message: 'a'.repeat(2000) }).status).toBe(202)
  })

  it('keeps each conversation its person’s own, for 24 hours', () => {
    const b = bff('gonzalo')
    const { conversationId } = askAndWait(b, { intent: 'ask', message: 'failed vms' })
    expect(b.get('/console/v1/assistant/conversations/conv_nope').status).toBe(404)
    expect(b.call('POST', '/console/v1/assistant/turns', { intent: 'ask', conversationId: 'conv_nope', message: 'hi' }).status).toBe(404)
    b.switchTo('dana')
    expect(b.get(`/console/v1/assistant/conversations/${conversationId}`).status).toBe(404)
    expect(b.call('POST', '/console/v1/assistant/turns', { intent: 'ask', conversationId, message: 'and these?' }).status).toBe(404)
    b.switchTo('gonzalo')
    expect(b.get(`/console/v1/assistant/conversations/${conversationId}`).status).toBe(200)
    b.tick(KEPT_MS)
    // a session lasts eight hours: sign in again to look a day later
    b.switchTo('gonzalo')
    expect(b.get(`/console/v1/assistant/conversations/${conversationId}`).status).toBe(404)
  })

  it('takes ten turns a minute from a person, then says when to try again', () => {
    const b = bff('gonzalo')
    for (let i = 0; i < TURNS_PER_MINUTE; i++) {
      expect(b.call('POST', '/console/v1/assistant/turns', { intent: 'ask', message: `failed vms ${i}` }).status).toBe(202)
      b.tick(2_000)
    }
    const refused = b.call('POST', '/console/v1/assistant/turns', { intent: 'ask', message: 'one more' })
    expect(refused.status).toBe(429)
    // the first of the ten leaves the minute 40 seconds from now
    expect(refused.headers?.['Retry-After']).toBe('40')
    expect(refused.body).toMatchObject({ retryAfter: 40 })
    // another person has their own minute
    b.switchTo('dana')
    expect(b.call('POST', '/console/v1/assistant/turns', { intent: 'ask', message: 'failed vms' }).status).toBe(202)
    b.switchTo('gonzalo')
    b.tick(39_000)
    expect(b.call('POST', '/console/v1/assistant/turns', { intent: 'ask', message: 'one more' }).status).toBe(429)
    b.tick(1_000)
    expect(b.call('POST', '/console/v1/assistant/turns', { intent: 'ask', message: 'one more' }).status).toBe(202)
  })
})
