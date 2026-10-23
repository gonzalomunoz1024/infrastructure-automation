import { fileURLToPath } from 'node:url'
import { expect, test, type Page } from '@playwright/test'
import { expectNoHorizontalOverflow, expectNoSeriousA11y, signInAs, tabTo } from './support'

/*
 * The failure explainer's journey in mock mode (WP 9.2): a failed request says what went wrong in
 * plain words, the step that stopped and a fix with how sure it is; Retry with fix runs the
 * request page's own retry (phase 4) with the fix, and the rerun then shows as the last fix; a
 * viewer sees the retry locked with the refusal behind it; a paused step leaves its retry to the
 * paused step's own buttons; and a resource whose create stopped names the cause above its facts.
 */

const shots = fileURLToPath(new URL('./screenshots/', import.meta.url))
const shot = (name: string) => `${shots}${name}.png`

const SEED_FAILED = 'job_83amhygy2c'
const SEED_PAUSED = 'job_demo_attention'

const card = (page: Page) => page.locator('[data-explanation]')
/** the request's own status, from its facts (its steps carry statuses too) */
const requestStatus = (page: Page) => page.locator('dl > div').filter({ has: page.locator('dt', { hasText: /^Status$/ }) }).locator('[data-status]')

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
    // the request page's own retry, with the fix
    const retry = what.getByRole('button', { name: /Retry with fix/ })
    await expect(retry).toBeEnabled()
    await expect(retry).not.toHaveAttribute('data-phase')
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

  test('Gonzalo retries the create with the fix, and the rerun then shows as the last fix', async ({ page }) => {
    test.slow()
    await signInAs(page, /Continue as Gonzalo Munoz/, `/requests/${SEED_FAILED}`)
    await card(page).getByRole('button', { name: /Retry with fix/ }).click()
    const confirm = page.getByRole('dialog', { name: 'Run this request again?' })
    await expect(confirm.getByText(/runs again with the changes above merged over its input, from step createVm/)).toBeVisible()
    await expectNoSeriousA11y(page, 'retry with fix, confirming')
    await confirm.getByRole('button', { name: 'Retry with fix' }).click()

    // an ended request runs again as a new one, which the page follows to its end
    await expect(page).not.toHaveURL(new RegExp(`#/requests/${SEED_FAILED}$`))
    const rerun = /#\/requests\/([^/?]+)$/.exec(page.url())?.[1] ?? ''
    expect(rerun).toMatch(/^job_/)
    await expect(requestStatus(page)).toHaveAttribute('data-status', 'succeeded', { timeout: 30_000 })

    await page.goto(`/#/requests/${SEED_FAILED}`)
    const fixed = card(page).locator('[data-similar]')
    await expect(fixed).toContainText('Last fixed by gonzalo')
    await expect(fixed.getByRole('link', { name: rerun })).toBeVisible()
    await expectNoSeriousA11y(page, 'failed request with its last fix')
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

  test('a request paused on a step is explained, and its retry stays with the paused step’s own buttons', async ({ page }) => {
    await signInAs(page, /Continue as Gonzalo Munoz/, `/requests/${SEED_PAUSED}`)
    const what = card(page)
    await expect(what).toHaveAttribute('data-explanation', 'rules')
    await expect(what.getByText('The provider failed while making the change')).toBeVisible()
    await expect(what.getByText('Medium confidence')).toBeVisible()
    await expect(what.getByText(/Runs again from step\s*apply/)).toBeVisible()
    // the fix changes nothing the page's own retry does not, so the card points to it rather than offer a second one
    await expect(what.getByText('Retry the step, above, runs it this way.')).toBeVisible()
    await expect(what.getByRole('button', { name: /Retry/ })).toHaveCount(0)
    await expect(page.locator('[data-attention]').getByRole('button', { name: 'Retry the step' })).toBeVisible()
    await expect(what.getByLabel('What the retry changes')).toHaveCount(0)
    await expectNoSeriousA11y(page, 'request paused on a step')
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
    // the provider fails the apply, and the create pauses on it (phase 4)
    const banner = page.locator('[data-job-status="needs-attention"]')
    await expect(banner).toBeVisible({ timeout: 30_000 })
    await expect(banner.getByText(/The provider failed while making the change\. The request has a fix to try\./)).toBeVisible()
    await expectNoSeriousA11y(page, 'resource with a paused create')
    await banner.getByRole('link', { name: /See the fix/ }).click()
    await expect(page).toHaveURL(new RegExp(`#/requests/${accepted.jobId}$`))
    const what = card(page)
    await expect(what.getByText('The provider failed while making the change')).toBeVisible()
    await expect(what.getByText(/Runs again from step\s*apply/)).toBeVisible()
    await expect(what.getByLabel('What the retry changes')).toHaveCount(0)

    // the paused step's own retry runs it again, and the request goes on to the end
    await page.locator('[data-attention]').getByRole('button', { name: 'Retry the step' }).click()
    await expect(page.locator('[data-attention]')).toHaveCount(0, { timeout: 30_000 })
    await expect(card(page)).toHaveCount(0)
    await expect(requestStatus(page)).toHaveAttribute('data-status', 'succeeded', { timeout: 30_000 })
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
