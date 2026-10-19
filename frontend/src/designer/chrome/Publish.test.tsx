/**
 * Publish with provider drafts: the review of each draft the workflow calls, the rows the sheet
 * shows for it (ready, what blocks it, the checks that did not run, in review), the change
 * requests it opens (the providers', then the workflow's requiring them) and the linked change
 * requests the drawer lists.
 */
import { renderToString } from 'react-dom/server'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { WorkflowDocument } from '@iaf/workflow-checks'
import { api } from '../../lib/api'
import type { ChangeRequestDetail, CommandAccepted, ProviderValidation } from '../../lib/types'
import { blankAction } from '../../components/provider-editor/state'
import { newProviderDraft, type ProviderDraft } from '../model/providerDrafts'
import { EMPTY_REGISTRY } from '../model/registry'
import { DesignerContext, DesignerStore } from '../model/store'
import { closedOf, LinkedChangeRequests, openChangeRequests, openDraftProvider, ProviderPublishError, ProviderRows, providerRows, providersFirst, ProvidersToPublish, publishFailure, publishHold, PublishSheetBody, publishWithProviders, reviewOf, settleProvider, type ProviderReviewState, type ProviderRow } from './Publish'

const text = (html: string) =>
  html
    .replace(/<!--.*?-->/g, '')
    .replace(/<[^>]+>/g, ' ')
    .replace(/&#x27;/g, "'")
    .replace(/\s+/g, ' ')

const passed = (id: string, label: string) => ({ id, label, status: 'passed' as const })

const READY: ProviderValidation = {
  checks: [passed('schema', 'Schema'), { id: 'samples', label: 'Samples', status: 'warning', detail: 'one action has none' }],
  problems: [{ level: 'warning', code: 'provider.sample', message: 'snapshot.list has no sample' }],
  review: { ready: true, checks: [passed('connection', 'Connection tested')], problems: [] },
}

const accepted = (id: string): CommandAccepted => ({ id, status: 'accepted' })

const draft = (name: string, patch: Partial<ProviderDraft> = {}): ProviderDraft => newProviderDraft('http', { title: `${name} API`, name, owner: 'team-compute', actions: [blankAction({ id: 'snapshot.create', path: '/snapshots', method: 'POST', effect: 'create', retry: 'unsafe' })], ...patch })

describe("a draft's review before Publish", () => {
  it('is ready when no check failed, no problem is an error and the review is ready', () => {
    expect(reviewOf(READY)).toEqual({ state: 'ready' })
    // a file that is not a provider has no review: its checks say so
    expect(reviewOf({ ...READY, review: null })).toEqual({ state: 'ready' })
  })

  it("names each failed check and error, the file's and the review's, once", () => {
    const v: ProviderValidation = {
      checks: [passed('schema', 'Schema'), { id: 'reachable', label: 'Endpoints reachable', status: 'failed', detail: 'dev answered 503' }],
      problems: [
        { level: 'error', code: 'provider.effect', message: 'snapshot.delete deletes something: say what it removes' },
        { level: 'warning', code: 'provider.sample', message: 'no sample' },
        { level: 'error', code: 'provider.effect', message: 'snapshot.delete deletes something: say what it removes' },
      ],
      review: { ready: false, checks: [{ id: 'tries', label: 'Every action tried', status: 'failed', detail: 'snapshot.create was not tried in dev' }], problems: [] },
    }
    expect(reviewOf(v)).toEqual({ state: 'blocked', reasons: ['Endpoints reachable: dev answered 503', 'snapshot.delete deletes something: say what it removes', 'Every action tried: snapshot.create was not tried in dev'] })
    expect(reviewOf({ ...READY, review: { ready: false, checks: [], problems: [] } })).toEqual({ state: 'blocked', reasons: ['The review of the evidence is not ready yet.'] })
  })
})

describe('the providers the Publish sheet lists', () => {
  const rows: ProviderRow[] = [
    { key: 'a', draft: draft('backup'), review: { state: 'ready' }, changeRequest: null },
    { key: 'b', draft: { ...draft('dns'), origin: 'edit', publishedVersion: '1.3.0', version: '1.3.1', editing: 'dns' }, review: { state: 'blocked', reasons: ['Every action tried: record.create was not tried in dev'] }, changeRequest: null },
    { key: 'c', draft: draft('storage'), review: { state: 'failed', message: 'The server did not answer' }, changeRequest: null },
    { key: 'd', draft: draft('lb'), review: { state: 'checking' }, changeRequest: null },
    { key: 'cr_5', draft: draft('ipam'), review: null, changeRequest: 'cr_5' },
  ]

  it('shows each one with its review: ready, what blocks it, the checks that did not run, in review', () => {
    const html = renderToString(<ProviderRows rows={rows} onOpen={() => undefined} onRetry={() => undefined} />)
    const t = text(html)
    expect(t).toContain('backup API')
    expect(t).toContain('Ready')
    expect(t).toContain('Not ready')
    expect(t).toContain('Every action tried: record.create was not tried in dev')
    expect(t).toContain('1.3.1, next after 1.3.0')
    expect(t).toContain('Checks did not run')
    expect(t).toContain('The server did not answer')
    expect(t).toContain('Check again')
    expect(t).toContain('Checking')
    expect(t).toContain('cr_5')
    expect(t).toContain('its change request merges first')
    // the blocked one and the one whose checks did not run open in the Provider sheet
    expect(t.match(/Open provider/g)).toHaveLength(2)
    expect(t.match(/ draft /g)).toHaveLength(4)
    expect(t.match(/ in review /g)).toHaveLength(1)
  })
})

describe('the change requests a Publish opens', () => {
  afterEach(() => vi.restoreAllMocks())

  it("opens one per draft with the draft's strategy, then the workflow's requiring them and the ones in review", async () => {
    const backup = draft('backup', { strategy: 'all' })
    const dns = draft('dns')
    const provider = vi.spyOn(api, 'publishProvider').mockResolvedValueOnce(accepted('cr_8')).mockResolvedValueOnce(accepted('cr_9'))
    const workflow = vi.spyOn(api, 'publishWorkflow').mockResolvedValue(accepted('cr_10'))
    const opened: [string, string][] = []
    const result = await openChangeRequests(
      { workflow: 'vm-backup', request: { strategy: 'canary', notes: 'First version' }, drafts: [{ draft: backup, yaml: 'backup.yaml' }, { draft: dns, yaml: 'dns.yaml' }], inReview: ['cr_5'] },
      (d, id) => opened.push([d.name, id]),
    )
    expect(result.id).toBe('cr_10')
    expect(provider.mock.calls).toEqual([
      ['backup.yaml', 'all'],
      ['dns.yaml', 'canary'],
    ])
    expect(opened).toEqual([
      ['backup', 'cr_8'],
      ['dns', 'cr_9'],
    ])
    expect(workflow).toHaveBeenCalledWith('vm-backup', { strategy: 'canary', notes: 'First version', requires: ['cr_8', 'cr_9', 'cr_5'] })
  })

  it("stops at a draft that cannot be published, naming it, and opens no workflow's change request", async () => {
    vi.spyOn(api, 'publishProvider')
      .mockResolvedValueOnce(accepted('cr_8'))
      .mockRejectedValueOnce(new Error('another change request for dns is open'))
    const workflow = vi.spyOn(api, 'publishWorkflow')
    const opened: string[] = []
    const run = openChangeRequests({ workflow: 'vm-backup', request: {}, drafts: [{ draft: draft('backup'), yaml: 'b' }, { draft: draft('dns'), yaml: 'd' }], inReview: [] }, (_, id) => opened.push(id))
    await expect(run).rejects.toBeInstanceOf(ProviderPublishError)
    await expect(run).rejects.toMatchObject({ provider: 'dns', message: 'another change request for dns is open' })
    expect(opened).toEqual(['cr_8'])
    expect(workflow).not.toHaveBeenCalled()
  })
})

describe('the Publish sheet of a workflow that calls a draft', () => {
  const doc = (provider: string): WorkflowDocument =>
    ({
      apiVersion: 'iaf.platform/v1',
      kind: 'Workflow',
      metadata: { name: 'vm-backup', title: 'VM backup', noun: 'vm', verb: 'backup', owner: 'team-compute', version: '1.0.0' },
      policy: { rbac: ['team-compute'] },
      workflow: { do: [{ snap: { name: 'Snapshot', call: 'provider', with: { provider, action: 'snapshot.create', input: {} } } }] },
    }) as WorkflowDocument

  beforeEach(() => {
    const data = new Map<string, string>()
    vi.stubGlobal('localStorage', { getItem: (k: string) => data.get(k) ?? null, setItem: (k: string, v: string) => void data.set(k, v), removeItem: (k: string) => void data.delete(k) })
  })

  afterEach(() => vi.unstubAllGlobals())

  const render = (store: DesignerStore) =>
    renderToString(
      <DesignerContext.Provider value={store}>
        <PublishSheetBody onOpened={() => undefined} />
      </DesignerContext.Provider>,
    )
  const confirm = (html: string) => /<button[^>]*data-testid="publish-confirm"[^>]*>/.exec(html)?.[0] ?? ''

  it('lists the draft, checking its review, and holds Publish until the review answers', () => {
    const store = new DesignerStore({ name: 'vm-backup', doc: doc('backup'), registry: EMPTY_REGISTRY })
    store.upsertProviderDraft(draft('backup'))
    store.upsertProviderDraft(draft('unused'))
    const html = render(store)
    const t = text(html)
    expect(t).toContain('Providers to publish first')
    expect(t).toContain("This workflow calls a provider that is not published yet. Each gets its own change request, which merges first; this workflow's change request requires it.")
    expect(t).toContain('backup API')
    expect(t).not.toContain('unused API')
    expect(t).toContain('Checking')
    expect(t).toContain('Open change requests')
    expect(confirm(html)).toMatch(/\sdisabled=""/)
  })

  it('lists a provider in review with its change request, and publishes the workflow requiring it', () => {
    const store = new DesignerStore({ name: 'vm-backup', doc: doc('backup'), registry: EMPTY_REGISTRY })
    const d = draft('backup')
    store.upsertProviderDraft(d)
    store.providerDraftPublished(d.id, 'cr_8')
    const html = render(store)
    const t = text(html)
    expect(t).toContain('Providers to publish first')
    expect(t).toContain('backup API in review')
    expect(t).toContain('cr_8')
    expect(t).not.toContain('Checking')
    expect(t).toContain('Open change request')
    expect(t).not.toContain('Open change requests')
    expect(confirm(html)).not.toMatch(/\sdisabled=""/)
  })

  it('is the plain sheet when the workflow calls no draft', () => {
    const store = new DesignerStore({ name: 'vm-backup', doc: doc('vsphere'), registry: EMPTY_REGISTRY })
    store.upsertProviderDraft(draft('backup'))
    const html = render(store)
    expect(text(html)).not.toContain('Providers to publish first')
    expect(text(html)).toContain('Open change request')
    expect(confirm(html)).not.toMatch(/\sdisabled=""/)
  })
})

describe("the Publish sheet's hold", () => {
  const ready = { state: 'ready' } as const
  const hold = (reviews: ProviderReviewState[], patch: Partial<Parameters<typeof publishHold>[0]> = {}) => publishHold({ name: 'vm-backup', errors: 0, stagesInvalid: false, reviews, ...patch })

  it('holds Publish while a review runs, and while one is blocked or did not run', () => {
    expect(hold([ready, { state: 'checking' }])).toEqual({ reviewing: true, notReady: 0, disabled: true })
    expect(hold([ready, { state: 'blocked', reasons: ['Every action tried: snapshot.create was not tried in dev'] }])).toEqual({ reviewing: false, notReady: 1, disabled: true })
    expect(hold([{ state: 'failed', message: 'The server did not answer' }, ready])).toEqual({ reviewing: false, notReady: 1, disabled: true })
  })

  it('lets Publish go once every review is ready, or with no draft, unless the workflow itself stops it', () => {
    expect(hold([ready, ready])).toEqual({ reviewing: false, notReady: 0, disabled: false })
    expect(hold([])).toEqual({ reviewing: false, notReady: 0, disabled: false })
    expect(hold([ready], { errors: 1 }).disabled).toBe(true)
    expect(hold([ready], { stagesInvalid: true }).disabled).toBe(true)
    expect(hold([ready], { name: null }).disabled).toBe(true)
  })

  it("says why it waits and offers to open a provider whose review is not ready", () => {
    const blocked = { key: 'a', draft: draft('backup'), review: { state: 'blocked', reasons: ['Every action tried: snapshot.create was not tried in dev'] }, changeRequest: null } satisfies ProviderRow
    const t = text(renderToString(<ProvidersToPublish rows={[blocked]} notReady={1} onOpen={() => undefined} onRetry={() => undefined} />))
    expect(t).toContain("Publishing waits until every provider's review is ready.")
    expect(t).toContain('Every action tried: snapshot.create was not tried in dev')
    expect(t).toContain('Open provider')
    const fine = text(renderToString(<ProvidersToPublish rows={[{ ...blocked, review: { state: 'ready' } }]} notReady={0} onOpen={() => undefined} onRetry={() => undefined} />))
    expect(fine).not.toContain('Publishing waits')
    expect(fine).not.toContain('Open provider')
  })

  it('lists each draft with its review, in order, then each provider in review', () => {
    const backup = draft('backup')
    const dns = draft('dns')
    const ipam = draft('ipam')
    const rows = providerRows({ drafts: [{ draft: backup, yaml: 'b' }, { draft: dns, yaml: 'd' }], inReview: [{ changeRequest: 'cr_5', draft: ipam, yaml: 'i' }] }, [{ state: 'ready' }])
    expect(rows.map((r) => [r.key, r.review, r.changeRequest])).toEqual([
      [backup.id, { state: 'ready' }, null],
      [dns.id, { state: 'checking' }, null],
      ['cr_5', null, 'cr_5'],
    ])
  })
})

describe("the Publish sheet's providers, from the store", () => {
  const doc: WorkflowDocument = {
    apiVersion: 'iaf.platform/v1',
    kind: 'Workflow',
    metadata: { name: 'vm-backup', title: 'VM backup', noun: 'vm', verb: 'backup', owner: 'team-compute', version: '1.0.0' },
    policy: { rbac: ['team-compute'] },
    workflow: {
      do: [{ snap: { name: 'Snapshot', call: 'provider', with: { provider: 'backup', action: 'snapshot.create', input: {} } } }, { record: { name: 'Record', call: 'provider', with: { provider: 'dns', action: 'snapshot.create', input: {} } } }],
    },
  } as WorkflowDocument

  beforeEach(() => {
    const data = new Map<string, string>()
    vi.stubGlobal('localStorage', { getItem: (k: string) => data.get(k) ?? null, setItem: (k: string, v: string) => void data.set(k, v), removeItem: (k: string) => void data.delete(k) })
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  it('opens a provider in the Provider sheet: a new one on its draft, the next patch of a published one on that provider', () => {
    const store = new DesignerStore({ name: 'vm-backup', doc, registry: EMPTY_REGISTRY })
    const backup = draft('backup')
    store.upsertProviderDraft(backup)
    openDraftProvider(store, backup)
    expect(store.getState().providerSheet).toEqual({ entry: 'publish', draft: backup.id, provider: null, target: null })
    const dns = { ...draft('dns'), origin: 'edit' as const, editing: 'dns', publishedVersion: '1.3.0', version: '1.3.1' }
    store.upsertProviderDraft(dns)
    openDraftProvider(store, dns)
    expect(store.getState().providerSheet).toMatchObject({ entry: 'publish', draft: dns.id, provider: 'dns' })
  })

  it('clears each draft whose change request opened, names the one that failed, and opens only the others when tried again', async () => {
    const store = new DesignerStore({ name: 'vm-backup', doc, registry: EMPTY_REGISTRY })
    const backup = draft('backup', { strategy: 'all' })
    const dns = draft('dns')
    store.upsertProviderDraft(backup)
    store.upsertProviderDraft(dns)
    const provider = vi
      .spyOn(api, 'publishProvider')
      .mockResolvedValueOnce(accepted('cr_8'))
      .mockRejectedValueOnce(new Error('another change request for dns is open.  '))
      .mockResolvedValueOnce(accepted('cr_9'))
    const workflow = vi.spyOn(api, 'publishWorkflow').mockResolvedValue(accepted('cr_10'))

    const failed = await publishWithProviders(store, 'vm-backup', { strategy: 'canary' }).catch((e: unknown) => e)
    expect(failed).toBeInstanceOf(ProviderPublishError)
    expect(publishFailure(failed)).toEqual({ title: 'Could not publish dns', message: "another change request for dns is open. The workflow's change request was not opened." })
    expect(workflow).not.toHaveBeenCalled()
    // backup's change request is open: its draft is cleared, in review; dns is still a draft
    expect(Object.values(store.getState().providerDrafts).map((d) => d.name)).toEqual(['dns'])
    expect(store.getState().providersInReview.map((r) => [r.draft.name, r.changeRequest])).toEqual([['backup', 'cr_8']])
    expect(providersFirst(store).drafts.map((p) => p.draft.name)).toEqual(['dns'])

    const result = await publishWithProviders(store, 'vm-backup', { strategy: 'canary', notes: 'First version' })
    expect(result.id).toBe('cr_10')
    expect(provider.mock.calls.map(([yaml, strategy]) => [/name: (\w+)/.exec(yaml)?.[1], strategy])).toEqual([
      ['backup', 'all'],
      ['dns', 'canary'],
      ['dns', 'canary'],
    ])
    expect(workflow).toHaveBeenCalledWith('vm-backup', { strategy: 'canary', notes: 'First version', requires: ['cr_9', 'cr_8'] })
    expect(store.getState().providerDrafts).toEqual({})
    expect(store.getState().providersInReview.map((r) => r.changeRequest)).toEqual(['cr_8', 'cr_9'])
    expect(publishFailure(new Error('Backend unreachable'))).toEqual({ title: 'Could not publish', message: 'Backend unreachable' })
  })
})

describe('the linked change requests the drawer lists', () => {
  const cr = (id: string, name: string, status: ChangeRequestDetail['status']): ChangeRequestDetail => ({
    id,
    kind: 'provider',
    name,
    version: '1.0.0',
    branch: `provider/${name}-1.0.0`,
    title: `Publish ${name} 1.0.0`,
    author: 'gonzalo',
    status,
    strategy: 'canary',
    createdAt: '2026-10-18T10:00:00Z',
    checks: [passed('schema', 'Schema'), passed('review', 'Review')],
    diff: '',
    yaml: '',
  })

  it('shows each with its status and says the providers merge first', () => {
    const t = text(renderToString(<LinkedChangeRequests ids={['cr_8', 'cr_9']} changes={[cr('cr_8', 'backup', 'merged'), cr('cr_9', 'dns', 'open')]} onFollow={() => undefined} />))
    expect(t).toContain('Linked change requests')
    expect(t).toContain('The provider change requests merge first: this one merges once they have.')
    expect(t).toContain('backup 1.0.0')
    expect(t).toContain('Provider · cr_9 · checks 2 passed')
    expect(t).toContain('merged')
    expect(t).toContain('open')
  })

  it('says when one was closed: this one cannot merge, the provider is fixed and published again', () => {
    const one = text(renderToString(<LinkedChangeRequests ids={['cr_8', 'cr_9']} changes={[cr('cr_8', 'backup', 'closed'), cr('cr_9', 'dns', 'merged')]} onFollow={() => undefined} />))
    expect(one).toContain('cr_8 was closed: this change request cannot merge. Close it, fix the provider and publish again.')
    expect(one).not.toContain('merge first')
    const two = text(renderToString(<LinkedChangeRequests ids={['cr_7', 'cr_8', 'cr_9']} changes={[cr('cr_7', 'a', 'closed'), cr('cr_8', 'b', 'closed'), cr('cr_9', 'c', 'closed')]} onFollow={() => undefined} />))
    expect(two).toContain('cr_7, cr_8 and cr_9 were closed: this change request cannot merge. Close it, fix the providers and publish again.')
    expect(closedOf(['cr_8', 'cr_9'], [cr('cr_8', 'backup', 'closed'), cr('cr_9', 'dns', 'open')])).toEqual(['cr_8'])
    expect(closedOf(['cr_8'], undefined)).toEqual([])
  })

  it("settles a provider's change request for the canvas: merged, published; closed, a draft again", () => {
    const store = { providerChangeMerged: vi.fn(async () => undefined), providerChangeClosed: vi.fn(() => true) }
    settleProvider(store, cr('cr_8', 'backup', 'merged'))
    settleProvider(store, cr('cr_9', 'dns', 'closed'))
    settleProvider(store, cr('cr_5', 'ipam', 'open'))
    settleProvider(store, { ...cr('cr_7', 'vm-restart', 'closed'), kind: 'workflow' })
    expect(store.providerChangeMerged.mock.calls).toEqual([['cr_8']])
    expect(store.providerChangeClosed.mock.calls).toEqual([['cr_9']])
  })

  it('says the workflow can merge once they all have', () => {
    const t = text(renderToString(<LinkedChangeRequests ids={['cr_8']} changes={[cr('cr_8', 'backup', 'merged')]} onFollow={() => undefined} />))
    expect(t).toContain('The providers this version calls are published: it can merge.')
    // still loading: the id stands in for the name
    expect(text(renderToString(<LinkedChangeRequests ids={['cr_8']} changes={undefined} onFollow={() => undefined} />))).toContain('cr_8 Loading…')
  })
})
