import { useState } from 'react'
import { Button, Card, CodeBlock, Field, Icon, Input, Segmented, StatusDot, Tag, duration, useToast } from '@iaf/ui'
import { navigate, pathFor } from '../../app/router'
import { api, errorMessage } from '../../lib/api'
import type { CommandAccepted, ProviderValidation } from '../../lib/types'
import { ImportSummary } from '../../pages/ProviderImport'
import { ProblemList } from '../ProblemList'
import { downloadText } from '../download'
import { checkDot } from '../common'
import type { StepProps } from './state'

/* ---- 5 · Review ---------------------------------------------------------- */

/** Checks, the review of the evidence, the strategy and Publish; `onPublished` takes the change request it opened (the wizard page opens it). */
export function ReviewStep({ state, update, yaml, onAsk, onPublished }: StepProps & { yaml: string; onAsk: () => void; onPublished: (accepted: CommandAccepted) => void }) {
  const toast = useToast()
  const [judged, setJudged] = useState<{ yaml: string; result: ProviderValidation } | null>(null)
  const validation = judged?.yaml === yaml ? judged.result : null
  const setValidation = (result: ProviderValidation) => setJudged({ yaml, result })
  const [busy, setBusy] = useState<'validate' | 'publish' | null>(null)

  const validate = async (): Promise<ProviderValidation | null> => {
    setBusy('validate')
    try {
      const v = await api.validateProvider(yaml)
      setValidation(v)
      return v
    } catch (e) {
      toast.error('The checks did not run', errorMessage(e))
      return null
    } finally {
      setBusy(null)
    }
  }

  const failed = (v: ProviderValidation) => v.checks.some((c) => c.status === 'failed') || v.problems.some((p) => p.level === 'error')
  // the review judges the evidence; publish refuses a file whose review is not ready
  const reviewBlocks = (v: ProviderValidation) => v.review?.ready === false
  const review = validation?.review
  const suggested = review?.slo ?? null

  const publish = async () => {
    setBusy('publish')
    try {
      let v = validation
      if (!v) {
        v = await api.validateProvider(yaml)
        setValidation(v)
      }
      if (failed(v) || reviewBlocks(v)) {
        toast.error('Not ready to publish', reviewBlocks(v) ? 'The review is not ready: see what it found.' : 'Fix the failed checks first.')
        return
      }
      const accepted = await api.publishProvider(yaml, state.strategy)
      toast.toast({
        title: `Opened change request ${accepted.id}`,
        message: `${state.name} ${state.version} goes through review${state.strategy === 'canary' ? ', then a canary' : ', then every call moves at once'}.`,
        tone: 'healthy',
        duration: 8000,
        action: { label: 'Open change request', onClick: () => navigate(pathFor('change-request', { id: accepted.id })) },
      })
      onPublished(accepted)
    } catch (e) {
      toast.error('Could not publish', errorMessage(e))
    } finally {
      setBusy(null)
    }
  }

  const path = `providers/${state.owner || 'team'}/${state.name || 'provider'}.yaml`

  return (
    <div className="grid grid-cols-1 gap-5 lg:grid-cols-[minmax(0,1fr)_22rem]">
      <div className="min-w-0 space-y-3">
        {state.imported && <ImportSummary imported={state.imported} />}
        {state.draftYaml && (
          <div className="flex flex-wrap items-center justify-between gap-2 rounded-md border border-accent/40 bg-accent-tint px-4 py-2 text-sm">
            <span className="inline-flex items-center gap-2 text-primary">
              <Icon name={state.editing ? 'edit' : 'sparkles'} size={14} className="text-accent" />
              {state.editing ? `The file as published, at version ${state.version}; a try's answer goes in with Use this.` : state.imported ? "The import's draft replaces the wizard's document." : "This draft replaces the wizard's document."}
            </span>
            {!state.editing && (
              <Button size="sm" variant="ghost" onClick={() => update({ draftYaml: null })}>
                Back to the wizard's YAML
              </Button>
            )}
          </div>
        )}
        <CodeBlock code={yaml} language="yaml" title={path} lineNumbers maxHeight="max-h-[60vh]" />
        <div className="flex flex-wrap gap-2">
          <Button size="sm" variant="ghost" icon="download" onClick={() => downloadText(yaml, `${state.name || 'provider'}.yaml`, 'application/yaml')}>
            Download
          </Button>
          <Button size="sm" variant="ghost" icon="sparkles" onClick={onAsk}>
            Ask Devin to draft it
          </Button>
        </div>
      </div>

      <aside className="min-w-0 space-y-4">
        <Card
          title="Checks"
          actions={
            <Button size="sm" icon="refresh" loading={busy === 'validate'} onClick={() => void validate()}>
              Run checks
            </Button>
          }
        >
          {!validation ? (
            <p className="text-sm text-secondary">Schema, reachability, effect and approval rules, samples. Publish runs them too.</p>
          ) : (
            <div className="space-y-3">
              <ul className="space-y-1.5" aria-label="Checks">
                {validation.checks.map((c) => (
                  <li key={c.id} className="flex items-start gap-2 text-sm">
                    <StatusDot kind={checkDot(c.status)} className="mt-1.5" label={c.status} />
                    <span className="min-w-0">
                      <span className="text-primary">{c.label}</span>
                      {c.detail && <span className="block text-caption text-secondary">{c.detail}</span>}
                    </span>
                  </li>
                ))}
              </ul>
              {validation.problems.length > 0 && <ProblemList problems={validation.problems} compact />}
              {!failed(validation) && !reviewBlocks(validation) && (
                <p className="flex items-center gap-1.5 text-caption text-healthy">
                  <Icon name="check" size={13} />
                  Ready to publish.
                </p>
              )}
            </div>
          )}
        </Card>

        {validation && review !== undefined && (
          <Card title="Review" subtitle="What the platform saw in this wizard, judged as the reviewer sees it on the change request." data-testid="review-evidence">
            {review === null ? (
              <p className="text-sm text-secondary">Not a provider file: nothing to review.</p>
            ) : (
              <div className="space-y-3">
                <Tag tone={review.ready ? 'healthy' : 'warning'} dot>
                  {review.ready ? 'Ready for review' : 'Not ready yet'}
                </Tag>
                <ul className="space-y-1.5" aria-label="Review checks">
                  {review.checks.map((c) => (
                    <li key={c.id} className="flex items-start gap-2 text-sm">
                      <StatusDot kind={checkDot(c.status)} className="mt-1.5" label={c.status} />
                      <span className="min-w-0">
                        <span className="text-primary">{c.label}</span>
                        {c.detail && <span className="block text-caption text-secondary">{c.detail}</span>}
                      </span>
                    </li>
                  ))}
                </ul>
                {review.problems.length > 0 && <ProblemList problems={review.problems} compact />}
                {suggested && (
                  <div className="rounded-md border border-stroke-light bg-surface-secondary px-3 py-2 text-caption" data-testid="slo-suggestion">
                    <p className="text-primary">
                      Suggested SLO: {suggested.successRate}% success, p95 {duration(suggested.p95Ms)}
                    </p>
                    <p className="text-secondary">
                      Observed p95 {duration(suggested.observedP95Ms)} over {suggested.calls} calls. {suggested.detail}
                    </p>
                    {!state.editing && (
                      <Button size="sm" className="mt-2" onClick={() => update({ slo: { ...state.slo, successRate: String(suggested.successRate), p95Ms: String(suggested.p95Ms) } })}>
                        Use suggested SLO
                      </Button>
                    )}
                  </div>
                )}
              </div>
            )}
          </Card>
        )}

        <Card title="Publish">
          <div className="space-y-4">
            <Field label="Strategy" hint={state.strategy === 'canary' ? 'New jobs move to this version in stages, gated on its health' : 'Every new job uses this version as soon as it merges'}>
              <Segmented
                ariaLabel="Strategy"
                full
                value={state.strategy}
                onChange={(v) => update({ strategy: v })}
                options={[
                  { value: 'canary', label: 'Canary' },
                  { value: 'all', label: 'All at once' },
                ]}
              />
            </Field>
            {state.editing ? (
              <p className="text-caption text-tertiary">The SLO and the alert channel change from the provider page, through a change request of their own.</p>
            ) : (
              <>
                <div className="grid grid-cols-2 gap-3">
                  <Field label="SLO success (%)" htmlFor="pub-slo-rate">
                    <Input id="pub-slo-rate" type="number" min={1} max={100} step={0.1} value={state.slo.successRate} onChange={(e) => update({ slo: { ...state.slo, successRate: e.target.value } })} />
                  </Field>
                  <Field label="SLO p95 (ms)" htmlFor="pub-slo-p95">
                    <Input id="pub-slo-p95" type="number" min={1} value={state.slo.p95Ms} onChange={(e) => update({ slo: { ...state.slo, p95Ms: e.target.value } })} />
                  </Field>
                </div>
                <Field label="Alert channel" htmlFor="pub-channel">
                  <Input id="pub-channel" mono value={state.slo.channel} onChange={(e) => update({ slo: { ...state.slo, channel: e.target.value } })} placeholder="#team-alerts" />
                </Field>
              </>
            )}
            <Button variant="primary" icon="git-branch" className="w-full" loading={busy === 'publish'} disabled={busy === 'validate' || (!!validation && (failed(validation) || reviewBlocks(validation)))} onClick={() => void publish()}>
              Publish as change request
            </Button>
            <p className="text-caption text-tertiary">Opens a change request on a branch; the checks run again there, and merging publishes.</p>
          </div>
        </Card>
      </aside>
    </div>
  )
}
