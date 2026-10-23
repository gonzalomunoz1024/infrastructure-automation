import { describe, expect, it } from 'vitest'
import { allowedSentence, andList, applyProblems, changeProblems, draftOf, draftTags, emptyTagsDraft, groupChangeProblems, groupDecision, keysOf, missingTags, newGroupProblems, newTagRow, policyProblems, requiredValue, rowErrors, tagAccepts, tagDefault, tagRulesOf, tagSentence, type RequiredTag } from './tags'

/* The tag rules as the BFF words them: required tags at create, what a change may not do, the Tag Editor's body, a group's query, and the create form's draft. */

const OWNER: RequiredTag = { key: 'owner', title: 'Owner', default: 'team-compute', defaultFrom: 'account' }
const COST: RequiredTag = { key: 'cost-centre', title: 'Cost centre', pattern: '^cc-[0-9]{4}$', default: 'cc-1042', defaultFrom: 'account' }
const CLASS: RequiredTag = { key: 'data-class', title: 'Data class', values: ['public', 'internal', 'confidential', 'restricted'], default: 'internal', defaultFrom: 'organization' }
const ENV: RequiredTag = { key: 'environment', title: 'Environment', values: ['dev', 'test', 'prod'], defaultFrom: 'input.environment' }
const POLICY = [OWNER, COST, CLASS, ENV]
const ALL = { owner: 'team-compute', 'cost-centre': 'cc-1042', 'data-class': 'internal', environment: 'dev' }

describe('a required tag', () => {
  it('takes a value in its list or matching its whole pattern, never a blank one or one over 256 characters', () => {
    expect(tagAccepts(COST, 'cc-1042')).toBe(true)
    expect(tagAccepts(COST, 'CC-1042')).toBe(false)
    expect(tagAccepts(COST, 'cc-10423')).toBe(false)
    expect(tagAccepts(CLASS, 'secret')).toBe(false)
    expect(tagAccepts(OWNER, '  ')).toBe(false)
    expect(tagAccepts(OWNER, 'x'.repeat(257))).toBe(false)
    expect(tagAccepts({ key: 'k', title: 'K', pattern: 'a' }, 'ab'), 'the pattern matches the whole value, as Java reads it').toBe(false)
  })

  it('says what it takes in the BFF’s words', () => {
    expect(allowedSentence(COST)).toBe('Cost centre must match ^cc-[0-9]{4}$')
    expect(allowedSentence(CLASS)).toBe('Data class is one of public, internal, confidential, restricted')
    expect(allowedSentence(OWNER)).toBe('Owner is at most 256 characters')
  })

  it('pre-fills its default, or the create’s own field for input.<field>', () => {
    expect(tagDefault(OWNER)).toBe('team-compute')
    expect(tagDefault(ENV, { environment: 'prod' })).toBe('prod')
    expect(tagDefault(ENV, {})).toBe('')
  })
})

describe('the policy’s checks', () => {
  it('names every missing or blank tag and every value it may not take, at /tags/<key>', () => {
    expect(policyProblems(POLICY, ALL)).toEqual([])
    const problems = policyProblems(POLICY, { owner: 'team-compute', 'cost-centre': 'CC-1', environment: ' ' })
    expect(problems).toEqual([
      { level: 'error', code: 'tags.invalid', field: '/tags/cost-centre', message: 'Cost centre must match ^cc-[0-9]{4}$' },
      { level: 'error', code: 'tags.required', field: '/tags/data-class', message: 'Data class is required (this account uses internal)' },
      { level: 'error', code: 'tags.required', field: '/tags/environment', message: 'Environment is required' },
    ])
    expect(keysOf(problems)).toEqual(['cost-centre', 'data-class', 'environment'])
    expect(tagRulesOf(problems)).toEqual([
      { id: 'tags.required', result: 'deny', message: 'required tags are missing: data-class, environment' },
      { id: 'tags.invalid', result: 'deny', message: 'tags with values they may not take: cost-centre' },
    ])
    expect(tagSentence(problems)).toEqual({ error: 'tags.required', message: 'a create names every required tag: cost-centre, data-class, environment' })
  })

  it('lets a change keep or change a required tag, never drop one the resource carries or give it a wrong value', () => {
    expect(changeProblems(POLICY, { owner: 'a', environment: 'dev' }, { owner: 'b', environment: 'test' }, [])).toEqual([])
    expect(changeProblems(POLICY, { owner: 'a', environment: 'dev' }, { owner: 'a' }, ['environment'])).toEqual([{ level: 'error', code: 'tags.required', field: '/tags/environment', message: 'Environment is required and stays on the resource' }])
    expect(changeProblems(POLICY, {}, { environment: 'staging' }, [])).toEqual([{ level: 'error', code: 'tags.invalid', field: '/tags/environment', message: 'Environment is one of dev, test, prod' }])
    expect(changeProblems(POLICY, { team: 'x' }, {}, ['team']), 'a resource that never carried a required tag is not made to').toEqual([])
  })
})

describe('the Tag Editor’s change', () => {
  const wfrn = 'wfrn:wf:compute:global:123456789012:vm/vm-demo01'
  it('needs a resource and a tag, at most 100 resources, names the state service takes, and the policy’s rules', () => {
    expect(applyProblems(POLICY, { resources: [wfrn], set: { team: 'web' }, remove: ['old'] })).toEqual([])
    expect(applyProblems(POLICY, { resources: [], set: {}, remove: [] }).map((p) => [p.code, p.field])).toEqual([
      ['required', '/resources'],
      ['required', '/set'],
    ])
    expect(applyProblems(POLICY, { resources: Array(101).fill(wfrn), set: { a: 'b' }, remove: [] })[0]).toMatchObject({ code: 'tags.too-many-resources', message: 'at most 100 resources at once, not 101' })
    const problems = applyProblems(POLICY, { resources: [wfrn], set: { Team: 'x', 'cost-centre': 'nope', team: 'y' }, remove: ['team', 'owner'] })
    expect(problems.map((p) => [p.code, p.field])).toEqual([
      ['tag.name', '/set/Team'],
      ['tags.conflict', '/set/team'],
      ['tags.required', '/remove/1'],
      ['tags.invalid', '/set/cost-centre'],
    ])
  })
})

describe('a resource group’s body', () => {
  const known = new Set(['vm', 'volume'])
  it('checks the name, the description and the query, each at its pointer', () => {
    expect(newGroupProblems({ name: 'web-tier', description: '', query: { types: ['vm'], tags: { team: 'web' } } }, known)).toEqual([])
    expect(newGroupProblems({ query: { types: ['vm', 'nope'], tags: {} } }, known).map((p) => [p.code, p.field, p.message])).toEqual([
      ['required', '/name', 'name is required'],
      ['type.unknown', '/query/types/1', "no type 'nope' in this region"],
      ['group.query.tags-required', '/query/tags', "a group's query names at least one tag"],
    ])
    const many = Object.fromEntries(Array.from({ length: 21 }, (_, i) => [`t${i}`, 'v']))
    expect(newGroupProblems({ name: 'bad name', description: 'x'.repeat(513), query: { types: [], tags: many } }, known).map((p) => p.code)).toEqual(['group.name', 'length', 'length'])
    expect(newGroupProblems({ name: 'g', query: { types: [], tags: { 'a/b': 'x' } } }, known)[0]).toMatchObject({ code: 'tag.name', field: '/query/tags/a~1b' })
  })

  it('greys a write for the roles that cannot make it: an operator creates and changes, only an admin deletes', () => {
    expect(groupDecision(['operator'], 'create', 'Create resource groups').allowed).toBe(true)
    expect(groupDecision(['operator'], 'delete', 'Delete resource groups').denied).toMatchObject({ layer: 'console-iam', action: 'resource-groups:delete', suggestedRole: 'account-admin', reason: 'Only an Admin of this account can delete resource groups; you hold Operator' })
    expect(groupDecision(['viewer'], 'update', 'Change resource groups').denied?.reason).toBe('Only an Operator or an Admin of this account can change resource groups; you hold Viewer')
    expect(groupDecision(['account-admin'], 'delete', 'Delete resource groups').allowed).toBe(true)
  })

  it('never changes a group’s name', () => {
    expect(groupChangeProblems({ name: 'other', description: 'x' }, { name: 'web-tier' }, known)[0]).toMatchObject({ code: 'group.name-immutable', field: '/name' })
    expect(groupChangeProblems({ name: 'web-tier', query: { types: [], tags: { team: 'web' } } }, { name: 'web-tier' }, known)).toEqual([])
  })
})

describe('the create form’s tags', () => {
  it('follows the create’s own field until the person sets the tag, and sends only the tags that have a value', () => {
    const draft = emptyTagsDraft()
    expect(requiredValue(ENV, draft, { environment: 'prod' })).toBe('prod')
    const edited = { ...draft, values: { environment: 'test' }, edited: ['environment'] }
    expect(requiredValue(ENV, edited, { environment: 'prod' })).toBe('test')
    const tags = draftTags(POLICY, { ...draft, extra: [newTagRow('team', 'web'), newTagRow('', '')] }, {})
    expect(tags).toEqual({ owner: 'team-compute', 'cost-centre': 'cc-1042', 'data-class': 'internal', team: 'web' })
    expect(missingTags(POLICY, draft, {}).map((t) => t.key)).toEqual(['environment'])
  })

  it('names what is wrong with the person’s own rows', () => {
    const rows = [newTagRow('Team', 'x'), newTagRow('', 'orphan'), newTagRow('owner', 'me'), newTagRow('a', '1'), newTagRow('a', '2')]
    const errors = rowErrors(rows, POLICY)
    expect(Object.values(errors).map((e) => e.key)).toEqual(['A tag name is 1 to 32 lowercase letters, digits or hyphens', 'Name the tag, or clear its value', 'Owner is a required tag; set it above', 'Another row names a already'])
  })

  it('opens on a clone’s tags: the required ones as set, the rest as rows', () => {
    const draft = draftOf({ owner: 'team-data', team: 'web' }, POLICY)
    expect(draft.values).toEqual({ owner: 'team-data' })
    expect(draft.edited).toEqual(['owner'])
    expect(draft.extra.map((r) => [r.key, r.value])).toEqual([['team', 'web']])
    expect(andList(['Owner', 'Cost centre', 'Environment'])).toBe('Owner, Cost centre and Environment')
  })
})
