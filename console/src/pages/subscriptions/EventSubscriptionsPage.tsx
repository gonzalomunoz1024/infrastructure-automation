import { useState, type FormEvent } from 'react'
import { Button, Checkbox, EmptyState, Field, Input, Modal, OverlayBody, OverlayFooter, OverlayHeader, PageHeader, Segmented, SkeletonRows } from '@iaf/ui'
import { ConfirmDialog, DotTag, StatusBadge, When, type Problem } from '@iaf/console-kit'
import { ExplainedError, FailureNote, useCommand } from '../../app/commands'
import { useConsole } from '../../app/console'
import { ActionButton } from '../../app/Refusal'
import { useQuery } from '../../lib/queries'
import { roleDecision } from '../../lib/roleGate'
import { useSubmissionKey } from '../../lib/submission'
import { EVENT_GROUPS, TARGET_WORDS, eventLabel, subscriptionProblems, subscriptionsApi, targetLine, type Delivery, type Subscription, type SubscriptionInput, type TargetKind } from '../../lib/subscriptions'

/*
 * Event subscriptions (WP 9.14): the account's events sent to a webhook, a Kafka topic or a Slack
 * channel. A subscription names the events, optionally the types, and where they go; Test sends
 * one sample now and says how it went. A webhook's address is data: it is shown as text, never as
 * a link, and only https is accepted. Its signing secret travels once and is never shown again.
 * An Operator or an Admin creates and tests; only an Admin deletes.
 */

export default function EventSubscriptionsPage() {
  const { account } = useConsole()
  const list = useQuery(['event-subscriptions', account.id], () => subscriptionsApi.list({ limit: 100 }))
  const [creating, setCreating] = useState(false)
  const create = roleDecision(account.roles, 'event-subscriptions:create', 'Create an event subscription')
  return (
    <>
      <PageHeader
        title="Event subscriptions"
        subtitle={`Send what happens in ${account.name} to a webhook, a Kafka topic or a Slack channel.`}
        actions={
          <ActionButton decision={create} what="Create an event subscription" verb="create" variant="primary" icon="plus" onClick={() => setCreating(true)}>
            Create subscription
          </ActionButton>
        }
      />
      {list.loading && !list.data ? (
        <SkeletonRows n={3} rowClassName="h-20" />
      ) : list.error && !list.data ? (
        <ExplainedError error={list.error} what="Load the event subscriptions" onRetry={() => void list.refetch()} />
      ) : !list.data?.items.length ? (
        <EmptyState icon="bell" title="No subscriptions yet" message="A subscription sends the events you pick to a webhook, Kafka or Slack as they happen." />
      ) : (
        <ul aria-label="Event subscriptions" className="space-y-3">
          {list.data.items.map((s) => (
            <SubscriptionRow key={s.subscriptionId} sub={s} />
          ))}
        </ul>
      )}
      {creating && <CreateDialog onClose={() => setCreating(false)} />}
    </>
  )
}

function SubscriptionRow({ sub: s }: { sub: Subscription }) {
  const { account } = useConsole()
  const cmd = useCommand()
  const keyFor = useSubmissionKey()
  const [result, setResult] = useState<Delivery | null>(null)
  const [deleting, setDeleting] = useState(false)
  const test = roleDecision(account.roles, 'event-subscriptions:test', `Test ${s.name}`)
  const remove = roleDecision(account.roles, 'event-subscriptions:delete', `Delete ${s.name}`)
  const last = result ?? s.lastDelivery

  const runTest = async () => {
    const d = await cmd.run(`Test ${s.name}`, () => subscriptionsApi.test(s.subscriptionId, keyFor({ test: s.subscriptionId, at: Date.now() })), { invalidates: ['event-subscriptions'], quiet: true })
    if (d) setResult(d)
  }

  return (
    <li className="rounded-xl border border-stroke-light bg-surface p-4" data-subscription={s.name}>
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0">
          <p className="flex flex-wrap items-center gap-2">
            <span className="text-headline font-semibold text-primary">{s.name}</span>
            <StatusBadge status={s.status === 'active' ? 'succeeded' : 'failed'} label={s.status === 'active' ? 'Active' : 'Failing'} size="sm" />
          </p>
          <p className="mt-1 flex min-w-0 flex-wrap items-center gap-2 text-sm text-secondary">
            <DotTag size="sm">{TARGET_WORDS[s.target.kind].label}</DotTag>
            {/* the address is data: text, never a link */}
            <span className="min-w-0 break-all font-mono text-caption text-primary">{targetLine(s.target)}</span>
            {s.target.kind === 'webhook' && <span className="text-caption">{s.target.secretSet ? 'signed' : 'not signed'}</span>}
          </p>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <ActionButton decision={test} what={`Test ${s.name}`} verb="test" size="sm" icon="send" loading={cmd.busy} onClick={() => void runTest()}>
            Test
          </ActionButton>
          <ActionButton decision={remove} what={`Delete ${s.name}`} verb="delete" size="sm" icon="trash" onClick={() => setDeleting(true)}>
            Delete
          </ActionButton>
        </div>
      </div>
      <p className="mt-3 text-caption text-secondary">{`${s.events.map(eventLabel).join(', ')}${s.resourceTypes.length ? `, for ${s.resourceTypes.join(', ')} only` : ', for every type'}.`}</p>
      <p className="mt-1 text-caption text-tertiary">
        {`Created by ${s.createdBy} `}
        <When at={s.createdAt} />
      </p>
      <div role="status" aria-live="polite" className="mt-3 text-sm">
        {last && (
          <p className="flex flex-wrap items-center gap-2" data-delivery={last.status}>
            <StatusBadge status={last.status === 'delivered' ? 'succeeded' : 'failed'} label={last.status === 'delivered' ? 'Delivered' : 'Not delivered'} size="sm" />
            <span className="text-primary">{deliveryLine(last)}</span>
            <When at={last.at} className="text-caption text-tertiary" />
          </p>
        )}
      </div>
      <FailureNote failure={cmd.failure} onDismiss={cmd.clear} compact className="mt-2" />
      {deleting && (
        <ConfirmDialog
          title={`Delete ${s.name}?`}
          message="Nothing more is sent to it. Events already sent stay where they went."
          confirmLabel="Delete subscription"
          tone="danger"
          icon="trash"
          onClose={() => setDeleting(false)}
          onConfirm={async () => {
            await cmd.run(`Delete ${s.name}`, () => subscriptionsApi.remove(s.subscriptionId, keyFor({ remove: s.subscriptionId })), { invalidates: ['event-subscriptions'], done: () => ({ title: `${s.name} is deleted` }) })
          }}
        />
      )}
    </li>
  )
}

function deliveryLine(d: Delivery): string {
  const what = `${d.test ? 'Test ' : ''}${eventLabel(d.eventType).toLowerCase()}`
  if (d.status === 'delivered') return `${what}${d.httpStatus ? `, answered ${d.httpStatus}` : ''}${d.latencyMs !== undefined ? ` in ${d.latencyMs} ms` : ''}`
  return `${what}: ${d.error ?? (d.httpStatus ? `answered ${d.httpStatus}` : 'it did not arrive')}`
}

function CreateDialog({ onClose }: { onClose: () => void }) {
  const { types } = useConsole()
  const cmd = useCommand()
  const keyFor = useSubmissionKey()
  const [name, setName] = useState('')
  const [events, setEvents] = useState<string[]>([])
  const [resourceTypes, setResourceTypes] = useState<string[]>([])
  const [kind, setKind] = useState<TargetKind>('webhook')
  const [url, setUrl] = useState('')
  const [secret, setSecret] = useState('')
  const [topic, setTopic] = useState('')
  const [channel, setChannel] = useState('')
  const [shown, setShown] = useState(false)
  const [server, setServer] = useState<Problem[]>([])
  const body: SubscriptionInput = {
    name: name.trim(),
    events,
    ...(resourceTypes.length ? { resourceTypes } : {}),
    target: kind === 'webhook' ? { kind, url: url.trim(), ...(secret ? { secret } : {}) } : kind === 'kafka' ? { kind, topic: topic.trim() } : { kind, channel: channel.trim() },
  }
  const local = subscriptionProblems(body)
  const why = (field: string) => (shown ? local.find((p) => p.field === field)?.message : undefined) ?? server.find((p) => p.field === field)?.message

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    setShown(true)
    if (local.length) {
      requestAnimationFrame(() => document.querySelector<HTMLElement>('[aria-invalid="true"], [data-invalid="true"] input')?.focus())
      return
    }
    const made = await cmd.run('Create the subscription', () => subscriptionsApi.create(body, keyFor(body)), {
      invalidates: ['event-subscriptions'],
      onProblems: setServer,
      done: (s) => ({ title: `${s.name} is created`, message: 'Test it to send one sample event now.' }),
    })
    if (made) onClose()
  }
  const toggle = (list: string[], v: string, on: boolean) => (on ? [...list, v] : list.filter((x) => x !== v))

  return (
    <Modal onClose={onClose} label="Create an event subscription" width="max-w-xl">
      <form onSubmit={(e) => void submit(e)} noValidate>
        <OverlayHeader title="Create an event subscription" subtitle="The events you pick are sent as they happen, at least once each." icon="bell" />
        <OverlayBody>
          <div className="space-y-5">
            <Field label="Name" htmlFor="sub-name" required error={why('/name')}>
              <Input id="sub-name" data-autofocus value={name} onChange={(e) => setName(e.target.value)} invalid={!!why('/name')} autoComplete="off" placeholder="Compute changes" />
            </Field>
            <fieldset data-invalid={!!why('/events') || undefined}>
              <legend className="text-sm font-medium text-primary">Events</legend>
              {why('/events') && <p className="mt-1 text-caption text-critical">{why('/events')}</p>}
              <div className="mt-2 grid grid-cols-1 gap-4 sm:grid-cols-2">
                {EVENT_GROUPS.map((g) => (
                  <div key={g.topic}>
                    <p className="text-caption font-medium uppercase tracking-wide text-tertiary">{g.title}</p>
                    <div className="mt-1.5 space-y-1.5">
                      {g.events.map((ev) => (
                        <Checkbox key={ev.type} checked={events.includes(ev.type)} onChange={(on) => setEvents(toggle(events, ev.type, on))} label={ev.label} />
                      ))}
                    </div>
                  </div>
                ))}
              </div>
            </fieldset>
            <fieldset>
              <legend className="text-sm font-medium text-primary">Only these types</legend>
              <p className="text-caption text-secondary">Leave them all clear for every type.</p>
              <div className="mt-2 flex flex-wrap gap-x-5 gap-y-1.5">
                {types.map((t) => (
                  <Checkbox key={t.name} checked={resourceTypes.includes(t.name)} onChange={(on) => setResourceTypes(toggle(resourceTypes, t.name, on))} label={t.title} />
                ))}
              </div>
            </fieldset>
            <Field label="Send to">
              <Segmented<TargetKind>
                ariaLabel="Send to"
                value={kind}
                onChange={setKind}
                full
                options={(Object.keys(TARGET_WORDS) as TargetKind[]).map((k) => ({ value: k, label: TARGET_WORDS[k].label }))}
              />
            </Field>
            {kind === 'webhook' && (
              <>
                <Field label="Address" htmlFor="sub-url" required hint="An https:// address. Each delivery is a POST of the event as JSON." error={why('/target/url')}>
                  <Input id="sub-url" type="url" inputMode="url" value={url} onChange={(e) => setUrl(e.target.value)} invalid={!!why('/target/url')} autoComplete="off" placeholder="https://hooks.example.com/console" />
                </Field>
                <Field label="Signing secret" htmlFor="sub-secret" hint="Optional, at least 16 characters. Each delivery is signed with it; it is never shown again." error={why('/target/secret')}>
                  <Input id="sub-secret" type="password" value={secret} onChange={(e) => setSecret(e.target.value)} invalid={!!why('/target/secret')} autoComplete="new-password" />
                </Field>
              </>
            )}
            {kind === 'kafka' && (
              <Field label="Topic" htmlFor="sub-topic" required error={why('/target/topic')}>
                <Input id="sub-topic" value={topic} onChange={(e) => setTopic(e.target.value)} invalid={!!why('/target/topic')} autoComplete="off" placeholder="team-compute.events" />
              </Field>
            )}
            {kind === 'slack' && (
              <Field label="Channel" htmlFor="sub-channel" required error={why('/target/channel')}>
                <Input id="sub-channel" value={channel} onChange={(e) => setChannel(e.target.value)} invalid={!!why('/target/channel')} autoComplete="off" placeholder="#compute-alerts" />
              </Field>
            )}
            <FailureNote failure={cmd.failure} shownFields={['name', 'events', 'target']} compact />
          </div>
        </OverlayBody>
        <OverlayFooter>
          <Button type="button" variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          <Button type="submit" variant="primary" loading={cmd.busy}>
            Create subscription
          </Button>
        </OverlayFooter>
      </form>
    </Modal>
  )
}
