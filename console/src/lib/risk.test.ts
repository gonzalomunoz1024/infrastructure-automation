import { describe, expect, it } from 'vitest'
import { costDeltaOf, failedRecentlyOf, isProduction, levelOf, riskKindOf, riskOf } from './risk'
import type { ActivityEntry } from './types'

const NOW = Date.parse('2026-10-23T12:00:00Z')
const prod = { attributes: { environment: 'prod' } }
const dev = { attributes: { environment: 'dev' } }

describe('the change risk score', () => {
  it('reads a dev update as low, with its one reason', () => {
    const r = riskOf({ kind: 'update', targets: [dev] })
    expect(r).toEqual({ score: 10, level: 'low', needsApprover: false, reasons: [{ id: 'change', points: 10, text: 'It changes a resource that exists' }] })
  })

  it('makes a production delete high, so it needs an approver', () => {
    const r = riskOf({ kind: 'delete', targets: [prod] })
    expect(r.score).toBe(60)
    expect(r.level).toBe('high')
    expect(r.needsApprover).toBe(true)
    expect(r.reasons.map((x) => x.id)).toEqual(['destructive', 'production'])
  })

  it('reads a dev delete as medium', () => {
    expect(riskOf({ kind: 'delete', targets: [dev] }).level).toBe('medium')
  })

  it('counts dependents by band, and those in other accounts once more', () => {
    const at = (total: number, other = 0) => riskOf({ kind: 'update', targets: [dev], radius: { total, byAccount: [{ accountId: 'a', count: total - other, current: true }, ...(other ? [{ accountId: 'b', count: other }] : [])] } })
    expect(at(1).score).toBe(20)
    expect(at(5).score).toBe(30)
    expect(at(20).score).toBe(40)
    expect(at(4, 2).score).toBe(30)
    expect(at(4, 2).reasons.find((r) => r.id === 'accounts')?.text).toBe("2 of them are in another team's account")
  })

  it('adds the size of a bulk run, the data class, history, drift and cost', () => {
    const many = Array.from({ length: 12 }, () => ({ ...dev, tags: { 'data-class': 'confidential' } }))
    const r = riskOf({ kind: 'change', targets: many, failedRecently: 3, monthlyCostDelta: 600 })
    expect(r.reasons.map((x) => [x.id, x.points])).toEqual([
      ['history', 15],
      ['change', 10],
      ['many', 10],
      ['cost', 10],
      ['data', 5],
    ])
    expect(r.score).toBe(50)
    expect(riskOf({ kind: 'change', targets: [{ ...dev, drift: true, tags: { 'data-class': 'restricted' } }] }).score).toBe(25)
  })

  it('says how many rows of a bulk run are in production', () => {
    const r = riskOf({ kind: 'change', targets: [prod, dev, prod] })
    expect(r.reasons.find((x) => x.id === 'production')?.text).toBe('2 of them run in production')
  })

  it('caps the score at 100', () => {
    const r = riskOf({ kind: 'delete', targets: Array.from({ length: 60 }, () => ({ ...prod, drift: true, tags: { 'data-class': 'restricted' } })), radius: { total: 30, byAccount: [{ accountId: 'b', count: 30 }] }, failedRecently: 4, monthlyCostDelta: 900 })
    expect(r.score).toBe(100)
  })

  it('bands the levels at 30 and 60', () => {
    expect([levelOf(29), levelOf(30), levelOf(59), levelOf(60)]).toEqual(['low', 'medium', 'medium', 'high'])
  })
})

describe('what the score is made of', () => {
  it('finds production in the environment attribute, the stage or the tag', () => {
    expect(isProduction({ attributes: { environment: 'prod' } })).toBe(true)
    expect(isProduction({ attributes: { stage: 'production' } })).toBe(true)
    expect(isProduction({ tags: { environment: 'PROD' } })).toBe(true)
    expect(isProduction({ attributes: { environment: 'preprod' } })).toBe(false)
  })

  it('counts the requests that went wrong in the last 30 days, each once', () => {
    const job = (jobId: string, status: string, endedAt: string): ActivityEntry => ({ at: endedAt, kind: 'job', job: { jobId, workflow: 'w', status, endedAt } })
    const entries = [job('a', 'failed', '2026-10-20T00:00:00Z'), job('a', 'failed', '2026-10-20T00:00:00Z'), job('b', 'rolled-back', '2026-10-01T00:00:00Z'), job('c', 'succeeded', '2026-10-22T00:00:00Z'), job('d', 'failed', '2026-09-01T00:00:00Z'), { at: '2026-10-22T00:00:00Z', kind: 'event' }] as ActivityEntry[]
    expect(failedRecentlyOf(entries, NOW)).toBe(2)
    expect(failedRecentlyOf(undefined, NOW)).toBe(0)
  })

  it('reads what a preview adds to the bill', () => {
    expect(costDeltaOf({ cost: { monthly: 154, currency: 'USD', detail: '8 vCPU; was 38.50 USD' } }, 'update')).toBeCloseTo(115.5)
    expect(costDeltaOf({ cost: { monthly: 154, currency: 'USD', detail: '8 vCPU' } }, 'update')).toBeUndefined()
    expect(costDeltaOf({ cost: { monthly: 520, currency: 'USD', detail: 'M60' } }, 'create')).toBe(520)
    expect(costDeltaOf({}, 'create')).toBeUndefined()
  })

  it('names the kind of a verb', () => {
    expect(['create', 'update', 'delete', 'restart'].map(riskKindOf)).toEqual(['create', 'update', 'delete', 'change'])
  })
})
