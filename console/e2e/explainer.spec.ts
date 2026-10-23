import { fileURLToPath } from 'node:url'
import { expect, test, type Page } from '@playwright/test'
import { expectNoHorizontalOverflow, expectNoSeriousA11y, signInAs, tabTo } from './support'

/*
 * The failure explainer's journey in mock mode (WP 9.2): a failed request says what went wrong in
 * plain words, the step that stopped and a fix with how sure it is; a viewer sees the retry locked
 * with the refusal behind it; a request waiting on a step is explained by the copilot; and a
 * resource whose create failed names the cause above its facts. The retry itself is phase 4's.
 */

const shots = fileURLToPath(new URL('./screenshots/', import.meta.url))
const shot = (name: string) => `${shots}${name}.png`

const SEED_FAILED = 'job_83amhygy2c'
const SEED_PAUSED = 'job_demo_attention'

const card = (page: Page) => page.locator('[data-explanation]')

test.describe('the failure explainer in mock mode', () => {
  test('Gonzalo reads why a create failed, the fix it suggests, and asks the copilot about it', async ({ page }) => {
    await signInAs(page, /Continue as Gonzalo Munoz/, `/requests/${SEED_FAILED}`)
    const what = card(page)
    await expect(what).toHaveAttribute('data-explanation', 'rules')
    await expect(what.getByRole('heading', { name: 'What went wrong' })).toBeVisible()
    await expect(what.getByText('The image it names does not exist')).toBeVisible()
    await expect(what.getByText('Explained by the rules')).toBeVisible()
    await expect(what.getByText('Medium confidence')).toBeVisible()
    await expect(what.getByText(/Use the image ubuntu-24\.04-cis-l1-2026-10-01/)).toBeVisible()
    // exactly what the retry would change, and where it starts
    const changes = what.getByLabel('What the retry changes')
    await expect(changes.getByText('image', { exact: true })).toBeVisible()
    await expect(changes.getByText('image-demo01')).toBeVisible()
    await expect(what.getByText(/Runs again from step\s*createVm/)).toBeVisible()
    await expect(what.getByText('The first failure like this in this account in the last 30 days.')).toBeVisible()
    // the retry waits for phase 4; until then it says so and does nothing
    const retry = what.getByRole('button', { name: /Retry with fix/ })
    await expect(retry).toHaveAttribute('aria-disabled', 'true')
    await expect(retry).toHaveAttribute('data-phase', '4')
    await expectNoSeriousA11y(page, 'request with its explanation')
    await page.screenshot({ path: shot('explainer-desktop'), fullPage: true })

    // in the dark, from the palette
    await page.keyboard.press('ControlOrMeta+k')
    const palette = page.getByRole('dialog', { name: 'Command palette' })
    await expect(palette).toBeVisible()
    await page.keyboard.type('Use the dark appearance')
    await page.keyboard.press('Enter')
    await expect(palette).toBeHidden()
    await expect(page.locator('html')).toHaveClass(/dark/)
    await expectNoSeriousA11y(page, 'explanation in the dark')
    await page.screenshot({ path: shot('explainer-dark'), fullPage: true })

    // the copilot opens on this failure, by keyboard
    const ask = what.getByRole('button', { name: 'Ask the copilot about this failure' })
    await tabTo(page, ask)
    await page.keyboard.press('Enter')
    await expect(page.locator('#copilot-panel')).toBeVisible()
  })

  test('a viewer sees the retry locked, and the refusal says why', async ({ page }) => {
    await signInAs(page, /Continue as Mia Chen/, `/requests/${SEED_FAILED}`)
    const what = card(page)
    await expect(what).toHaveAttribute('data-explanation', 'rules')
    const retry = what.getByRole('button', { name: /Retry with fix/ })
    await expect(retry).toHaveAttribute('data-denied', 'console-iam')
    await retry.click()
    const refusal = page.getByRole('dialog')
    await expect(refusal).toBeVisible()
    await expect(refusal.getByText(/compute:create/).first()).toBeVisible()
    await expectNoSeriousA11y(page, 'retry refusal')
    await page.keyboard.press('Escape')
    await expect(refusal).toBeHidden()
  })

  test('a request waiting on a step is explained by the copilot, with a retry of the step', async ({ page }) => {
    await signInAs(page, /Continue as Gonzalo Munoz/, `/requests/${SEED_PAUSED}`)
    const what = card(page)
    await expect(what).toHaveAttribute('data-explanation', 'assistant')
    await expect(what.getByText('A step stopped and waits for a decision')).toBeVisible()
    await expect(what.getByText('Explained by the copilot')).toBeVisible()
    await expect(what.getByText('Low confidence')).toBeVisible()
    await expect(what.getByRole('button', { name: /Retry the step/ })).toBeVisible()
    await expect(what.getByLabel('What the retry changes')).toHaveCount(0)
    await expectNoSeriousA11y(page, 'request waiting on a step')
  })

  test('a VM whose create failed names the cause on its page, and the link leads to the fix', async ({ page }) => {
    test.slow()
    await signInAs(page, /Continue as Dana Reyes/, '/')
    // a create the mock's provider fails (its name asks for it), sent as the wizard would send it
    const accepted = await page.evaluate(async () => {
      const session = (await (await fetch('/console/v1/session')).json()) as { csrfToken: string }
      const res = await fetch('/console/v1/r/vm', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'X-Csrf-Token': session.csrfToken, 'Idempotency-Key': `e2e-${Date.now()}` },
        body: JSON.stringify({ input: { name: 'web-fail-9', image: 'image-demo01', size: 'small' } }),
      })
      return (await res.json()) as { wfrn: string; jobId: string }
    })
    await page.goto(`/#/console/compute/vm/${encodeURIComponent(accepted.wfrn)}`)
    const banner = page.locator('[data-job-status="failed"]')
    await expect(banner).toBeVisible({ timeout: 30_000 })
    await expect(banner.getByText(/The provider failed while making the change\. The request has a fix to try\./)).toBeVisible()
    await expectNoSeriousA11y(page, 'resource with a failed create')
    await banner.getByRole('link', { name: /See the fix/ }).click()
    await expect(page).toHaveURL(new RegExp(`#/requests/${accepted.jobId}$`))
    const what = card(page)
    await expect(what.getByText('The provider failed while making the change')).toBeVisible()
    await expect(what.getByText(/Runs again from step\s*apply/)).toBeVisible()
    // nothing to change in the input: the retry runs it again as it was
    await expect(what.getByRole('button', { name: /^Retry\b/ })).toBeVisible()
    await expect(what.getByLabel('What the retry changes')).toHaveCount(0)
  })

  test('the explanation fits a phone', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 })
    await signInAs(page, /Continue as Gonzalo Munoz/, `/requests/${SEED_FAILED}`)
    await expect(card(page)).toHaveAttribute('data-explanation', 'rules')
    await expectNoHorizontalOverflow(page)
    await expectNoSeriousA11y(page, 'explanation on a phone')
    await page.screenshot({ path: shot('explainer-phone'), fullPage: true })
  })
})
