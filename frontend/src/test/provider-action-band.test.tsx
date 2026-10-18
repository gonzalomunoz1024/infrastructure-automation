import { renderToString } from 'react-dom/server'
import { describe, expect, it } from 'vitest'
import { ActionBand } from '../pages/ProviderPage'

/*
 * docs/api.md "Providers": each action's band over the last 24 hours, judged against the SLO
 * the action is judged by (its own, else the provider's); the page shows that band with the
 * provider's HealthBand, and the SLO when it is the action's own.
 */

const text = (html: string) => html.replace(/<!--.*?-->/g, '').replace(/<[^>]+>/g, ' ').replace(/\s+/g, ' ').trim()

describe('an action’s band', () => {
  it('shows the band of an action judged against its own SLO, and that SLO', () => {
    const html = renderToString(<ActionBand action={{ slo: { successRate: 98.5, p95Ms: 12_000 } }} stats={{ band: 'healthy' }} />)
    expect(text(html)).toBe('Healthy own SLO 98.5% · p95 12s')
    expect(html).toContain('text-healthy')
  })

  it('shows only the band of an action that takes the provider’s SLO', () => {
    expect(text(renderToString(<ActionBand action={{}} stats={{ band: 'failing' }} />))).toBe('Failing')
    expect(text(renderToString(<ActionBand action={{ slo: null }} stats={{ band: 'degraded' }} />))).toBe('Degraded')
  })

  it('is unknown for an action without calls', () => {
    expect(text(renderToString(<ActionBand action={{}} />))).toBe('Unknown')
  })
})
