import AxeBuilder from '@axe-core/playwright'
import { expect, type Page } from '@playwright/test'

/**
 * Shared by the journeys: axe on the settled page, and the phone-width overflow check.
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

/** The page must not scroll sideways at the width it is checked at. */
export async function expectNoHorizontalOverflow(page: Page): Promise<void> {
  const widths = await page.evaluate(() => {
    const main = document.getElementById('main')
    return { document: document.documentElement.scrollWidth, viewport: window.innerWidth, content: main ? main.scrollWidth - main.clientWidth : 0 }
  })
  expect(widths.document, 'the page is no wider than the viewport').toBeLessThanOrEqual(widths.viewport)
  expect(widths.content, 'the content does not scroll sideways').toBeLessThanOrEqual(0)
}
