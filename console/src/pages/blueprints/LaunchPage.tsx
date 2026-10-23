import { useMemo, useRef, useState, type ReactNode } from 'react'
import { Button, Field, Icon, Input, PageHeader, Select, SkeletonRows, Stepper, useToast } from '@iaf/ui'
import { DotTag, Facts, Section, type Problem } from '@iaf/console-kit'
import { ExplainedError, FailureNote, useCommand } from '../../app/commands'
import { useConsole } from '../../app/console'
import { ActionButton } from '../../app/Refusal'
import { navigate, pathFor, type PageProps } from '../../app/router'
import { bff } from '../../lib/bff'
import { blueprintsApi, cleanParameters, countsLine, parameterDefaults, parameterProblem, stackNameProblem, type Blueprint, type BlueprintParameter, type StackPlan } from '../../lib/blueprints'
import { idOfWfrn } from '../../lib/descriptors'
import { denialTitle } from '../../lib/permissions'
import { useQuery } from '../../lib/queries'
import { riskOf } from '../../lib/risk'
import { firstRefusal, roleDecision } from '../../lib/roleGate'
import { useSubmissionKey } from '../../lib/submission'
import { draftTags, emptyTagsDraft, missingTags, tagsApi, type TagsDraft } from '../../lib/tags'
import { TagsStep, tagsStepProblems } from '../create/TagsStep'
import { RiskCard } from '../risk/RiskCard'
import { useTypeWords, withArticle } from './BlueprintsPage'

/*
 * Launching a stack from a blueprint (WP 9.7): the stack's name and the blueprint's parameters,
 * drawn from its schema (a choice is a select, a number a number, a parameter naming a resource a
 * picker of the registered ones of its type); the account's required tags, which every resource of
 * the stack carries; then the plan, judged by the state service before anything exists: every
 * resource it will create wave by wave with the inputs resolved as far as they can be, how many
 * of each type, and every authorization the launch needs, decided for this person. Launch sends
 * one request and opens the stack's page, which follows it.
 */

type StepId = 'parameters' | 'tags' | 'plan'
const STEPS: { id: StepId; label: string; description: string }[] = [
  { id: 'parameters', label: 'Parameters', description: 'Its name and what you choose' },
  { id: 'tags', label: 'Tags', description: 'On every resource it creates' },
  { id: 'plan', label: 'Review the plan', description: 'What it will create' },
]

export default function LaunchPage({ route }: PageProps) {
  const q = useQuery(['blueprint', route.params.name], () => blueprintsApi.get(route.params.name))
  if (q.loading && !q.data) return <SkeletonRows n={4} />
  if (!q.data) return <ExplainedError error={q.error} what="Load this blueprint" onRetry={() => void q.refetch()} />
  return <Launch blueprint={q.data} />
}

function Launch({ blueprint: bp }: { blueprint: Blueprint }) {
  const { account } = useConsole()
  const toast = useToast()
  const cmd = useCommand()
  const keyFor = useSubmissionKey()
  const words = useTypeWords()
  const heading = useRef<HTMLHeadingElement>(null)
  const [current, setCurrent] = useState(0)
  const [name, setName] = useState('')
  const [values, setValues] = useState<Record<string, unknown>>(() => parameterDefaults(bp.parameters))
  const [shown, setShown] = useState(false)
  const [problems, setProblems] = useState<Problem[]>([])
  const [tagDraft, setTagDraft] = useState<TagsDraft>(emptyTagsDraft)
  const [showMissing, setShowMissing] = useState(false)
  const tagPolicy = useQuery(['tag-policy', account.id], () => tagsApi.policy())
  const required = useMemo(() => tagPolicy.data?.required ?? [], [tagPolicy.data])

  const parameters = useMemo(() => cleanParameters(bp.parameters, values), [bp.parameters, values])
  const tags = useMemo(() => draftTags(required, tagDraft, parameters), [required, tagDraft, parameters])
  const missing = useMemo(() => missingTags(required, tagDraft, parameters), [required, tagDraft, parameters])
  const step = STEPS[current].id
  const nameWhy = stackNameProblem(name)
  const paramWhy = Object.fromEntries(Object.entries(bp.parameters).map(([k, p]) => [k, parameterProblem(p, values[k])]))
  const serverWhy = (field: string) => problems.find((p) => p.field === field)?.message
  const planBody = JSON.stringify({ name, parameters })
  const plan = useQuery(['stack-plan', bp.name, planBody], () => blueprintsApi.plan(bp.name, JSON.parse(planBody) as { name: string; parameters: Record<string, unknown> }), { enabled: step === 'plan' })
  const launchDecision = firstRefusal([roleDecision(account.roles, 'blueprints:launch', `Launch ${bp.title}`), ...(plan.data?.authorize ?? []).map((a) => (a.allowed === false && a.denied ? { allowed: false, denied: a.denied } : { allowed: true }))])

  const moveTo = (i: number) => {
    setCurrent(i)
    requestAnimationFrame(() => heading.current?.focus())
  }
  const next = () => {
    if (step === 'parameters') {
      setShown(true)
      const bad = nameWhy ? 'stack-name' : Object.keys(paramWhy).find((k) => paramWhy[k])
      if (bad) {
        requestAnimationFrame(() => document.getElementById(bad === 'stack-name' ? bad : `param-${bad}`)?.focus())
        return
      }
    }
    if (step === 'tags' && tagsStepProblems(tagPolicy.data, tagDraft, parameters).length) {
      requestAnimationFrame(() => document.querySelector<HTMLElement>('[aria-invalid="true"]')?.focus())
      return
    }
    moveTo(current + 1)
  }
  const launch = async () => {
    if (missing.length) {
      setShowMissing(true)
      moveTo(1)
      return
    }
    const body = { blueprint: bp.name, version: bp.version, name, parameters, tags }
    const stack = await cmd.run(`Launch ${bp.title}`, () => blueprintsApi.stacks.launch(body, keyFor(body)), {
      quiet: true,
      invalidates: ['stacks', 'resources', 'jobs'],
      onProblems: (ps) => {
        setProblems(ps)
        setShown(true)
        if (ps.some((p) => p.field?.startsWith('/tags'))) {
          setShowMissing(true)
          moveTo(1)
        } else if (ps.length) moveTo(0)
      },
    })
    if (!stack) return
    toast.toast({ tone: 'healthy', title: `${name} is launching`, message: 'Its page follows every resource as it is created.' })
    navigate(pathFor('stack', { wfrn: stack.wfrn }, { name: stack.name }))
  }

  let content: ReactNode
  if (step === 'parameters') {
    content = (
      <div className="max-w-2xl space-y-5">
        <Field label="Stack name" htmlFor="stack-name" required hint="3 to 25 lowercase letters, digits and hyphens. It names the stack in this account." error={(shown && nameWhy) || serverWhy('/name')}>
          <Input id="stack-name" value={name} onChange={(e) => setName(e.target.value.trim())} invalid={shown && !!nameWhy} placeholder="shop-web" autoComplete="off" />
        </Field>
        {Object.entries(bp.parameters).map(([k, p]) => (
          <ParameterField key={k} id={k} param={p} value={values[k]} onChange={(v) => setValues({ ...values, [k]: v })} error={(shown && paramWhy[k]) || serverWhy(`/parameters/${k}`)} />
        ))}
      </div>
    )
  } else if (step === 'tags') {
    content = <TagsStep policy={tagPolicy.data} loading={tagPolicy.loading} error={tagPolicy.error} onRetry={() => void tagPolicy.refetch()} draft={tagDraft} onChange={setTagDraft} input={parameters} problems={problems.filter((p) => p.field?.startsWith('/tags'))} showMissing={showMissing} />
  } else {
    content = plan.data ? <PlanView blueprint={bp} plan={plan.data} tags={tags} words={words} /> : plan.error ? <ExplainedError error={plan.error} what="Plan this launch" onRetry={() => void plan.refetch()} /> : <SkeletonRows n={3} rowClassName="h-16" />
  }

  return (
    <>
      <PageHeader eyebrow={`Blueprint ${bp.title}, v${bp.version}`} title={`Launch ${bp.title}`} subtitle={bp.description} />
      <div className="grid gap-8 lg:grid-cols-[13rem_minmax(0,1fr)]">
        <nav aria-label="Wizard steps" className="hidden lg:block">
          <Stepper steps={STEPS.map((s) => ({ id: s.id, label: s.label, description: s.description }))} current={current} onStepClick={(i) => (i < current ? moveTo(i) : undefined)} orientation="vertical" />
        </nav>
        <form
          className="min-w-0 max-w-4xl"
          noValidate
          aria-labelledby="launch-step-title"
          onSubmit={(e) => {
            e.preventDefault()
            if (step === 'plan') void launch()
            else next()
          }}
        >
          <p className="text-caption text-tertiary">
            Step {current + 1} of {STEPS.length}
          </p>
          <h2 id="launch-step-title" ref={heading} tabIndex={-1} className="mt-0.5 text-title3 font-semibold text-primary outline-none">
            {STEPS[current].label}
          </h2>
          {step === 'plan' && <p className="mt-1 text-sm text-secondary">Nothing exists yet. Check what it will create and who may, then launch it.</p>}
          <div className="mt-5">{content}</div>
          <FailureNote failure={cmd.failure} shownFields={['name', ...Object.keys(bp.parameters)]} onDismiss={cmd.clear} className="mt-6" />
          {step === 'plan' && missing.length > 0 && (
            <p className="mt-6 flex items-start gap-2 text-sm text-secondary" data-blocked="tags">
              <Icon name="lock" size={14} className="mt-0.5 shrink-0 text-tertiary" />
              <span>{`Launch is not available yet: set ${missing.map((t) => t.title).join(', ')} on the Tags step.`}</span>
            </p>
          )}
          <div className="mt-10 flex flex-wrap items-center justify-between gap-3 border-t border-stroke-light pt-5">
            <Button variant="ghost" onClick={() => navigate(pathFor('blueprint', { name: bp.name }))}>
              Cancel
            </Button>
            <div className="flex flex-wrap items-center gap-2">
              {current > 0 && (
                <Button icon="arrow-left" onClick={() => moveTo(current - 1)}>
                  Previous
                </Button>
              )}
              {step === 'plan' ? (
                <ActionButton decision={launchDecision} what={`Launch ${bp.title}`} verb="launch" variant="primary" icon="play" loading={cmd.busy} disabled={!plan.data || missing.length > 0} onClick={() => void launch()}>
                  Launch stack
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

/** One parameter as its schema draws it: a choice, a number, a registered resource or text. */
function ParameterField({ id, param: p, value, onChange, error }: { id: string; param: BlueprintParameter; value: unknown; onChange: (v: unknown) => void; error?: string | null }) {
  const label = p.title
  const hint = p.description
  const htmlFor = `param-${id}`
  if (p['x-resource']?.type) return <ResourceParameter id={htmlFor} param={p} type={p['x-resource'].type} value={typeof value === 'string' ? value : ''} onChange={onChange} error={error} />
  if (p.enum) {
    const v = value === undefined ? '' : String(value)
    return (
      <Field label={label} hint={hint} required={p.required} error={error}>
        <Select ariaLabel={`${label}: ${v || 'Choose'}`} value={v} onChange={(x) => onChange(p.type === 'integer' ? Number(x) : x)} placeholder="Choose" options={p.enum.map((e) => ({ value: String(e), label: String(e) }))} />
      </Field>
    )
  }
  return (
    <Field label={label} htmlFor={htmlFor} hint={hint ?? (p.type === 'integer' && (p.minimum !== undefined || p.maximum !== undefined) ? `From ${p.minimum ?? 'any'} to ${p.maximum ?? 'any'}` : undefined)} required={p.required} error={error}>
      <Input id={htmlFor} inputMode={p.type === 'integer' ? 'numeric' : undefined} value={value === undefined ? '' : String(value)} onChange={(e) => onChange(e.target.value)} invalid={!!error} autoComplete="off" />
    </Field>
  )
}

/** A parameter naming a registered resource: a picker of the account's resources of that type. */
function ResourceParameter({ id, param: p, type, value, onChange, error }: { id: string; param: BlueprintParameter; type: string; value: string; onChange: (v: unknown) => void; error?: string | null }) {
  const list = useQuery(['resources', type, 'blueprint-picker'], () => bff.resources.list(type, { limit: 100, state: 'active' }))
  const options = (list.data?.items ?? []).map((r) => ({ value: idOfWfrn(r.wfrn) ?? r.wfrn, label: r.name ?? idOfWfrn(r.wfrn) ?? r.wfrn }))
  const chosen = options.find((o) => o.value === value)?.label
  return (
    <Field label={p.title} hint={p.description} required={p.required} error={error ?? (list.error ? 'Could not list them; try again in a moment' : undefined)}>
      <span id={id} className="block">
        <Select ariaLabel={`${p.title}: ${chosen ?? 'Choose one'}`} value={value} onChange={onChange} placeholder={list.loading ? 'Loading…' : 'Choose one'} options={options} disabled={list.loading} />
      </span>
    </Field>
  )
}

/** The plan: waves, counts, authorizations and the risk of what it creates. */
function PlanView({ blueprint: bp, plan, tags, words }: { blueprint: Blueprint; plan: StackPlan; tags: Record<string, string>; words: (type: string, n?: number) => string }) {
  const waves = [...new Set(plan.steps.map((s) => s.wave))].sort((a, b) => a - b)
  const refused = plan.authorize.filter((a) => a.allowed === false)
  const risk = riskOf({ kind: 'create', targets: plan.steps.map((s) => ({ attributes: s.input, tags })) })
  return (
    <div className="space-y-8">
      <Section title="Parameters">
        <Facts columns={2} items={[{ label: 'Stack name', value: plan.name }, ...Object.entries(plan.parameters).map(([k, v]) => ({ label: bp.parameters[k]?.title ?? k, value: String(v) }))]} />
      </Section>
      <Section title="What it will create" description={`${countsLine(plan.creates, words)}, wave by wave. A reference to another resource of the stack is filled in once that one exists.`}>
        <ol aria-label="The plan, wave by wave" className="space-y-3">
          {waves.map((w) => (
            <li key={w} className="rounded-lg border border-stroke-light px-4 py-3">
              <p className="text-caption font-medium uppercase tracking-wide text-tertiary">Wave {w + 1}</p>
              <ul className="mt-2 space-y-3">
                {plan.steps
                  .filter((s) => s.wave === w)
                  .map((s) => (
                    <li key={s.step} data-plan-step={s.step}>
                      <p className="flex flex-wrap items-center gap-2 text-sm">
                        <span className="font-medium text-primary">{s.title ?? s.step}</span>
                        <span className="text-secondary">{withArticle(words(s.type))}</span>
                        {s.deletionPolicy === 'retain' && <DotTag size="sm">kept at teardown</DotTag>}
                      </p>
                      {s.input && (
                        <p className="mt-0.5 break-words font-mono text-caption text-secondary">
                          {Object.entries(s.input)
                            .map(([k, v]) => `${k} ${typeof v === 'string' && v.startsWith('${resources.') ? `(from ${bp.resources[v.slice(12).split('.')[0]]?.title ?? 'another step'})` : String(v)}`)
                            .join(' · ')}
                        </p>
                      )}
                    </li>
                  ))}
              </ul>
            </li>
          ))}
        </ol>
      </Section>
      <Section title="Who may launch it" description="Checked for you now; the launch checks them again.">
        <ul aria-label="Authorizations" className="space-y-1.5 text-sm">
          {plan.authorize.map((a) => (
            <li key={`${a.action}:${a.resource}`} className="flex flex-wrap items-center gap-2" data-allowed={a.allowed !== false}>
              <DotTag tone={a.allowed === false ? 'critical' : 'healthy'}>{a.allowed === false ? 'Not allowed' : 'Allowed'}</DotTag>
              <span className="font-mono text-caption text-primary">{a.action}</span>
              {a.denied && <span className="text-caption text-secondary">{denialTitle(a.denied)}</span>}
            </li>
          ))}
        </ul>
        {refused.length > 0 && <p className="mt-3 text-sm text-critical">You cannot launch it here as it stands: {refused.map((a) => a.action).join(', ')} is not allowed for you in this account.</p>}
      </Section>
      <RiskCard risk={risk} />
    </div>
  )
}
