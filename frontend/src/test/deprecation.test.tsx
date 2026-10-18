import { renderToString } from 'react-dom/server'
import { describe, expect, it } from 'vitest'
import { DeprecatedTag, DeprecationNotice, dateOf, replacementEntry, sunsetState } from '../components/Deprecation'

/*
 * How a deprecated operation shows: the dates as written, where the sunset stands against the
 * clock, the replacement linked to the catalog entry that answers it, and the notice's wording.
 */

const text = (html: string) => html.replace(/<!--.*?-->/g, '')
const NOW = Date.parse('2026-10-18T12:00:00Z')
const DEPRECATED = { since: '2026-10-01', sunset: '2027-01-31', replacement: '/api/v2/vms/{vmId}:restart', message: 'Use v2, which restarts through the maintenance window.' }
const CATALOG = [
  { workflow: 'vm-restart', title: 'VM restart', path: '/api/v1/vms/{vmId}:restart' },
  { workflow: 'vm-restart-v2', title: 'VM restart (v2)', path: '/api/v2/vms/{vmId}:restart' },
]

describe('dateOf', () => {
  it('keeps the date part of a date or a date-time, and a dash for nothing', () => {
    expect(dateOf('2026-10-01')).toBe('2026-10-01')
    expect(dateOf('2026-10-01T09:30:00Z')).toBe('2026-10-01')
    expect(dateOf(undefined)).toBe('—')
    expect(dateOf('soon')).toBe('soon')
  })
})

describe('sunsetState', () => {
  it('counts the whole days left, within 30 days being soon', () => {
    expect(sunsetState('2027-01-31', NOW)).toEqual({ state: 'later', days: 105 })
    expect(sunsetState('2026-11-01', NOW)).toEqual({ state: 'soon', days: 14 })
    expect(sunsetState('2026-10-18', NOW)).toEqual({ state: 'soon', days: 0 })
  })
  it('says when the sunset has passed, and nothing for a date it cannot read', () => {
    expect(sunsetState('2026-10-01', NOW)).toEqual({ state: 'passed', days: -17 })
    expect(sunsetState('2026-10-01T00:00:00Z', NOW)).toEqual({ state: 'passed', days: -17 })
    expect(sunsetState(undefined, NOW)).toEqual({ state: 'unknown', days: null })
    expect(sunsetState('whenever', NOW)).toEqual({ state: 'unknown', days: null })
  })
})

describe('replacementEntry', () => {
  it('finds the catalog entry that answers the replacement path, with or without an origin', () => {
    expect(replacementEntry('/api/v2/vms/{vmId}:restart', CATALOG)?.workflow).toBe('vm-restart-v2')
    expect(replacementEntry('https://api.example.com/api/v2/vms/{vmId}:restart', CATALOG)?.workflow).toBe('vm-restart-v2')
    expect(replacementEntry('/api/v3/vms/{vmId}:restart', CATALOG)).toBeUndefined()
    expect(replacementEntry(undefined, CATALOG)).toBeUndefined()
    expect(replacementEntry('/api/v2/vms/{vmId}:restart', undefined)).toBeUndefined()
  })
})

describe('DeprecatedTag', () => {
  it('is a warning capsule with the dates and the successor on hover', () => {
    const html = text(renderToString(<DeprecatedTag deprecated={DEPRECATED} />))
    expect(html).toContain('deprecated')
    expect(html).toContain('title="Deprecated since 2026-10-01; sunset 2027-01-31; use /api/v2/vms/{vmId}:restart"')
  })
})

describe('DeprecationNotice', () => {
  it('says since when, until when and links the successor in the catalog', () => {
    const html = text(renderToString(<DeprecationNotice deprecated={DEPRECATED} catalog={CATALOG} now={NOW} />))
    expect(html).toContain('aria-label="Deprecated operation"')
    expect(html).toContain('Deprecated')
    expect(html).not.toContain('Past its sunset')
    expect(html).toContain('2026-10-01')
    expect(html).toContain('2027-01-31')
    expect(html).toContain('in 105 days')
    expect(html).toContain('href="#/catalog/vm-restart-v2"')
    expect(html).toContain('VM restart (v2)')
    expect(html).toContain('Use v2, which restarts through the maintenance window.')
    expect(html).toContain('Link to the successor')
  })
  it('opens an outside successor in a new tab and shows any other as a path', () => {
    const outside = text(renderToString(<DeprecationNotice deprecated={{ ...DEPRECATED, replacement: 'https://api.example.com/v2/vms' }} catalog={CATALOG} now={NOW} />))
    expect(outside).toContain('href="https://api.example.com/v2/vms"')
    expect(outside).toContain('target="_blank"')
    const bare = text(renderToString(<DeprecationNotice deprecated={{ ...DEPRECATED, replacement: 'vm-info-v3' }} now={NOW} />))
    expect(bare).toContain('vm-info-v3')
    expect(bare).not.toContain('href="#/catalog/')
  })
  it('turns critical once the sunset has passed, and leaves out what is not there', () => {
    const html = text(renderToString(<DeprecationNotice deprecated={{ since: '2026-09-01', sunset: '2026-10-01' }} now={NOW} />))
    expect(html).toContain('Past its sunset')
    expect(html).toContain('17 days ago')
    expect(html).not.toContain('Use instead')
    expect(html).not.toContain('Link to the successor')
  })
})
