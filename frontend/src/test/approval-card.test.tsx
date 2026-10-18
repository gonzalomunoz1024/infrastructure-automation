import { renderToString } from 'react-dom/server'
import { describe, expect, it } from 'vitest'
import type { Approval, Preview } from '../lib/types'
import { ApprovalCard } from '../components/ApprovalCard'

/*
 * An approval as the approvals page and the job page show it: the message, the group, the time
 * left and the decision buttons; with showPreview, the preview the approver decides on (what the
 * run does, what it touches, what policy said); decided, who decided and what they wrote.
 */

const PREVIEW: Preview = {
  workflow: 'vm-restart',
  version: '1.2.0',
  steps: [
    { id: 'get-vm', name: 'Read the VM', effect: 'read' },
    { id: 'restart', name: 'Restart the VM', effect: 'change', detail: 'vsphere vm.restart' },
  ],
  affectedResources: [{ id: 'vm-1042', type: 'vm', name: 'web-1042', via: 'restart' }],
  policy: { decision: 'needs-approval', rules: [{ id: 'prod-approval', result: 'needs-approval', message: 'Production changes need compute-approvers' }], engine: 'opa' },
}

const PENDING: Approval = {
  id: 'ap-1',
  jobId: 'job-9d21aa07',
  workflow: 'vm-restart',
  step: 'approve',
  group: 'compute-approvers',
  message: 'Restart web-1042 in prod for a kernel patch.',
  requestedAt: new Date(Date.now() - 12 * 60_000).toISOString(),
  timeout: 'PT4H',
  status: 'pending',
  preview: PREVIEW,
}

const text = (html: string) => html.replace(/<!--.*?-->/g, '')
const decide = async () => {}

describe('ApprovalCard', () => {
  it('shows the preview the approver decides on when asked to, above the decision', () => {
    const html = text(renderToString(<ApprovalCard approval={PENDING} showPreview onApprove={decide} onReject={decide} />))
    expect(html).toContain('Restart web-1042 in prod for a kernel patch.')
    expect(html).toContain('Needs approval')
    expect(html).toContain('Read the VM')
    expect(html).toContain('Restart the VM')
    expect(html).toContain('href="#/resources/vm-1042"')
    expect(html).toContain('Production changes need compute-approvers')
    // the preview comes before the comment box and the buttons
    expect(html.indexOf('Restart the VM')).toBeLessThan(html.indexOf('aria-label="Comment"'))
    expect(html).toContain('>Approve<')
    expect(html).toContain('>Reject<')
  })

  it('keeps to the compact card without showPreview, or when the approval carries none', () => {
    const plain = text(renderToString(<ApprovalCard approval={PENDING} onApprove={decide} onReject={decide} />))
    expect(plain).toContain('Restart web-1042 in prod for a kernel patch.')
    expect(plain).not.toContain('Production changes need compute-approvers')
    expect(plain).not.toContain('Read the VM')
    const none = text(renderToString(<ApprovalCard approval={{ ...PENDING, preview: undefined }} showPreview onApprove={decide} />))
    expect(none).not.toContain('Needs approval')
  })

  it('counts down the time left and links the job', () => {
    const html = text(renderToString(<ApprovalCard approval={PENDING} showPreview onApprove={decide} />))
    expect(html).toContain('compute-approvers')
    expect(html).toContain('(PT4H)')
    expect(html).toMatch(/in 3h 4\dm/)
    expect(html).toContain('href="#/jobs/job-9d21aa07"')
    expect(html).toContain('Waiting')
    expect(renderToString(<ApprovalCard approval={PENDING} jobLink={false} />)).not.toContain('href="#/jobs/job-9d21aa07"')
  })

  it('says who decided and what they wrote once decided, with no buttons', () => {
    const html = text(renderToString(<ApprovalCard approval={{ ...PENDING, status: 'approved', decidedBy: 'alice', decidedAt: new Date().toISOString(), comment: 'Window agreed' }} showPreview onApprove={decide} onReject={decide} />))
    expect(html).toContain('alice')
    expect(html).toContain('“Window agreed”')
    expect(html).not.toContain('aria-label="Comment"')
    expect(html).not.toContain('>Approve<')
    // the preview still says what was approved
    expect(html).toContain('Restart the VM')
  })
})
