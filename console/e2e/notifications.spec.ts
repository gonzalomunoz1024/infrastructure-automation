import { fileURLToPath } from 'node:url'
import { expect, test, type Page } from '@playwright/test'
import { expectNoHorizontalOverflow, expectNoSeriousA11y, signInAs } from './support'

/*
 * Phase 7, in mock mode: notifications. Dana reads their notifications in the bell and on the
 * Notifications page, filters and marks them read, opens one to its resource, and sees a new one
 * arrive on the live stream when a request they started ends; changes where each kind goes and
 * sends a test; links Teams with the token the chat app gives and unlinks Slack; reads the
 * account's weekly digest and follows a highlight. Each page and dialog is checked with axe and
 * the pages at phone width. The mock runs requests on a clock: 8 s to apply.
 */

const shots = fileURLToPath(new URL('./screenshots/', import.meta.url))
const shot = (name: string) => `${shots}${name}.png`

const ACCOUNT = '123456789012'
const DANA = /Continue as Dana Reyes/
const wfrnOf = (id: string) => `wfrn:wf:compute:global:${ACCOUNT}:vm/${id}`

const bell = (page: Page) => page.getByRole('button', { name: /^Notifications/ })
const bellPanel = (page: Page) => page.getByRole('dialog', { name: /^Notifications/ })
const feed = (page: Page) => page.getByRole('list', { name: 'Notifications' })
const palette = (page: Page) => page.getByRole('dialog', { name: 'Command palette' })

/** The token `/console link` answers with, as the mock BFF signs it: a JWT naming the chat user, good for ten minutes. */
function chatLinkToken(claims: Record<string, unknown>): string {
  const b64 = (o: unknown) => Buffer.from(JSON.stringify(o)).toString('base64url')
  return `${b64({ alg: 'HS256', typ: 'JWT' })}.${b64({ aud: 'console-chat-link', exp: Math.floor(Date.now() / 1000) + 600, ...claims })}.mock-signature`
}

async function onPhone(page: Page, ready: () => Promise<void>): Promise<void> {
  await page.setViewportSize({ width: 390, height: 844 })
  await page.reload()
  await ready()
}

test.describe('notifications in mock mode', () => {
  test('Dana reads their notifications in the bell, opens one to its resource, filters and marks the rest read', async ({ page }) => {
    await signInAs(page, DANA)
    await expect(bell(page)).toHaveAccessibleName(/^Notifications, (\d+ to decide, )?\d+ unread$/)
    await bell(page).click()
    const panel = bellPanel(page)
    await expect(panel.getByRole('list', { name: 'Notifications' })).toBeVisible()
    await expect(panel.locator('[data-notification="ntf_dana_drift"]')).toHaveAttribute('data-unread', 'true')
    await page.screenshot({ path: shot('notifications-bell-desktop') })
    await expectNoSeriousA11y(page, 'notifications bell')

    // the drift finding leads to api-01's Drift tab, and is read on the way
    await panel.getByRole('link', { name: /api-01 drifted from what was asked for/ }).click()
    await expect(page).toHaveURL(/#\/console\/compute\/vm\/.*vm-demo02/)
    await expect(page).toHaveURL(/tab=drift/)
    await expect(page.getByRole('heading', { level: 1 })).toContainText('api-01')

    // the Notifications page: unread first filtered, then by kind, each marked read where it stands
    await page.goto('/#/notifications')
    await expect(page.getByRole('heading', { name: 'Notifications', level: 1 })).toBeVisible()
    await expect(feed(page).locator('[data-notification="ntf_dana_drift"]')).not.toHaveAttribute('data-unread')
    await page.getByRole('radio', { name: /^Unread/ }).click()
    await expect(page).toHaveURL(/unread=1/)
    await expect(feed(page).locator('[data-notification]').first()).toHaveAttribute('data-unread', 'true')
    await expect(feed(page).locator('[data-notification="ntf_dana_drift"]')).toHaveCount(0)
    await page.screenshot({ path: shot('notifications-unread-desktop'), fullPage: true })
    await expectNoSeriousA11y(page, 'Notifications page')
    const digest = feed(page).locator('li').filter({ hasText: 'The weekly digest of Compute is ready' })
    await digest.getByRole('button', { name: /^Mark read/ }).click()
    await expect(digest).toHaveCount(0)

    await page.getByRole('radio', { name: 'All' }).click()
    await page.getByRole('button', { name: /^Kind: / }).click()
    await page.getByRole('option', { name: 'Access decided' }).click()
    await expect(page).toHaveURL(/kind=access-request.decided/)
    await expect(feed(page).locator('[data-notification]')).toHaveCount(1)
    await expect(feed(page)).toContainText('Your access request for Operator in Compute was approved')

    await page.goto('/#/notifications')
    await page.getByRole('button', { name: 'Mark all read' }).click()
    await expect(page.getByRole('button', { name: 'Mark all read' })).toBeDisabled()
    await expect(feed(page).locator('[data-unread]')).toHaveCount(0)
    await expect(bell(page)).not.toHaveAccessibleName(/unread/)
  })

  test('a request Dana started notifies them on the live stream when it ends, and opens to its request', async ({ page }) => {
    await signInAs(page, DANA, '/notifications')
    await expect(feed(page)).toBeVisible()
    // started elsewhere (the CLI, say): a restart of shop-01, a dev machine that needs no approval
    const jobId = await page.evaluate(async (wfrn) => {
      const res = await fetch(`/console/v1/r/vm/${encodeURIComponent(wfrn)}:restart`, { method: 'POST', headers: { 'Content-Type': 'application/json', 'X-Csrf-Token': 'mock-csrf-dana', 'Idempotency-Key': `e2e-${Date.now()}` }, body: JSON.stringify({ input: { reason: 'routine restart' } }) })
      return ((await res.json()) as { jobId: string }).jobId
    }, wfrnOf('vm-demo06'))
    const arrived = feed(page).locator('li').filter({ hasText: 'shop-01 succeeded' })
    await expect(arrived).toBeVisible({ timeout: 25_000 })
    await expect(arrived).toHaveAttribute('data-unread', 'true')
    await expect(page.getByRole('status').filter({ hasText: 'New notification: ' })).toBeAttached()
    await arrived.getByRole('link').click()
    await expect(page).toHaveURL(new RegExp(`#/requests/${jobId}$`))
  })

  test('Dana changes where each kind goes, saves against the version read and sends a test', async ({ page }) => {
    await signInAs(page, DANA)
    // found from the user menu
    await page.getByRole('button', { name: /^Account menu: Dana Reyes/ }).click()
    await page.getByRole('menuitem', { name: 'Notification settings' }).click()
    await expect(page.getByRole('heading', { name: 'Notification settings', level: 1 })).toBeVisible()
    const succeededByEmail = page.getByRole('checkbox', { name: 'Request succeeded by Email' })
    await expect(succeededByEmail).not.toBeChecked()
    await expect(page.getByRole('checkbox', { name: 'Request failed by Slack' })).toBeChecked()
    await expect(page.getByRole('checkbox', { name: /^Request failed by Teams, teams is not linked$/ })).toBeDisabled()
    await page.screenshot({ path: shot('notification-settings-desktop'), fullPage: true })
    await expectNoSeriousA11y(page, 'Notification settings')

    await succeededByEmail.check()
    await expect(page.getByText('You have changes that are not saved.')).toBeVisible()
    await page.getByRole('button', { name: 'Save settings' }).click()
    await expect(page.getByText('Everything is saved.')).toBeVisible()
    await page.reload()
    await expect(page.getByRole('checkbox', { name: 'Request succeeded by Email' })).toBeChecked()
    await expect(page.locator('[data-kind="job.succeeded"]')).toContainText('Changed from the default')

    // a test goes to Slack; with email off, every email box greys and its test with it
    await page.locator('[data-test-channel="slack"]').click()
    await expect(page.getByRole('status').filter({ hasText: 'Test sent' })).toBeAttached()
    await page.getByRole('switch', { name: 'Email' }).click()
    await expect(page.getByRole('checkbox', { name: /^Request succeeded by Email, email is turned off$/ })).toBeDisabled()
    await page.getByRole('button', { name: 'Save settings' }).click()
    await expect(page.getByText('Everything is saved.')).toBeVisible()
    await expect(page.locator('[data-test-channel="email"]')).toBeDisabled()

    // and from the palette
    await page.goto('/#/')
    await page.keyboard.press('ControlOrMeta+k')
    await expect(palette(page)).toBeVisible()
    await page.keyboard.type('Notification settings')
    await page.keyboard.press('Enter')
    await expect(page).toHaveURL(/#\/settings\/notifications$/)
  })

  test('Dana links Teams with the token the chat app gave them, and unlinks Slack', async ({ page }) => {
    const token = chatLinkToken({ platform: 'teams', workspaceId: 'firm.onmicrosoft.com', chatUserId: '29:dana-reyes', chatUserName: 'Dana Reyes (Teams)' })
    await signInAs(page, DANA, `/chat-link?token=${token}`)
    const confirm = page.locator('[data-chat-link-confirm]')
    await expect(confirm).toContainText('Link Teams to Dana Reyes')
    await expect(confirm).toContainText('Dana Reyes (Teams)')
    await expect(confirm).toContainText('firm.onmicrosoft.com')
    await page.screenshot({ path: shot('chat-link-confirm-desktop'), fullPage: true })
    await expectNoSeriousA11y(page, 'chat link page')
    await confirm.getByRole('button', { name: 'Link Teams' }).click()
    await expect(page).toHaveURL(/#\/chat-link$/)
    const links = page.getByRole('list', { name: 'Linked chats' })
    await expect(links.locator('[data-chat-link]')).toHaveCount(2)
    await expect(links).toContainText('Teams: Dana Reyes (Teams)')

    await links.locator('[data-chat-link="slack"]').getByRole('button', { name: 'Unlink Slack' }).click()
    const dialog = page.getByRole('dialog', { name: /Unlink Slack/ })
    await expect(dialog).toBeVisible()
    await expectNoSeriousA11y(page, 'unlink dialog')
    await dialog.getByRole('button', { name: 'Unlink' }).click()
    await expect(links.locator('[data-chat-link]')).toHaveCount(1)
    await page.screenshot({ path: shot('chat-links-desktop'), fullPage: true })

    // the settings follow: Teams on, Slack no longer linked
    await page.goto('/#/settings/notifications')
    await expect(page.getByRole('switch', { name: 'Teams' })).toHaveAttribute('aria-checked', 'true')
    await expect(page.getByRole('switch', { name: 'Slack' })).toBeDisabled()

    // a stale token is refused in words
    await page.goto(`/#/chat-link?token=${chatLinkToken({ platform: 'slack', workspaceId: 'T0FIRM01', chatUserId: 'U0DANA42', exp: Math.floor(Date.now() / 1000) - 60 })}`)
    await page.getByRole('button', { name: 'Link Slack' }).click()
    await expect(page.locator('[data-chat-link-failed]')).toContainText('This link has expired')
  })

  test('Dana reads the account’s weekly digest and follows a highlight to the resource', async ({ page }) => {
    await signInAs(page, DANA, '/digests')
    await expect(page.getByRole('heading', { name: 'Weekly digests', level: 1 })).toBeVisible()
    const weeks = page.getByRole('list', { name: 'Weekly digests of Compute' }).locator('[data-digest]')
    await expect(weeks).toHaveCount(2)
    await expectNoSeriousA11y(page, 'Weekly digests')
    await weeks.first().getByRole('link').click()
    await expect(page).toHaveURL(/#\/digests\/\d{4}-\d{2}-\d{2}$/)
    await expect(page.getByRole('heading', { level: 1 })).toContainText(/^Week of \d+/)
    await expect(page.locator('[data-digest-source="assistant"]')).toContainText('Written by the copilot')
    for (const s of ['changes', 'drift', 'cost', 'access']) await expect(page.locator(`[data-digest-section="${s}"]`)).toBeVisible()
    await expect(page.locator('[data-digest-section="changes"]')).toContainText('Requests')
    await page.screenshot({ path: shot('digest-desktop'), fullPage: true })
    await expectNoSeriousA11y(page, 'weekly digest')

    await page.getByRole('link', { name: /Week before:/ }).click()
    await expect(page.locator('[data-digest-source="rules"]')).toContainText('Written from the template')
    await page.goBack()
    await page.getByRole('link', { name: 'api-01 runs 16 GB where 8 GB was asked for' }).click()
    await expect(page).toHaveURL(/tab=drift/)
    await expect(page.getByRole('heading', { level: 1 })).toContainText('api-01')
  })

  test('the notification pages fit a phone', async ({ page }) => {
    await signInAs(page, DANA, '/notifications')
    await onPhone(page, () => expect(feed(page)).toBeVisible())
    await expectNoHorizontalOverflow(page)
    await expectNoSeriousA11y(page, 'Notifications page at phone width')
    await page.screenshot({ path: shot('notifications-phone'), fullPage: true })
    await bell(page).click()
    await expect(bellPanel(page).getByRole('list', { name: 'Notifications' })).toBeVisible()
    await expectNoHorizontalOverflow(page)
    await page.keyboard.press('Escape')

    await page.goto('/#/settings/notifications')
    await expect(page.getByRole('heading', { name: 'Notification settings', level: 1 })).toBeVisible()
    await expectNoHorizontalOverflow(page)
    await expectNoSeriousA11y(page, 'Notification settings at phone width')
    await page.screenshot({ path: shot('notification-settings-phone'), fullPage: true })

    await page.goto('/#/chat-link')
    await expect(page.getByRole('list', { name: 'Linked chats' })).toBeVisible()
    await expectNoHorizontalOverflow(page)
    await expectNoSeriousA11y(page, 'Linked chats at phone width')

    await page.goto('/#/digests')
    await page.locator('[data-digest]').first().getByRole('link').click()
    await expect(page.locator('[data-digest-section="cost"]')).toBeVisible()
    await expectNoHorizontalOverflow(page)
    await expectNoSeriousA11y(page, 'weekly digest at phone width')
    await page.screenshot({ path: shot('digest-phone'), fullPage: true })
  })
})
