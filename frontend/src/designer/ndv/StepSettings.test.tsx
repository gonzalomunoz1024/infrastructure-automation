import { renderToString } from 'react-dom/server'
import { describe, expect, it } from 'vitest'
import { parse } from 'yaml'
import type { WorkflowDocument } from '@iaf/workflow-checks'
import { findStepNode } from '../model/graph'
import { draftYaml } from '../model/providerDrafts'
import { EMPTY_REGISTRY } from '../model/registry'
import { DesignerContext, DesignerStore } from '../model/store'
import { isSettingsField, writeField } from './fields'
import { draftFromProvider } from '../model/providerDrafts'
import { errorRows, withErrorClasses, withPolicy, type ProviderStepInfo } from './providerStep'
import { StepSettings } from './StepSettings'
import { editDraft, loadingInfo, newDraft, newDraftInfo, publishedInfo, settledDetail, unknownInfo, vsphereDetail } from './testProviders'

/*
 * A step paused on its failure waits for a person's decision for its `attentionTimeout` (seven
 * days when unset) before it aborts: the Settings tab edits it beside Pause on failure.
 */

const notifyWith = (extra: Record<string, unknown>): WorkflowDocument =>
  ({
    apiVersion: 'iaf.platform/v1',
    kind: 'Workflow',
    metadata: { name: 'tell', noun: 'channel', verb: 'tell', owner: 'team-compute', version: '1.0.0' },
    workflow: { do: [{ tell: { name: 'Tell the channel', notify: { channel: '#ops', message: 'done' }, ...extra } }] },
  }) as WorkflowDocument

function storeOf(doc: WorkflowDocument): DesignerStore {
  return new DesignerStore({ name: 'tell', doc, registry: EMPTY_REGISTRY })
}

function render(doc: WorkflowDocument): string {
  const store = storeOf(doc)
  const node = findStepNode(store.getState().graph, 'tell')
  if (!node) throw new Error('no tell step')
  return renderToString(
    <DesignerContext.Provider value={store}>
      <StepSettings stepId="tell" body={node.data.body} step={node.data.step} doc={store.getState().doc} ownCtx={{}} allProblems={[]} />
    </DesignerContext.Provider>,
  )
}

/** The markup of one field, from its data-field to the next one. */
function field(html: string, name: string): string {
  const start = html.indexOf(`data-field="${name}"`)
  expect(start, `no field ${name}`).toBeGreaterThanOrEqual(0)
  const next = html.indexOf('data-field="', start + 1)
  return html.slice(start, next < 0 ? undefined : next)
}

describe('the decision timeout of a step', () => {
  it('is offered while the step pauses on its failure, empty for the seven days of the default', () => {
    const html = field(render(notifyWith({})), 'attentionTimeout')
    expect(html).toContain('Decision timeout')
    expect(html).toContain('placeholder="P7D"')
    expect(html).toContain('seven days when empty')
    expect(field(render(notifyWith({ attentionTimeout: 'PT4H' })), 'attentionTimeout')).toContain('value="PT4H"')
  })

  it('is not offered when the step fails at once', () => {
    expect(render(notifyWith({ pauseOnFailure: false }))).not.toContain('data-field="attentionTimeout"')
  })

  it('is written beside pauseOnFailure, not under the step’s own block, and counted on the Settings tab', () => {
    const store = storeOf(notifyWith({}))
    store.updateStep('tell', (b) => writeField(b, 'attentionTimeout', 'PT4H'))
    expect(store.getState().doc.workflow?.do?.[0]).toEqual({ tell: { name: 'Tell the channel', notify: { channel: '#ops', message: 'done' }, attentionTimeout: 'PT4H' } })
    store.updateStep('tell', (b) => writeField(b, 'attentionTimeout', undefined))
    expect(store.getState().doc.workflow?.do?.[0]).toEqual({ tell: { name: 'Tell the channel', notify: { channel: '#ops', message: 'done' } } })
    expect(isSettingsField('attentionTimeout')).toBe(true)
  })
})

/*
 * A provider step's Settings tab also shows its action as every workflow calling it shares it:
 * effect, retry policy, approval, plan, what a delete removes, the action's own SLO and the error
 * classes. They are the provider's, so an edit goes into its draft, never into the workflow; the
 * step's own retry, timeout and pause stay where they were.
 */

const checkVm = {
  apiVersion: 'iaf.platform/v1',
  kind: 'Workflow',
  metadata: { name: 'vm-check', noun: 'vm', verb: 'check', owner: 'team-compute', version: '1.0.0' },
  workflow: { do: [{ check: { name: 'Check VM exists', call: 'provider', with: { provider: 'vsphere', action: 'vm.get', input: { id: '{{ $input.vmId }}' } } } }] },
} as WorkflowDocument

function renderProvider(info: ProviderStepInfo, readOnly = false): string {
  const store = new DesignerStore({ name: 'vm-check', doc: checkVm, registry: EMPTY_REGISTRY })
  const node = findStepNode(store.getState().graph, 'check')
  if (!node) throw new Error('no check step')
  return renderToString(
    <DesignerContext.Provider value={store}>
      <StepSettings stepId="check" body={node.data.body} step={node.data.step} doc={store.getState().doc} ownCtx={{}} allProblems={[]} provider={info} readOnly={readOnly} />
    </DesignerContext.Provider>,
  ).replace(/<!--.*?-->/g, '')
}

/** The opening tag of the control with this accessible name. */
function control(html: string, label: string): string {
  const at = html.indexOf(`aria-label="${label}"`)
  expect(at, `no control ${label}`).toBeGreaterThanOrEqual(0)
  return html.slice(html.lastIndexOf('<', at), html.indexOf('>', at) + 1)
}

/** The text a select shows: what follows its trigger's tag. */
function shown(html: string, label: string): string {
  const at = html.indexOf(`aria-label="${label}"`)
  return html.slice(html.indexOf('>', at) + 1, html.indexOf('</button>', at)).replace(/<[^>]+>/g, '')
}

type ProviderFile = { metadata: { version: string }; spec: { actions: Record<string, unknown>[]; errors?: Record<string, unknown>[] } }
const fileAction = (yaml: string | null, id: string) => (parse(yaml ?? '') as ProviderFile).spec.actions.find((a) => a.id === id)

describe('the action a provider step shares with every workflow', () => {
  it('is shown after the step’s own settings, with the published values, and says whose it is', () => {
    const html = renderProvider(publishedInfo())
    expect(html).toContain('Action (shared by every workflow)')
    expect(html).toContain('vm.get of VMware vSphere')
    expect(html).toContain('published 1.3.0')
    expect(html).toContain('for every workflow that calls it')
    expect(html).toContain('The first change starts a draft of the provider’s next version.')
    expect(html.indexOf('Pause on failure')).toBeLessThan(html.indexOf('Action (shared by every workflow)'))
    expect(shown(html, 'Effect of the action')).toContain('read')
    expect(shown(html, 'Retry policy of the action')).toContain('safe')
    expect(control(html, 'Retry policy of the action')).not.toContain('disabled')
    // the published file's error classes, as rows to edit
    expect(html).toContain('value="not_found"')
    expect(html).toContain('value="rate_limited"')
    expect(html).toContain('Add error class')
    expect(html).toContain('Open provider')
  })

  it('names an open change request of the provider, which a draft has to wait for', () => {
    const html = renderProvider(publishedInfo())
    expect(html).toContain('href="#/change-requests/cr_41"')
    expect(html).toContain('(1.3.1) is still open: vsphere takes no other change until it merges or closes, so a draft started here can be published only after that.')
    expect(renderProvider(publishedInfo('vm.get', editDraft()))).toContain('so the draft can be published only after that')
    expect(renderProvider(publishedInfo('vm.get', undefined, settledDetail()))).not.toContain('is still open')
  })

  it('says so for a provider the platform does not know and no draft holds', () => {
    const html = renderProvider(unknownInfo())
    expect(html).toContain('vspher is not a registered provider, and no draft of it is kept in this browser')
    expect(html).not.toContain('Effect of the action')
  })

  it('is not there for a step that calls no provider, and waits for the provider to load', () => {
    expect(render(notifyWith({}))).not.toContain('Action (shared by every workflow)')
    const loading = renderProvider(loadingInfo())
    expect(loading).toContain('Loading vsphere…')
    expect(control(loading, 'Effect of the action')).toContain('disabled')
    expect(renderProvider(loadingInfo(new Error('down')))).toContain('Could not load vsphere')
  })

  it('shows the draft’s values once there is one, and a delete says what it removes', () => {
    const draft = withPolicy(editDraft(), 'vm.get', { effect: 'delete', effectDetail: 'The VM and its disks' })
    if (!draft) throw new Error('not written')
    const html = renderProvider(publishedInfo('vm.get', draft))
    expect(html).toContain('draft 1.3.1')
    expect(html).toContain('It goes into the provider’s draft.')
    expect(html).toContain('value="The VM and its disks"')
    expect(control(html, 'Needs an approval')).toContain('aria-checked="true"')
  })

  it('goes into an edit draft’s rows and, by lines, its file at the next patch', () => {
    const draft = editDraft()
    const retry = withPolicy(draft, 'vm.get', { retry: 'unsafe' })!
    expect(retry.actions.find((a) => a.id === 'vm.get')?.retry).toBe('unsafe')
    expect(fileAction(retry.draftYaml, 'vm.get')).toMatchObject({ effect: 'read', retry: 'unsafe' })
    expect((parse(retry.draftYaml!) as ProviderFile).metadata.version).toBe('1.3.1')
    expect(fileAction(retry.draftYaml, 'vm.list')).toEqual(fileAction(draft.draftYaml, 'vm.list'))

    // a delete asks for an approval; turned off on a delete it is written as none, since a delete requires one by default
    const del = withPolicy(retry, 'vm.get', { effect: 'delete' })!
    expect(fileAction(del.draftYaml, 'vm.get')).toMatchObject({ effect: 'delete', approval: 'required' })
    const off = withPolicy(del, 'vm.get', { approval: false })!
    expect(fileAction(off.draftYaml, 'vm.get')).toMatchObject({ approval: 'none' })
    const read = withPolicy(withPolicy(off, 'vm.get', { effect: 'read' })!, 'vm.get', { approval: false })!
    expect(fileAction(read.draftYaml, 'vm.get')).not.toHaveProperty('approval')

    const slo = withPolicy(draft, 'vm.get', { sloSuccessRate: '99.9', sloP95Ms: '300' })!
    expect(fileAction(slo.draftYaml, 'vm.get')).toMatchObject({ slo: { successRate: 99.9, p95Ms: 300 } })
    const half = withPolicy(slo, 'vm.get', { sloP95Ms: '' })!
    expect((fileAction(half.draftYaml, 'vm.get') as { slo: unknown }).slo).toEqual({ successRate: 99.9 })
    expect(fileAction(withPolicy(half, 'vm.get', { sloSuccessRate: '' })!.draftYaml, 'vm.get')).not.toHaveProperty('slo')

    const plan = withPolicy(draft, 'vm.get', { plan: 'vm.list', effectDetail: '  ' })!
    expect(fileAction(plan.draftYaml, 'vm.get')).toMatchObject({ plan: 'vm.list' })
    expect(fileAction(plan.draftYaml, 'vm.get')).not.toHaveProperty('effectDetail')
  })

  it('goes into the state of a draft written from it, where the error classes can change too', () => {
    const out = withPolicy(newDraft(), 'invoices.get', { retry: 'unsafe', approval: true })!
    expect(out.draftYaml).toBeNull()
    expect(fileAction(draftYaml(out), 'invoices.get')).toMatchObject({ retry: 'unsafe', approval: 'required' })
    const html = renderProvider(newDraftInfo(out))
    expect(html).toContain('draft 1.0.0')
    expect(html).toContain('Start from the usual ones')
    const classes = withErrorClasses(out, [{ key: 1, status: '429', body: '', class: 'rate_limited', retry: true }])!
    expect(classes.draftYaml).toBeNull()
    expect((parse(draftYaml(classes)) as ProviderFile).spec.errors).toEqual([{ match: { status: 429 }, class: 'rate_limited', retry: true }])
  })

  it('edits the error classes of a provider file into the draft’s rows and, by lines, its file', () => {
    // a published provider: the rows come from its file, and the first edit starts the draft
    const rows = errorRows(undefined, vsphereDetail().yaml)!
    expect(rows.map((r) => r.class)).toEqual(['not_found', 'conflict', 'rate_limited', 'unavailable'])
    const draft = withErrorClasses(draftFromProvider(vsphereDetail()), [...rows, { key: 99, status: '500', body: 'quota', class: 'quota', retry: false }])!
    expect((parse(draft.draftYaml!) as ProviderFile).spec.errors?.map((e) => e.class)).toEqual(['not_found', 'conflict', 'rate_limited', 'unavailable', 'quota'])
    expect((parse(draft.draftYaml!) as ProviderFile).metadata.version).toBe('1.3.1')
    // from then on the draft's rows stand for its file, keys and all
    expect(errorRows(draft, vsphereDetail().yaml)).toBe(draft.errors)
    // an edit draft whose rows are not its file's yet reads them from the file
    expect(errorRows(editDraft(), undefined)?.map((r) => r.class)).toEqual(rows.map((r) => r.class))
  })

  it('shows error classes written another way as they are, and cannot edit them', () => {
    const block = '  errors:\n    - match:\n        status: 404\n      class: not_found\n      retry: false\n'
    const draft = { ...editDraft(), draftYaml: editDraft().draftYaml!.replace(/ {2}errors:\n(?: {4}- .*\n)+/, block) }
    expect(errorRows(draft, undefined)).toBeNull()
    expect(withErrorClasses(draft, [])).toBeNull()
    const html = renderProvider(publishedInfo('vm.get', draft))
    expect(html).toContain('writes them in a way the designer does not edit; open the provider to change them')
    expect(html).toContain('class: not_found')
    expect(html).not.toContain('Add error class')
  })

  it('is not written where the file does not hold the action by lines, or there is no such action', () => {
    const flow = { ...editDraft(), draftYaml: 'kind: Provider\nspec:\n  actions:\n    - { id: vm.get, effect: read }\n' }
    expect(withPolicy(flow, 'vm.get', { retry: 'unsafe' })).toBeNull()
    expect(withPolicy({ ...newDraft(), actions: [] }, 'invoices.get', { retry: 'unsafe' })).toBeNull()
  })

  it('cannot be changed where the step cannot', () => {
    const html = renderProvider(publishedInfo(), true)
    expect(html).not.toContain('Open provider')
    expect(control(html, 'Retry policy of the action')).toContain('disabled')
    // the error classes as the file writes them, nothing to edit
    expect(html).not.toContain('Add error class')
    expect(html).toContain('- { match: { status: 404 }, class: not_found, retry: false }')
  })
})
