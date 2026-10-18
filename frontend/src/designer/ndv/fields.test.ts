import { describe, expect, it } from 'vitest'
import type { Problem, StepBody, WorkflowDocument } from '@iaf/workflow-checks'
import { DesignerStore } from '../model/store'
import { problemsAt, readField, toExpression, toFixed, writeField, isSettingsField, rootOf } from './fields'
import { sampleTriggerPayload, triggerOutput } from './TriggerParameters'

describe('step fields', () => {
  it('reads and writes the fields of a call step inside with', () => {
    const body: StepBody = { name: 'Get', call: 'provider', with: { provider: 'vsphere', action: 'vm.get', input: { id: '{{ $input.vmId }}' } } }
    expect(readField(body, 'input.id')).toBe('{{ $input.vmId }}')
    writeField(body, 'input.id', 'vm-1')
    writeField(body, 'input.extra', 5)
    expect(body.with).toEqual({ provider: 'vsphere', action: 'vm.get', input: { id: 'vm-1', extra: 5 } })
    writeField(body, 'input.extra', undefined)
    expect(body.with?.input).toEqual({ id: 'vm-1' })
  })

  it('writes block steps into their own key and common keys at the top', () => {
    const body: StepBody = { name: 'Tell', notify: { channel: '#ops', message: '' } }
    writeField(body, 'message', 'done')
    writeField(body, 'timeout', '5m')
    writeField(body, 'retry.maxAttempts', 2)
    expect(body).toEqual({ name: 'Tell', notify: { channel: '#ops', message: 'done' }, timeout: '5m', retry: { maxAttempts: 2 } })
  })

  it('turns a wait written as text into an object before writing until', () => {
    const body: StepBody = { wait: 'PT5M' }
    expect(readField(body, 'duration')).toBe('PT5M')
    expect(readField(body, 'until')).toBeUndefined()
    writeField(body, 'until', '{{ $json.ready }}')
    expect(body.wait).toEqual({ duration: 'PT5M', until: '{{ $json.ready }}' })
  })

  it('edits a switch case by the path the checks use', () => {
    const body: StepBody = { switch: [{ case: { when: '{{ $json.a }}', then: 'x' } }, { default: 'end' }] }
    expect(readField(body, 'switch[0].when')).toBe('{{ $json.a }}')
    writeField(body, 'switch[0].when', '{{ $json.b }}')
    expect(body.switch?.[0]).toEqual({ case: { when: '{{ $json.b }}', then: 'x' } })
  })

  it('converts between fixed values and expressions', () => {
    expect(toExpression(5)).toBe('{{ 5 }}')
    expect(toExpression('text')).toBe('text')
    expect(toExpression(undefined)).toBe('')
    expect(toFixed('{{ 5 }}', 'number')).toBe(5)
    expect(toFixed('{{ $json.a }}', 'number')).toBeUndefined()
    expect(toFixed('{{ "a" }}', 'string')).toBe('a')
    expect(toFixed('plain', 'string')).toBe('plain')
    expect(toFixed('{{ $json.a }}', 'string')).toBeUndefined()
    expect(toFixed('{{ true }}', 'boolean')).toBe(true)
  })

  it('finds the problems of a field and of what it holds', () => {
    const problems: Problem[] = [
      { level: 'error', code: 'a', field: 'input', message: 'a' },
      { level: 'error', code: 'b', field: 'input.vmId', message: 'b' },
      { level: 'error', code: 'c', field: 'inputs', message: 'c' },
      { level: 'error', code: 'd', field: 'switch[0].when', message: 'd' },
    ]
    expect(problemsAt(problems, 'input').map((p) => p.code)).toEqual(['a', 'b'])
    expect(problemsAt(problems, 'switch[0]').map((p) => p.code)).toEqual(['d'])
    expect(rootOf('switch[0].when')).toBe('switch')
    expect(isSettingsField('retry.maxAttempts')).toBe(true)
    expect(isSettingsField('input.vmId')).toBe(false)
  })
})

describe('trigger previews', () => {
  const doc: WorkflowDocument = {
    metadata: { name: 'x' },
    api: { input: { type: 'object', properties: { vmId: { type: 'string' } } } },
    triggers: [{ alert: { match: { source: 'grafana', labels: { alertname: 'Down' } }, input: { vmId: '{{ $alert.labels.vm_id }}', why: '{{ $alert.annotations.summary }}' } } }],
    workflow: { do: [] },
  }

  it('builds a sample alert from the match and the labels the input reads', () => {
    const p = sampleTriggerPayload('alert', doc.triggers?.[0]?.alert as never)
    expect(p).toEqual({ source: 'grafana', labels: { alertname: 'Down', vm_id: 'vm_id-1' }, annotations: { summary: 'Sample summary' } })
  })

  it('shows the job input a trigger hands the first step', () => {
    expect(triggerOutput(doc, { kind: 'alert', index: 0 }, null)).toEqual({ vmId: 'vm_id-1', why: 'Sample summary' })
    expect(triggerOutput(doc, { kind: 'api' }, null)).toEqual({ vmId: 'vmId' })
    expect(triggerOutput(doc, { kind: 'api' }, { vmId: 'vm-7' })).toEqual({ vmId: 'vm-7' })
  })
})

describe('the node detail view in the store', () => {
  it('keeps the view open, and the pin, when the step id changes', () => {
    const doc: WorkflowDocument = { metadata: { name: 'x' }, workflow: { do: [{ first: { name: 'First', wait: 'PT1M' } }] } }
    const s = new DesignerStore({ name: 'x', doc, registry: null })
    s.openNdv('step:first')
    s.setPin('first', { ok: true })
    expect(s.renameStepId('first', 'second')).toBe(true)
    expect(s.getState().ndv).toBe('step:second')
    expect(s.getState().pins).toEqual({ second: { ok: true } })
    s.setPin('second', undefined)
    expect(s.getState().pins).toEqual({})
  })
})
