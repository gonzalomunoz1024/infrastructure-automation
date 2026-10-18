/**
 * The Parameters tab of a step: a form per step type, generated from the registry where the
 * registry knows the shape (a provider action's input, a child workflow's input, a resource
 * type's attributes) and written by hand for the flow steps (switch cases, fork branches, the
 * loop, wait, set). Every field writes straight into the step body through the store.
 */
import { createContext, useContext, useMemo, useState, type ReactNode } from 'react'
import { Button, cx, Icon, Segmented, Tag, type SelectOption } from '@iaf/ui'
import { displayName, STEP_TYPES, type JsonObject, type JsonValue, type ParsedStep, type Problem, type StepBody, type StepType, type WorkflowDocument } from '@iaf/workflow-checks'
import type { Schema } from '../../lib/types'
import { findStepLocation, safeParseSteps } from '../model/graph'
import { findAction, findProvider, knownGroups, type Registry } from '../model/registry'
import { fieldsOf, type FieldType } from '../model/schema'
import { useDesignerStore } from '../model/store'
import { effectTags } from '../creator/catalog'
import { useNdv } from './context'
import { asObject, asString, isSettingsField, problemsAt, readField, rootOf, writeField } from './fields'
import { FieldLabel, KeyValueEditor, ParameterField, ParamSection, ProblemList, type ParamSpec } from './ParameterField'

interface StepEdit {
  stepId: string
  body: StepBody
  step: ParsedStep
  registry: Registry | null
  doc: WorkflowDocument
  readOnly: boolean
  get: (field: string) => JsonValue | undefined
  set: (field: string, value: JsonValue | undefined) => void
  update: (fn: (body: StepBody) => void, coalesce?: string) => void
}

const StepEditContext = createContext<StepEdit | null>(null)

function useStepEdit(): StepEdit {
  const v = useContext(StepEditContext)
  if (!v) throw new Error('useStepEdit outside StepParameters')
  return v
}

type PProps = Omit<ParamSpec, 'type'> & { type?: FieldType; onChange?: (v: JsonValue | undefined) => void; value?: JsonValue | undefined }

/** A parameter of the open step, read from and written to its body. */
function P({ onChange, value, ...spec }: PProps) {
  const { get, set, readOnly } = useStepEdit()
  return <ParameterField spec={{ type: 'string', ...spec }} value={value !== undefined ? value : get(spec.field)} onChange={onChange ?? ((v) => set(spec.field, v))} readOnly={readOnly} />
}

/** A free-form object parameter as key/value rows. */
function KeyValueParam({ field, label, description, addLabel, keyPlaceholder, valuePlaceholder }: { field: string; label: string; description?: string; addLabel?: string; keyPlaceholder?: string; valuePlaceholder?: string }) {
  const { get, set, readOnly } = useStepEdit()
  const { problems } = useNdv()
  const value = get(field)
  const own = problems.filter((p) => p.field === field)
  return (
    <div data-field={field}>
      <FieldLabel label={label} description={description} />
      <div className="mt-1">
        <KeyValueEditor
          value={asObject(value)}
          onChange={(v) => set(field, Object.keys(v).length === 0 && value === undefined ? undefined : v)}
          addLabel={addLabel}
          problemsOf={(k) => problemsAt(problems, `${field}.${k}`)}
          readOnly={readOnly}
          keyPlaceholder={keyPlaceholder}
          valuePlaceholder={valuePlaceholder}
        />
      </div>
      <ProblemList problems={own} className="mt-1" />
    </div>
  )
}

function optionLabel(schema: Schema, key: string): string {
  return typeof schema.title === 'string' ? schema.title : key
}

/** The fields of an input schema under `prefix` (provider input, child workflow input, attributes). */
function SchemaFields({ schema, prefix, emptyText }: { schema: Schema | null | undefined; prefix: string; emptyText: string }) {
  const { get } = useStepEdit()
  const fields = fieldsOf(schema)
  const current = asObject(get(prefix))
  if (fields.length === 0) {
    return <P field={prefix} label={optionLabel(schema ?? {}, 'Input')} type="object" description={emptyText} />
  }
  const known = new Set(fields.map((f) => f.key))
  const extra = Object.keys(current).filter((k) => !known.has(k))
  return (
    <>
      {fields.map((f) => {
        const hint = [f.description, f.resource ? `A ${f.resource.type} id` : null].filter(Boolean).join('. ')
        return (
          <P
            key={f.key}
            field={`${prefix}.${f.key}`}
            label={f.title}
            type={f.type}
            required={f.required}
            description={hint || undefined}
            options={f.enum?.map((e) => ({ value: e, label: e }))}
            placeholder={f.default !== undefined ? String(f.default) : undefined}
          />
        )
      })}
      {extra.map((k) => (
        <P key={k} field={`${prefix}.${k}`} label={k} type="any" description="Not a field of the schema" />
      ))}
    </>
  )
}

function CredentialParam({ field = 'credential', kinds }: { field?: string; kinds?: string[] }) {
  const { registry } = useStepEdit()
  const creds = registry?.credentials ?? []
  const sorted = [...creds].sort((a, b) => Number(kinds ? !kinds.includes(b.type) : 0) - Number(kinds ? !kinds.includes(a.type) : 0) || a.name.localeCompare(b.name))
  const options: SelectOption[] = sorted.map((c) => ({ value: `vault:${c.name}`, label: c.name, hint: c.type }))
  return <P field={field} label="Credential" options={options} expression={false} mono description="A Vault reference: the secret itself never goes in the workflow file" />
}

const METHODS: SelectOption[] = ['GET', 'POST', 'PUT', 'PATCH', 'DELETE'].map((m) => ({ value: m, label: m }))
const CALL_TYPES = ['openapi', 'http', 'sdk']

function ProviderParams() {
  const { get, update, registry } = useStepEdit()
  const providerName = asString(get('provider'))
  const actionId = asString(get('action'))
  const providers = (registry?.providers ?? []).filter((p) => CALL_TYPES.includes(p.type))
  const provider = findProvider(registry, providerName)
  const action = findAction(registry, providerName, actionId)
  const setProvider = (v: JsonValue | undefined) =>
    update((b) => {
      writeField(b, 'provider', v ?? '')
      if (v !== providerName) writeField(b, 'action', '')
    })
  return (
    <>
      <P field="provider" label="Provider" required options={providers.map((p) => ({ value: p.name, label: p.title, hint: p.name }))} expression={false} onChange={setProvider} />
      <P field="action" label="Action" required options={(provider?.actions ?? []).map((a) => ({ value: a.id, label: a.title || a.id, hint: `${a.id} · ${a.effect}` }))} expression={false} />
      {action && (
        <div className="flex flex-wrap items-center gap-1.5">
          {effectTags(action).map((t) => (
            <Tag key={t.label} size="sm" tone={t.tone}>
              {t.label}
            </Tag>
          ))}
          <Tag size="sm" outline>
            retry {action.retry}
          </Tag>
        </div>
      )}
      <ParamSection title="Input">
        <SchemaFields schema={action?.input} prefix="input" emptyText="The action's fields as JSON; values may be expressions" />
      </ParamSection>
    </>
  )
}

function HttpParams() {
  return (
    <>
      <P field="method" label="Method" options={METHODS} expression={false} />
      <P field="url" label="URL" required placeholder="https://api.example.com/things/{{ $input.id }}" mono />
      <KeyValueParam field="headers" label="Headers" addLabel="Add header" keyPlaceholder="Header" />
      <P field="body" label="Body" type="any" description="JSON, or an expression" />
      <CredentialParam kinds={['token', 'basic', 'bearer']} />
    </>
  )
}

type PlaybookSource = 'library' | 'git' | 'upload'

async function sha256Of(file: File): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', await file.arrayBuffer())
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, '0')).join('')
}

function AnsibleParams() {
  const { get, set, registry, readOnly } = useStepEdit()
  const { problems } = useNdv()
  const pb = get('playbook')
  const obj = pb !== null && typeof pb === 'object' && !Array.isArray(pb) ? pb : null
  const source: PlaybookSource = typeof pb === 'string' ? 'library' : obj ? (obj.git !== undefined ? 'git' : obj.upload !== undefined ? 'upload' : obj.library !== undefined ? 'library' : 'git') : 'git'
  const playbooks = registry?.playbooks ?? []
  const libraryField = typeof pb === 'string' || !obj ? 'playbook' : 'playbook.library'
  const [hashing, setHashing] = useState(false)
  const setSource = (s: PlaybookSource) => {
    if (s === source) return
    if (s === 'library') set('playbook', playbooks[0]?.name ?? '')
    else if (s === 'git') set('playbook', { git: { repo: '', ref: 'main', path: '' }, commit: '' })
    else set('playbook', { upload: { file: '', sha256: '' } })
  }
  const fromFile = async (file: File | undefined) => {
    if (!file) return
    setHashing(true)
    try {
      const hash = await sha256Of(file)
      set('playbook', { ...(obj ?? {}), upload: { file: file.name, sha256: hash } })
    } finally {
      setHashing(false)
    }
  }
  return (
    <>
      <div>
        <FieldLabel label="Playbook source" required />
        <Segmented<PlaybookSource>
          value={source}
          onChange={setSource}
          ariaLabel="Playbook source"
          size="sm"
          options={[
            { value: 'library', label: 'Library' },
            { value: 'git', label: 'Git' },
            { value: 'upload', label: 'Upload' },
          ]}
          className="mt-1"
        />
        <ProblemList problems={problems.filter((p) => p.field === 'playbook' && source !== 'library')} className="mt-1" />
      </div>
      {source === 'library' && (
        <P
          field={libraryField}
          label="Playbook"
          required
          expression={false}
          mono
          problemsExact={['playbook']}
          options={playbooks.map((x) => ({ value: x.name, label: x.name, hint: [x.version, x.owner].filter(Boolean).join(' · ') }))}
          description="A playbook of the library: <provider>/<playbook>"
        />
      )}
      {source === 'git' && (
        <>
          <P field="playbook.git.repo" label="Repository" required mono placeholder="github.com/acme/ops-playbooks" expression={false} />
          <div className="grid grid-cols-2 gap-3">
            <P field="playbook.git.ref" label="Ref" required mono placeholder="v1.0.0" expression={false} />
            <P field="playbook.git.path" label="Path" required mono placeholder="vm/restart.yml" expression={false} />
          </div>
          <P field="playbook.commit" label="Commit" mono placeholder="the commit every run uses" expression={false} description="Pin the playbook to a commit so every run uses the same code" />
        </>
      )}
      {source === 'upload' && (
        <>
          <P field="playbook.upload.file" label="File" required mono expression={false} />
          <P field="playbook.upload.sha256" label="SHA-256" required mono expression={false} />
          {!readOnly && (
            <label className={cx('inline-flex cursor-pointer items-center gap-1.5 text-caption font-medium text-accent hover:underline', hashing && 'opacity-60')}>
              <Icon name="upload" size={12} />
              {hashing ? 'Hashing…' : 'Fill in from a local file'}
              <input type="file" accept=".yml,.yaml" className="sr-only" onChange={(e) => void fromFile(e.target.files?.[0])} />
            </label>
          )}
        </>
      )}
      <div className="grid grid-cols-2 gap-3">
        <P field="inventory" label="Inventory" suggestions={['dynamic']} placeholder="dynamic" />
        <P field="limit" label="Limit" placeholder="{{ $vm.name }}" />
      </div>
      <KeyValueParam field="extraVars" label="Extra vars" addLabel="Add variable" keyPlaceholder="variable" />
      <CredentialParam kinds={['ssh']} />
      <P field="executionEnvironment" label="Execution environment" mono placeholder="ee-platform:2.17" expression={false} />
      <P field="checkModeOnTest" label="Check mode on the test environment" type="boolean" expression={false} />
    </>
  )
}

function TerraformParams() {
  return (
    <>
      <P field="module.source" label="Module source" required mono problemsExact={['module']} placeholder="git::https://github.com/acme/tf-modules.git//vm" expression={false} />
      <P field="module.version" label="Module version" mono placeholder="1.4.0" expression={false} description="Pin the module version so every run uses the same code" />
      <P field="action" label="Action" required options={['plan', 'apply', 'destroy'].map((a) => ({ value: a, label: a }))} expression={false} />
      <KeyValueParam field="vars" label="Variables" addLabel="Add variable" keyPlaceholder="variable" />
      <CredentialParam kinds={['token', 'cloud']} />
    </>
  )
}

function IdpParams() {
  const { get, registry } = useStepEdit()
  const tiles = (registry?.providers ?? []).filter((p) => p.type === 'idp').flatMap((p) => p.actions.map((a) => ({ provider: p, action: a })))
  const tile = asString(get('tile'))
  const current = tiles.find((t) => t.action.id === tile)
  return (
    <>
      <P field="tile" label="Tile" required expression={false} options={tiles.map((t) => ({ value: t.action.id, label: t.action.title || t.action.id, hint: t.provider.title }))} />
      <ParamSection title="Input">
        <SchemaFields schema={current?.action.input} prefix="input" emptyText="The tile's fields as JSON" />
      </ParamSection>
    </>
  )
}

function ActivityParams() {
  return (
    <>
      <P field="taskQueue" label="Task queue" required mono expression={false} />
      <P field="activity" label="Activity" required mono expression={false} />
      <P field="input" label="Input" type="object" />
    </>
  )
}

function RunParams() {
  const { get, registry, doc } = useStepEdit()
  const workflows = (registry?.workflows ?? []).filter((w) => w.name !== doc.metadata?.name)
  const name = asString(get('workflow'))
  const wf = workflows.find((w) => w.name === name)
  return (
    <>
      <P field="workflow" label="Workflow" required expression={false} options={workflows.map((w) => ({ value: w.name, label: w.title, hint: `${w.name} ${w.version}` }))} />
      <P field="version" label="Version" mono placeholder="^1" expression={false} description="A range like ^1, ~1.2 or 1.2.0" />
      <ParamSection title="Input">
        <SchemaFields schema={wf?.input} prefix="input" emptyText="The child job's input as JSON" />
      </ParamSection>
    </>
  )
}

function ApprovalParams() {
  const { doc } = useStepEdit()
  const groups = knownGroups(doc)
  return (
    <>
      <P field="group" label="Approver group" required suggestions={groups} expression={false} />
      <P field="message" label="Message" multiline placeholder="Restart {{ $vm.name }}?" description="What the approver decides on" />
      <P field="timeout" label="Timeout" mono placeholder="4h" expression={false} />
      <div className="grid grid-cols-2 gap-3">
        <P field="escalation.after" label="Escalate after" mono placeholder="2h" expression={false} />
        <P field="escalation.to" label="Escalate to" placeholder="group or person" expression={false} />
      </div>
    </>
  )
}

function laterSteps(doc: WorkflowDocument, stepId: string): { id: string; name: string }[] {
  const loc = findStepLocation(doc, stepId)
  if (!loc) return []
  return safeParseSteps(loc.list.slice(loc.index + 1)).map((s) => ({ id: s.id, name: displayName(s) }))
}

function SwitchParams() {
  const store = useDesignerStore()
  const { stepId, body, doc, update, readOnly } = useStepEdit()
  const { problems } = useNdv()
  const cases = Array.isArray(body.switch) ? body.switch : []
  const later = laterSteps(doc, stepId)
  const hasDefault = cases.some((c) => c.default !== undefined)
  const routeOf = (i: number): { value: string; steps: number } => {
    const c = cases[i]
    if (c.default !== undefined) {
      const d = c.default
      if (typeof d === 'string') return { value: d, steps: 0 }
      return d && Array.isArray(d.do) ? { value: '__do', steps: d.do.length } : { value: d?.then ?? '', steps: 0 }
    }
    return Array.isArray(c.case?.do) ? { value: '__do', steps: c.case.do.length } : { value: c.case?.then ?? '', steps: 0 }
  }
  const setRoute = (i: number, target: string) =>
    update((b) => {
      const entry = Array.isArray(b.switch) ? b.switch[i] : undefined
      if (!entry) return
      if (entry.default !== undefined) entry.default = target === '__do' ? { do: [] } : target
      else {
        entry.case = entry.case ?? {}
        if (target === '__do') {
          delete entry.case.then
          entry.case.do = []
        } else {
          entry.case.then = target
          delete entry.case.do
        }
      }
    })
  const routeOptions: SelectOption[] = [{ value: '__do', label: 'Its own branch on the canvas' }, ...later.map((s) => ({ value: s.id, label: `Jump to ${s.name}`, hint: s.id })), { value: 'end', label: 'End the job' }]
  return (
    <>
      <p className="text-caption text-tertiary">Cases are tried in order; the first whose condition holds runs. Without a default, the job carries on after the switch.</p>
      <ol className="space-y-3">
        {cases.map((c, i) => {
          const route = routeOf(i)
          const isDefault = c.default !== undefined
          return (
            <li key={i} className="rounded-md border border-stroke-light bg-surface-tertiary p-3" data-field={`switch[${i}]`}>
              <div className="mb-2 flex items-center gap-2">
                <span className="text-caption font-semibold text-primary">{isDefault ? 'Default' : `Case ${i + 1}`}</span>
                <span className="flex-1" />
                {!readOnly && (
                  <button type="button" onClick={() => store.removeSwitchCase(stepId, i)} aria-label={`Remove ${isDefault ? 'the default' : `case ${i + 1}`}`} title="Remove" className="rounded-sm p-1 text-tertiary hover:bg-surface-secondary hover:text-critical">
                    <Icon name="trash" size={13} />
                  </button>
                )}
              </div>
              <div className="space-y-3">
                {!isDefault && <P field={`switch[${i}].when`} label="When" required placeholder="{{ $input.size == 'large' }}" />}
                {route.steps > 0 ? (
                  <p className="text-caption text-secondary">
                    Runs {route.steps} {route.steps === 1 ? 'step' : 'steps'} of its own on the canvas, then carries on after the switch.
                  </p>
                ) : (
                  <ParameterField spec={{ field: `switch[${i}].then`, label: 'Then', type: 'enum', options: routeOptions, required: true, expression: false }} value={route.value || undefined} onChange={(v) => setRoute(i, typeof v === 'string' ? v : '__do')} readOnly={readOnly} />
                )}
                <ProblemList problems={problems.filter((p) => p.field === `switch[${i}]`)} />
              </div>
            </li>
          )
        })}
      </ol>
      {!readOnly && (
        <div className="flex gap-2">
          <Button size="sm" variant="secondary" icon="plus" onClick={() => store.addSwitchCase(stepId)}>
            Add case
          </Button>
          {!hasDefault && (
            <Button size="sm" variant="ghost" icon="plus" onClick={() => store.addSwitchCase(stepId, true)}>
              Add default
            </Button>
          )}
        </div>
      )}
    </>
  )
}

function ForkParams() {
  const store = useDesignerStore()
  const { stepId, step, readOnly } = useStepEdit()
  const { problems } = useNdv()
  const branches = step.branches ?? []
  return (
    <>
      <p className="text-caption text-tertiary">Every branch runs at the same time; the job carries on when all of them are done. Add steps to a branch from its output on the canvas.</p>
      <ol className="space-y-2">
        {branches.map((b, i) => (
          <li key={i} className="flex items-center gap-3 rounded-md border border-stroke-light bg-surface-tertiary px-3 py-2">
            <span className="text-caption font-semibold text-primary">Branch {i + 1}</span>
            <span className="min-w-0 flex-1 truncate text-caption text-tertiary">{b.length === 0 ? 'No steps yet' : b.map((s) => displayName(s)).join(' → ')}</span>
            {!readOnly && branches.length > 1 && (
              <button type="button" onClick={() => store.removeForkBranch(stepId, i)} aria-label={`Remove branch ${i + 1}`} title="Remove the branch and its steps" className="rounded-sm p-1 text-tertiary hover:bg-surface-secondary hover:text-critical">
                <Icon name="trash" size={13} />
              </button>
            )}
          </li>
        ))}
      </ol>
      <ProblemList problems={problems.filter((p) => p.field?.startsWith('branches'))} />
      {!readOnly && (
        <Button size="sm" variant="secondary" icon="plus" onClick={() => store.addForkBranch(stepId)}>
          Add branch
        </Button>
      )}
    </>
  )
}

function ForParams() {
  return (
    <>
      <P field="in" label="Loop over" required placeholder="{{ $json.items }}" description="An expression that gives a list" />
      <P field="each" label="Each item as" required mono placeholder="item" expression={false} description="The loop variable: $item inside the loop" />
      <p className="text-caption text-tertiary">The loop body hangs off the loop output on the canvas; “done” carries on after the last item.</p>
    </>
  )
}

function TryParams() {
  return (
    <>
      <p className="text-caption text-tertiary">The steps on the “try” output run first; when one fails, the steps on the “catch” output run with the error.</p>
      <P field="catch.as" label="Error as" mono placeholder="error" expression={false} description="The catch steps read the error as $error, or this alias" />
    </>
  )
}

function WaitParams() {
  const { get, update, readOnly } = useStepEdit()
  const mode: 'duration' | 'until' = get('until') !== undefined ? 'until' : 'duration'
  return (
    <>
      <div>
        <FieldLabel label="Wait" />
        <Segmented<'duration' | 'until'>
          value={mode}
          ariaLabel="Wait for"
          size="sm"
          className="mt-1"
          onChange={(m) => {
            if (readOnly || m === mode) return
            update((b) => {
              b.wait = m === 'until' ? { until: '' } : { duration: 'PT5M' }
            })
          }}
          options={[
            { value: 'duration', label: 'A while' },
            { value: 'until', label: 'Until a condition' },
          ]}
        />
      </div>
      {mode === 'duration' ? <P field="duration" label="Duration" mono placeholder="PT5M, 30m or 2h" /> : <P field="until" label="Until" required placeholder="{{ $json.status == 'ready' }}" />}
    </>
  )
}

function SetParams() {
  const { body, update, readOnly } = useStepEdit()
  const { problems } = useNdv()
  const vars = asObject(body.set as JsonValue | undefined)
  return (
    <>
      <p className="text-caption text-tertiary">Later steps read each variable as $vars.name.</p>
      <KeyValueEditor
        value={vars}
        onChange={(v) =>
          update((b) => {
            b.set = v
          }, 'set')
        }
        addLabel="Add variable"
        keyPlaceholder="variable"
        problemsOf={(k) => problemsAt(problems, k)}
        readOnly={readOnly}
      />
    </>
  )
}

function RaiseParams() {
  return (
    <>
      <P field="error" label="Error" required mono placeholder="UpgradeFailed" expression={false} />
      <P field="message" label="Message" placeholder="{{ $json.reason }}" />
    </>
  )
}

function ResourceTypeParam() {
  const { registry } = useStepEdit()
  return <P field="type" label="Resource type" required expression={false} options={(registry?.resourceTypes ?? []).map((t) => ({ value: t.name, label: t.name, hint: t.owner }))} />
}

function UseParams() {
  return (
    <>
      <ResourceTypeParam />
      <P field="id" label="Id" placeholder="{{ $input.vmId }}" />
      <KeyValueParam field="selector" label="Selector" description="Attributes to match when there is no id" addLabel="Add attribute" keyPlaceholder="attribute" />
      <div className="grid grid-cols-2 gap-3">
        <P field="waitUntil" label="Wait until" options={[{ value: 'ready', label: 'ready' }]} expression={false} />
        <P field="checkAccess" label="Check access" type="boolean" expression={false} description="Ask policy whether the requester may use it" />
      </div>
    </>
  )
}

function RegisterParams() {
  const { get, registry } = useStepEdit()
  const type = asString(get('type'))
  const rt = registry?.resourceTypes.find((t) => t.name === type)
  return (
    <>
      <ResourceTypeParam />
      <P field="id" label="Id" placeholder="minted by the registry when empty" />
      <P field="state" label="State" expression={false} options={(rt?.states ?? []).map((s) => ({ value: s, label: s }))} />
      <ParamSection title="Attributes">
        <SchemaFields schema={rt?.schema} prefix="attributes" emptyText="The resource's attributes as JSON" />
      </ParamSection>
      <P field="builtFrom" label="Built from" type="array" description="Ids of the resources this one was built from" />
    </>
  )
}

function NotifyParams() {
  return (
    <>
      <P field="channel" label="Channel" required placeholder="#ops" />
      <P field="message" label="Message" required multiline placeholder="Restarted {{ $vm.name }}" />
    </>
  )
}

function ScriptParams() {
  return (
    <>
      <P field="language" label="Language" options={[{ value: 'javascript', label: 'JavaScript' }]} expression={false} />
      <P field="code" label="Code" required multiline mono expression={false} description="The function body; input is in scope and the return value is the output" />
      <KeyValueParam field="input" label="Input" description="What the script sees as input" addLabel="Add field" />
    </>
  )
}

const EDITORS: Record<StepType, () => ReactNode> = {
  provider: ProviderParams,
  http: HttpParams,
  ansible: AnsibleParams,
  terraform: TerraformParams,
  idp: IdpParams,
  activity: ActivityParams,
  run: RunParams,
  approval: ApprovalParams,
  switch: SwitchParams,
  fork: ForkParams,
  for: ForParams,
  try: TryParams,
  wait: WaitParams,
  set: SetParams,
  raise: RaiseParams,
  use: UseParams,
  register: RegisterParams,
  notify: NotifyParams,
  script: ScriptParams,
}

/** The top-level keys each editor shows; problems elsewhere are listed above the form. */
const HANDLED: Record<StepType, readonly string[]> = {
  provider: ['provider', 'action', 'input'],
  http: ['method', 'url', 'headers', 'body', 'credential'],
  ansible: ['playbook', 'inventory', 'limit', 'extraVars', 'credential', 'executionEnvironment', 'checkModeOnTest'],
  terraform: ['module', 'action', 'vars', 'credential'],
  idp: ['tile', 'input'],
  activity: ['taskQueue', 'activity', 'input'],
  run: ['workflow', 'version', 'input'],
  approval: ['group', 'message', 'timeout', 'escalation'],
  switch: ['switch'],
  fork: ['branches'],
  for: ['each', 'in'],
  try: ['catch'],
  wait: ['duration', 'until'],
  set: [],
  raise: ['error', 'message'],
  use: ['type', 'id', 'selector', 'waitUntil', 'checkAccess'],
  register: ['type', 'id', 'state', 'attributes', 'builtFrom'],
  notify: ['channel', 'message'],
  script: ['language', 'code', 'input'],
}

/** The step problems the Parameters form does not show under a field. */
export function looseProblems(step: ParsedStep, body: StepBody, problems: readonly Problem[]): Problem[] {
  const handled = step.type === 'set' ? Object.keys(asObject(body.set as JsonValue | undefined)) : HANDLED[step.type]
  return problems.filter((p) => !p.field || (!isSettingsField(p.field) && !handled.includes(rootOf(p.field))))
}

export function StepParameters({ stepId, body, step, registry, doc, readOnly = false }: { stepId: string; body: StepBody; step: ParsedStep; registry: Registry | null; doc: WorkflowDocument; readOnly?: boolean }) {
  const store = useDesignerStore()
  const value = useMemo<StepEdit>(
    () => ({
      stepId,
      body,
      step,
      registry,
      doc,
      readOnly,
      get: (field) => readField(body, field),
      set: (field, v) => store.updateStep(stepId, (b) => writeField(b, field, v), `param:${stepId}:${field}`),
      update: (fn, coalesce) => store.updateStep(stepId, fn, coalesce ? `param:${stepId}:${coalesce}` : undefined),
    }),
    [stepId, body, step, registry, doc, readOnly, store],
  )
  const Editor = EDITORS[step.type]
  return (
    <StepEditContext.Provider value={value}>
      <div className="space-y-4">
        <Editor />
      </div>
    </StepEditContext.Provider>
  )
}

export function stepKindLabel(step: ParsedStep, registry: Registry | null): string {
  const w = asObject(step.with as JsonValue | undefined) as JsonObject
  if (step.type === 'provider') {
    const p = findProvider(registry, asString(w.provider))
    const a = findAction(registry, asString(w.provider), asString(w.action))
    return [p?.title ?? asString(w.provider), a?.title ?? asString(w.action)].filter(Boolean).join(' · ') || STEP_TYPES.provider.title
  }
  return STEP_TYPES[step.type].title
}
