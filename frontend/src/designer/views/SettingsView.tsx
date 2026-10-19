/**
 * The Settings tab: everything about the workflow that is not a step. Its metadata, the API it
 * serves (batch runs and their rollout included), the input schema as an editable table (with
 * x-resource and the workflow that creates a missing resource), the MCP tool, policy, triggers,
 * how a new version rolls out, and the workflow outputs. Every change goes through the store, so
 * it is undoable and checked live like the canvas.
 */
import { useMemo, useRef, useState, type ReactNode } from 'react'
import { Button, Checkbox, Icon, Input, Segmented, Select, StateTag, type SelectOption } from '@iaf/ui'
import type { JsonObject, JsonSchema, JsonValue, WorkflowDocument } from '@iaf/workflow-checks'
import { api } from '../../lib/api'
import { useQuery } from '../../lib/queries'
import { apiTriggerLabel, triggerNodeId } from '../model/graph'
import { contextAt } from '../model/expressions'
import { useDesignerSelector, useDesignerStore } from '../model/store'
import { revealIn } from '../scroll'
import { NdvContext, type NdvContextValue } from '../ndv/context'
import { asObject, asString, problemsAt, readDocField, writeDocField } from '../ndv/fields'
import { FieldLabel, KeyValueEditor, ParameterField, ProblemList, type ParamSpec } from '../ndv/ParameterField'

const TYPES = ['string', 'number', 'integer', 'boolean', 'object', 'array'] as const

function Section({ id, title, description, children }: { id: string; title: string; description?: string; children: ReactNode }) {
  return (
    <section id={`settings-${id}`} aria-labelledby={`settings-${id}-title`} className="rounded-lg border border-stroke-light bg-surface p-5">
      <h2 id={`settings-${id}-title`} className="text-subheadline font-semibold text-primary">
        {title}
      </h2>
      {description && <p className="mt-0.5 text-caption text-tertiary">{description}</p>}
      <div className="mt-4 space-y-4">{children}</div>
    </section>
  )
}

function useDocField() {
  const store = useDesignerStore()
  const doc = useDesignerSelector((s) => s.doc)
  return {
    doc,
    get: (field: string) => readDocField(doc as unknown as JsonValue, field),
    set: (field: string, v: JsonValue | undefined) => store.update((d) => writeDocField(d as unknown as JsonObject, field, v), `settings:${field}`),
  }
}

function DocField(spec: ParamSpec) {
  const { get, set } = useDocField()
  return <ParameterField spec={{ expression: false, ...spec }} value={get(spec.field)} onChange={(v) => set(spec.field, v)} />
}

/** A list edited as comma-separated text (tags, roles, stages), written when the field loses focus; `problemsExact` adds the problems of other fields (not their children's). */
function ListField({ field, label, numbers, placeholder, description, required, problemsExact = [] }: { field: string; label: string; numbers?: boolean; placeholder?: string; description?: string; required?: boolean; problemsExact?: string[] }) {
  const { get, set } = useDocField()
  const value = get(field)
  const asText = (v: JsonValue | undefined) => (Array.isArray(v) ? v.join(', ') : typeof v === 'string' ? v : '')
  // the draft exists only while the field is focused; otherwise the document's value shows
  const [draft, setDraft] = useState('')
  const [focused, setFocused] = useState(false)
  const text = focused ? draft : asText(value)
  const ndvProblems = useDesignerSelector((s) => s.problems)
  const docProblems = ndvProblems.filter((p) => !p.step)
  const problems = [...problemsAt(docProblems, field), ...docProblems.filter((p) => p.field !== undefined && problemsExact.includes(p.field))]
  const commit = () => {
    setFocused(false)
    const parts = text
      .split(',')
      .map((s) => s.trim())
      .filter(Boolean)
    if (parts.length === 0) return set(field, undefined)
    set(field, numbers ? parts.map((p) => Number(p.replace('%', ''))).filter((n) => Number.isFinite(n)) : parts)
  }
  return (
    <div>
      <FieldLabel htmlFor={`list-${field}`} label={label} required={required} description={description} />
      <Input id={`list-${field}`} mono={numbers} value={text} placeholder={placeholder} onFocus={() => {
          setDraft(asText(value))
          setFocused(true)
        }}
        onChange={(e) => {
          setDraft(e.target.value)
          setFocused(true)
        }}
        onBlur={commit} onKeyDown={(e) => e.key === 'Enter' && commit()} invalid={problems.some((p) => p.level === 'error')} className="mt-1" />
      <ProblemList problems={problems} className="mt-1" />
    </div>
  )
}

// ---------------------------------------------------------------------------------------------
// input schema

function schemaOf(doc: WorkflowDocument): JsonSchema {
  return (doc.api?.input ?? { type: 'object', properties: {} }) as JsonSchema
}

function InputSchemaEditor() {
  const store = useDesignerStore()
  const doc = useDesignerSelector((s) => s.doc)
  const registry = useDesignerSelector((s) => s.registry)
  const problems = useDesignerSelector((s) => s.problems)
  const schema = schemaOf(doc)
  const props = schema.properties ?? {}
  const required = new Set(Array.isArray(schema.required) ? schema.required : [])
  const keys = Object.keys(props)
  const [open, setOpen] = useState<string | null>(null)

  const write = (fn: (s: JsonSchema) => void, coalesce?: string) =>
    store.update((d) => {
      if (!d.api) d.api = {}
      const s = (d.api.input ?? { type: 'object', properties: {} }) as JsonSchema
      if (!s.type) s.type = 'object'
      if (!s.properties) s.properties = {}
      fn(s)
      if (Array.isArray(s.required) && s.required.length === 0) delete s.required
      d.api.input = s
    }, coalesce)

  const setProp = (key: string, patch: (p: JsonSchema) => void, coalesce?: string) =>
    write((s) => {
      const p = { ...((s.properties ?? {})[key] ?? {}) } as JsonSchema
      patch(p)
      s.properties = { ...(s.properties ?? {}), [key]: p }
    }, coalesce)

  const rename = (from: string, to: string): boolean => {
    const key = to.trim()
    if (!key || key === from) return key === from
    if (!/^[A-Za-z_][A-Za-z0-9_]*$/.test(key) || Object.hasOwn(props, key)) return false
    write((s) => {
      const next: Record<string, JsonSchema> = {}
      for (const [k, v] of Object.entries(s.properties ?? {})) next[k === from ? key : k] = v
      s.properties = next
      if (Array.isArray(s.required)) s.required = s.required.map((r) => (r === from ? key : r))
    })
    if (open === from) setOpen(key)
    return true
  }

  const move = (key: string, dir: -1 | 1) =>
    write((s) => {
      const entries = Object.entries(s.properties ?? {})
      const i = entries.findIndex(([k]) => k === key)
      const j = i + dir
      if (i < 0 || j < 0 || j >= entries.length) return
      ;[entries[i], entries[j]] = [entries[j], entries[i]]
      s.properties = Object.fromEntries(entries)
    })

  const remove = (key: string) =>
    write((s) => {
      const next = { ...(s.properties ?? {}) }
      delete next[key]
      s.properties = next
      if (Array.isArray(s.required)) s.required = s.required.filter((r) => r !== key)
    })

  const add = () => {
    let n = keys.length + 1
    let key = `field${n}`
    while (Object.hasOwn(props, key)) key = `field${++n}`
    write((s) => {
      s.properties = { ...(s.properties ?? {}), [key]: { type: 'string' } }
    })
    setOpen(key)
  }

  const resourceOptions: SelectOption[] = [{ value: '', label: '—' }, ...(registry?.resourceTypes ?? []).map((t) => ({ value: t.name, label: t.name, hint: t.owner }))]
  const workflowOptions: SelectOption[] = [{ value: '', label: '—' }, ...(registry?.workflows ?? []).filter((w) => w.name !== doc.metadata?.name).map((w) => ({ value: w.name, label: w.title, hint: w.name }))]
  const docProblems = problems.filter((p) => !p.step)

  return (
    <div className="space-y-2" data-testid="input-schema">
      <div className="hidden grid-cols-[minmax(0,1.1fr)_120px_minmax(0,1.3fr)_80px_minmax(0,1fr)_76px] gap-2 px-2 text-caption2 font-semibold uppercase tracking-wide text-tertiary md:grid">
        <span>Field</span>
        <span>Type</span>
        <span>Title</span>
        <span>Required</span>
        <span>Resource</span>
        <span />
      </div>
      {keys.length === 0 && <p className="px-2 text-caption text-secondary">The input has no fields; a job starts with an empty input.</p>}
      {keys.map((key, i) => {
        const p = (props[key] ?? {}) as JsonSchema
        const xr = asObject(p['x-resource'] as JsonValue | undefined)
        const type = typeof p.type === 'string' ? p.type : 'string'
        const own = problemsAt(docProblems, `api.input.${key}`)
        return (
          <div key={key} className="rounded-md border border-stroke-light bg-surface-tertiary">
            <div className="grid grid-cols-1 items-center gap-2 p-2 md:grid-cols-[minmax(0,1.1fr)_120px_minmax(0,1.3fr)_80px_minmax(0,1fr)_76px]">
              <Input
                key={key}
                size="sm"
                mono
                defaultValue={key}
                aria-label={`Name of ${key}`}
                onBlur={(e) => {
                  if (!rename(key, e.currentTarget.value)) e.currentTarget.value = key
                }}
                onKeyDown={(e) => e.key === 'Enter' && e.currentTarget.blur()}
              />
              <Select size="sm" value={type} ariaLabel={`Type of ${key}`} options={TYPES.map((t) => ({ value: t, label: t }))} onChange={(v) => setProp(key, (q) => void (q.type = v))} />
              <Input size="sm" value={typeof p.title === 'string' ? p.title : ''} placeholder={key} aria-label={`Title of ${key}`} onChange={(e) => setProp(key, (q) => (e.target.value ? void (q.title = e.target.value) : void delete q.title), `input:${key}:title`)} />
              <Checkbox
                checked={required.has(key)}
                label="Required"
                onChange={(c) =>
                  write((s) => {
                    const r = new Set(Array.isArray(s.required) ? s.required : [])
                    if (c) r.add(key)
                    else r.delete(key)
                    s.required = Object.keys(s.properties ?? {}).filter((k) => r.has(k))
                  })
                }
              />
              <Select
                size="sm"
                value={asString(xr.type)}
                ariaLabel={`Resource type of ${key}`}
                options={resourceOptions}
                onChange={(v) =>
                  setProp(key, (q) => {
                    if (!v) delete q['x-resource']
                    else q['x-resource'] = { ...asObject(q['x-resource'] as JsonValue | undefined), type: v }
                  })
                }
              />
              <div className="flex items-center justify-end gap-0.5">
                <button type="button" onClick={() => setOpen(open === key ? null : key)} aria-expanded={open === key} aria-label={`More about ${key}`} title="More" className="rounded-sm p-1 text-tertiary hover:bg-surface-secondary hover:text-primary">
                  <Icon name="more-horizontal" size={14} />
                </button>
                <button type="button" onClick={() => move(key, -1)} disabled={i === 0} aria-label={`Move ${key} up`} title="Move up" className="rounded-sm p-1 text-tertiary hover:bg-surface-secondary hover:text-primary">
                  <Icon name="chevron-up" size={14} />
                </button>
                <button type="button" onClick={() => remove(key)} aria-label={`Remove ${key}`} title="Remove" className="rounded-sm p-1 text-tertiary hover:bg-surface-secondary hover:text-critical">
                  <Icon name="trash" size={13} />
                </button>
              </div>
            </div>
            {open === key && (
              <div className="grid grid-cols-1 gap-3 border-t border-stroke-light p-3 md:grid-cols-2">
                <div className="md:col-span-2">
                  <FieldLabel htmlFor={`desc-${key}`} label="Description" />
                  <Input id={`desc-${key}`} size="sm" value={typeof p.description === 'string' ? p.description : ''} placeholder="Shown in the catalog form and the MCP tool" onChange={(e) => setProp(key, (q) => (e.target.value ? void (q.description = e.target.value) : void delete q.description), `input:${key}:description`)} className="mt-1" />
                </div>
                <div>
                  <FieldLabel htmlFor={`enum-${key}`} label="Allowed values" description="Comma separated; empty allows any value" />
                  <Input
                    id={`enum-${key}`}
                    size="sm"
                    mono
                    defaultValue={Array.isArray(p.enum) ? p.enum.join(', ') : ''}
                    placeholder="dev, test, prod"
                    onBlur={(e) =>
                      setProp(key, (q) => {
                        const values = e.target.value
                          .split(',')
                          .map((s) => s.trim())
                          .filter(Boolean)
                        if (values.length > 0) q.enum = values
                        else delete q.enum
                      })
                    }
                    className="mt-1"
                  />
                </div>
                <div>
                  <FieldLabel htmlFor={`default-${key}`} label="Default" />
                  <Input
                    id={`default-${key}`}
                    size="sm"
                    mono
                    value={p.default === undefined ? '' : typeof p.default === 'string' ? p.default : JSON.stringify(p.default)}
                    onChange={(e) =>
                      setProp(
                        key,
                        (q) => {
                          const t = e.target.value
                          if (!t) return void delete q.default
                          if (type === 'string') q.default = t
                          else {
                            try {
                              q.default = JSON.parse(t) as JsonValue
                            } catch {
                              q.default = t
                            }
                          }
                        },
                        `input:${key}:default`,
                      )
                    }
                    className="mt-1"
                  />
                </div>
                {xr.type !== undefined && (
                  <div className="md:col-span-2">
                    <FieldLabel label="When it does not exist, create it with" description="x-resource.orCreateWith: a published workflow that creates the resource first" />
                    <div className="mt-1">
                      <Select
                        size="sm"
                        value={asString(xr.orCreateWith)}
                        ariaLabel={`Workflow that creates a missing ${asString(xr.type)}`}
                        options={workflowOptions}
                        onChange={(v) =>
                          setProp(key, (q) => {
                            const next = { ...asObject(q['x-resource'] as JsonValue | undefined) }
                            if (v) next.orCreateWith = v
                            else delete next.orCreateWith
                            q['x-resource'] = next
                          })
                        }
                      />
                    </div>
                  </div>
                )}
              </div>
            )}
            {own.length > 0 && (
              <div className="px-3 pb-2">
                <ProblemList problems={own} />
              </div>
            )}
          </div>
        )
      })}
      <ProblemList problems={docProblems.filter((p) => p.field === 'api.input' || p.field === 'api.input.required')} />
      <Button size="sm" variant="secondary" icon="plus" onClick={add}>
        Add field
      </Button>
    </div>
  )
}

// ---------------------------------------------------------------------------------------------
// triggers

function TriggersList() {
  const store = useDesignerStore()
  const doc = useDesignerSelector((s) => s.doc)
  const triggers = doc.triggers ?? []
  const { method, path } = apiTriggerLabel(doc)
  const openTrigger = (nodeId: string) => {
    store.setTab('editor')
    store.openNdv(nodeId)
  }
  return (
    <div className="space-y-2">
      <ul className="divide-y divide-stroke-light rounded-md border border-stroke-light">
        <li className="flex items-center gap-3 px-3 py-2.5">
          <Icon name="link" size={14} className="text-accent" />
          <span className="text-footnote font-medium text-primary">API</span>
          <span className="truncate font-mono text-caption text-tertiary">{`${method} ${path}`}</span>
          <span className="flex-1" />
          <Button size="sm" variant="ghost" onClick={() => openTrigger('trigger:api')}>
            Open
          </Button>
        </li>
        {triggers.map((t, i) => {
          const kind = Object.hasOwn(t, 'alert') ? 'alert' : Object.hasOwn(t, 'event') ? 'event' : asString(t.type as JsonValue | undefined) || 'other'
          const spec = asObject(t[kind] as JsonValue | undefined)
          const summary = kind === 'alert' ? [asString(asObject(spec.match).source), ...Object.entries(asObject(asObject(spec.match).labels)).map(([k, v]) => `${k}=${asString(v)}`)].filter(Boolean).join(' · ') : kind === 'event' ? asString(spec.topic) : ''
          return (
            <li key={i} className="flex items-center gap-3 px-3 py-2.5">
              <Icon name={kind === 'alert' ? 'bell' : 'pulse'} size={14} className={kind === 'alert' ? 'text-warning' : 'text-healthy'} />
              <span className="text-footnote font-medium capitalize text-primary">{kind}</span>
              <span className="truncate text-caption text-tertiary">{summary || 'not set up yet'}</span>
              <span className="flex-1" />
              <Button size="sm" variant="ghost" onClick={() => openTrigger(triggerNodeId(i))}>
                Open
              </Button>
              <button type="button" onClick={() => store.removeTrigger(i)} aria-label={`Remove the ${kind} trigger`} title="Remove" className="rounded-sm p-1 text-tertiary hover:bg-surface-secondary hover:text-critical">
                <Icon name="trash" size={13} />
              </button>
            </li>
          )
        })}
      </ul>
      <div className="flex gap-2">
        <Button size="sm" variant="secondary" icon="bell" onClick={() => store.addTrigger('alert')}>
          Add alert trigger
        </Button>
        <Button size="sm" variant="secondary" icon="pulse" onClick={() => store.addTrigger('event')}>
          Add event trigger
        </Button>
      </div>
    </div>
  )
}

// ---------------------------------------------------------------------------------------------
// the view

const NAV: { id: string; label: string }[] = [
  { id: 'workflow', label: 'Workflow' },
  { id: 'api', label: 'API' },
  { id: 'input', label: 'Input' },
  { id: 'mcp', label: 'MCP' },
  { id: 'policy', label: 'Policy' },
  { id: 'triggers', label: 'Triggers' },
  { id: 'rollout', label: 'Rollout' },
  { id: 'outputs', label: 'Outputs' },
]

export function SettingsView() {
  const store = useDesignerStore()
  const doc = useDesignerSelector((s) => s.doc)
  const isNew = useDesignerSelector((s) => s.isNew)
  const detail = useDesignerSelector((s) => s.detail)
  const problems = useDesignerSelector((s) => s.problems)
  const registry = useDesignerSelector((s) => s.registry)
  const me = useQuery(['me', 'default'], () => api.me())
  const docProblems = useMemo(() => problems.filter((p) => !p.step), [problems])
  const known = store.knownOutputs()
  const outputCtx = useMemo(() => contextAt(doc, null, known, registry).ctx, [doc, known, registry])
  const ndv: NdvContextValue = useMemo(() => ({ ctx: outputCtx, problems: docProblems, dragging: false, setDragging: () => {}, applyFix: (p) => void store.applyFix(p) }), [outputCtx, docProblems, store])
  const strategy = doc.rollout?.strategy === 'all' ? 'all' : 'canary'
  const counts = (prefix: string[]) => docProblems.filter((p) => p.field !== undefined && prefix.some((x) => p.field === x || p.field?.startsWith(`${x}.`) || p.field?.startsWith(`${x}[`))).length
  const navCount: Record<string, number> = {
    workflow: counts(['metadata']),
    api: counts(['api.method', 'api.path', 'api.batch', 'api.response', 'api.deprecated']),
    input: counts(['api.input']),
    mcp: counts(['mcp']),
    policy: counts(['policy']),
    triggers: counts(['triggers']),
    rollout: counts(['rollout']),
    outputs: counts(['workflow.outputs']),
  }
  const teams = me.data?.teams ?? []
  const scrollRef = useRef<HTMLDivElement>(null)

  return (
    <NdvContext.Provider value={ndv}>
      <div className="flex h-full min-h-0 bg-surface-secondary" data-testid="settings-view">
        <nav aria-label="Settings sections" className="hidden w-48 shrink-0 border-r border-stroke-light px-3 py-5 lg:block">
          <ul className="space-y-0.5">
            {NAV.map((n) => (
              <li key={n.id}>
                <button
                  type="button"
                  onClick={() => {
                    const section = document.getElementById(`settings-${n.id}`)
                    if (scrollRef.current && section) revealIn(scrollRef.current, section, { align: 'start', offset: 24, smooth: true })
                  }}
                  className="flex w-full items-center justify-between rounded-sm px-2.5 py-1.5 text-left text-footnote text-secondary hover:bg-surface hover:text-primary"
                >
                  {n.label}
                  {navCount[n.id] > 0 && <span className="rounded-full bg-critical-tint px-1.5 text-caption2 font-medium text-critical">{navCount[n.id]}</span>}
                </button>
              </li>
            ))}
          </ul>
        </nav>
        {/* relative: the absolutely placed bits inside (sr-only labels) scroll and clip with it instead of hanging below the designer and making it scrollable */}
        <div ref={scrollRef} className="relative min-h-0 flex-1 overflow-y-auto">
          <div className="mx-auto max-w-3xl space-y-5 px-4 py-6 sm:px-6">
            <Section id="workflow" title="Workflow" description="How the catalog, the API and the MCP tool name and group this workflow.">
              <div className="grid gap-4 sm:grid-cols-2">
                <DocField field="metadata.title" label="Title" type="string" required />
                <DocField field="metadata.folder" label="Folder" type="string" placeholder="Compute" />
              </div>
              <DocField field="metadata.description" label="Description" type="string" multiline />
              <div className="grid gap-4 sm:grid-cols-3">
                <DocField field="metadata.owner" label="Owner" type="string" required suggestions={teams} description="The team that may edit and publish it" />
                <DocField field="metadata.persona" label="Persona" type="string" suggestions={['ops', 'dev', 'data', 'security']} />
                <ListField field="metadata.tags" label="Tags" placeholder="vsphere, ops" />
              </div>
              <div className="grid gap-4 sm:grid-cols-3">
                <div>
                  <FieldLabel label="Name" />
                  <p className="mt-1 font-mono text-footnote text-secondary">{doc.metadata?.name ?? '—'}</p>
                </div>
                <div>
                  <FieldLabel label="Noun · verb" description="They name the endpoint and the MCP tool, so they are set when the workflow is created" />
                  <p className="mt-1 font-mono text-footnote text-secondary">{`${doc.metadata?.noun ?? '—'} · ${doc.metadata?.verb ?? '—'}`}</p>
                </div>
                <div>
                  <FieldLabel label="Version" description="Set when a version is published" />
                  <p className="mt-1 flex items-center gap-2 font-mono text-footnote text-secondary">
                    {doc.metadata?.version ?? '—'}
                    {!isNew && detail?.publishedVersion && <StateTag status="published" size="sm">{`published ${detail.publishedVersion}`}</StateTag>}
                  </p>
                </div>
              </div>
              {isNew && (
                <div className="grid gap-4 sm:grid-cols-2">
                  <DocField field="metadata.noun" label="Noun" type="string" mono />
                  <DocField field="metadata.verb" label="Verb" type="string" mono />
                </div>
              )}
              <ProblemList problems={docProblems.filter((p) => p.field === 'metadata.name' || p.field === 'metadata.version' || (!isNew && p.field === 'metadata.noun'))} />
            </Section>

            <Section id="api" title="API" description="Every workflow is an endpoint; a request starts a job and answers with its id.">
              <div className="grid gap-4 sm:grid-cols-[140px_1fr]">
                <DocField field="api.method" label="Method" type="enum" options={['POST', 'PUT', 'PATCH', 'DELETE', 'GET'].map((m) => ({ value: m, label: m }))} />
                <DocField field="api.path" label="Path" type="string" mono placeholder={apiTriggerLabel(doc).path} />
              </div>
              <div className="grid gap-4 sm:grid-cols-2">
                <DocField field="api.response.status" label="Response status" type="number" placeholder="202" />
                <DocField field="api.preview" label="Preview endpoint" type="boolean" description="Also serve :preview, which shows what a run would do without running it" />
              </div>
              <DocField field="api.response.body" label="Response body" type="object" description="The JSON Schema of a succeeded job's output: the keys of the workflow outputs. OpenAPI publishes it as the operation's output" />
              <div className="rounded-md border border-stroke-light p-4" data-testid="api-deprecation">
                <p className="text-footnote font-medium text-primary">Deprecation</p>
                <p className="mt-0.5 text-caption text-tertiary">The operation keeps answering until the sunset date, with Deprecation, Sunset and Link headers; the catalog and the OpenAPI document mark it deprecated.</p>
                <div className="mt-3 grid gap-4 sm:grid-cols-2">
                  <DocField field="api.deprecated.since" label="Deprecated since" type="string" mono placeholder="2026-11-01" description="YYYY-MM-DD or an ISO date-time" />
                  <DocField field="api.deprecated.sunset" label="Sunset" type="string" mono placeholder="2027-05-01" description="When the operation goes away; after since" />
                  <DocField field="api.deprecated.replacement" label="Replacement" type="string" mono placeholder="/api/v2/vms/{vmId}:restart" description="The successor's path under /api/v<n>/, or a URL" />
                  <DocField field="api.deprecated.message" label="Message" type="string" placeholder="Use the v2 restart" />
                </div>
              </div>
              <div className="rounded-md border border-stroke-light p-4">
                <p className="text-footnote font-medium text-primary">Batch runs</p>
                <p className="mt-0.5 text-caption text-tertiary">One request with many inputs; the rows run in waves and the batch stops when too many fail.</p>
                <div className="mt-3 grid gap-4 sm:grid-cols-2">
                  <DocField field="api.batch.max" label="Most rows per batch" type="number" placeholder="500" />
                  <ListField field="api.batch.rollout.waves" label="Waves (% of rows)" numbers placeholder="5, 25, 100" />
                  <DocField field="api.batch.rollout.maxParallel" label="Rows in parallel" type="number" placeholder="10" />
                  <DocField field="api.batch.rollout.stopIfFailedPercent" label="Stop when failed (%)" type="number" placeholder="5" />
                </div>
              </div>
            </Section>

            <Section id="input" title="Input" description="The JSON Schema of the job input: the catalog form, the API body and the MCP tool's arguments.">
              <InputSchemaEditor />
            </Section>

            <Section id="mcp" title="MCP" description="Agents can call this workflow as a tool.">
              <DocField field="mcp.expose" label="Expose as an MCP tool" type="boolean" />
              <DocField field="mcp.description" label="Tool description" type="string" multiline description="What an agent reads to decide when to call the tool" />
            </Section>

            <Section id="policy" title="Policy" description="Who may run it, and what needs a sign-off.">
              <div className="grid gap-4 sm:grid-cols-3">
                <DocField field="policy.approval.required" label="Every run needs an approval" type="boolean" />
                <DocField field="policy.approval.group" label="Approver group" type="string" />
                <DocField field="policy.approval.timeout" label="Approval timeout" type="string" mono placeholder="4h" />
              </div>
              <ListField
                field="policy.rbac"
                label="Roles or teams that may run it"
                required
                problemsExact={['policy']}
                placeholder="ops, sre"
                description="At least one role or team: a workflow that names none does not pass the checks (policy.missing). Admins may always run it"
              />
              <DocField field="policy.change.required" label="Changes need a change record" type="boolean" description="Jobs open and close a change in the ITSM tool" />
            </Section>

            <Section id="triggers" title="Triggers" description="What starts a job besides the API.">
              <TriggersList />
              <ProblemList problems={docProblems.filter((p) => p.field?.startsWith('triggers'))} />
            </Section>

            <Section id="rollout" title="Rollout" description="How a newly published version takes over new jobs.">
              <div>
                <FieldLabel label="Strategy" />
                <Segmented<'canary' | 'all'>
                  value={strategy}
                  ariaLabel="Rollout strategy"
                  className="mt-1"
                  onChange={(v) =>
                    store.update((d) => {
                      d.rollout = { ...(d.rollout ?? {}), strategy: v }
                    })
                  }
                  options={[
                    { value: 'canary', label: 'Canary' },
                    { value: 'all', label: 'All at once' },
                  ]}
                />
              </div>
              {strategy === 'canary' && <ListField field="rollout.stages" label="Stages (% of new jobs)" numbers placeholder="10, 50, 100" />}
              <ProblemList problems={docProblems.filter((p) => p.field === 'rollout.strategy')} />
            </Section>

            <Section id="outputs" title="Outputs" description="What a finished job returns: expressions over the steps.">
              <KeyValueEditor
                value={asObject((doc.workflow?.outputs ?? {}) as JsonValue)}
                onChange={(v) =>
                  store.update((d) => {
                    if (!d.workflow) d.workflow = {}
                    if (Object.keys(v).length === 0) delete d.workflow.outputs
                    else d.workflow.outputs = v as Record<string, string>
                  }, 'settings:outputs')
                }
                addLabel="Add output"
                keyPlaceholder="output"
                valuePlaceholder="{{ $vm.name }}"
                problemsOf={(k) => problemsAt(docProblems, `workflow.outputs.${k}`)}
              />
            </Section>
          </div>
        </div>
      </div>
    </NdvContext.Provider>
  )
}

