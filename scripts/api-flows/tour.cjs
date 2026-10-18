// The README's tour: one screenshot per page a new user meets, in light mode, 1440x900.
// Usage: node tour.cjs [frontend base] [api base] [out dir]
const { chromium } = require('@playwright/test')
const BASE = process.argv[2] || 'http://localhost:3000'
const API = process.argv[3] || 'http://localhost:8080/api/v1'
const OUT = process.argv[4] || require('path').join(__dirname, '..', '..', 'docs', 'screenshots')
const get = async (p) => { const r = await fetch(API + p); if (!r.ok) throw new Error(p + ' ' + r.status); return r.json() }
const items = (d) => (Array.isArray(d) ? d : d.items || [])
;(async () => {
  const [jobs, releases, crs, resources, audit] = await Promise.all([
    get('/jobs?limit=200'), get('/releases'), get('/change-requests'), get('/resources?type=vm'), get('/audit?limit=20'),
  ])
  const all = items(jobs)
  const running = all.find((j) => j.kind === 'batch' && j.status === 'running') || all[0]
  const attention = all.find((j) => j.status === 'needs-attention') || running
  const canary = releases.find((r) => r.status === 'canary') || releases[0]
  const rolledBack = releases.find((r) => r.status === 'rolled-back') || canary
  const openCr = crs.find((c) => c.status === 'open') || crs[0]
  const vm = items(resources)[0]
  const evidence = items(audit)[0]
  const shots = [
    ['02-workflows', '/workflows', 'Workflows: every published workflow, its owner, version and release state'],
    ['03-designer', '/workflows/vm-restart', 'Designer: the n8n-style canvas for a workflow'],
    ['04-catalog', '/catalog', 'Catalog: the generated run forms, one per published workflow'],
    ['05-run-form', '/catalog/vm-restart', 'Run form: inputs from the schema; Preview shows the blast radius before Run'],
    ['06-jobs', '/jobs', 'Jobs: two weeks of history, live jobs on top'],
    ['07-job-attention', attention ? `/jobs/${attention.id}` : null, 'Job detail: steps, inputs and outputs; a failed step waits for a decision'],
    ['08-batch', running ? `/jobs/${running.id}` : null, 'Bulk run: one job per target, in waves, stopping on errors'],
    ['09-approvals', '/approvals', 'Approvals: what waits for an approver'],
    ['10-providers', '/providers', 'Providers: health, success rate and timings per provider'],
    ['11-provider', '/providers/vsphere', 'Provider detail: actions, SLO, errors and the Try it panel'],
    ['12-resources', '/resources', 'Resources: the registry every workflow reads and writes'],
    ['13-lineage', vm ? `/resources/${vm.id}` : null, 'Resource lineage: what it was built from and what depends on it'],
    ['14-releases', '/releases', 'Releases: canaries stage by stage, gated on how they behave'],
    ['15-release-canary', canary ? `/releases/${canary.id}` : null, 'A running canary: traffic split, gates and history'],
    ['16-release-rolled-back', rolledBack ? `/releases/${rolledBack.id}` : null, 'A rolled-back canary: the gate that failed and why'],
    ['17-change-request', openCr ? `/change-requests/${openCr.id}` : null, 'Change request: checks, diff and the release strategy'],
    ['18-audit', '/audit', 'Audit: evidence for every job'],
    ['19-evidence', evidence ? `/audit/${evidence.jobId}` : null, 'Audit evidence: the sealed record of one job'],
    ['20-assistant', '/assistant', 'Assistant: describe a workflow, get a draft to open in the designer'],
    ['21-provider-wizard', '/providers/new', 'Add provider: onboarding from an OpenAPI document, a playbook repo or a module'],
  ].filter(([, p]) => p)
  const browser = await chromium.launch()
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, colorScheme: 'light', deviceScaleFactor: 1 })
  const page = await ctx.newPage()
  const captions = []
  for (const [name, path, caption] of shots) {
    await page.goto(`${BASE}/#${path}`)
    await page.waitForLoadState('networkidle', { timeout: 8000 }).catch(() => {})
    await page.waitForTimeout(1500)
    await page.screenshot({ path: `${OUT}/${name}.png`, fullPage: false })
    captions.push(`![${caption}](docs/screenshots/${name}.png)\n*${caption}*`)
    console.log(`shot ${name} #${path}`)
  }
  await browser.close()
  require('fs').writeFileSync(`${OUT}/captions.md`, captions.join('\n\n') + '\n')
  console.log(`${shots.length} screenshots in ${OUT}`)
})().catch((e) => { console.error(e); process.exit(1) })
