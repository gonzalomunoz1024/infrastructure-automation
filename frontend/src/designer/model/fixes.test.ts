import { describe, expect, it } from 'vitest'
import { check, EMPTY_CONTEXT, type JsonObject, type Problem, type WorkflowDocument } from '@iaf/workflow-checks'
import { applyFix, parsePath, setAtPath, stepFieldPath } from './fixes'
import { sampleDocuments } from './graph.test'

const doc = (steps: unknown[], extra: Partial<WorkflowDocument> = {}): WorkflowDocument =>
  ({ apiVersion: 'iaf.platform/v1', kind: 'Workflow', metadata: { name: 'x', noun: 'thing', verb: 'poke', owner: 'team', version: '1.0.0' }, workflow: { do: steps }, ...extra }) as WorkflowDocument

describe('paths', () => {
  it('parses dotted paths with indexes', () => {
    expect(parsePath('api.input.vmId')).toEqual(['api', 'input', 'vmId'])
    expect(parsePath('switch[0].when')).toEqual(['switch', 0, 'when'])
    expect(parsePath('triggers[1]')).toEqual(['triggers', 1])
  })
  it('sets values creating objects and arrays on the way', () => {
    const o: JsonObject = {}
    setAtPath(o, ['a', 'b', 0, 'c'], 1)
    expect(o).toEqual({ a: { b: [{ c: 1 }] } })
  })
  it('maps a step problem field onto the body', () => {
    expect(stepFieldPath({ call: 'provider', with: {} }, 'input.vmId')).toEqual(['with', 'input', 'vmId'])
    expect(stepFieldPath({ call: 'http', with: {} }, 'credential')).toEqual(['with', 'credential'])
    expect(stepFieldPath({ approval: {} }, 'message')).toEqual(['approval', 'message'])
    expect(stepFieldPath({ switch: [{ case: { when: 'x' } }] }, 'switch[0].when')).toEqual(['switch', 0, 'case', 'when'])
    expect(stepFieldPath({ switch: [{ default: { do: [] } }] }, 'switch[0].then')).toEqual(['switch', 0, 'default', 'then'])
    expect(stepFieldPath({ wait: 'PT1M' }, 'duration')).toEqual(['wait'])
    expect(stepFieldPath({ wait: { duration: 'PT1M' } }, 'duration')).toEqual(['wait', 'duration'])
    expect(stepFieldPath({ call: 'ansible', with: {} }, 'retry.maxAttempts')).toEqual(['retry', 'maxAttempts'])
  })
})

describe('applyFix', () => {
  it('replaces a plain secret with the Vault reference the check proposes', () => {
    const d = doc([{ call: { name: 'Call', call: 'http', with: { method: 'GET', url: 'https://x', credential: 'hunter2' } } }])
    const problem = check(d, EMPTY_CONTEXT).find((p) => p.code === 'credential.plain')
    expect(problem).toBeDefined()
    const fixed = applyFix(d, problem as Problem)
    expect(fixed?.workflow?.do?.[0]?.call?.with?.credential).toBe('vault:call/credential')
    expect(d.workflow?.do?.[0]?.call?.with?.credential).toBe('hunter2')
  })
  it('replaces a document field', () => {
    const d = doc([{ a: { wait: 'PT1M' } }])
    const fixed = applyFix(d, { level: 'error', code: 'metadata.owner', field: 'metadata.owner', message: '', fix: { label: 'Set', kind: 'replace', value: 'team-x' } })
    expect(fixed?.metadata?.owner).toBe('team-x')
  })
  it('inserts an approval before a destructive step', () => {
    const d = doc([{ wipe: { name: 'Wipe disk', call: 'http', with: { method: 'DELETE', url: 'https://x/disks/1' } } }], { policy: { approval: { required: false, group: 'ops-approvers' } } })
    const problem = check(d, EMPTY_CONTEXT).find((p) => p.code === 'policy.approvalRequired')
    expect(problem?.fix?.kind).toBe('insert-approval')
    const fixed = applyFix(d, problem as Problem)
    const ids = fixed?.workflow?.do?.map((s) => Object.keys(s)[0])
    expect(ids).toEqual(['approveWipe', 'wipe'])
    expect(fixed?.workflow?.do?.[0]?.approveWipe?.approval).toEqual({ group: 'ops-approvers', message: 'Approve "Wipe disk"?', timeout: '4h' })
    expect(check(fixed, EMPTY_CONTEXT).some((p) => p.code === 'policy.approvalRequired')).toBe(false)
  })
  it('leaves connect fixes to the canvas', () => {
    const d = doc([{ a: { notify: { channel: '#x', message: '{{ $json.name }}' } } }])
    const problem = check(d, EMPTY_CONTEXT).find((p) => p.code === 'expr.noPredecessor')
    expect(problem?.fix?.kind).toBe('connect')
    expect(applyFix(d, problem as Problem)).toBeNull()
  })
  it('keeps every sample free of fixable errors after applying their fixes', () => {
    for (const { file, doc: d } of sampleDocuments()) {
      let current = d
      for (const p of check(current, EMPTY_CONTEXT)) {
        if (p.fix && p.fix.kind !== 'connect') {
          const next = applyFix(current, p)
          expect(next, `${file}: ${p.code}`).not.toBeNull()
          current = next ?? current
        }
      }
    }
  })
})
