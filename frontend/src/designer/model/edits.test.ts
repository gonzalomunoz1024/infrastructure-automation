import { describe, expect, it } from 'vitest'
import { parseStep, STEP_TYPE_KEYS, type JsonObject, type WorkflowDocument } from '@iaf/workflow-checks'
import { bodyFor, connectSwitch, duplicateSteps, insertStep, newStepId, pasteSteps, removeSteps, renameStep, resolveInsertPoint, stepIdProblem } from './edits'
import { clone } from './fixes'

const doc = (steps: unknown[], extra: Partial<WorkflowDocument> = {}): WorkflowDocument =>
  ({ apiVersion: 'iaf.platform/v1', kind: 'Workflow', metadata: { name: 'x', noun: 'thing', verb: 'poke' }, workflow: { do: steps }, ...extra }) as WorkflowDocument

const ids = (d: WorkflowDocument) => d.workflow?.do?.map((s) => Object.keys(s)[0])

describe('insertStep', () => {
  it('inserts at the start, after a step and at the end', () => {
    const d = doc([{ a: { wait: 'PT1M' } }, { b: { wait: 'PT1M' } }])
    expect(ids(insertStep(d, { kind: 'start', flow: 'main' }, 'n', { wait: 'PT1S' }))).toEqual(['n', 'a', 'b'])
    expect(ids(insertStep(d, { kind: 'after', step: 'a', handle: 'main' }, 'n', { wait: 'PT1S' }))).toEqual(['a', 'n', 'b'])
    expect(ids(insertStep(d, { kind: 'before', step: 'b' }, 'n', { wait: 'PT1S' }))).toEqual(['a', 'n', 'b'])
    expect(ids(insertStep(d, { kind: 'end' }, 'n', { wait: 'PT1S' }))).toEqual(['a', 'b', 'n'])
    expect(ids(d)).toEqual(['a', 'b'])
  })
  it('starts the catch flow', () => {
    const d = insertStep(doc([{ a: { wait: 'PT1M' } }]), { kind: 'start', flow: 'catch' }, 'tell', { notify: { channel: '#x', message: 'm' } })
    expect(d.workflow?.catch?.do?.map((s) => Object.keys(s)[0])).toEqual(['tell'])
  })
  it('turns a switch jump into a nested branch and fills the default', () => {
    const d = doc([{ s: { switch: [{ case: { when: '{{ $input.a }}', then: 'b' } }] } }, { b: { wait: 'PT1M' } }])
    const one = insertStep(d, { kind: 'after', step: 's', handle: 'case:0' }, 'n', { wait: 'PT1S' })
    expect(one.workflow?.do?.[0]?.s?.switch?.[0]).toEqual({ case: { when: '{{ $input.a }}', do: [{ n: { wait: 'PT1S' } }] } })
    const two = insertStep(one, { kind: 'after', step: 's', handle: 'default' }, 'm', { wait: 'PT2S' })
    expect(two.workflow?.do?.[0]?.s?.switch?.[1]).toEqual({ default: { do: [{ m: { wait: 'PT2S' } }] } })
    const three = insertStep(two, { kind: 'after', step: 'n', handle: 'main' }, 'o', { wait: 'PT3S' })
    expect(three.workflow?.do?.[0]?.s?.switch?.[0]?.case?.do?.map((s) => Object.keys(s)[0])).toEqual(['n', 'o'])
  })
  it('fills fork branches, loop bodies, try and catch and compensations', () => {
    const d = doc([{ f: { fork: { branches: [[], []] } } }, { l: { for: { each: 'i', in: '{{ $input.items }}', do: [] } } }, { t: { try: { do: [] } } }, { c: { wait: 'PT1M' } }])
    const f = insertStep(d, { kind: 'after', step: 'f', handle: 'branch:2' }, 'n', { wait: 'PT1S' })
    expect(f.workflow?.do?.[0]?.f?.fork?.branches).toEqual([[], [], [{ n: { wait: 'PT1S' } }]])
    const l = insertStep(d, { kind: 'after', step: 'l', handle: 'loop' }, 'n', { wait: 'PT1S' })
    expect(l.workflow?.do?.[1]?.l?.for?.do).toEqual([{ n: { wait: 'PT1S' } }])
    expect(ids(insertStep(d, { kind: 'after', step: 'l', handle: 'done' }, 'n', { wait: 'PT1S' }))).toEqual(['f', 'l', 'n', 't', 'c'])
    const t = insertStep(d, { kind: 'after', step: 't', handle: 'catch' }, 'n', { wait: 'PT1S' })
    expect(t.workflow?.do?.[2]?.t?.try).toEqual({ do: [], catch: { as: 'error', do: [{ n: { wait: 'PT1S' } }] } })
    const u = insertStep(d, { kind: 'after', step: 'c', handle: 'undo' }, 'n', { wait: 'PT1S' })
    expect(u.workflow?.do?.[3]?.c?.compensate).toEqual([{ n: { wait: 'PT1S' } }])
  })
  it('returns null for a point that does not exist', () => {
    expect(resolveInsertPoint(doc([]), { kind: 'after', step: 'nope', handle: 'main' })).toBeNull()
  })
})

describe('removeSteps', () => {
  it('removes steps anywhere and points jumps at the next sibling', () => {
    const d = doc([{ s: { switch: [{ case: { when: '{{ $input.a }}', then: 'b' } }, { default: 'c' }] } }, { b: { wait: 'PT1M' } }, { c: { fork: { branches: [[{ x: { wait: 'PT1M' } }]] } } }])
    const out = removeSteps(d, ['b', 'x'])
    expect(ids(out)).toEqual(['s', 'c'])
    expect(out.workflow?.do?.[0]?.s?.switch).toEqual([{ case: { when: '{{ $input.a }}', then: 'c' } }, { default: 'c' }])
    expect(out.workflow?.do?.[1]?.c?.fork?.branches).toEqual([[]])
    expect(removeSteps(out, ['c']).workflow?.do?.[0]?.s?.switch).toEqual([{ case: { when: '{{ $input.a }}', then: 'end' } }, { default: 'end' }])
  })
})

describe('renameStep', () => {
  it('renames the key, the jumps and the $() references', () => {
    const d = doc([{ s: { switch: [{ case: { when: '{{ $input.a }}', then: 'b' } }] } }, { b: { name: 'B', wait: 'PT1M' } }, { c: { notify: { channel: '#x', message: "{{ $('b').json.x }} {{ $(\"b\").json.y }}" } } }], {
      workflow: { do: [], outputs: { v: "{{ $('b').json.z }}" } },
    })
    d.workflow!.do = [{ s: { switch: [{ case: { when: '{{ $input.a }}', then: 'b' } }] } }, { b: { name: 'B', wait: 'PT1M' } }, { c: { notify: { channel: '#x', message: "{{ $('b').json.x }} {{ $(\"b\").json.y }}" } } }]
    const out = renameStep(d, 'b', 'pause')
    expect(ids(out)).toEqual(['s', 'pause', 'c'])
    expect(out.workflow?.do?.[0]?.s?.switch?.[0]?.case?.then).toBe('pause')
    expect(out.workflow?.do?.[2]?.c?.notify?.message).toBe("{{ $('pause').json.x }} {{ $('pause').json.y }}")
    expect(out.workflow?.outputs?.v).toBe("{{ $('pause').json.z }}")
  })
})

describe('duplicateSteps and pasteSteps', () => {
  it('clones next to the original with fresh ids, nested ones included', () => {
    const d = doc([{ f: { fork: { branches: [[{ x: { wait: 'PT1M' } }]] }, position: { x: 10, y: 20 } } }, { b: { wait: 'PT1M' } }])
    const { doc: out, ids: created } = duplicateSteps(d, ['f'], () => ({ x: 10, y: 20 }))
    expect(created).toEqual(['fCopy'])
    expect(ids(out)).toEqual(['f', 'fCopy', 'b'])
    expect(out.workflow?.do?.[1]?.fCopy?.fork?.branches?.[0]?.map((s) => Object.keys(s)[0])).toEqual(['xCopy'])
    expect(out.workflow?.do?.[1]?.fCopy?.position).toEqual({ x: 50, y: 60 })
    const again = duplicateSteps(out, ['f'], () => undefined)
    expect(again.ids).toEqual(['fCopy2'])
  })
  it('pastes entries renaming taken ids', () => {
    const d = doc([{ a: { wait: 'PT1M' } }])
    const { doc: out, ids: pasted } = pasteSteps(d, [{ a: { wait: 'PT2M', position: { x: 0, y: 0 } } }, { z: { wait: 'PT3M' } }], { kind: 'after', step: 'a', handle: 'main' }, { x: 40, y: 40 })
    expect(pasted).toEqual(['aCopy', 'z'])
    expect(ids(out)).toEqual(['a', 'aCopy', 'z'])
    expect(out.workflow?.do?.[1]?.aCopy?.position).toEqual({ x: 40, y: 40 })
  })
})

describe('connectSwitch', () => {
  it('points a case or the default at a step or end', () => {
    const d = doc([{ s: { switch: [{ case: { when: '{{ $input.a }}', do: [{ x: { wait: 'PT1M' } }] } }] } }, { b: { wait: 'PT1M' } }])
    const one = connectSwitch(d, 's', 'case:0', 'b')
    expect(one.workflow?.do?.[0]?.s?.switch?.[0]).toEqual({ case: { when: '{{ $input.a }}', then: 'b' } })
    const two = connectSwitch(one, 's', 'default', 'end')
    expect(two.workflow?.do?.[0]?.s?.switch?.[1]).toEqual({ default: 'end' })
  })
})

describe('ids and bodies', () => {
  it('derives unique camelCase ids', () => {
    const d = doc([{ vmGet: { wait: 'PT1M' } }])
    expect(newStepId(d, 'vm.get')).toBe('vmGet2')
    expect(newStepId(d, 'Approve change')).toBe('approveChange')
    expect(newStepId(d, 'end')).toBe('end2')
    expect(stepIdProblem(d, 'vmGet', null)).toMatch(/already used/)
    expect(stepIdProblem(d, 'vmGet', 'vmGet')).toBeNull()
    expect(stepIdProblem(d, 'Bad Id', null)).toMatch(/camelCase/)
    expect(stepIdProblem(d, 'ok-id', null)).toBeNull()
  })
  it('makes a body the parser accepts for every step type', () => {
    for (const type of STEP_TYPE_KEYS) {
      const body = bodyFor(type, { provider: 'vsphere', action: 'vm.get', tile: 't', workflow: 'w', resourceType: 'vm' })
      const parsed = parseStep('x', clone(body) as JsonObject)
      expect(parsed.type, type).toBe(type)
    }
  })
})
