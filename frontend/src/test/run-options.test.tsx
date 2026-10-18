import { renderToString } from 'react-dom/server'
import { describe, expect, it } from 'vitest'
import { QuotaNotice, quotaMessage } from '../components/RetryAfter'
import { ROUTED, versionOptions } from '../components/RunAdvanced'
import { curlFor } from '../pages/CatalogRunPage'
import type { WorkflowVersion } from '../lib/types'

/*
 * What a run form sends beyond its input: the version it pins (only published and retired
 * versions can be pinned), the curl line with the header, and what a 429 reads as.
 */

const text = (html: string) => html.replace(/<!--.*?-->/g, '')
const VERSIONS: WorkflowVersion[] = [
  { version: '1.2.0', status: 'published', contentHash: 'sha256:12' },
  { version: '1.1.0', status: 'retired', contentHash: 'sha256:11' },
  { version: '1.3.0', status: 'draft', contentHash: 'sha256:13' },
]

describe('versionOptions', () => {
  it('offers the routed choice first, then the published and retired versions as the API orders them; drafts cannot run', () => {
    const options = versionOptions(VERSIONS, '1.2.0')
    expect(options.map((o) => o.value)).toEqual([ROUTED, '1.2.0', '1.1.0'])
    expect(options[0].label).toBe('Routed by the release')
    expect(options[0].hint).toContain('1.2.0')
    expect(options[2].hint).toBe('retired')
  })
  it('has only the routed choice while the versions are not here', () => {
    expect(versionOptions(undefined, '1.2.0')).toHaveLength(1)
  })
})

describe('curlFor', () => {
  const entry = { method: 'POST', path: '/api/v1/vms/{vmId}:restart' }
  it('fills the path from the input and sends the rest as the body', () => {
    expect(curlFor(entry, { vmId: 'vm-1042', reason: 'patch' }, 'https://iaf.example.com')).toBe(`curl -X POST 'https://iaf.example.com/api/v1/vms/vm-1042:restart' \\\n  -H 'Content-Type: application/json' \\\n  -d '{"vmId":"vm-1042","reason":"patch"}'`)
  })
  it('adds the X-Workflow-Version header when the run is pinned, on a GET too', () => {
    expect(curlFor(entry, { vmId: 'vm-1042' }, 'https://iaf.example.com', '1.1.0')).toContain(`'https://iaf.example.com/api/v1/vms/vm-1042:restart' \\\n  -H 'X-Workflow-Version: 1.1.0' \\\n  -H 'Content-Type`)
    expect(curlFor({ method: 'GET', path: '/api/v1/vms/{vmId}' }, { vmId: 'vm-1' }, 'https://iaf.example.com', '1.1.0')).toBe(`curl 'https://iaf.example.com/api/v1/vms/vm-1' \\\n  -H 'X-Workflow-Version: 1.1.0'`)
  })
})

describe('quotaMessage', () => {
  it('is the backend message and when to try again, from Retry-After', () => {
    expect(quotaMessage({ error: 'team-compute is over its quota of 60 jobs a minute', retryAfter: 12 })).toBe('team-compute is over its quota of 60 jobs a minute — try again in 12 s')
    expect(quotaMessage({ error: 'team-compute is over its quota', retryAfter: 600 })).toBe('team-compute is over its quota — try again in 10 min')
    expect(quotaMessage({ error: 'team-compute is over its quota', retryAfter: undefined })).toBe('team-compute is over its quota')
  })
})

describe('QuotaNotice', () => {
  it('counts down while the submit waits, says when the wait is over, and is nothing before any 429', () => {
    expect(text(renderToString(<QuotaNotice quota={{ blocked: true, secondsLeft: 7, message: 'team-data is over its quota' }} />))).toContain('team-data is over its quota — try again in 7 s')
    expect(text(renderToString(<QuotaNotice quota={{ blocked: false, secondsLeft: 0, message: 'team-data is over its quota' }} />))).toContain('you can try again now')
    expect(renderToString(<QuotaNotice quota={{ blocked: false, secondsLeft: 0, message: null }} />)).toBe('')
  })
})
