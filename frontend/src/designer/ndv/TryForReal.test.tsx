import type { ReactNode } from 'react'
import { renderToString } from 'react-dom/server'
import { describe, expect, it } from 'vitest'
import { parse } from 'yaml'
import type { WorkflowDocument } from '@iaf/workflow-checks'
import type { TryResult } from '../../lib/types'
import { draftYaml } from '../model/providerDrafts'
import { EMPTY_REGISTRY } from '../model/registry'
import { DesignerContext, DesignerStore } from '../model/store'
import { changesSomething, fixableAnswer, sampleRefusal, sampleSaved, tryEnvironments, tryYaml, withFixedSchema, withSample, type TriedCall } from './providerStep'
import { editDraft, loadingInfo, newDraft, newDraftInfo, providerStep, publishedInfo, VSPHERE_YAML } from './testProviders'
import { confirmMessage, prefillInput, testResourceFields, TriedOutput, TryForReal } from './TryForReal'

/*
 * Try for real on a provider step: one real call (POST /providers:try) with the draft's file or
 * the published one, starting where the review counts tries, asking first when it changes
 * something, and its answer kept, on request, in the provider's draft: as the sample (the file the
 * backend wrote, whose sample it redacted: the live answer is never kept), or as the schema of the
 * action's output. Use the real response is the published provider's change request.
 */

// the live answer holds a secret; the file the backend returns has it masked
const ANSWER = { id: 'vm-1042', name: 'web-1042', power_state: 'POWERED_ON', cpu: 4, token: 'example-token' }
const RESULT: TryResult = {
  ok: true,
  status: 200,
  durationMs: 412,
  response: ANSWER,
  mismatches: [{ field: 'power_state', expected: undefined, actual: 'POWERED_ON' }],
  environment: 'dev',
  at: '2026-10-18T10:00:00Z',
  yaml: `${VSPHERE_YAML}# recorded, token: vault:redacted`,
}
const FAILED: TryResult = { ...RESULT, ok: false, status: 404, response: { error: { class: 'not_found', message: 'No VM vm-1042' } }, mismatches: [], yaml: undefined }

const tried = (yaml: string, result: TryResult = RESULT, action = 'vm.get'): TriedCall => ({ stepId: 'check', action, environment: 'dev', yaml, result })
type Doc = { metadata: { version: string }; spec: { actions: Record<string, unknown>[]; overrides?: Record<string, unknown> } }

const doc: WorkflowDocument = { apiVersion: 'iaf.platform/v1', kind: 'Workflow', metadata: { name: 'vm-check', owner: 'team-compute', version: '1.0.0' }, workflow: { do: [] } } as WorkflowDocument
const text = (html: string) => html.replace(/<!--.*?-->/g, '')

function render(node: ReactNode): string {
  const store = new DesignerStore({ name: null, doc, registry: EMPTY_REGISTRY })
  return text(renderToString(<DesignerContext.Provider value={store}>{node}</DesignerContext.Provider>))
}

describe('what a real call is made with', () => {
  it('is the draft’s file, else the published one, and none while the provider loads', () => {
    const draft = editDraft()
    expect(tryYaml(publishedInfo('vm.get', draft))).toBe(draftYaml(draft))
    expect(tryYaml(publishedInfo())).toBe(VSPHERE_YAML)
    expect(tryYaml(newDraftInfo())).toBe(draftYaml(newDraft()))
    expect(tryYaml(loadingInfo())).toBeNull()
  })

  it('starts in the environment the review counts: dev, else the first', () => {
    expect(tryEnvironments(publishedInfo())).toEqual({ environments: ['dev', 'test', 'prod'], sandbox: 'dev' })
    const staging = newDraft()
    expect(tryEnvironments(newDraftInfo({ ...staging, environments: [{ key: 9, name: 'staging', url: 'https://billing-staging.example.com' }] }))).toEqual({ environments: ['staging'], sandbox: 'staging' })
  })

  it('takes the step’s input as it resolves, leaving out what does not', () => {
    const step = providerStep('vsphere', 'vm.get', { id: '{{ $input.vmId }}', note: '{{ $input.missing }}', fixed: 'web', count: 2 })
    const schema = { type: 'object', properties: { id: { type: 'string' }, region: { type: 'string', default: 'eu-1' } } }
    expect(prefillInput(step, { input: { vmId: 'vm-1042' } }, schema)).toEqual({ id: 'vm-1042', region: 'eu-1', fixed: 'web', count: 2 })
    expect(prefillInput(providerStep('vsphere', 'vm.get', {}), {}, schema)).toEqual({ region: 'eu-1' })
  })

  it('asks first when the action changes something, and a delete picks its target from the registry', () => {
    expect(changesSomething('read')).toBe(false)
    expect(changesSomething('none')).toBe(false)
    for (const e of ['create', 'change', 'delete', 'unknown']) expect(changesSomething(e)).toBe(true)
    const schema = { type: 'object', properties: { id: { type: 'string', 'x-resource': { type: 'vm' } }, force: { type: 'boolean' } } }
    expect(testResourceFields('delete', schema)).toEqual(['id'])
    expect(testResourceFields('change', schema)).toEqual([])
    expect(confirmMessage('delete', 'dev', ['vm-test-7'])).toBe('This is a real delete call, not a dry run: it deletes vm-test-7 in dev.')
    expect(confirmMessage('change', 'test', [])).toBe('This is a real change call, not a dry run.')
  })

  it('is offered once the file is there', () => {
    expect(render(<TryForReal info={publishedInfo()} stepId="check" prefill={{}} onTried={() => {}} />)).not.toContain('disabled=""')
    expect(render(<TryForReal info={loadingInfo()} stepId="check" prefill={{}} onTried={() => {}} />)).toContain('disabled=""')
  })
})

/** The opening tag of the button that shows this text. */
function button(html: string, label: string): string {
  const at = html.indexOf(`>${label}<`)
  expect(at, `no button ${label}`).toBeGreaterThanOrEqual(0)
  return html.slice(html.lastIndexOf('<button', at), html.indexOf('>', html.lastIndexOf('<button', at)) + 1)
}

describe('the answer of a real call', () => {
  it('is shown as the provider page shows it, with Save as sample and Fix schema, and Use the real response for a published provider', () => {
    const published = render(<TriedOutput info={publishedInfo()} tried={tried(VSPHERE_YAML)} onClose={() => {}} />)
    expect(published).toContain('A real call of vm.get in')
    expect(published).toContain('HTTP 200')
    expect(published).toContain('1 field differ')
    expect(button(published, 'Save as sample')).not.toContain('disabled=""')
    expect(button(published, 'Fix schema')).not.toContain('disabled=""')
    expect(published).toContain('Use the real response')
    const draft = render(<TriedOutput info={newDraftInfo()} tried={tried(draftYaml(newDraft()), RESULT, 'invoices.get')} onClose={() => {}} />)
    expect(draft).toContain('Save as sample')
    expect(draft).not.toContain('Use the real response')
  })

  it('keeps nothing of a call that failed', () => {
    const html = render(<TriedOutput info={publishedInfo()} tried={tried(VSPHERE_YAML, FAILED)} onClose={() => {}} />)
    expect(button(html, 'Save as sample')).toContain('disabled=""')
    expect(button(html, 'Fix schema')).toContain('disabled=""')
    expect(html).toContain('A call that failed keeps nothing')
    expect(fixableAnswer(FAILED)).toBe(false)
    expect(fixableAnswer({ ok: true, response: 'Bad gateway' })).toBe(false)
    expect(fixableAnswer(RESULT)).toBe(true)
    expect(withSample(editDraft(), tried(VSPHERE_YAML, FAILED))).toBeNull()
  })

  it('says why the answer cannot be saved once the draft changed since the call', () => {
    const draft = editDraft()
    const changed = withFixedSchema(draft, tried(VSPHERE_YAML))!
    const html = render(<TriedOutput info={publishedInfo('vm.get', changed)} tried={tried(draftYaml(draft))} onClose={() => {}} />)
    expect(button(html, 'Save as sample')).toContain('disabled=""')
    expect(html).toContain('The draft changed since this call: try again to save its answer as the sample.')
  })
})

describe('Save as sample', () => {
  it('takes the file the call came back with, at the draft’s version, and never the live answer', () => {
    const draft = editDraft()
    // tried with the published file (1.3.0): the draft is its next patch
    const out = withSample(draft, tried(VSPHERE_YAML))!
    expect(out.draftYaml).toContain('# recorded, token: vault:redacted')
    expect((parse(out.draftYaml!) as Doc).metadata.version).toBe('1.3.1')
    expect(out.samples).toEqual(draft.samples)
    expect(JSON.stringify(out)).not.toContain('example-token')
    expect(sampleSaved(out, tried(VSPHERE_YAML))).toBe(true)
    expect(sampleSaved(draft, tried(VSPHERE_YAML))).toBe(false)
  })

  it('makes a draft written from its state hold that file, as the wizard’s Use this does', () => {
    const draft = newDraft()
    const file = draftYaml(draft)
    const out = withSample(draft, tried(file, { ...RESULT, yaml: `${file}# recorded, token: vault:redacted` }, 'invoices.get'))!
    expect(out.draftYaml).toBe(`${file}# recorded, token: vault:redacted`)
    expect(out.samples).toEqual({})
    expect(JSON.stringify(out)).not.toContain('example-token')
  })

  it('keeps nothing when the draft changed since the call, or the call came back without its file', () => {
    const draft = { ...editDraft(), draftYaml: editDraft().draftYaml!.replace('retry: safe', 'retry: unsafe') }
    expect(withSample(draft, tried(VSPHERE_YAML))).toBeNull()
    expect(sampleRefusal(draft, tried(VSPHERE_YAML))).toContain('The draft changed since this call')
    expect(withSample(editDraft(), tried(VSPHERE_YAML, { ...RESULT, yaml: undefined }))).toBeNull()
    expect(sampleRefusal(editDraft(), tried(VSPHERE_YAML, { ...RESULT, yaml: undefined }))).toContain('without the file')
    expect(sampleRefusal(editDraft(), tried(VSPHERE_YAML, FAILED))).toBe('A call that failed records no sample.')
    expect(sampleRefusal(editDraft(), tried(VSPHERE_YAML))).toBeNull()
    expect(sampleRefusal(null, tried(VSPHERE_YAML))).toBeNull()
  })
})

describe('Fix schema', () => {
  it('writes the answer’s shape as the action’s override, in the state or in the file', () => {
    const fresh = withFixedSchema(newDraft(), tried('', RESULT, 'invoices.get'))!
    expect(fresh.overrides['invoices.get']).toEqual({ type: 'object', properties: { id: { type: 'string' }, name: { type: 'string' }, power_state: { type: 'string' }, cpu: { type: 'integer' }, token: { type: 'string' } } })
    expect(draftYaml(fresh)).toContain('overrides:')
    const edit = withFixedSchema(editDraft(), tried(VSPHERE_YAML))!
    expect((parse(edit.draftYaml!) as Doc).spec.overrides).toEqual({ 'vm.get': { response: fresh.overrides['invoices.get'] } })
  })
})
