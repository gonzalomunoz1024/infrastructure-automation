import { describe, expect, it } from 'vitest'
import type { AssistantProposal } from '../../lib/assistant'
import { composeTypes } from '../../lib/descriptors'
import { decodeTokens } from '../../lib/listState'
import { decodePrefill } from '../../lib/prefill'
import { serverFiltersOf } from '../../lib/resourceList'
import { buildMockData } from '../../mock/data'
import { routeFor } from '../router'
import { chipText, chipsOf, contextOf, describeTerm, fieldTitle, filledOf, filterOfTokens, listableOf, openPath, proposalPath, sortWords, suggestionsFor, termsOf } from './proposals'

/*
 * What a proposal of the copilot opens: the list a person could have filtered by hand (the same
 * tokens, the same query to the BFF), the wizard or the drawer filled through `?prefill=`, or a
 * console route; and how its card says what it applies.
 */

const data = buildMockData()
const types = composeTypes(data.resourceTypes, data.catalog)
const vmType = types.find((t) => t.name === 'vm')!
const WEB01 = 'wfrn:wf:compute:global:123456789012:vm/vm-demo01'
const REGION = data.regionName

/** The route a path opens, with its query. */
const opened = (path: string | null) => {
  expect(path).not.toBeNull()
  return routeFor(`#${path}`)
}

describe('a filter proposal opens the list with the same filter', () => {
  const stopped: AssistantProposal = {
    kind: 'filter',
    label: 'List the 10 deleted virtual machines tagged environment=prod',
    service: 'compute',
    type: 'vm',
    filter: { state: 'deleted', tags: { environment: 'prod' } },
  }

  it('as the list’s own tokens, in its link', () => {
    const path = proposalPath(stopped, types, REGION)
    expect(path).toBe('/console/compute/vm?f=status%3Ddeleted%3Btag.environment%3Dprod')
    const route = opened(path)
    expect(route.name).toBe('type')
    expect(route.params).toEqual({ service: 'compute', type: 'vm' })
    // the list sends what the reading said
    expect(serverFiltersOf(decodeTokens(route.query.f))).toEqual({ state: 'deleted', 'tag.environment': 'prod' })
  })

  it('carries the order, drift, and the free text with its time terms as the list’s q', () => {
    const p: AssistantProposal = { kind: 'filter', label: 'List them', type: 'vm', filter: { drift: true, q: 'web created:>=2026-10-19 "two words"' }, sort: '-created' }
    const route = opened(proposalPath(p, types, REGION))
    expect(route.query.sort).toBe('-created')
    expect(serverFiltersOf(decodeTokens(route.query.f))).toEqual({ drift: true, q: 'web created:>=2026-10-19 "two words"' })
    // each term its own chip on the list, so each can be taken off
    expect(
      decodeTokens(route.query.f)
        .filter((t) => t.property === null)
        .map((t) => t.value),
    ).toEqual(['web', 'created:>=2026-10-19', '"two words"'])
  })

  it('finds the type by its name when the proposal names no service, and opens nothing for a type the console does not know', () => {
    expect(proposalPath({ kind: 'filter', label: 'x', type: 'vm', filter: {} }, types)).toBe('/console/compute/vm')
    expect(proposalPath({ kind: 'filter', label: 'x', type: 'spaceship', filter: {} }, types)).toBeNull()
    expect(proposalPath({ kind: 'filter', label: 'x', filter: {} }, types)).toBeNull()
  })
})

describe('what the list can hold', () => {
  it('turns a reading into tokens, and names what it leaves out', () => {
    expect(listableOf({ state: 'failed', drift: false, tags: { owner: 'team-payments', environment: '' }, region: 'us-east-1', q: 'web' }, REGION)).toEqual({
      tokens: [
        { property: 'status', operator: '=', value: 'failed' },
        { property: 'drift', operator: '=', value: 'no' },
        { property: 'tag.owner', operator: '=', value: 'team-payments' },
        { property: null, operator: ':', value: 'web' },
      ],
      leftOut: ['the tag environment with any value', 'the region us-east-1'],
    })
  })

  it('takes the console’s own region as said, and a tag name the list refuses as left out', () => {
    expect(listableOf({ region: REGION }, REGION)).toEqual({ tokens: [], leftOut: [] })
    expect(listableOf({ tags: { 'Not A Key': 'x' } }).leftOut).toEqual(['the tag Not A Key=x'])
    expect(listableOf(undefined)).toEqual({ tokens: [], leftOut: [] })
  })

  it('reads the list’s tokens back as a filter, for a question asked on the list', () => {
    expect(filterOfTokens(decodeTokens('status=active;drift=yes;tag.environment=prod;~web;~created:7d'))).toEqual({ state: 'active', drift: true, tags: { environment: 'prod' }, q: 'web created:7d' })
    expect(filterOfTokens([])).toBeUndefined()
  })

  it('splits free text into terms, a quoted phrase as one', () => {
    expect(termsOf('web  "two words" “curly one” created:7d')).toEqual(['web', '"two words"', '“curly one”', 'created:7d'])
    expect(termsOf('""')).toEqual([])
    expect(termsOf(undefined)).toEqual([])
  })
})

describe('a form proposal opens the form filled, never submitted', () => {
  it('create: the wizard with ?prefill=', () => {
    const p: AssistantProposal = {
      kind: 'create',
      label: 'Create a small virtual machine in dev',
      service: 'compute',
      type: 'vm',
      input: { size: 'small', environment: 'dev' },
      missing: ['name', 'image'],
    }
    const route = opened(proposalPath(p, types))
    expect(route.name).toBe('create')
    expect(route.path).toBe('/console/compute/vm/create')
    expect(decodePrefill(route.query.prefill)).toEqual({ size: 'small', environment: 'dev' })
    expect(proposalPath({ ...p, input: {} }, types)).toBe('/console/compute/vm/create')
  })

  it('action: the resource’s drawer for the verb, filled when there is something to fill', () => {
    const restart: AssistantProposal = { kind: 'action', label: 'Restart web-01', service: 'compute', type: 'vm', wfrn: WEB01, verb: 'restart', input: {} }
    const route = opened(proposalPath(restart, types))
    expect(route.name).toBe('resource')
    expect(route.params.wfrn).toBe(WEB01)
    expect(route.query).toEqual({ action: 'restart' })
    const patch = opened(proposalPath({ ...restart, verb: 'patch', input: { cve: 'CVE-2026-1234' } }, types))
    expect(patch.query.action).toBe('patch')
    expect(decodePrefill(patch.query.prefill)).toEqual({ cve: 'CVE-2026-1234' })
    expect(proposalPath({ ...restart, wfrn: undefined }, types)).toBeNull()
    expect(proposalPath({ ...restart, verb: undefined }, types)).toBeNull()
  })

  it('names the inputs by their titles, and never shows a sensitive one', () => {
    const schema = vmType.create?.input
    expect(filledOf(schema, { size: 'small', environment: 'dev', nonsense: 'x' }).map(chipText)).toEqual(['Size = small', 'Environment = dev'])
    expect(fieldTitle(schema, 'name')).toBe('VM name')
    expect(fieldTitle(schema, 'keepSnapshotDays')).toBe('Keep snapshot days')
    const secret = { type: 'object', properties: { password: { type: 'string', title: 'Password', 'x-console': { sensitive: true } }, user: { type: 'string', title: 'User' } } }
    expect(filledOf(secret, { password: 'hunter2', user: 'ops' }).map(chipText)).toEqual(['User = ops'])
  })
})

describe('an open proposal goes only to a console route', () => {
  it('keeps a route the console has, with its query', () => {
    expect(openPath('/cost/budgets')).toBe('/cost/budgets')
    expect(openPath('#/requests?status=failed')).toBe('/requests?status=failed')
    expect(proposalPath({ kind: 'open', label: 'Open the budgets', path: '/cost/budgets' }, types)).toBe('/cost/budgets')
  })

  it('opens nothing else', () => {
    for (const path of ['https://example.com', '//example.com/x', 'javascript:alert(1)', '/signin', '/no/such/page/here/at/all', '', undefined]) expect(openPath(path)).toBeNull()
  })
})

describe('the chips of a filter card', () => {
  it('say each part as the list does, times in words, then the order', () => {
    const { tokens } = listableOf({ state: 'deleted', tags: { environment: 'prod' }, q: 'created:14d web' })
    expect(chipsOf(tokens, '-created').map(chipText)).toEqual(['Status = deleted', 'Tag: environment = prod', 'Created in the last 14 days', 'search web', 'Sort newest first'])
    expect(chipsOf([], 'score')).toEqual([])
    expect(sortWords('name')).toBe('by name, A to Z')
  })

  it.each([
    ['created:7d', 'Created', 'in the last 7 days'],
    ['created:1w', 'Created', 'in the last week'],
    ['updated:24h', 'Updated', 'in the last 24 hours'],
    ['created:<90d', 'Created', 'more than 90 days ago'],
    ['updated:<=1d', 'Updated', 'more than 1 day ago'],
    ['created:2026-10-23', 'Created', 'on 2026-10-23'],
    ['created:>=2026-10-01', 'Created', 'since 2026-10-01'],
    ['created:>2026-10-01', 'Created', 'after 2026-10-01'],
    ['created:<2026-10-01', 'Created', 'before 2026-10-01'],
    ['created:<=2026-10-01', 'Created', 'on or before 2026-10-01'],
    ['created:>=2026-10-01T12:00Z', 'Created', 'since 2026-10-01T12:00Z'],
    ['updated:2026-10-01..2026-10-07', 'Updated', 'between 2026-10-01 and 2026-10-07'],
    ['created:7d..14d', 'Created', 'between 7 days and 14 days ago'],
  ])('%s is %s %s', (term, field, words) => {
    expect(describeTerm(term)).toEqual({ field, words })
  })

  it('leaves anything else to be shown as typed', () => {
    for (const term of ['web', 'created:soon', 'created:2026-10-01T12:00Z', 'created:7d..2026-10-01', 'owner:x']) expect(describeTerm(term)).toBeNull()
  })
})

describe('where the person is', () => {
  it('a list: its type and its filter, so “these” means the rows shown', () => {
    expect(contextOf(routeFor('#/console/compute/vm?f=status%3Dactive%3B~web'))).toEqual({ path: '/console/compute/vm', service: 'compute', type: 'vm', filter: { state: 'active', q: 'web' } })
  })

  it('a resource: its wfrn; anywhere else: the path alone', () => {
    expect(contextOf(routeFor(`#/console/compute/vm/${encodeURIComponent(WEB01)}`))).toEqual({ path: `/console/compute/vm/${encodeURIComponent(WEB01)}`, service: 'compute', type: 'vm', wfrn: WEB01 })
    expect(contextOf(routeFor('#/cost'))).toEqual({ path: '/cost' })
  })

  it('offers first questions that fit the page', () => {
    expect(suggestionsFor(routeFor(`#/console/compute/vm/${encodeURIComponent(WEB01)}`))).toEqual([
      { text: 'Explain this resource', intent: 'explain', context: expect.objectContaining({ wfrn: WEB01 }) },
    ])
    expect(suggestionsFor(routeFor('#/console/compute/vm')).map((s) => s.text)).toEqual(['Which of these drifted?'])
    expect(suggestionsFor(routeFor('#/')).map((s) => s.text)).toEqual(['Find stopped virtual machines in prod', 'Create a small VM in dev'])
  })
})
