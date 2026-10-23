import { useMemo, useState, type FormEvent } from 'react'
import { Button, Checkbox, Field, Input, Modal, OverlayBody, OverlayFooter, OverlayHeader, PageHeader, Segmented, SkeletonRows, Table, Textarea, type TableColumn } from '@iaf/ui'
import { Facts, Section, StatusBadge, When } from '@iaf/console-kit'
import { ExplainedError, FailureNote, useCommand } from '../../app/commands'
import { pathOfWfrn, useConsole } from '../../app/console'
import { ActionButton } from '../../app/Refusal'
import { Link, pathFor, type PageProps } from '../../app/router'
import { STACK_STATE_WORDS, STEP_STATE_WORDS, blueprintsApi, stackMoving, teardownOrder, TEARDOWN_REASON_MIN, type Stack, type StackEvent, type StackState, type StackStep } from '../../lib/blueprints'
import type { Decision } from '../../lib/permissions'
import { usePoll } from '../../lib/poll'
import { useQuery } from '../../lib/queries'
import { firstRefusal, roleDecision } from '../../lib/roleGate'
import { useSubmissionKey } from '../../lib/submission'
import { useTypeWords, withArticle } from './BlueprintsPage'

/*
 * Stacks (WP 9.7): what was launched from a blueprint, and one stack's page, which follows its
 * single request through every resource: the steps wave by wave with each child's state and a
 * link to the child and to its request, what the stack hands back once active, and its events.
 * Teardown is one request: it says what it deletes, in order, and what it keeps, asks why, and
 * wants the stack's name typed. Resume starts again what failed, without launching anew.
 */

type Filter = 'all' | StackState
const FILTERS: { value: Filter; label: string }[] = [
  { value: 'all', label: 'All' },
  { value: 'active', label: 'Active' },
  { value: 'pending', label: 'Launching' },
  { value: 'failed', label: 'Failed' },
  { value: 'deleting', label: 'Tearing down' },
  { value: 'deleted', label: 'Torn down' },
]

export default function StacksPage() {
  const { account } = useConsole()
  const [filter, setFilter] = useState<Filter>('all')
  const list = useQuery(['stacks', filter], () => blueprintsApi.stacks.list(filter === 'all' ? { limit: 100 } : { limit: 100, state: filter }))
  usePoll((list.data?.items ?? []).some(stackMoving), () => void list.refetch())
  const columns: TableColumn<Stack>[] = [
    {
      key: 'name',
      header: 'Stack',
      render: (s) => (
        <Link to={pathFor('stack', { wfrn: s.wfrn }, { name: s.name })} className="font-medium text-primary hover:text-accent">
          {s.name}
        </Link>
      ),
    },
    { key: 'state', header: 'State', render: (s) => <StatusBadge status={STACK_STATE_WORDS[s.state].status} label={STACK_STATE_WORDS[s.state].label} /> },
    { key: 'blueprint', header: 'Blueprint', render: (s) => `${s.blueprint} v${s.blueprintVersion}` },
    { key: 'steps', header: 'Resources', render: (s) => `${s.steps.filter((x) => x.state === 'done').length} of ${s.steps.length} done` },
    { key: 'by', header: 'Launched by', render: (s) => s.launchedBy ?? 'someone' },
    { key: 'at', header: 'Launched', render: (s) => <When at={s.createdAt} /> },
  ]
  return (
    <>
      <PageHeader
        title="Stacks"
        subtitle="Every stack launched from a blueprint in this account: where each is, and what it made."
        actions={
          <Link to={pathFor('blueprints')} className="text-sm font-medium text-accent hover:opacity-70">
            Blueprints
          </Link>
        }
      />
      <div className="mb-4 max-w-full overflow-x-auto">
        <Segmented<Filter> ariaLabel="Show stacks that are" value={filter} onChange={setFilter} options={FILTERS} />
      </div>
      {list.error && !list.data ? (
        <ExplainedError error={list.error} what="Load the stacks" onRetry={() => void list.refetch()} />
      ) : (
        <Table caption={`Stacks in ${account.name}`} columns={columns} rows={list.data?.items ?? []} rowKey={(s) => s.wfrn} loading={list.loading && !list.data} emptyIcon="layers" emptyTitle="No stacks here" emptyMessage="Launch one from a blueprint; it shows up here at once." />
      )}
    </>
  )
}

/** One stack, followed while it moves. */
export function StackPage({ route }: PageProps) {
  const wfrn = route.params.wfrn
  const q = useQuery(['stacks', 'one', wfrn], () => blueprintsApi.stacks.get(wfrn))
  const events = useQuery(['stacks', 'events', wfrn], () => blueprintsApi.stacks.events(wfrn, { limit: 100 }))
  const moving = stackMoving(q.data)
  usePoll(moving, () => {
    void q.refetch()
    void events.refetch()
  })
  if (q.loading && !q.data) return <SkeletonRows n={5} />
  if (!q.data) return <ExplainedError error={q.error} what="Load this stack" onRetry={() => void q.refetch()} />
  return <StackView stack={q.data} events={events.data?.items ?? []} />
}

function StackView({ stack: s, events }: { stack: Stack; events: StackEvent[] }) {
  const { account, types, decide } = useConsole()
  const words = useTypeWords()
  const cmd = useCommand()
  const keyFor = useSubmissionKey()
  const [tearing, setTearing] = useState(false)
  const order = useMemo(() => teardownOrder(s), [s])
  const state = STACK_STATE_WORDS[s.state]
  const waves = useMemo(() => wavesOf(s.steps), [s.steps])
  const failed = s.steps.find((x) => x.step === s.failure?.step) ?? s.steps.find((x) => x.state === 'failed')

  // who may tear it down: the account's teardown, and the delete of every child it deletes
  const teardown: Decision = useMemo(() => {
    const role = roleDecision(account.roles, 'blueprints:teardown', `Tear down ${s.name}`)
    const children = order.deletes.map((x) => {
      const t = types.find((y) => y.name === x.type)
      return t ? decide(t, 'delete') : { allowed: true as const }
    })
    const first = firstRefusal([role, ...children])
    if (!first.allowed || !s.can || s.can.includes('teardown')) return first
    return { allowed: false, denied: { layer: 'console-iam', action: 'blueprints:teardown', reason: `You may not tear down ${s.name} in this account`, requestable: false } }
  }, [account.roles, decide, order.deletes, s.can, s.name, types])
  const resume: Decision = s.can?.includes('resume') ? { allowed: true } : roleDecision(account.roles, 'blueprints:resume', `Resume ${s.name}`)
  const canTearDown = s.state === 'active' || s.state === 'failed' || s.state === 'pending'
  const canResume = s.state === 'failed' || (s.state === 'deleting' && !!s.failure)

  const doResume = () => {
    const body = { expectedVersion: s.version }
    void cmd.run(`Resume ${s.name}`, () => blueprintsApi.stacks.resume(s.wfrn, body, keyFor({ resume: s.wfrn, ...body })), {
      invalidates: ['stacks', 'resources', 'jobs'],
      done: () => ({ title: `${s.name} goes on`, message: failed ? `${failed.title ?? failed.step} is tried again.` : 'What stopped is tried again.' }),
    })
  }

  return (
    <>
      <PageHeader
        eyebrow={`Stack from ${s.blueprint} v${s.blueprintVersion}`}
        title={s.name}
        subtitle={s.state === 'deleted' ? `Torn down${s.teardownBy ? ` by ${s.teardownBy}` : ''}${s.teardownReason ? `: ${s.teardownReason}` : ''}.` : `Launched by ${s.launchedBy ?? 'someone'}. Every resource carries the tag stack ${s.name}.`}
        actions={
          <div className="flex flex-wrap items-center gap-2">
            {canResume && (
              <ActionButton decision={resume} what={`Resume ${s.name}`} verb="resume" icon="refresh" loading={cmd.busy} onClick={doResume}>
                Resume
              </ActionButton>
            )}
            {canTearDown && (
              <ActionButton decision={teardown} what={`Tear down ${s.name}`} verb="teardown" icon="trash" onClick={() => setTearing(true)}>
                Tear down
              </ActionButton>
            )}
          </div>
        }
      />
      <p role="status" aria-live="polite" className="mb-6 flex flex-wrap items-center gap-2 text-sm text-secondary" data-stack-state={s.state}>
        <StatusBadge status={state.status} label={state.label} />
        <span>{stateLine(s)}</span>
      </p>
      <FailureNote failure={cmd.failure} onDismiss={cmd.clear} className="mb-6" />
      {s.failure && failed && (
        <section aria-label="What failed" className="mb-8 rounded-xl border border-critical bg-critical-tint px-4 py-3">
          <h2 className="text-subheadline font-semibold text-primary">{`${failed.title ?? failed.step} failed${s.state === 'deleting' ? ' while tearing down' : ''}`}</h2>
          <p className="mt-1 text-sm text-primary">{s.failure.message ?? failed.error?.message ?? 'Its request did not succeed.'}</p>
          <p className="mt-2 text-sm text-secondary">
            {s.state === 'deleting' ? 'The teardown stopped there. ' : 'The steps after it wait. '}
            {failed.jobId && (
              <>
                <Link to={pathFor('request', { id: failed.jobId })} className="text-accent underline hover:opacity-70">
                  Open its request
                </Link>
                {' to see why or retry it there; '}
              </>
            )}
            Resume tries it again and goes on.
          </p>
        </section>
      )}
      <Facts
        columns={4}
        className="mb-8"
        items={[
          { label: 'Blueprint', value: <Link to={pathFor('blueprint', { name: s.blueprint })} className="text-accent hover:opacity-70">{`${s.blueprint} v${s.blueprintVersion}`}</Link> },
          { label: 'Launched', value: <When at={s.createdAt} /> },
          { label: 'Region', value: s.region },
          { label: 'Parameters', value: Object.entries(s.parameters).map(([k, v]) => `${k} ${String(v)}`).join(', ') || 'none' },
        ]}
      />
      <Section title="Resources" description="Wave by wave. A wave starts once everything it waits for is active; teardown runs the other way.">
        <ol aria-label="Steps, wave by wave" className="space-y-3">
          {waves.map((wave, i) => (
            <li key={i} className="rounded-lg border border-stroke-light">
              <p className="px-4 pt-3 text-caption font-medium uppercase tracking-wide text-tertiary">Wave {i + 1}</p>
              <ul className="divide-y divide-stroke-light">
                {wave.map((x) => (
                  <StepRow key={x.step} step={x} typeWord={words(x.type)} href={pathOfWfrn(x.wfrn, types)} />
                ))}
              </ul>
            </li>
          ))}
        </ol>
      </Section>
      {s.state === 'active' && s.outputs && Object.keys(s.outputs).length > 0 && (
        <Section title="What it hands back" className="mt-8">
          <Facts columns={3} items={Object.values(s.outputs).map((o) => ({ label: o.title, value: <OutputValue value={o.value} /> }))} />
        </Section>
      )}
      <Section title="Events" description="Newest last." className="mt-8">
        {events.length === 0 ? (
          <p className="text-sm text-secondary">Nothing has happened yet.</p>
        ) : (
          <ol aria-label="Stack events" className="space-y-2">
            {events.map((e) => (
              <li key={e.id} className="flex flex-wrap items-baseline gap-x-3 text-sm">
                <When at={e.at} className="w-24 shrink-0 text-caption text-tertiary" />
                <span className="text-primary">{eventLine(e, s)}</span>
                {e.actor && <span className="text-caption text-secondary">{`by ${e.actor}`}</span>}
              </li>
            ))}
          </ol>
        )}
      </Section>
      {tearing && <TeardownDialog stack={s} onClose={() => setTearing(false)} />}
    </>
  )
}

function StepRow({ step: x, typeWord, href }: { step: StackStep; typeWord: string; href: string | null }) {
  const w = STEP_STATE_WORDS[x.state]
  return (
    <li className="flex flex-wrap items-center gap-x-3 gap-y-1 px-4 py-3 text-sm" data-step={x.step} data-step-state={x.state}>
      <StatusBadge status={w.status} label={w.label} size="sm" />
      <span className="font-medium text-primary">{x.title ?? x.step}</span>
      <span className="text-secondary">{withArticle(typeWord)}</span>
      {x.name &&
        (href ? (
          <Link to={href} className="text-accent hover:opacity-70">
            {x.name}
          </Link>
        ) : (
          <span className="text-secondary">{x.name}</span>
        ))}
      {x.deletionPolicy === 'retain' && <span className="text-caption text-tertiary">kept at teardown</span>}
      {x.attempt > 1 && <span className="text-caption text-tertiary">{`attempt ${x.attempt}`}</span>}
      {x.jobId && (
        <Link to={pathFor('request', { id: x.jobId })} className="ml-auto text-caption text-accent hover:opacity-70" aria-label={`Request for ${x.title ?? x.step}`}>
          Request
        </Link>
      )}
    </li>
  )
}

/** An output: a wfrn links to its resource, anything else is text. A value from data never becomes an href unless it is a console path. */
function OutputValue({ value }: { value: string }) {
  const { types } = useConsole()
  const href = value.startsWith('wfrn:') ? pathOfWfrn(value, types) : null
  if (href)
    return (
      <Link to={href} className="break-all text-accent hover:opacity-70">
        {value.slice(value.lastIndexOf('/') + 1)}
      </Link>
    )
  return <span className="break-all font-mono text-caption">{value}</span>
}

function TeardownDialog({ stack: s, onClose }: { stack: Stack; onClose: () => void }) {
  const cmd = useCommand()
  const keyFor = useSubmissionKey()
  const order = teardownOrder(s)
  const [reason, setReason] = useState('')
  const [retain, setRetain] = useState<string[]>([])
  const [typed, setTyped] = useState('')
  const [shown, setShown] = useState(false)
  const reasonWhy = reason.trim().length < TEARDOWN_REASON_MIN ? `Say why in at least ${TEARDOWN_REASON_MIN} characters` : reason.length > 1000 ? 'Up to 1000 characters' : null
  const ready = !reasonWhy && typed === s.name
  const deletes = order.deletes.filter((x) => !retain.includes(x.step))
  const keeps = [...order.keeps, ...order.deletes.filter((x) => retain.includes(x.step))]

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    setShown(true)
    if (!ready) return
    const body = { reason: reason.trim(), retain: retain.length ? retain : undefined, expectedVersion: s.version }
    const done = await cmd.run(`Tear down ${s.name}`, () => blueprintsApi.stacks.teardown(s.wfrn, body, keyFor({ teardown: s.wfrn, ...body })), {
      invalidates: ['stacks', 'resources', 'jobs'],
      quiet: true,
    })
    if (done) onClose()
  }

  return (
    <Modal onClose={onClose} label={`Tear down ${s.name}`} width="max-w-lg">
      <form onSubmit={(e) => void submit(e)} noValidate>
        <OverlayHeader title={`Tear down ${s.name}`} subtitle="One request deletes the stack's resources, the last wave first. A deleted resource cannot be brought back." icon="trash" />
        <OverlayBody>
          <div className="space-y-5">
            <div>
              <h3 className="text-sm font-semibold text-primary">It deletes, in this order</h3>
              {deletes.length ? (
                <ol aria-label="Deleted, in order" className="mt-1 list-decimal space-y-0.5 pl-5 text-sm text-primary">
                  {deletes.map((x) => (
                    <li key={x.step}>{`${x.name ?? x.title ?? x.step} (${x.title ?? x.step})`}</li>
                  ))}
                </ol>
              ) : (
                <p className="mt-1 text-sm text-secondary">Nothing: every resource is kept.</p>
              )}
            </div>
            {keeps.length > 0 && (
              <div>
                <h3 className="text-sm font-semibold text-primary">It keeps</h3>
                <ul aria-label="Kept" className="mt-1 list-disc space-y-0.5 pl-5 text-sm text-primary">
                  {keeps.map((x) => (
                    <li key={x.step}>{`${x.name ?? x.title ?? x.step}, which leaves the stack and stays`}</li>
                  ))}
                </ul>
              </div>
            )}
            {order.deletes.length > 0 && (
              <fieldset>
                <legend className="text-sm font-semibold text-primary">Keep any of these as well?</legend>
                <div className="mt-2 space-y-2">
                  {order.deletes.map((x) => (
                    <Checkbox key={x.step} checked={retain.includes(x.step)} onChange={(on) => setRetain(on ? [...retain, x.step] : retain.filter((r) => r !== x.step))} label={`Keep ${x.name ?? x.title ?? x.step}`} />
                  ))}
                </div>
              </fieldset>
            )}
            <Field label="Why" htmlFor="teardown-reason" required hint="Kept with the stack's history." error={shown ? reasonWhy : undefined}>
              <Textarea id="teardown-reason" data-autofocus value={reason} onChange={(e) => setReason(e.target.value)} rows={2} invalid={shown && !!reasonWhy} />
            </Field>
            <Field label={`Type ${s.name} to confirm`} htmlFor="teardown-confirm" required error={shown && typed !== s.name ? `Type ${s.name} exactly` : undefined}>
              <Input id="teardown-confirm" value={typed} onChange={(e) => setTyped(e.target.value)} autoComplete="off" invalid={shown && typed !== s.name} />
            </Field>
            <FailureNote failure={cmd.failure} shownFields={['reason', 'retain']} compact />
          </div>
        </OverlayBody>
        <OverlayFooter>
          <Button type="button" variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          <Button type="submit" variant="danger" loading={cmd.busy}>
            Tear down stack
          </Button>
        </OverlayFooter>
      </form>
    </Modal>
  )
}

function wavesOf(steps: StackStep[]): StackStep[][] {
  const byId = new Map(steps.map((x) => [x.step, x]))
  const memo = new Map<string, number>()
  const depth = (id: string, seen = new Set<string>()): number => {
    if (memo.has(id)) return memo.get(id)!
    if (seen.has(id)) return 0
    seen.add(id)
    const x = byId.get(id)
    const d = x && x.dependsOn.length ? 1 + Math.max(...x.dependsOn.map((y) => depth(y, seen))) : 0
    memo.set(id, d)
    return d
  }
  const out: StackStep[][] = []
  for (const x of steps) (out[depth(x.step)] ??= []).push(x)
  return out.filter(Boolean)
}

function stateLine(s: Stack): string {
  const done = s.steps.filter((x) => x.state === 'done').length
  switch (s.state) {
    case 'pending':
      return `${done} of ${s.steps.length} resources created. This page follows it.`
    case 'active':
      return `All ${s.steps.length} resources are active.`
    case 'failed':
      return 'A step failed, so the ones after it wait.'
    case 'deleting':
      return s.failure ? 'A delete failed, so the teardown stopped.' : 'Deleting its resources, the last wave first. This page follows it.'
    default:
      return 'Its resources are deleted, apart from what it kept.'
  }
}

function eventLine(e: StackEvent, s: Stack): string {
  const step = e.data.step ? (s.steps.find((x) => x.step === e.data.step)?.title ?? e.data.step) : null
  const words: Record<string, string> = {
    StackRequested: 'Launch requested',
    StackLaunched: 'Every resource is active',
    StackStepStarted: `${step ?? 'A step'} started`,
    StackStepSucceeded: `${step ?? 'A step'} is done`,
    StackStepFailed: `${step ?? 'A step'} failed`,
    StackStepDeleting: `Deleting ${step ?? 'a step'}`,
    StackStepRemoved: `${step ?? 'A step'} deleted`,
    StackResumed: 'Resumed',
    StackTeardownRequested: 'Teardown requested',
    StackDeleted: 'Torn down',
  }
  return words[e.type] ?? e.type.replace(/^Stack/, '').replace(/([a-z])([A-Z])/g, '$1 $2')
}
