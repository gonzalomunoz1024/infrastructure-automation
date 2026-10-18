import { useMemo, useState } from 'react'
import { Button, Card, CodeBlock, ConfirmDialog, EmptyState, Field, Icon, JsonView, Select, Tag, duration, useToast } from '@iaf/ui'
import { api, errorMessage } from '../../lib/api'
import { SchemaForm, defaultsFor, type SchemaValue } from '../SchemaForm'
import { EffectTag, Mono, When } from '../common'
import { endpointsOf, inferSchema, parseSchema, resourceFields, sandboxEnvironment, type StepProps } from './state'

/* ---- 4 · Try it ---------------------------------------------------------- */

/** `initialAction`: the action it opens on, when it is one of the selected ones (the designer's Provider sheet passes the one picked to use). */
export function TryStep({ state, update, yaml, initialAction }: StepProps & { yaml: string; initialAction?: string }) {
  const toast = useToast()
  const selected = state.actions.filter((a) => a.selected && a.id)
  const envs = Object.keys(endpointsOf(state))
  const [actionId, setActionId] = useState(() => (initialAction && selected.some((a) => a.id === initialAction) ? initialAction : (selected[0]?.id ?? '')))
  const action = selected.find((a) => a.id === actionId) ?? selected[0]
  // the review counts the tries in the sandbox (dev), so a try starts there
  const sandbox = sandboxEnvironment(envs)
  const [environment, setEnvironment] = useState(sandbox)
  const inputText = action?.inputText
  const schema = useMemo(() => (inputText === undefined ? undefined : parseSchema(inputText)), [inputText])
  // the review judges tries for the providers the platform calls over HTTP
  const counted = state.type === 'openapi' || state.type === 'http'
  const [input, setInput] = useState<SchemaValue>(() => defaultsFor(schema))
  const [busy, setBusy] = useState(false)
  const [confirm, setConfirm] = useState(false)
  const result = action ? state.tries[action.id] : undefined

  const pick = (id: string) => {
    setActionId(id)
    setInput(defaultsFor(parseSchema(selected.find((a) => a.id === id)?.inputText ?? '')))
  }

  const run = async () => {
    if (!action) return
    setBusy(true)
    try {
      const r = await api.tryAction({ yaml, action: action.id, environment, input })
      update({ tries: { ...state.tries, [action.id]: r } })
      if (r.ok) toast.success('It answered', `HTTP ${r.status} in ${duration(r.durationMs)}.`)
      else toast.toast({ title: 'It did not answer as expected', message: `HTTP ${r.status}`, tone: 'warning' })
    } catch (e) {
      toast.error('The call did not go through', errorMessage(e))
    } finally {
      setBusy(false)
    }
  }

  if (selected.length === 0) {
    return (
      <Card flush>
        <EmptyState icon="play" title="Nothing to try" message="Select at least one action first." />
      </Card>
    )
  }

  const risky = !!action && action.effect !== 'read'
  // a destructive action whose input names a registry resource: the developer picks a test one
  const testFields = action?.effect === 'delete' ? resourceFields(schema) : []
  const pickTestResource = testFields.length > 0
  const targets = testFields.map((f) => input[f]).filter((v): v is string => typeof v === 'string' && v !== '')
  const offSandbox = counted && !!result?.environment && result.environment !== sandbox
  const recorded = action ? action.id in state.samples : false
  const fixed = action ? action.id in state.overrides : false
  const used = !!result?.yaml && state.draftYaml === result.yaml

  return (
    <div className="grid grid-cols-1 gap-5 lg:grid-cols-2">
      <Card title="One real call" subtitle="Optional, and worth it: the answer becomes the sample dry runs use, and it proves the schema.">
        <div className="space-y-4">
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
            <Field label="Action">
              <Select ariaLabel="Action" value={action?.id ?? ''} onChange={pick} mono options={selected.map((a) => ({ value: a.id, label: a.id, hint: `${a.title || a.id} · ${a.effect}` }))} />
            </Field>
            <Field label="Environment" hint={counted ? (environment === sandbox ? 'The review counts the tries in ' + sandbox : 'Not review evidence: the review counts only the tries in ' + sandbox) : undefined}>
              <Select ariaLabel="Environment" value={environment} onChange={setEnvironment} mono options={(envs.length ? envs : ['dev']).map((e) => ({ value: e, label: e }))} />
            </Field>
          </div>
          {action && (
            <div className="flex flex-wrap items-center gap-2">
              <EffectTag effect={action.effect} />
              {risky && (
                <Tag size="sm" tone="warning" icon="alert-triangle">
                  changes something in {environment}
                </Tag>
              )}
            </div>
          )}
          {/* a destructive try deletes something real: its resource comes from the registry, like the catalog's run form */}
          <SchemaForm schema={schema} value={input} onChange={setInput} idPrefix="wtry" pickers={pickTestResource} />
          {pickTestResource && (
            <p className="flex items-start gap-1.5 text-caption text-secondary" data-testid="try-test-resource">
              <Icon name="alert-triangle" size={12} className="mt-0.5 shrink-0 text-warning" />
              Pick a test resource from the registry: the try deletes it in {environment} for real.
            </p>
          )}
          <div className="flex justify-end">
            <Button variant="primary" icon="play" loading={busy} disabled={!action} onClick={() => (risky ? setConfirm(true) : void run())}>
              Try it
            </Button>
          </div>
        </div>
      </Card>

      <Card title="Answer" subtitle={result ? undefined : 'The real response shows here, compared with the declared output.'}>
        {!result || !action ? (
          <EmptyState compact icon="pulse" title="Not tried yet" message="Nothing is recorded until you try." />
        ) : (
          <div className="animate-fade-in space-y-3">
            <div className="flex flex-wrap items-center gap-2">
              <Tag tone={result.ok ? 'healthy' : 'critical'} dot>
                {result.ok ? 'OK' : 'Failed'}
              </Tag>
              <Mono className="text-primary">HTTP {result.status}</Mono>
              <span className="text-caption tabular-nums text-tertiary">{duration(result.durationMs)}</span>
              {result.verified !== undefined && (
                <Tag size="sm" tone={result.verified ? 'healthy' : 'warning'}>
                  {result.verified ? 'verified afterwards' : 'not verified'}
                </Tag>
              )}
              {result.mismatches.length > 0 ? (
                <Tag size="sm" tone="warning" outline>
                  {result.mismatches.length} field{result.mismatches.length === 1 ? '' : 's'} differ from the schema
                </Tag>
              ) : (
                <Tag size="sm" tone="healthy" outline>
                  matches the schema
                </Tag>
              )}
            </div>
            <JsonView value={result.response} name="response" defaultExpanded={2} maxHeight="max-h-72" />
            {result.mismatches.length > 0 && (
              <ul className="space-y-1 text-caption">
                {result.mismatches.map((m) => (
                  <li key={m.field} className="flex flex-wrap gap-2">
                    <Mono className="text-primary">{m.field}</Mono>
                    <span className="text-tertiary">declared</span>
                    <Mono>{JSON.stringify(m.expected)}</Mono>
                    <span className="text-tertiary">got</span>
                    <Mono className="text-primary">{JSON.stringify(m.actual)}</Mono>
                  </li>
                ))}
              </ul>
            )}
            {(result.environment || result.at) && (
              <p className="flex flex-wrap items-center gap-1 text-caption text-secondary" data-testid="try-evidence">
                <Icon name={offSandbox ? 'info' : 'check'} size={12} className={offSandbox ? 'text-warning' : 'text-healthy'} />
                {offSandbox ? 'Kept' : 'Kept as review evidence'}
                {result.environment ? ` · tried in ${result.environment}` : ''}
                {result.at && (
                  <>
                    {' · '}
                    <When at={result.at} />
                  </>
                )}
                {offSandbox ? `. The review counts only the tries in ${sandbox}.` : ". The review's tried check counts it."}
              </p>
            )}
            {result.yaml && (
              <details className="rounded-md border border-stroke-light" data-testid="try-yaml">
                <summary className="cursor-pointer px-3 py-2 text-sm text-primary">The file with this answer as {action.id}'s sample</summary>
                <CodeBlock code={result.yaml} language="yaml" maxHeight="max-h-72" />
              </details>
            )}
            <div className="flex flex-wrap items-center justify-end gap-2 border-t border-stroke-light pt-3">
              {result.yaml && (
                <Button
                  variant="primary"
                  icon={used ? 'check' : 'document'}
                  disabled={used}
                  title="The backend's file: the response saved as the sample, with where it was recorded"
                  onClick={() => {
                    update({ draftYaml: result.yaml ?? null })
                    toast.success('Draft replaced', `The file now carries ${action.id}'s answer as its sample.`)
                  }}
                >
                  {used ? 'Using this file' : 'Use this'}
                </Button>
              )}
              {!state.editing && (
                <>
                  <Button
                    icon={recorded ? 'check' : 'document'}
                    disabled={recorded}
                    onClick={() => {
                      update({ samples: { ...state.samples, [action.id]: result.response } })
                      toast.success('Sample recorded', `Dry runs of ${action.id} answer with this.`)
                    }}
                  >
                    {recorded ? 'Sample recorded' : 'Record as sample'}
                  </Button>
                  <Button
                    icon={fixed ? 'check' : 'edit'}
                    disabled={fixed || !result.response || typeof result.response !== 'object'}
                    onClick={() => {
                      update({ overrides: { ...state.overrides, [action.id]: inferSchema(result.response) } })
                      toast.success('Schema fixed', `${action.id}'s output now follows the real answer.`)
                    }}
                  >
                    {fixed ? 'Schema fixed' : 'Fix schema from response'}
                  </Button>
                </>
              )}
            </div>
          </div>
        )}
      </Card>

      {confirm && action && (
        <ConfirmDialog
          title={`Run ${action.id} in ${environment}?`}
          tone={action.effect === 'delete' ? 'danger' : 'warning'}
          icon="alert-triangle"
          message={`This is a real ${action.effect} call, not a dry run${targets.length ? `: it deletes ${targets.join(', ')} in ${environment}` : ''}.`}
          confirmLabel="Run it"
          onConfirm={async () => {
            await run()
          }}
          onClose={() => setConfirm(false)}
        />
      )}
    </div>
  )
}
