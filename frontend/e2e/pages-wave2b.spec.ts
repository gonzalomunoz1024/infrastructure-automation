import { expect, test } from '@playwright/test'
import { NOTIFICATIONS, PLATFORM_STREAM, WORKFLOWS, inboxRoutes, mockApi } from './fixtures'

/*
 * The pages against a mocked /api/v1: the inbox in the header, the platform audit stream, a
 * deprecated catalog entry, a run pinned to a version with an idempotency key and a 429, the
 * Swagger links, provider releases and the provider change requests, the provider imports, the
 * assistant's validation rounds and the service-account scopes.
 */

test.describe('notifications', () => {
  test('the bell counts the unread, the panel lists the inbox and marks one, then all, read', async ({ page }) => {
    const api = await mockApi(page, inboxRoutes())
    await page.goto('/#/jobs')
    const bell = page.getByTestId('notifications-bell')
    await expect(bell).toHaveAccessibleName('Notifications: 2 unread')
    await bell.click()
    const panel = page.getByRole('dialog', { name: 'Notifications' })
    await expect(panel).toBeVisible()
    const items = panel.getByRole('list', { name: 'Notifications' }).getByRole('listitem')
    await expect(items).toHaveCount(3)
    // newest first, as the backend orders them; where each one leads
    await expect(items.nth(0)).toContainText('Approve the restart of web-1042')
    await expect(items.nth(0).getByRole('link', { name: 'Open job' })).toHaveAttribute('href', '#/jobs/job-7f3a2c91')
    await expect(items.nth(1).getByRole('link', { name: 'Open provider' })).toHaveAttribute('href', '#/providers/vsphere')
    await expect(items.nth(2)).toHaveAttribute('data-read', 'true')

    await items.nth(1).getByRole('button', { name: 'Mark read: vsphere is failing' }).click()
    await expect.poll(() => api.callsTo('POST', '/notifications/ntf-2:read').length).toBe(1)
    await expect(bell).toHaveAccessibleName('Notifications: 1 unread')
    await expect(items.nth(1)).toHaveAttribute('data-read', 'true')

    await panel.getByRole('button', { name: 'Mark all read' }).click()
    await expect.poll(() => api.callsTo('POST', '/notifications/ntf-1:read').length).toBe(1)
    await expect(bell).toHaveAccessibleName('Notifications')
    await expect(panel.getByRole('button', { name: 'Mark all read' })).toBeDisabled()

    // Escape closes the panel; following a link does too
    await page.keyboard.press('Escape')
    await expect(panel).toBeHidden()
    await bell.click()
    await page.getByRole('dialog', { name: 'Notifications' }).getByRole('link', { name: 'Open provider' }).click()
    await expect(page).toHaveURL(/#\/providers\/vsphere$/)
    await expect(page.getByRole('dialog', { name: 'Notifications' })).toBeHidden()
  })

  test('an empty inbox says so and the bell carries no count', async ({ page }) => {
    await mockApi(page, inboxRoutes(NOTIFICATIONS.map((n) => ({ ...n, read: true }))))
    await page.goto('/#/jobs')
    const bell = page.getByTestId('notifications-bell')
    await expect(bell).toHaveAccessibleName('Notifications')
    await bell.click()
    await expect(page.getByRole('dialog', { name: 'Notifications' }).getByRole('listitem')).toHaveCount(3)
    await page.keyboard.press('Escape')
    await mockApi(page, inboxRoutes([]))
    await page.reload()
    await page.getByTestId('notifications-bell').click()
    await expect(page.getByText('Nothing for you')).toBeVisible()
  })
})

test.describe('audit: the platform stream', () => {
  test('a second tab lists platform events with their subjects, filters by type and actor, and opens the entry', async ({ page }) => {
    const api = await mockApi(page, {
      'GET /audit': [],
      'GET /workflows': WORKFLOWS,
      'GET /audit/platform': (req) => {
        const aggregate = req.query.get('aggregate')
        const actor = req.query.get('actor')
        const entries = PLATFORM_STREAM.entries.filter((e) => (!aggregate || e.data.aggregate === aggregate) && (!actor || e.actor === actor))
        return { ...PLATFORM_STREAM, entries, total: PLATFORM_STREAM.total }
      },
    })
    await page.goto('/#/audit')
    await page.getByRole('tab', { name: 'Platform events' }).click()
    await expect(page).toHaveURL(/tab=platform/)
    await expect(page.getByText('Chain intact')).toBeVisible()
    await expect(page.getByText('6 of 6 entries verify against their hashes')).toBeVisible()
    const table = page.getByRole('table', { name: 'Platform events' })
    const rows = table.locator('tbody tr')
    await expect(rows).toHaveCount(6)
    // newest first; each row says what happened and links to its subject
    await expect(rows.nth(0)).toContainText('service account ci-deployer created')
    await expect(rows.nth(1).getByRole('link', { name: 'rel-1' })).toHaveAttribute('href', '#/releases/rel-1')
    await expect(rows.nth(2).getByRole('link', { name: 'vsphere' })).toHaveAttribute('href', '#/providers/vsphere')
    await expect(rows.nth(5).getByRole('link', { name: 'cr-41' })).toHaveAttribute('href', '#/change-requests/cr-41')

    await page.getByRole('button', { name: 'Type' }).click()
    await page.getByRole('option', { name: 'Providers' }).click()
    await expect.poll(() => api.callsTo('GET', '/audit/platform').some((c) => c.query.includes('aggregate=provider'))).toBe(true)
    await expect(rows).toHaveCount(1)
    await expect(rows.nth(0)).toContainText('vsphere 2.1.1 change requested')

    await page.getByLabel('Actor').fill('maria')
    await expect.poll(() => api.callsTo('GET', '/audit/platform').some((c) => c.query.includes('actor=maria') && c.query.includes('aggregate=provider'))).toBe(true)
    await page.getByRole('button', { name: 'Clear' }).click()
    await expect(rows).toHaveCount(6)

    await rows.nth(3).getByText('vm-restart 1.2.0 published').click()
    const drawer = page.getByRole('dialog', { name: 'Platform event 3' })
    await expect(drawer).toBeVisible()
    await expect(drawer).toContainText('vm-restart 1.2.0 published')
    await expect(drawer).toContainText('sha256:1')
    await page.keyboard.press('Escape')
    await expect(drawer).toBeHidden()

    // the job evidence tab is still there, untouched
    await page.getByRole('tab', { name: 'Job evidence' }).click()
    await expect(page.getByRole('table', { name: 'Evidence records' })).toBeVisible()
  })

  test('a broken chain says where it broke', async ({ page }) => {
    await mockApi(page, { 'GET /audit': [], 'GET /workflows': WORKFLOWS, 'GET /audit/platform': { ...PLATFORM_STREAM, verification: { valid: false, entries: 6, brokenAt: 4 } } })
    await page.goto('/#/audit?tab=platform')
    await expect(page.getByRole('tab', { name: 'Platform events' })).toHaveAttribute('aria-selected', 'true')
    await expect(page.getByText('Chain broken at entry 4')).toBeVisible()
  })
})
