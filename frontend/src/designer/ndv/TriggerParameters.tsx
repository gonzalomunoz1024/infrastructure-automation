/**
 * The Parameters of the trigger nodes: the API trigger (method, path, response, preview and the
 * MCP tool; the input schema itself is edited in Settings), alert triggers (what to match and
 * how the alert becomes the job input), event triggers (topic and input), and the error trigger.
 * Also what each trigger hands the first step, for the Output pane.
 */
import { Button } from '@iaf/ui'
import { resolve, type JsonObject, type JsonValue, type Problem, type WorkflowDocument } from '@iaf/workflow-checks'
import type { Schema } from '../../lib/types'
import { apiTriggerLabel } from '../model/graph'
import { fieldsOf } from '../model/schema'
import { sampleInput } from '../model/expressions'
import { useDesignerStore } from '../model/store'
import { NdvContext, useNdv, type NdvContextValue } from './context'
import { asObject, asString, problemsAt, readDocField, writeDocField } from './fields'
import { FieldLabel, KeyValueEditor, ParameterField, ParamSection, ProblemList, type ParamSpec } from './ParameterField'

export type TriggerTarget = { kind: 'api' } | { kind: 'alert' | 'event'; index: number } | { kind: 'error' }

/** A sample alert or event for the previews: the matched labels plus every label the input reads. */
export function sampleTriggerPayload(kind: 'alert' | 'event', spec: JsonObject): JsonObject {
  const text = JSON.stringify(spec.input ?? {})
  if (kind === 'alert') {
    const match = asObject(spec.match)
    const labels: JsonObject = { ...asObject(match.labels) }
    const annotations: JsonObject = {}
    for (const m of text.matchAll(/\$alert\.labels\.([A-Za-z_][\w]*)/g)) if (labels[m[1]] === undefined) labels[m[1]] = `${m[1]}-1`
    for (const m of text.matchAll(/\$alert\.annotations\.([A-Za-z_][\w]*)/g)) annotations[m[1]] = `Sample ${m[1]}`
    return { source: asString(match.source) || 'monitoring', labels, annotations }
  }
  const payload: JsonObject = {}
  for (const m of text.matchAll(/\$event\.payload\.([A-Za-z_][\w]*)/g)) payload[m[1]] = `${m[1]}-1`
  return { topic: asString(spec.topic) || 'topic', payload }
}

/** What a trigger hands the first step: the job input. */
export function triggerOutput(doc: WorkflowDocument, target: TriggerTarget, lastInput: JsonObject | null): JsonValue {
  if (target.kind === 'api') return lastInput ?? sampleInput(doc)
  if (target.kind === 'error') return { message: 'Something failed', class: 'step_failed', step: 'stepId' }
  const entry = asObject((doc.triggers ?? [])[target.index] as JsonValue | undefined)
  const spec = asObject(entry[target.kind])
  const payload = sampleTriggerPayload(target.kind, spec)
  try {
    return resolve(spec.input ?? {}, { aliases: { [target.kind]: payload } })
  } catch {
    return spec.input ?? {}
  }
}

function DocField({ spec, readOnly }: { spec: ParamSpec; readOnly: boolean }) {
  const store = useDesignerStore()
  const doc = store.getState().doc
  return (
    <ParameterField
      spec={spec}
      value={readDocField(doc as unknown as JsonValue, spec.field)}
      onChange={(v) => store.update((d) => writeDocField(d as unknown as JsonObject, spec.field, v), `doc:${spec.field}`)}
      readOnly={readOnly}
    />
  )
}

function ApiTrigger({ doc, readOnly }: { doc: WorkflowDocument; readOnly: boolean }) {
  const store = useDesignerStore()
  const { method, path } = apiTriggerLabel(doc)
  const fields = fieldsOf((doc.api?.input ?? null) as Schema | null)
  return (
    <div className="space-y-4">
      <p className="text-caption text-tertiary">
        Every workflow is an endpoint: <span className="font-mono text-secondary">{`${method} ${path}`}</span>. A request starts a job and answers with its id.
      </p>
      <div className="grid grid-cols-[120px_1fr] gap-3">
        <DocField readOnly={readOnly} spec={{ field: 'api.method', label: 'Method', type: 'enum', expression: false, options: ['POST', 'PUT', 'PATCH', 'DELETE', 'GET'].map((m) => ({ value: m, label: m })) }} />
        <DocField readOnly={readOnly} spec={{ field: 'api.path', label: 'Path', type: 'string', mono: true, expression: false, placeholder: path }} />
      </div>
      <div className="grid grid-cols-2 gap-3">
        <DocField readOnly={readOnly} spec={{ field: 'api.response.status', label: 'Response status', type: 'number', expression: false, placeholder: '202' }} />
        <DocField readOnly={readOnly} spec={{ field: 'api.preview', label: 'Preview endpoint', type: 'boolean', expression: false, description: 'Also serve a :preview endpoint that shows what a run would do' }} />
      </div>
      <ParamSection
        title="Input"
        actions={
          <Button
            size="sm"
            variant="ghost"
            iconRight="arrow-right"
            onClick={() => {
              store.closeNdv()
              store.setTab('settings')
            }}
          >
            Edit in Settings
          </Button>
        }
      >
        {fields.length === 0 ? (
          <p className="text-caption text-secondary">The input has no fields yet.</p>
        ) : (
          <ul className="divide-y divide-stroke-light rounded-md border border-stroke-light">
            {fields.map((f) => (
              <li key={f.key} className="flex items-center gap-3 px-3 py-2 text-caption">
                <span className="font-mono text-primary">{f.key}</span>
                <span className="text-tertiary">{f.type === 'enum' ? (f.enum ?? []).join(' | ') : f.type}</span>
                <span className="flex-1" />
                {f.resource && <span className="text-tertiary">a {f.resource.type}</span>}
                {f.required && <span className="font-medium text-secondary">required</span>}
              </li>
            ))}
          </ul>
        )}
      </ParamSection>
      <ParamSection title="MCP">
        <DocField readOnly={readOnly} spec={{ field: 'mcp.expose', label: 'Expose as an MCP tool', type: 'boolean', expression: false }} />
        <DocField readOnly={readOnly} spec={{ field: 'mcp.description', label: 'Tool description', type: 'string', multiline: true, expression: false, description: 'What an agent reads to decide when to call the tool' }} />
      </ParamSection>
    </div>
  )
}

function SpecTrigger({ kind, index, doc, readOnly }: { kind: 'alert' | 'event'; index: number; doc: WorkflowDocument; readOnly: boolean }) {
  const store = useDesignerStore()
  const ndv = useNdv()
  const entry = asObject((doc.triggers ?? [])[index] as JsonValue | undefined)
  const spec = asObject(entry[kind])
  const field = `triggers[${index}]`
  const write = (next: JsonObject) => store.setTrigger(index, next)
  const payload = sampleTriggerPayload(kind, spec)
  // the input expressions read $alert / $event
  const ctxValue: NdvContextValue = { ...ndv, ctx: { aliases: { [kind]: payload } } }
  const match = asObject(spec.match)
  return (
    <NdvContext.Provider value={ctxValue}>
      <div className="space-y-4">
        <p className="text-caption text-tertiary">{kind === 'alert' ? 'A job starts when an alert that matches arrives; the alert becomes the job input below.' : 'A job starts when an event arrives on the topic; the event becomes the job input below.'}</p>
        <ProblemList problems={ndv.problems.filter((p) => p.field === field)} />
        {kind === 'alert' ? (
          <ParamSection title="Match">
            <ParameterField spec={{ field: `${field}.alert.match.source`, problemField: `${field}.match.source`, label: 'Source', type: 'string', expression: false, placeholder: 'grafana' }} value={match.source} onChange={(v) => write({ ...spec, match: { ...match, source: v ?? '' } })} readOnly={readOnly} />
            <div>
              <FieldLabel label="Labels" description="Every label must match" />
              <div className="mt-1">
                <KeyValueEditor value={asObject(match.labels)} onChange={(labels) => write({ ...spec, match: { ...match, labels } })} addLabel="Add label" keyPlaceholder="label" valuePlaceholder="value" readOnly={readOnly} />
              </div>
            </div>
          </ParamSection>
        ) : (
          <ParameterField spec={{ field: `${field}.event.topic`, problemField: `${field}.topic`, label: 'Topic', type: 'string', required: true, mono: true, expression: false, placeholder: 'vm.unresponsive' }} value={spec.topic} onChange={(v) => write({ ...spec, topic: v ?? '' })} readOnly={readOnly} />
        )}
        <ParamSection title="Job input">
          <KeyValueEditor
            value={asObject(spec.input)}
            onChange={(input) => write({ ...spec, input })}
            addLabel="Add input field"
            keyPlaceholder="input field"
            valuePlaceholder={kind === 'alert' ? '{{ $alert.labels.name }}' : '{{ $event.payload.id }}'}
            problemsOf={(k) => problemsAt(ndv.problems, `${field}.input.${k}`)}
            readOnly={readOnly}
          />
        </ParamSection>
      </div>
    </NdvContext.Provider>
  )
}

function ErrorTrigger() {
  return <p className="text-caption text-secondary">When a step fails and nothing catches it, the steps after this trigger run, then the job fails. They read the failure as $error: its message, its class and the step that failed.</p>
}

/** The Parameters of a trigger node. */
export function TriggerParameters({ target, doc, readOnly = false }: { target: TriggerTarget; doc: WorkflowDocument; readOnly?: boolean }) {
  if (target.kind === 'api') return <ApiTrigger doc={doc} readOnly={readOnly} />
  if (target.kind === 'error') return <ErrorTrigger />
  return <SpecTrigger kind={target.kind} index={target.index} doc={doc} readOnly={readOnly} />
}

/** The document problems a trigger shows. */
export function triggerProblems(problems: readonly Problem[], target: TriggerTarget): Problem[] {
  if (target.kind === 'api') return problems.filter((p) => !p.step && p.field !== undefined && (p.field.startsWith('api.') || p.field.startsWith('mcp.')))
  if (target.kind === 'error') return []
  return problems.filter((p) => !p.step && p.field !== undefined && (p.field === `triggers[${target.index}]` || p.field.startsWith(`triggers[${target.index}].`)))
}
