import { describe, expect, it } from 'vitest'
import { renderToString } from 'react-dom/server'
import type { JobReplay } from '../lib/types'
import { ReplayComparison } from '../pages/JobPage'

const text = (html: string) => html.replace(/<!--.*?-->/g, '')

const REPLAY: JobReplay = {
  jobId: 'job-7f3a2c91',
  workflow: 'vm-restart',
  version: '1.3.0',
  status: 'failed',
  comparedTo: 'job-2c44a810',
  comparedVersion: '1.2.0',
  comparedStatus: 'succeeded',
  inputDiff: [{ field: 'input.environment', before: 'dev', after: 'prod' }],
  steps: [
    { id: 'get-vm', name: 'Read the VM', type: 'provider', status: 'succeeded', attempt: 1, diff: [] },
    { id: 'restart', name: 'Restart the VM', type: 'provider', status: 'failed', attempt: 3, diff: [{ field: 'status', before: 'succeeded', after: 'failed' }, { field: 'error.class', before: null, after: 'timeout' }] },
  ],
}

describe('ReplayComparison', () => {
  it('names the compared run, its version and status, and what differs', () => {
    const html = text(renderToString(<ReplayComparison replay={REPLAY} />))
    expect(html).toContain('href="#/jobs/job-2c44a810"')
    expect(html).toContain('1.2.0')
    expect(html).toContain('succeeded')
    expect(html).toContain('different version')
    expect(html).toContain('1 input field and 1 step differ.')
    // the input diff table: the compared run first, then this run
    expect(html).toContain('input.environment')
    expect(html.indexOf('Compared run')).toBeLessThan(html.indexOf('This run'))
    expect(html.indexOf('>dev<')).toBeLessThan(html.indexOf('>prod<'))
  })

  it('says when nothing differs', () => {
    const html = text(renderToString(<ReplayComparison replay={{ ...REPLAY, version: '1.2.0', inputDiff: [], steps: REPLAY.steps.map((s) => ({ ...s, diff: [] })) }} />))
    expect(html).toContain('nothing differs.')
    expect(html).not.toContain('different version')
  })

  it('says when there was nothing to compare with', () => {
    const html = text(renderToString(<ReplayComparison replay={{ steps: REPLAY.steps }} />))
    expect(html).toContain('no earlier successful run to compare with')
  })
})
