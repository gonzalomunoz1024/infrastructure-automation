import { useState } from 'react'
import { Button, Card, ConfirmDialog, Field, Select, useToast } from '@iaf/ui'
import { navigate, pathFor } from '../app/router'
import { api, errorMessage } from '../lib/api'
import type { RerunRequest } from '../lib/types'
import { JsonEditor, parseJsonText } from './JsonEditor'

export type RerunPlan = { request: RerunRequest; error?: undefined } | { request?: undefined; error: string }

/**
 * The body of `POST /jobs/{id}:rerun` from the card's two fields. The backend merges the input
 * onto this job's own, so an empty box re-runs with the same input and an object changes only the
 * fields it names; a list or a scalar is refused here rather than sent.
 */
export function rerunRequest(fromStep: string, inputText: string): RerunPlan {
  if (!fromStep) return { error: 'Choose the step to start from' }
  const parsed = parseJsonText(inputText)
  if (parsed.error) return { error: parsed.error }
  if (parsed.empty) return { request: { fromStep } }
  const value = parsed.value
  if (typeof value !== 'object' || value === null || Array.isArray(value)) return { error: 'Expected a JSON object' }
  return { request: { fromStep, input: value as Record<string, unknown> } }
}

/** Why the backend refuses to re-run this job (409), in the card's words; null when it can. */
export function rerunBlocker(job: { active: boolean; batch: boolean }): string | null {
  if (job.batch) return 'A batch is not re-run as a whole: retry its failed rows from the Batch tab.'
  if (job.active) return 'A job can be re-run once it has ended.'
  return null
}

export interface RerunCardProps {
  jobId: string
  /** the job's steps, in order: the first is chosen to start from */
  steps: readonly { id: string; name: string; status: string }[]
  /** this job's input, offered as the starting point of the box */
  input: Record<string, unknown>
  /** why it cannot be re-run (rerunBlocker), shown instead of the form */
  blocked?: string | null
}

/** "Re-run from a step" on the job page's Time travel tab: a new job, from the step you pick. */
export function RerunCard({ jobId, steps, input, blocked }: RerunCardProps) {
  const toast = useToast()
  const [fromStep, setFromStep] = useState(steps[0]?.id ?? '')
  const [inputText, setInputText] = useState('')
  const [confirm, setConfirm] = useState(false)
  const [busy, setBusy] = useState(false)
  const plan = rerunRequest(fromStep, inputText)

  const rerun = async (request: RerunRequest) => {
    setBusy(true)
    try {
      const accepted = await api.rerunJob(jobId, request)
      toast.success('Re-run started', `A new job starts at ${request.fromStep}.`)
      navigate(pathFor('job', { id: accepted.id }))
    } catch (e) {
      toast.error('Could not re-run', errorMessage(e))
    } finally {
      setBusy(false)
    }
  }

  const unavailable = blocked ?? (steps.length === 0 ? 'This job recorded no steps to start from.' : null)
  return (
    <Card title="Re-run from a step" subtitle="Starts a new job on the same version: the steps before the one you choose keep this run's outputs, and every step from there runs again.">
      {unavailable ? (
        <p className="text-caption text-secondary" data-testid="rerun-unavailable">
          {unavailable}
        </p>
      ) : (
        <div className="space-y-3">
          <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
            <Field label="From step">
              <Select ariaLabel="From step" value={fromStep} onChange={setFromStep} mono options={steps.map((s) => ({ value: s.id, label: s.id, hint: `${s.name} · ${s.status}` }))} />
            </Field>
            <Field label="Input" hint="Fields you give replace this job's; leave it empty to reuse its input" htmlFor="rerun-input">
              <Button size="sm" variant="ghost" icon="copy" onClick={() => setInputText(JSON.stringify(input, null, 2))}>
                Start from this job's input
              </Button>
            </Field>
          </div>
          <JsonEditor id="rerun-input" value={inputText} onChange={setInputText} rows={5} placeholder='{ "name": "…" }' objectOnly />
          <div className="flex justify-end">
            <Button variant="primary" icon="play" disabled={!plan.request} loading={busy} onClick={() => setConfirm(true)}>
              Re-run from {fromStep || 'step'}
            </Button>
          </div>
        </div>
      )}
      {confirm && plan.request && (
        <ConfirmDialog
          title={`Re-run from ${plan.request.fromStep}?`}
          icon="play"
          message={`A new job starts now and runs every step from ${plan.request.fromStep} with real effects, on this job's input${plan.request.input ? ' with your changes' : ''}.`}
          confirmLabel="Start the re-run"
          onConfirm={async () => {
            if (plan.request) await rerun(plan.request)
          }}
          onClose={() => setConfirm(false)}
        />
      )}
    </Card>
  )
}
