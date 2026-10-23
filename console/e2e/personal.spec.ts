import { fileURLToPath } from 'node:url'
import { expect, test, type Page } from '@playwright/test'
import { expectNoHorizontalOverflow, expectNoSeriousA11y, signInAs } from './support'

/*
 * Phase 7, in mock mode: recent visits and favourites kept at the BFF (Dana pins the machines,
 * stars one on its page and one in the list, and finds them on the console home with her visits,
 * after a reload too, and on a phone), and Service Quotas (Dana asks for more machines and a rule
 * approves it at once; she asks beyond the rule and it waits for the platform team; Mia, a
 * viewer, reads it all and may decide nothing; Gonzalo, on the platform team, approves it).
 */

const shots = fileURLToPath(new URL('./screenshots/', import.meta.url))
const shot = (name: string) => `${shots}${name}.png`

const ACCOUNT = '123456789012'
const wfrnOf = (id: string) => `wfrn:wf:compute:global:${ACCOUNT}:vm/${id}`
const vmPage = (id: string) => `/console/compute/vm/${encodeURIComponent(wfrnOf(id))}`

async function signOut(page: Page) {
  await page.goto('/#/settings')
  await page.getByRole('button', { name: 'Sign out' }).click()
  await expect(page).toHaveURL(/#\/signin/)
}

async function onPhone(page: Page, ready: () => Promise<void>) {
  await page.setViewportSize({ width: 390, height: 844 })
  await page.reload()
  await ready()
}

test.describe('recent visits, favourites and Service Quotas in mock mode', () => {
  test('Dana pins the machines, stars a machine on its page and one in the list, and finds them on the home page with her recent visits', async ({ page }) => {
    await signInAs(page, /Continue as Dana Reyes/, '/console/compute/vm')
    await expect(page.getByRole('heading', { name: 'Virtual machines', level: 1 })).toBeVisible()

    // pin the type: a favourite service, kept in her preferences
    const pin = page.getByRole('button', { name: 'Pin Virtual machines' })
    await expect(pin).toHaveAttribute('aria-pressed', 'false')
    await pin.click()
    await expect(pin).toHaveAttribute('aria-pressed', 'true')

    // a star on the first row stays on the star: the row does not open
    const grid = page.getByRole('grid', { name: 'Virtual machines' })
    const rowStar = grid.getByRole('button', { name: /^Favourite / }).first()
    await expect(rowStar).toBeVisible()
    const starredName = ((await rowStar.textContent()) ?? '').replace(/^Favourite /, '').trim()
    await rowStar.click()
    await expect(rowStar).toHaveAttribute('aria-pressed', 'true')
    await expect(page).toHaveURL(/#\/console\/compute\/vm$/)
    await expectNoSeriousA11y(page, 'list with stars')

    // a star on the resource page, from the keyboard
    await page.goto(`/#${vmPage('vm-demo01')}`)
    await expect(page.getByRole('heading', { name: 'web-01', level: 1 })).toBeVisible()
    const star = page.getByRole('button', { name: 'Favourite web-01' })
    await expect(star).toHaveAttribute('aria-pressed', 'false')
    await star.focus()
    await page.keyboard.press('Enter')
    await expect(star).toHaveAttribute('aria-pressed', 'true')
    await page.screenshot({ path: shot('favourite-resource-desktop') })
    await expectNoSeriousA11y(page, 'resource page with its star')

    // the console home reads both panels from the BFF
    await page.goto('/#/')
    const recent = page.getByRole('list', { name: 'Recently visited' })
    await expect(recent.getByRole('link')).toHaveCount(2)
    await expect(recent.getByRole('link').first()).toContainText('web-01')
    await expect(recent.getByRole('link').nth(1)).toContainText('Virtual machines')
    const pinned = page.getByRole('list', { name: 'Pinned resource types' })
    await expect(pinned.getByRole('link', { name: /Virtual machines/ })).toBeVisible()
    const favourites = page.getByRole('list', { name: 'Favourite resources' })
    await expect(favourites.getByRole('link')).toHaveCount(2)
    await expect(favourites.getByRole('link').first()).toContainText('web-01')
    await expect(favourites.getByRole('link', { name: new RegExp(starredName) })).toBeVisible()
    await page.screenshot({ path: shot('home-favourites-desktop'), fullPage: true })
    await expectNoSeriousA11y(page, 'console home with favourites')

    // kept over a reload; unstarring from the home removes it
    await page.reload()
    await expect(page.getByRole('list', { name: 'Favourite resources' }).getByRole('link')).toHaveCount(2)
    await page.getByRole('list', { name: 'Favourite resources' }).getByRole('button', { name: `Favourite ${starredName}` }).click()
    await expect(page.getByRole('list', { name: 'Favourite resources' }).getByRole('link')).toHaveCount(1)

    // the palette's recent places are the same visits
    await page.keyboard.press('ControlOrMeta+k')
    const palette = page.getByRole('dialog', { name: 'Command palette' })
    await expect(palette).toBeVisible()
    await expect(palette.getByRole('option', { name: /web-01/ }).first()).toBeVisible()
    await page.keyboard.press('Escape')
    await expect(palette).toBeHidden()

    // at phone width the panels fit
    await onPhone(page, async () => {
      await expect(page.getByRole('list', { name: 'Favourite resources' })).toBeVisible()
    })
    await expectNoHorizontalOverflow(page)
    await expectNoSeriousA11y(page, 'console home at phone width')
    await page.screenshot({ path: shot('home-favourites-phone'), fullPage: true })

    // the next person on the device sees none of it
    await signOut(page)
    await signInAs(page, /Continue as Mateo Silva/)
    await expect(page.getByText('Nothing visited yet')).toBeVisible()
    await expect(page.getByText('No favourites yet')).toBeVisible()
  })

  test('Dana gets more machines at once under the rule, asks beyond it, Mia reads it all and decides nothing, and Gonzalo approves', async ({ page }) => {
    await signInAs(page, /Continue as Dana Reyes/, '/service-quotas')
    await expect(page.getByRole('heading', { name: 'Service Quotas', level: 1 })).toBeVisible()
    const vms = page.locator('[data-quota="resources.vm"]')
    await expect(vms).toContainText(/Virtual machines: \d+ of 400/)
    await expect(vms.locator('[data-rule]')).toHaveAttribute('data-rule', 'resources-double')
    await expect(vms).toContainText('Raised at once to up to twice the limit, 1,000 at most, once every 30 days.')
    await expectNoSeriousA11y(page, 'Service Quotas')

    // within the rule: approved at once
    await vms.getByRole('button', { name: 'Request increase' }).click()
    await expect(page).toHaveURL(/request=resources\.vm/)
    const ask = page.getByRole('dialog', { name: 'Request more virtual machines' })
    await expect(ask).toBeVisible()
    const limit = ask.getByLabel(/^New limit/)
    await expect(limit).toBeFocused()
    await expect(limit).toHaveValue('800')
    await limit.fill('600')
    await expect(ask.locator('[data-outcome]')).toHaveAttribute('data-outcome', 'once')
    await expect(ask.locator('[data-outcome]')).toContainText('Approved at once by the rule resources-double')
    await ask.getByLabel(/^Why/).fill('The batch farm moves to Compute next week')
    await expectNoSeriousA11y(page, 'request dialog')
    await page.screenshot({ path: shot('quota-request-desktop') })
    await ask.getByRole('button', { name: 'Send request' }).click()
    await expect(ask).toBeHidden()
    await expect(vms).toContainText(/Virtual machines: \d+ of 600/)
    const requests = page.getByRole('list', { name: 'Quota requests' })
    const first = requests.locator('[data-request-status="approved"]').first()
    await expect(first).toContainText('Virtual machines: 400 to 600')
    await expect(first).toContainText('Approved by the rule resources-double, at once')

    // beyond the rule, and within its cooldown: the platform team decides
    await vms.getByRole('button', { name: 'Request increase' }).click()
    await expect(ask).toBeVisible()
    await ask.getByLabel(/^New limit/).fill('3000')
    await expect(ask.locator('[data-outcome]')).toHaveAttribute('data-outcome', 'later')
    await expect(ask.locator('[data-outcome]')).toContainText('the platform team decides')
    await ask.getByLabel(/^Why/).fill('The render farm follows the batch farm in November')
    await ask.getByRole('button', { name: 'Send request' }).click()
    await expect(ask).toBeHidden()
    await expect(vms.locator('[data-quota-pending]')).toContainText('3,000 asked by you, waiting.')
    const open = requests.locator('[data-request-status="open"]')
    await expect(open).toContainText('Waiting for the platform team')
    // nobody decides their own: no decision is offered, and the request says why
    await expect(open.getByRole('button', { name: /^Approve/ })).toHaveCount(0)
    await expect(open.locator('[data-not-offered="decide"]')).toHaveText('Nobody decides their own quota request.')
    await expect(open.getByRole('button', { name: 'Withdraw the request for more virtual machines' })).toBeVisible()
    await page.screenshot({ path: shot('quota-requests-desktop'), fullPage: true })

    // a viewer reads the quotas and the requests, and decides nothing
    await signOut(page)
    await signInAs(page, /Continue as Mia Chen/, '/service-quotas')
    const seen = page.getByRole('list', { name: 'Quota requests' }).locator('[data-request-status="open"]')
    await expect(seen).toContainText('Dana Reyes asked')
    await expect(seen.getByRole('button')).toHaveCount(0)
    await expect(seen.locator('[data-not-offered="decide"]')).toHaveText('The platform team decides the quota requests no rule approves, and you are not on it.')
    // asking stays open to every member, as the identity service's viewer policy allows it
    await expect(page.locator('[data-quota="access-keys"]').getByRole('button', { name: 'Request increase' })).toBeVisible()
    await expectNoSeriousA11y(page, 'Service Quotas as a viewer')
    await onPhone(page, async () => {
      await expect(page.getByRole('list', { name: 'Quota requests' })).toBeVisible()
    })
    await expectNoHorizontalOverflow(page)
    await expectNoSeriousA11y(page, 'Service Quotas at phone width')
    await page.screenshot({ path: shot('service-quotas-viewer-phone'), fullPage: true })
    await page.setViewportSize({ width: 1280, height: 800 })

    // the platform team approves
    await signOut(page)
    await signInAs(page, /Continue as Gonzalo Munoz/, '/service-quotas')
    const waiting = page.getByRole('list', { name: 'Quota requests' }).locator('[data-request-status="open"]')
    await waiting.getByRole('button', { name: /^Approve/ }).click()
    const decide = page.getByRole('dialog', { name: 'Approve more virtual machines' })
    await expect(decide).toBeVisible()
    await expect(decide.getByLabel(/^Limit to grant/)).toHaveValue('3000')
    await decide.getByLabel(/^Note for the requester/).fill('Approved for the render farm')
    await expectNoSeriousA11y(page, 'decide dialog')
    await decide.getByRole('button', { name: 'Approve' }).click()
    await expect(decide).toBeHidden()
    await expect(page.locator('[data-quota="resources.vm"]')).toContainText(/Virtual machines: \d+ of 3,000/)
    const approved = page.getByRole('list', { name: 'Quota requests' }).locator('[data-request-status="approved"]').first()
    await expect(approved).toContainText('Virtual machines: 600 to 3,000')
    await expect(approved).toContainText('Approved by you')
    await expect(approved).toContainText('Approved for the render farm')
    await page.screenshot({ path: shot('service-quotas-approved-desktop'), fullPage: true })
  })
})
