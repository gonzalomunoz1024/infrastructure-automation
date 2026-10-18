import { describe, expect, it } from 'vitest'
import { renderToString } from 'react-dom/server'
import type { Batch, EvidenceEntry, JobStep, JobSummary, Lineage, Preview, Problem, Resource, Schema } from '../lib/types'
import { BatchPanel, retryLabel, retryableRows } from '../components/BatchPanel'
import { EvidenceTimeline, summarizeEntry } from '../components/EvidenceTimeline'
import { LineageGraph, lineageGraph, lineageLayout } from '../components/LineageGraph'
import { PreviewCard } from '../components/PreviewCard'
import { ProblemList, normalizeField, problemsFor, unfieldedProblems } from '../components/ProblemList'
import { SchemaForm, coerceBySchema, defaultsFor, isComplete, splitProblems, validateAgainst } from '../components/SchemaForm'
import { StepTimeline } from '../components/StepTimeline'

/*
 * Render tests for the composites the pages are built from, server-rendered like
 * components.test.tsx: what each one says, links to and marks, for a realistic input.
 */

const text = (html: string) => html.replace(/<!--.*?-->/g, '')

/* ---------------------------------------------------------------- SchemaForm */

const RESTART_INPUT: Schema = {
  type: 'object',
  required: ['vmId', 'reason'],
  properties: {
    vmId: { type: 'string', title: 'VM', 'x-resource': { type: 'vm', orCreateWith: 'create-vm' } },
    reason: { type: 'string', title: 'Reason', description: 'Shown to the approver' },
    environment: { type: 'string', enum: ['dev', 'test', 'prod'], default: 'dev' },
    graceful: { type: 'boolean', default: true },
    waitSeconds: { type: 'integer', minimum: 0, maximum: 3600, default: 60 },
    tags: { type: 'array', items: { type: 'string' } },
    labels: { type: 'object' },
  },
}

describe('SchemaForm', () => {
  it('renders one labelled control per property, with ids from the prefix', () => {
    const html = text(renderToString(<SchemaForm schema={RESTART_INPUT} value={defaultsFor(RESTART_INPUT)} onChange={() => {}} idPrefix="run" />))
    for (const id of ['run-reason', 'run-graceful', 'run-waitSeconds', 'run-tags', 'run-labels']) expect(html).toContain(`id="${id}"`)
    // listboxes name themselves; no label points at an id that does not exist
    expect(html).toContain('aria-label="VM"')
    expect(html).toContain('aria-label="environment"')
    expect(html).not.toContain('for="run-vmId"')
    expect(html).toContain('VM')
    expect(html).toContain('Reason')
    expect(html).toContain('Shown to the approver')
    // the enum is a listbox trigger showing the default, the boolean a switch
    expect(html).toContain('aria-haspopup="listbox"')
    expect(html).toContain('role="switch"')
    expect(html).toContain('aria-checked="true"')
    // the x-resource field offers the workflow that creates one
    expect(html).toContain('create-vm')
  })

  it('marks required fields and shows a problem under its field', () => {
    const problems: Problem[] = [{ level: 'error', code: 'required', field: 'input.reason', message: 'Reason is required' }]
    const html = text(renderToString(<SchemaForm schema={RESTART_INPUT} value={{}} onChange={() => {}} problems={problems} idPrefix="p" />))
    expect(html).toContain('Reason is required')
    expect(html).toContain('aria-invalid="true"')
  })

  it('says so when the workflow takes no input', () => {
    expect(renderToString(<SchemaForm schema={{ type: 'object', properties: {} }} value={{}} onChange={() => {}} />)).toContain('This workflow takes no input.')
  })

  it('validates like the server would before asking it', () => {
    const v = { vmId: 'vm-1042', environment: 'staging', waitSeconds: 7200 }
    const problems = validateAgainst(RESTART_INPUT, v)
    expect(problems.map((p) => `${p.code}:${p.field}`).sort()).toEqual(['enum:environment', 'maximum:waitSeconds', 'required:reason'])
    expect(isComplete(RESTART_INPUT, v)).toBe(false)
    expect(isComplete(RESTART_INPUT, { ...v, reason: 'kernel patch' })).toBe(true)
  })

  it('types CSV cells by the schema and splits fielded problems from general ones', () => {
    expect(coerceBySchema(RESTART_INPUT, { vmId: 'vm-1', graceful: 'no', waitSeconds: '30', tags: 'a, b', labels: '{"team":"compute"}', reason: '' })).toEqual({
      vmId: 'vm-1',
      graceful: false,
      waitSeconds: 30,
      tags: ['a', 'b'],
      labels: { team: 'compute' },
      reason: '',
    })
    const { fielded, general } = splitProblems(
      [
        { level: 'error', code: 'required', field: '$.reason', message: 'Reason is required' },
        { level: 'error', code: 'policy', message: 'prod needs a ticket' },
      ],
      RESTART_INPUT,
    )
    expect(fielded).toHaveLength(1)
    expect(general.map((p) => p.code)).toEqual(['policy'])
  })
})

/* ---------------------------------------------------------------- PreviewCard */

const PREVIEW: Preview = {
  workflow: 'vm-restart',
  version: '1.4.0',
  steps: [
    { id: 'check-backup', name: 'Check no backup is running', effect: 'read' },
    { id: 'restart', name: 'Restart the VM', effect: 'change', detail: 'vsphere vm.restart' },
  ],
  affectedResources: [{ id: 'vm-1042', type: 'vm', name: 'web-1042', via: 'restart' }],
  cost: { monthly: 0, currency: 'EUR', detail: 'No new resources' },
  policy: { decision: 'needs-approval', rules: [{ id: 'prod-approval', result: 'needs-approval', message: 'prod changes need the on-call lead' }], engine: 'opa' },
}

describe('PreviewCard', () => {
  it('shows the decision, the steps with their effect, what is affected and the policy', () => {
    const html = text(renderToString(<PreviewCard preview={PREVIEW} />))
    expect(html).toContain('Needs approval')
    expect(html).toContain('Check no backup is running')
    expect(html).toContain('Restart the VM')
    expect(html).toContain('change')
    expect(html).toContain('web-1042')
    expect(html).toContain('href="#/resources/vm-1042"')
    expect(html).toContain('prod changes need the on-call lead')
    expect(html).toContain('opa')
    expect(html).toContain('EUR')
  })

  it('names an allowed run plainly', () => {
    const html = text(renderToString(<PreviewCard preview={{ ...PREVIEW, policy: { decision: 'allow', rules: [], engine: 'opa' }, cost: undefined }} compact />))
    expect(html).toContain('Allowed')
    expect(html).not.toContain('Monthly cost')
  })
})

/* ---------------------------------------------------------------- StepTimeline */

const STEPS: JobStep[] = [
  { id: 'check-backup', name: 'Check no backup is running', type: 'provider', status: 'succeeded', attempt: 1, startedAt: '2026-10-17T09:00:00Z', endedAt: '2026-10-17T09:00:02Z', durationMs: 2100, output: { items: [] } },
  { id: 'approve', name: 'Approval for prod', type: 'approval', status: 'succeeded', attempt: 1, durationMs: 60_000 },
  { id: 'restart', name: 'Restart the VM', type: 'provider', status: 'failed', attempt: 3, durationMs: 31_000, error: { class: 'timeout', message: 'vCenter did not answer in 30s', retryable: true } },
  { id: 'notify', name: 'Tell the channel', type: 'run', status: 'pending', attempt: 0 },
]
const CHILD: JobSummary = { id: 'job-child-1', workflow: 'notify-channel', title: 'Notify #compute', version: '1.0.0', status: 'queued', requester: 'gonzalo', channel: 'rest', startedAt: '2026-10-17T09:02:00Z', kind: 'single' }

describe('StepTimeline', () => {
  it('lists every step with its kind, attempt, duration and state', () => {
    const html = text(renderToString(<StepTimeline steps={STEPS} children={[CHILD]} highlight="restart" defaultOpen={['restart']} />))
    for (const s of STEPS) expect(html).toContain(s.name)
    expect(html).toContain('provider')
    expect(html).toContain('approval')
    expect(html).toContain('attempt 3')
    expect(html).toContain('2.1s')
    expect(html).toContain('vCenter did not answer in 30s')
    expect(html).toContain('timeout')
  })

  it('links a run step to its child job', () => {
    const html = text(renderToString(<StepTimeline steps={STEPS} children={[CHILD]} />))
    expect(html).toContain('href="#/jobs/job-child-1"')
  })
})

/* ---------------------------------------------------------------- BatchPanel */

const BATCH: Batch = {
  rollout: { waves: [5, 25, 100], maxParallel: 10, stopIfFailedPercent: 10 },
  waves: [
    { index: 0, percent: 5, size: 1, status: 'succeeded' },
    { index: 1, percent: 25, size: 4, status: 'running' },
    { index: 2, percent: 100, size: 15, status: 'pending' },
  ],
  rows: [
    { index: 0, input: { vmId: 'vm-1' }, jobId: 'job-r0', status: 'succeeded' },
    { index: 1, input: { vmId: 'vm-2' }, jobId: 'job-r1', status: 'failed', error: 'vCenter timeout' },
    { index: 2, input: { vmId: 'vm-3' }, status: 'running' },
  ],
}

describe('BatchPanel', () => {
  it('shows the waves, the rows and their jobs', () => {
    const html = text(renderToString(<BatchPanel batch={BATCH} />))
    expect(html).toContain('Wave 1 · 5%')
    expect(html).toContain('Wave 2 · 25%')
    expect(html).toContain('Wave 3 · 100%')
    expect(html).toContain('vm-2')
    expect(html).toContain('vCenter timeout')
    expect(html).toContain('href="#/jobs/job-r1"')
    expect(html).not.toContain('Retry 1 failed row')
  })

  it('offers no retry while the batch runs: the backend retries the rows of an ended batch only', () => {
    const html = text(renderToString(<BatchPanel batch={BATCH} onRetryFailed={async () => {}} />))
    expect(html).toContain('vCenter timeout')
    expect(html).not.toContain('Retry')
    expect(text(renderToString(<BatchPanel batch={BATCH} ended={false} onRetryFailed={async () => {}} />))).not.toContain('Retry')
  })

  it('offers to retry the rows that did not succeed once it stopped, and says why it stopped', () => {
    const html = text(renderToString(<BatchPanel batch={STOPPED} onRetryFailed={async () => {}} />))
    expect(html).toContain('Retry 4 rows')
    expect(html).toContain('2 failed, 2 skipped: they run again in a new wave')
    expect(html).toContain('stopped after wave 1: 2 of 3 rows failed (more than 10%)')
    // the waves ended (done, done, skipped): none is the current one
    expect(html).not.toContain('aria-current="step"')
    expect(text(renderToString(<BatchPanel batch={STOPPED} ended onRetryFailed={async () => {}} />))).toContain('Retry 4 rows')
    expect(text(renderToString(<BatchPanel batch={STOPPED} />))).not.toContain('Retry')
  })

  it('counts the rows the backend retries: failed, cancelled and skipped', () => {
    expect(retryableRows(STOPPED)).toEqual({ failed: 2, cancelled: 0, skipped: 2, total: 4 })
    expect(retryLabel(retryableRows(STOPPED))).toEqual({ label: 'Retry 4 rows', detail: '2 failed, 2 skipped: they run again in a new wave' })
    const onlyFailed = { ...STOPPED, rows: STOPPED.rows.filter((r) => r.status !== 'skipped') }
    expect(retryLabel(retryableRows(onlyFailed))).toEqual({ label: 'Retry 2 failed rows' })
    const cancelled = { ...STOPPED, rows: [{ index: 0, input: {}, status: 'cancelled' }] }
    expect(retryLabel(retryableRows(cancelled))).toEqual({ label: 'Retry 1 row', detail: '1 cancelled: they run again in a new wave' })
  })
})

/** A batch the backend stopped after its second wave: waves done, done, skipped; the job failed. */
const STOPPED: Batch = {
  rollout: { waves: [20, 60, 100], maxParallel: 2, stopIfFailedPercent: 10 },
  waves: [
    { index: 0, percent: 20, size: 1, status: 'done', startedAt: '2026-10-17T09:00:00Z', endedAt: '2026-10-17T09:01:00Z' },
    { index: 1, percent: 60, size: 2, status: 'done', startedAt: '2026-10-17T09:01:00Z', endedAt: '2026-10-17T09:03:00Z' },
    { index: 2, percent: 100, size: 2, status: 'skipped', endedAt: '2026-10-17T09:03:00Z' },
  ],
  rows: [
    { index: 0, input: { vmId: 'vm-1' }, jobId: 'job-r0', status: 'succeeded' },
    { index: 1, input: { vmId: 'vm-2' }, jobId: 'job-r1', status: 'failed', error: 'vcenter_timeout at restart' },
    { index: 2, input: { vmId: 'vm-3' }, jobId: 'job-r2', status: 'failed', error: 'failed' },
    { index: 3, input: { vmId: 'vm-4' }, status: 'skipped' },
    { index: 4, input: { vmId: 'vm-5' }, status: 'skipped' },
  ],
  stoppedReason: 'stopped after wave 1: 2 of 3 rows failed (more than 10%)',
}

/* ---------------------------------------------------------------- EvidenceTimeline */

const ENTRIES: EvidenceEntry[] = [
  { seq: 1, at: '2026-10-17T09:00:00Z', kind: 'requested', actor: 'gonzalo', data: { channel: 'catalog', workflow: 'vm-restart', version: '1.4.0' }, hash: 'a1'.repeat(32), previous: '0'.repeat(64) },
  { seq: 2, at: '2026-10-17T09:00:01Z', kind: 'policy', data: { decision: 'needs-approval', engine: 'opa' }, hash: 'b2'.repeat(32), previous: 'a1'.repeat(32) },
  { seq: 3, at: '2026-10-17T09:01:00Z', kind: 'approval', actor: 'maria', data: { status: 'approved', decidedBy: 'maria' }, hash: 'c3'.repeat(32), previous: 'b2'.repeat(32) },
  { seq: 4, at: '2026-10-17T09:02:00Z', kind: 'changed', data: { type: 'vm', id: 'vm-1042', effect: 'change' }, hash: 'd4'.repeat(32), previous: 'c3'.repeat(32) },
  { seq: 5, at: '2026-10-17T09:02:30Z', kind: 'closed', data: { status: 'succeeded' }, hash: 'e5'.repeat(32), previous: 'd4'.repeat(32) },
]

describe('EvidenceTimeline', () => {
  it('reads each entry as one sentence', () => {
    expect(summarizeEntry(ENTRIES[0])).toBe('Run requested by gonzalo via catalog for vm-restart 1.4.0')
    expect(summarizeEntry(ENTRIES[1])).toBe('Policy decided: needs-approval (opa)')
    expect(summarizeEntry(ENTRIES[2])).toBe('Approval approved by maria')
    expect(summarizeEntry(ENTRIES[3])).toBe('Changed vm vm-1042 (change)')
    expect(summarizeEntry(ENTRIES[4])).toBe('Run closed: succeeded')
  })

  it('renders the chain in order and marks where it broke', () => {
    const intact = text(renderToString(<EvidenceTimeline entries={ENTRIES} />))
    expect(intact).toContain('Run requested by gonzalo')
    expect(intact).toContain('Run closed: succeeded')
    expect(intact).not.toContain('chain broken')
    const broken = text(renderToString(<EvidenceTimeline entries={ENTRIES} brokenAt={4} />))
    expect(broken.match(/chain broken/g)).toHaveLength(2)
  })

  it('says when nothing is recorded', () => {
    expect(renderToString(<EvidenceTimeline entries={[]} />)).toContain('No evidence recorded yet.')
  })
})

/* ---------------------------------------------------------------- LineageGraph */

const res = (id: string, type: string, name: string): Resource => ({
  id,
  type,
  name,
  status: 'ready',
  attributes: {},
  owner: 'team-platform',
  visibility: 'org',
  createdBy: { jobId: `job-${id}`, workflow: 'build-vm-image' },
  builtFrom: [],
  createdAt: '2026-10-01T00:00:00Z',
})

describe('LineageGraph', () => {
  it('draws what it was built from, the resource, and what depends on it, each a link', () => {
    const lineage: Lineage = { resource: res('img-77', 'image', 'ubuntu-2404-cis1'), builtFrom: [res('tpl-1', 'template', 'ubuntu-2404')], dependents: [res('vm-1042', 'vm', 'web-1042'), res('vm-1043', 'vm', 'web-1043')] }
    const html = text(renderToString(<LineageGraph lineage={lineage} />))
    expect(html).toContain('<svg')
    for (const name of ['ubuntu-2404-cis1', 'ubuntu-2404', 'web-1042', 'web-1043']) expect(html).toContain(name)
    expect(html).toContain('href="#/resources/vm-1042"')
    expect(html).toContain('href="#/resources/tpl-1"')
    expect(html.match(/<path/g)?.length).toBeGreaterThanOrEqual(3)
  })

  it('says when either side is empty', () => {
    const html = text(renderToString(<LineageGraph lineage={{ resource: res('vol-1', 'volume', 'data-1'), builtFrom: [], dependents: [] }} />))
    expect(html).toContain('not built from another resource')
    expect(html).toContain('nothing depends on it')
  })

  /* the backend's transitive answer: a base image, the image, two VMs from it, a DNS record from one VM */
  const BASE = res('img-60', 'image', 'ubuntu-2404')
  const IMG = res('img-77', 'image', 'ubuntu-2404-cis1')
  const VM1 = res('vm-1042', 'vm', 'web-1042')
  const VM2 = res('vm-1043', 'vm', 'web-1043')
  const DNS = res('dns-1042', 'dns-record', 'web-1042.prod')
  const TRANSITIVE: Lineage = {
    resource: IMG,
    builtFrom: [BASE],
    dependents: [VM1, VM2],
    nodes: [IMG, BASE, VM1, VM2, DNS],
    edges: [
      { from: 'img-60', to: 'img-77', via: 'build-vm-image' },
      { from: 'img-77', to: 'vm-1042', via: 'create-vm' },
      { from: 'img-77', to: 'vm-1043', via: 'create-vm' },
      { from: 'vm-1042', to: 'dns-1042', via: 'register-dns' },
    ],
  }
  const ids = (layout: ReturnType<typeof lineageLayout>) => layout.columns.map((c) => [c.depth, c.nodes.map((n) => n.id)])

  it('draws the whole graph the backend answers, two hops away included, each edge saying how', () => {
    expect(ids(lineageLayout(TRANSITIVE))).toEqual([
      [-1, ['img-60']],
      [0, ['img-77']],
      [1, ['vm-1042', 'vm-1043']],
      [2, ['dns-1042']],
    ])
    const html = text(renderToString(<LineageGraph lineage={TRANSITIVE} />))
    expect(html).toContain('href="#/resources/dns-1042"')
    expect(html).toContain('web-1042.prod')
    expect(html.match(/<path/g)?.length).toBe(4)
    expect(html).toContain('<title>web-1042.prod was built from web-1042 by register-dns</title>')
    expect(html).toContain('aria-label="Lineage of ubuntu-2404-cis1: built from 1, 2 dependents; 1 upstream and 3 downstream in all"')
    expect(html).not.toContain('nothing depends on it')
  })

  it('reads the direct lists as the same graph when the backend sends no nodes and edges', () => {
    const direct: Lineage = { resource: IMG, builtFrom: [BASE], dependents: [VM1, VM2] }
    expect(lineageGraph(direct).edges).toEqual([
      { from: 'img-60', to: 'img-77', via: 'build-vm-image' },
      { from: 'img-77', to: 'vm-1042', via: 'build-vm-image' },
      { from: 'img-77', to: 'vm-1043', via: 'build-vm-image' },
    ])
    expect(ids(lineageLayout(direct))).toEqual([
      [-1, ['img-60']],
      [0, ['img-77']],
      [1, ['vm-1042', 'vm-1043']],
    ])
    expect(text(renderToString(<LineageGraph lineage={direct} />))).toContain('aria-label="Lineage of ubuntu-2404-cis1: built from 1, 2 dependents"')
  })

  it('puts a resource on its longest chain, so every edge points right', () => {
    // the image was built from both the base and a hardened base, itself built from the base
    const HARD = res('img-61', 'image', 'ubuntu-2404-hard')
    const layout = lineageLayout({
      resource: IMG,
      builtFrom: [BASE, HARD],
      dependents: [],
      nodes: [IMG, BASE, HARD],
      edges: [
        { from: 'img-60', to: 'img-77' },
        { from: 'img-61', to: 'img-77' },
        { from: 'img-60', to: 'img-61' },
      ],
    })
    expect(ids(layout)).toEqual([
      [-2, ['img-60']],
      [-1, ['img-61']],
      [0, ['img-77']],
    ])
    expect(layout.upstream).toBe(2)
    expect(layout.downstream).toBe(0)
  })

  it('stays finite on a cycle, drops edges to resources it was not given, and keeps each edge once', () => {
    const A = res('a', 'thing', 'a')
    const B = res('b', 'thing', 'b')
    const layout = lineageLayout({
      resource: IMG,
      builtFrom: [],
      dependents: [A],
      nodes: [A, B],
      edges: [
        { from: 'img-77', to: 'a' },
        { from: 'a', to: 'b' },
        { from: 'b', to: 'a' },
        { from: 'a', to: 'b' },
        { from: 'b', to: 'ghost' },
      ],
    })
    expect(layout.columns.flatMap((c) => c.nodes.map((n) => n.id)).sort()).toEqual(['a', 'b', 'img-77'])
    expect(layout.columns.every((c) => Math.abs(c.depth) <= 3)).toBe(true)
    expect(layout.edges).toHaveLength(3)
    // the resource itself is placed even when the backend's nodes leave it out
    expect(layout.columns.find((c) => c.depth === 0)?.nodes.map((n) => n.id)).toEqual(['img-77'])
  })

  it('orders a column after the resources it hangs from, so edges do not cross', () => {
    const P1 = res('p1', 'thing', 'z-first-parent')
    const P2 = res('p2', 'thing', 'a-second-parent')
    const C1 = res('c1', 'thing', 'z-child-of-second')
    const C2 = res('c2', 'thing', 'a-child-of-first')
    const layout = lineageLayout({
      resource: IMG,
      builtFrom: [],
      dependents: [P1, P2],
      nodes: [IMG, P1, P2, C1, C2],
      edges: [
        { from: 'img-77', to: 'p1' },
        { from: 'img-77', to: 'p2' },
        { from: 'p1', to: 'c2' },
        { from: 'p2', to: 'c1' },
      ],
    })
    // the parents by name; each child under its own parent, though by name it would come first
    expect(ids(layout)).toEqual([
      [0, ['img-77']],
      [1, ['p2', 'p1']],
      [2, ['c1', 'c2']],
    ])
  })
})

/* ---------------------------------------------------------------- ProblemList */

const PROBLEMS: Problem[] = [
  { level: 'error', code: 'approval.missing', step: 'delete-vm', message: 'Deleting in prod needs an approval step before it', fix: { label: 'Insert an approval', kind: 'insert-approval' } },
  { level: 'warning', code: 'retry.unsafe', step: 'restart', field: 'retry', message: 'vm.restart is unsafe to retry' },
]

describe('ProblemList', () => {
  it('lists each problem with its code, step and field', () => {
    const html = text(renderToString(<ProblemList problems={PROBLEMS} />))
    expect(html).toContain('aria-label="Problems"')
    expect(html).toContain('Deleting in prod needs an approval step before it')
    expect(html).toContain('approval.missing')
    expect(html).toContain('delete-vm')
    expect(html).toContain('vm.restart is unsafe to retry')
    expect(html).not.toContain('Insert an approval') // no fix handler, no button
  })

  it('offers the fix when a handler is given, and the empty label when clean', () => {
    expect(renderToString(<ProblemList problems={PROBLEMS} onFix={() => {}} />)).toContain('Insert an approval')
    expect(renderToString(<ProblemList problems={[]} />)).toBe('')
    expect(renderToString(<ProblemList problems={[]} emptyLabel="No problems" />)).toContain('No problems')
  })

  it('matches problems to fields however the server spells the path', () => {
    expect(normalizeField('$.input.vmId')).toBe('vmId')
    expect(normalizeField('/body/properties/vmId')).toBe('vmId')
    const ps: Problem[] = [
      { level: 'error', code: 'required', field: 'input.vmId', message: 'VM is required' },
      { level: 'error', code: 'policy', message: 'Not in a change window' },
    ]
    expect(problemsFor(ps, 'vmId').map((p) => p.message)).toEqual(['VM is required'])
    expect(unfieldedProblems(ps, ['vmId']).map((p) => p.code)).toEqual(['policy'])
  })
})
