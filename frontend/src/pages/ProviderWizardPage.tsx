import { useMemo, useState } from 'react'
import { Button, Icon, PageHeader, Spinner, Stepper, useMediaQuery } from '@iaf/ui'
import type { PageProps } from '../app/router'
import { navigate, pathFor } from '../app/router'
import { api, useCurrentUser } from '../lib/api'
import { useQuery } from '../lib/queries'
import { ImportStep, type ImportKind } from './ProviderImport'
import { ErrorState } from '../components/common'
import { STEPS, STEP_NUMBER, describeProvider, draftNames, initialState, providerYaml, stateFromProvider, stepIssues, type StepId, type WizardState } from '../components/provider-editor/state'
import { TypeStep } from '../components/provider-editor/TypeStep'
import { ConnectStep } from '../components/provider-editor/ConnectStep'
import { ActionsStep } from '../components/provider-editor/ActionsStep'
import { TryStep } from '../components/provider-editor/TryStep'
import { ReviewStep } from '../components/provider-editor/ReviewStep'
import { DraftModal } from '../components/provider-editor/DraftModal'

/*
 * Add provider: five steps from "what kind of system" to a change request. The whole wizard is one
 * plain object (`WizardState`); `buildProviderDocument` turns it into the provider document and
 * `emitYaml` (components/yaml-emit.ts) writes the YAML that :validate, :try and :publish take.
 * The state, the file and the steps live in components/provider-editor/, which the designer
 * shares; what the tests and older imports read from this page is re-exported here.
 */

export type { ActionRow, ErrorRow, WizardState } from '../components/provider-editor/state'
export {
  actionSlo,
  actionSloIssues,
  authOf,
  blankAction,
  blankError,
  buildProviderDocument,
  bumpPatch,
  describeProvider,
  draftNames,
  endpointsOf,
  envNames,
  errorDocument,
  errorIssues,
  inferSchema,
  initialState,
  kebab,
  matchesOperation,
  metadataOf,
  renameAction,
  resourceFields,
  sandboxEnvironment,
  specBlock,
  specImportRequest,
  starterActions,
  stateFromProvider,
  stepIssues,
  withVersion,
} from '../components/provider-editor/state'
export { ErrorClasses } from '../components/provider-editor/ActionsStep'

/* ----------------------------------------------------------------------------
   The page
   ------------------------------------------------------------------------- */

/** Editing keeps the file as it is: only the evidence (Connect, Try it) and the review are stepped through. */
const EDIT_STEPS = STEPS.filter((s) => s.id === 'connect' || s.id === 'try' || s.id === 'review')

export default function ProviderWizardPage({ route }: PageProps) {
  // the same cache entry as the shell's, so the owner defaults to the signed-in user's team
  const user = useCurrentUser()
  const me = useQuery(['me', user ?? 'default'], () => api.me())
  // /providers/:name/edit: the provider's file is the draft, the next patch its version
  const editingName = route.params.name ?? null
  const detail = useQuery(['provider', editingName ?? ''], () => api.provider(editingName ?? ''), { enabled: editingName !== null })
  const steps = editingName ? EDIT_STEPS : STEPS
  // a phone stacks the steps: five labels do not fit side by side
  const narrow = useMediaQuery('(max-width: 639px)')
  const [state, setState] = useState<WizardState>(initialState)
  const [stepId, setStepId] = useState<StepId>(steps[0].id)
  const [furthest, setFurthest] = useState(0)
  // Ask Devin, from the Type step or the Review step
  const [asking, setAsking] = useState<'type' | 'review' | null>(null)
  const update = (patch: Partial<WizardState>) => setState((s) => ({ ...s, ...patch }))
  // ?import=ansible or ?import=terraform: the backend drafts the provider from a repository or a module, and the wizard opens on the review
  const importKind: ImportKind | null = !editingName && (route.query.import === 'ansible' || route.query.import === 'terraform') ? route.query.import : null
  const importing = importKind !== null && state.imported === null

  // the provider seeds the wizard once it arrives
  if (detail.data && state.editing !== detail.data.name) setState(stateFromProvider(detail.data))
  // the owner defaults to the signed-in user's first team until they pick one
  const firstTeam = me.data?.teams?.[0]
  if (firstTeam && !state.ownerTouched && !state.owner) update({ owner: firstTeam })

  const stepPos = Math.max(0, steps.findIndex((s) => s.id === stepId))
  const issues = stepIssues(state, STEP_NUMBER[stepId])
  const yaml = useMemo(() => providerYaml(state), [state])

  const goTo = (pos: number) => {
    setStepId(steps[pos].id)
    setFurthest((f) => Math.max(f, pos))
  }
  const next = () => {
    if (issues.length) return
    goTo(Math.min(steps.length - 1, stepPos + 1))
  }

  const title = editingName ? `Edit ${detail.data?.title ?? editingName}` : importKind === 'ansible' ? 'Import an Ansible repository' : importKind === 'terraform' ? 'Import a Terraform module' : 'Add a provider'
  const subtitle = editingName
    ? 'The file stays as it is: test the connection and try the actions again, which the platform keeps as review evidence, then publish the next patch. The SLO, the overrides and the spec change from the provider page.'
    : importKind
      ? 'The platform reads it and drafts the provider; review the draft, then open a change request.'
      : 'Five steps from a spec, a repository or a module to a change request.'
  const back = editingName ? { href: `#${pathFor('provider', { name: editingName })}`, label: detail.data?.title ?? editingName } : { href: `#${pathFor('providers')}`, label: 'Providers' }

  if (editingName && !detail.data) {
    return (
      <>
        <PageHeader eyebrow="Providers" title={title} subtitle={subtitle} back={back} />
        {detail.error ? (
          <ErrorState error={detail.error} title="Could not load the provider" onRetry={() => void detail.refetch()} />
        ) : (
          <div className="flex items-center gap-2 py-10 text-sm text-secondary" role="status">
            <Spinner /> Loading the provider…
          </div>
        )}
      </>
    )
  }

  return (
    <>
      <PageHeader eyebrow="Providers" title={title} subtitle={subtitle} back={back} />
      <Stepper steps={steps} current={stepPos} onStepClick={(i) => i <= furthest && setStepId(steps[i].id)} orientation={narrow ? 'vertical' : 'horizontal'} className="mb-6" />

      {importing && importKind ? (
        <ImportStep
          kind={importKind}
          defaultOwner={state.owner}
          onImported={(patch) => {
            update(patch)
            goTo(steps.length - 1)
          }}
        />
      ) : (
        <>
          {stepId === 'type' && <TypeStep state={state} update={update} onAsk={() => setAsking('type')} />}
          {stepId === 'connect' && <ConnectStep state={state} update={update} />}
          {stepId === 'actions' && <ActionsStep state={state} update={update} />}
          {stepId === 'try' && <TryStep state={state} update={update} yaml={yaml} />}
          {stepId === 'review' && <ReviewStep state={state} update={update} yaml={yaml} onAsk={() => setAsking('review')} onPublished={(accepted) => navigate(pathFor('change-request', { id: accepted.id }))} />}
        </>
      )}

      {!importing && (
        <div className="mt-6 flex flex-wrap items-start justify-between gap-3 border-t border-stroke-light pt-4">
          <Button variant="ghost" icon="arrow-left" disabled={stepPos === 0} onClick={() => setStepId(steps[stepPos - 1].id)}>
            Back
          </Button>
          {issues.length > 0 && (
            <ul className="min-w-0 flex-1 space-y-0.5 text-caption text-secondary" aria-label="Before you continue">
              {issues.slice(0, 4).map((i) => (
                <li key={i} className="flex items-start gap-1.5">
                  <Icon name="info" size={12} className="mt-0.5 shrink-0 text-tertiary" />
                  {i}
                </li>
              ))}
            </ul>
          )}
          {stepPos < steps.length - 1 ? (
            <Button variant="primary" iconRight="arrow-right" disabled={issues.length > 0} onClick={next}>
              {stepId === 'try' && Object.keys(state.tries).length === 0 ? 'Skip to review' : 'Next'}
            </Button>
          ) : (
            <span />
          )}
        </div>
      )}

      {asking && (
        <DraftModal
          initial={describeProvider(state)}
          onUse={(d) => {
            // the draft replaces the wizard's document, and names the provider
            update({ draftYaml: d.yaml, ...draftNames(d.yaml) })
            // asked from the Type step: the review is where the draft is checked, tried and published
            if (asking === 'type') goTo(steps.length - 1)
            setAsking(null)
          }}
          onClose={() => setAsking(null)}
        />
      )}
    </>
  )
}
