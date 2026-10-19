import { expect, test, type Locator, type Page } from '@playwright/test'
import { mockDesignerApi } from './designer-fixtures'

/*
 * The designer against the mocked backend, clicked the way a person clicks: real pointer events
 * at the element's place on screen (no dispatchEvent, no force), so whatever is on top gets them.
 */

function node(page: Page, id: string): Locator {
  return page.locator(`.react-flow__node[data-id="${id}"]`)
}

async function newWorkflow(page: Page): Promise<void> {
  await mockDesignerApi(page)
  await page.goto('/#/workflows/new')
  const dialog = page.getByRole('dialog', { name: 'New workflow' })
  await dialog.getByLabel('Title').fill('Disk resize')
  await dialog.getByLabel('Noun').fill('disk')
  await dialog.getByLabel('Verb').fill('resize')
  await dialog.getByRole('button', { name: 'Create workflow' }).click()
  await expect(node(page, 'trigger:api')).toBeVisible()
}

test.describe('designer pointer', () => {
  test('"Add first step" on an empty canvas takes a real click and opens the step list', async ({ page }) => {
    await newWorkflow(page)
    const add = node(page, 'placeholder').getByRole('button', { name: 'Add first step' })
    // a plain click: Playwright fails it when the canvas pane would get the pointer instead
    await add.click({ timeout: 5_000 })
    const creator = page.getByTestId('node-creator')
    await expect(creator).toBeVisible()
    await creator.getByRole('combobox', { name: 'Search steps' }).fill('wait')
    await page.keyboard.press('Enter')
    await expect(page.getByTestId('ndv')).toBeVisible()
    await page.keyboard.press('Escape')
    await expect(node(page, 'step:wait')).toBeVisible()
    await expect(node(page, 'placeholder')).toHaveCount(0)
  })
})
