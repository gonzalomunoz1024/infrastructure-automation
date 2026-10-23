import { fileURLToPath } from 'node:url'
import { expect, test, type Locator, type Page } from '@playwright/test'
import { expectNoHorizontalOverflow, expectNoSeriousA11y, signInAs, tabTo } from './support'

/*
 * Phase 7, custom policies (WP 7.8), in mock mode: Gonzalo writes a policy that lets operators
 * restart only prod VMs, partly in the visual editor and partly as JSON, checks it in the
 * simulator before saving it, and grants it through a new role; Dana's prod restart then runs
 * while her delete is refused by the statement that denies it. A permission boundary narrows a
 * role and the simulator shows the boundary deciding, a guardrail shows its own refusal, Mia
 * (a viewer) meets every write locked with the refusal in words, and the pages fit a phone.
 * The mock decides with the same code for a real request and for the simulator.
 */

const shots = fileURLToPath(new URL('./screenshots/', import.meta.url))
const shot = (name: string) => `${shots}${name}.png`

const ACCOUNT = '123456789012'
const wfrnOf = (id: string) => `wfrn:wf:compute:global:${ACCOUNT}:vm/${id}`
const vmPage = (id: string) => `/console/compute/vm/${encodeURIComponent(wfrnOf(id))}`
const VMS = `wfrn:wf:compute:*:${ACCOUNT}:vm/*`

/** The rest of the policy, written as JSON once the first statement is built visually. */
const POLICY = {
  name: 'prod-restarts',
  description: 'Operators restart prod VMs only, and never delete one',
  statements: [
    { sid: 'restart-prod', effect: 'allow', actions: ['compute:restart'], resources: [VMS], conditions: { 'tag.environment': 'prod' } },
    { sid: 'restart-only-prod', effect: 'deny', actions: ['compute:restart'], resources: [VMS], conditions: { 'tag.environment': ['dev', 'test'] } },
    { sid: 'no-vm-deletes', effect: 'deny', actions: ['compute:delete'], resources: [VMS] },
  ],
}

/** A form's Select: named by its label and the text it shows, "Person: Dana Reyes". */
const selectNamed = (label: string) => new RegExp(`^${label.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}: `)

async function choose(page: Page, scope: Locator, label: string, option: string | RegExp) {
  await scope.getByRole('button', { name: selectNamed(label) }).click()
  await page.getByRole('option', { name: option }).first().click()
  await expect(scope.getByRole('button', { name: selectNamed(label) })).toContainText(option)
}

async function signOut(page: Page) {
  await page.goto('/#/settings')
  await page.getByRole('button', { name: 'Sign out' }).click()
  await expect(page).toHaveURL(/#\/signin/)
}

/** Adds one of the account's VMs to the simulator by its name; its tags go with it. */
async function addVm(page: Page, scope: Locator, name: string) {
  await choose(page, scope, 'Type', /^(VM|Virtual machine)/)
  await choose(page, scope, 'Resource', name)
  await scope.getByRole('button', { name: 'Add resource' }).click()
  await expect(scope.locator(`[data-sim-resource="${wfrnOf(name === 'web-01' ? 'vm-demo01' : 'vm-demo02')}"]`)).toBeVisible()
}

const result = (scope: Locator, action: string, vm: string) => scope.locator(`[data-sim-result="${action} ${wfrnOf(vm)}"]`)

/** Gonzalo creates a role carrying the named policies, granted by the members group (the operators). */
async function createRole(page: Page, name: string, policy: string, members = true) {
  const dialog = page.getByRole('dialog', { name: 'Create a role' })
  await expect(dialog).toBeVisible()
  await expect(dialog.locator('#role-name')).toBeFocused()
  await dialog.locator('#role-name').fill(name)
  const box = dialog.getByRole('checkbox', { name: new RegExp(`^${policy}( |$)`) })
  if (!(await box.isChecked())) await box.check()
  if (members) await dialog.getByRole('checkbox', { name: /^members( |$)/ }).check()
  return dialog
}

test.describe('custom policies in mock mode', () => {
  test('Gonzalo writes a policy letting operators restart only prod VMs, checks it in the simulator and grants it; Dana restarts prod and is still refused a delete, by the statement', async ({ page }) => {
    test.setTimeout(120_000)
    await signInAs(page, /Continue as Gonzalo Munoz/, '/iam/policies')
    await expect(page.getByRole('heading', { name: 'Policies', level: 1 })).toBeVisible()
    await expect(page.locator('[data-policy-row="managed-operator"]')).toContainText('used by Operator')
    await expectNoSeriousA11y(page, 'policies page')

    // the editor, from the keyboard
    const create = page.getByRole('button', { name: 'Create policy' })
    await tabTo(page, create, 80)
    await page.keyboard.press('Enter')
    await expect(page).toHaveURL(/#\/iam\/policies\/new$/)
    await expect(page.getByRole('heading', { name: 'Create policy', level: 1 })).toBeVisible()
    await page.locator('#policy-name').fill('prod-restarts')
    await page.locator('#policy-description').fill(POLICY.description)

    // statement 1, built visually: compute:restart on the account's VMs, when the VM is tagged prod
    const first = page.locator('[data-statement="0"]')
    await first.locator('#policy-s0-sid').fill('restart-prod')
    await choose(page, first, 'Service', 'Compute')
    await first.locator('#policy-s0-actions-restart').check()
    await expect(first.locator('[data-action="compute:restart"]')).toBeVisible()
    await expect(first.getByLabel('Resources of statement 1, row 1')).toHaveValue(VMS)
    await first.getByRole('button', { name: 'Add a condition' }).click()
    await first.locator('#policy-s0-cond-0-value').fill('prod')
    await expect(page.locator('[data-problem-count]')).toHaveText('The policy reads well')
    await page.screenshot({ path: shot('policy-editor-visual-desktop'), fullPage: true })
    await expectNoSeriousA11y(page, 'policy editor (visual)')

    // the same document as JSON: a typo is flagged at its line, then the rest is pasted in
    await page.getByRole('radio', { name: 'JSON' }).click()
    const json = page.locator('#policy-json')
    await expect(json).toHaveValue(/"sid": "restart-prod"/)
    await expect(json).toHaveValue(/"tag\.environment": "prod"/)
    const typo = { ...POLICY, statements: [POLICY.statements[0], { ...POLICY.statements[2], actions: ['compute:delet'] }] }
    await json.fill(JSON.stringify(typo, null, 2))
    await expect(page.locator('[data-problem-count]')).toHaveText('1 problem to fix')
    const problem = page.getByRole('list', { name: 'Problems in the JSON' }).getByRole('listitem').first()
    await expect(problem).toContainText('compute:delet')
    await expect(problem).toContainText(/^Line \d+:/)
    await expect(page.getByRole('button', { name: 'Create policy' })).toBeDisabled()
    await problem.getByRole('button', { name: /^Show line/ }).click()
    await expect(json).toBeFocused()
    await page.screenshot({ path: shot('policy-editor-json-problem-desktop'), fullPage: true })
    await expectNoSeriousA11y(page, 'policy editor (JSON with a problem)')
    await json.fill('{ "name": "prod-restarts", ')
    await expect(page.getByRole('list', { name: 'Problems in the JSON' })).toBeVisible()
    await json.fill(JSON.stringify(POLICY, null, 2))
    await expect(page.locator('[data-problem-count]')).toHaveText('The policy reads well')

    // back in the visual view: three statements, the deny ones too
    await page.getByRole('radio', { name: 'Visual' }).click()
    await expect(page.locator('[data-statement]')).toHaveCount(3)
    await expect(page.locator('[data-statement="2"] #policy-s2-sid')).toHaveValue('no-vm-deletes')
    await expect(page.locator('[data-statement="1"] [data-condition="tag.environment"] #policy-s1-cond-0-value')).toHaveValue('dev, test')

    // checked in the simulator before it is saved: Dana, on web-01 (prod) and api-01 (dev)
    await page.getByRole('button', { name: 'Check in the simulator' }).click()
    const drawer = page.getByRole('dialog', { name: 'Check prod-restarts in the simulator' })
    await expect(drawer).toBeVisible()
    await expect(drawer.locator('[data-draft="prod-restarts"]')).toBeVisible()
    await choose(page, drawer, 'Person', 'Dana Reyes')
    await expect(drawer.locator('[data-action="compute:restart"]')).toBeVisible()
    await expect(drawer.locator('[data-action="compute:delete"]')).toBeVisible()
    await addVm(page, drawer, 'web-01')
    await addVm(page, drawer, 'api-01')
    await drawer.getByRole('button', { name: 'Run the simulation' }).click()
    await expect(drawer.locator('[data-sim-summary]')).toHaveText('1 allowed, 3 denied')
    await expect(result(drawer, 'compute:restart', 'vm-demo01')).toHaveAttribute('data-allowed', 'true')
    const dev = result(drawer, 'compute:restart', 'vm-demo02')
    await expect(dev).toHaveAttribute('data-allowed', 'false')
    await expect(dev).toContainText('Policy prod-restarts (unsaved), statement restart-only-prod')
    const del = result(drawer, 'compute:delete', 'vm-demo01')
    await expect(del).toContainText('statement no-vm-deletes')
    await del.getByText('Show how it was decided').click()
    await expect(del.locator('[data-deciding]')).toHaveAttribute('data-sim-layer', 'The policies of the roles')
    await expect(del.locator('[data-sim-layer="The organization’s guardrails"]')).toHaveAttribute('data-layer-verdict', 'allowed')
    await page.screenshot({ path: shot('policy-editor-simulator-desktop') })
    await expectNoSeriousA11y(page, 'simulator drawer')
    await drawer.getByRole('button', { name: 'Back to the editor' }).click()
    await expect(drawer).toBeHidden()

    // saved: version 1, used by no role yet
    await page.getByRole('button', { name: 'Create policy' }).click()
    await expect(page).toHaveURL(/#\/iam\/policies\/pol_/)
    await expect(page.getByRole('heading', { name: 'prod-restarts', level: 1 })).toBeVisible()
    await expect(page.locator('[data-used-by]')).toContainText('No role uses it yet')
    await expect(page.getByRole('list', { name: 'Statements of prod-restarts' }).getByRole('listitem')).toHaveCount(3)

    // granted: a new role carrying it, granted by the members group
    await page.getByRole('button', { name: 'Create a role with it' }).click()
    const dialog = await createRole(page, 'prod-restarter', 'prod-restarts')
    await expectNoSeriousA11y(page, 'create role dialog')
    await dialog.getByRole('button', { name: 'Create role' }).click()
    await expect(dialog).toBeHidden()
    await expect(page.locator('[data-used-by]')).toContainText('prod-restarter carries it')
    await page.screenshot({ path: shot('policy-page-desktop'), fullPage: true })
    await expectNoSeriousA11y(page, 'policy page')

    // a delete waits while a role holds it, and says which
    await page.getByRole('button', { name: 'Delete', exact: true }).click()
    const inUse = page.getByRole('dialog', { name: 'prod-restarts is in use' })
    await expect(inUse).toBeVisible()
    await expect(inUse.locator('[data-in-use]')).toContainText('prod-restarter carries it')
    await expect(inUse.locator('[data-autofocus]')).toBeFocused()
    await expectNoSeriousA11y(page, 'policy in use dialog')
    await page.keyboard.press('Escape')
    await expect(inUse).toBeHidden()

    // Dana restarts web-01, a prod VM
    await signOut(page)
    await signInAs(page, /Continue as Dana Reyes/, vmPage('vm-demo01'))
    await expect(page.getByRole('heading', { name: 'web-01', level: 1 })).toBeVisible()
    await page.getByRole('button', { name: 'Actions', exact: true }).click()
    await page.getByRole('menu', { name: 'Actions for web-01' }).getByRole('menuitem', { name: /VM restart/ }).click()
    const restart = page.getByRole('dialog', { name: 'VM restart: web-01' })
    await expect(restart).toBeVisible()
    await restart.locator('#action-restart-reason').fill('rolling the kernel patch')
    await restart.locator('[data-verb="restart"]').click()
    await expect(restart).toBeHidden()
    await expect(page.getByText('VM restart requested')).toBeVisible()

    // her delete is still refused, now by the statement that denies it
    const deleteVm = page.getByRole('button', { name: /^Delete VM/ })
    await expect(deleteVm).toHaveAttribute('data-denied', 'console-iam')
    await deleteVm.click()
    const refusal = page.getByRole('dialog', { name: /You can.t delete VM here/ })
    await expect(refusal).toBeVisible()
    await expect(refusal.getByText(/Statement no-vm-deletes of policy .+ denies it for the role prod-restarter/)).toBeVisible()
    await page.screenshot({ path: shot('refusal-custom-statement-desktop'), fullPage: true })
    await expectNoSeriousA11y(page, 'refusal by a custom statement')
    await page.keyboard.press('Escape')

    // and api-01, a dev VM, is refused its restart by restart-only-prod
    await page.goto(`/#${vmPage('vm-demo02')}`)
    await expect(page.getByRole('heading', { name: 'api-01', level: 1 })).toBeVisible()
    await page.getByRole('button', { name: 'Actions', exact: true }).click()
    await page.getByRole('menu', { name: 'Actions for api-01' }).getByRole('menuitem', { name: /VM restart/ }).click()
    const devDrawer = page.getByRole('dialog', { name: 'VM restart: api-01' })
    await devDrawer.locator('#action-restart-reason').fill('stuck after a kernel update')
    await devDrawer.locator('[data-verb="restart"]').click()
    // the menu decides by the type; the request is decided on the VM itself, whose tags say dev
    await expect(page.getByText(/Statement restart-only-prod of policy .+ denies it/).first()).toBeVisible()
  })

  test('a permission boundary narrows a role, and the simulator shows the boundary deciding; a guardrail decides before any role', async ({ page }) => {
    test.setTimeout(120_000)
    await signInAs(page, /Continue as Gonzalo Munoz/, '/iam/roles')
    await page.getByRole('button', { name: 'Create role' }).click()
    const dialog = await createRole(page, 'restarter', 'managed-operator', false)
    await dialog.getByRole('button', { name: 'Create role' }).click()
    await expect(dialog).toBeHidden()
    await page.goto('/#/iam/roles/restarter')
    await expect(page.locator('[data-boundary="none"]')).toBeVisible()

    // a draft boundary tried first, in the simulator: nothing saved
    await page.getByRole('link', { name: 'Simulate what this role may do' }).click()
    await expect(page).toHaveURL(/#\/iam\/simulator\?role=/)
    const sim = page.locator('[data-simulator]')
    await expect(sim.getByRole('radio', { name: 'A role' })).toBeChecked()
    await expect(sim.getByRole('button', { name: selectNamed('Role') })).toContainText('restarter')
    await choose(page, sim, 'Permission boundary to try', 'managed-viewer')
    await sim.locator('#sim-actions-typed').fill('compute:restart')
    await sim.getByRole('button', { name: 'Add action' }).click()
    await sim.locator('#sim-actions-typed').fill('compute:get')
    await sim.getByRole('button', { name: 'Add action' }).click()
    await addVm(page, sim, 'web-01')
    await sim.getByRole('button', { name: 'Run the simulation' }).click()
    const bounded = result(sim, 'compute:restart', 'vm-demo01')
    await expect(bounded).toHaveAttribute('data-allowed', 'false')
    await expect(bounded.locator('[data-decided-by]')).toHaveAttribute('data-decided-by', 'Permission boundary')
    await expect(bounded).toContainText('Permission boundary managed-viewer (draft boundary)')
    await expect(result(sim, 'compute:get', 'vm-demo01')).toHaveAttribute('data-allowed', 'true')
    await bounded.getByText('Show how it was decided').click()
    await expect(bounded.locator('[data-sim-layer="The policies of the roles"]')).toHaveAttribute('data-layer-verdict', 'allowed')
    await expect(bounded.locator('[data-deciding]')).toHaveAttribute('data-sim-layer', 'The role’s permission boundary')
    await page.screenshot({ path: shot('simulator-boundary-desktop'), fullPage: true })
    await expectNoSeriousA11y(page, 'simulator with a boundary deciding')

    // a guardrail decides before any role: no creates outside the approved regions
    await sim.locator('#sim-actions-typed').fill('compute:create')
    await sim.getByRole('button', { name: 'Add action' }).click()
    await sim.locator('#sim-wfrn').fill(`wfrn:wf:compute:us-east-1:${ACCOUNT}:vm/*`)
    await sim.getByRole('button', { name: 'Add', exact: true }).click()
    await choose(page, sim, 'Permission boundary to try', 'No boundary')
    await sim.getByRole('button', { name: 'Run the simulation' }).click()
    const railed = sim.locator(`[data-sim-result="compute:create wfrn:wf:compute:us-east-1:${ACCOUNT}:vm/*"]`)
    await expect(railed).toHaveAttribute('data-allowed', 'false')
    await expect(railed).toContainText('Guardrail approved-regions')
    await expect(result(sim, 'compute:restart', 'vm-demo01')).toHaveAttribute('data-allowed', 'true')

    // the boundary saved on the role: its page says so, and the simulator shows it deciding
    await page.goto('/#/iam/roles/restarter')
    await page.getByRole('button', { name: 'Set boundary' }).click()
    const set = page.getByRole('dialog', { name: 'Permission boundary of restarter' })
    await expect(set).toBeVisible()
    await choose(page, set, 'Boundary', 'managed-viewer')
    await expectNoSeriousA11y(page, 'boundary dialog')
    await set.getByRole('button', { name: 'Save boundary' }).click()
    await expect(set).toBeHidden()
    await expect(page.locator('[data-boundary]')).toContainText('Bounded by managed-viewer')
    await page.screenshot({ path: shot('role-boundary-desktop'), fullPage: true })
    await expectNoSeriousA11y(page, 'role with a boundary')
    await page.getByRole('link', { name: 'Simulate what this role may do' }).click()
    await expect(sim.getByRole('button', { name: selectNamed('Permission boundary to try') })).toContainText('Its own (managed-viewer)')
    await sim.locator('#sim-actions-typed').fill('compute:restart')
    await sim.getByRole('button', { name: 'Add action' }).click()
    await addVm(page, sim, 'web-01')
    await sim.getByRole('button', { name: 'Run the simulation' }).click()
    await expect(result(sim, 'compute:restart', 'vm-demo01')).toContainText('Permission boundary managed-viewer')

    // the guardrails, listed, and editable by an organization admin
    await page.goto('/#/iam/guardrails')
    await expect(page.getByRole('heading', { name: 'Guardrails', level: 1 })).toBeVisible()
    await expect(page.locator('[data-guardrail-row="approved-regions"]')).toContainText('Every account, now and later')
    await expectNoSeriousA11y(page, 'guardrails page')
    await page.getByRole('link', { name: 'approved-regions' }).click()
    await expect(page.getByRole('heading', { name: 'approved-regions', level: 1 })).toBeVisible()
    await page.getByRole('button', { name: 'Edit', exact: true }).click()
    await expect(page.getByLabel('Every account in the organization, now and later')).toBeChecked()
    await page.locator('#guardrail-description').fill('Creates only in the approved regions, eu and global')
    await page.getByRole('button', { name: 'Save new version' }).click()
    await expect(page.getByText('Creates only in the approved regions, eu and global')).toBeVisible()
    await page.screenshot({ path: shot('guardrail-desktop'), fullPage: true })
    await expectNoSeriousA11y(page, 'guardrail page')
  })

  test('Mia, a viewer, reads policies and guardrails but every write is locked, with the refusal in words', async ({ page }) => {
    await signInAs(page, /Continue as Mia Chen/, '/iam/policies')
    await expect(page.getByRole('heading', { name: 'Policies', level: 1 })).toBeVisible()
    const create = page.getByRole('button', { name: /^Create policy/ })
    await expect(create).toHaveAttribute('data-denied', 'console-iam')
    await create.click()
    const refusal = page.getByRole('dialog', { name: /You can.t/ })
    await expect(refusal).toBeVisible()
    await expect(refusal.getByText(/iam:create/).first()).toBeVisible()
    await expectNoSeriousA11y(page, 'refusal of a policy write')
    await page.keyboard.press('Escape')

    // the editor's page itself: the save is locked
    await page.goto('/#/iam/policies/new')
    await expect(page.getByRole('button', { name: /^Create policy/ })).toHaveAttribute('data-denied', 'console-iam')

    // roles and guardrails: listed, never changed
    await page.goto('/#/iam/roles')
    await expect(page.getByRole('button', { name: /^Create role/ })).toHaveAttribute('data-denied', 'console-iam')
    await page.goto('/#/iam/guardrails')
    await expect(page.locator('[data-guardrail-row="approved-regions"]')).toBeVisible()
    await expect(page.locator('[data-org-locked]')).toContainText('Only an organization admin')
    const rail = page.getByRole('button', { name: /^Create guardrail/ })
    await expect(rail).toHaveAttribute('data-denied', 'console-iam')
    await rail.click()
    await expect(page.getByRole('dialog', { name: /You can.t/ }).getByText(/organization admin/).first()).toBeVisible()
    await page.keyboard.press('Escape')
    await page.screenshot({ path: shot('guardrails-viewer-desktop'), fullPage: true })
    await expectNoSeriousA11y(page, 'guardrails for a viewer')

    // she may still ask the simulator about herself
    await page.goto('/#/iam/simulator')
    const sim = page.locator('[data-simulator]')
    await sim.locator('#sim-actions-typed').fill('compute:restart')
    await sim.getByRole('button', { name: 'Add action' }).click()
    await addVm(page, sim, 'web-01')
    await sim.getByRole('button', { name: 'Run the simulation' }).click()
    await expect(result(sim, 'compute:restart', 'vm-demo01')).toContainText('No statement allows it')
    await expectNoSeriousA11y(page, 'simulator for a viewer')
  })

  test('the policy pages fit a phone', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 })
    await signInAs(page, /Continue as Gonzalo Munoz/, '/iam/policies')
    await expect(page.getByRole('heading', { name: 'Policies', level: 1 })).toBeVisible()
    await expectNoHorizontalOverflow(page)
    await page.screenshot({ path: shot('policies-phone'), fullPage: true })

    await page.goto('/#/iam/policies/new')
    await expect(page.getByRole('heading', { name: 'Create policy', level: 1 })).toBeVisible()
    await page.getByRole('button', { name: 'Build a pattern' }).click()
    await expectNoHorizontalOverflow(page)
    await page.screenshot({ path: shot('policy-editor-phone'), fullPage: true })
    await expectNoSeriousA11y(page, 'policy editor at phone width')
    await page.getByRole('radio', { name: 'JSON' }).click()
    await expectNoHorizontalOverflow(page)

    await page.goto('/#/iam/policies/managed-operator')
    await expect(page.getByRole('heading', { name: 'managed-operator', level: 1 })).toBeVisible()
    await expectNoHorizontalOverflow(page)

    await page.goto('/#/iam/simulator')
    const sim = page.locator('[data-simulator]')
    await sim.locator('#sim-actions-typed').fill('compute:delete')
    await sim.getByRole('button', { name: 'Add action' }).click()
    await addVm(page, sim, 'web-01')
    await sim.getByRole('button', { name: 'Run the simulation' }).click()
    await expect(result(sim, 'compute:delete', 'vm-demo01')).toBeVisible()
    await result(sim, 'compute:delete', 'vm-demo01').getByText('Show how it was decided').click()
    await expectNoHorizontalOverflow(page)
    await page.screenshot({ path: shot('simulator-phone'), fullPage: true })
    await expectNoSeriousA11y(page, 'simulator at phone width')

    await page.goto('/#/iam/guardrails')
    await expect(page.getByRole('heading', { name: 'Guardrails', level: 1 })).toBeVisible()
    await expectNoHorizontalOverflow(page)
  })
})
