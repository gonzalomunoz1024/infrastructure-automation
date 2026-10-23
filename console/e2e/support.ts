import AxeBuilder from '@axe-core/playwright'
import { expect, type Locator, type Page } from '@playwright/test'

/**
 * Shared by the journeys: axe on the settled page, the phone-width overflow check, signing in and
 * reaching a control with Tab.
 *
 * The suite runs with reduced motion (playwright.config.ts), so the shell's fade-in between pages
 * is instant; `settled` still waits for whatever finite animation is running, because axe reads
 * colours mid-fade otherwise and reports a contrast failure that no person would ever see.
 */
export async function settled(page: Page): Promise<void> {
  await page.evaluate(async () => {
    const running = () => document.getAnimations().filter((a) => a.playState === 'running' && a.effect?.getTiming().iterations !== Infinity)
    const deadline = performance.now() + 3_000
    while (running().length > 0 && performance.now() < deadline) {
      await Promise.race([Promise.allSettled(running().map((a) => a.finished)), new Promise((r) => setTimeout(r, 100))])
    }
  })
}

/** Fails on any serious or critical axe finding, naming the element, its colours and the ratio axe measured. */
export async function expectNoSeriousA11y(page: Page, where: string): Promise<void> {
  await settled(page)
  const results = await new AxeBuilder({ page }).analyze()
  const bad = results.violations.filter((v) => v.impact === 'serious' || v.impact === 'critical')
  const lines = bad.map((v) => {
    const nodes = v.nodes.slice(0, 4).map((n) => {
      const check = n.any[0] ?? n.all[0]
      const data = check?.data ? ` ${JSON.stringify(check.data)}` : ''
      return `  at ${n.target.join(' ')}: ${check?.message ?? ''}${data}\n    ${n.html.replace(/\s+/g, ' ').slice(0, 160)}`
    })
    return `${v.id} (${v.impact}): ${v.help}\n${nodes.join('\n')}`
  })
  expect(lines, `serious or critical axe violations on the ${where}`).toEqual([])
}

/** Signs in through the mock identity provider and lands on `next`. */
export async function signInAs(page: Page, name: RegExp, next = '/'): Promise<void> {
  await page.goto(`/#/signin?next=${encodeURIComponent(next)}`)
  await expect(page.getByRole('heading', { name: 'Sign in', level: 1 })).toBeVisible()
  await page.getByRole('button', { name }).click()
  await expect(page).not.toHaveURL(/#\/signin/)
}

/** Presses Tab until `target` has the focus, as a keyboard user would reach it; fails past `max` presses. */
export async function tabTo(page: Page, target: Locator, max = 40): Promise<void> {
  for (let i = 0; i < max; i++) {
    if (await target.evaluate((el) => el === document.activeElement)) return
    await page.keyboard.press('Tab')
  }
  await expect(target, `reached within ${max} presses of Tab`).toBeFocused()
}

/** The page must not scroll sideways at the width it is checked at. */
export async function expectNoHorizontalOverflow(page: Page): Promise<void> {
  // a resize lays out again over a few frames (resize observers, a toast leaving): wait for it to settle
  const widths = () =>
    page.evaluate(() => {
      const main = document.getElementById('main')
      return { document: document.documentElement.scrollWidth - window.innerWidth, content: main ? main.scrollWidth - main.clientWidth : 0 }
    })
  await expect.poll(async () => (await widths()).document, { message: 'the page is no wider than the viewport', timeout: 5_000 }).toBeLessThanOrEqual(0)
  await expect.poll(async () => (await widths()).content, { message: 'the content does not scroll sideways', timeout: 5_000 }).toBeLessThanOrEqual(0)
}
