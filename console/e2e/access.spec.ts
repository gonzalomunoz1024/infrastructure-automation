import { fileURLToPath } from 'node:url'
import { expect, test, type Page } from '@playwright/test'
import { expectNoHorizontalOverflow, expectNoSeriousA11y, signInAs } from './support'

/*
 * Access over time and the account's life (WP 9.11, 9.12 and 7.7), in mock mode. Gonzalo starts
 * an access review of the Data account and keeps two accesses with the keyboard; Raj, an operator
 * there, holds Admin for an hour by rule, finds the review in the bell and removes Ana's redundant
 * viewer access with a reason, but not his own. Dana is refused a delete, asks for Admin for an
 * hour from the refusal, the rule approves it, the delete unlocks marked elevated, and she ends
 * it. Mia asks for Operator, which no rule covers, Gonzalo approves it from the bell and later
 * ends it. Gonzalo, an organization admin, gives emergency access with its warning and its audit
 * line, and is refused closing an account that still holds resources. Axe on every page and
 * dialog, and the pages at phone width.
 */

const shots = fileURLToPath(new URL('./screenshots/', import.meta.url))
const shot = (name: string) => `${shots}${name}.png`
const DATA = '210987654321'

async function signOut(page: Page) {
  await page.goto('/#/settings')
  await page.getByRole('button', { name: 'Sign out' }).click()
  await expect(page).toHaveURL(/#\/signin/)
}

async function openFirstVm(page: Page) {
  await page.goto('/#/console/compute/vm')
  const grid = page.getByRole('grid', { name: 'Virtual machines' })
  const row = grid.getByRole('row').nth(1)
  await expect(row).toBeVisible()
  await row
    .getByRole('gridcell')
    .filter({ hasNotText: /Copy wfrn|Copied|^Select / })
    .first()
    .click()
  await expect(page).toHaveURL(/#\/console\/compute\/vm\/wfrn%3A/)
}

test.describe('access over time in mock mode', () => {
  test('Gonzalo runs an access review and Raj, holding Admin for an hour, removes one access', async ({ page }) => {
    await signInAs(page, /Continue as Gonzalo Munoz/, `/account/${DATA}/iam/access-reviews`)
    await expect(page.getByRole('heading', { name: 'Access reviews', level: 1 })).toBeVisible()
    await expect(page.getByRole('button', { name: /^Account: Data / })).toBeVisible()
    await expect(page.getByText('No access review yet')).toBeVisible()
    await page.getByRole('button', { name: 'Start a review' }).click()

    const start = page.getByRole('dialog', { name: 'Start an access review' })
    await expect(start).toBeVisible()
    await start.getByLabel('Name').fill('Data, autumn review')
    await expect(start.getByText(/Everyone with a group in Data: 3 people/)).toBeVisible()
    await expect(start.locator('[data-expiry-rule]')).toContainText('expires: the person loses that group')
    await page.screenshot({ path: shot('access-review-start-desktop'), fullPage: true })
    await expectNoSeriousA11y(page, 'start access review dialog')
    await start.getByRole('button', { name: 'Start the review' }).click()

    // the campaign: progress, the due date's rule in plain words, and what is left to review
    await expect(page).toHaveURL(/#\/iam\/access-reviews\/arev_/)
    await expect(page.getByRole('heading', { level: 1 })).toContainText('Data, autumn review')
    await expect(page.locator('[data-review-progress="0/5"]')).toBeVisible()
    await expect(page.locator('[data-expiry-rule]')).toContainText(/Anything not reviewed by .* expires/)
    // nobody reviews their own access
    await expect(page.locator('[data-item="gonzalo/owners"]')).toContainText('Someone else reviews your own access')

    // the keyboard: K keeps the person in focus, arrows move between people
    const rajMembers = page.locator('[data-item="raj/members"]')
    await rajMembers.getByRole('button', { name: 'Keep' }).focus()
    await page.keyboard.press('k')
    const keep = page.getByRole('dialog', { name: /^Keep Raj Patel in members/ })
    await expect(keep).toBeVisible()
    await keep.getByLabel('Reason').fill('On the data on-call rotation')
    await expectNoSeriousA11y(page, 'keep access dialog')
    await keep.getByRole('button', { name: 'Keep access' }).click()
    await expect(keep).toBeHidden()
    await expect(page.locator('[data-review-progress="1/5"]')).toBeVisible()
    await page.locator('[data-item="ana/viewers"]').getByRole('button', { name: 'Keep' }).focus()
    await page.keyboard.press('ArrowUp')
    await expect(page.locator('[data-item="ana/members"]').getByRole('button', { name: 'Keep' })).toBeFocused()
    await page.keyboard.press('k')
    const keepAna = page.getByRole('dialog', { name: /^Keep Ana Costa in members/ })
    await keepAna.getByRole('button', { name: 'Keep access' }).click()
    await expect(keepAna).toBeHidden()
    await expect(page.locator('[data-review-progress="2/5"]')).toBeVisible()
    await page.screenshot({ path: shot('access-review-campaign-desktop'), fullPage: true })
    await expectNoSeriousA11y(page, 'access review campaign')
    await signOut(page)

    // Raj, an operator of Data, may not see the reviews; he holds Admin for an hour by the rule
    await signInAs(page, /Continue as Raj Patel/, '/iam/access-reviews')
    await expect(page.getByText('Only admins and auditors see access reviews')).toBeVisible()
    await page.goto('/#/iam/elevations')
    await expect(page.getByRole('heading', { name: 'Elevations', level: 1 })).toBeVisible()
    await expect(page.locator('[data-approval="rule"]')).toContainText('Approved at once by the rule operators-admin-two-hours')
    await page.getByLabel('Why you need it').fill('Working through the autumn access review')
    await page.getByRole('button', { name: 'Ask for Admin' }).click()
    await expect(page.locator('[data-held-roles="1"]')).toContainText('You hold Admin in Data for a while')
    await expect(page.locator('header [data-role="account-admin"]')).toBeVisible()

    // the review waits in his bell
    const bell = page.getByRole('button', { name: 'Notifications, 1 to decide' })
    await bell.click()
    const panel = page.getByRole('dialog', { name: 'Notifications, 1 to decide' })
    await expect(panel.getByRole('list', { name: 'Access reviews to work through' })).toContainText('Data, autumn review: 2 to review')
    await page.screenshot({ path: shot('access-review-bell-desktop') })
    await expectNoSeriousA11y(page, 'bell with an access review')
    await panel.getByRole('link', { name: /Data, autumn review/ }).click()
    await expect(page).toHaveURL(/#\/iam\/access-reviews\/arev_/)

    // his own accesses are someone else's to review; Ana's viewer access is redundant
    await expect(page.locator('[data-item="raj/members"]')).toHaveCount(0)
    const anaViewer = page.locator('[data-item="ana/viewers"]')
    await anaViewer.getByRole('checkbox').check()
    await page.getByRole('button', { name: 'Remove selected' }).click()
    const remove = page.getByRole('dialog', { name: /^Remove Ana Costa in viewers/ })
    await expect(remove.getByRole('button', { name: 'Remove access' })).toBeDisabled()
    await remove.getByLabel('Reason').fill('Ana operates in Data; the viewer group adds nothing')
    await expectNoSeriousA11y(page, 'remove access dialog')
    await remove.getByRole('button', { name: 'Remove access' }).click()
    await expect(remove).toBeHidden()
    await expect(page.locator('[data-review-progress="3/5"]')).toBeVisible()
    await page.getByRole('tab', { name: /All/ }).click()
    await expect(page.locator('[data-item="ana/viewers"]')).toContainText('Removed')
    await expect(page.locator('[data-item="ana/viewers"]')).toContainText('by Raj Patel')
    await page.screenshot({ path: shot('access-review-removed-desktop'), fullPage: true })

    // at phone width
    await page.setViewportSize({ width: 390, height: 844 })
    await expectNoHorizontalOverflow(page)
    await page.screenshot({ path: shot('access-review-phone'), fullPage: true })
    await expectNoSeriousA11y(page, 'access review at phone width')
  })

  test('Dana asks for Admin for an hour from a refusal, the rule approves it, her delete unlocks, and she ends it', async ({ page }) => {
    await signInAs(page, /Continue as Dana Reyes/)
    await openFirstVm(page)
    const del = page.getByRole('button', { name: /^Delete VM/ })
    await expect(del).toHaveAttribute('data-denied', 'console-iam')
    await del.click()
    const refusal = page.getByRole('dialog', { name: /You can.t delete VM here/ })
    await expect(refusal.getByRole('heading', { name: 'Ask for this role for an hour' })).toBeVisible()
    await expect(refusal.getByText(/approved at once for an Operator by the rule operators-admin-two-hours/)).toBeVisible()
    await refusal.locator('#elevate-reason').fill('Clearing the load-test machines from Tuesday')
    await page.screenshot({ path: shot('refusal-elevate-desktop'), fullPage: true })
    await expectNoSeriousA11y(page, 'refusal with the elevation offer')
    await refusal.getByRole('button', { name: 'Ask for Admin for an hour' }).click()
    await expect(refusal.locator('[data-elevation="active"]')).toContainText(/You hold Admin until \d\d:\d\d/)
    await page.keyboard.press('Escape')
    await expect(refusal).toBeHidden()

    // the delete is unlocked, marked as allowed only by the role held for a while
    const unlocked = page.getByRole('button', { name: /^Delete VM/ })
    await expect(unlocked).not.toHaveAttribute('data-denied')
    await expect(unlocked.locator('[data-elevated]')).toBeVisible()
    await expect(page.locator('[data-held-roles="1"]')).toContainText(/You hold Admin in Compute for a while: (1 hour|59 minutes) left/)
    await page.screenshot({ path: shot('elevated-delete-desktop'), fullPage: true })
    await expectNoSeriousA11y(page, 'resource with a held role')

    // she ends it on the elevations page, and the delete locks again
    await page.locator('[data-held-roles]').getByRole('link', { name: 'Elevations' }).click()
    const card = page.locator('[data-elevation-card][data-status="active"]')
    await expect(card).toContainText('approved by the rule operators-admin-two-hours')
    await expect(card.locator('[data-time-left]')).toContainText('left, until')
    await page.screenshot({ path: shot('elevations-active-desktop'), fullPage: true })
    await expectNoSeriousA11y(page, 'elevations page')
    await card.getByRole('button', { name: 'End it now' }).click()
    await expect(page.locator('[data-elevation-card][data-status="revoked"]')).toBeVisible()
    await expect(page.locator('[data-held-roles]')).toHaveCount(0)
    await openFirstVm(page)
    await expect(page.getByRole('button', { name: /^Delete VM/ })).toHaveAttribute('data-denied', 'console-iam')

    await page.setViewportSize({ width: 390, height: 844 })
    await page.goto('/#/iam/elevations')
    await expect(page.getByRole('heading', { name: 'Elevations', level: 1 })).toBeVisible()
    await expectNoHorizontalOverflow(page)
    await page.screenshot({ path: shot('elevations-phone'), fullPage: true })
    await expectNoSeriousA11y(page, 'elevations at phone width')
  })

  test('Mia asks for Operator, which no rule covers; Gonzalo approves it from the bell and ends it', async ({ page }) => {
    await signInAs(page, /Continue as Mia Chen/, '/iam/elevations?role=operator')
    await expect(page.locator('[data-approval="person"]')).toContainText('An admin of Compute who is not you decides')
    await page.getByLabel('Why you need it').fill('Patching the payments machines tonight')
    await page.getByRole('button', { name: 'Ask for Operator' }).click()
    await expect(page.locator('[data-elevation-card][data-status="pending"]')).toContainText('Waiting for Gonzalo Munoz')
    await signOut(page)

    await signInAs(page, /Continue as Gonzalo Munoz/)
    const bell = page.getByRole('button', { name: 'Notifications, 1 to decide' })
    await bell.click()
    const panel = page.getByRole('dialog', { name: 'Notifications, 1 to decide' })
    await panel.getByRole('link', { name: /Mia Chen asks for Operator for 1 hour/ }).click()
    await expect(page).toHaveURL(/#\/iam\/elevations$/)
    const ask = page.locator('[data-elevation-card][data-status="pending"]')
    await ask.getByLabel('Comment').fill('Fine for tonight')
    await expectNoSeriousA11y(page, 'elevation to decide')
    await ask.locator('[data-decide="approve"]').click()
    await expect(page.getByRole('button', { name: 'Notifications', exact: true })).toBeVisible()
    await signOut(page)

    // Mia may create now, by the role she holds for a while
    await signInAs(page, /Continue as Mia Chen/, '/console/compute/vm')
    const create = page.getByRole('button', { name: /^Create (VM|virtual machine)/i }).first()
    await expect(create).not.toHaveAttribute('data-denied')
    await expect(create.locator('[data-elevated]')).toBeVisible()
    await signOut(page)

    // an admin ends someone else's elevation from the account's list
    await signInAs(page, /Continue as Gonzalo Munoz/, '/iam/elevations')
    await page.getByRole('tab', { name: 'Everything in the account' }).click()
    const held = page.locator('[data-elevation-card][data-status="active"]')
    await expect(held).toContainText('Mia Chen holds')
    await held.getByRole('button', { name: 'End it now' }).click()
    await expect(page.locator('[data-elevation-card][data-status="revoked"]')).toContainText('Ended')
  })

  test('an organization admin gives emergency access with its warning, and may not close an account that holds resources', async ({ page }) => {
    await signInAs(page, /Continue as Gonzalo Munoz/, '/organization/accounts')
    await expect(page.getByRole('heading', { name: 'Accounts', level: 1 })).toBeVisible()
    const data = page.locator(`[data-account="${DATA}"]`)
    await expect(data).toContainText('2109-8765-4321')
    await page.screenshot({ path: shot('organization-accounts-desktop'), fullPage: true })
    await expectNoSeriousA11y(page, 'organization accounts')

    // break-glass: loud, approved by the admin giving it, and on the account's own trail
    await page.locator('[data-account="123456789012"]').getByRole('button', { name: 'Give emergency access' }).click()
    const glass = page.getByRole('dialog', { name: 'Emergency access to Compute' })
    await expect(glass.locator('[data-break-glass-warning]')).toContainText('This bypasses Compute’s own approvers')
    await glass.locator('#glass-person').fill('mia@example.com')
    await expect(glass.getByText('Mia Chen, a member of Compute')).toBeVisible()
    await glass.getByLabel('Incident or support ticket').fill('INC-4521')
    await glass.getByLabel('Reason').fill('Restore the payments volumes after the storage incident')
    await expect(glass.getByRole('button', { name: 'Give emergency access' })).toBeDisabled()
    await glass.getByLabel('I approve this emergency access').check()
    await expectNoSeriousA11y(page, 'break-glass dialog')
    await glass.getByRole('button', { name: 'Give emergency access' }).click()
    await expect(glass.locator('[data-audit-line]')).toContainText(/Gonzalo Munoz gave Mia Chen Admin by emergency access from \d\d:\d\d to \d\d:\d\d, ticket INC-4521/)
    await page.screenshot({ path: shot('break-glass-desktop'), fullPage: true })
    await glass.getByRole('button', { name: 'Done' }).click()
    await page.goto('/#/iam/elevations')
    await page.getByRole('tab', { name: 'Everything in the account' }).click()
    await expect(page.locator('[data-elevation-card]').first()).toContainText('Break-glass')
    await expect(page.locator('[data-elevation-card]').first()).toContainText('On Compute’s own trail: emergency access, given by Gonzalo Munoz')

    // closing an account that holds resources is refused, naming what it holds
    await page.goto('/#/organization/accounts')
    await data.getByRole('button', { name: 'Close account' }).click()
    const close = page.getByRole('dialog', { name: 'Close Data' })
    await expect(close.getByText('Closing cannot be undone', { exact: false })).toBeVisible()
    await close.getByLabel('Reason').fill('The data team moved into Compute')
    await expect(close.getByRole('button', { name: 'Close the account' })).toBeDisabled()
    await close.getByLabel('Type Data to confirm').fill('Data')
    await close.getByRole('button', { name: 'Close the account' }).click()
    await expect(close.locator('[data-close-refused]')).toContainText(/Data still holds [\d,]+ resources, so it cannot be closed/)
    await expect(close.locator('[data-close-refused]')).toContainText('Delete them, or move them to another account')
    await page.screenshot({ path: shot('close-account-refused-desktop'), fullPage: true })
    await expectNoSeriousA11y(page, 'close account refused')
    await close.getByRole('button', { name: 'Cancel' }).click()
    await expect(data).toContainText('Active')

    await page.setViewportSize({ width: 390, height: 844 })
    await page.goto('/#/organization/accounts')
    await expect(page.getByRole('heading', { name: 'Accounts', level: 1 })).toBeVisible()
    await expectNoHorizontalOverflow(page)
    await page.screenshot({ path: shot('organization-accounts-phone'), fullPage: true })
  })

  test('someone who is not an organization admin is told the accounts page is not theirs', async ({ page }) => {
    await signInAs(page, /Continue as Dana Reyes/, '/organization/accounts')
    await expect(page.getByText('For the organization’s admins')).toBeVisible()
    await expectNoSeriousA11y(page, 'accounts page for someone else')
  })
})
