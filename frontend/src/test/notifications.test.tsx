import { describe, expect, it } from 'vitest'
import { renderToString } from 'react-dom/server'
import { NotificationList, notificationTarget, severityDot } from '../app/Notifications'
import type { Notification } from '../lib/types'

/* The inbox panel: what each notification says, where it leads, and how it is marked read. */

const text = (html: string) => html.replace(/<!--.*?-->/g, '')

const base = { at: '2026-10-18T09:00:00Z', users: ['gonzalo'], teams: [] as string[] }
const ITEMS: Notification[] = [
  { ...base, id: 'n-1', kind: 'approval.requested', severity: 'warning', title: 'Approve the restart of web-1042', message: 'compute-approvers: a production restart waits for you', link: '/jobs/job_1042', jobId: 'job_1042', workflow: 'vm-restart', teams: ['compute-approvers'], read: false },
  { ...base, id: 'n-2', kind: 'provider.alert', severity: 'critical', title: 'vsphere is failing', message: '3 timeouts in 10 minutes', provider: 'vsphere', channel: '#compute-alerts', read: false },
  { ...base, id: 'n-3', kind: 'notify', severity: 'info', title: 'Patch window done', message: '20 of 20 servers patched', link: '/jobs/job_7', jobId: 'job_7', read: true },
]

describe('notificationTarget', () => {
  it('follows the link when the router knows it, with a label for what it opens', () => {
    expect(notificationTarget({ link: '/jobs/job_1' })).toEqual({ to: '/jobs/job_1', label: 'Open job' })
    expect(notificationTarget({ link: '#/change-requests/cr_7' })).toEqual({ to: '/change-requests/cr_7', label: 'Open change request' })
    expect(notificationTarget({ link: '/releases/rel-9' })).toEqual({ to: '/releases/rel-9', label: 'Open release' })
    expect(notificationTarget({ link: '/providers/vsphere' })).toEqual({ to: '/providers/vsphere', label: 'Open provider' })
    expect(notificationTarget({ link: '/approvals?status=pending' })).toEqual({ to: '/approvals?status=pending', label: 'Open approvals' })
  })

  it('falls back to the job, the provider or the workflow it names when the link is not a page', () => {
    expect(notificationTarget({ link: '/somewhere/else', jobId: 'job_2' })).toEqual({ to: '/jobs/job_2', label: 'Open job' })
    expect(notificationTarget({ provider: 'backup-service' })).toEqual({ to: '/providers/backup-service', label: 'Open provider' })
    expect(notificationTarget({ workflow: 'vm-restart' })).toEqual({ to: '/workflows/vm-restart', label: 'Open workflow' })
    expect(notificationTarget({})).toBeNull()
  })

  it('tints the dot by severity', () => {
    expect(severityDot('critical')).toBe('critical')
    expect(severityDot('warning')).toBe('warning')
    expect(severityDot('info')).toBe('neutral')
  })
})

describe('NotificationList', () => {
  it('lists title, message, kind, channel and the link of each one, unread ones marked', () => {
    const html = text(renderToString(<NotificationList items={ITEMS} onRead={() => {}} />))
    expect(html).toContain('aria-label="Notifications"')
    expect(html).toContain('Approve the restart of web-1042')
    expect(html).toContain('a production restart waits for you')
    expect(html).toContain('href="#/jobs/job_1042"')
    expect(html).toContain('Open job')
    expect(html).toContain('href="#/providers/vsphere"')
    expect(html).toContain('Open provider')
    expect(html).toContain('#compute-alerts')
    expect(html).toContain('provider alert')
    expect(html).toContain('approval')
    expect(html.match(/data-read="false"/g)).toHaveLength(2)
    expect(html.match(/data-read="true"/g)).toHaveLength(1)
    // only the unread ones offer to be marked read
    expect(html).toContain('aria-label="Mark read: vsphere is failing"')
    expect(html).not.toContain('aria-label="Mark read: Patch window done"')
  })

  it('asks the owner team for a review with a link to the change request', () => {
    const review: Notification = { ...base, id: 'n-4', kind: 'review.requested', severity: 'info', title: 'Review requested: vm-restart 2.0.0', message: 'gonzalo opened a change request for workflow vm-restart 2.0.0', link: '/change-requests/cr-202', workflow: 'vm-restart', users: [], teams: ['team-compute'], read: false }
    const html = text(renderToString(<NotificationList items={[review]} onRead={() => {}} />))
    expect(html).toContain('Review requested: vm-restart 2.0.0')
    expect(html).toContain('gonzalo opened a change request for workflow vm-restart 2.0.0')
    expect(html).toContain('>review<')
    expect(html).toContain('href="#/change-requests/cr-202"')
    expect(html).toContain('Open change request')
  })

  it('says when there is nothing', () => {
    const html = text(renderToString(<NotificationList items={[]} onRead={() => {}} />))
    expect(html).toContain('Nothing for you')
    expect(html).toContain('change requests your team reviews')
  })
})
