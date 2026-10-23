import { fileURLToPath } from 'node:url'
import { expect, test, type Page } from '@playwright/test'
import { expectNoHorizontalOverflow, expectNoSeriousA11y, signInAs } from './support'

/*
 * Gate 1, in mock mode: sign in through the (mock) identity provider, pick an account, meet a
 * refusal that names the deciding layer and what decided, request access from it, have an
 * approver of the account approve it from the bell, and find the action unlocked. Then an
 * operator refused a delete that no statement allows (the managed policies carve deletes out of
 * the operator's allow), the platform layer refusing an account that lacks the scope, the admin
 * switching accounts from the header, and the identity and access pages, with axe on each. The
 * people, roles and groups are the identity service's demo seed.
 */

const shots = fileURLToPath(new URL('./screenshots/', import.meta.url))
const shot = (name: string) => `${shots}${name}.png`

async function signOut(page: Page) {
  await page.goto('/#/settings')
  await page.getByRole('button', { name: 'Sign out' }).click()
  await expect(page).toHaveURL(/#\/signin/)
}

const createButton = (page: Page) => page.getByRole('button', { name: /^Create (VM|virtual machine)/i }).first()

test.describe('identity and access in mock mode', () => {
  test('a viewer is refused with the layer shown, asks for access, the admin approves from the bell, and the action unlocks', async ({ page }) => {
    // the door
    await page.goto('/')
    await expect(page).toHaveURL(/#\/signin/)
    await expect(page.getByRole('heading', { name: 'Demo identity provider' })).toBeVisible()
    await page.screenshot({ path: shot('signin-desktop'), fullPage: true })
    await expectNoSeriousA11y(page, 'sign-in page')

    // mia holds viewer in Compute (and is one of its approvers)
    await signInAs(page, /Continue as Mia Chen/, '/console/compute/vm')
    await expect(page.getByRole('heading', { name: 'Virtual machines', level: 1 })).toBeVisible()
    await expect(page.locator('header [data-role="viewer"]')).toBeVisible()
    const create = createButton(page)
    await expect(create).toHaveAttribute('data-denied', 'console-iam')
    await create.click()

    // the refusal names the layer and says what no statement allows
    const refusal = page.getByRole('dialog', { name: /You can.t create VM here/ })
    await expect(refusal).toBeVisible()
    await expect(refusal.getByText('Your role does not allow compute:create')).toBeVisible()
    await expect(page.locator('[data-refusal="console-iam"]')).toBeAttached()
    await expect(refusal.getByRole('heading', { name: 'Who decided' })).toBeVisible()
    await expect(refusal.locator('[data-layer-verdict="denied"]')).toHaveCount(1)
    await expect(refusal.getByText('decided here')).toBeVisible()
    await expect(refusal.getByText(/No statement of the role viewer allows it/)).toBeVisible()
    await page.screenshot({ path: shot('refusal-viewer-desktop'), fullPage: true })
    await expectNoSeriousA11y(page, 'refusal')

    // Request access, from the refusal itself
    await refusal.locator('#request-reason').fill('I build the payments team machines and need to create them myself')
    await refusal.getByRole('button', { name: 'Send request' }).click()
    const sent = refusal.locator('[data-request-sent]')
    await expect(sent).toBeVisible()
    // the account's approvers, but never the requester: the admins and the compute-approvers group
    await expect(sent).toContainText('Request sent to Dana Reyes, Gonzalo Munoz')
    const requestId = await sent.getAttribute('data-request-sent')
    expect(requestId).toMatch(/^areq_/)
    await page.screenshot({ path: shot('refusal-request-sent-desktop'), fullPage: true })
    await page.keyboard.press('Escape')
    await expect(refusal).toBeHidden()

    // the requester follows it on the access requests page
    await page.goto('/#/iam/access-requests')
    await expect(page.getByRole('table', { name: 'Access requests' }).getByText(requestId ?? 'areq_')).toBeVisible()

    // the admin sees it in the bell and approves it
    await signOut(page)
    await signInAs(page, /Continue as Gonzalo Munoz/)
    await expect(page.getByRole('heading', { name: 'Console home', level: 1 })).toBeVisible()
    await expect(page.locator('header [data-role="account-admin"]')).toBeVisible()
    const bell = page.getByRole('button', { name: 'Notifications, 1 to decide' })
    await expect(bell).toBeVisible()
    await bell.click()
    const panel = page.getByRole('dialog', { name: 'Notifications, 1 to decide' })
    await expect(panel).toBeVisible()
    await page.screenshot({ path: shot('bell-desktop') })
    await panel.getByRole('link', { name: /Mia Chen asks for Operator/ }).click()
    await expect(page).toHaveURL(new RegExp(`#/iam/access-requests/${requestId}$`))
    await expect(page.getByRole('heading', { level: 1 })).toContainText('Mia Chen asks for')
    await expect(page.locator('[data-status="pending"]').first()).toBeVisible()
    await page.getByLabel('Comment').fill('Welcome to the members group')
    await expectNoSeriousA11y(page, 'access request')
    await page.locator('[data-decide="approve"]').click()
    await expect(page.locator('[data-status="approved"]').first()).toBeVisible()
    await expect(page.getByText('Welcome to the members group')).toBeVisible()
    await page.screenshot({ path: shot('access-request-approved-desktop'), fullPage: true })
    await expect(page.getByRole('button', { name: 'Notifications, 1 to decide' })).toBeHidden()
    await expect(page.getByRole('button', { name: 'Notifications', exact: true })).toBeVisible()

    // mia now holds operator and may create
    await signOut(page)
    await signInAs(page, /Continue as Mia Chen/, '/console/compute/vm')
    await expect(page.locator('header [data-role="operator"]')).toBeVisible()
    const unlocked = createButton(page)
    await expect(unlocked).not.toHaveAttribute('data-denied')
    // phase 3 builds the create wizard: the unlocked button is live and opens it
    await expect(unlocked).not.toHaveAttribute('data-phase')
    await page.goto(`/#/iam/access-requests/${requestId}`)
    await expect(page.getByText(/You now hold Operator/)).toBeVisible()
  })

  test('an operator is refused a delete that no statement allows, and reads why on the role', async ({ page }) => {
    await signInAs(page, /Continue as Dana Reyes/, '/console/compute/vm')
    await expect(createButton(page)).not.toHaveAttribute('data-phase')
    await expect(createButton(page)).not.toHaveAttribute('data-denied')
    const grid = page.getByRole('grid', { name: 'Virtual machines' })
    const row = grid.getByRole('row').nth(1)
    await expect(row).toBeVisible()
    // the name cell: the row's centre falls in the wfrn cell, where the Copy button keeps the click
    await row.getByRole('gridcell').filter({ hasNotText: /Copy wfrn|Copied|^Select / }).first().click()
    await expect(page).toHaveURL(/#\/console\/compute\/vm\/wfrn%3A/)
    const del = page.getByRole('button', { name: /^Delete VM/ })
    await expect(del).toHaveAttribute('data-denied', 'console-iam')
    await del.click()
    const refusal = page.getByRole('dialog', { name: /You can.t delete VM here/ })
    await expect(refusal).toBeVisible()
    await expect(refusal.getByText('Your role does not allow compute:delete')).toBeVisible()
    // the operator's allow carves deletes out, so nothing allows it: a default deny, which names the role
    await expect(refusal.getByText(/No statement of the role operator allows it, and what no statement allows is denied/)).toBeVisible()
    await expect(refusal.getByRole('link', { name: 'operator' })).toBeVisible()
    await page.screenshot({ path: shot('refusal-statement-desktop'), fullPage: true })
    await expectNoSeriousA11y(page, 'statement refusal')
    // the statements, in full, on the role's page: the allow, and the deletes it leaves out
    await refusal.getByRole('link', { name: 'operator' }).click()
    await expect(page).toHaveURL(/#\/iam\/roles\/operator$/)
    const policy = page.locator('[data-policy="managed-operator"]')
    await expect(policy).toContainText('changes')
    await expect(policy.locator('[data-not-actions]').first()).toContainText('*:delete')
    await expectNoSeriousA11y(page, 'role')
  })

  test('the platform refuses an account whose service account lacks the scope', async ({ page }) => {
    await signInAs(page, /Continue as Raj Patel/, '/console/compute/vm')
    await expect(page.getByRole('button', { name: /^Account: Data / })).toBeVisible()
    const create = createButton(page)
    await expect(create).toHaveAttribute('data-denied', 'platform')
    await create.click()
    const refusal = page.getByRole('dialog', { name: /You can.t create VM here/ })
    await expect(refusal).toBeVisible()
    await expect(refusal.getByText('The platform does not allow compute:create in this account')).toBeVisible()
    await expect(page.locator('[data-refusal="platform"]')).toBeAttached()
    await expect(refusal.getByText(/no compute:write scope/)).toBeVisible()
    await expect(refusal.getByText('svc-data')).toBeVisible()
    await page.screenshot({ path: shot('refusal-platform-desktop'), fullPage: true })
    await expectNoSeriousA11y(page, 'platform refusal')
  })

  test('the admin runs identity and access: users, groups, roles, access keys, and switches account', async ({ page }) => {
    await signInAs(page, /Continue as Gonzalo Munoz/, '/iam')
    await expect(page.getByRole('heading', { name: 'Identity and access', level: 1 })).toBeVisible()
    await expect(page.getByText(/Request access sends it to the account’s approvers: its admins \(Gonzalo Munoz\)/)).toBeVisible()
    await page.screenshot({ path: shot('iam-overview-desktop'), fullPage: true })
    await expectNoSeriousA11y(page, 'IAM overview')

    // users
    await page.getByRole('navigation', { name: 'Identity and access pages' }).getByRole('link', { name: 'Users' }).click()
    await expect(page).toHaveURL(/#\/iam\/users$/)
    const users = page.getByRole('table', { name: /^Users of Compute/ })
    await expect(users.getByRole('link', { name: 'Mia Chen' })).toBeVisible()
    await page.getByLabel('Filter users').fill('dana')
    await expect(users.getByRole('link', { name: 'Dana Reyes' })).toBeVisible()
    await expect(users.getByRole('link', { name: 'Mia Chen' })).toBeHidden()
    await page.getByLabel('Filter users').fill('')
    await page.screenshot({ path: shot('iam-users-desktop'), fullPage: true })
    await expectNoSeriousA11y(page, 'users')
    await users.getByRole('link', { name: 'Mia Chen' }).click()
    await expect(page).toHaveURL(/#\/iam\/users\/mia$/)
    await expect(page.getByRole('heading', { level: 1 })).toContainText('Mia Chen')
    await expect(page.getByRole('group', { name: 'Member of' })).toBeVisible()
    await expectNoSeriousA11y(page, 'user')

    // groups: the managed ones and the approvers; create one, see it, delete it
    await page.goto('/#/iam/groups')
    const groups = page.getByRole('table', { name: /^Groups of/ })
    for (const name of ['owners', 'members', 'viewers', 'auditors', 'compute-approvers']) await expect(groups.getByRole('link', { name, exact: true })).toBeVisible()
    await page.getByRole('button', { name: 'Create group' }).click()
    const dialog = page.getByRole('dialog', { name: 'Create a group' })
    await dialog.getByLabel('Name').fill('team-storage')
    await dialog.getByLabel('Description').fill('Looks after the volumes')
    await dialog.getByRole('button', { name: 'Create group' }).click()
    await expect(page).toHaveURL(/#\/iam\/groups\/.+/)
    await expect(page.getByRole('heading', { level: 1 })).toContainText('team-storage')
    await expectNoSeriousA11y(page, 'group')
    await page.getByRole('button', { name: 'Delete group' }).click()
    const confirm = page.getByRole('dialog', { name: 'Delete team-storage?' })
    await confirm.getByRole('textbox').fill('team-storage')
    await confirm.getByRole('button', { name: 'Delete group' }).click()
    await expect(page).toHaveURL(/#\/iam\/groups$/)

    // access keys: the secret is shown once
    await page.goto('/#/iam/access-keys')
    await page.getByRole('button', { name: 'Create access key' }).click()
    const keyDialog = page.getByRole('dialog', { name: 'Create an access key' })
    await keyDialog.getByLabel('Description').fill('Playwright')
    await keyDialog.getByRole('button', { name: 'Create key' }).click()
    const secret = page.locator('[data-secret-shown]')
    await expect(secret).toBeVisible()
    await expect(secret.getByText(/^cs_/)).toBeVisible()
    await expectNoSeriousA11y(page, 'secret')
    await page.getByRole('button', { name: 'I have copied it' }).click()
    await expect(page.getByRole('table', { name: /^Access keys of/ }).getByText('Playwright')).toBeVisible()
    await page.screenshot({ path: shot('iam-access-keys-desktop'), fullPage: true })

    // a quota: ask the platform team for more, see it pending, and withdraw it
    const quotas = page.getByRole('list', { name: 'Quotas' })
    await quotas.getByRole('button', { name: 'Request an increase of access keys' }).click()
    const ask = page.getByRole('dialog', { name: 'Request more access keys' })
    await expect(ask).toBeVisible()
    await ask.getByLabel(/^New limit/).fill('80')
    await ask.getByRole('button', { name: 'Send request' }).click()
    await expect(ask.getByText('Say why in 10 characters or more.')).toBeVisible()
    await ask.getByLabel(/^Why/).fill('The build farm gets a key per runner next week')
    await expectNoSeriousA11y(page, 'quota increase')
    await ask.getByRole('button', { name: 'Send request' }).click()
    await expect(ask).toBeHidden()
    const pending = quotas.locator('[data-quota="access-keys"] [data-quota-pending]')
    await expect(pending).toContainText('80 asked by you')
    await quotas.getByRole('button', { name: 'Withdraw the request for more access keys' }).click()
    await expect(quotas.getByRole('button', { name: 'Request an increase of access keys' })).toBeVisible()
    // one resources quota per resource type, named by the type
    await expect(quotas.locator('[data-quota="resources.vm"]')).toContainText(/Virtual machines: \d+ of \d+/)

    // the account picker: gonzalo is also admin of Data
    await page.getByRole('button', { name: /^Account: Compute/ }).click()
    await page.getByRole('menuitemradio', { name: /^Data / }).click()
    await expect(page.getByRole('button', { name: /^Account: Data / })).toBeVisible()
    await page.goto('/#/iam/users')
    await expect(page.getByRole('table', { name: /^Users of Data/ }).getByRole('link', { name: 'Raj Patel' })).toBeVisible()
    await page.screenshot({ path: shot('account-switched-desktop'), fullPage: true })
  })

  test('the identity pages and the refusal fit a phone', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 })
    await signInAs(page, /Continue as Mia Chen/, '/iam')
    await expect(page.getByRole('heading', { name: 'Identity and access', level: 1 })).toBeVisible()
    await expectNoHorizontalOverflow(page)
    await page.screenshot({ path: shot('iam-overview-phone'), fullPage: true })
    await page.goto('/#/iam/users')
    await expect(page.getByRole('table', { name: /^Users of/ }).getByRole('row').nth(1)).toBeVisible()
    await expectNoHorizontalOverflow(page)
    await page.goto('/#/console/compute/vm')
    const create = createButton(page)
    await expect(create).toHaveAttribute('data-denied', 'console-iam')
    await create.click()
    await expect(page.getByRole('dialog', { name: /You can.t create VM here/ })).toBeVisible()
    await expectNoHorizontalOverflow(page)
    await page.screenshot({ path: shot('refusal-phone'), fullPage: true })
  })
})
