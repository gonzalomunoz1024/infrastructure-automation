import { renderToString } from 'react-dom/server'
import { describe, expect, it } from 'vitest'
import type { WorkflowDocument } from '@iaf/workflow-checks'
import { findStepNode } from '../model/graph'
import { EMPTY_REGISTRY } from '../model/registry'
import { DesignerContext, DesignerStore } from '../model/store'
import { isSettingsField, writeField } from './fields'
import { StepSettings } from './StepSettings'

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
