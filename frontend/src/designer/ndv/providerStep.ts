/**
 * The provider behind a provider step, as the node detail view shows it: the workflow's draft of
 * it, the published provider (GET /providers/{name}), and the edits Try for real, the Settings
 * tab's shared action and the Provider tab make. Every edit goes into the provider's draft,
 * started from the published file for a provider that has none (`draftFromProvider`), never into
 * the workflow: it changes the action for every workflow once its change request merges.
 *
 * A draft whose file is written from its state (a new provider the sheet edits) takes an edit in
 * that state. A draft that holds a file (an edit's published file at the next patch, or a file a
 * try or Devin wrote: its `draftYaml`) takes it in its rows and, by lines, in that file
 * (providerFile.ts), so its YAML says what the step shows.
 */
import { useCallback, useMemo } from 'react'
import { useToast } from '@iaf/ui'
import type { JsonValue, ParsedStep } from '@iaf/workflow-checks'
import { api } from '../../lib/api'
import { useQuery } from '../../lib/queries'
import type { ChangeRequest, Effect, ProviderDetail, RegistryAction, RegistryProvider, RetryPolicy, Schema, TryResult } from '../../lib/types'
import { actionSlo, blankAction, endpointsOf, inferSchema, nextKey, parseSchema, sandboxEnvironment, stateFromProvider, withVersion, type ActionRow } from '../../components/provider-editor/state'
import { draftFromProvider, draftYaml, findProviderDraft, type ProviderDraft } from '../model/providerDrafts'
import { findProvider } from '../model/registry'
import { useDesignerSelector, useDesignerStore } from '../model/store'
import { asObject, asString } from './fields'
import { withActionFields, withOverride, type ActionField } from './providerFile'

/** What every workflow calling the action shares, as the Settings tab edits it: the wizard's fields of an action. */
export type ActionPolicy = Pick<ActionRow, 'effect' | 'retry' | 'approval' | 'plan' | 'effectDetail' | 'sloSuccessRate' | 'sloP95Ms'>

/** The provider step's provider and action, the workflow's draft of it, and the published one. */
export interface ProviderStepInfo {
  provider: string
  action: string
  draft: ProviderDraft | undefined
  /** it has a published version: the registry lists it, or the draft edits one */
  published: boolean
  registryProvider: RegistryProvider | undefined
  /** GET /providers/{name}, for a published provider */
  detail: ProviderDetail | undefined
  detailLoading: boolean
  detailError: unknown
}

/** A real call made from the node detail view: the file it was made with, and what it answered. */
export interface TriedCall {
  stepId: string
  action: string
  environment: string
  yaml: string
  result: TryResult
}

/** The provider and action a provider step calls, empty when it names none. */
export function providerCall(step: ParsedStep): { provider: string; action: string } {
  if (step.type !== 'provider') return { provider: '', action: '' }
  const w = asObject(step.with as JsonValue | undefined)
  return { provider: asString(w.provider), action: asString(w.action) }
}

/** The step's provider as the node detail view reads it; null for a step that calls no provider. */
export function useProviderStep(step: ParsedStep): ProviderStepInfo | null {
  const registry = useDesignerSelector((s) => s.registry)
  const drafts = useDesignerSelector((s) => s.providerDrafts)
  const { provider, action } = providerCall(step)
  const draft = findProviderDraft(drafts, provider)
  const registryProvider = findProvider(registry, provider)
  const published = draft ? draft.origin === 'edit' : registryProvider !== undefined
  const detail = useQuery(['provider', provider], () => api.provider(provider), { enabled: !!provider && published })
  const data = published ? detail.data : undefined
  const loading = published && detail.loading
  const error = published ? detail.error : null
  return useMemo(() => (provider ? { provider, action, draft, published, registryProvider, detail: data, detailLoading: loading, detailError: error } : null), [provider, action, draft, published, registryProvider, data, loading, error])
}

/** The provider's change request that is still open, if any: the platform takes no other change to the provider until it merges or closes. */
export function openChange(info: ProviderStepInfo): ChangeRequest | undefined {
  return info.detail?.changeRequests.find((c) => c.status === 'open')
}

function registryRow(a: RegistryAction): ActionRow {
  return blankAction({ id: a.id, title: a.title, effect: a.effect, retry: a.retry, approval: a.approval === true || a.approval === 'required', inputText: a.input ? JSON.stringify(a.input, null, 2) : '', outputText: a.output ? JSON.stringify(a.output, null, 2) : '' })
}

/** The action as the step shows it: the draft's row, else the published file's (as Edit provider reads it), else the registry's. */
export function actionRow(info: ProviderStepInfo): ActionRow | undefined {
  const own = info.draft?.actions.find((a) => a.id === info.action)
  if (own) return own
  if (info.detail) {
    const published = stateFromProvider(info.detail).actions.find((a) => a.id === info.action)
    if (published) return published
  }
  const a = info.registryProvider?.actions.find((x) => x.id === info.action)
  return a ? registryRow(a) : undefined
}

/** The provider's own SLO, which an action's own SLO falls back to: the draft's, else the published one. */
export function providerSlo(info: ProviderStepInfo): { successRate: string; p95Ms: string; channel: string } {
  if (info.draft) return info.draft.slo
  const s = info.detail?.slo
  return { successRate: s ? String(s.successRate) : '', p95Ms: s ? String(s.p95Ms) : '', channel: s?.channel ?? '' }
}

/** The other actions of the provider a plan step can be: the draft's, else the published ones. */
export function planOptions(info: ProviderStepInfo): string[] {
  const ids = info.draft ? info.draft.actions.filter((a) => a.selected).map((a) => a.id) : (info.detail?.actions ?? info.registryProvider?.actions ?? []).map((a) => a.id)
  return ids.filter((id) => id && id !== info.action)
}

/** The draft an edit goes into: the workflow's, else one started from the published provider (null until it has loaded). */
export function draftToEdit(info: ProviderStepInfo): ProviderDraft | null {
  return info.draft ?? (info.detail ? draftFromProvider(info.detail) : null)
}

/** The file a real call is made with: the draft's, else the published one. */
export function tryYaml(info: ProviderStepInfo): string | null {
  return info.draft ? draftYaml(info.draft) : (info.detail?.yaml ?? null)
}

/** The environments a call can go to, and the one it starts in: the one the review counts (dev, else the first). */
export function tryEnvironments(info: ProviderStepInfo): { environments: string[]; sandbox: string } {
  const names = info.draft ? Object.keys(endpointsOf(info.draft)) : (info.detail?.environments ?? [])
  const environments = names.length > 0 ? names : ['dev']
  return { environments, sandbox: sandboxEnvironment(names) }
}

/** The action's input schema, as the call's form asks for it. */
export function tryInputSchema(info: ProviderStepInfo): Schema | undefined {
  const own = info.draft?.actions.find((a) => a.id === info.action)
  if (own) return parseSchema(own.inputText)
  const published = info.detail?.actions.find((a) => a.id === info.action)
  if (published) return published.input
  return info.registryProvider?.actions.find((a) => a.id === info.action)?.input ?? undefined
}

/** The action's effect, which decides whether a real call asks first. */
export function actionEffect(info: ProviderStepInfo): Effect {
  return actionRow(info)?.effect ?? 'unknown'
}

/** Whether a real call of an action with this effect changes something, and asks first. */
export const changesSomething = (effect: Effect | string): boolean => effect !== 'read' && effect !== 'none'

/* ----------------------------------------------------------------------------
   Edits of the draft
   ------------------------------------------------------------------------- */

/** What the file says for an approval: required, or none for a delete (which requires one when the key is left out). */
function approvalValue(row: Pick<ActionRow, 'approval' | 'effect'>): string | undefined {
  return row.approval ? 'required' : row.effect === 'delete' ? 'none' : undefined
}

/**
 * The draft with the action's shared fields changed: in its row (added from `base` when it has
 * none) and, for a draft that holds a file, in that file. As in the wizard, making an action a
 * delete asks for an approval. Null when the change cannot be written: no row and no file, or a
 * file the action cannot be edited in by lines.
 */
export function withPolicy(draft: ProviderDraft, actionId: string, patch: Partial<ActionPolicy>, base?: ActionRow): ProviderDraft | null {
  const p: Partial<ActionPolicy> = patch.effect === 'delete' ? { ...patch, approval: true } : { ...patch }
  const own = draft.actions.find((a) => a.id === actionId)
  const from = own ?? (base ? { ...base, key: nextKey(), selected: true } : undefined)
  if (!from && draft.draftYaml === null) return null
  const row: ActionRow = { ...(from ?? blankAction({ id: actionId })), ...p }
  const actions = own ? draft.actions.map((a) => (a.id === actionId ? row : a)) : from ? [...draft.actions, row] : draft.actions
  if (draft.draftYaml === null) return { ...draft, actions }
  const fields: Partial<Record<ActionField, unknown>> = {}
  if (p.effect !== undefined) fields.effect = row.effect
  if (p.retry !== undefined) fields.retry = row.retry
  if (p.approval !== undefined) fields.approval = approvalValue(row)
  if (p.plan !== undefined) fields.plan = row.plan || undefined
  if (p.effectDetail !== undefined) fields.effectDetail = row.effectDetail.trim() || undefined
  if (p.sloSuccessRate !== undefined || p.sloP95Ms !== undefined) fields.slo = actionSlo(row)
  const file = withActionFields(draft.draftYaml, actionId, fields)
  return file === null ? null : { ...draft, actions, draftYaml: file }
}

/**
 * Why "Save as sample" cannot keep this call's answer, or null when it can. The sample is the one
 * the backend wrote into the file the call came back with (`result.yaml`): secret-looking fields,
 * the secrets it resolved and token-shaped words masked as `vault:redacted`. The live answer
 * (`result.response`) is never kept, so a call that failed (no sample), one that came back without
 * that file, or one made with a file the draft no longer has keeps nothing.
 */
export function sampleRefusal(draft: ProviderDraft | null, tried: TriedCall): string | null {
  const { result } = tried
  if (!result.ok) return 'A call that failed records no sample.'
  if (!result.yaml) return 'The call came back without the file that keeps its sample; try again.'
  if (draft && withVersion(tried.yaml, draft.version) !== draftYaml(draft)) return 'The draft changed since this call: try again to save its answer as the sample.'
  return null
}

/**
 * "Save as sample": the draft takes the file the call came back with, at the draft's version: the
 * file it was made with plus the answer as the action's sample (redacted by the backend) and
 * where it was recorded, so dry runs answer with it. A draft written from its state then holds
 * that file, as the wizard's "Use this" does. Null when the answer cannot be kept (`sampleRefusal`).
 */
export function withSample(draft: ProviderDraft, tried: TriedCall): ProviderDraft | null {
  const yaml = tried.result.yaml
  if (!yaml || sampleRefusal(draft, tried) !== null) return null
  return { ...draft, draftYaml: withVersion(yaml, draft.version) }
}

/** Whether the draft holds the file this call came back with: its answer is the draft's sample. */
export function sampleSaved(draft: ProviderDraft | undefined, tried: TriedCall): boolean {
  const yaml = tried.result.yaml
  return !!draft && !!yaml && draftYaml(draft) === withVersion(yaml, draft.version)
}

/** Whether "Fix schema" can take this answer: only the JSON object or list of a call that worked has a shape to record. */
export const fixableAnswer = (result: Pick<TryResult, 'ok' | 'response'>): boolean => result.ok && typeof result.response === 'object' && result.response !== null

/**
 * Whether "Use the real response" (POST /providers/{name}/overrides, a change request on the
 * published version) is offered for this call: only for a call made with the published file and
 * no draft of the provider in the workflow (whose change request a second one would block: Fix
 * schema writes into the draft instead), and not while another change request is open, which the
 * platform would refuse.
 */
export function realResponseOffer(info: ProviderStepInfo, tried: TriedCall): 'offered' | 'draft' | 'open' | 'none' {
  if (info.draft) return info.draft.origin === 'edit' ? 'draft' : 'none'
  if (!info.published || !info.detail || tried.yaml !== info.detail.yaml) return 'none'
  return openChange(info) ? 'open' : 'offered'
}

/** "Fix schema": the draft with the schema of the call's answer as the action's override (`spec.overrides`). */
export function withFixedSchema(draft: ProviderDraft, tried: TriedCall): ProviderDraft | null {
  const schema = inferSchema(tried.result.response)
  const overrides = { ...draft.overrides, [tried.action]: schema }
  if (draft.draftYaml === null) return { ...draft, overrides }
  const file = withOverride(draft.draftYaml, tried.action, schema)
  return file === null ? null : { ...draft, overrides, draftYaml: file }
}

/**
 * Applies an edit to the step's provider draft (started from the published provider when there is
 * none, which a toast says) and keeps it; false, with a toast saying why, when it cannot.
 */
export function useDraftEdit(info: ProviderStepInfo | null): (edit: (draft: ProviderDraft) => ProviderDraft | null, failure: string) => boolean {
  const store = useDesignerStore()
  const toast = useToast()
  return useCallback(
    (edit, failure) => {
      if (!info) return false
      const draft = draftToEdit(info)
      if (!draft) {
        toast.error(failure, info.detailError ? `The provider ${info.provider} did not load.` : `The provider ${info.provider} is still loading.`)
        return false
      }
      const next = edit(draft)
      if (!next) {
        toast.error(failure, `The provider file does not write ${info.action} where the designer can edit it; open the provider to change it there.`)
        return false
      }
      store.upsertProviderDraft(next)
      if (!info.draft) toast.toast({ title: `Started a draft of ${info.provider} ${next.version}`, message: 'Every workflow that calls it gets the change once the provider’s change request merges.', tone: 'neutral' })
      return true
    },
    [info, store, toast],
  )
}

/** Retry options, as the wizard's Actions step offers them. */
export const RETRY_OPTIONS: { value: RetryPolicy; label: string; hint: string }[] = [
  { value: 'safe', label: 'safe', hint: 'May run twice without harm' },
  { value: 'unsafe', label: 'unsafe', hint: 'Needs a person before a retry' },
]
