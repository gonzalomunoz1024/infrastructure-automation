import { expect, test } from '@playwright/test'
import { NOTIFICATIONS, inboxRoutes, mockApi } from './fixtures'

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
