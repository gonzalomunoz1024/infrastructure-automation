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

  test('dismissing a toast leaves the step list open; a click on the canvas still closes it', async ({ page }) => {
    const api = await mockDesignerApi(page)
    await page.goto('/#/workflows/vm-restart')
    await node(page, 'step:noBackupRunning').click()
    await page.keyboard.press('Tab')
    const creator = page.getByTestId('node-creator')
    await expect(creator).toBeVisible()
    // ⌘S works from the step list's search box and says so in a toast
    await page.keyboard.press('ControlOrMeta+s')
    await expect.poll(() => api.drafts.length).toBe(1)
    const toast = page.getByRole('region', { name: 'Notifications' }).getByRole('status').filter({ hasText: 'Saved' })
    await toast.getByRole('button', { name: 'Dismiss' }).click()
    await expect(toast).toHaveCount(0)
    await expect(creator).toBeVisible()
    // anywhere else is still outside
    await page.getByTestId('designer-canvas').click({ position: { x: 20, y: 20 } })
    await expect(creator).toBeHidden()
  })
})
