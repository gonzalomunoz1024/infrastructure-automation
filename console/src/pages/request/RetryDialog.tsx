import { useMemo, useState, type FormEvent } from 'react'
import { Button, Field, Modal, OverlayBody, OverlayFooter, OverlayHeader, Select } from '@iaf/ui'
import { SchemaForm, fieldNames, type Problem, type Schema, type SchemaValue } from '@iaf/console-kit'
import { FailureNote } from '../../app/commands'
import { subjectFor } from '../../lib/code'
import type { ConsoleType } from '../../lib/descriptors'
import type { Explanation } from '../../lib/plain'
import { cleanInput } from '../../lib/submission'
import type { BffJobDetail, RetryFix } from '../../lib/types'
import { actionOfJob, defaultFromStep, retryFixOf } from './parts'

/*
 * Retry with changes: the request's own input as the workflow's form, the fields the person
 * changes sent as the fix the BFF merges over the job's input, and the step to run from. A
 * create keeps the name it was given, and a bulk run takes no changes (its failed rows run again
 * as they were), so neither is offered. Nothing changed is a plain retry.
 */

export interface RetryDialogProps {
  job: BffJobDetail
  types: ConsoleType[]
  busy?: boolean
  failure?: Explanation | null
  problems?: Problem[]
  onSubmit: (fix: RetryFix | undefined) => void
  onClose: () => void
}

export function RetryDialog({ job, types, busy, failure, problems = [], onSubmit, onClose }: RetryDialogProps) {
  const known = useMemo(() => actionOfJob(types, job.workflow), [types, job.workflow])
  const subject = known ? subjectFor(known.action, known.type) : undefined
  const omit = useMemo(() => [subject, known?.create ? 'name' : undefined].filter((x): x is string => !!x), [subject, known])
  const schema = useMemo<Schema | undefined>(() => (known ? { ...known.action.input, required: (known.action.input.required ?? []).filter((r) => !omit.includes(r)) } : undefined), [known, omit])
  // the job's input as the form knows it: its own fields, minus what the page fills itself
  const original = useMemo(() => {
    const props = schema?.properties ?? {}
    return Object.fromEntries(Object.entries(job.input ?? {}).filter(([k]) => k in props && !omit.includes(k)))
  }, [job.input, schema, omit])
  const [value, setValue] = useState<SchemaValue>(() => ({ ...original }))
  const steps = job.steps ?? []
  const fallback = defaultFromStep(job)
  const [fromStep, setFromStep] = useState(fallback ?? '')
  const name = typeof job.input?.name === 'string' ? job.input.name : undefined
  const batch = !!job.batch || job.kind === 'batch'

  const submit = (e: FormEvent) => {
    e.preventDefault()
    onSubmit(batch ? undefined : retryFixOf(original, cleanInput(value), fromStep || undefined, fallback))
  }

  return (
    <Modal onClose={onClose} label="Retry with changes" width="max-w-xl">
      <form onSubmit={submit} noValidate>
        <OverlayHeader title={batch ? 'Run the failed rows again' : 'Retry with changes'} subtitle={job.title ?? job.workflow} icon="refresh" />
        <OverlayBody>
          {batch ? (
            <p className="text-sm text-secondary">A bulk run takes no changes: its failed rows run again as they were, under the same rollout, as a new request. To change a row, run the action on that resource alone.</p>
          ) : (
            <>
              <p className="mb-4 text-sm text-secondary">
                Change what made the step fail. Only the fields you change are sent; the rest of the request stays as it was{job.status === 'needs-attention' ? ', and it goes on in the same request' : ', and it runs again as a new request'}.
              </p>
              {known?.create && name && (
                <p className="mb-4 text-sm text-primary">
                  <span className="text-tertiary">Name </span>
                  <span className="font-medium">{name}</span>
                  <span className="text-tertiary">, which a retry keeps.</span>
                </p>
              )}
              {schema ? (
                <SchemaForm schema={schema} omit={omit} value={value} onChange={setValue} problems={problems} idPrefix="retry" />
              ) : (
                <p className="text-sm text-tertiary">The console does not know this workflow’s form, so it runs again with the same input.</p>
              )}
              {steps.length > 1 && (
                <Field label="Run from" htmlFor="retry-from" hint="The step the request starts again from. The ones before it stay as they ran." className="mt-5">
                  <Select ariaLabel="Run from" value={fromStep} onChange={setFromStep} options={steps.map((s) => ({ value: s.id, label: `${s.name} (${s.id})` }))} />
                </Field>
              )}
            </>
          )}
          <FailureNote failure={failure ?? null} shownFields={schema ? fieldNames(schema) : []} className="mt-5" />
        </OverlayBody>
        <OverlayFooter>
          <Button type="button" variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          <Button type="submit" variant="primary" icon="refresh" loading={busy} data-autofocus={batch || !schema ? true : undefined}>
            {batch ? 'Run them again' : 'Retry'}
          </Button>
        </OverlayFooter>
      </form>
    </Modal>
  )
}
