import { useMemo, useRef, useState, type ReactNode } from 'react'
import { Button, EmptyState, Field, Icon, PageHeader, SkeletonRows, Stepper, Textarea, useToast } from '@iaf/ui'
import { Facts, PreviewCard, SchemaForm, Section, createFormOf, defaultsFor, fieldNames, fieldRoot, formatField, formatValueText, isCreateRequest, titleCase, validateAgainst, type Problem, type SchemaValue } from '@iaf/console-kit'
import { CodeDisclosure } from '../app/CodeDisclosure'
import { useConsole, useRouteType } from '../app/console'
import { FailureNote, useCommand } from '../app/commands'
import { ActionButton, useRefusal } from '../app/Refusal'
import { Link, navigate, pathFor, resourcePath, type PageProps } from '../app/router'
import { bff } from '../lib/bff'
import { codeSamplesFor } from '../lib/code'
import { purposeOf, singularOf, type ConsoleAction, type ConsoleType } from '../lib/descriptors'
import { explain } from '../lib/plain'
import { prefillFor } from '../lib/prefill'
import { useQuery } from '../lib/queries'
import { cleanInput, useSubmissionKey } from '../lib/submission'
import type { CommandInput } from '../lib/types'
import { MissingType } from './parts'
import { stepOfProblems, stepsFor, withTypeHints } from './create/steps'

/*
 * The create wizard (WP 3.3), generated from the type's create workflow: its input schema makes
 * the steps (ungrouped fields first, then one step per `x-console.group`), each step checks its
 * own fields before moving on, and the review shows every value, the preview the BFF judged
 * (steps, what it touches, the monthly cost, what policy says) and the same request as code.
 * Create sends one request with one Idempotency-Key and opens the new resource's page, which
 * follows the request until the resource is there.
 */

export default function CreatePage({ route }: PageProps) {
  const { loading, error, refetch } = useConsole()
  const type = useRouteType(route.params.service, route.params.type)
  if (!type) return <MissingType name={route.params.type} service={route.params.service} loading={loading} error={error} refetch={refetch} />
  if (!type.create) return <NothingCreates type={type} />
  return <Wizard key={`${type.service}/${type.name}`} type={type} action={type.create} prefill={route.query.prefill} />
}

function NothingCreates({ type }: { type: ConsoleType }) {
  const lower = type.title.toLowerCase()
  return (
    <>
      <PageHeader eyebrow={type.title} title={`Create ${singularOf(type.title).toLowerCase()}`} />
      <EmptyState
        icon={type.icon}
        title={`Nothing creates ${lower} from the console yet`}
        message={`${type.owner} has not published a workflow that creates ${lower}. ${purposeOf(type)}`}
        action={
          <Link to={pathFor('type', { service: type.service, type: type.name })} className="text-sm font-medium text-accent transition-opacity hover:opacity-70">
            Back to {lower}
          </Link>
        }
      />
    </>
  )
}

/** A value as the review shows it: a build-first request in words, anything else as its field formats it. */
function reviewValue(action: ConsoleAction, name: string, v: unknown): ReactNode {
  const prop = action.input.properties?.[name]
  const form = createFormOf(prop)
  if (form && isCreateRequest(v)) {
    const parts = Object.entries(v.create ?? {})
      .filter(([, x]) => x !== undefined && x !== '')
      .map(([k, x]) => `${form.createSchema.properties?.[k]?.title ?? titleCase(k)} ${formatValueText(x)}`)
    return `Built first${form.producer ? ` with ${form.producer}` : ''}${parts.length ? `: ${parts.join(', ')}` : ''}`
  }
  return formatField(action.input, name, v)
}

function Wizard({ type, action, prefill }: { type: ConsoleType; action: ConsoleAction; prefill?: string }) {
  const { decide, account, region } = useConsole()
  const refusal = useRefusal()
  const toast = useToast()
  const cmd = useCommand()
  const keyFor = useSubmissionKey()
  const noun = singularOf(type.title).toLowerCase()
  const title = action.title
  const listPath = pathFor('type', { service: type.service, type: type.name })
  // the create's input, laid out with the type's own groups where the input carries none
  const schema = useMemo(() => withTypeHints(action.input, type.schema), [action.input, type.schema])
  const steps = useMemo(() => stepsFor(schema, noun), [schema, noun])
  const [current, setCurrent] = useState(0)
  // the copilot's and Clone's links open the wizard filled (lib/prefill.ts); the person still checks every step
  const [value, setValue] = useState<SchemaValue>(() => ({ ...defaultsFor(schema), ...prefillFor(schema, prefill) }))
  const [problems, setProblems] = useState<Problem[]>([])
  const [reason, setReason] = useState('')
  const heading = useRef<HTMLHeadingElement>(null)
  const step = steps[current]
  const review = step.kind === 'review'
  const decision = decide(type, 'create')
  const allFields = useMemo(() => fieldNames(schema), [schema])

  const input = useMemo(() => cleanInput(value), [value])
  const body: CommandInput = reason.trim() ? { input, reason: reason.trim() } : { input }
  const inputJson = JSON.stringify(input)
  const preview = useQuery(['create-preview', type.name, inputJson], () => bff.resources.previewCreate(type.name, { input: JSON.parse(inputJson) as Record<string, unknown> }), { enabled: review })
  const policy = review ? preview.data?.policy.decision : undefined
  const samples = codeSamplesFor({ kind: 'create', type, action, body, context: { accountId: account.id, region: region.name } })

  const moveTo = (i: number) => {
    setCurrent(i)
    // the step's heading takes focus, so a screen reader hears where it landed
    requestAnimationFrame(() => heading.current?.focus())
  }
  const problemsIn = (i: number, v: SchemaValue) => validateAgainst(schema, v).filter((p) => steps[i].fields.includes(fieldRoot(p.field) ?? ''))
  const focusField = (p: Problem | undefined) => {
    const root = fieldRoot(p?.field)
    if (root) requestAnimationFrame(() => document.getElementById(`create-${root}`)?.focus())
  }
  const next = () => {
    const found = problemsIn(current, value)
    setProblems(found)
    if (found.length) {
      focusField(found[0])
      return
    }
    moveTo(current + 1)
  }
  const create = async () => {
    const found = validateAgainst(schema, value)
    if (found.length) {
      setProblems(found)
      const i = stepOfProblems(steps, found)
      if (i >= 0) moveTo(i)
      return
    }
    const key = keyFor(body)
    const accepted = await cmd.run(title, () => bff.resources.create(type.name, body, key), {
      quiet: true,
      invalidates: ['resources', 'jobs'],
      onProblems: (ps) => {
        setProblems(ps)
        const i = stepOfProblems(steps, ps)
        if (i >= 0) moveTo(i)
      },
    })
    if (!accepted) return
    toast.toast({
      tone: 'healthy',
      title: `${title} requested`,
      message: accepted.approval ? `It waits for an approver${accepted.approval.group ? ` from ${accepted.approval.group}` : ''}, then runs.` : `Request ${accepted.jobId} is running; the page follows it.`,
      action: { label: 'View request', onClick: () => navigate(pathFor('request', { id: accepted.jobId })) },
    })
    navigate(resourcePath(type.service, type.name, accepted.wfrn))
  }

  const denied = !decision.allowed && decision.denied ? decision.denied : null

  let content: ReactNode
  if (step.kind === 'fields') {
    content = (
      <div className="max-w-2xl space-y-6">
        <SchemaForm
          schema={schema}
          value={value}
          onChange={(v) => {
            setValue(v)
            // once a field was flagged, the flags follow the typing
            if (problems.length) setProblems(problemsIn(current, v))
          }}
          problems={problems}
          idPrefix="create"
          omit={allFields.filter((n) => !step.fields.includes(n))}
        />
        <CodeDisclosure samples={samples} ariaLabel={`${title} as code`} />
      </div>
    )
  } else {
    content = (
      <div className="space-y-8">
        {steps
          .filter((s) => s.kind === 'fields')
          .map((s) => (
            <Section
              key={s.id}
              title={s.label}
              actions={
                <Button size="sm" variant="ghost" icon="edit" onClick={() => moveTo(steps.indexOf(s))}>
                  {`Edit ${s.label.toLowerCase()}`}
                </Button>
              }
            >
              <Facts columns={2} items={s.fields.map((name) => ({ label: action.input.properties?.[name]?.title ?? titleCase(name), value: reviewValue(action, name, value[name]) }))} />
            </Section>
          ))}
        <Section title="What will happen" description="What the workflow will do, what it touches, what it costs a month and what policy says, judged before anything starts.">
          {preview.loading ? (
            <SkeletonRows n={2} rowClassName="h-20" />
          ) : preview.error ? (
            <FailureNote failure={explain(preview.error, 'Preview this request')} onRetry={() => void preview.refetch()} />
          ) : preview.data ? (
            <PreviewCard preview={preview.data} title={`${title}${typeof input.name === 'string' ? `: ${input.name}` : ''}`} />
          ) : null}
          {policy === 'deny' && <p className="mt-3 text-sm text-critical">Policy refuses this request as it stands, so it cannot be created. Change the values above, or ask the account’s admins.</p>}
        </Section>
        {policy === 'needs-approval' && (
          <Field label="Reason for the approver" hint="Optional. The approvers read it with the request." htmlFor="create-reason" className="max-w-2xl">
            <Textarea id="create-reason" rows={3} maxLength={1000} value={reason} onChange={(e) => setReason(e.target.value)} placeholder="What it is for, and why now" />
          </Field>
        )}
        <CodeDisclosure samples={samples} ariaLabel={`${title} as code`} open />
        <FailureNote failure={cmd.failure} shownFields={allFields} onDismiss={cmd.clear} />
      </div>
    )
  }

  return (
    <>
      <PageHeader eyebrow={type.title} title={title} subtitle={action.description ?? purposeOf(type)} />

      {denied && (
        <div className="mb-6 flex flex-wrap items-start gap-3 rounded-lg border border-stroke-light bg-surface-secondary px-4 py-3 text-sm" data-denied={denied.layer}>
          <Icon name="lock" size={16} className="mt-0.5 shrink-0 text-tertiary" />
          <div className="min-w-0 flex-1">
            <p className="font-medium text-primary">You can fill this in, but not create it here</p>
            <p className="mt-0.5 text-caption text-secondary">{denied.reason}. The request is still yours as code, to hand to someone who may run it.</p>
          </div>
          <Button size="sm" variant="ghost" onClick={() => refusal.show({ denied, what: title })}>
            See why
          </Button>
        </div>
      )}

      <div className="grid gap-8 lg:grid-cols-[13rem_minmax(0,1fr)]">
        <nav aria-label="Wizard steps" className="hidden lg:block">
          <Stepper steps={steps.map((s) => ({ id: s.id, label: s.label, description: s.description, optional: s.optional }))} current={current} onStepClick={moveTo} orientation="vertical" />
        </nav>
        <form
          className="min-w-0 max-w-4xl"
          noValidate
          aria-labelledby="create-step-title"
          onSubmit={(e) => {
            e.preventDefault()
            if (review) void create()
            else next()
          }}
        >
          <p className="text-caption text-tertiary">
            Step {current + 1} of {steps.length}
          </p>
          <h2 id="create-step-title" ref={heading} tabIndex={-1} className="mt-0.5 text-title3 font-semibold text-primary outline-none">
            {step.label}
          </h2>
          {review && <p className="mt-1 text-sm text-secondary">Check the request, what it will do and what it costs, then create it.</p>}
          <div className="mt-5">{content}</div>
          <div className="mt-10 flex flex-wrap items-center justify-between gap-3 border-t border-stroke-light pt-5">
            <Button variant="ghost" onClick={() => navigate(listPath)}>
              Cancel
            </Button>
            <div className="flex flex-wrap items-center gap-2">
              {current > 0 && (
                <Button icon="arrow-left" onClick={() => moveTo(current - 1)}>
                  Previous
                </Button>
              )}
              {review ? (
                <ActionButton decision={decision} what={title} verb="create" variant="primary" icon="plus" loading={cmd.busy} disabled={policy === 'deny' || preview.loading} onClick={() => void create()}>
                  {title}
                </ActionButton>
              ) : (
                <Button type="submit" variant="primary" iconRight="arrow-right">
                  Next
                </Button>
              )}
            </div>
          </div>
        </form>
      </div>
    </>
  )
}
