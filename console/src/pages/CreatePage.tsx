import { useMemo, useRef, useState, type ReactNode } from 'react'
import { Button, EmptyState, Field, Icon, PageHeader, SkeletonRows, Stepper, Textarea, useToast } from '@iaf/ui'
import { Facts, PreviewCard, SchemaForm, Section, createFormOf, defaultsFor, fieldNames, fieldRoot, formatField, formatValueText, isCreateRequest, titleCase, validateAgainst, type Problem, type SchemaValue } from '@iaf/console-kit'
import { CodeDisclosure } from '../app/CodeDisclosure'
import { useConsole, useRouteType } from '../app/console'
import { ExplainedError, FailureNote, useCommand } from '../app/commands'
import { ActionButton, useRefusal } from '../app/Refusal'
import { Link, navigate, pathFor, resourcePath, type PageProps } from '../app/router'
import { bff } from '../lib/bff'
import { codeSamplesFor } from '../lib/code'
import { purposeOf, singularOf, type ConsoleAction, type ConsoleType } from '../lib/descriptors'
import { explain } from '../lib/plain'
import { useQuery } from '../lib/queries'
import { cleanInput, useSubmissionKey } from '../lib/submission'
import { ALLOWED } from '../lib/permissions'
import { andList, draftOf, draftTags, emptyTagsDraft, missingTags, requiredValue, tagsApi, type CloneDraft, type CloneTarget, type TagsDraft } from '../lib/tags'
import type { CommandInput } from '../lib/types'
import { MissingType } from './parts'
import { isTagProblem, stepOfProblems, stepsFor, withTypeHints } from './create/steps'
import { TagsStep, tagsStepProblems } from './create/TagsStep'
import { keepDraft, noteWords, takeDraft } from './create/clone'

/*
 * The create wizard (WP 3.3), generated from the type's create workflow: its input schema makes
 * the steps (ungrouped fields first, then one step per `x-console.group`), each step checks its
 * own fields before moving on, and the review shows every value, the preview the BFF judged
 * (steps, what it touches, the monthly cost, what policy says) and the same request as code.
 * Create sends one request with one Idempotency-Key and opens the new resource's page, which
 * follows the request until the resource is there. Before the review, the Tags step asks for
 * every tag the account requires (README decision 38): a blank one keeps Create disabled, and says
 * so in words, because the BFF refuses a create that leaves one out. `?from=<wfrn>` opens it as a
 * copy of that resource (the BFF's clone draft: its fields, a new name, its tags), and
 * `?draft=<token>` with a draft another account's wizard handed over (README decision 41).
 */

export default function CreatePage({ route }: PageProps) {
  const { loading, error, refetch } = useConsole()
  const type = useRouteType(route.params.service, route.params.type)
  if (!type) return <MissingType name={route.params.type} service={route.params.service} loading={loading} error={error} refetch={refetch} />
  if (!type.create) return <NothingCreates type={type} />
  const from = route.query.from
  if (from) return <CloneWizard key={`${type.service}/${type.name}/from/${from}`} type={type} action={type.create} from={from} />
  const token = route.query.draft
  if (token) return <DraftWizard key={`${type.service}/${type.name}/draft/${token}`} type={type} action={type.create} token={token} />
  return <Wizard key={`${type.service}/${type.name}`} type={type} action={type.create} />
}

/** The wizard filled from a resource of this account: the BFF's clone draft. */
function CloneWizard({ type, action, from }: { type: ConsoleType; action: ConsoleAction; from: string }) {
  const { account } = useConsole()
  const draft = useQuery(['clone-draft', account.id, type.name, from], () => tagsApi.clone(type.name, from))
  if (draft.data) return <Wizard type={type} action={action} clone={draft.data} />
  return (
    <>
      <PageHeader eyebrow={type.title} title={action.title} subtitle="A copy of an existing resource." />
      {draft.loading ? <SkeletonRows n={4} rowClassName="h-14" /> : <ExplainedError error={draft.error} what="Read the resource to copy" onRetry={() => void draft.refetch()} />}
    </>
  )
}

/** The wizard filled from a draft another account's wizard handed over, read once. */
function DraftWizard({ type, action, token }: { type: ConsoleType; action: ConsoleAction; token: string }) {
  const [draft] = useState(() => takeDraft(token))
  return <Wizard type={type} action={action} clone={draft} lost={!draft} />
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

function Wizard({ type, action, clone, lost }: { type: ConsoleType; action: ConsoleAction; clone?: CloneDraft | null; lost?: boolean }) {
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
  const tagPolicy = useQuery(['tag-policy', account.id], () => tagsApi.policy())
  const required = useMemo(() => tagPolicy.data?.required ?? [], [tagPolicy.data])
  const steps = useMemo(() => stepsFor(schema, noun, { tags: true, requiredTags: required.length }), [schema, noun, required.length])
  const [current, setCurrent] = useState(0)
  const [value, setValue] = useState<SchemaValue>(() => (clone ? { ...defaultsFor(schema), ...clone.input } : defaultsFor(schema)))
  const [problems, setProblems] = useState<Problem[]>([])
  const [reason, setReason] = useState('')
  const [tagDraft, setTagDraft] = useState<TagsDraft>(() => (clone ? draftOf(clone.tags, clone.tagPolicy.required, clone.input) : emptyTagsDraft()))
  const [showMissing, setShowMissing] = useState(false)
  const heading = useRef<HTMLHeadingElement>(null)
  const step = steps[current]
  const review = step.kind === 'review'
  const decision = decide(type, 'create')
  const allFields = useMemo(() => fieldNames(schema), [schema])

  const input = useMemo(() => cleanInput(value), [value])
  const tags = useMemo(() => draftTags(required, tagDraft, input), [required, tagDraft, input])
  const missing = useMemo(() => missingTags(required, tagDraft, input), [required, tagDraft, input])
  const body: CommandInput = reason.trim() ? { input, tags, reason: reason.trim() } : { input, tags }
  const inputJson = JSON.stringify(input)
  const tagsJson = JSON.stringify(tags)
  const preview = useQuery(['create-preview', type.name, inputJson, tagsJson], () => bff.resources.previewCreate(type.name, { input: JSON.parse(inputJson) as Record<string, unknown>, tags: JSON.parse(tagsJson) as Record<string, string> }), { enabled: review })
  const tagsStep = steps.findIndex((s) => s.kind === 'tags')
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
    if (step.kind === 'tags') {
      // a value a tag may not take, or a row that is not a tag, keeps the step; a blank required tag waits for the review
      if (tagsStepProblems(tagPolicy.data, tagDraft, input).length) {
        requestAnimationFrame(() => document.querySelector<HTMLElement>('[aria-invalid="true"]')?.focus())
        return
      }
      moveTo(current + 1)
      return
    }
    const found = problemsIn(current, value)
    setProblems(found)
    if (found.length) {
      focusField(found[0])
      return
    }
    moveTo(current + 1)
  }
  const create = async () => {
    if (missing.length) {
      setShowMissing(true)
      if (tagsStep >= 0) moveTo(tagsStep)
      return
    }
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
        if (ps.some(isTagProblem)) setShowMissing(true)
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

  // a copy may go to another account of the person's: the draft is fetched for it here, and handed over
  const others = clone && clone.source.accountId === account.id ? clone.targets.filter((t) => t.accountId !== account.id) : []
  const elsewhere = async (t: CloneTarget) => {
    if (!clone) return
    const there = await cmd.run(`Copy ${clone.source.name} to ${t.name}`, () => tagsApi.clone(type.name, clone.source.wfrn, t.accountId))
    if (!there) return
    navigate(pathFor('create', { service: type.service, type: type.name }, { draft: keepDraft(there) }, { accountId: t.accountId }))
  }
  const fieldTitle = (field: string) => action.input.properties?.[field]?.title ?? titleCase(field)

  const blocked = missing.length ? `Set ${andList(missing.map((t) => t.title))} on the Tags step before you create: every new resource of this account carries its required tags.` : null

  let content: ReactNode
  if (step.kind === 'tags') {
    content = (
      <div className="space-y-6">
        <TagsStep
          policy={tagPolicy.data}
          loading={tagPolicy.loading}
          error={tagPolicy.error}
          onRetry={() => void tagPolicy.refetch()}
          draft={tagDraft}
          onChange={(d) => {
            setTagDraft(d)
            // the BFF's word on a tag goes once the tag changes
            if (problems.some(isTagProblem)) setProblems(problems.filter((p) => !isTagProblem(p)))
          }}
          input={input}
          problems={problems.filter(isTagProblem)}
          showMissing={showMissing}
        />
        <CodeDisclosure samples={samples} ariaLabel={`${title} as code`} />
      </div>
    )
  } else if (step.kind === 'fields') {
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
        {tagsStep >= 0 && (
          <Section
            title="Tags"
            actions={
              <Button size="sm" variant="ghost" icon="edit" onClick={() => moveTo(tagsStep)}>
                Edit tags
              </Button>
            }
          >
            <Facts
              columns={2}
              items={[
                ...required.map((t) => {
                  const v = requiredValue(t, tagDraft, input).trim()
                  return { label: t.title, value: v ? <span className="font-mono text-caption">{v}</span> : <span className="text-critical">Not set: required</span> }
                }),
                ...Object.entries(tags)
                  .filter(([k]) => !required.some((t) => t.key === k))
                  .map(([k, v]) => ({ label: k, value: <span className="font-mono text-caption">{v || '(empty)'}</span> })),
              ]}
            />
          </Section>
        )}
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

      {clone && (
        <div className="mb-6 rounded-lg border border-stroke-light bg-surface-secondary px-4 py-3 text-sm" data-clone={clone.source.wfrn}>
          <p className="flex flex-wrap items-center gap-x-1.5 font-medium text-primary">
            <Icon name="copy" size={14} className="text-tertiary" />
            <span>A copy of</span>
            <Link to={pathFor('resource', { service: type.service, type: type.name, wfrn: clone.source.wfrn }, undefined, { accountId: clone.source.accountId !== account.id ? clone.source.accountId : undefined })} className="text-accent hover:opacity-70">
              {clone.source.name}
            </Link>
            {clone.source.accountId !== account.id && <span className="font-normal text-secondary">{`from account ${clone.source.accountId}`}</span>}
          </p>
          <p className="mt-0.5 text-caption text-secondary">Its values and tags are filled in from it; check them, because nothing is created until you create it.</p>
          {clone.notes.length > 0 && (
            <ul className="mt-2 list-disc space-y-0.5 pl-5 text-caption text-secondary" aria-label="What to check in the copy">
              {clone.notes.map((n) => (
                <li key={`${n.field}:${n.code}`} data-note={n.code}>
                  {noteWords(n, fieldTitle)}
                </li>
              ))}
            </ul>
          )}
          {others.length > 0 && (
            <div className="mt-3 flex flex-wrap items-center gap-2" role="group" aria-label="Create it in another account">
              <span className="text-caption text-secondary">Create it in another account instead:</span>
              {others.map((t) => (
                <ActionButton key={t.accountId} decision={t.allowed ? ALLOWED : { allowed: false, denied: t.denied ?? { layer: 'console-iam', action: `${type.service}:create`, reason: `You may not create ${type.title.toLowerCase()} in ${t.name}`, requestable: true } }} what={`Create a copy in ${t.name}`} verb="clone" size="sm" icon="arrow-right" loading={cmd.busy} onClick={() => void elsewhere(t)}>
                  {t.name}
                </ActionButton>
              ))}
            </div>
          )}
        </div>
      )}
      {lost && (
        <p className="mb-6 rounded-lg border border-dashed border-stroke-light px-4 py-3 text-sm text-secondary" role="status">
          The copy handed over from the other account is no longer here: a copy opens once, in the tab that made it. The form starts empty; open the resource again for another copy.
        </p>
      )}

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
          {review && blocked && (
            <p className="mt-8 flex items-start gap-2 text-sm text-secondary" data-blocked="tags">
              <Icon name="lock" size={14} className="mt-0.5 shrink-0 text-tertiary" />
              <span>
                {`Create is not available yet. ${blocked}`}{' '}
                <button type="button" className="font-medium text-accent hover:opacity-70" onClick={() => moveTo(tagsStep)}>
                  Set the tags
                </button>
              </span>
            </p>
          )}
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
                <ActionButton decision={decision} what={title} verb="create" variant="primary" icon="plus" loading={cmd.busy} disabled={policy === 'deny' || preview.loading || !!blocked || tagPolicy.loading} onClick={() => void create()}>
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
