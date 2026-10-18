import { expect, test, type Locator, type Page } from '@playwright/test'
import { mockDesignerApi, type MockApi } from './designer-fixtures'

/*
 * The designer against the mocked backend, third wave: a terraform provider's action inserted
 * with the provider's own module.
 */

function node(page: Page, id: string): Locator {
  return page.locator(`.react-flow__node[data-id="${id}"]`)
}

async function openVmRestart(page: Page): Promise<MockApi> {
  const api = await mockDesignerApi(page)
  await page.goto('/#/workflows/vm-restart')
  await expect(node(page, 'step:checkVmExists')).toBeVisible()
  return api
}

test.describe('terraform from the library', () => {
  test("a terraform provider's action is inserted with the provider's module and passes the module check", async ({ page }) => {
    await openVmRestart(page)
    await node(page, 'step:noBackupRunning').click()
    await page.keyboard.press('Tab')
    const creator = page.getByTestId('node-creator')
    await creator.getByRole('option', { name: /Terraform/ }).first().click()
    const plan = creator.getByRole('option', { name: /Terraform modules · plan/ })
    await expect(plan).toContainText('git::https://github.com/acme/tf-modules//lb @ 2.3.0')
    await plan.click()
    const ndv = page.getByTestId('ndv')
    await expect(ndv.getByRole('textbox', { name: /^Module source/ })).toHaveValue('git::https://github.com/acme/tf-modules//lb')
    await expect(ndv.getByRole('textbox', { name: /^Module version/ })).toHaveValue('2.3.0')
    await expect(ndv.getByText('A Terraform step needs module: { source, version }')).toHaveCount(0)
  })
})
