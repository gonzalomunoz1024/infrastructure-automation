import { useMemo, useRef, useState, type KeyboardEvent } from 'react'
import {
  Button,
  Card,
  Checkbox,
  EmptyState,
  Field,
  Icon,
  Input,
  Modal,
  OverlayBody,
  OverlayFooter,
  OverlayHeader,
  PageHeader,
  ProgressBar,
  Select,
  Skeleton,
  SkeletonText,
  Tabs,
  Textarea,
  plural,
} from '@iaf/ui'
import { ConfirmDialog, ErrorState, StatusBadge, When } from '@iaf/console-kit'
import { useConsole } from '../../app/console'
import { ActionButton } from '../../app/Refusal'
import { Link, href, navigate, pathFor, type PageProps } from '../../app/router'
import {
  DECISION_WORDS,
  DEFAULT_DUE_DAYS,
  REVIEW_STATUS_WORDS,
  SYSTEM_REVIEWER,
  decidableBy,
  deciderWords,
  dueWords,
  isOpen,
  progressOf,
  reviewsApi,
  type AccessReview,
  type AccessReviewItem,
} from '../../lib/accessReviews'
import { isApiError } from '../../lib/api'
import { bff } from '../../lib/bff'
import { invalidate, useQuery } from '../../lib/queries'
import { RoleTags, useAdminDecision, useIamWrite } from './common'
import { RefusedRead, usePeopleNames } from './accessTime'

/*
 * Access reviews (WP 9.11). An admin starts a campaign: it takes everyone's group memberships in
 * the account at that moment, one item per person and group, and stays open until its due date.
 * The account's admins keep or remove each item with a reason, one at a time or a selection at a
 * time, by keyboard as well as by pointer; nobody reviews their own. Whatever is not reviewed by
 * the due date expires, which takes the group off the person as a removal would (the account's
 * last owner keeps theirs), and the pages say so wherever the due date shows.
 */

const DUE_CHOICES = [7, 14, 30, 60, 90]
const DAY = 86_400_000

/** The rule of the due date, in one sentence. */
function expiryRule(dueAt: string): string {
  return `Anything not reviewed by ${dueWords(dueAt)} expires: the person loses that group, as if it were removed. The account’s last owner keeps theirs.`
}

function ReviewStatus({ review }: { review: AccessReview }) {
  const w = REVIEW_STATUS_WORDS[review.status]
  return <StatusBadge status={w.status} label={w.label} size="sm" />
}

function Progress({ review }: { review: AccessReview }) {
  const c = review.counts
  const decided = c.total - c.pending
  return (
    <div className="space-y-1.5">
      <ProgressBar value={decided} max={Math.max(1, c.total)} label={`${review.name}: reviewed`} tone={c.pending ? 'accent' : 'healthy'} />
      <p className="text-caption text-secondary" data-review-progress={`${decided}/${c.total}`}>
        {decided} of {plural(c.total, 'access', 'accesses')} reviewed ({progressOf(c)}%): {c.kept} kept, {c.revoked} removed{c.expired ? `, ${c.expired} expired` : ''}.
      </p>
    </div>
  )
}

export default function IamAccessReviewsPage(_props: PageProps) {
  const { account } = useConsole()
  const can = useAdminDecision()
  const reviews = useQuery(['iam', 'access-reviews', account.id], () => reviewsApi.list({ limit: 200 }))
  const nameOf = usePeopleNames()
  const [starting, setStarting] = useState(false)
  const items = reviews.data?.items ?? []
  const open = items.find((r) => isOpen(r))

  return (
    <>
      <PageHeader
        title="Access reviews"
        subtitle={`Campaigns in which the admins of ${account.name} confirm who should keep their access. Anything not reviewed by the due date loses it.`}
        actions={
          <ActionButton
            decision={can('access-review:create', 'Start an access review')}
            what="Start an access review"
            variant="primary"
            icon="plus"
            disabled={!!open}
            onClick={() => setStarting(true)}
          >
            Start a review
          </ActionButton>
        }
      />
      {reviews.error ? (
        <RefusedRead
          error={reviews.error}
          title="Only admins and auditors see access reviews"
          message={`Reading the campaigns of ${account.name} takes a role that may list access reviews: Admin or Auditor.`}
          what="See the access reviews"
          onRetry={() => void reviews.refetch()}
        />
      ) : reviews.loading && !reviews.data ? (
        <SkeletonText lines={4} />
      ) : items.length === 0 ? (
        <EmptyState icon="audit" title="No access review yet" message="A review asks the admins to confirm each person’s groups. The console also starts one every 90 days." />
      ) : (
        <div className="space-y-4">
          {open && <p className="text-caption text-secondary">One campaign runs at a time: a new one can start once {open.name} closes or is cancelled.</p>}
          <ul className="space-y-4" aria-label="Access review campaigns">
            {items.map((r) => (
              <li key={r.reviewId}>
                <Card>
                  <div className="flex flex-wrap items-start justify-between gap-3">
                    <div className="min-w-0">
                      <Link to={pathFor('iam-review', { id: r.reviewId })} className="text-headline font-semibold text-primary hover:underline">
                        {r.name}
                      </Link>
                      <p className="mt-0.5 text-caption text-secondary">
                        Started by {r.startedBy === SYSTEM_REVIEWER ? 'the quarterly schedule' : nameOf(r.startedBy)} <When at={r.startedAt} />
                      </p>
                    </div>
                    <ReviewStatus review={r} />
                  </div>
                  <div className="mt-3">
                    <Progress review={r} />
                  </div>
                  <p className="mt-2 text-caption text-secondary">
                    {isOpen(r) ? expiryRule(r.dueAt) : r.closedAt ? `${r.status === 'cancelled' ? 'Cancelled' : 'Closed'} ${dueWords(r.closedAt)}.` : ''}
                  </p>
                </Card>
              </li>
            ))}
          </ul>
        </div>
      )}
      {starting && <StartReviewDialog onClose={() => setStarting(false)} />}
    </>
  )
}

/* ---------------------------------------------------------------- starting one */

function StartReviewDialog({ onClose }: { onClose: () => void }) {
  const { account } = useConsole()
  const write = useIamWrite()
  const users = useQuery(['iam', 'users', account.id], () => bff.iam.users.list({ limit: 200 }))
  const groups = useQuery(['iam', 'groups', account.id], () => bff.iam.groups.list({ limit: 200 }))
  const [name, setName] = useState(`Access review ${new Date().toISOString().slice(0, 10)}`)
  const [days, setDays] = useState(String(DEFAULT_DUE_DAYS))
  const [busy, setBusy] = useState(false)
  const [conflict, setConflict] = useState<string | null>(null)
  const dueAt = new Date(Date.now() + Number(days) * DAY).toISOString()
  const people = users.data?.items.length
  const memberships = (groups.data?.items ?? []).reduce((n, g) => n + (g.members?.length ?? 0), 0)
  const empty = !name.trim()

  const start = async () => {
    if (empty) return
    setBusy(true)
    setConflict(null)
    try {
      const r = await reviewsApi.start({ name: name.trim(), dueInDays: Number(days) })
      invalidate('iam')
      onClose()
      navigate(pathFor('iam-review', { id: r.reviewId }))
    } catch (e) {
      const open = isApiError(e) && e.status === 409 ? (e.detail as { reviewId?: string } | undefined)?.reviewId : undefined
      if (open) setConflict(open)
      else await write('Start an access review', () => Promise.reject(e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <Modal onClose={onClose} width="max-w-lg" label="Start an access review">
      <form
        onSubmit={(e) => {
          e.preventDefault()
          void start()
        }}
      >
        <OverlayHeader title="Start an access review" subtitle={`The admins of ${account.name} confirm each person’s groups`} icon="audit" />
        <OverlayBody className="space-y-4">
          <Field label="Name" htmlFor="review-name" required>
            <Input id="review-name" value={name} maxLength={200} onChange={(e) => setName(e.target.value)} data-autofocus />
          </Field>
          <Field label="Due in" htmlFor="review-due" hint={`Due ${dueWords(dueAt)}.`}>
            <Select ariaLabel={`Due in: ${plural(Number(days), 'day')}`} value={days} onChange={setDays} options={DUE_CHOICES.map((d) => ({ value: String(d), label: plural(d, 'day') }))} />
          </Field>
          <dl className="space-y-2 rounded-md bg-surface-secondary p-3 text-sm">
            <div>
              <dt className="font-medium text-primary">Who is reviewed</dt>
              <dd className="text-secondary">
                Everyone with a group in {account.name}
                {people !== undefined ? `: ${plural(people, 'person', 'people')}` : ''}.
              </dd>
            </div>
            <div>
              <dt className="font-medium text-primary">What is reviewed</dt>
              <dd className="text-secondary">Each of their groups, with the roles it carries{groups.data ? `: ${plural(memberships, 'access', 'accesses')} to keep or remove` : ''}.</dd>
            </div>
            <div>
              <dt className="font-medium text-primary">Who reviews</dt>
              <dd className="text-secondary">The account’s admins, and anyone holding Admin for a while. Nobody reviews their own access.</dd>
            </div>
          </dl>
          <p className="flex gap-2 rounded-md border border-warning/40 bg-warning-tint p-3 text-sm text-primary" data-expiry-rule>
            <Icon name="clock" size={16} className="mt-0.5 shrink-0 text-warning" />
            <span>{expiryRule(dueAt)}</span>
          </p>
          {conflict && (
            <p role="alert" className="text-sm text-critical">
              A review is open already.{' '}
              <Link to={pathFor('iam-review', { id: conflict })} onClick={onClose} className="font-medium text-accent hover:underline">
                Open it
              </Link>
            </p>
          )}
        </OverlayBody>
        <OverlayFooter>
          <Button type="button" onClick={onClose}>
            Cancel
          </Button>
          <Button type="submit" variant="primary" icon="play" loading={busy} disabled={busy || empty}>
            Start the review
          </Button>
        </OverlayFooter>
      </form>
    </Modal>
  )
}

/* ---------------------------------------------------------------- one campaign */

type Tab = 'pending' | 'all'
type Verdict = 'keep' | 'revoke'

export function IamAccessReviewPage({ route }: PageProps) {
  const reviewId = route.params.id
  const { account, me } = useConsole()
  const can = useAdminDecision()
  const write = useIamWrite()
  const nameOf = usePeopleNames()
  const review = useQuery(['iam', 'access-reviews', account.id, reviewId], () => reviewsApi.get(reviewId))
  const items = useQuery(['iam', 'access-reviews', account.id, reviewId, 'items'], () => reviewsApi.items(reviewId, { limit: 200 }))
  const [tab, setTab] = useState<Tab>('pending')
  const [selected, setSelected] = useState<Set<string>>(new Set())
  const [deciding, setDeciding] = useState<{ verdict: Verdict; items: AccessReviewItem[] } | null>(null)
  const [cancelling, setCancelling] = useState(false)
  const body = useRef<HTMLTableSectionElement>(null)
  const back = { href: href(pathFor('iam-reviews')), label: 'Access reviews' }
  const all = useMemo(() => items.data?.items ?? [], [items.data])
  const rows = tab === 'pending' ? all.filter((i) => i.decision === 'pending') : all
  const r = review.data

  if (review.error) {
    return (
      <>
        <PageHeader back={back} title="Access review" />
        <ErrorState error={review.error} title="Could not load this access review" onRetry={() => void review.refetch()} />
      </>
    )
  }
  if (!r) {
    return (
      <>
        <PageHeader back={back} title={<Skeleton className="h-7 w-64" />} />
        <SkeletonText lines={4} />
      </>
    )
  }

  const open = isOpen(r)
  const decide = can('access-review:decide', 'Review access')
  const mayDecide = open && decide.allowed
  const decidable = new Set(decidableBy(all, me.id).map((i) => i.itemId))
  const picked = rows.filter((i) => selected.has(i.itemId) && decidable.has(i.itemId))
  const toggle = (id: string, on: boolean) =>
    setSelected((s) => {
      const next = new Set(s)
      if (on) next.add(id)
      else next.delete(id)
      return next
    })
  const pageDecidable = rows.filter((i) => decidable.has(i.itemId))
  const allPicked = pageDecidable.length > 0 && pageDecidable.every((i) => selected.has(i.itemId))

  // the keys of the item list: arrows move between people, K keeps, R removes
  const onKeyDown = (e: KeyboardEvent<HTMLTableSectionElement>) => {
    const target = e.target as HTMLElement
    const row = target.closest('tr')
    if (!row || !body.current) return
    const trs = Array.from(body.current.querySelectorAll('tr'))
    const index = trs.indexOf(row)
    if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
      const next = trs[index + (e.key === 'ArrowDown' ? 1 : -1)]
      if (!next) return
      e.preventDefault()
      const col = target.closest('[data-col]')?.getAttribute('data-col')
      const into =
        (col && next.querySelector<HTMLElement>(`[data-col="${col}"] button:not([disabled]), [data-col="${col}"] input:not([disabled])`)) ||
        next.querySelector<HTMLElement>('button:not([disabled]), input:not([disabled])')
      into?.focus()
      return
    }
    const key = e.key.toLowerCase()
    if ((key === 'k' || key === 'r') && !e.metaKey && !e.ctrlKey && !e.altKey && mayDecide) {
      const item = rows[index]
      if (!item || !decidable.has(item.itemId)) return
      e.preventDefault()
      setDeciding({ verdict: key === 'k' ? 'keep' : 'revoke', items: [item] })
    }
  }

  return (
    <>
      <PageHeader
        back={back}
        eyebrow="Access review"
        title={
          <span className="flex flex-wrap items-center gap-2">
            {r.name} <ReviewStatus review={r} />
          </span>
        }
        subtitle={`Started by ${r.startedBy === SYSTEM_REVIEWER ? 'the quarterly schedule' : nameOf(r.startedBy)} on ${dueWords(r.startedAt)}.`}
        actions={
          open && (
            <ActionButton decision={can('access-review:cancel', 'Cancel the access review')} what="Cancel the access review" icon="close" onClick={() => setCancelling(true)}>
              Cancel review
            </ActionButton>
          )
        }
      />
      <div className="space-y-6">
        <Card>
          <Progress review={r} />
          {open ? (
            <p className="mt-3 flex gap-2 rounded-md border border-warning/40 bg-warning-tint p-3 text-sm text-primary" data-expiry-rule>
              <Icon name="clock" size={16} className="mt-0.5 shrink-0 text-warning" />
              <span>
                Due {dueWords(r.dueAt)} (<When at={r.dueAt} />
                ). {expiryRule(r.dueAt)}
              </span>
            </p>
          ) : (
            <p className="mt-3 text-sm text-secondary">
              {r.status === 'cancelled' ? 'This review was cancelled: what nobody reviewed keeps its access.' : 'This review is closed. Every access was kept, removed, or expired at the due date.'}
            </p>
          )}
        </Card>

        <div className="flex flex-wrap items-center justify-between gap-3">
          <Tabs<Tab>
            ariaLabel="Items"
            value={tab}
            onChange={setTab}
            tabs={[
              { id: 'pending', label: 'To review', icon: 'approval', count: r.counts.pending },
              { id: 'all', label: 'All', icon: 'list', count: r.counts.total },
            ]}
          />
          {mayDecide && (
            <div className="flex flex-wrap items-center gap-2" aria-live="polite">
              <span className="text-caption text-secondary">{picked.length ? `${plural(picked.length, 'access', 'accesses')} selected` : 'Select people to decide together'}</span>
              <Button size="sm" icon="check" disabled={!picked.length} onClick={() => setDeciding({ verdict: 'keep', items: picked })}>
                Keep selected
              </Button>
              <Button size="sm" variant="danger" icon="close" disabled={!picked.length} onClick={() => setDeciding({ verdict: 'revoke', items: picked })}>
                Remove selected
              </Button>
            </div>
          )}
        </div>
        {mayDecide && <p className="text-caption text-tertiary">Keys: arrow up and down move between people, Space selects, K keeps and R removes the person in focus.</p>}
        {open && !decide.allowed && <p className="text-caption text-secondary">Only an admin of {account.name} decides; you can follow the review here.</p>}

        {items.error ? (
          <ErrorState error={items.error} title="Could not load what this review asks about" onRetry={() => void items.refetch()} />
        ) : rows.length === 0 && !items.loading ? (
          <EmptyState
            icon="check"
            iconClassName="text-healthy"
            title={tab === 'pending' ? 'Nothing left to review' : 'Nothing to review'}
            message={tab === 'pending' ? 'Every access in this review has been decided.' : 'Nobody held a group when the review started.'}
          />
        ) : (
          <div className="relative overflow-x-auto rounded-lg border border-stroke-light">
            <table className="w-full min-w-[40rem] text-left text-sm">
              <caption className="sr-only">Access to review in {r.name}</caption>
              <thead className="bg-surface-secondary text-caption text-secondary">
                <tr>
                  <th scope="col" className="w-10 px-3 py-2">
                    {mayDecide && (
                      <Checkbox
                        checked={allPicked}
                        onChange={(on) => setSelected(on ? new Set(pageDecidable.map((i) => i.itemId)) : new Set())}
                        label={<span className="sr-only">Select everyone shown</span>}
                      />
                    )}
                  </th>
                  <th scope="col" className="px-3 py-2 font-medium">
                    Person
                  </th>
                  <th scope="col" className="px-3 py-2 font-medium">
                    Group and roles
                  </th>
                  <th scope="col" className="px-3 py-2 font-medium">
                    Decision
                  </th>
                  <th scope="col" className="px-3 py-2 font-medium">
                    <span className="sr-only">Actions</span>
                  </th>
                </tr>
              </thead>
              <tbody ref={body} onKeyDown={onKeyDown} className="divide-y divide-stroke-light">
                {rows.map((i) => {
                  const person = i.name ?? i.email ?? nameOf(i.userId)
                  const own = i.userId === me.id
                  const rowDecidable = mayDecide && decidable.has(i.itemId)
                  const words = DECISION_WORDS[i.decision]
                  return (
                    <tr key={i.itemId} data-item={`${i.userId}/${i.groupName}`} className="align-top">
                      <td className="px-3 py-2.5" data-col="select">
                        {rowDecidable && (
                          <Checkbox
                            checked={selected.has(i.itemId)}
                            onChange={(on) => toggle(i.itemId, on)}
                            label={
                              <span className="sr-only">
                                Select {person} in {i.groupName}
                              </span>
                            }
                          />
                        )}
                      </td>
                      <td className="px-3 py-2.5">
                        <span className="block font-medium text-primary">{person}</span>
                        {i.email && <span className="block text-caption text-tertiary">{i.email}</span>}
                      </td>
                      <td className="px-3 py-2.5">
                        <span className="block font-mono text-caption text-primary">{i.groupName}</span>
                        <span className="mt-1 block">
                          <RoleTags roles={i.roles} />
                        </span>
                      </td>
                      <td className="px-3 py-2.5">
                        <StatusBadge status={words.status} label={words.label} size="sm" />
                        {i.decidedBy && (
                          <span className="mt-1 block text-caption text-secondary">
                            by {deciderWords(i.decidedBy, nameOf)}
                            {i.decidedAt ? ', ' : ''}
                            {i.decidedAt && <When at={i.decidedAt} />}
                          </span>
                        )}
                        {i.comment && <span className="mt-1 block text-caption text-primary">“{i.comment}”</span>}
                      </td>
                      <td className="px-3 py-2.5" data-col="actions">
                        {rowDecidable ? (
                          <span className="flex flex-wrap justify-end gap-2">
                            <Button size="sm" icon="check" data-decide="keep" onClick={() => setDeciding({ verdict: 'keep', items: [i] })}>
                              Keep
                            </Button>
                            <Button size="sm" variant="danger" icon="close" data-decide="revoke" onClick={() => setDeciding({ verdict: 'revoke', items: [i] })}>
                              Remove
                            </Button>
                          </span>
                        ) : own && open && i.decision === 'pending' ? (
                          <span className="block text-right text-caption text-tertiary">Someone else reviews your own access</span>
                        ) : null}
                      </td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
          </div>
        )}
      </div>
      {deciding && (
        <DecideDialog
          review={r}
          verdict={deciding.verdict}
          items={deciding.items}
          nameOf={nameOf}
          onClose={() => setDeciding(null)}
          onDone={(done) => {
            setSelected((s) => new Set([...s].filter((id) => !done.includes(id))))
            invalidate('iam')
          }}
        />
      )}
      {cancelling && (
        <ConfirmDialog
          title="Cancel this review?"
          message="Nothing in it expires any more: whatever nobody reviewed keeps its access. A new review can start once it is cancelled."
          confirmLabel="Cancel the review"
          cancelLabel="Keep it open"
          tone="warning"
          onConfirm={async () => {
            await write('Cancel the access review', () => reviewsApi.cancel(r.reviewId), { title: 'Review cancelled' })
          }}
          onClose={() => setCancelling(false)}
        />
      )}
    </>
  )
}

/**
 * Keeps or removes one or many items with one reason: one call per item (the identity service
 * has no batch decision), counted as they go; what could not be decided is named with its reason.
 */
function DecideDialog({
  review,
  verdict,
  items,
  nameOf,
  onClose,
  onDone,
}: {
  review: AccessReview
  verdict: Verdict
  items: AccessReviewItem[]
  nameOf: (id: string) => string
  onClose: () => void
  onDone: (itemIds: string[]) => void
}) {
  const [reason, setReason] = useState('')
  const [running, setRunning] = useState(false)
  const [done, setDone] = useState(0)
  const [failed, setFailed] = useState<{ item: AccessReviewItem; why: string }[]>([])
  const remove = verdict === 'revoke'
  const needsReason = remove && !reason.trim()
  const label = (i: AccessReviewItem) => `${i.name ?? nameOf(i.userId)} in ${i.groupName}`
  const title = `${remove ? 'Remove' : 'Keep'} ${items.length === 1 ? label(items[0]) : plural(items.length, 'access', 'accesses')}`

  const run = async () => {
    if (needsReason) return
    setRunning(true)
    setFailed([])
    const ok: string[] = []
    const bad: { item: AccessReviewItem; why: string }[] = []
    for (const i of items) {
      try {
        await reviewsApi.decide(review.reviewId, i.itemId, { decision: verdict, comment: reason.trim() || undefined })
        ok.push(i.itemId)
      } catch (e) {
        bad.push({ item: i, why: isApiError(e) ? e.error : String(e) })
      }
      setDone((n) => n + 1)
    }
    setRunning(false)
    onDone(ok)
    if (bad.length) setFailed(bad)
    else onClose()
  }

  return (
    <Modal onClose={onClose} width="max-w-lg" label={title}>
      <form
        onSubmit={(e) => {
          e.preventDefault()
          void run()
        }}
      >
        <OverlayHeader title={title} subtitle={review.name} icon={remove ? 'close-circle' : 'check'} />
        <OverlayBody className="space-y-4">
          <p className="text-sm text-secondary">
            {remove
              ? 'Each person loses the group now, and every role it gives them in this account. They can ask for access again from a refusal.'
              : 'Each person keeps the group as it is. The decision is final for this review.'}
          </p>
          {items.length > 1 && (
            <ul className="max-h-40 list-disc space-y-0.5 overflow-y-auto pl-5 text-sm text-primary">
              {items.map((i) => (
                <li key={i.itemId}>{label(i)}</li>
              ))}
            </ul>
          )}
          <Field label="Reason" htmlFor="review-reason" hint={remove ? 'Required. Kept with each decision.' : 'Optional. Kept with each decision.'} required={remove}>
            <Textarea id="review-reason" rows={3} maxLength={2000} value={reason} onChange={(e) => setReason(e.target.value)} data-autofocus />
          </Field>
          {(running || done > 0) && (
            <p role="status" className="text-caption text-secondary">
              {done} of {items.length} done
            </p>
          )}
          {failed.length > 0 && (
            <div role="alert" className="text-sm text-critical">
              <p className="font-medium">Not decided:</p>
              <ul className="mt-1 list-disc pl-5">
                {failed.map((f) => (
                  <li key={f.item.itemId}>
                    {label(f.item)}: {f.why}
                  </li>
                ))}
              </ul>
            </div>
          )}
        </OverlayBody>
        <OverlayFooter>
          <Button type="button" onClick={onClose} disabled={running}>
            {failed.length ? 'Close' : 'Cancel'}
          </Button>
          {!failed.length && (
            <Button type="submit" variant={remove ? 'danger' : 'primary'} icon={remove ? 'close' : 'check'} loading={running} disabled={running || needsReason}>
              {remove ? 'Remove access' : 'Keep access'}
            </Button>
          )}
        </OverlayFooter>
      </form>
    </Modal>
  )
}
