import { fileURLToPath } from 'node:url'
import { expect, test, type Page } from '@playwright/test'
import { expectNoHorizontalOverflow, expectNoSeriousA11y, signInAs, tabTo } from './support'

/*
 * The executive home (WP 9.5) in mock mode: from the console home's card to the page; the period
 * and the scope switched by pointer and by keys, each kept in the URL; spend by team read from
 * the cost service's report by cost centre (`/console/v1/cost`); a chart's table opened
 * from the keyboard; the dark appearance; a viewer reading it; a person whose requests no ticket
 * export covers; an account left out, a refused scope and its explanation, with the home's card
 * gone; spend the role may not read; the skeleton, a failed load and its retry; and a phone. Axe
 * runs on every state.
 */

const shots = fileURLToPath(new URL('./screenshots/', import.meta.url))
const shot = (name: string) => `${shots}${name}.png`

const ADMIN = /Continue as Gonzalo Munoz/
const VIEWER = /Continue as Mia Chen/
const DATA_ONLY = /Continue as Raj Patel/
const COMPUTE = '123456789012'

const heading = (page: Page) => page.getByRole('heading', { name: 'Executive home', level: 1 })
const period = (page: Page) => page.getByRole('radiogroup', { name: /^Period: / })
const scope = (page: Page) => page.getByRole('radiogroup', { name: /^Scope: / })
const tiles = (page: Page) => page.getByRole('list', { name: 'Headline numbers' })
const tile = (page: Page, label: string) => tiles(page).getByRole('listitem').filter({ hasText: label })

/** Goes to a route inside the app, keeping what the page holds in memory (the mock's directory among it). */
async function goInside(page: Page, hash: string) {
  await page.evaluate((h) => {
    window.location.hash = h
  }, hash)
}

/** Keeps every URL the page fetches from here on, in the page. */
async function recordAsks(page: Page) {
  await page.evaluate(() => {
    const w = window as unknown as { __asked: string[] }
    w.__asked = []
    const real = window.fetch.bind(window)
    window.fetch = (input, init) => {
      w.__asked.push(typeof input === 'string' ? input : input instanceof URL ? input.href : input.url)
      return real(input, init)
    }
  })
}

/** The last cost report the page asked for: its path, its query, and how many days it spans. */
async function spendAsked(page: Page): Promise<Record<string, string | number>> {
  const asked = await page.evaluate(() => (window as unknown as { __asked: string[] }).__asked)
  const url = new URL(asked.filter((u) => /\/console\/v1\/cost\?/.test(u)).at(-1) ?? '/none', 'http://console.test')
  const query = Object.fromEntries(url.searchParams)
  return { path: url.pathname, ...query, days: (Date.parse(query.to) - Date.parse(query.from)) / 86_400_000 }
}

/**
 * Writes a policy denying an action in an account and binds it to a role, in the browser's mock
 * directory: `insights:get` becomes policy no-insights, statement hide-insights.
 */
async function deny(page: Page, accountId: string, roleId: string, action: string) {
  await page.evaluate(
    ([account, role, act]) => {
      type Dir = { policies: Record<string, unknown[]>; roles: { roleId: string; policies?: string[] }[] }
      const dir = (window as unknown as { __consoleMock: { data: { directory: Dir } } }).__consoleMock.data.directory
      const service = act.split(':')[0]
      dir.policies[account].push({
        policyId: `no-${service}`,
        name: `no-${service}`,
        builtIn: false,
        version: 1,
        statements: [{ sid: `hide-${service}`, effect: 'deny', actions: [act], resources: [`wfrn:wf:*:*:${account}:*`] }],
      })
      const bound = dir.roles.find((r) => r.roleId === role)
      if (bound) bound.policies = [...(bound.policies ?? []), `no-${service}`]
    },
    [accountId, roleId, action],
  )
}

test.describe('the executive home in mock mode', () => {
  test('from the home’s card to the page, the period and the scope by pointer and by keys, and in the dark', async ({ page }) => {
    await signInAs(page, ADMIN)
    // the card: the last 30 days in the session's account
    const card = page.locator('section').filter({ has: page.getByRole('heading', { name: 'Last 30 days' }) })
    await expect(card.getByText('What self-service did in Compute.')).toBeVisible()
    await expect(card.getByText('Resources managed')).toBeVisible()
    await expect(card.getByText('221', { exact: true })).toBeVisible()
    await expect(card.getByText('94%', { exact: true })).toBeVisible()
    await expect(card.getByText('0 of 19', { exact: true })).toBeVisible()
    await expectNoSeriousA11y(page, 'home with the executive home’s card')
    await recordAsks(page)

    // the same view on the page, read from the cache
    await card.getByRole('link', { name: 'Executive home' }).click()
    await expect(page).toHaveURL(/#\/insights\?period=30d&scope=account$/)
    await expect(heading(page)).toBeVisible()
    await expect(period(page)).toHaveAccessibleName('Period: 30 days')
    await expect(scope(page)).toHaveAccessibleName('Scope: This account')
    await expect(page.getByText('Covers Compute.')).toBeVisible()
    await expect(tile(page, 'Resources managed')).toContainText('221')
    await expect(tile(page, 'Self-served')).toContainText('44 of 47 requests')
    // spend by team: the cost service's report by cost centre, over the numbers' days and accounts
    await expect(page.getByRole('table', { name: 'Spend by team' }).getByRole('row').filter({ hasText: 'CC-4100' })).toBeVisible()
    expect(await spendAsked(page)).toMatchObject({ path: '/console/v1/cost', groupBy: 'costCentre', scope: 'account', days: 30 })

    // every account, by pointer
    await scope(page).getByRole('radio', { name: 'All my accounts' }).click()
    await expect(page).toHaveURL(/scope=organization/)
    await expect(scope(page)).toHaveAccessibleName('Scope: All my accounts')
    await expect(page.getByText('Covers Compute and Data.')).toBeVisible()
    await expect(tile(page, 'Resources managed')).toContainText('223')
    await expect(tile(page, 'Self-served')).toContainText('92%')
    await expect(tile(page, 'Self-served')).toContainText('46 of 50 requests')
    await expect(tile(page, 'Median lead time')).toContainText('12 min')
    await expect(tile(page, 'Median lead time')).toContainText('against 2 days by ticket')
    await expect(tile(page, 'Hours saved')).toContainText('1,933 hours')
    await expect(tile(page, 'Drift fixed')).toContainText('0 of 19')
    await expect(tile(page, 'Drift fixed')).toContainText('19 open now')
    await expect(tile(page, 'Spend in the period')).toContainText(/\$[\d,]+\.\d\d/)
    await expect(page.getByRole('status').filter({ hasText: 'Showing the last 30 days in all your accounts.' })).toBeAttached()
    const spend = page.getByRole('table', { name: 'Spend by team' })
    await expect(spend.getByRole('row').filter({ hasText: 'CC-4100' })).toContainText('Compute · team-compute')
    await expect(spend.getByRole('row').filter({ hasText: 'CC-4200' })).toContainText('Data · team-data')
    expect(await spendAsked(page)).toMatchObject({ path: '/console/v1/cost', groupBy: 'costCentre', scope: 'organization', days: 30 })
    await expect(page.getByText('Estimates from list prices, not invoices').first()).toBeVisible()
    await expect(page.getByRole('img', { name: /^Resources by service: Compute 216, Platform 5, Data 2$/ })).toBeVisible()
    await page.screenshot({ path: shot('insights-desktop'), fullPage: true })
    await expectNoSeriousA11y(page, 'executive home')

    // the period by keys: the checked segment is the stop, the arrows move it
    await tabTo(page, period(page).getByRole('radio', { name: '30 days' }))
    await page.keyboard.press('ArrowLeft')
    await expect(page).toHaveURL(/period=7d/)
    await expect(period(page)).toHaveAccessibleName('Period: 7 days')
    await expect(page.getByRole('status').filter({ hasText: 'Showing the last 7 days in all your accounts.' })).toBeAttached()
    await expect(page.getByRole('img', { name: /^Active people by week, weeks of 12 Oct to 19 Oct$/ })).toBeVisible()
    await page.keyboard.press('End')
    await expect(page).toHaveURL(/period=90d/)
    await expect(page.getByRole('img', { name: /^Active people by week, weeks of 20 Jul to 19 Oct$/ })).toBeVisible()

    // a chart's numbers, opened from the keyboard
    const twin = page.locator('figure').filter({ hasText: 'Active people' }).locator('summary')
    await tabTo(page, twin, 60)
    await page.keyboard.press('Enter')
    const people = page.getByRole('table', { name: 'Active people' })
    await expect(people).toBeVisible()
    await expect(people.getByRole('row').filter({ hasText: '19 Oct' })).toContainText('4')
    await expectNoSeriousA11y(page, 'executive home with a chart’s table open')

    // the scope by keys
    await tabTo(page, scope(page).getByRole('radio', { name: 'All my accounts' }), 80)
    await page.keyboard.press('ArrowLeft')
    await expect(page).toHaveURL(/period=90d&scope=account/)
    await expect(page.getByText('Covers Compute.')).toBeVisible()

    // the dark appearance, on every account over 30 days
    await scope(page).getByRole('radio', { name: 'All my accounts' }).click()
    await period(page).getByRole('radio', { name: '30 days' }).click()
    await expect(tile(page, 'Resources managed')).toContainText('223')
    await page.getByRole('button', { name: 'Switch to dark mode' }).click()
    await expect(page.locator('html')).toHaveClass(/dark/)
    await page.screenshot({ path: shot('insights-dark-desktop'), fullPage: true })
    await expectNoSeriousA11y(page, 'executive home in the dark')
    await page.getByRole('button', { name: 'Switch to light mode' }).click()
    await expect(page.locator('html')).not.toHaveClass(/dark/)
  })

  test('a viewer reads every number, nothing to change', async ({ page }) => {
    await signInAs(page, VIEWER, '/insights')
    await expect(heading(page)).toBeVisible()
    await expect(page.getByText('Covers Compute.')).toBeVisible()
    await expect(tile(page, 'Resources managed')).toContainText('221')
    await expect(tile(page, 'Hours saved')).toContainText('1,933 hours')
    await expect(page.locator('#main').getByRole('button', { name: /create|delete|edit|update/i })).toHaveCount(0)
    await expectNoSeriousA11y(page, 'executive home for a viewer')
  })

  test('where no ticket export covers the requests, the lead time stands alone and hours saved say why', async ({ page }) => {
    await signInAs(page, DATA_ONLY, '/insights')
    await expect(heading(page)).toBeVisible()
    await expect(page.getByText('Covers Data.')).toBeVisible()
    await expect(tile(page, 'Median lead time')).toContainText('5 s')
    await expect(tile(page, 'Median lead time')).toContainText('No ticket baseline; 90% within 7 min')
    await expect(tile(page, 'Hours saved')).toContainText('Needs a baseline')
    await expect(tile(page, 'Hours saved')).toContainText('No ticket export covers these requests')
    await expect(tile(page, 'Drift fixed')).toContainText('None found')
    await expectNoSeriousA11y(page, 'executive home without a ticket baseline')
  })

  test('an account left out is named, a refused scope explains itself, and the home’s card steps aside', async ({ page }) => {
    await signInAs(page, ADMIN)
    await expect(page.getByRole('heading', { name: 'Last 30 days' })).toBeVisible()
    await deny(page, COMPUTE, 'account-admin', 'insights:get')

    await goInside(page, '#/insights')
    await expect(heading(page)).toBeVisible()
    await expect(page.getByText('Covers Data. Left out: Compute, where your role may not read these numbers.')).toBeVisible()
    await expect(tile(page, 'Resources managed')).toContainText('2')
    await expectNoSeriousA11y(page, 'executive home with an account left out')

    await scope(page).getByRole('radio', { name: 'This account' }).click()
    await expect(page.getByText('You can’t read the executive home here')).toBeVisible()
    await expect(page.getByText('denied by policy no-insights, statement hide-insights')).toBeVisible()
    await expect(tiles(page)).toHaveCount(0)
    await expectNoSeriousA11y(page, 'executive home refused')
    await page.getByRole('button', { name: 'See why' }).click()
    const why = page.getByRole('dialog', { name: /You can.t read the executive home here/ })
    await expect(why).toBeVisible()
    await expect(why).toContainText('hide-insights')
    await expectNoSeriousA11y(page, 'the refusal of the executive home')
    await page.keyboard.press('Escape')
    await expect(why).toBeHidden()

    // the home asks again, is refused, and shows no card at all
    await goInside(page, '#/')
    await expect(page.getByRole('heading', { name: 'Console home', level: 1 })).toBeVisible()
    await expect(page.getByRole('heading', { name: 'Last 30 days' })).toHaveCount(0)
  })

  test('spend the role may not read is refused in its card and tile alone, and an account left out of it is named', async ({ page }) => {
    await signInAs(page, ADMIN)
    await expect(page.getByRole('heading', { name: 'Last 30 days' })).toBeVisible()
    await deny(page, COMPUTE, 'account-admin', 'cost:list')

    // this account: the numbers stand, spend says why it is missing
    await goInside(page, '#/insights?period=30d&scope=account')
    await expect(heading(page)).toBeVisible()
    await expect(tile(page, 'Resources managed')).toContainText('221')
    await expect(tile(page, 'Spend in the period')).toContainText('Not available')
    await expect(tile(page, 'Spend in the period')).toContainText('Your role may not read cost')
    await expect(page.getByText('You can’t read spend here')).toBeVisible()
    await expect(page.getByText('denied by policy no-cost, statement hide-cost')).toBeVisible()
    await expectNoSeriousA11y(page, 'executive home with spend refused')
    await page.getByRole('button', { name: 'See why' }).click()
    const why = page.getByRole('dialog', { name: /You can.t read cost here/ })
    await expect(why).toBeVisible()
    await expect(why).toContainText('hide-cost')
    await page.keyboard.press('Escape')
    await expect(why).toBeHidden()

    // every account: the spend of the others, and the one left out named under it
    await scope(page).getByRole('radio', { name: 'All my accounts' }).click()
    const spend = page.getByRole('table', { name: 'Spend by team' })
    await expect(spend.getByRole('row').filter({ hasText: 'CC-4200' })).toContainText('Data · team-data')
    await expect(spend.getByRole('row').filter({ hasText: 'CC-4100' })).toHaveCount(0)
    await expect(page.getByText('Left out: Compute, where your role may not read cost.')).toBeVisible()
    await expect(tile(page, 'Spend in the period')).toContainText(/\$[\d,]+\.\d\d/)
    await expectNoSeriousA11y(page, 'executive home with spend for some accounts')
  })

  test('the skeleton holds the page, a failed load offers a retry, and the retry draws the numbers', async ({ page }) => {
    await signInAs(page, ADMIN)
    await expect(page.getByRole('heading', { name: 'Last 30 days' })).toBeVisible()

    // hold the numbers' answer: the skeleton stands in for the tiles and the cards
    await page.evaluate(() => {
      const w = window as unknown as { __release: () => void }
      const answered = new Promise<void>((resolve) => (w.__release = resolve))
      const real = window.fetch.bind(window)
      window.fetch = async (input, init) => {
        const url = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url
        if (url.includes('/console/v1/insights')) await answered
        return real(input, init)
      }
    })
    await goInside(page, '#/insights?period=7d&scope=organization')
    await expect(page.getByRole('status', { name: 'Loading the numbers' })).toBeVisible()
    await expectNoSeriousA11y(page, 'executive home loading')
    await page.evaluate(() => (window as unknown as { __release: () => void }).__release())
    await expect(tile(page, 'Resources managed')).toContainText('223')

    // the BFF fails the next view: the page says so and offers a retry
    await page.evaluate(() => {
      type Handle = (req: { url: string }) => unknown
      const mock = (window as unknown as { __consoleMock: { handle: Handle }; __restore: () => void }).__consoleMock
      const handle = mock.handle
      ;(window as unknown as { __restore: () => void }).__restore = () => {
        mock.handle = handle
      }
      mock.handle = (req) => (req.url.startsWith('/console/v1/insights') ? { status: 502, body: { error: 'The numbers are not available right now' } } : handle(req))
    })
    await period(page).getByRole('radio', { name: '90 days' }).click()
    await expect(page.getByText('Could not load the numbers')).toBeVisible()
    await expect(page.getByText('The numbers are not available right now')).toBeVisible()
    await expectNoSeriousA11y(page, 'executive home failed')
    await page.evaluate(() => (window as unknown as { __restore: () => void }).__restore())
    await page.getByRole('button', { name: 'Try again' }).click()
    await expect(tile(page, 'Resources managed')).toContainText('223')
    await expect(page.getByRole('img', { name: /^Active people by week, weeks of 20 Jul to 19 Oct$/ })).toBeVisible()
  })

  test('fits a phone', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 })
    await signInAs(page, ADMIN, '/insights')
    await expect(heading(page)).toBeVisible()
    await expect(tile(page, 'Resources managed')).toContainText('223')
    await expect(page.getByRole('table', { name: 'Spend by team' })).toBeVisible()
    await expectNoHorizontalOverflow(page)
    await page.screenshot({ path: shot('insights-phone'), fullPage: true })
    await expectNoSeriousA11y(page, 'executive home on a phone')
  })
})
