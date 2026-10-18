import { describe, expect, it } from 'vitest'
import type { WorkflowDocument } from '@iaf/workflow-checks'
import { compareWithCanvas } from './AssistantPanel'

/*
 * What a draft of the designer's assistant would change on the canvas: the canvas and the draft,
 * both as the backend renders them, line against line. An answer that leaves the workflow as it
 * is (an explanation) has nothing to apply.
 */

const CANVAS = ['apiVersion: iaf.platform/v1', 'kind: Workflow', 'metadata:', '  name: vm-restart', 'workflow:', '  do:', '    - restart:', '        name: Restart with Ansible', '        call: ansible', ''].join('\n')
const DRAFT = CANVAS.replace('  do:\n', '  do:\n    - announce:\n        notify: { channel: "#compute-ops", message: Restarting }\n')
const DEFINITION = { apiVersion: 'iaf.platform/v1', kind: 'Workflow', metadata: { name: 'vm-restart' }, workflow: { do: [] } } as WorkflowDocument

describe('a draft against the canvas', () => {
  it('is the diff of the canvas against the draft, with what it adds and removes', () => {
    const c = compareWithCanvas(CANVAS, { definition: DEFINITION, yaml: DRAFT.replace('name: Restart with Ansible', 'name: Restart') })
    expect(c.kind).toBe('diff')
    if (c.kind !== 'diff') return
    expect(c.additions).toBe(3)
    expect(c.deletions).toBe(1)
    expect(c.diff.split('\n').slice(0, 2)).toEqual(['--- a/canvas', '+++ b/draft'])
    expect(c.diff).toContain('+    - announce:')
    expect(c.diff).toContain('-        name: Restart with Ansible')
    expect(c.diff).toContain('+        name: Restart')
  })

  it('has nothing to apply when the draft is the workflow on the canvas', () => {
    expect(compareWithCanvas(CANVAS, { definition: DEFINITION, yaml: CANVAS })).toEqual({ kind: 'same' })
  })

  it('waits for both sides, and says why a draft that does not parse cannot be applied', () => {
    expect(compareWithCanvas(null, { definition: DEFINITION, yaml: DRAFT })).toEqual({ kind: 'pending' })
    expect(compareWithCanvas(CANVAS, null)).toEqual({ kind: 'pending' })
    expect(compareWithCanvas(null, { error: 'line 3: bad indentation' })).toEqual({ kind: 'invalid', message: 'line 3: bad indentation' })
  })
})
