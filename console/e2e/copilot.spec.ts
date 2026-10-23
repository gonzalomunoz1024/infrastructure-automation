import { fileURLToPath } from 'node:url'
import { expect, test, type Page } from '@playwright/test'
import { expectNoHorizontalOverflow, expectNoSeriousA11y, signInAs } from './support'

/*
 * Phase 9's copilot (WP 9.1), in mock mode: plain English from the header's panel to the list it
 * describes, filtered, and to the create wizard, filled; a reading it is not sure of, shown and
 * left for the person to apply; Explain on a resource's page; a failed turn tried again and a
 * 429 that says when; the palette's Ask; the focus going back where it came from; the phone's
 * whole screen. The mock's copilot thinks for 1.2 s and never acts.
 */

const shots = fileURLToPath(new URL('./screenshots/', import.meta.url))
const shot = (name: string) => `${shots}${name}.png`

const ADMIN = /Continue as Gonzalo Munoz/
const ACCOUNT = '123456789012'
const WEB01 = `wfrn:wf:compute:global:${ACCOUNT}:vm/vm-demo01`
const STOPPED_PROD = /#\/console\/compute\/vm\?f=status%3Ddeleted%3Btag\.environment%3Dprod$/

const header = (page: Page) => page.getByRole('banner').getByRole('button', { name: 'Ask the copilot' })
const panel = (page: Page) => page.getByRole('complementary', { name: 'Copilot' })
const box = (page: Page) => panel(page).getByRole('textbox', { name: 'Ask the copilot' })
const said = (page: Page) => panel(page).getByRole('status')

/** Types a question in the panel and sends it with Enter. */
async function ask(page: Page, text: string) {
  await box(page).fill(text)
  await box(page).press('Enter')
  await expect(box(page)).toHaveValue('')
}

test.describe('the copilot in mock mode', () => {
  test('from the header: plain English to a filtered list and a filled wizard, kept across pages, and closed by keyboard', async ({ page }) => {
    await signInAs(page, ADMIN)
    await expect(page.getByRole('heading', { name: 'Console home', level: 1 })).toBeVisible()
    await header(page).click()
    await expect(panel(page)).toBeVisible()
    await expect(header(page)).toHaveAttribute('aria-expanded', 'true')
    await expect(box(page)).toBeFocused()
    // empty, it offers questions that fit the page
    await expect(panel(page).getByRole('button', { name: 'Find stopped virtual machines in prod' })).toBeVisible()
    await expect(panel(page).getByRole('button', { name: 'Create a small VM in dev' })).toBeVisible()
    await expect(panel(page).getByRole('button', { name: 'New conversation' })).toBeDisabled()
    await expectNoSeriousA11y(page, 'copilot, empty')

    // near its 2,000 characters the box says how many are used
    const hint = panel(page).getByText(/Enter sends\. Shift\+Enter adds a line\.$/)
    await expect(hint).toHaveText('Enter sends. Shift+Enter adds a line.')
    await box(page).fill('x'.repeat(1_850))
    await expect(hint).toHaveText('1,850 of 2,000 characters. Enter sends. Shift+Enter adds a line.')
    await expect(box(page)).toHaveAttribute('maxlength', '2000')

    // Shift+Enter is a new line, Enter sends
    await box(page).fill('Find stopped virtual machines')
    await box(page).press('Shift+Enter')
    await box(page).pressSequentially('in prod')
    await expect(box(page)).toHaveValue('Find stopped virtual machines\nin prod')
    await box(page).press('Enter')
    await expect(box(page)).toHaveValue('')
    await expect(panel(page).getByText('Thinking…')).toBeVisible()
    await expect(said(page)).toHaveText('The copilot is thinking.')
    const conversation = panel(page).getByRole('list', { name: 'Conversation' })
    await expect(conversation).toContainText('10 deleted virtual machines tagged environment=prod match right now.')
    await expect(said(page)).toHaveText('The copilot answered: 10 deleted virtual machines tagged environment=prod match right now.')
    const card = conversation.locator('[data-proposal="filter"]')
    await expect(card).toContainText('List the 10 deleted virtual machines tagged environment=prod')
    const chips = card.getByRole('list', { name: 'Filters it applies' }).getByRole('listitem')
    await expect(chips).toHaveText(['Status=deleted', 'Tag: environment=prod'])
    // the reading was sure of itself: nothing says otherwise, and nothing opened on its own
    await expect(card).not.toContainText('Not sure')
    await expect(page).toHaveURL(/#\/$/)
    await expectNoSeriousA11y(page, 'copilot, answered')
    await page.screenshot({ path: shot('copilot-desktop') })

    // the list it describes, filtered as the copilot read it, with the list's own count
    await card.getByRole('link', { name: 'Open the list' }).click()
    await expect(page).toHaveURL(STOPPED_PROD)
    await expect(page.getByText('10 virtual machines match')).toBeVisible()
    await expect(page.getByRole('button', { name: 'Remove filter Status = deleted' })).toBeVisible()
    await expect(page.getByRole('button', { name: 'Remove filter Tag: environment = prod' })).toBeVisible()
    await expect(panel(page)).toBeVisible()
    // the list makes room for the panel beside it
    await expectNoHorizontalOverflow(page)

    // a create: the wizard opens filled with what could be filled, and names what it still needs
    await ask(page, 'Create a small VM in dev')
    const create = conversation.locator('[data-proposal="create"]')
    await expect(create).toContainText('Create a small virtual machine in dev')
    await expect(create.getByRole('list', { name: 'Filled in' }).getByRole('listitem')).toHaveText(['Size=small', 'Environment=dev'])
    await expect(create).toContainText('Still needed: VM name and Image.')
    await expectNoSeriousA11y(page, 'copilot, a form to open')
    await create.getByRole('button', { name: 'Open the form' }).click()
    await expect(page).toHaveURL(/#\/console\/compute\/vm\/create\?prefill=/)
    await expect(page.getByRole('heading', { name: 'Create VM', level: 1 })).toBeVisible()
    await expect(page.getByRole('button', { name: 'Size: small' })).toBeVisible()
    await expect(page.getByRole('button', { name: 'Environment: dev' })).toBeVisible()
    await expect(page.locator('#create-name')).toHaveValue('')
    await expectNoHorizontalOverflow(page)

    // Escape closes it and gives the focus back to the header's button; the conversation stays
    await box(page).focus()
    await page.keyboard.press('Escape')
    await expect(panel(page)).toBeHidden()
    await expect(header(page)).toBeFocused()
    await expect(header(page)).toHaveAttribute('aria-expanded', 'false')
    await page.keyboard.press('Enter')
    await expect(box(page)).toBeFocused()
    await expect(conversation).toContainText('Find stopped virtual machines')
    await expect(conversation).toContainText('Create a small virtual machine in dev')

    // a new conversation starts empty again
    await panel(page).getByRole('button', { name: 'New conversation' }).click()
    await expect(conversation).toHaveCount(0)
    await expect(panel(page).getByText(/^Ask in plain English\. The copilot turns it into/)).toBeVisible()
    await expect(panel(page).getByRole('button', { name: 'Create a small VM in dev' })).toBeVisible()
    await expect(panel(page).getByRole('button', { name: 'New conversation' })).toBeDisabled()

    // a page of the console, named on its own
    await ask(page, 'show me my requests')
    const open = panel(page).locator('[data-proposal="open"]')
    await expect(open).toContainText('Open Requests')
    await open.getByRole('link', { name: 'Open the page' }).click()
    await expect(page).toHaveURL(/#\/requests$/)
    await expect(page.getByRole('heading', { name: 'Requests', level: 1 })).toBeVisible()
  })

  test('from the palette: a reading it is not sure of is shown with what it left out, and applied only when asked', async ({ page }) => {
    await signInAs(page, ADMIN)
    await expect(page.getByRole('heading', { name: 'Console home', level: 1 })).toBeVisible()
    await page.keyboard.press('ControlOrMeta+k')
    const palette = page.getByRole('dialog', { name: 'Command palette' })
    await expect(palette).toBeVisible()
    await page.keyboard.type('stopped machines in prod')
    await palette.getByRole('option', { name: 'Ask the copilot: stopped machines in prod' }).click()
    await expect(palette).toBeHidden()
    await expect(panel(page)).toBeVisible()
    await expect(box(page)).toBeFocused()

    const conversation = panel(page).getByRole('list', { name: 'Conversation' })
    await expect(conversation).toContainText('stopped machines in prod')
    await expect(conversation).toContainText('I am not sure I understood.')
    const card = conversation.locator('[data-proposal="filter"]')
    await expect(card).toHaveAttribute('data-confidence', 'low')
    await expect(card).toContainText('Not sure this is what you meant')
    await expect(card.getByRole('list', { name: 'Filters it applies' }).getByRole('listitem')).toHaveText(['Status=deleted', 'Tag: environment=prod'])
    await expect(card).toContainText('Left out: “machines”.')
    // it waits: the page is where it was
    await expect(page).toHaveURL(/#\/$/)
    await expectNoSeriousA11y(page, 'copilot, not sure')

    // in the dark
    await page.getByRole('button', { name: 'Switch to dark mode' }).click()
    await expect(page.locator('html')).toHaveClass(/dark/)
    await expectNoSeriousA11y(page, 'copilot, not sure, dark')
    await page.screenshot({ path: shot('copilot-dark-desktop') })
    await page.getByRole('button', { name: 'Switch to light mode' }).click()
    await expect(page.locator('html')).not.toHaveClass(/dark/)

    await card.getByRole('link', { name: 'Apply this filter' }).click()
    await expect(page).toHaveURL(STOPPED_PROD)
    await expect(page.getByText('10 virtual machines match')).toBeVisible()
  })

  test('Explain on a resource page: what it is, what it costs, where it came from, and the focus back on Explain', async ({ page }) => {
    await signInAs(page, ADMIN, `/console/compute/vm/${encodeURIComponent(WEB01)}`)
    await expect(page.getByRole('heading', { name: 'web-01', level: 1 })).toBeVisible()
    const explain = page.getByRole('button', { name: 'Explain web-01' })
    await explain.click()
    await expect(panel(page)).toBeVisible()
    await expect(box(page)).toBeFocused()
    const conversation = panel(page).getByRole('list', { name: 'Conversation' })
    await expect(conversation).toContainText('Explain web-01')
    const card = conversation.getByRole('region', { name: 'web-01' })
    await expect(card).toContainText('web-01 is a virtual machine in Compute.')
    await expect(card).toContainText('Monthly cost$77.00')
    await expect(card).toContainText('Dependents2 resources')
    await expect(card).toContainText('DriftIn sync')
    await expect(card).toContainText('by create-vm 2.0.1')
    await expect(card).toContainText('Costs are estimates from list prices, not invoices.')
    for (const section of ['What it is', 'Configuration', 'Cost', 'History', 'Dependents', 'Drift']) await expect(card.getByRole('heading', { name: section, level: 4 })).toBeVisible()
    await expect(card.getByRole('link', { name: 'Open its page' })).toHaveAttribute('href', `#/console/compute/vm/${encodeURIComponent(WEB01)}`)
    // the page makes room beside the panel: its actions are still in reach
    await expect(explain).toBeInViewport()
    await expect(page.getByRole('button', { name: /^Actions/ })).toBeInViewport()
    await expectNoSeriousA11y(page, 'copilot, explanation')
    await page.screenshot({ path: shot('copilot-explain-desktop') })

    // an action: its drawer opens filled, and runs only from there, after its preview
    await ask(page, 'Patch web-01 for CVE-2026-1234')
    const patch = conversation.locator('[data-proposal="action"]')
    await expect(patch).toContainText('Patch web-01 for CVE-2026-1234')
    await expect(patch.getByRole('list', { name: 'Filled in' }).getByRole('listitem')).toHaveText(['CVE=CVE-2026-1234'])
    const openAction = patch.getByRole('button', { name: 'Open the action' })
    await openAction.click()
    await expect(page).toHaveURL(/action=patch&prefill=/)
    const drawer = page.getByRole('dialog', { name: 'Patch CVE: web-01' })
    await expect(drawer).toBeVisible()
    await expect(drawer.locator('#action-patch-cve')).toHaveValue('CVE-2026-1234')
    await page.keyboard.press('Escape')
    await expect(drawer).toBeHidden()
    await expect(openAction).toBeFocused()

    // Escape in the panel closes it and gives the focus back to Explain
    await page.keyboard.press('Escape')
    await expect(panel(page)).toBeHidden()
    await expect(explain).toBeFocused()
  })

  test('a failed turn says why and asks again; ten questions a minute, and the 429 says when to ask again', async ({ page }) => {
    await signInAs(page, ADMIN, '/console/compute/vm')
    await expect(page.getByRole('heading', { name: 'Virtual machines', level: 1 })).toBeVisible()
    await header(page).click()
    // on a list, the question about the list
    await expect(panel(page).getByRole('button', { name: 'Which of these drifted?' })).toBeVisible()

    await ask(page, 'fail the copilot and find stopped virtual machines in prod')
    const conversation = panel(page).getByRole('list', { name: 'Conversation' })
    await expect(conversation).toContainText('The copilot is not answering right now.')
    await expect(said(page)).toHaveText('The copilot could not answer. The copilot is not answering right now.')
    await expectNoSeriousA11y(page, 'copilot, failed turn')
    await panel(page).getByRole('button', { name: 'Try again' }).click()
    await expect(conversation).toContainText('10 deleted virtual machines tagged environment=prod match right now.')
    await expect(panel(page).getByRole('button', { name: 'Try again' })).toHaveCount(0)

    // the rest of this minute's questions, asked straight to the mock's BFF
    const statuses = await page.evaluate(() => {
      const mock = (window as unknown as { __consoleMock: { handle: (req: { method: string; url: string; headers: Record<string, string>; body?: unknown }) => { status: number } } }).__consoleMock
      const out: number[] = []
      for (let i = 0; i < 10; i++) {
        const res = mock.handle({ method: 'POST', url: '/console/v1/assistant/turns', headers: { 'x-csrf-token': 'mock-csrf-gonzalo' }, body: { intent: 'ask', message: 'help' } })
        out.push(res.status)
        if (res.status === 429) break
      }
      return out
    })
    expect(statuses.at(-1), 'the minute is used up').toBe(429)
    await ask(page, 'Which of these drifted?')
    await expect(conversation).toContainText(/The copilot takes 10 questions a minute\. Try again in \d+ seconds\./)
    await expect(said(page)).toHaveText(/^The copilot takes 10 questions a minute\. Try again in \d+ seconds\.$/)
    await expect(panel(page).getByRole('button', { name: 'Try again' })).toBeDisabled()
    await expectNoSeriousA11y(page, 'copilot, too many questions')
  })

  test('at phone width it covers the screen, and opening what it proposes closes it', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 })
    await signInAs(page, ADMIN)
    await header(page).click()
    await expect(panel(page)).toBeVisible()
    const shape = await panel(page).boundingBox()
    expect(shape, 'the whole screen').toEqual({ x: 0, y: 0, width: 390, height: 844 })
    await panel(page).getByRole('button', { name: 'Find stopped virtual machines in prod' }).click()
    const card = panel(page).locator('[data-proposal="filter"]')
    await expect(card).toContainText('List the 10 deleted virtual machines tagged environment=prod')
    await expectNoSeriousA11y(page, 'copilot, phone')
    await page.screenshot({ path: shot('copilot-phone') })
    // covering the screen, it keeps Tab: nothing focused hides underneath it
    await box(page).focus()
    await page.keyboard.press('Tab')
    await expect(panel(page).getByRole('button', { name: 'New conversation' })).toBeFocused()
    await page.keyboard.press('Shift+Tab')
    await expect(box(page)).toBeFocused()
    await card.getByRole('link', { name: 'Open the list' }).click()
    await expect(panel(page)).toBeHidden()
    await expect(page).toHaveURL(STOPPED_PROD)
    await expect(header(page)).toBeFocused()
    await expect(page.getByText('10 virtual machines match')).toBeVisible()
    await expectNoHorizontalOverflow(page)
  })
})
