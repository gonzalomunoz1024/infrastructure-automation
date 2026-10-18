import { expect, test } from '@playwright/test'
import { DEPENDENCIES, RESOURCE_TYPES, VMS, WORKFLOWS, mockApi } from './fixtures'

/*
 * The dependencies page against a mocked /api/v1: reached from the workflows and resources
 * pages, it names who produces and consumes each type, what each workflow runs, and the cycles.
 */

test.describe('dependencies', () => {
  const routes = {
    'GET /registry/dependencies': DEPENDENCIES,
    'GET /workflows': WORKFLOWS,
    'GET /resource-types': RESOURCE_TYPES,
    'GET /resources': VMS,
  }

  test('shows producers, consumers and runs with links to every workflow and type', async ({ page }) => {
    await mockApi(page, routes)
    await page.goto('/#/dependencies')
    await expect(page.getByRole('heading', { level: 1, name: 'Dependencies' })).toBeVisible()
    await expect(page.getByRole('link', { name: 'Dependencies' })).toHaveAttribute('aria-current', 'page')

    const types = page.getByRole('table', { name: 'Resource types' })
    const image = types.getByRole('row', { name: /^image/ })
    await expect(image).toContainText('build-vm-image')
    await expect(image).toContainText('declared producer')
    await expect(image).toContainText('create-vm')
    await expect(image).toContainText('input image')
    await expect(image).toContainText('builds one first for input image')

    const createVm = page.getByTestId('dependencies-create-vm')
    await expect(createVm).toContainText('2.0.0')
    await expect(createVm).toContainText('^2')
    await expect(createVm).toContainText('step create')
    await expect(createVm).toContainText('web-environment')

    // the loop is called out in plain words
    await expect(page.getByRole('region', { name: 'Cycles among runs' })).toContainText('create-vm runs web-environment, and web-environment runs create-vm again')
    await expect(createVm).toContainText('in a loop')

    // a workflow links to its designer page, a type to its resources
    await createVm.getByRole('link', { name: 'build-vm-image' }).click()
    await expect(page).toHaveURL(/#\/workflows\/build-vm-image$/)
    await page.goBack()
    await image.getByRole('link', { name: 'image', exact: true }).click()
    await expect(page).toHaveURL(/#\/resources\?type=image$/)
  })

  test('is reachable from the workflows and resources pages', async ({ page }) => {
    await mockApi(page, routes)
    await page.goto('/#/workflows')
    await page.getByRole('button', { name: 'Dependencies' }).click()
    await expect(page).toHaveURL(/#\/dependencies$/)
    await page.goto('/#/resources')
    await page.getByRole('button', { name: 'Dependencies' }).click()
    await expect(page).toHaveURL(/#\/dependencies$/)
    await expect(page.getByRole('heading', { level: 1, name: 'Dependencies' })).toBeVisible()
  })
})
