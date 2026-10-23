import { expect, test, type Browser, type Page } from '@playwright/test'
import { Bff, BffError, type Finding, type Resource } from '../support/bff.js'
import { checkNow } from '../support/drift.js'
import { fixmeWhileOpen } from '../support/findings.js'
import { publicImage, resizeOutOfBand } from '../support/platform.js'
import { until } from '../support/poll.js'
import { signIn } from '../support/sign-in.js'
import { CONSOLE_URL, DANA, GONZALO, readSeed, storageStateOf } from '../support/stack.js'

/*
 * The customer console's full-system journey, through every real service of docker-compose.yml:
 *
 *   1 Gonzalo signs in through the company SSO (Dex) and the BFF
 *   2 creates a VM in the create wizard
 *   3 waits until it is active
 *   4 finds it in global search (search indexer, OpenSearch)
 *   5 sees what it costs (cost service)
 *   6 someone resizes it behind the console's back (platform-fake's world) and the drift service's
 *     scan finds it
 *   7 he sees the drift and reverts it with one click
 *   8 deletes it, Dana approves, and it disappears
 *
 * A step in the console's pages comes with the same step through the BFF, on the route the page
 * calls, with his session. While a finding blocks the page (support/findings.ts), the page's test
 * is marked fixme with the finding's link and the BFF's test takes the step, so the rest of the
 * journey still runs; once the page runs, the BFF's test checks what the page did instead.
 */

test.describe.configure({ mode: 'serial' })

/** One VM per run, named so that a run's leftovers are easy to tell apart (^[a-z][a-z0-9-]{2,30}$). */
const VM_NAME = `e2e-vm-${Date.now().toString(36).slice(-6)}`
const DELETE_REASON = 'the e2e journey is done with it'

const journey: { wfrn?: string; vmId?: string; findingId?: string; deleteJobId?: string } = {}

const vmPath = (wfrn: string, query = '') => `/#/console/compute/vm/${encodeURIComponent(wfrn)}${query}`

function wfrnOf(): string {
  expect(journey.wfrn, 'the VM of step 2').toBeTruthy()
  return journey.wfrn!
}

/** Picks an option of an Atlas Select, a button named "<label>: <current value>". */
async function choose(page: Page, label: string, option: string) {
  const select = page.getByRole('button', { name: new RegExp(`^${label}: `) })
  await select.click()
  await page.getByRole('option', { name: option, exact: true }).click()
  await expect(select).toHaveAccessibleName(`${label}: ${option}`)
}

/** The value of one of the facts a resource page lists ("Status", "Drift"). */
function factOf(page: Page, term: string) {
  return page.locator(`xpath=//dt[normalize-space()="${term}"]/following-sibling::dd[1]`).first()
}

/** Dana's own browser, signed in through Dex like Gonzalo. */
async function danaSignsIn(browser: Browser, next = '/'): Promise<{ page: Page; close: () => Promise<void> }> {
  // a context of her own: without storageState it would start from the test's (Gonzalo's)
  const context = await browser.newContext({ baseURL: CONSOLE_URL, storageState: { cookies: [], origins: [] } })
  const page = await context.newPage()
  await signIn(page, DANA, next)
  return { page, close: () => context.close() }
}

/** Polls the BFF until the VM is active, and answers it. */
function activeVm(bff: Bff, wfrn: string): Promise<Resource> {
  return until(`${wfrn} to be active`, async () => {
    const vm = await bff.resource('vm', wfrn)
    return vm?.state === 'active' ? vm : null
  }, { timeout: 120_000 })
}

test.describe('1 · sign in', () => {
  test.use({ storageState: { cookies: [], origins: [] } })

  test('Gonzalo signs in through the company SSO (Dex) and lands in his account', async ({ page }) => {
    const seed = readSeed()
    await test.step('the console sends him to Dex and back through the BFF', () => signIn(page, GONZALO))
    const session = await new Bff(page.request).session()
    expect(session.user.email).toBe(GONZALO.email)
    expect(session.account.accountId).toBe(seed.accountId)
    expect(session.account.roles).toContain('account-admin')
    expect(session.csrfToken).toBeTruthy()
    await page.context().storageState({ path: storageStateOf(GONZALO) })
  })
})

test.describe('after sign-in', () => {
  test.use({ storageState: storageStateOf(GONZALO) })

  test('1 · the console greets him in the Compute account', async ({ page }) => {
    fixmeWhileOpen('F-9')
    await page.goto('/#/')
    await expect(page.getByText(`Signed in as ${GONZALO.name}`)).toBeVisible()
    await expect(page.getByRole('button', { name: /^Account: Compute/ })).toBeVisible()
    await expect(page.getByRole('link', { name: 'Virtual machines' }).first()).toBeVisible()
  })

  test('2 · he creates a VM in the create wizard', async ({ page }) => {
    fixmeWhileOpen('F-9')
    const seed = readSeed()
    const image = await publicImage()
    await page.goto('/#/console/compute/vm')
    await expect(page.getByRole('heading', { name: 'Virtual machines', level: 1 })).toBeVisible()
    await page.getByRole('button', { name: /^Create (VM|virtual machine)/i }).first().click()
    await expect(page.getByRole('heading', { name: 'Create VM', level: 1 })).toBeVisible()

    await page.locator('#create-name').fill(VM_NAME)
    // the account has no image of its own yet: a public one, typed by its id
    await page.getByRole('button', { name: 'Type', exact: true }).click()
    await page.locator('#create-image').fill(image.id)
    // medium, which platform-fake's vSphere reads back as created (F-11)
    await choose(page, 'Size', 'medium')
    await page.locator('#create-owner').fill(seed.owningTeam)
    await page.getByRole('button', { name: 'Next' }).click()

    await expect(page.getByRole('heading', { name: 'Tags', level: 2, exact: true })).toBeVisible()
    const costCentre = page.locator('#create-tag-cost-centre')
    if (!(await costCentre.inputValue())) await costCentre.fill(seed.costCentre)
    await page.getByRole('button', { name: 'Next' }).click()

    await expect(page.getByRole('heading', { name: 'Review and create', level: 2 })).toBeVisible()
    await expect(page.getByTestId('preview-card')).toContainText('Allowed')
    await page.locator('form [data-verb="create"]').click()

    await expect(page).toHaveURL(/#\/console\/compute\/vm\/wfrn%3A/)
    await expect(page.getByRole('heading', { name: VM_NAME, level: 1 })).toBeVisible()
    journey.wfrn = decodeURIComponent(new URL(page.url()).hash.replace(/^#\/console\/compute\/vm\//, '').split('?')[0])
  })

  test('2 · the create, through the BFF route the wizard calls', async ({ request }) => {
    const seed = readSeed()
    const bff = new Bff(request)
    if (!journey.wfrn) {
      const image = await publicImage()
      const accepted = await bff.create(
        'vm',
        {
          input: { name: VM_NAME, image: image.id, size: 'medium', environment: 'dev', owner: seed.owningTeam },
          tags: { owner: seed.owningTeam, 'cost-centre': seed.costCentre, 'data-class': 'internal', environment: 'dev' },
        },
        `e2e-create-${VM_NAME}`,
      )
      expect(accepted.jobId).toMatch(/^job_/)
      journey.wfrn = accepted.wfrn
    }
    expect(journey.wfrn).toMatch(new RegExp(`^wfrn:wf:compute:global:${seed.accountId}:vm/`))
  })

  test('3 · he waits on its page until it is active', async ({ page }) => {
    fixmeWhileOpen('F-9')
    await page.goto(vmPath(wfrnOf()))
    await expect(page.getByRole('heading', { name: VM_NAME, level: 1 })).toBeVisible()
    // the banner follows the create's request until it ended
    await expect(page.locator('[data-job-status]')).toHaveCount(0, { timeout: 120_000 })
    await expect(factOf(page, 'Status')).toHaveText('active')
  })

  test('3 · the state service has it active, as the platform made it', async ({ request }) => {
    const bff = new Bff(request)
    const vm = await activeVm(bff, wfrnOf())
    expect(vm.name).toBe(VM_NAME)
    expect(vm.attributes).toMatchObject({ cpu: 4, memoryGb: 16, environment: 'dev' })
    expect(vm.tags).toMatchObject({ owner: 'team-compute', 'data-class': 'internal' })
    expect(vm.lastJobId, 'the create request').toBeTruthy()
    const job = await bff.job(vm.lastJobId!)
    expect(job.workflow).toBe('create-vm')
    expect(job.status).toBe('succeeded')
    journey.vmId = String(job.output?.vmId ?? '')
    expect(journey.vmId, 'the vSphere id of the VM').toMatch(/^vm-/)
  })

  test('4 · he finds it in the global search', async ({ page }) => {
    fixmeWhileOpen('F-9')
    await page.goto('/#/')
    await page.getByRole('button', { name: 'Search services, resources and pages' }).click()
    const palette = page.getByRole('dialog', { name: 'Command palette' })
    await expect(palette).toBeVisible()
    await page.keyboard.type(VM_NAME)
    const hit = palette.getByRole('option', { name: new RegExp(VM_NAME) }).first()
    await expect(hit).toBeVisible({ timeout: 30_000 })
    await hit.click()
    await expect(page.getByRole('heading', { name: VM_NAME, level: 1 })).toBeVisible()
  })

  test('4 · the BFF’s search answers it from OpenSearch', async ({ request }) => {
    const bff = new Bff(request)
    const wfrn = wfrnOf()
    const hit = await until(`the search indexer to answer ${VM_NAME}`, async () => (await bff.search(VM_NAME)).items.find((h) => h.wfrn === wfrn), { timeout: 60_000 })
    expect(hit.type).toBe('vm')
  })

  test('5 · he sees what it costs', async ({ page, request }) => {
    fixmeWhileOpen('F-4', 'F-5')
    const bff = new Bff(request)
    const wfrn = wfrnOf()
    const cost = await until('the cost service to price it', async () => {
      try {
        const c = await bff.cost('vm', wfrn)
        return c.charged && c.monthly > 0 ? c : null
      } catch (e) {
        if (e instanceof BffError && e.status === 404) return null
        throw e
      }
    })
    expect(cost.source).toBe('list-prices')
    // whatever the panel looks like, the page shows the monthly rate the BFF answered
    await page.goto(vmPath(wfrn))
    await expect(page.getByText(new RegExp(cost.monthly.toFixed(2).replace('.', '\\.'))).first()).toBeVisible()
  })

  test('6 · it is resized behind the console’s back, and the drift scan finds it', async ({ request }) => {
    const bff = new Bff(request)
    const wfrn = wfrnOf()
    expect(journey.vmId, 'the vSphere id from step 3').toBeTruthy()
    // in sync first: what vSphere reports is what was asked for
    await checkNow(wfrn)
    expect((await bff.drift('vm', wfrn)).items.filter((f) => f.status === 'open')).toHaveLength(0)

    // out of band: vSphere now reports 8 CPUs where the console asked for 4
    await resizeOutOfBand(journey.vmId!, { cpu: 8 })
    await checkNow(wfrn)

    const finding = await until('the drift service to open a finding', async () => (await bff.drift('vm', wfrn)).items.find((f) => f.status === 'open'), { timeout: 60_000 })
    expect(finding.fields).toEqual(expect.arrayContaining([expect.objectContaining({ path: '/cpu', expected: 4, observed: 8 })]))
    expect(finding.can).toContain('revert')
    journey.findingId = finding.findingId
    const vm = await bff.resource('vm', wfrn)
    expect(vm?.drift).toBe(true)
  })

  test('7 · he sees the drift on the VM\u2019s page and reverts it with one click', async ({ page }) => {
    // F-12: the tab stops following once the job succeeded, before the finding closes
    fixmeWhileOpen('F-9', 'F-12')
    const wfrn = wfrnOf()
    await page.goto(vmPath(wfrn, '?tab=drift'))
    const card = page.locator('[data-finding]')
    await expect(card).toHaveCount(1)
    await expect(card.getByRole('table', { name: 'Fields that drifted' })).toContainText('8')
    await card.getByRole('button', { name: 'Revert to what was asked for' }).click()
    await expect(page.getByRole('status').filter({ hasText: /^Reverting CPU: request job_/ })).toBeAttached()
    // the tab follows the revert's request; the finding closes when it succeeded and the next read agrees
    await expect(page.locator('[data-finding]')).toHaveCount(0, { timeout: 120_000 })
    await expect(page.getByRole('list', { name: 'Closed findings' }).getByRole('listitem').first()).toContainText('Reverted')
    await page.getByRole('tab', { name: /^Configuration/ }).click()
    await expect(factOf(page, 'Drift')).toContainText('In sync')
  })

  test('7 · the revert, through the BFF route the button calls: the finding closes as reverted', async ({ request }) => {
    const bff = new Bff(request)
    const wfrn = wfrnOf()
    expect(journey.findingId, 'the finding of step 6').toBeTruthy()
    let finding: Finding = await bff.finding(journey.findingId!)
    if (finding.status === 'open' && finding.revertStatus !== 'running' && finding.revertStatus !== 'succeeded') {
      const started = await bff.revert(finding.findingId, 'put back by the e2e journey')
      expect(started.jobId).toMatch(/^job_/)
    }
    finding = await until('the revert to close the finding', async () => {
      const f = await bff.finding(journey.findingId!)
      if (f.status === 'closed') return f
      // the update succeeded: the next check sees vSphere back at what was asked for
      if (f.revertStatus === 'succeeded') await checkNow(wfrn)
      return null
    }, { timeout: 120_000 })
    expect(finding.resolution).toBe('reverted')
    await checkNow(wfrn)
    const vm = await until('the VM to read in sync', async () => {
      const r = await bff.resource('vm', wfrn)
      return r && !r.drift && r.observed?.cpu === 4 ? r : null
    })
    expect(vm.attributes).toMatchObject({ cpu: 4, memoryGb: 16 })
  })

  test('8 · he deletes it, Dana approves, and it disappears', async ({ page, browser }) => {
    // F-13: the page keeps showing the request as running while it waits for its approver;
    // F-14: once the VM is gone the page says it cannot load it instead of showing its tombstone
    fixmeWhileOpen('F-9', 'F-13', 'F-14')
    const wfrn = wfrnOf()
    await page.goto(vmPath(wfrn))
    await expect(page.getByRole('heading', { name: VM_NAME, level: 1 })).toBeVisible()
    await page.locator('[data-verb="delete"]').first().click()
    const drawer = page.getByRole('dialog', { name: `Delete VM: ${VM_NAME}` })
    await expect(drawer).toBeVisible()
    await drawer.locator('#action-delete-reason').fill(DELETE_REASON)
    await expect(drawer.getByTestId('preview-card')).toContainText('Needs approval')
    await drawer.locator('[data-verb="delete"]').click()
    const confirm = page.getByRole('dialog', { name: `Delete VM: ${VM_NAME}?` })
    await confirm.getByLabel(`Type ${VM_NAME} to confirm`).fill(VM_NAME)
    await confirm.getByRole('button', { name: 'Delete VM' }).click()
    const banner = page.locator('[data-job-status]')
    await expect(banner).toContainText('Delete VM is')
    await expect.soft(banner).toHaveAttribute('data-job-status', 'waiting-approval')

    const deletion = await until('the delete request', async () => {
      const vm = await new Bff(page.request).resource('vm', wfrn)
      const job = vm?.lastJobId ? await new Bff(page.request).job(vm.lastJobId) : null
      return job?.workflow === 'delete-vm' ? job : null
    })
    journey.deleteJobId = deletion.jobId

    // Dana, in compute-approvers, approves on the request's page
    const dana = await danaSignsIn(browser, `/requests/${deletion.jobId}`)
    try {
      await dana.page.getByRole('button', { name: 'Approve', exact: true }).click()
      await expect(dana.page.getByText('Approved').first()).toBeVisible()
    } finally {
      await dana.close()
    }

    await until('the delete to finish', async () => ['succeeded', 'failed'].includes((await new Bff(page.request).job(deletion.jobId)).status), { timeout: 120_000 })
    await expect.soft(page.locator('[data-deleted="true"]')).toContainText(`This virtual machine was deleted`, { timeout: 10_000 })
    await page.goto('/#/console/compute/vm')
    await expect(page.getByRole('heading', { name: 'Virtual machines', level: 1 })).toBeVisible()
    await expect(page.getByRole('link', { name: VM_NAME })).toHaveCount(0)
  })

  test('8 · the delete, through the BFF: Dana approves, and it leaves the inventory and the search', async ({ request, browser }) => {
    const bff = new Bff(request)
    const wfrn = wfrnOf()
    if (!journey.deleteJobId) {
      const accepted = await bff.run('vm', wfrn, 'delete', { input: { reason: DELETE_REASON }, confirm: true }, `e2e-delete-${VM_NAME}`)
      journey.deleteJobId = accepted.jobId
      const job = await until('the delete to wait for an approver', async () => {
        const j = await bff.job(accepted.jobId)
        return j.approvals?.some((a) => a.status === 'pending') ? j : null
      })
      expect(job.approvals?.[0]?.group).toBe(readSeed().approverGroup)

      // the platform never lets the requester approve: Dana, in compute-approvers, does
      const dana = await danaSignsIn(browser)
      try {
        const asDana = new Bff(dana.page.request)
        const pending = await asDana.job(accepted.jobId)
        expect(pending.approvals?.find((a) => a.status === 'pending')?.canDecide).toBe(true)
        const decided = await asDana.decideApprovals(accepted.jobId, 'approve', 'approved by the e2e journey')
        expect(decided.decided.length).toBe(1)
      } finally {
        await dana.close()
      }
    }

    const job = await until('the delete to finish', async () => {
      const j = await bff.job(journey.deleteJobId!)
      return ['succeeded', 'failed', 'cancelled'].includes(j.status) ? j : null
    }, { timeout: 120_000 })
    expect(job.status).toBe('succeeded')

    // the inventory keeps a tombstone: the resource answers 410 Gone with when it was deleted
    const tombstone = await until('the VM to leave the inventory', () => bff.tombstone('vm', wfrn))
    expect(tombstone.deletedAt).toBeTruthy()
    await until('the VM to leave the list', async () => !(await bff.list('vm', { limit: '200' })).items.some((r) => r.wfrn === wfrn))
    await until('the VM to leave the search', async () => !(await bff.search(VM_NAME)).items.some((h) => h.wfrn === wfrn), { timeout: 60_000 })
  })
})
