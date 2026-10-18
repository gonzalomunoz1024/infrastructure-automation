import { renderToString } from 'react-dom/server'
import { describe, expect, it } from 'vitest'
import { CHANGE_REQUEST_COLUMNS, ChangeRequestTable, CheckDots, checksSummary, emptyChangeRequests, filterOf } from '../components/ChangeRequestTable'
import type { ChangeRequest } from '../lib/types'
import { workflowsTabOf } from '../pages/WorkflowsPage'

/* The Workflows page's Change requests tab: where a reviewer finds what waits for them. */

const text = (html: string) => html.replace(/<!--.*?-->/g, '')

const cr = (over: Partial<ChangeRequest> = {}): ChangeRequest => ({
  id: 'cr-202',
  kind: 'workflow',
  name: 'vm-restart',
  version: '2.0.0',
  branch: 'workflow/vm-restart-2.0.0',
  title: 'Publish vm-restart 2.0.0',
  author: 'gonzalo',
  status: 'open',
  strategy: 'canary',
  createdAt: '2026-10-18T09:00:00Z',
  checks: [
    { id: 'checks', label: 'Workflow checks', status: 'passed' },
    { id: 'contracts', label: 'Contracts', status: 'failed', detail: 'input.reason became required' },
    { id: 'replay', label: 'Replay of recent jobs', status: 'passed' },
  ],
  ...over,
})

describe('the change requests view', () => {
  it('reads its tab and its status from the address, the open ones by default', () => {
    expect(workflowsTabOf(undefined)).toBe('workflows')
    expect(workflowsTabOf('change-requests')).toBe('change-requests')
    expect(workflowsTabOf('releases')).toBe('workflows')
    expect(filterOf(undefined)).toBe('open')
    expect(filterOf('merged')).toBe('merged')
    expect(filterOf('closed')).toBe('closed')
    expect(filterOf('all')).toBe('all')
    expect(filterOf('draft')).toBe('open')
  })

  it('counts the checks by result, the worst first', () => {
    expect(checksSummary(cr().checks)).toBe('1 failed, 2 passed')
    expect(
      checksSummary([
        { id: 'a', label: 'A', status: 'skipped' },
        { id: 'b', label: 'B', status: 'warning' },
      ]),
    ).toBe('1 warning, 1 skipped')
    expect(checksSummary([])).toBe('none yet')
  })

  it('draws one dot per check under a label that counts them, each named in the tooltip', () => {
    const html = text(renderToString(<CheckDots checks={cr().checks} />))
    expect(html).toContain('role="img"')
    expect(html).toContain('aria-label="Checks: 1 failed, 2 passed"')
    expect(html).toContain('Workflow checks: passed')
    expect(html).toContain('Contracts: failed')
    expect(html.match(/rounded-full/g)).toHaveLength(3)
    expect(text(renderToString(<CheckDots checks={[]} />))).toContain('none yet')
  })

  it('lists the title, kind, name, version, author, status, checks and when it opened', () => {
    expect(CHANGE_REQUEST_COLUMNS.map((c) => c.key)).toEqual(['title', 'kind', 'name', 'version', 'author', 'status', 'checks', 'createdAt'])
    const rows = [cr(), cr({ id: 'cr-101', kind: 'provider', name: 'backup-service', version: '1.0.0', title: 'Add provider backup-service', author: 'ana', checks: [] })]
    const html = text(renderToString(<ChangeRequestTable rows={rows} filter="open" />))
    expect(html).toContain('<caption class="sr-only">Change requests</caption>')
    for (const header of ['Change request', 'Kind', 'Name', 'Version', 'Author', 'Status', 'Checks', 'Opened']) expect(html).toContain(`>${header}<`)
    expect(html).toContain('Publish vm-restart 2.0.0')
    expect(html).toContain('cr-202')
    expect(html).toContain('>workflow<')
    expect(html).toContain('Add provider backup-service')
    expect(html).toContain('>provider<')
    expect(html).toContain('backup-service')
    expect(html).toContain('>ana<')
    expect(html).toContain('aria-label="Checks: 1 failed, 2 passed"')
    expect(html).not.toContain('Nothing waits for review')
  })

  it('says nothing waits for review when nothing is open, and words the other statuses', () => {
    const html = text(renderToString(<ChangeRequestTable rows={[]} filter="open" />))
    expect(html).toContain('Nothing waits for review')
    expect(emptyChangeRequests('merged').title).toBe('No merged change requests')
    expect(emptyChangeRequests('closed').title).toBe('No closed change requests')
    expect(emptyChangeRequests('all').title).toBe('No change requests yet')
  })
})
