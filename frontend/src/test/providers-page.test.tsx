import { describe, expect, it } from 'vitest'
import { renderToString } from 'react-dom/server'
import { Table } from '@iaf/ui'
import { AUDIT_COLUMNS, LIST_COLUMNS, PERFORMANCE_COLUMNS, emptyProviders, errorTotals, providerEvents, sloVerdict, tabOf } from '../pages/ProvidersPage'
import type { EvidenceEntry, ProviderSummary } from '../lib/types'

/*
 * The providers admin view: the tab the address names, a provider against its SLO, the 14-day
 * error trend, the provider events of the platform stream and what an empty list tells someone
 * who is not an admin (the backend lists them only their teams' providers and the unowned ones).
 */

const text = (html: string) => html.replace(/<!--.*?-->/g, '')

const VSPHERE: ProviderSummary = {
  name: 'vsphere',
  title: 'VMware vSphere',
  type: 'openapi',
  owner: 'team-compute',
  version: '1.3.0',
  environments: ['dev', 'prod'],
  actionCount: 3,
  health: { status: 'healthy', successRate: 99.6, p50Ms: 190, p95Ms: 720, calls: 15_480, errorTrend: [1, 0, 2, 1, 0, 0, 1, 0, 0, 2, 1, 0, 1, 0], reasons: [] },
  slo: { successRate: 99.5, p95Ms: 800 },
}
const BACKUP: ProviderSummary = {
  ...VSPHERE,
  name: 'backup',
  title: 'Backup service',
  type: 'http',
  owner: 'team-platform',
  version: '1.0.0',
  actionCount: 2,
  health: { status: 'degraded', successRate: 97.2, p50Ms: 420, p95Ms: 1900, calls: 2_210, errorTrend: [0, 1, 0, 2, 1, 3, 2, 3, 5, 8, 6, 9, 11, 12], reasons: ['p95 1.9s is over the 1.2s SLO'] },
  slo: { successRate: 99.0, p95Ms: 1200 },
}
const SLOW: ProviderSummary = { ...VSPHERE, name: 'dns', title: 'DNS', health: { ...VSPHERE.health, p95Ms: 950 } }
const LEGACY: ProviderSummary = { ...VSPHERE, name: 'cmdb', title: 'Legacy CMDB', slo: undefined, health: { ...VSPHERE.health, errorTrend: [] } }

const entry = (seq: number, data: Record<string, unknown>, actor = 'gonzalo'): EvidenceEntry => ({ seq, at: '2026-10-18T09:00:00Z', kind: 'decision', actor, data, hash: 'ab'.repeat(32), previous: '00'.repeat(32) })

describe('the providers tabs', () => {
  it('reads the view from the address and falls back to the list', () => {
    expect(tabOf('performance')).toBe('performance')
    expect(tabOf('audit')).toBe('audit')
    expect(tabOf(undefined)).toBe('providers')
    expect(tabOf('providers')).toBe('providers')
    expect(tabOf('anything-else')).toBe('providers')
  })
})

describe('sloVerdict', () => {
  it('judges the success rate and the p95 against the SLO', () => {
    expect(sloVerdict(VSPHERE)).toEqual({ success: true, p95: true, within: true })
    expect(sloVerdict(BACKUP)).toEqual({ success: false, p95: false, within: false })
    expect(sloVerdict(SLOW)).toEqual({ success: true, p95: false, within: false })
  })

  it('counts a value on the objective as within it', () => {
    expect(sloVerdict({ health: { ...VSPHERE.health, successRate: 99.5, p95Ms: 800 }, slo: VSPHERE.slo })).toEqual({ success: true, p95: true, within: true })
  })

  it('has no verdict for a provider without an SLO', () => {
    expect(sloVerdict(LEGACY)).toEqual({ success: null, p95: null, within: null })
  })
})

describe('errorTotals', () => {
  it('adds up the 14 daily counts and names the last day', () => {
    expect(errorTotals(BACKUP.health.errorTrend)).toEqual({ total: 63, last: 12, days: 14 })
    expect(errorTotals(VSPHERE.health.errorTrend)).toEqual({ total: 9, last: 0, days: 14 })
  })

  it('is empty without a trend and skips what is not a number', () => {
    expect(errorTotals(undefined)).toEqual({ total: 0, last: 0, days: 0 })
    expect(errorTotals([])).toEqual({ total: 0, last: 0, days: 0 })
    expect(errorTotals([2, Number.NaN, 3])).toEqual({ total: 5, last: 3, days: 2 })
  })
})

describe('providerEvents', () => {
  it('keeps the provider events of the platform stream', () => {
    const entries = [
      entry(4, { event: 'provider.change-requested', aggregate: 'provider', aggregateId: 'vsphere', version: '2.1.1', reason: 'slo' }, 'ana'),
      entry(5, { event: 'workflow.published', aggregate: 'workflow', aggregateId: 'vm-restart', name: 'vm-restart', version: '1.2.0' }),
      entry(7, { event: 'provider.published', aggregate: 'provider', aggregateId: 'vsphere', version: '1.3.0' }, 'maria'),
      entry(8, {}),
    ]
    expect(providerEvents(entries).map((e) => e.seq)).toEqual([4, 7])
  })
})

describe('emptyProviders', () => {
  it('tells someone outside the admins which providers they see', () => {
    const empty = emptyProviders({ admin: false, teams: ['team-compute', 'team-network'] }, false)
    expect(empty.title).toBe('No providers of your teams yet')
    expect(empty.message).toContain('You see the providers your teams own (team-compute, team-network) and the ones no team owns; an admin sees every provider.')
    expect(emptyProviders({ admin: false, teams: [] }, false).message).toContain('your teams own (none yet)')
  })

  it('invites an admin to add the first one, and a filter to be loosened', () => {
    expect(emptyProviders({ admin: true, teams: [] }, false).title).toBe('No providers yet')
    expect(emptyProviders(undefined, false).title).toBe('No providers yet')
    expect(emptyProviders({ admin: false, teams: ['team-compute'] }, true)).toEqual({ title: 'No providers match', message: 'Loosen the filters to see more.' })
  })
})

describe('the providers tables', () => {
  it('lists each provider with p50 beside p95 and its SLO', () => {
    const html = text(renderToString(<Table columns={LIST_COLUMNS} rows={[VSPHERE, BACKUP, LEGACY]} rowKey={(p) => p.name} caption="Providers" />))
    expect(html).toContain('>p50<')
    expect(html).toContain('>p95<')
    expect(html).toContain('190ms')
    expect(html).toContain('420ms')
    expect(html).toContain('99.5% · 800ms')
    expect(html).toContain('>none<')
  })

  it('shows performance against the SLO with the 14-day error trend', () => {
    const html = text(renderToString(<Table columns={PERFORMANCE_COLUMNS} rows={[VSPHERE, BACKUP, SLOW, LEGACY]} rowKey={(p) => p.name} caption="Provider performance" />))
    expect(html).toContain('15,480')
    expect(html).toContain('99.60%')
    expect(html).toContain('SLO ≥ 99.5%')
    expect(html).toContain('SLO ≤ 800ms')
    expect(html).toContain('SLO ≤ 1.2s')
    expect(html).toContain('>within<')
    expect(html).toContain('>both missed<')
    expect(html).toContain('>p95 missed<')
    expect(html).toContain('no SLO')
    expect(html).toContain('aria-label="Errors per day, last 14 days"')
    expect(html).toContain('title="12 on the last day"')
    expect(html).toContain('>63<')
  })

  it('lists a provider event with its provider and who made it', () => {
    const html = text(renderToString(<Table columns={AUDIT_COLUMNS} rows={[entry(7, { event: 'provider.published', aggregate: 'provider', aggregateId: 'vsphere', version: '1.3.0' }, 'maria')]} rowKey={(e) => String(e.seq)} caption="Provider events" />))
    expect(html).toContain('provider.published')
    expect(html).toContain('vsphere 1.3.0 published')
    expect(html).toContain('href="#/providers/vsphere"')
    expect(html).toContain('maria')
  })
})
