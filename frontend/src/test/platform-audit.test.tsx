import { describe, expect, it } from 'vitest'
import { renderToString } from 'react-dom/server'
import { PlatformEntryDetails, PlatformSubject, PlatformVerification, platformEvent, platformSummary, subjectHref } from '../components/PlatformAudit'
import type { EvidenceEntry } from '../lib/types'

/* The platform audit stream: how each entry reads, where its subject lives, and the chain's verdict. */

const text = (html: string) => html.replace(/<!--.*?-->/g, '')

const entry = (data: Record<string, unknown>, actor = 'gonzalo'): EvidenceEntry => ({ seq: 1, at: '2026-10-18T09:00:00Z', kind: 'decision', actor, data, hash: 'ab'.repeat(32), previous: '00'.repeat(32) })

describe('platformSummary', () => {
  it('names the subject, what happened and the version the event carries', () => {
    expect(platformSummary(entry({ event: 'workflow.published', aggregate: 'workflow', aggregateId: 'vm-restart', name: 'vm-restart', version: '1.2.0' }))).toBe('vm-restart 1.2.0 published')
    expect(platformSummary(entry({ event: 'change-request.opened', aggregate: 'change-request', aggregateId: 'cr-41', name: 'vm-restart', version: '1.2.0', strategy: 'canary' }))).toBe('change request vm-restart 1.2.0 opened (canary)')
    expect(platformSummary(entry({ event: 'provider.change-requested', aggregate: 'provider', aggregateId: 'vsphere', version: '2.1.1', reason: 'slo' }))).toBe('vsphere 2.1.1 change requested · slo')
    expect(platformSummary(entry({ event: 'release.rolled-back', aggregate: 'release', aggregateId: 'rel-1', from: '1.1.0', to: '1.2.0' }))).toBe('release rel-1 1.1.0 → 1.2.0 rolled back')
    expect(platformSummary(entry({ event: 'credential.stored', aggregate: 'credential', aggregateId: 'vsphere/token', name: 'vsphere/token', type: 'token' }))).toBe('credential vsphere/token stored (token)')
    expect(platformSummary(entry({ event: 'service-account.created', aggregate: 'service-account', aggregateId: 'sa-ci', name: 'ci-deployer' }))).toBe('service account ci-deployer created')
    expect(platformSummary(entry({ event: 'workflow.draft-saved', aggregate: 'workflow', aggregateId: 'create-vm', name: 'create-vm' }))).toBe('create-vm draft saved')
  })

  it('falls back to the kind and the aggregate when the data says nothing', () => {
    expect(platformEvent(entry({}))).toEqual({ event: 'decision', aggregate: undefined, aggregateId: undefined })
    expect(platformSummary(entry({}))).toBe('decision')
  })
})

describe('subjectHref', () => {
  it('links each aggregate to its page in the shell', () => {
    expect(subjectHref('workflow', 'vm-restart')).toBe('/workflows/vm-restart')
    expect(subjectHref('provider', 'vsphere')).toBe('/providers/vsphere')
    expect(subjectHref('change-request', 'cr-41')).toBe('/change-requests/cr-41')
    expect(subjectHref('release', 'rel-1')).toBe('/releases/rel-1')
    expect(subjectHref('resource', 'vm-1042')).toBe('/resources/vm-1042')
    expect(subjectHref('credential', 'vsphere/token')).toBe('/credentials')
    expect(subjectHref('service-account', 'sa-ci')).toBe('/settings')
    expect(subjectHref('audit', 'platform')).toBeNull()
    expect(subjectHref('workflow', undefined)).toBeNull()
  })

  it('renders the subject as a link, or plain when the shell has no page for it', () => {
    const linked = text(renderToString(<PlatformSubject entry={entry({ event: 'provider.published', aggregate: 'provider', aggregateId: 'vsphere' })} />))
    expect(linked).toContain('href="#/providers/vsphere"')
    expect(linked).toContain('provider')
    const plain = text(renderToString(<PlatformSubject entry={entry({ event: 'audit.sealed', aggregate: 'audit', aggregateId: 'platform' })} />))
    expect(plain).not.toContain('href=')
    expect(plain).toContain('platform')
  })
})

describe('PlatformVerification', () => {
  it('says the chain is intact and how the checkpoint fared', () => {
    const html = text(renderToString(<PlatformVerification verification={{ valid: true, entries: 6, seal: 'verified' }} total={6} />))
    expect(html).toContain('Chain intact')
    expect(html).toContain('6 of 6 entries verify against their hashes')
    expect(html).toContain('the checkpoint signature verifies')
  })

  it('says where the chain broke', () => {
    const html = text(renderToString(<PlatformVerification verification={{ valid: false, entries: 6, brokenAt: 4, seal: 'mismatch' }} total={6} />))
    expect(html).toContain('Chain broken at entry 4')
    expect(html).toContain('does not match')
  })

  it('details show the hashes and the data', () => {
    const html = text(renderToString(<PlatformEntryDetails entry={entry({ event: 'workflow.published', name: 'vm-restart', version: '1.2.0', contentHash: 'sha256:1' })} />))
    expect(html).toContain('ab'.repeat(32))
    expect(html).toContain('00'.repeat(32))
    expect(html).toContain('gonzalo')
    expect(html).toContain('sha256:1')
  })
})
