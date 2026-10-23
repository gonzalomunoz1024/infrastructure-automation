import { describe, expect, it } from 'vitest'
import type { Schema } from '@iaf/console-kit'
import type { ConsoleAction, ConsoleType } from '../../lib/descriptors'
import type { BatchPreview } from '../../lib/types'
import { batchBodyOf, bulkVerbsOf, chosenRollout, confirmTextOf, endAtAll, ownInputsOf, plannedRollout, presetWaves, rowInputOf, unrunnableOf, wavesLine } from './bulk'

const action = (verb: string, bulk = false): ConsoleAction => ({ workflow: `${verb}-vm`, title: verb, verb, method: 'POST', bulk, preview: true, destructive: verb === 'delete', input: { type: 'object' } })
const own = { waves: [5, 25, 100], maxParallel: 10, stopIfFailedPercent: 5 }

describe('the verbs a selection runs in bulk', () => {
  it('are the type’s actions the descriptor marks bulk, create aside', () => {
    const type = { actions: [action('create', true), action('restart', true), action('resize'), action('delete', true)] } as ConsoleType
    expect(bulkVerbsOf(type).map((a) => a.verb)).toEqual(['restart', 'delete'])
  })
})

describe('a bulk run’s rollout', () => {
  it('offers the workflow’s own waves, two waves or all at once, always ending with every row', () => {
    expect(presetWaves('own', own.waves)).toEqual([5, 25, 100])
    expect(presetWaves('own', [1, 5, 25])).toEqual([1, 5, 25, 100])
    expect(presetWaves('own', undefined)).toEqual([100])
    expect(presetWaves('two', own.waves)).toEqual([10, 100])
    expect(presetWaves('all', own.waves)).toEqual([100])
    expect(endAtAll([100])).toEqual([100])
  })

  it('names no rollout when the person kept the workflow’s own, and all of it otherwise', () => {
    expect(chosenRollout('own', 10, 5, own)).toBeUndefined()
    expect(chosenRollout('own', 4, 5, own)).toEqual({ waves: [5, 25, 100], maxParallel: 4, stopIfFailedPercent: 5 })
    expect(chosenRollout('all', 10, 5, own)).toEqual({ waves: [100], maxParallel: 10, stopIfFailedPercent: 5 })
  })

  it('shows the waves the BFF will run: 50 rows in the workflow’s own waves go 3, then 10, then 37', () => {
    const planned = plannedRollout(undefined, own, 50)
    expect(planned.waves.map((w) => w.size)).toEqual([3, 10, 37])
    expect(wavesLine(planned.waves)).toBe('3, then 10, then 37 rows')
    expect(planned.words).toBe('3 waves (5%, 25%, then the rest), 10 at a time, stopping when more than 5% fail')
    expect(wavesLine(plannedRollout({ waves: [100] }, own, 1).waves)).toBe('1 row in one wave')
  })
})

describe('the request a bulk run sends', () => {
  it('has a row per resource, the shared input as defaults, and the rollout and reason only when given', () => {
    expect(batchBodyOf('restart', [{ wfrn: 'wfrn:a', input: { environment: 'prod' } }, { wfrn: 'wfrn:b', input: {} }], {}, undefined, '  ')).toEqual({ verb: 'restart', items: [{ wfrn: 'wfrn:a', input: { environment: 'prod' } }, { wfrn: 'wfrn:b', input: {} }] })
    expect(batchBodyOf('patch', [{ wfrn: 'wfrn:a', input: {} }], { window: 'now' }, { waves: [100] }, ' kernel CVE ')).toEqual({ verb: 'patch', items: [{ wfrn: 'wfrn:a', input: {} }], defaults: { window: 'now' }, rollout: { waves: [100] }, reason: 'kernel CVE' })
  })

  it('keeps each row’s own answer to an input every row answers, and shares the rest', () => {
    const schema = { type: 'object', properties: { vmId: { type: 'string', 'x-resource': { type: 'vm' } }, reason: { type: 'string' }, environment: { type: 'string', enum: ['dev', 'test', 'prod'] } } } as Schema
    const rows = [{ attributes: { environment: 'prod', reason: 'old' } }, { attributes: { environment: 'dev' } }]
    expect(ownInputsOf(schema, 'vmId', rows)).toEqual(['environment'])
    expect(ownInputsOf(schema, 'vmId', [...rows, { attributes: { environment: 'staging' } }])).toEqual([])
    expect(rowInputOf(schema, 'vmId', ['environment'], rows[0].attributes)).toEqual({ environment: 'prod' })
  })

  it('asks for the verb and the count typed out before a destructive one', () => {
    expect(confirmTextOf('delete', 12)).toBe('delete 12')
  })
})

describe('the rows that cannot run', () => {
  const preview: BatchPreview = {
    verb: 'delete',
    items: [
      { index: 0, wfrn: 'wfrn:a', name: 'web-01', state: 'active', dependents: 2, named: [], problems: [{ code: 'dependents.exist', field: '/items/0/wfrn', message: '2 resources are built from web-01' }] },
      { index: 1, wfrn: 'wfrn:b', name: 'web-02', state: 'active', dependents: 0, named: [], problems: [] },
      { index: 2, name: undefined, dependents: 0, named: [], problems: [{ code: 'state', field: '/items/2/wfrn', message: 'vm-x is building' }] },
      { index: 3, wfrn: 'wfrn:d', name: 'web-04', dependents: 0, named: [], problems: [{ code: 'required', field: '/items/3/input/reason', message: 'reason is required' }] },
    ],
    affected: { total: 2, direct: 2, byAccount: [], dependents: [], edges: [], depth: 3 },
    policy: { decision: 'deny' },
  }

  it('names each with why, finds a row without a wfrn by its place, and leaves a missing shared field to the form', () => {
    expect(unrunnableOf(preview, ['wfrn:a', 'wfrn:b', 'wfrn:c', 'wfrn:d'])).toEqual([
      { wfrn: 'wfrn:a', name: 'web-01', reasons: ['2 resources are built from web-01'] },
      { wfrn: 'wfrn:c', name: 'wfrn:c', reasons: ['vm-x is building'] },
    ])
    expect(unrunnableOf(undefined, [])).toEqual([])
  })
})
