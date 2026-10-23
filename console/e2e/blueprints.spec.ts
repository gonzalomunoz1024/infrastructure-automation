import { fileURLToPath } from 'node:url'
import { expect, test, type Page } from '@playwright/test'
import { expectNoHorizontalOverflow, expectNoSeriousA11y, signInAs } from './support'

/*
 * Phase 7, in mock mode: blueprints, change risk, scorecards and event subscriptions. Dana launches
 * a web tier from its blueprint and follows the stack's one request through every resource; she
 * may not delete machines, so Teardown is refused for her with why, and Gonzalo tears it down with
 * a reason and the name typed. A delete of a production machine says its risk is high and that it
 * needs an approver. Gonzalo reads the scorecard and fixes a powered-off production machine with
 * the rule's workflow. A webhook subscription is created and tested. Mia reads all of it and
 * changes nothing. The mock runs a request in 8 s and approves in 6 s.
 */

const shots = fileURLToPath(new URL('./screenshots/', import.meta.url))
const shot = (name: string) => `${shots}${name}.png`
const ACCOUNT = '123456789012'
const vmPage = (id: string) => `/console/compute/vm/${encodeURIComponent(`wfrn:wf:compute:global:${ACCOUNT}:vm/${id}`)}`

async function signOut(page: Page) {
  await page.goto('/#/settings')
  await page.getByRole('button', { name: 'Sign out' }).click()
  await expect(page).toHaveURL(/#\/signin/)
}

async function phone(page: Page, name: string, where: string) {
  await page.setViewportSize({ width: 390, height: 844 })
  // charts and wrapped rows measure themselves after a resize: let the layout settle first
  await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth), { timeout: 5_000 }).toBe(true)
  await expectNoHorizontalOverflow(page)
  await page.screenshot({ path: shot(`${name}-phone`), fullPage: true })
  await expectNoSeriousA11y(page, `${where} at phone width`)
  await page.setViewportSize({ width: 1280, height: 900 })
}

test.describe('blueprints and stacks in mock mode', () => {
  test('Dana launches a web tier, follows its stack to active, and Gonzalo tears it down', async ({ page }) => {
    test.setTimeout(150_000)
    await signInAs(page, /Continue as Dana Reyes/, '/blueprints')
    const catalog = page.getByRole('list', { name: /^Blueprints for/ })
    await expect(catalog.getByRole('listitem')).toHaveCount(3)
    await page.screenshot({ path: shot('blueprints-catalog-desktop'), fullPage: true })
    await expectNoSeriousA11y(page, 'blueprint catalog')
    await catalog.getByRole('link', { name: 'Web tier' }).click()
    await expect(page.getByRole('heading', { name: 'Web tier', level: 1 })).toBeVisible()
    await expect(page.getByRole('list', { name: 'Waves' }).getByRole('listitem').filter({ hasText: /^Wave/ })).toHaveCount(3)
    await expectNoSeriousA11y(page, 'blueprint page')
    await page.getByRole('button', { name: 'Launch' }).click()

    // step 1: the stack's name and the parameters, checked before moving on
    await expect(page.getByRole('heading', { name: 'Parameters', exact: true, level: 2 })).toBeVisible()
    await page.getByRole('button', { name: 'Next' }).click()
    await expect(page.locator('#stack-name')).toBeFocused()
    await expect(page.getByText('Name the stack')).toBeVisible()
    await page.locator('#stack-name').fill('shop')
    await page.locator('#param-appName').fill('shop')
    await expectNoSeriousA11y(page, 'launch wizard parameters')
    await page.getByRole('button', { name: 'Next' }).click()

    // step 2: the required tags, each with its default
    await expect(page.getByRole('heading', { name: 'Tags', exact: true, level: 2 })).toBeFocused()
    await page.getByRole('button', { name: 'Next' }).click()

    // step 3: the plan by wave, the authorizations, and the risk
    await expect(page.getByRole('heading', { name: 'Review the plan', level: 2 })).toBeFocused()
    const plan = page.getByRole('list', { name: 'The plan, wave by wave' })
    await expect(plan.locator('[data-plan-step]')).toHaveCount(3)
    await expect(plan.locator('[data-plan-step="web"]')).toContainText('(from Hardened image)')
    await expect(page.getByRole('list', { name: 'Authorizations' }).locator('[data-allowed="false"]')).toHaveCount(0)
    await expect(page.locator('[data-risk]')).toBeVisible()
    await page.screenshot({ path: shot('blueprint-launch-plan-desktop'), fullPage: true })
    await expectNoSeriousA11y(page, 'launch plan')
    await phone(page, 'blueprint-launch-plan', 'launch plan')
    await page.getByRole('button', { name: 'Launch stack' }).click()

    // the stack's page follows its one request through each resource, wave by wave
    await expect(page).toHaveURL(/#\/stacks\//)
    await expect(page.getByRole('heading', { name: 'shop', level: 1 })).toBeVisible()
    const status = page.locator('[data-stack-state]')
    await expect(status).toHaveAttribute('data-stack-state', 'pending')
    await expect(page.locator('[data-step="image"]')).toHaveAttribute('data-step-state', 'running')
    await expect(page.locator('[data-step="web"]')).toHaveAttribute('data-step-state', 'waiting')
    await expect(page.locator('[data-step="image"]').getByRole('link', { name: 'Request for Hardened image' })).toHaveAttribute('href', /#\/requests\/job_/)
    await page.screenshot({ path: shot('stack-launching-desktop'), fullPage: true })
    await expectNoSeriousA11y(page, 'stack launching')
    await expect(status).toHaveAttribute('data-stack-state', 'active', { timeout: 45_000 })
    await expect(page.locator('[data-step="web"]').getByRole('link', { name: 'shop-web' })).toHaveAttribute('href', /#\/console\/compute\/vm\//)
    await expect(page.getByRole('heading', { name: 'What it hands back' })).toBeVisible()
    await expect(page.getByRole('list', { name: 'Stack events' })).toContainText('Every resource is active')
    await page.screenshot({ path: shot('stack-active-desktop'), fullPage: true })
    await expectNoSeriousA11y(page, 'active stack')
    await phone(page, 'stack-active', 'active stack')

    // Dana may not delete machines: Teardown is refused, with why
    const locked = page.getByRole('button', { name: /^Tear down/ })
    await expect(locked).toHaveAttribute('data-denied', /.+/)
    await locked.click()
    await expect(page.getByRole('dialog', { name: /tear down shop here/ })).toContainText(/(platform|compute):delete/)
    await page.keyboard.press('Escape')

    // Gonzalo tears it down: what goes in order, what stays, why, and the name typed
    const stackPath = new URL(page.url()).hash.slice(1)
    await signOut(page)
    await signInAs(page, /Continue as Gonzalo Munoz/, stackPath)
    await page.getByRole('button', { name: 'Tear down' }).click()
    const dialog = page.getByRole('dialog', { name: 'Tear down shop' })
    await expect(dialog.getByRole('list', { name: 'Deleted, in order' }).getByRole('listitem')).toHaveText([/shop-data|Data volume/, /shop-web/])
    await expect(dialog.getByRole('list', { name: 'Kept' })).toContainText('shop-image')
    await expect(dialog.getByLabel(/^Why/)).toBeFocused()
    await dialog.getByRole('button', { name: 'Tear down stack' }).click()
    await expect(dialog.getByText(/at least 10 characters/)).toBeVisible()
    await dialog.getByLabel(/^Why/).fill('the demo is over')
    await dialog.getByLabel(/^Type shop to confirm/).fill('shop')
    await page.screenshot({ path: shot('stack-teardown-desktop'), fullPage: true })
    await expectNoSeriousA11y(page, 'teardown dialog')
    await dialog.getByRole('button', { name: 'Tear down stack' }).click()
    await expect(dialog).toBeHidden()
    await expect(status).toHaveAttribute('data-stack-state', 'deleting')
    await expect(page.locator('[data-step="image"]')).toHaveAttribute('data-step-state', 'retained')
    await expect(status).toHaveAttribute('data-stack-state', 'deleted', { timeout: 60_000 })
    await expect(page.getByText(/Torn down by gonzalo: the demo is over/)).toBeVisible()
    await page.goto('/#/stacks')
    await expect(page.getByRole('table', { name: /^Stacks in/ })).toContainText('Torn down')
    await expectNoSeriousA11y(page, 'stacks list')
    await phone(page, 'stacks', 'stacks list')
  })

  test('a high-risk delete says it needs an approver', async ({ page }) => {
    await signInAs(page, /Continue as Gonzalo Munoz/, vmPage('vm-demo03'))
    await expect(page.getByRole('heading', { name: 'web-02', level: 1 })).toBeVisible()
    await page.locator('[data-verb="delete"]').first().click()
    const drawer = page.getByRole('dialog', { name: 'Delete VM: web-02' })
    await expect(drawer).toBeVisible()
    await drawer.locator('#action-delete-reason').fill('replaced by the new web tier')
    const risk = drawer.getByRole('region', { name: 'Change risk' })
    await expect(risk).toHaveAttribute('data-risk', 'high')
    await expect(risk).toContainText('High risk')
    await expect(risk).toContainText('It needs an approver')
    await expect(risk.getByRole('list', { name: 'Why' })).toContainText(/production/i)
    await page.screenshot({ path: shot('risk-high-delete-desktop'), fullPage: true })
    await expectNoSeriousA11y(page, 'delete drawer with its risk')
  })
})

test.describe('scorecards and event subscriptions in mock mode', () => {
  test('Gonzalo reads the scorecard and fixes a powered-off production machine with the rule’s workflow', async ({ page }) => {
    test.setTimeout(90_000)
    await signInAs(page, /Continue as Gonzalo Munoz/, '/compliance')
    await expect(page.getByRole('heading', { name: 'Scorecard', level: 1 })).toBeVisible()
    await expect(page.getByRole('heading', { name: 'Compute baseline' })).toBeVisible()
    await page.screenshot({ path: shot('scorecard-desktop'), fullPage: true })
    await expectNoSeriousA11y(page, 'scorecard')
    await phone(page, 'scorecard', 'scorecard')

    await page.getByRole('link', { name: 'Organization heat map' }).click()
    const heat = page.getByRole('table', { name: 'Scores by account and pack' })
    await expect(heat.getByRole('rowheader')).toHaveCount(2)
    await page.screenshot({ path: shot('scorecard-heatmap-desktop'), fullPage: true })
    await expectNoSeriousA11y(page, 'heat map')
    await phone(page, 'scorecard-heatmap', 'heat map')
    await page.goBack()

    await page.locator('[data-rule="compute-baseline/prod-powered-on"]').getByRole('link').click()
    await expect(page).toHaveURL(/#\/compliance\/compute-baseline\/prod-powered-on/)
    const row = page.locator('tr', { has: page.locator('a[href*="vm-gen-011"]') })
    await expect(row).toHaveCount(1)
    await expectNoSeriousA11y(page, 'failing resources')
    await row.getByRole('button', { name: 'Remediate' }).click()
    const fix = page.getByRole('dialog', { name: /^Fix / })
    await expect(fix).toContainText('A production VM that is not powered on')
    await fix.getByLabel(/^Reason/).fill('it should be serving traffic')
    await expectNoSeriousA11y(page, 'remediate dialog')
    await fix.getByRole('button', { name: 'Start the fix' }).click()
    await expect(fix).toBeHidden()
    await expect(page.getByText(/request started; the rule passes once it succeeds/)).toBeVisible()
    // the restart is a production change: approved, run, and the machine passes the rule
    await expect(row).toHaveCount(0, { timeout: 45_000 })

    // the resource's own Compliance tab says so
    await page.goto(`/#${vmPage('vm-gen-011')}?tab=compliance`)
    await expect(page.locator('[data-rule="compute-baseline/prod-powered-on"]')).toHaveAttribute('data-rule-status', 'pass')
    await expectNoSeriousA11y(page, 'compliance tab')
  })

  test('a webhook subscription is created, tested and deleted; the address is never a link', async ({ page }) => {
    await signInAs(page, /Continue as Gonzalo Munoz/, '/event-subscriptions')
    await page.getByRole('button', { name: 'Create subscription' }).click()
    const dialog = page.getByRole('dialog', { name: 'Create an event subscription' })
    await expect(dialog.getByLabel(/^Name/)).toBeFocused()
    await dialog.getByLabel(/^Name/).fill('Compute changes')
    await dialog.getByRole('checkbox', { name: 'A resource is ready' }).check()
    await dialog.getByRole('checkbox', { name: 'A resource is deleted' }).check()
    await dialog.getByLabel(/^Address/).fill('http://hooks.example.com/console')
    await dialog.getByRole('button', { name: 'Create subscription' }).click()
    await expect(dialog.getByText('A webhook is an https:// address')).toBeVisible()
    await dialog.getByLabel(/^Address/).fill('https://hooks.example.com/console')
    await dialog.getByLabel(/^Signing secret/).fill('a-long-signing-secret')
    await page.screenshot({ path: shot('subscription-create-desktop'), fullPage: true })
    await expectNoSeriousA11y(page, 'create subscription dialog')
    await dialog.getByRole('button', { name: 'Create subscription' }).click()
    await expect(dialog).toBeHidden()

    const card = page.locator('[data-subscription="Compute changes"]')
    await expect(card).toContainText('https://hooks.example.com/console')
    await expect(card.locator('a')).toHaveCount(0)
    await card.getByRole('button', { name: 'Test' }).click()
    await expect(card.locator('[data-delivery="delivered"]')).toContainText('answered 200')
    await page.screenshot({ path: shot('subscriptions-desktop'), fullPage: true })
    await expectNoSeriousA11y(page, 'event subscriptions')
    await phone(page, 'subscriptions', 'event subscriptions')

    await card.getByRole('button', { name: 'Delete' }).click()
    await page.getByRole('dialog', { name: 'Delete Compute changes?' }).getByRole('button', { name: 'Delete subscription' }).click()
    await expect(card).toHaveCount(0)
  })

  test('Mia reads everything and every change is refused, with why', async ({ page }) => {
    await signInAs(page, /Continue as Mia Chen/, '/blueprints/web-tier')
    await expect(page.getByRole('button', { name: /^Launch/ })).toHaveAttribute('data-denied', /.+/)
    await page.getByRole('button', { name: /^Launch/ }).click()
    const why = page.getByRole('dialog', { name: 'You can’t launch Web tier here' }).or(page.getByRole('dialog', { name: "You can't launch Web tier here" }))
    await expect(why).toContainText('blueprints:launch')
    await page.keyboard.press('Escape')

    await page.goto('/#/compliance/compute-baseline/prod-powered-on')
    await expect(page.getByRole('button', { name: /^Remediate/ }).first()).toHaveAttribute('data-denied', /.+/)

    await page.goto('/#/event-subscriptions')
    await expect(page.getByRole('button', { name: /^Create subscription/ })).toHaveAttribute('data-denied', /.+/)
    await expectNoSeriousA11y(page, 'event subscriptions for a viewer')
  })
})
