import { renderToString } from 'react-dom/server'
import { describe, expect, it } from 'vitest'
import type { WorkflowDocument } from '@iaf/workflow-checks'
import type { ProviderAlert } from '../../lib/types'
import { EMPTY_REGISTRY } from '../model/registry'
import { DesignerContext, DesignerStore } from '../model/store'
import { againstSlo, alertsFor, ProviderTab } from './ProviderTab'
import type { ProviderStepInfo } from './providerStep'
import { loadingInfo, newDraftInfo, publishedInfo, vsphereDetail } from './testProviders'

/*
 * The Provider tab of a provider step brings the provider page into the designer: the health band
 * and its reasons, the success rate and p95 against the SLO, the step's action's own band, the
 * alerts (the action's first), the release in flight, the open change requests, Edit SLO and
 * Refresh spec, and links to the provider page and its audit. A draft has none of these yet.
 */

const doc: WorkflowDocument = { apiVersion: 'iaf.platform/v1', kind: 'Workflow', metadata: { name: 'vm-check', owner: 'team-compute', version: '1.0.0' }, workflow: { do: [] } } as WorkflowDocument
const text = (html: string) => html.replace(/<!--.*?-->/g, '')

function render(info: ProviderStepInfo, readOnly = false): string {
  const store = new DesignerStore({ name: null, doc, registry: EMPTY_REGISTRY })
  return text(renderToString(
    <DesignerContext.Provider value={store}>
      <ProviderTab info={info} stepId="check" readOnly={readOnly} />
    </DesignerContext.Provider>,
  ))
}

const alert = (id: string, action: string, at: string): ProviderAlert => ({ id, at, provider: 'vsphere', action, message: `${action} alert`, severity: 'warning' })

describe('the Provider tab of a published provider', () => {
  it('shows the health band and its reasons, the figures against the SLO and the step’s action’s band', () => {
    const html = render(publishedInfo())
    expect(html).toContain('VMware vSphere · 1.3.0')
    expect(html).toContain('Degraded')
    expect(html).toContain('p95 1.35 s is above the SLO of 800 ms')
    expect(html).toContain('97.2%')
    expect(html).toContain('SLO 99.5% · missed')
    expect(html).toContain('1240 calls')
    expect(html).toContain('alerts go to #compute-alerts')
    expect(html).toContain('This step’s action · vm.get')
    expect(html).toContain('Failing')
    expect(html).toContain('812 calls in 14 days')
  })

  it('lists only the open change requests, and links the provider page and its audit', () => {
    const html = render(publishedInfo())
    expect(html).toContain('href="#/change-requests/cr_41"')
    expect(html).not.toContain('cr_30')
    expect(html).toContain('href="#/providers/vsphere"')
    expect(html).toContain('href="#/audit?tab=platform&amp;aggregate=provider"')
  })

  it('offers Edit SLO, Refresh spec for an OpenAPI provider, and nothing to edit when read-only', () => {
    const html = render(publishedInfo())
    expect(html).toContain('Edit SLO')
    expect(html).toContain('Refresh spec')
    expect(html).toContain('Open provider')
    expect(render(publishedInfo('vm.get', undefined, vsphereDetail({ type: 'http' })))).not.toContain('Refresh spec')
    const readOnly = render(publishedInfo(), true)
    expect(readOnly).not.toContain('Edit SLO')
    expect(readOnly).not.toContain('Open provider')
  })

  it('says so when the action is not the provider’s, or the provider has not answered', () => {
    expect(render(publishedInfo('vm.gone'))).toContain('has no action vm.gone')
    expect(render(loadingInfo(new Error('timeout')))).toContain('Could not load vsphere: timeout')
    expect(render(loadingInfo())).not.toContain('Degraded')
  })

  it('judges nothing without calls or an SLO', () => {
    const quiet = vsphereDetail({ health: { status: 'unknown', successRate: 0, p50Ms: 0, p95Ms: 0, calls: 0, errorTrend: [], reasons: [] }, slo: undefined })
    expect(againstSlo(quiet)).toEqual({ rate: '—', rateMet: null, p95: '—', p95Met: null })
    expect(againstSlo(vsphereDetail())).toMatchObject({ rate: '97.2%', rateMet: false, p95Met: false })
    expect(againstSlo(vsphereDetail({ health: { ...vsphereDetail().health, successRate: 99.9, p95Ms: 400 } }))).toMatchObject({ rateMet: true, p95Met: true })
    expect(render(publishedInfo('vm.get', undefined, quiet))).toContain('No SLO yet')
  })
})

describe('the alerts the tab shows', () => {
  it('puts the step’s action’s first, then the newest, five at most', () => {
    const alerts = [alert('a', 'vm.create', '2026-10-18T09:00:00Z'), alert('b', 'vm.get', '2026-10-17T09:00:00Z'), alert('c', 'vm.list', '2026-10-18T10:00:00Z'), alert('d', 'vm.get', '2026-10-18T08:00:00Z')]
    expect(alertsFor(alerts, 'vm.get').map((a) => a.id)).toEqual(['d', 'b', 'c', 'a'])
    expect(alertsFor(alerts, 'vm.get', 2).map((a) => a.id)).toEqual(['d', 'b'])
    expect(alertsFor([], 'vm.get')).toEqual([])
  })
})

describe('the Provider tab of a draft', () => {
  it('says the provider is not published yet and opens the draft', () => {
    const html = render(newDraftInfo())
    expect(html).toContain('Billing is a draft')
    expect(html).toContain('once its change request merges')
    expect(html).toContain('Open provider')
    expect(html).not.toContain('Edit SLO')
  })
})
