// Visits every page of the app against the real backend and reports console errors,
// failed API calls and visible error states. Usage: node smoke.cjs [baseUrl] [apiUrl]
// Screenshots go to $SMOKE_OUT (default: a smoke/ folder under the system temp directory).
process.env.PLAYWRIGHT_BROWSERS_PATH ??= '/opt/pw-browsers'
const { chromium } = require('@playwright/test')
const BASE = process.argv[2] || 'http://localhost:3000'
const API = process.argv[3] || 'http://localhost:8080/api/v1'
const fs = require('fs')
const os = require('os')
const path = require('path')
const OUT = process.env.SMOKE_OUT || path.join(os.tmpdir(), 'iaf-smoke')
fs.mkdirSync(OUT, { recursive: true })
const get = async (p) => { const r = await fetch(API + p); if (!r.ok) throw new Error(p + ' ' + r.status); return r.json() }
;(async () => {
  const [workflows, jobs, providers, resources, releases, audit, crs, approvals] = await Promise.all([
    get('/workflows'), get('/jobs?limit=50'), get('/providers'), get('/resources'), get('/releases'), get('/audit'), get('/change-requests'), get('/approvals'),
  ])
  const pick = (xs, f = (x) => x) => (xs.length ? f(xs[0]) : null)
  const running = jobs.find((j) => j.status === 'running' || j.status === 'waiting') || jobs[0]
  const routes = [
    ['home', '/'], ['workflows', '/workflows'], ['designer', `/workflows/${pick(workflows, (w) => w.name)}`],
    ['versions', `/workflows/${pick(workflows, (w) => w.name)}/versions`], ['catalog', '/catalog'],
    ['catalog-run', `/catalog/${pick(workflows, (w) => w.name)}`], ['jobs', '/jobs'], ['job', `/jobs/${running.id}`],
    ['approvals', '/approvals'], ['providers', '/providers'], ['provider-new', '/providers/new'],
    ['provider', `/providers/${pick(providers, (p) => p.name)}`], ['resources', '/resources'], ['resource', `/resources/${pick(resources, (r) => r.id)}`],
    ['releases', '/releases'], ['release', releases.length ? `/releases/${releases[0].id}` : null], ['audit', '/audit'],
    ['audit-record', audit.length ? `/audit/${audit[0].jobId}` : null], ['assistant', '/assistant'], ['credentials', '/credentials'],
    ['settings', '/settings'], ['change-request', crs.length ? `/change-requests/${crs[0].id}` : null], ['templates', '/templates'],
  ].filter(([, p]) => p)
  console.log(`data: ${workflows.length} workflows, ${jobs.length} jobs, ${providers.length} providers, ${resources.length} resources, ${releases.length} releases, ${audit.length} audit, ${crs.length} CRs, ${approvals.length} approvals`)
  const browser = await chromium.launch()
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } })
  const page = await ctx.newPage()
  let issues = []
  page.on('console', (m) => { if (m.type() === 'error') issues.push(`console: ${m.text().slice(0, 300)}`) })
  page.on('pageerror', (e) => issues.push(`pageerror: ${String(e).slice(0, 300)}`))
  page.on('response', (r) => { if (r.url().includes('/api/') && r.status() >= 400) issues.push(`HTTP ${r.status()} ${r.request().method()} ${r.url().replace(/^https?:\/\/[^/]+/, '')}`) })
  page.on('requestfailed', (r) => { if (!r.url().includes('/events') && !r.url().includes('/ws/')) issues.push(`failed: ${r.url()} ${r.failure()?.errorText}`) })
  let bad = 0
  for (const [name, path] of routes) {
    issues = []
    await page.goto(`${BASE}/#${path}`)
    await page.waitForLoadState('networkidle', { timeout: 8000 }).catch(() => {})
    await page.waitForTimeout(1200)
    const text = await page.locator('body').innerText()
    for (const s of ['Could not load', 'Something went wrong', 'Not found', 'undefined', 'NaN', '[object Object]']) {
      if (text.includes(s)) issues.push(`visible text: "${s}"`)
    }
    await page.screenshot({ path: `${OUT}/${name}.png`, fullPage: false })
    if (issues.length) bad++
    console.log(`${issues.length ? 'ISSUES' : 'ok    '} ${name.padEnd(15)} #${path}${issues.length ? '\n   - ' + [...new Set(issues)].join('\n   - ') : ''}`)
  }
  await browser.close()
  console.log(`${routes.length - bad}/${routes.length} pages clean`)
})().catch((e) => { console.error(e); process.exit(1) })
