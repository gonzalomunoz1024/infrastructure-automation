import { describe, expect, it } from 'vitest'
import { actionCatalogOf, checksOf, globMatches, guardrailProblems, holdersOf, inputOf, knowsAction, lineOf, partsOfPattern, patternOf, patternProblem, patternWords, policyJson, policyProblems, readJson, readPolicyJson, scopeLabel, sentinel, statementWords, type PolicyDoc } from './policies'
import { decidedBy } from './permissions'

/*
 * The policy rules the editor and the mock share: the identity service's wildcard rule and
 * document checks, the action catalog built from the types, the pattern helper, and the JSON
 * reader that puts each problem on its line.
 */

const VMS = 'wfrn:wf:compute:*:123456789012:vm/*'
const catalog = actionCatalogOf([
  { service: 'compute', actions: [{ verb: 'restart' }, { verb: 'delete' }, { verb: 'patch' }], create: { verb: 'create' } },
  { service: 'data', actions: [], create: { verb: 'create' } },
])

describe('the wildcard rule', () => {
  it('matches across : and /, with ? for one character, and keeps a checked pattern literal', () => {
    expect(globMatches('wfrn:wf:*', 'wfrn:wf:compute:global:123456789012:vm/vm-1')).toBe(true)
    expect(globMatches(VMS, 'wfrn:wf:compute:global:123456789012:vm/vm-1')).toBe(true)
    expect(globMatches('compute:re?tart', 'compute:restart')).toBe(true)
    expect(globMatches('compute:*', 'data:get')).toBe(false)
    // a list check over vm/* is covered by a statement on vm/*, not by one on vm/web-*
    expect(globMatches(VMS, sentinel('wfrn:wf:compute:global:123456789012:vm/*'))).toBe(true)
    expect(globMatches('wfrn:wf:compute:*:123456789012:vm/web-*', sentinel('wfrn:wf:compute:global:123456789012:vm/*'))).toBe(false)
  })
})

describe('the action catalog', () => {
  it('gives every type’s service the read verbs and its workflows, beside the console’s own services', () => {
    expect(catalog.compute).toEqual(['create', 'delete', 'get', 'history', 'list', 'observe', 'patch', 'restart'])
    expect(catalog.iam).toContain('create')
    expect(knowsAction(catalog, 'compute:restart')).toBe(true)
    expect(knowsAction(catalog, 'compute:*')).toBe(true)
    expect(knowsAction(catalog, '*:delete')).toBe(true)
    expect(knowsAction(catalog, 'compute:resize')).toBe(false)
    expect(knowsAction(catalog, 'storage:get')).toBe(false)
  })
})

describe('the checks on a document', () => {
  it('names each problem by the field the identity service names', () => {
    const problems = policyProblems({ name: 'x', statements: [{ sid: 'a', effect: 'allow', actions: [], resources: ['nope'] }, { sid: 'a', effect: 'deny', actions: ['compute:resize'], notActions: ['compute:get'], resources: [VMS], conditions: { 'tag.env': { deep: true } } }] }, catalog)
    expect(problems.map((p) => [p.field, p.message])).toEqual([
      ['name', 'name matches ^[a-z0-9-]{2,64}$'],
      ['statements[0].actions', 'at least one action is required'],
      ['statements[0].resources[0]', "'nope' is not a wfrn pattern"],
      ['statements[1].sid', "sid 'a' is used twice"],
      ['statements[1].actions[0]', "'compute:resize' names no known service or verb"],
      ['statements[1].notActions', 'notActions is for allow statements'],
      ['statements[1].conditions.tag.env', 'a condition value is a string, number, boolean or a list of them'],
    ])
    expect(policyProblems({ name: 'prod-restarts', statements: [{ effect: 'allow', actions: ['compute:restart'], resources: [VMS], conditions: { 'tag.environment': 'prod' } }] }, catalog)).toEqual([])
  })

  it('checks a guardrail’s accounts: every one, or ids that exist', () => {
    expect(guardrailProblems({ name: 'g1', statements: [{ effect: 'deny', actions: ['*:delete'], resources: ['wfrn:wf:*'] }], accounts: ['*', '123456789012', '42', '999999999999'] }, ['123456789012']).map((p) => p.field)).toEqual(['accounts[0]', 'accounts[2]', 'accounts[3]'])
    expect(guardrailProblems({ accounts: ['123456789012'] }, ['123456789012'], undefined, true)).toEqual([])
  })
})

describe('wfrn patterns', () => {
  it('builds one from the helper’s parts and reads it back', () => {
    const parts = { partition: 'wf', service: 'compute', region: '*', account: '123456789012', type: 'vm', id: '*' }
    expect(patternOf(parts)).toBe(VMS)
    expect(partsOfPattern(VMS)).toEqual(parts)
    expect(patternOf({ ...parts, type: '*' })).toBe('wfrn:wf:compute:*:123456789012:*')
    expect(patternProblem(VMS)).toBeNull()
    expect(patternProblem('wfrn:wf:Compute:*')).toBe('a wfrn is all lowercase')
  })

  it('says a pattern and a statement in words', () => {
    expect(patternWords(VMS, () => 'virtual machine')).toBe('Every virtual machine, account 123456789012, any region')
    expect(patternWords('wfrn:wf:*')).toBe('wfrn:wf:*')
    expect(statementWords({ effect: 'allow', actions: ['compute:restart'], resources: [VMS], conditions: { 'tag.environment': 'prod' } }, () => 'virtual machine')).toBe('Allows compute:restart on every virtual machine, account 123456789012, any region, when the resource’s tag environment is prod.')
  })
})

describe('the JSON view', () => {
  const doc: PolicyDoc = { name: 'prod-restarts', description: '', statements: [{ sid: 'restart-prod', effect: 'allow', actions: ['compute:restart', 'compute:resize'], resources: [VMS] }] }

  it('writes the document and reads it back, knowing the line of every value', () => {
    const text = policyJson(doc)
    const read = readPolicyJson(text)
    expect(read.doc).toEqual(doc)
    const problems = policyProblems(read.doc!, catalog)
    expect(problems.map((p) => p.field)).toEqual(['statements[0].actions[1]'])
    expect(text.split('\n')[lineOf(read.spots, problems[0].field)! - 1]).toContain('compute:resize')
    expect(inputOf(read.doc!)).toEqual({ name: 'prod-restarts', statements: doc.statements })
  })

  it('says where the text stops being JSON, and which values have the wrong shape', () => {
    const broken = readPolicyJson('{\n  "name": "x",\n  "statements": [\n    { "effect": "allow", }\n  ]\n}')
    expect(broken.doc).toBeUndefined()
    expect(broken.syntax).toMatchObject({ line: 4, message: 'a comma is not allowed before }' })
    const shaped = readPolicyJson('{ "name": "x", "statements": [ { "effect": "allow", "actions": "compute:get", "resources": [], "Effect": 1 } ] }')
    expect(shaped.problems.map((p) => p.field)).toEqual(['statements[0].Effect', 'statements[0].actions'])
    expect(readJson('[1, 2').error?.message).toBe('a comma or ] is expected')
  })
})

describe('the simulator’s words and checks', () => {
  it('makes every action on every resource a check, with the resource’s tags', () => {
    expect(checksOf(['compute:restart', 'compute:delete'], [{ wfrn: 'w1', tags: { environment: 'prod' } }, { wfrn: 'w2' }])).toEqual([
      { action: 'compute:restart', resource: 'w1', context: { tags: { environment: 'prod' } } },
      { action: 'compute:delete', resource: 'w1', context: { tags: { environment: 'prod' } } },
      { action: 'compute:restart', resource: 'w2' },
      { action: 'compute:delete', resource: 'w2' },
    ])
    expect([scopeLabel({ allowed: false, scope: 'guardrail' }), scopeLabel({ allowed: false, scope: 'boundary' }), scopeLabel({ allowed: true, scope: 'policy' }), scopeLabel({ allowed: false })]).toEqual(['Guardrail', 'Permission boundary', 'Policy', 'No statement'])
    const roles = [{ roleId: 'r1', name: 'a', policies: ['p'], version: 1 }, { roleId: 'r2', name: 'b', boundary: 'p', version: 1 }]
    expect(holdersOf('p', roles)).toEqual({ attached: [roles[0]], bounding: [roles[1]] })
  })

  it('says in words what a boundary or a guardrail refused', () => {
    const base = { layer: 'console-iam' as const, action: 'compute:restart', resource: VMS, requestable: false }
    expect(decidedBy({ ...base, refusedBy: 'boundary', policyId: 'managed-viewer', role: 'restarter' })).toBe("It is outside the permission boundary managed-viewer of the role restarter, so the role's allow does not count.")
    expect(decidedBy({ ...base, refusedBy: 'guardrail', guardrailId: 'gr_1', statement: 'freeze' })).toMatch(/^The organization's guardrail gr_1 denies it \(statement freeze\)\./)
    expect(decidedBy({ ...base, refusedBy: 'guardrail' })).toBe("It is outside the organization's guardrails, and no role in the account can allow it.")
    expect(decidedBy({ ...base, refusedBy: 'policy', reason: 'Only an organization admin changes the organization’s guardrails' })).toBe('Only an organization admin changes the organization’s guardrails.')
    expect(decidedBy({ ...base, requestable: true, refusedBy: 'policy', reason: 'whatever' })).toMatch(/^No statement of your roles allows it/)
  })
})
