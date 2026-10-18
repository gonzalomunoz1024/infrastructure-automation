import { describe, expect, it } from 'vitest'
import { check, EMPTY_CONTEXT, STEP_TYPE_KEYS, validateWorkflowDocument, type WorkflowDocument } from '@iaf/workflow-checks'
import { bodyFor } from './edits'
import { clone } from './fixes'
import { DesignerStore } from './store'
import { withoutEmptyKeys } from './nulls'

/*
 * An empty YAML value (`notes:`) reads as null in the TypeScript checks, and the Workflow schema,
 * which they judge first, refuses null where it names a type (docs/config-as-code.md, Checks): the
 * canvas never writes such a key.
 */

const BASE: WorkflowDocument = {
  apiVersion: 'iaf.platform/v1',
  kind: 'Workflow',
  metadata: { name: 'vm-poke', title: 'Poke a VM', noun: 'vm', verb: 'poke', owner: 'team-compute', version: '1.0.0' },
  api: { method: 'POST', path: '/api/v1/vms:poke', input: { type: 'object', properties: { id: { type: 'string' } } } },
  policy: { rbac: ['ops'] },
  workflow: {
    do: [
      { tell: { name: 'Tell', notify: { channel: '#ops', message: 'poked' } } },
      { remember: { set: { last: '{{ $input.id }}' } } },
    ],
  },
}

// eslint-disable-next-line @typescript-eslint/no-explicit-any
type Loose = Record<string, any>

/** The document with nulls under typed keys, as `notes:` and its kind would read. */
function withNulls(): WorkflowDocument {
  const doc = clone(BASE) as unknown as Loose
  doc.metadata.persona = null
  doc.mcp = { expose: true, description: null }
  doc.policy.approval = { required: true, group: 'compute-approvers', timeout: null }
  doc.triggers = [{ alert: { match: null, input: { id: '{{ $alert.labels.vm }}' } } }]
  doc.workflow.do[0].tell.notes = null
  doc.workflow.do[0].tell.retry = { maxAttempts: 2, backoff: null }
  doc.workflow.do[0].tell.timeout = null
  return doc as unknown as WorkflowDocument
}

const schemaProblems = (doc: WorkflowDocument) => validateWorkflowDocument(doc).map((p) => p.field)

describe('the canvas never writes an empty key', () => {
  it('leaves out every null the Workflow schema types, which the checks would refuse', () => {
    const dirty = withNulls()
    expect(schemaProblems(dirty)).toEqual(['/mcp/description', '/metadata/persona', '/policy/approval/timeout', '/triggers/0/alert/match', '/workflow/do/0/tell/notes', '/workflow/do/0/tell/retry/backoff', '/workflow/do/0/tell/timeout'])
    const clean = withoutEmptyKeys(dirty)
    expect(schemaProblems(clean)).toEqual([])
    expect(clean.metadata).toEqual(BASE.metadata)
    expect(clean.mcp).toEqual({ expose: true })
    expect(clean.policy).toEqual({ rbac: ['ops'], approval: { required: true, group: 'compute-approvers' } })
    expect(clean.triggers).toEqual([{ alert: { input: { id: '{{ $alert.labels.vm }}' } } }])
    expect(clean.workflow?.do?.[0]).toEqual({ tell: { name: 'Tell', notify: { channel: '#ops', message: 'poked' }, retry: { maxAttempts: 2 } } })
  })

  it("keeps a null where it is a value: under a call step's with, a set and the outputs", () => {
    const doc = clone(BASE) as unknown as Loose
    doc.workflow.do.push({ restart: { call: 'ansible', with: { playbook: 'ops/restart', extraVars: { reason: null } } } })
    doc.workflow.do[1].remember.set.cleared = null
    doc.workflow.outputs = { last: null }
    const kept = withoutEmptyKeys(doc as unknown as WorkflowDocument)
    expect(kept).toBe(doc)
    expect(schemaProblems(kept)).toEqual([])
  })

  it('returns the document itself when there is nothing to leave out', () => {
    expect(withoutEmptyKeys(BASE)).toBe(BASE)
  })

  it('applies to every edit the store commits, whatever the edit wrote', () => {
    const store = new DesignerStore({ name: 'vm-poke', doc: BASE, registry: null })
    store.update((d) => {
      ;(d.metadata as Loose).persona = null
    })
    expect(store.getState().doc.metadata).toEqual(BASE.metadata)
    store.updateStep('tell', (b) => {
      ;(b as Loose).notes = null
    })
    expect(store.getState().doc.workflow?.do?.[0]).toEqual(BASE.workflow?.do?.[0])
    store.setDocument(withNulls())
    expect(schemaProblems(store.getState().doc)).toEqual([])
    expect(store.checkNow().filter((p) => p.code === 'schema.invalid')).toEqual([])
  })

  it('makes a body of every step type that the Workflow schema accepts', () => {
    for (const type of STEP_TYPE_KEYS) {
      const body = bodyFor(type, { provider: 'vsphere', action: 'vm.get', tile: 't', workflow: 'w', resourceType: 'vm' })
      const doc = { ...clone(BASE), workflow: { do: [{ added: body }] } } as WorkflowDocument
      expect(schemaProblems(doc), type).toEqual([])
      expect(check(doc, EMPTY_CONTEXT).filter((p) => p.code === 'schema.invalid'), type).toEqual([])
    }
  })
})
