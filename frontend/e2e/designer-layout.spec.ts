import { expect, test, type Page } from '@playwright/test'
import { mockDesignerApi } from './designer-fixtures'

/*
 * The designer's layout against the mocked backend: the full-bleed designer is exactly as wide
 * as its box, so nothing (scrollIntoView, a click's auto-scroll) can slide it sideways.
 */

interface Scroll {
  scrollWidth: number
  clientWidth: number
  scrollLeft: number
  scrollHeight: number
  clientHeight: number
  scrollTop: number
}

// as strings: this project's e2e types carry no DOM
const SCROLL_OF = (selector: string) => `(() => {
  const el = document.querySelector(${JSON.stringify(selector)})
  return { scrollWidth: el.scrollWidth, clientWidth: el.clientWidth, scrollLeft: el.scrollLeft, scrollHeight: el.scrollHeight, clientHeight: el.clientHeight, scrollTop: el.scrollTop }
})()`

async function scrollOf(page: Page, selector: string): Promise<Scroll> {
  return (await page.evaluate(SCROLL_OF(selector))) as Scroll
}

/** Element.scrollIntoView, centred on both axes: it scrolls every scrollable ancestor, overflow-hidden ones included. */
async function scrollIntoView(page: Page, id: string): Promise<void> {
  await page.evaluate(`document.getElementById(${JSON.stringify(id)}).scrollIntoView({ block: 'center', inline: 'center' })`)
}

const SECTIONS = ['workflow', 'api', 'input', 'mcp', 'policy', 'triggers', 'rollout', 'outputs']

test.describe('designer layout', () => {
  // 1440: the window of the report (the root 1208 wide, its content 1253); 700: the header takes two rows
  for (const width of [1440, 1280, 700]) {
    test(`at ${width}px the designer fits its box and a Settings section scrolls into view without sliding it sideways`, async ({ page }) => {
      await page.setViewportSize({ width, height: 900 })
      await mockDesignerApi(page)
      await page.goto('/#/workflows/vm-restart')
      await expect(page.locator('.react-flow__node[data-id="step:checkVmExists"]')).toBeVisible()

      const root = await scrollOf(page, '[data-testid="designer-root"]')
      expect(root.scrollWidth).toBe(root.clientWidth)

      await page.getByRole('tab', { name: 'Settings' }).click()
      const view = page.getByTestId('settings-view')
      await expect(view.getByRole('heading', { name: 'Workflow' })).toBeVisible()
      const settings = await scrollOf(page, '[data-testid="designer-root"]')
      expect(settings.scrollWidth).toBe(settings.clientWidth)
      // the sections scroll in their own pane: nothing of them hangs below the designer
      expect(settings.scrollHeight).toBe(settings.clientHeight)

      for (const id of SECTIONS) {
        await scrollIntoView(page, `settings-${id}`)
        await expect(view.locator(`#settings-${id}`)).toBeInViewport()
        const after = await scrollOf(page, '[data-testid="designer-root"]')
        expect(after.scrollLeft, id).toBe(0)
        expect(after.scrollTop, id).toBe(0)
        expect((await scrollOf(page, '[data-testid="settings-view"] .overflow-y-auto')).scrollLeft, id).toBe(0)
      }
      // the designer header stays where it was
      await expect(page.getByRole('tab', { name: 'Settings' })).toBeInViewport()
    })
  }

  test("the last button's tooltip opens inside the window", async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 900 })
    await mockDesignerApi(page)
    await page.goto('/#/workflows/vm-restart')
    await page.getByRole('button', { name: 'Publish', exact: true }).hover()
    const tip = page.getByRole('tooltip', { name: 'Open a change request to publish' })
    await expect(tip).toHaveCSS('opacity', '1')
    const box = await tip.boundingBox()
    expect(box).not.toBeNull()
    expect(box!.x + box!.width).toBeLessThanOrEqual(1440)
  })
})
