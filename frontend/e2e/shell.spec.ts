import { expect, test } from '@playwright/test'

/*
 * The shell smoke: it renders, the sidebar navigates, ⌘K opens the palette.
 * Only /me is ever needed, and the shell must survive without it.
 */

test.describe('shell', () => {
  test('renders the sidebar, the header and the workflows page', async ({ page }) => {
    await page.goto('/#/workflows')
    await expect(page.getByRole('navigation', { name: 'Primary' })).toBeVisible()
    await expect(page.getByRole('link', { name: 'Workflows' })).toHaveAttribute('aria-current', 'page')
    await expect(page.getByRole('heading', { level: 1, name: 'Workflows' })).toBeVisible()
    await expect(page.getByRole('button', { name: 'Search' })).toBeVisible()
    await expect(page).toHaveTitle(/Workflows · Infrastructure Automation/)
  })

  test('the root redirects to the workflows list', async ({ page }) => {
    await page.goto('/')
    await expect(page).toHaveURL(/#\/workflows$/)
  })

  test('the sidebar navigates between pages', async ({ page }) => {
    await page.goto('/#/workflows')
    await page.getByRole('link', { name: 'Jobs' }).click()
    await expect(page).toHaveURL(/#\/jobs$/)
    await expect(page.getByRole('heading', { level: 1, name: 'Jobs' })).toBeVisible()
    await expect(page.getByRole('link', { name: 'Jobs' })).toHaveAttribute('aria-current', 'page')
    await page.getByRole('link', { name: 'Settings' }).click()
    await expect(page.getByRole('heading', { level: 1, name: 'Settings' })).toBeVisible()
  })

  test('⌘K opens the command palette and jumps to a page', async ({ page }) => {
    await page.goto('/#/workflows')
    await expect(page.getByRole('button', { name: 'Search' })).toBeVisible() // the shell is mounted
    await page.keyboard.press('ControlOrMeta+k')
    const dialog = page.getByRole('dialog', { name: 'Command palette' })
    await expect(dialog).toBeVisible()
    await dialog.getByRole('textbox').fill('approv')
    await page.keyboard.press('Enter')
    await expect(page).toHaveURL(/#\/approvals$/)
    await expect(page.getByRole('heading', { level: 1, name: 'Approvals' })).toBeVisible()
  })

  test('Escape closes the palette', async ({ page }) => {
    await page.goto('/#/workflows')
    await page.getByRole('button', { name: 'Search' }).click()
    const dialog = page.getByRole('dialog', { name: 'Command palette' })
    await expect(dialog).toBeVisible()
    await page.keyboard.press('Escape')
    await expect(dialog).toBeHidden()
  })

  test('keeps rendering when the backend is unreachable', async ({ page }) => {
    await page.route('**/api/v1/**', (route) => route.abort('connectionrefused'))
    await page.goto('/#/jobs')
    await expect(page.getByText('Backend unreachable').first()).toBeVisible()
    await expect(page.getByRole('heading', { level: 1, name: 'Jobs' })).toBeVisible()
  })

  test('shows the signed-in user from /me', async ({ page }) => {
    await page.route('**/api/v1/me', (route) =>
      route.fulfill({
        json: { id: 'gonzalo', name: 'Gonzalo', email: 'gonzalo@example.com', teams: ['team-compute'], roles: ['ops', 'admin'], admin: true, kind: 'user' },
      }),
    )
    await page.goto('/#/settings')
    await expect(page.getByRole('button', { name: /Gonzalo/ })).toBeVisible()
  })
})
