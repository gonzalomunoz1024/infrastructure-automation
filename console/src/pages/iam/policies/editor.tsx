import { useId, useMemo, useRef, useState, type ReactNode } from 'react'
import { Button, Checkbox, Field, Icon, IconButton, Input, Segmented, Select, cx } from '@iaf/ui'
import { DotTag, type Problem } from '@iaf/console-kit'
import { useConsole } from '../../../app/console'
import { singularOf } from '../../../lib/descriptors'
import { SERVICE_TITLES, actionCatalogOf, conditionKeyWords, isConditionKey, lineOf, partsOfAction, patternOf, patternProblem, patternWords, policyJson, readPolicyJson, statementWords, type ActionCatalog, type PolicyDoc } from '../../../lib/policies'
import type { IamStatement } from '../../../lib/types'

/*
 * The policy editor, in AWS's shape: a document of statements, each an effect, the actions it
 * speaks about (picked from the services and workflows the console's types offer, or typed), the
 * actions an allow carves out, the resources as wfrn patterns (typed, or built with the helper),
 * and conditions. The visual view and the JSON view edit the same document: a change in one is in
 * the other at once, and a JSON text that does not read leaves the visual view on the last
 * version that did. Every problem is shown where it is: under the field in the visual view, on
 * its line in the JSON view.
 */

/** The actions the console's types and workflows offer, and the console's own services. */
export function useActionCatalog(): ActionCatalog {
  const { types } = useConsole()
  return useMemo(() => actionCatalogOf(types.map((t) => ({ service: t.service, actions: t.actions, create: t.create }))), [types])
}

/** A type's name as people read it, singular: 'virtual machine'. */
export function useTypeTitle(): (service: string, type: string) => string | undefined {
  const { types } = useConsole()
  return useMemo(() => {
    const map = new Map(types.map((t) => [`${t.service}/${t.name}`, singularOf(t.title).toLowerCase()]))
    return (service: string, type: string) => map.get(`${service}/${type}`) ?? (service === '*' || service.includes('*') ? types.find((t) => t.name === type)?.title.toLowerCase() : undefined)
  }, [types])
}

export const emptyStatement = (): IamStatement => ({ effect: 'allow', actions: [], resources: [] })

/** The problems at a field and below it: `statements[0].actions` holds `statements[0].actions[1]`. */
export function problemsUnder(problems: readonly Problem[], field: string): Problem[] {
  return problems.filter((p) => p.field === field || p.field?.startsWith(`${field}[`) || p.field?.startsWith(`${field}.`))
}

function ProblemText({ problems, id }: { problems: readonly Problem[]; id?: string }) {
  if (!problems.length) return null
  return (
    <ul id={id} className="mt-1 space-y-0.5" data-problems>
      {problems.map((p, i) => (
        <li key={`${p.field}-${i}`} className="flex items-start gap-1 text-caption text-critical">
          <Icon name="alert-triangle" size={12} className="mt-0.5 shrink-0" />
          <span>{p.message}</span>
        </li>
      ))}
    </ul>
  )
}

/* ----------------------------------------------------------------------------
   Actions: chips, and a picker over the catalog
   ------------------------------------------------------------------------- */

function serviceTitle(service: string, catalogTitles: Map<string, string>): string {
  return catalogTitles.get(service) ?? SERVICE_TITLES[service] ?? service
}

/** The services as the picker names them: the console's services by their titles, the console's own after them. */
function useServiceTitles(): Map<string, string> {
  const { services } = useConsole()
  return useMemo(() => new Map(services.map((s) => [s.id, s.title])), [services])
}

export interface ActionPickerProps {
  /** for ids and names: "Actions of statement 1" */
  label: string
  value: string[]
  onChange: (next: string[]) => void
  catalog: ActionCatalog
  problems: readonly Problem[]
  /** the field the problems are named under, `statements[0].actions` */
  field: string
  idPrefix: string
  /** what an empty list says */
  emptyText?: string
}

/** The actions as chips, each removable, and a picker: a service, then its verbs ticked on or off, or an action typed. */
export function ActionPicker({ label, value, onChange, catalog, problems, field, idPrefix, emptyText = 'No action yet' }: ActionPickerProps) {
  const titles = useServiceTitles()
  const services = Object.keys(catalog)
  const [service, setService] = useState(() => (value[0] ? partsOfAction(value[0]).service : (services.find((s) => s === 'compute') ?? services[0] ?? '*')))
  const [typed, setTyped] = useState('')
  const verbs = service === '*' ? [] : (catalog[service] ?? [])
  const has = (a: string) => value.includes(a)
  const toggle = (a: string, on: boolean) => onChange(on ? [...value, a] : value.filter((x) => x !== a))
  const own = problemsUnder(problems, field).filter((p) => p.field === field)
  const typedProblem = typed && !/^[a-z0-9*-]{1,64}:[a-z0-9*-]{1,64}$/.test(typed.trim()) ? 'An action is <service>:<verb>, such as compute:restart or compute:*' : undefined
  // a row of its own, not a form: the picker sits inside the policy's form
  const addTyped = () => {
    const a = typed.trim()
    if (!a || typedProblem || value.includes(a)) return
    onChange([...value, a])
    setTyped('')
  }
  return (
    <div>
      <ul className="flex flex-wrap gap-1.5" aria-label={label}>
        {value.map((a, j) => {
          const bad = problems.filter((p) => p.field === `${field}[${j}]`)
          return (
            <li key={`${a}-${j}`} className={cx('inline-flex items-center gap-1 rounded-md border py-0.5 pl-2 pr-0.5 font-mono text-caption', bad.length ? 'border-critical bg-critical-tint/40 text-primary' : 'border-stroke-light bg-surface-secondary text-primary')} data-action={a} data-invalid={bad.length ? true : undefined}>
              {a}
              <IconButton size="sm" label={`Remove ${a}`} onClick={() => onChange(value.filter((_, k) => k !== j))}>
                <Icon name="close" size={12} />
              </IconButton>
            </li>
          )
        })}
        {value.length === 0 && <li className="text-caption text-tertiary">{emptyText}</li>}
      </ul>
      <ProblemText problems={[...own, ...value.flatMap((_, j) => problems.filter((p) => p.field === `${field}[${j}]`))]} />
      <div className="mt-3 rounded-md border border-stroke-light p-3">
        <div className="grid gap-3 sm:grid-cols-[minmax(0,14rem)_1fr]">
          <Field label="Service" htmlFor={`${idPrefix}-service`}>
            <Select ariaLabel={`Service: ${service === '*' ? 'Any service' : serviceTitle(service, titles)}`} value={service} onChange={setService} options={[...services.map((s) => ({ value: s, label: serviceTitle(s, titles), hint: s })), { value: '*', label: 'Any service', hint: '*' }]} />
          </Field>
          <fieldset className="min-w-0">
            <legend className="text-caption font-medium text-secondary">{service === '*' ? 'Every service' : `What ${serviceTitle(service, titles)} offers`}</legend>
            <div className="mt-1.5 grid grid-cols-1 gap-x-4 gap-y-1.5 min-[420px]:grid-cols-2 lg:grid-cols-3">
              <Checkbox id={`${idPrefix}-all`} checked={has(`${service}:*`)} onChange={(on) => toggle(`${service}:*`, on)} label={<span className="font-mono text-caption">{service}:*</span>} description="Every action, now and later" />
              {verbs.map((v) => (
                <Checkbox key={v} id={`${idPrefix}-${v}`} checked={has(`${service}:${v}`)} onChange={(on) => toggle(`${service}:${v}`, on)} label={<span className="font-mono text-caption">{`${service}:${v}`}</span>} />
              ))}
            </div>
          </fieldset>
        </div>
        <div
          className="mt-3 flex flex-wrap items-end gap-2"
          onKeyDown={(e) => {
            if (e.key !== 'Enter' || (e.target as HTMLElement).tagName !== 'INPUT') return
            e.preventDefault()
            addTyped()
          }}
        >
          <Field label="Or type an action" htmlFor={`${idPrefix}-typed`} hint="Wildcards: *:delete, compute:*" error={typedProblem} className="min-w-0 flex-1">
            <Input id={`${idPrefix}-typed`} mono value={typed} invalid={!!typedProblem} onChange={(e) => setTyped(e.target.value)} placeholder="service:verb" autoComplete="off" spellCheck={false} />
          </Field>
          <Button size="sm" icon="plus" disabled={!typed.trim() || !!typedProblem} onClick={addTyped}>
            Add action
          </Button>
        </div>
      </div>
    </div>
  )
}

/* ----------------------------------------------------------------------------
   Resources: wfrn patterns, typed or built
   ------------------------------------------------------------------------- */

/** Builds a wfrn pattern from a service, a type, a region, an account and a name. */
function PatternHelper({ onAdd, idPrefix }: { onAdd: (pattern: string) => void; idPrefix: string }) {
  const { services, region, account, regions } = useConsole()
  const [service, setService] = useState(services[0]?.id ?? '*')
  const [type, setType] = useState('*')
  const [where, setWhere] = useState<string>('*')
  const [inAccount, setInAccount] = useState<'this' | 'any'>('this')
  const [id, setId] = useState('*')
  const types = service === '*' ? [] : (services.find((s) => s.id === service)?.types ?? [])
  const pattern = patternOf({ partition: region.partition, service, region: where, account: inAccount === 'this' ? account.id : '*', type, id: id.trim() || '*' })
  const problem = patternProblem(pattern)
  const serviceLabel = service === '*' ? 'Any service' : (services.find((s) => s.id === service)?.title ?? service)
  const typeLabel = type === '*' ? 'Every type' : (types.find((t) => t.name === type)?.title ?? type)
  const regionLabel = where === '*' ? 'Any region' : where
  return (
    <div className="rounded-md border border-dashed border-stroke p-3" data-pattern-helper>
      <p className="text-caption font-medium text-secondary">Build a pattern</p>
      <div className="mt-2 grid grid-cols-1 gap-3 min-[480px]:grid-cols-2 lg:grid-cols-3">
        <Field label="Service" htmlFor={`${idPrefix}-svc`}>
          <Select
            ariaLabel={`Service: ${serviceLabel}`}
            value={service}
            onChange={(v) => {
              setService(v)
              setType('*')
            }}
            options={[...services.map((s) => ({ value: s.id, label: s.title })), { value: '*', label: 'Any service' }]}
          />
        </Field>
        <Field label="Type" htmlFor={`${idPrefix}-type`}>
          <Select ariaLabel={`Type: ${typeLabel}`} value={type} onChange={setType} options={[{ value: '*', label: 'Every type' }, ...types.map((t) => ({ value: t.name, label: t.title }))]} />
        </Field>
        <Field label="Region" htmlFor={`${idPrefix}-region`}>
          <Select ariaLabel={`Region: ${regionLabel}`} value={where} onChange={setWhere} options={[{ value: '*', label: 'Any region' }, ...regions.map((r) => ({ value: r.name, label: r.name }))]} />
        </Field>
        <Field label="Account" htmlFor={`${idPrefix}-account`}>
          <Segmented ariaLabel="Account" size="sm" value={inAccount} onChange={setInAccount} options={[{ value: 'this', label: account.name }, { value: 'any', label: 'Any account' }]} />
        </Field>
        <Field label="Name or id" htmlFor={`${idPrefix}-id`} hint="* for every one; web-* for those starting web-" className="min-[480px]:col-span-2">
          <Input id={`${idPrefix}-id`} mono value={id} onChange={(e) => setId(e.target.value)} autoComplete="off" spellCheck={false} />
        </Field>
      </div>
      <p className="mt-3 break-all font-mono text-caption text-primary" data-pattern-preview>
        {pattern}
      </p>
      <p className="mt-0.5 text-caption text-secondary">{problem ?? patternWords(pattern)}</p>
      <div className="mt-2 flex justify-end">
        <Button size="sm" icon="plus" disabled={!!problem} onClick={() => onAdd(pattern)}>
          Add this pattern
        </Button>
      </div>
    </div>
  )
}

function ResourceList({ value, onChange, problems, field, idPrefix, label }: { value: string[]; onChange: (next: string[]) => void; problems: readonly Problem[]; field: string; idPrefix: string; label: string }) {
  const typeTitle = useTypeTitle()
  const [helper, setHelper] = useState(value.length === 0)
  return (
    <div>
      <ul className="space-y-2" aria-label={label}>
        {value.map((r, j) => {
          const local = r.trim() ? patternProblem(r.trim()) : 'A resource is a wfrn or a wfrn pattern'
          const server = problems.filter((p) => p.field === `${field}[${j}]`).map((p) => p.message)
          const shown = local ?? server[0]
          const inputId = `${idPrefix}-${j}`
          return (
            <li key={j} className="flex items-start gap-2">
              <div className="min-w-0 flex-1">
                <label htmlFor={inputId} className="sr-only">{`${label}, row ${j + 1}`}</label>
                <Input id={inputId} mono value={r} invalid={!!shown} aria-describedby={`${inputId}-note`} onChange={(e) => onChange(value.map((x, k) => (k === j ? e.target.value : x)))} autoComplete="off" spellCheck={false} />
                <p id={`${inputId}-note`} className={cx('mt-0.5 text-caption', shown ? 'text-critical' : 'text-secondary')}>
                  {shown ?? patternWords(r.trim(), typeTitle)}
                </p>
              </div>
              <IconButton label={`Remove ${label.toLowerCase()} row ${j + 1}`} onClick={() => onChange(value.filter((_, k) => k !== j))}>
                <Icon name="trash" size={15} />
              </IconButton>
            </li>
          )
        })}
        {value.length === 0 && <li className="text-caption text-tertiary">No resource yet: a statement speaks about at least one.</li>}
      </ul>
      <ProblemText problems={problems.filter((p) => p.field === field)} />
      <div className="mt-2 flex flex-wrap gap-2">
        <Button size="sm" variant="ghost" icon="plus" onClick={() => onChange([...value, ''])}>
          Type a pattern
        </Button>
        <Button size="sm" variant="ghost" icon={helper ? 'chevron-up' : 'chevron-down'} aria-expanded={helper} onClick={() => setHelper((h) => !h)}>
          {helper ? 'Hide the pattern helper' : 'Build a pattern'}
        </Button>
      </div>
      {helper && (
        <div className="mt-2">
          <PatternHelper idPrefix={`${idPrefix}-helper`} onAdd={(p) => onChange(value.includes(p) ? value : [...value.filter((x) => x.trim()), p])} />
        </div>
      )}
    </div>
  )
}

/* ----------------------------------------------------------------------------
   Conditions
   ------------------------------------------------------------------------- */

const KEY_CHOICES: { value: string; label: string }[] = [
  { value: 'tag.', label: 'A tag of the resource' },
  { value: 'region', label: 'The region' },
  { value: 'account', label: 'The account' },
  { value: 'service', label: 'The service' },
  { value: 'type', label: 'The resource type' },
  { value: 'requester.user', label: 'The person' },
  { value: 'requester.email', label: 'The person’s email' },
  { value: 'requester.group', label: 'One of the person’s groups' },
  { value: 'context.', label: 'A value the request carries' },
]

function kindOfKey(key: string): string {
  if (key.startsWith('tag.')) return 'tag.'
  if (key.startsWith('context.')) return 'context.'
  return key
}

function valueText(v: unknown): string {
  return Array.isArray(v) ? v.map(String).join(', ') : v === undefined || v === null ? '' : String(v)
}

/** 'prod' stays a word; 'dev, test' becomes a list, any of which matches. */
function valueOf(text: string): string | string[] {
  const parts = text
    .split(',')
    .map((s) => s.trim())
    .filter(Boolean)
  return parts.length > 1 ? parts : (parts[0] ?? '')
}

function ConditionRows({ value, onChange, problems, field, idPrefix }: { value: Record<string, unknown>; onChange: (next: Record<string, unknown>) => void; problems: readonly Problem[]; field: string; idPrefix: string }) {
  const entries = Object.entries(value)
  const set = (i: number, key: string, v: unknown) => onChange(Object.fromEntries(entries.map(([k, x], j) => (j === i ? [key, v] : [k, x]))))
  return (
    <div>
      <ul className="space-y-3" aria-label="Conditions">
        {entries.map(([key, v], i) => {
          const kind = kindOfKey(key)
          const name = kind.endsWith('.') ? key.slice(kind.length) : ''
          const mine = problems.filter((p) => p.field === `${field}.${key}`)
          const choice = KEY_CHOICES.find((c) => c.value === kind)
          return (
            <li key={i} className="rounded-md border border-stroke-light p-3" data-condition={key}>
              <div className="grid grid-cols-1 gap-3 min-[480px]:grid-cols-2 lg:grid-cols-[minmax(0,1.2fr)_minmax(0,1fr)_minmax(0,1.2fr)_auto] lg:items-end">
                <Field label="When" htmlFor={`${idPrefix}-${i}-key`}>
                  <Select ariaLabel={`When: ${choice?.label ?? key}`} value={choice ? kind : key} onChange={(k) => set(i, k.endsWith('.') ? `${k}${name || 'environment'}` : k, v)} options={KEY_CHOICES} />
                </Field>
                {kind.endsWith('.') ? (
                  <Field label={kind === 'tag.' ? 'Tag name' : 'Value name'} htmlFor={`${idPrefix}-${i}-name`}>
                    <Input id={`${idPrefix}-${i}-name`} mono value={name} onChange={(e) => set(i, `${kind}${e.target.value.trim()}`, v)} autoComplete="off" spellCheck={false} />
                  </Field>
                ) : (
                  <span className="hidden lg:block" />
                )}
                <Field label="Is any of" hint="One value, or several with commas" htmlFor={`${idPrefix}-${i}-value`}>
                  <Input id={`${idPrefix}-${i}-value`} mono value={valueText(v)} onChange={(e) => set(i, key, valueOf(e.target.value))} autoComplete="off" spellCheck={false} />
                </Field>
                <IconButton label={`Remove condition ${i + 1}`} onClick={() => onChange(Object.fromEntries(entries.filter((_, j) => j !== i)))}>
                  <Icon name="trash" size={15} />
                </IconButton>
              </div>
              <p className="mt-1 text-caption text-secondary">{isConditionKey(key) ? `Only when ${conditionKeyWords(key)} is ${valueText(v) || '…'}.` : 'Choose what the condition reads.'}</p>
              <ProblemText problems={mine} />
            </li>
          )
        })}
      </ul>
      {entries.length === 0 && <p className="text-caption text-tertiary">No condition: the statement applies whatever the request.</p>}
      <Button
        className="mt-2"
        size="sm"
        variant="ghost"
        icon="plus"
        onClick={() => {
          let key = 'tag.environment'
          for (let n = 2; key in value; n++) key = `tag.name-${n}`
          onChange({ ...value, [key]: '' })
        }}
      >
        Add a condition
      </Button>
    </div>
  )
}

/* ----------------------------------------------------------------------------
   One statement
   ------------------------------------------------------------------------- */

function StatementCard({ index, statement, onChange, onRemove, problems, catalog, idPrefix }: { index: number; statement: IamStatement; onChange: (next: IamStatement) => void; onRemove?: () => void; problems: readonly Problem[]; catalog: ActionCatalog; idPrefix: string }) {
  const typeTitle = useTypeTitle()
  const at = `statements[${index}]`
  const n = index + 1
  const [except, setExcept] = useState(!!statement.notActions?.length)
  const mine = problemsUnder(problems, at)
  const titleId = `${idPrefix}-title`
  return (
    <section aria-labelledby={titleId} className={cx('rounded-lg border p-4', mine.length ? 'border-critical/50' : 'border-stroke-light')} data-statement={index}>
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0">
          <h3 id={titleId} className="flex flex-wrap items-center gap-2 text-sm font-semibold text-primary">
            Statement {n}
            {statement.sid && <span className="font-mono text-caption font-normal text-secondary">{statement.sid}</span>}
            <DotTag size="sm" tone={statement.effect === 'deny' ? 'critical' : 'healthy'}>
              {statement.effect}
            </DotTag>
            {mine.length > 0 && (
              <DotTag size="sm" tone="critical">
                {mine.length === 1 ? '1 problem' : `${mine.length} problems`}
              </DotTag>
            )}
          </h3>
          <p className="mt-1 break-words text-caption text-secondary" data-statement-words>
            {statementWords(statement, typeTitle)}
          </p>
        </div>
        {onRemove && (
          <Button size="sm" variant="ghost" icon="trash" onClick={onRemove}>
            Remove statement {n}
          </Button>
        )}
      </div>

      <div className="mt-4 grid gap-4 sm:grid-cols-2">
        <Field label="Statement id (sid)" hint="Optional; a refusal names it" htmlFor={`${idPrefix}-sid`} error={problems.find((p) => p.field === `${at}.sid`)?.message}>
          <Input id={`${idPrefix}-sid`} mono value={statement.sid ?? ''} invalid={problems.some((p) => p.field === `${at}.sid`)} onChange={(e) => onChange({ ...statement, sid: e.target.value || undefined })} autoComplete="off" spellCheck={false} />
        </Field>
        <Field label="Effect" htmlFor={`${idPrefix}-effect`} error={problems.find((p) => p.field === `${at}.effect`)?.message}>
          <Segmented
            ariaLabel={`Effect of statement ${n}`}
            value={statement.effect === 'deny' ? 'deny' : 'allow'}
            onChange={(effect) => {
              const next: IamStatement = { ...statement, effect }
              if (effect === 'deny') {
                delete next.notActions
                setExcept(false)
              }
              onChange(next)
            }}
            options={[
              { value: 'allow', label: 'Allow' },
              { value: 'deny', label: 'Deny' },
            ]}
          />
        </Field>
      </div>

      <fieldset className="mt-5">
        <legend className="text-sm font-medium text-primary">Actions</legend>
        <p className="mb-2 text-caption text-secondary">What the statement speaks about: a workflow’s action, a read, or a wildcard.</p>
        <ActionPicker label={`Actions of statement ${n}`} value={statement.actions} onChange={(actions) => onChange({ ...statement, actions })} catalog={catalog} problems={problems} field={`${at}.actions`} idPrefix={`${idPrefix}-actions`} />
      </fieldset>

      {statement.effect === 'allow' && (
        <fieldset className="mt-5">
          <legend className="text-sm font-medium text-primary">Except</legend>
          <Checkbox
            id={`${idPrefix}-except`}
            checked={except}
            onChange={(on) => {
              setExcept(on)
              if (!on) {
                const next = { ...statement }
                delete next.notActions
                onChange(next)
              }
            }}
            label="Carve some actions out of this allow"
            description="Everything the actions name but these, such as compute:* except compute:delete"
          />
          {except && (
            <div className="mt-2">
              <ActionPicker label={`Actions statement ${n} leaves out`} value={statement.notActions ?? []} onChange={(notActions) => onChange({ ...statement, notActions })} catalog={catalog} problems={problems} field={`${at}.notActions`} idPrefix={`${idPrefix}-not`} emptyText="Nothing carved out yet" />
            </div>
          )}
        </fieldset>
      )}

      <fieldset className="mt-5">
        <legend className="text-sm font-medium text-primary">Resources</legend>
        <p className="mb-2 text-caption text-secondary">wfrn patterns: * matches anything, ? one character, across : and /.</p>
        <ResourceList label={`Resources of statement ${n}`} value={statement.resources} onChange={(resources) => onChange({ ...statement, resources })} problems={problems} field={`${at}.resources`} idPrefix={`${idPrefix}-res`} />
      </fieldset>

      <fieldset className="mt-5">
        <legend className="text-sm font-medium text-primary">Conditions</legend>
        <p className="mb-2 text-caption text-secondary">Every condition must hold. A tag is read from the resource the request is about.</p>
        <ConditionRows
          value={statement.conditions ?? {}}
          onChange={(conditions) => {
            const next: IamStatement = { ...statement, conditions }
            if (!Object.keys(conditions).length) delete next.conditions
            onChange(next)
          }}
          problems={problems}
          field={`${at}.conditions`}
          idPrefix={`${idPrefix}-cond`}
        />
      </fieldset>
    </section>
  )
}

/* ----------------------------------------------------------------------------
   The editor: both views over one document
   ------------------------------------------------------------------------- */

export type EditorView = 'visual' | 'json'

export interface PolicyEditorProps {
  doc: PolicyDoc
  onChange: (doc: PolicyDoc) => void
  /** every problem of the document, by field: the editor's own checks and the server's */
  problems: Problem[]
  catalog: ActionCatalog
  idPrefix?: string
  /** the thing being written, in words: "policy", "guardrail" */
  noun?: string
  /** the fields of the document's header (name, description) are shown */
  header?: boolean
  /** extra fields under the name, a guardrail's accounts */
  extra?: ReactNode
  /** told when the JSON text stops or starts reading, so a save can wait */
  onJsonState?: (readable: boolean) => void
}

export function PolicyEditor({ doc, onChange, problems, catalog, idPrefix: given, noun = 'policy', header = true, extra, onJsonState }: PolicyEditorProps) {
  const auto = useId().replace(/:/g, '')
  const idPrefix = given ?? `pe${auto}`
  const [view, setView] = useState<EditorView>('visual')
  // the JSON text, and the document it was last read from or written for
  const [json, setJson] = useState(() => ({ text: policyJson(doc), doc, problems: [] as Problem[], syntaxLine: undefined as number | undefined }))
  if (json.doc !== doc) setJson({ text: policyJson(doc), doc, problems: [], syntaxLine: undefined })
  const textRef = useRef<HTMLTextAreaElement>(null)
  const read = useMemo(() => readPolicyJson(json.text), [json.text])
  const jsonProblems = json.problems
  const visualBlocked = jsonProblems.length > 0

  const onText = (text: string) => {
    const r = readPolicyJson(text)
    if (r.doc) {
      setJson({ text, doc: r.doc, problems: [], syntaxLine: undefined })
      onJsonState?.(true)
      onChange(r.doc)
    } else {
      setJson((j) => ({ ...j, text, problems: r.problems, syntaxLine: r.syntax?.line }))
      onJsonState?.(false)
    }
  }

  const goToLine = (line: number | undefined) => {
    const ta = textRef.current
    if (!ta || !line) return
    const lines = json.text.split('\n')
    const start = lines.slice(0, line - 1).reduce((n, l) => n + l.length + 1, 0)
    ta.focus()
    ta.setSelectionRange(start, start + (lines[line - 1]?.length ?? 0))
    const lh = parseFloat(getComputedStyle(ta).lineHeight) || 18
    ta.scrollTop = Math.max(0, (line - 3) * lh)
  }

  const setStatement = (i: number, s: IamStatement) => onChange({ ...doc, statements: doc.statements.map((x, j) => (j === i ? s : x)) })
  const all = visualBlocked ? jsonProblems : problems
  const count = all.length
  const nameProblem = problems.find((p) => p.field === 'name')?.message
  const lineCount = json.text.split('\n').length

  return (
    <div className="space-y-6" data-policy-editor>
      {header && (
        <div className="grid gap-4 sm:grid-cols-2">
          <Field label="Name" required hint="2 to 64 lowercase letters, digits or -" htmlFor={`${idPrefix}-name`} error={nameProblem}>
            <Input id={`${idPrefix}-name`} data-autofocus mono value={doc.name} invalid={!!nameProblem} onChange={(e) => onChange({ ...doc, name: e.target.value })} autoComplete="off" spellCheck={false} />
          </Field>
          <Field label="Description" hint="What it is for, in a sentence" htmlFor={`${idPrefix}-description`}>
            <Input id={`${idPrefix}-description`} value={doc.description} onChange={(e) => onChange({ ...doc, description: e.target.value })} />
          </Field>
        </div>
      )}
      {extra}
      <div className="flex flex-wrap items-center justify-between gap-3">
        <Segmented<EditorView> ariaLabel={`How to edit the ${noun}`} value={view} onChange={setView} options={[{ value: 'visual', label: 'Visual', icon: 'grid' }, { value: 'json', label: 'JSON', icon: 'terminal' }]} />
        <p role="status" aria-live="polite" className={cx('text-caption', count ? 'text-critical' : 'text-healthy')} data-problem-count={count}>
          {count === 0 ? `The ${noun} reads well` : count === 1 ? '1 problem to fix' : `${count} problems to fix`}
        </p>
      </div>

      {view === 'visual' ? (
        <div className="space-y-4">
          {visualBlocked && (
            <p role="alert" className="rounded-md bg-warning-tint px-3 py-2 text-sm text-primary">
              The JSON view does not read{json.syntaxLine ? ` (line ${json.syntaxLine})` : ''}, so this shows the last version that did. Fix it there, or change something here to start again from this one.
            </p>
          )}
          <ProblemText problems={problems.filter((p) => p.field === 'statements' || p.field === '' || p.field === undefined)} />
          {doc.statements.map((s, i) => (
            <StatementCard key={i} index={i} statement={s} onChange={(next) => setStatement(i, next)} onRemove={doc.statements.length > 1 ? () => onChange({ ...doc, statements: doc.statements.filter((_, j) => j !== i) }) : undefined} problems={problems} catalog={catalog} idPrefix={`${idPrefix}-s${i}`} />
          ))}
          <Button icon="plus" onClick={() => onChange({ ...doc, statements: [...doc.statements, emptyStatement()] })}>
            Add a statement
          </Button>
        </div>
      ) : (
        <div className="space-y-3">
          <div className="flex min-w-0 rounded-md border border-stroke-light" data-json-editor>
            <div aria-hidden="true" className="select-none border-r border-stroke-light bg-surface-secondary px-2 py-2 text-right font-mono text-caption leading-relaxed text-tertiary">
              {Array.from({ length: lineCount }, (_, k) => {
                const line = k + 1
                const flagged = line === json.syntaxLine || all.some((p) => lineOf(read.spots, p.field) === line)
                return (
                  <div key={line} className={cx(flagged && 'font-semibold text-critical')}>
                    {line}
                  </div>
                )
              })}
            </div>
            <label htmlFor={`${idPrefix}-json`} className="sr-only">
              {`The ${noun} as JSON`}
            </label>
            <textarea
              ref={textRef}
              id={`${idPrefix}-json`}
              rows={Math.min(40, Math.max(12, lineCount + 1))}
              value={json.text}
              onChange={(e) => onText(e.target.value)}
              spellCheck={false}
              autoComplete="off"
              wrap="off"
              aria-invalid={count > 0 || undefined}
              aria-describedby={`${idPrefix}-json-problems`}
              className="min-w-0 flex-1 resize-y overflow-x-auto bg-surface px-3 py-2 font-mono text-caption leading-relaxed text-primary outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-accent"
            />
          </div>
          <div id={`${idPrefix}-json-problems`}>
            {count > 0 ? (
              <ul className="space-y-1" aria-label="Problems in the JSON">
                {all.map((p, i) => {
                  const line = p.code === 'json' ? json.syntaxLine : lineOf(read.spots, p.field)
                  return (
                    <li key={`${p.field}-${i}`} className="flex flex-wrap items-start gap-2 text-caption" data-json-problem={line}>
                      <Icon name="alert-triangle" size={12} className="mt-0.5 shrink-0 text-critical" />
                      <span className="min-w-0 flex-1 text-primary">
                        {line && p.code !== 'json' ? <span className="font-semibold">Line {line}: </span> : null}
                        {p.message}
                        {p.field && p.code !== 'json' ? <span className="ml-1 font-mono text-tertiary">({p.field})</span> : null}
                      </span>
                      {line ? (
                        <Button size="sm" variant="ghost" onClick={() => goToLine(line)}>
                          Show line {line}
                        </Button>
                      ) : null}
                    </li>
                  )
                })}
              </ul>
            ) : (
              <p className="text-caption text-secondary">Edits here show in the visual view at once.</p>
            )}
          </div>
        </div>
      )}
    </div>
  )
}
