/**
 * Try for real, beside Test step on a provider step: one real call to the provider
 * (POST /providers:try) with the draft's file, or the published one, as the Add provider wizard's
 * Try it makes it. It starts in the environment the review counts, asks first when the action
 * changes something, and for a delete whose input names a registry resource offers the registry
 * picker, so the call deletes a test resource someone chose. The answer shows in the Output pane
 * as the provider page shows it (`TryOutcome`), with Save as sample and Fix schema into the
 * provider's draft and, for a published provider, Use the real response (a change request).
 * Test step stays a dry run, and pinned data stays the designer's own mock: a call pins nothing.
 */
import { useMemo, useState } from 'react'
import { Button, ConfirmDialog, Field, Icon, Modal, OverlayBody, OverlayFooter, OverlayHeader, Select, Tag, Tooltip, duration, useToast } from '@iaf/ui'
import { resolve, type ExpressionContext, type JsonValue, type ParsedStep } from '@iaf/workflow-checks'
import { api, errorMessage } from '../../lib/api'
import { invalidate } from '../../lib/queries'
import type { Effect, OverrideRequest, Schema } from '../../lib/types'
import { useChangeRequestToast } from '../../components/ChangeRequestToast'
import { SchemaForm, defaultsFor, type SchemaValue } from '../../components/SchemaForm'
import { EffectTag } from '../../components/common'
import { TryOutcome } from '../../components/provider-admin'
import { resourceFields } from '../../components/provider-editor/state'
import { UiGlyph } from '../canvas/glyphs'
import { asObject, isExpression } from './fields'
import { actionEffect, changesSomething, fixableAnswer, tryEnvironments, tryInputSchema, tryYaml, useDraftEdit, withFixedSchema, withSample, type ProviderStepInfo, type TriedCall } from './providerStep'

/**
 * What the call's form starts with: the schema's defaults, then the step's input as it resolves
 * against the data the step sees (its test or sample input, the steps before it). A field whose
 * expression does not resolve to a value is left for the person to fill.
 */
export function prefillInput(step: ParsedStep, ctx: ExpressionContext, schema: Schema | undefined): SchemaValue {
  const out: SchemaValue = { ...defaultsFor(schema) }
  const input = asObject(step.with as JsonValue | undefined).input
  if (input === undefined || input === null || typeof input !== 'object' || Array.isArray(input)) return out
  for (const [k, v] of Object.entries(input)) {
    let value: JsonValue
    try {
      value = resolve(v, ctx)
    } catch {
      continue
    }
    if (value === null || value === '' || (typeof value === 'string' && isExpression(value))) continue
    out[k] = value
  }
  return out
}

/** The input fields of a delete that name a registry resource: the call deletes what they name, so the form picks them from the registry. */
export function testResourceFields(effect: Effect, schema: Schema | undefined): string[] {
  return effect === 'delete' ? resourceFields(schema) : []
}

/** What the confirmation says before a call that changes something. */
export function confirmMessage(effect: Effect, environment: string, targets: readonly string[]): string {
  return `This is a real ${effect} call, not a dry run${targets.length ? `: it deletes ${targets.join(', ')} in ${environment}` : ''}.`
}

/** The Try for real button, and the dialog that makes the call. */
export function TryForReal({ info, stepId, prefill, onTried, disabled }: { info: ProviderStepInfo; stepId: string; prefill: SchemaValue; onTried: (tried: TriedCall) => void; disabled?: boolean }) {
  const [open, setOpen] = useState(false)
  const yaml = tryYaml(info)
  const ready = !!yaml && !!info.action
  const why = !info.action ? 'Pick an action first' : !yaml ? (info.detailError ? `Could not load ${info.provider}` : `Loading ${info.provider}…`) : 'One real call to the provider, not a dry run'
  return (
    <>
      <Tooltip label={why}>
        <Button size="sm" variant="secondary" onClick={() => setOpen(true)} disabled={disabled || !ready} aria-label="Try for real" data-testid="ndv-try-for-real">
          <span className="flex items-center gap-1.5">
            <Icon name="play" size={13} />
            {/* the name gives way to the icon on a narrow node pane, as the step's name needs the room */}
            <span className="hidden xl:inline">Try for real</span>
          </span>
        </Button>
      </Tooltip>
      {open && yaml && <TryDialog info={info} stepId={stepId} yaml={yaml} prefill={prefill} onTried={onTried} onClose={() => setOpen(false)} />}
    </>
  )
}

function TryDialog({ info, stepId, yaml, prefill, onTried, onClose }: { info: ProviderStepInfo; stepId: string; yaml: string; prefill: SchemaValue; onTried: (tried: TriedCall) => void; onClose: () => void }) {
  return (
    <Modal onClose={onClose} width="max-w-xl" label={`Try ${info.action} for real`} raised>
      <TryForm info={info} stepId={stepId} yaml={yaml} prefill={prefill} onTried={onTried} onClose={onClose} />
    </Modal>
  )
}

function TryForm({ info, stepId, yaml, prefill, onTried, onClose }: { info: ProviderStepInfo; stepId: string; yaml: string; prefill: SchemaValue; onTried: (tried: TriedCall) => void; onClose: () => void }) {
  const toast = useToast()
  const { environments, sandbox } = tryEnvironments(info)
  const [environment, setEnvironment] = useState(environments.includes(sandbox) ? sandbox : environments[0])
  const schema = useMemo(() => tryInputSchema(info), [info])
  const [input, setInput] = useState<SchemaValue>(prefill)
  const [busy, setBusy] = useState(false)
  const [confirm, setConfirm] = useState(false)
  const effect = actionEffect(info)
  const risky = changesSomething(effect)
  const testFields = testResourceFields(effect, schema)
  const targets = testFields.map((f) => input[f]).filter((v): v is string => typeof v === 'string' && v !== '')
  const counted = info.draft ? info.draft.type === 'openapi' || info.draft.type === 'http' : false
  const hasFields = Object.keys(schema?.properties ?? {}).length > 0

  const run = async () => {
    setBusy(true)
    try {
      const result = await api.tryAction({ yaml, action: info.action, environment, input })
      if (result.ok) toast.success('It answered', `HTTP ${result.status} in ${duration(result.durationMs)}.`)
      else toast.toast({ title: 'It did not answer as expected', message: `HTTP ${result.status}`, tone: 'warning' })
      onTried({ stepId, action: info.action, environment, yaml, result })
      onClose()
    } catch (e) {
      toast.error('The call did not go through', errorMessage(e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <form
      className="flex min-h-0 flex-1 flex-col"
      onSubmit={(e) => {
        e.preventDefault()
        if (risky) setConfirm(true)
        else void run()
      }}
    >
      <OverlayHeader title={`Try ${info.action} for real`} subtitle={`One real call to ${info.provider} with its credential, not a dry run: the answer is compared with the declared output.`} icon="play" />
      <OverlayBody className="space-y-4">
        <div className="flex flex-wrap items-center gap-2">
          <EffectTag effect={effect} />
          {info.draft ? (
            <Tag size="sm" tone="accent">
              draft {info.draft.version}
            </Tag>
          ) : (
            info.detail && (
              <Tag size="sm" outline>
                published {info.detail.version}
              </Tag>
            )
          )}
          {risky && (
            <Tag size="sm" tone="warning" icon="alert-triangle">
              changes something in {environment}
            </Tag>
          )}
        </div>
        <Field label="Environment" hint={counted ? (environment === sandbox ? `The review counts the tries in ${sandbox}` : `Not review evidence: the review counts only the tries in ${sandbox}`) : undefined}>
          <Select ariaLabel="Environment" value={environment} onChange={setEnvironment} mono options={environments.map((e) => ({ value: e, label: e }))} />
        </Field>
        {hasFields ? <SchemaForm schema={schema} value={input} onChange={setInput} idPrefix="ndv-try" pickers={testFields.length > 0} /> : <p className="text-caption text-tertiary">The action takes no input.</p>}
        {testFields.length > 0 && (
          <p className="flex items-start gap-1.5 text-caption text-secondary" data-testid="try-test-resource">
            <Icon name="alert-triangle" size={12} className="mt-0.5 shrink-0 text-warning" />
            Pick a test resource from the registry: the call deletes it in {environment} for real.
          </p>
        )}
      </OverlayBody>
      <OverlayFooter>
        <Button type="button" variant="ghost" onClick={onClose} disabled={busy}>
          Cancel
        </Button>
        <Button type="submit" variant="primary" icon="play" loading={busy}>
          Try it
        </Button>
      </OverlayFooter>
      {confirm && (
        <ConfirmDialog
          title={`Run ${info.action} in ${environment}?`}
          tone={effect === 'delete' ? 'danger' : 'warning'}
          icon="alert-triangle"
          message={confirmMessage(effect, environment, targets)}
          confirmLabel="Run it"
          onConfirm={async () => {
            await run()
          }}
          onClose={() => setConfirm(false)}
        />
      )}
    </form>
  )
}

/**
 * The answer of a real call in the Output pane: the provider page's outcome, with Save as sample
 * and Fix schema into the provider's draft (started from the published provider when it has
 * none) and, for a published provider, Use the real response.
 */
export function TriedOutput({ info, tried, onClose }: { info: ProviderStepInfo; tried: TriedCall; onClose: () => void }) {
  const toast = useToast()
  const notify = useChangeRequestToast()
  const edit = useDraftEdit(info)
  const [done, setDone] = useState<{ sample: boolean; schema: boolean }>({ sample: false, schema: false })
  const [fixing, setFixing] = useState(false)
  const { result, action } = tried

  // "Use the real response": the answer's schema as the published action's override, through a change request
  const adoptRealResponse = async (override: OverrideRequest) => {
    setFixing(true)
    try {
      const accepted = await api.overrideAction(info.provider, override)
      invalidate('provider')
      notify(accepted, `The override of ${override.action}, from the real answer,`)
    } catch (e) {
      toast.error('Could not record the override', errorMessage(e))
    } finally {
      setFixing(false)
    }
  }

  return (
    <div className="space-y-2" data-testid="ndv-tried">
      <div className="flex items-center gap-2 rounded-sm bg-surface px-2 py-1.5 text-caption text-secondary">
        <UiGlyph name="flask" size={12} />
        <span className="min-w-0 flex-1">
          A real call of {action} in <span className="font-mono">{tried.environment}</span>, not a dry run
        </span>
        <button type="button" onClick={onClose} className="font-medium text-accent hover:underline">
          Back to the test output
        </button>
      </div>
      <TryOutcome result={result} action={action} fixing={fixing} onUseRealResponse={info.published ? (o) => void adoptRealResponse(o) : undefined}>
        <div className="flex flex-wrap items-center justify-end gap-2 border-t border-stroke-light pt-3">
          <span className="mr-auto text-caption text-tertiary">Into the provider’s draft; the step’s pinned data stays as it is.</span>
          <Button
            size="sm"
            icon={done.sample ? 'check' : 'document'}
            disabled={done.sample}
            title="Dry runs of the action answer with this"
            onClick={() => {
              if (edit((d) => withSample(d, tried), 'Could not save the sample')) {
                setDone((s) => ({ ...s, sample: true }))
                toast.success('Sample saved', `Dry runs of ${action} answer with this.`)
              }
            }}
          >
            {done.sample ? 'Sample saved' : 'Save as sample'}
          </Button>
          <Button
            size="sm"
            icon={done.schema ? 'check' : 'edit'}
            disabled={done.schema || !fixableAnswer(result.response)}
            title="The action's output follows the shape of this answer"
            onClick={() => {
              if (edit((d) => withFixedSchema(d, tried), 'Could not fix the schema')) {
                setDone((s) => ({ ...s, schema: true }))
                toast.success('Schema fixed', `${action}'s output now follows the real answer.`)
              }
            }}
          >
            {done.schema ? 'Schema fixed' : 'Fix schema'}
          </Button>
        </div>
      </TryOutcome>
    </div>
  )
}
