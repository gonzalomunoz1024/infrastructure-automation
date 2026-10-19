/**
 * Publishing: a sheet that saves the draft and asks how to roll it out (canary with its stages,
 * or all at once) and why, then a drawer that follows the change request it opened: the checks
 * (refreshed every 2 s), the diff, Merge once the checks pass, Close. A merged request shows
 * the version it published and the release that is rolling it out.
 *
 * A workflow that calls providers it has not published yet (its drafts) publishes them first: the
 * sheet lists each with its review (POST /providers:validate), holds Publish until every review is
 * ready and opens the provider to fix one. Publish then opens one provider change request per
 * draft (POST /providers:publish) and the workflow's, which requires them. The drawer lists those
 * linked change requests and follows any of them; the workflow's merges once they have.
 */
import { useEffect, useState } from 'react'
import { Button, cx, DiffView, Drawer, Icon, Input, Modal, OverlayBody, OverlayFooter, OverlayHeader, Segmented, Skeleton, SkeletonRows, Spinner, StateTag, Tag, Textarea, useOverlayClose, useToast } from '@iaf/ui'
import { api, errorMessage, ownChange, useCurrentUser } from '../../lib/api'
import { invalidate, useQuery } from '../../lib/queries'
import type { ChangeRequest, ChangeRequestDetail, Check, CommandAccepted, ProviderValidation, PublishRequest, Release, Strategy } from '../../lib/types'
import { Link, pathFor } from '../../app/router'
import { checksSummary } from '../../components/ChangeRequestTable'
import { draftFile, type ProviderDraft } from '../model/providerDrafts'
import { useDesignerSelector, useDesignerStore, type DesignerStore, type ProviderInReview } from '../model/store'

function parseStages(text: string): number[] | null {
  const parts = text
    .split(/[\s,]+/)
    .map((s) => s.replace('%', '').trim())
    .filter(Boolean)
  if (parts.length === 0) return null
  const nums = parts.map(Number)
  if (nums.some((n) => !Number.isFinite(n) || n <= 0 || n > 100)) return null
  for (let i = 1; i < nums.length; i++) if (nums[i] <= nums[i - 1]) return null
  if (nums[nums.length - 1] !== 100) return null
  return nums
}

/* ----------------------------------------------------------------------------
   The providers a Publish opens first
   ------------------------------------------------------------------------- */

/** The review of a provider before Publish: running, ready, what blocks it, or why it did not run. */
export type ProviderReviewState = { state: 'checking' } | { state: 'ready' } | { state: 'blocked'; reasons: string[] } | { state: 'failed'; message: string }

/**
 * A draft's review (POST /providers:validate) as the Publish sheet judges it, the way the wizard's
 * Review step does: ready when no check failed, no problem is an error and the review of the
 * evidence is ready; else each failed check and error, the file's and the review's, once.
 */
export function reviewOf(v: ProviderValidation): ProviderReviewState {
  const reasons: string[] = []
  const add = (reason: string) => {
    if (!reasons.includes(reason)) reasons.push(reason)
  }
  const failedCheck = (c: Check) => {
    if (c.status === 'failed') add(c.detail ? `${c.label}: ${c.detail}` : c.label)
  }
  v.checks.forEach(failedCheck)
  for (const p of v.problems) if (p.level === 'error') add(p.message)
  if (v.review) {
    v.review.checks.forEach(failedCheck)
    for (const p of v.review.problems) if (p.level === 'error') add(p.message)
    if (!v.review.ready && reasons.length === 0) add('The review of the evidence is not ready yet.')
  }
  return reasons.length === 0 ? { state: 'ready' } : { state: 'blocked', reasons }
}

/** One provider the sheet lists: a draft to publish with its review, or one whose change request is open. */
export interface ProviderRow {
  /** the draft's id, or the change request's for one in review */
  key: string
  draft: ProviderDraft
  /** null once its change request is open */
  review: ProviderReviewState | null
  /** the change request opened for it, by this sheet or a Publish before */
  changeRequest: string | null
}

function RowStatus({ row }: { row: ProviderRow }) {
  if (row.changeRequest)
    return (
      <Tag tone="accent" icon="git-branch" size="sm" mono>
        {row.changeRequest}
      </Tag>
    )
  switch (row.review?.state) {
    case 'ready':
      return (
        <Tag tone="healthy" dot size="sm">
          Ready
        </Tag>
      )
    case 'blocked':
      return (
        <Tag tone="warning" dot size="sm">
          Not ready
        </Tag>
      )
    case 'failed':
      return (
        <Tag tone="critical" dot size="sm">
          Checks did not run
        </Tag>
      )
    default:
      return (
        <span className="inline-flex items-center gap-1.5 text-caption text-tertiary">
          <Spinner size={10} />
          Checking
        </span>
      )
  }
}

/** What the row says under the title: the name, a new provider or the next version of a published one, and an open change request. */
function rowDetail(row: ProviderRow): string {
  const d = row.draft
  const what = d.origin === 'edit' && d.publishedVersion ? `${d.version}, next after ${d.publishedVersion}` : `${d.version}, new`
  return row.changeRequest ? `${what} · its change request merges first` : what
}

/**
 * The providers to publish first, each with its review: ready, what blocks it with "Open
 * provider" to fix it in the Provider sheet, or the checks that did not run with "Check again".
 */
export function ProviderRows({ rows, onOpen, onRetry }: { rows: readonly ProviderRow[]; onOpen: (draft: ProviderDraft) => void; onRetry: () => void }) {
  return (
    <ul className="divide-y divide-stroke-light rounded-lg border border-stroke-light" aria-label="Providers to publish first" data-testid="publish-providers">
      {rows.map((row) => (
        <li key={row.key} className="space-y-2 px-3 py-2.5" data-provider={row.draft.name}>
          <div className="flex items-start gap-2.5">
            <span className="mt-0.5 flex h-6 w-6 shrink-0 items-center justify-center rounded-sm bg-surface-secondary">
              <Icon name="provider" size={14} className="text-secondary" />
            </span>
            <div className="min-w-0 flex-1">
              <p className="flex min-w-0 items-center gap-1.5 text-footnote font-medium text-primary">
                <span className="truncate">{row.draft.title.trim() || row.draft.name}</span>
                <Tag size="sm" outline>
                  {row.changeRequest ? 'in review' : 'draft'}
                </Tag>
              </p>
              <p className="truncate text-caption text-tertiary">
                <span className="font-mono">{row.draft.name}</span> {rowDetail(row)}
              </p>
            </div>
            <span className="shrink-0">
              <RowStatus row={row} />
            </span>
          </div>
          {row.review?.state === 'blocked' && (
            <div className="ml-8 space-y-2">
              <ul className="space-y-1" aria-label={`What blocks ${row.draft.name}`}>
                {row.review.reasons.map((r) => (
                  <li key={r} className="flex items-start gap-1.5 text-caption text-secondary">
                    <Icon name="close" size={12} className="mt-0.5 shrink-0 text-critical" />
                    <span className="min-w-0">{r}</span>
                  </li>
                ))}
              </ul>
              <Button size="sm" variant="secondary" icon="edit" onClick={() => onOpen(row.draft)}>
                Open provider
              </Button>
            </div>
          )}
          {row.review?.state === 'failed' && (
            <div className="ml-8 space-y-2">
              <p className="text-caption text-critical">{row.review.message}</p>
              <div className="flex gap-2">
                <Button size="sm" variant="secondary" icon="refresh" onClick={onRetry}>
                  Check again
                </Button>
                <Button size="sm" variant="ghost" icon="edit" onClick={() => onOpen(row.draft)}>
                  Open provider
                </Button>
              </div>
            </div>
          )}
        </li>
      ))}
    </ul>
  )
}

/** A draft whose publish failed: the Publish stops there and says which. */
export class ProviderPublishError extends Error {
  readonly provider: string
  constructor(provider: string, cause: unknown) {
    super(errorMessage(cause))
    this.name = 'ProviderPublishError'
    this.provider = provider
  }
}

/** What one Publish opens: the workflow's request, its drafts with their files, and the change requests in review it requires. */
export interface PublishPlan {
  workflow: string
  request: Omit<PublishRequest, 'requires'>
  drafts: readonly { draft: ProviderDraft; yaml: string }[]
  inReview: readonly string[]
}

/**
 * Opens a Publish's change requests: one per draft (POST /providers:publish with the draft's
 * strategy), each handed to `onProvider` as it opens, then the workflow's, requiring every
 * provider change request (the drafts', then the ones in review). A draft that cannot be
 * published stops it before the workflow's (`ProviderPublishError`); the ones opened stay open.
 */
export async function openChangeRequests(plan: PublishPlan, onProvider: (draft: ProviderDraft, changeRequest: string) => void): Promise<CommandAccepted> {
  const opened: string[] = []
  for (const { draft, yaml } of plan.drafts) {
    let accepted: CommandAccepted
    try {
      accepted = await api.publishProvider(yaml, draft.strategy)
    } catch (e) {
      throw new ProviderPublishError(draft.name, e)
    }
    opened.push(accepted.id)
    onProvider(draft, accepted.id)
  }
  return api.publishWorkflow(plan.workflow, { ...plan.request, requires: [...opened, ...plan.inReview] })
}

/** What a Publish opens first, as the store has it now: the drafts the workflow calls with their files, and the providers in review it requires. */
export interface ProvidersFirst {
  drafts: { draft: ProviderDraft; yaml: string }[]
  inReview: ProviderInReview[]
}

export function providersFirst(store: Pick<DesignerStore, 'usedProviderDrafts' | 'usedProvidersInReview'>): ProvidersFirst {
  // the drafts come under the workflow's owner already: their file is the one a dry run sends
  return { drafts: store.usedProviderDrafts().map((draft) => ({ draft, yaml: draftFile(draft, null) })), inReview: store.usedProvidersInReview() }
}

/**
 * Opens a Publish's change requests from what the store has now (`providersFirst`). A draft whose
 * change request opened is cleared at once and stays in review (`providerDraftPublished`), so a
 * Publish tried again after one failed opens only the others and requires the ones opened.
 */
export function publishWithProviders(store: DesignerStore, workflow: string, request: Omit<PublishRequest, 'requires'>): Promise<CommandAccepted> {
  const { drafts, inReview } = providersFirst(store)
  return openChangeRequests({ workflow, request, drafts, inReview: inReview.map((r) => r.changeRequest) }, (draft, changeRequest) => {
    store.providerDraftPublished(draft.id, changeRequest)
    invalidate('provider')
  })
}

/** What the sheet says when a Publish fails: the provider that stopped it before the workflow's change request, or why. */
export function publishFailure(e: unknown): { title: string; message: string } {
  if (e instanceof ProviderPublishError) return { title: `Could not publish ${e.provider}`, message: `${e.message.replace(/[.\s]*$/, '.')} The workflow's change request was not opened.` }
  return { title: 'Could not publish', message: errorMessage(e) }
}

/** The rows the sheet lists: each draft with its review (in order), then each provider in review with its change request. */
export function providerRows(first: ProvidersFirst, reviews: readonly ProviderReviewState[]): ProviderRow[] {
  return [
    ...first.drafts.map((p, i) => ({ key: p.draft.id, draft: p.draft, review: reviews[i] ?? { state: 'checking' as const }, changeRequest: null })),
    ...first.inReview.map((r) => ({ key: r.changeRequest, draft: r.draft, review: null, changeRequest: r.changeRequest })),
  ]
}

/** Whether the sheet holds Publish: no workflow name, an error to fix, stages that do not parse, a review still running or one not ready. */
export function publishHold({ name, errors, stagesInvalid, reviews }: { name: string | null; errors: number; stagesInvalid: boolean; reviews: readonly ProviderReviewState[] }): { reviewing: boolean; notReady: number; disabled: boolean } {
  const reviewing = reviews.some((r) => r.state === 'checking')
  const notReady = reviews.filter((r) => r.state === 'blocked' || r.state === 'failed').length
  return { reviewing, notReady, disabled: !name || errors > 0 || stagesInvalid || reviewing || notReady > 0 }
}

/** "Open provider": the Provider sheet on the draft, on its published provider when it is the next patch of one. */
export function openDraftProvider(store: Pick<DesignerStore, 'openProviderSheet'>, draft: ProviderDraft): void {
  store.openProviderSheet({ entry: 'publish', draft: draft.id, provider: draft.origin === 'edit' ? (draft.editing ?? draft.name) : null })
}

/** The sheet's section on the providers to publish first: each with its review, and why Publish waits while one is not ready. */
export function ProvidersToPublish({ rows, notReady, onOpen, onRetry }: { rows: readonly ProviderRow[]; notReady: number; onOpen: (draft: ProviderDraft) => void; onRetry: () => void }) {
  return (
    <section>
      <p className="mb-1 text-caption font-medium text-secondary">Providers to publish first</p>
      <p className="mb-2 text-caption text-tertiary">
        This workflow calls {rows.length === 1 ? 'a provider that is' : `${rows.length} providers that are`} not published yet. Each gets its own change request, which merges first; this workflow&apos;s change request requires {rows.length === 1 ? 'it' : 'them'}.
      </p>
      <ProviderRows rows={rows} onOpen={onOpen} onRetry={onRetry} />
      {notReady > 0 && (
        <p className="mt-2 text-caption text-warning" role="status">
          Publishing waits until every provider&apos;s review is ready.
        </p>
      )}
    </section>
  )
}

/* ----------------------------------------------------------------------------
   The sheet
   ------------------------------------------------------------------------- */

/** The sheet's content, outside its modal (tests render it on its own). */
export function PublishSheetBody({ onOpened }: { onOpened: (changeRequestId: string, strategy: Strategy) => void }) {
  const store = useDesignerStore()
  const toast = useToast()
  const close = useOverlayClose()
  const doc = useDesignerSelector((s) => s.doc)
  const name = useDesignerSelector((s) => s.name)
  const dirty = useDesignerSelector((s) => s.dirty)
  const detail = useDesignerSelector((s) => s.detail)
  const problems = useDesignerSelector((s) => s.problems)
  const [strategy, setStrategy] = useState<Strategy>(doc.rollout?.strategy === 'all' ? 'all' : 'canary')
  const [stagesText, setStagesText] = useState((doc.rollout?.stages ?? [10, 50, 100]).join(', '))
  const [notes, setNotes] = useState('')
  const [busy, setBusy] = useState(false)
  const errors = problems.filter((p) => p.level === 'error').length
  const stages = parseStages(stagesText)
  const stagesInvalid = strategy === 'canary' && stages === null

  // what the workflow calls before it is published, as the store has it now: a draft whose change
  // request this sheet opened moves into review (a Publish tried again opens only the others)
  useDesignerSelector((s) => s.providerDrafts)
  useDesignerSelector((s) => s.providersInReview)
  const first = providersFirst(store)
  // how each provider in review stands now: one closed since is a draft again, one merged is published
  useEffect(() => {
    void store.refreshProvidersInReview()
  }, [store])
  const reviews = useQuery(
    ['provider-reviews', name ?? '', ...first.drafts.map((p) => p.draft.id)],
    () => Promise.all(first.drafts.map((p) => api.validateProvider(p.yaml).then(reviewOf, (e): ProviderReviewState => ({ state: 'failed', message: errorMessage(e) })))),
    { enabled: first.drafts.length > 0 },
  )
  // a review on screen is judged again whenever the sheet asks: until it answers, it is checking
  const reviewStates = first.drafts.map((_, i): ProviderReviewState => (reviews.fetching || !reviews.data ? { state: 'checking' } : (reviews.data[i] ?? { state: 'checking' })))
  const rows = providerRows(first, reviewStates)
  const hold = publishHold({ name, errors, stagesInvalid, reviews: reviewStates })

  const openProvider = (draft: ProviderDraft) => {
    close()
    openDraftProvider(store, draft)
  }

  const publish = async () => {
    if (!name || busy || hold.disabled) return
    setBusy(true)
    try {
      // the rollout belongs to the workflow file: write it, then save the draft the request is opened from
      const current = store.getState().doc.rollout
      const wantStages = strategy === 'canary' ? stages : current?.stages
      if (current?.strategy !== strategy || JSON.stringify(current?.stages) !== JSON.stringify(wantStages)) {
        store.update((d) => {
          d.rollout = { ...(d.rollout ?? {}), strategy, ...(wantStages ? { stages: wantStages } : {}) }
        })
      }
      if (store.getState().dirty) await store.save()
      // each draft is cleared once its change request is open; the canvas keeps the provider, in review
      const accepted = await publishWithProviders(store, name, { strategy, notes: notes.trim() || undefined })
      invalidate(`workflow/${name}`)
      onOpened(accepted.id, strategy)
      close()
    } catch (e) {
      const failure = publishFailure(e)
      toast.error(failure.title, failure.message)
    } finally {
      setBusy(false)
    }
  }

  return (
    <>
      <OverlayHeader title={`Publish ${doc.metadata?.title ?? name ?? ''}`} subtitle={detail?.publishedVersion ? `Published today: ${detail.publishedVersion}. This opens a change request for the next version.` : 'This opens a change request for the first version.'} icon="release" />
      <OverlayBody className="space-y-5">
        {errors > 0 && (
          <div className="flex items-start gap-2 rounded-md bg-critical-tint px-3 py-2 text-caption text-critical" role="alert">
            <Icon name="alert-triangle" size={14} className="mt-0.5 shrink-0" />
            {errors} {errors === 1 ? 'problem stops' : 'problems stop'} this workflow from publishing; fix {errors === 1 ? 'it' : 'them'} first (see Issues).
          </div>
        )}
        {dirty && errors === 0 && <p className="text-caption text-secondary">Your unsaved changes are saved to the draft first.</p>}
        {rows.length > 0 && <ProvidersToPublish rows={rows} notReady={hold.notReady} onOpen={openProvider} onRetry={() => void reviews.refetch()} />}
        <div>
          <p className="mb-1.5 text-caption font-medium text-secondary">Roll out</p>
          <Segmented<Strategy>
            value={strategy}
            onChange={setStrategy}
            ariaLabel="Rollout"
            full
            options={[
              { value: 'canary', label: 'Canary' },
              { value: 'all', label: 'All at once' },
            ]}
          />
          <p className="mt-1.5 text-caption text-tertiary">{strategy === 'canary' ? 'New jobs move to the new version in stages; each stage waits for its gates (success rate, latency) before the next.' : 'Every new job uses the new version as soon as the request is merged.'}</p>
        </div>
        {strategy === 'canary' && (
          <div>
            <label htmlFor="publish-stages" className="mb-1.5 block text-caption font-medium text-secondary">
              Stages (% of new jobs)
            </label>
            <Input id="publish-stages" mono value={stagesText} onChange={(e) => setStagesText(e.target.value)} invalid={stagesInvalid} placeholder="10, 50, 100" />
            {stagesInvalid ? (
              <p className="mt-1 text-caption2 text-critical">Ascending percentages ending at 100, like 10, 50, 100.</p>
            ) : (
              <div className="mt-2 flex items-center gap-1.5">
                {stages?.map((s, i) => (
                  <span key={i} className="flex items-center gap-1.5">
                    {i > 0 && <Icon name="chevron-right" size={12} className="text-tertiary" />}
                    <span className="rounded-full bg-accent-tint px-2 py-0.5 text-caption font-medium text-accent">{s}%</span>
                  </span>
                ))}
              </div>
            )}
          </div>
        )}
        <div>
          <label htmlFor="publish-notes" className="mb-1.5 block text-caption font-medium text-secondary">
            Notes
          </label>
          <Textarea id="publish-notes" rows={3} value={notes} onChange={(e) => setNotes(e.target.value)} placeholder="What changed and why; reviewers read this on the change request" />
        </div>
      </OverlayBody>
      <OverlayFooter>
        <Button variant="ghost" onClick={close}>
          Cancel
        </Button>
        <Button variant="primary" icon="release" loading={busy} disabled={hold.disabled} onClick={() => void publish()} data-testid="publish-confirm">
          {first.drafts.length > 0 ? 'Open change requests' : 'Open change request'}
        </Button>
      </OverlayFooter>
    </>
  )
}

export function PublishSheet({ onClose, onOpened }: { onClose: () => void; onOpened: (changeRequestId: string, strategy: Strategy) => void }) {
  return (
    <Modal onClose={onClose} width="max-w-lg" label="Publish">
      <PublishSheetBody onOpened={onOpened} />
    </Modal>
  )
}

/* ----------------------------------------------------------------------------
   The drawer
   ------------------------------------------------------------------------- */

function CheckRow({ check }: { check: Check }) {
  const icon = check.status === 'passed' ? <Icon name="check" size={14} className="text-healthy" /> : check.status === 'failed' ? <Icon name="close" size={14} className="text-critical" /> : <Icon name="minus" size={14} className="text-tertiary" />
  return (
    <li className="flex items-start gap-2.5 py-2">
      <span className="mt-0.5 flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-surface-secondary">{icon}</span>
      <div className="min-w-0">
        <p className="text-footnote font-medium text-primary">{check.label}</p>
        {check.detail && <p className="text-caption text-tertiary">{check.detail}</p>}
      </div>
    </li>
  )
}

function Merged({ cr, workflow }: { cr: ChangeRequestDetail; workflow: string }) {
  const [found, setFound] = useState(false)
  const provider = cr.kind === 'provider'
  const releases = useQuery(['releases', provider ? `provider:${cr.name}` : workflow], () => api.releases(provider ? { provider: cr.name } : { workflow }), { refetchInterval: found ? undefined : 3000 })
  const release: Release | undefined = releases.data?.find((r) => r.to === cr.version)
  // the poll stops once the release is there (adjusted during render, React's derived-state pattern)
  if (release && !found) setFound(true)
  const all = provider ? 'Every new call uses this version.' : 'Every new job uses this version.'
  return (
    <div className="rounded-lg border border-healthy/30 bg-healthy-tint p-4" data-testid="publish-merged">
      <p className="flex items-center gap-2 text-subheadline font-semibold text-primary">
        <Icon name="check" size={16} className="text-healthy" />
        {release && release.strategy === 'canary' ? `Published ${cr.version}, canary at ${release.stagePercent}%` : `Published ${cr.version}`}
      </p>
      <p className="mt-1 text-caption text-secondary">
        {release ? (release.strategy === 'canary' ? 'The release moves to the next stage when its gates pass.' : all) : releases.loading ? 'Looking up the release…' : provider ? 'New calls pick up the new version.' : 'New jobs pick up the new version.'}
      </p>
      {release && (
        <Link to={pathFor('release', { id: release.id })} className="mt-2 inline-flex items-center gap-1 text-footnote font-medium text-accent hover:underline">
          Follow the release
          <Icon name="arrow-right" size={13} />
        </Link>
      )}
    </div>
  )
}

/** Ids as a sentence lists them: `cr_8`, `cr_8 and cr_9`, `cr_7, cr_8 and cr_9`. */
const listed = (ids: readonly string[]): string => (ids.length < 2 ? (ids[0] ?? '') : `${ids.slice(0, -1).join(', ')} and ${ids[ids.length - 1]}`)

/** The change requests a workflow's requires that were closed without merging: it can never merge. */
export function closedOf(ids: readonly string[], changes: readonly ChangeRequest[] | undefined): string[] {
  return ids.filter((id) => changes?.find((c) => c.id === id)?.status === 'closed')
}

/**
 * The change requests a workflow's requires, each with its status and checks: the provider change
 * requests the same Publish opened, which merge first. One that was closed instead stops this one
 * for good: it says so. One opens in the drawer to follow it.
 */
export function LinkedChangeRequests({ ids, changes, onFollow }: { ids: readonly string[]; changes: readonly ChangeRequestDetail[] | undefined; onFollow: (id: string) => void }) {
  const waiting = ids.filter((id) => changes?.find((c) => c.id === id)?.status !== 'merged').length
  const closed = closedOf(ids, changes)
  return (
    <section data-testid="cr-linked">
      <h3 className="mb-1 text-caption2 font-semibold uppercase tracking-wide text-tertiary">Linked change requests</h3>
      <p className={cx('mb-2 text-caption', closed.length > 0 ? 'text-critical' : 'text-secondary')}>
        {closed.length > 0
          ? `${listed(closed)} ${closed.length === 1 ? 'was' : 'were'} closed: this change request cannot merge. Close it, fix the ${closed.length === 1 ? 'provider' : 'providers'} and publish again.`
          : waiting === 0
            ? 'The providers this version calls are published: it can merge.'
            : `The provider change requests merge first: this one merges once ${ids.length === 1 ? 'it has' : 'they have'}.`}
      </p>
      <ul className="divide-y divide-stroke-light rounded-lg border border-stroke-light">
        {ids.map((id) => {
          const c = changes?.find((x) => x.id === id)
          return (
            <li key={id}>
              <button type="button" onClick={() => onFollow(id)} className="flex w-full items-center gap-3 rounded-lg px-3 py-2.5 text-left hover:bg-surface-secondary" aria-label={`Follow ${c ? `${c.name} ${c.version}` : id}`}>
                <Icon name={c?.kind === 'workflow' ? 'workflow' : 'provider'} size={14} className="shrink-0 text-tertiary" />
                <span className="min-w-0 flex-1">
                  <span className="block truncate text-footnote font-medium text-primary">{c ? `${c.name} ${c.version}` : id}</span>
                  <span className="block truncate text-caption text-tertiary">{c ? `${c.kind === 'provider' ? 'Provider' : 'Workflow'} · ${c.id} · checks ${checksSummary(c.checks)}` : 'Loading…'}</span>
                </span>
                {c && <StateTag status={c.status} size="sm" />}
                <Icon name="chevron-right" size={14} className="shrink-0 text-tertiary" />
              </button>
            </li>
          )
        })}
      </ul>
    </section>
  )
}

/**
 * A provider change request's outcome, for the canvas: merged, the provider is published (the
 * registry loads again); closed, its draft is the workflow's again. Nothing for a workflow's or
 * an open one.
 */
export function settleProvider(store: Pick<DesignerStore, 'providerChangeMerged' | 'providerChangeClosed'>, c: Pick<ChangeRequest, 'id' | 'kind' | 'status'>): void {
  if (c.kind !== 'provider') return
  if (c.status === 'merged') void store.providerChangeMerged(c.id)
  else if (c.status === 'closed') store.providerChangeClosed(c.id)
}

function DrawerBody({ id, workflow, onFollow, onBack }: { id: string; workflow: string; onFollow?: (id: string) => void; onBack?: () => void }) {
  const toast = useToast()
  const store = useDesignerStore()
  const [acting, setActing] = useState<'merge' | 'close' | null>(null)
  const [live, setLive] = useState(true)
  const q = useQuery(['change-request', id], () => api.changeRequest(id), { refetchInterval: live ? 2000 : undefined })
  const cr = q.data
  // the poll stops once the request is merged or closed
  if (live && cr && cr.status !== 'open') setLive(false)
  const checks = cr?.checks ?? []
  const failed = checks.filter((c) => c.status === 'failed').length
  const passed = checks.length > 0 && failed === 0
  const open = cr?.status === 'open'
  const user = useCurrentUser()
  const me = useQuery(['me', user ?? 'default'], () => api.me())
  // the author waits for a teammate: the backend refuses their own merge
  const own = ownChange(me.data, cr?.author)

  // the change requests this one requires, followed until every one is merged or closed
  const requires = cr?.requires ?? []
  const [linkedLive, setLinkedLive] = useState(true)
  const linked = useQuery(['change-requests-linked', id, ...requires], () => Promise.all(requires.map((r) => api.changeRequest(r))), { enabled: requires.length > 0, refetchInterval: linkedLive ? 2000 : undefined })
  if (linkedLive && linked.data && linked.data.length > 0 && linked.data.every((c) => c.status !== 'open')) setLinkedLive(false)
  const waiting = requires.filter((r) => linked.data?.find((c) => c.id === r)?.status !== 'merged').length
  const closedRequired = closedOf(requires, linked.data)
  // a provider change request merged or closed, this one or a linked one: the canvas follows
  useEffect(() => {
    for (const c of linked.data ?? []) settleProvider(store, c)
  }, [linked.data, store])
  useEffect(() => {
    if (cr) settleProvider(store, cr)
  }, [cr, store])

  const act = async (kind: 'merge' | 'close') => {
    setActing(kind)
    try {
      if (kind === 'merge') await api.mergeChangeRequest(id)
      else await api.closeChangeRequest(id)
      await q.refetch()
      invalidate('releases')
      if (cr?.kind === 'provider') {
        invalidate('provider')
        if (kind === 'merge') {
          void store.providerChangeMerged(id)
          toast.success('Merged', `${cr.name} ${cr.version} is published`)
        } else if (store.providerChangeClosed(id)) {
          toast.success('Closed', `${cr.name} is a draft of this workflow again: fix it and publish again.`)
        }
        return
      }
      invalidate(`workflow/${workflow}`)
      invalidate('workflows')
      if (kind === 'merge') {
        void api.workflow(workflow).then((d) => store.setDetail(d), () => {})
        toast.success('Merged', `${workflow} ${cr?.version ?? ''} is published`)
      }
    } catch (e) {
      toast.error(kind === 'merge' ? 'Could not merge' : 'Could not close', errorMessage(e))
    } finally {
      setActing(null)
    }
  }

  return (
    <>
      <OverlayHeader
        title={cr ? cr.title : 'Change request'}
        subtitle={cr ? `${cr.name} ${cr.version} · ${cr.branch} · by ${cr.author}` : 'Opening…'}
        icon="git-branch"
        actions={cr ? <StateTag status={cr.status} /> : undefined}
      />
      <OverlayBody className="space-y-5">
        {onBack && (
          <Button size="sm" variant="ghost" icon="arrow-left" onClick={onBack}>
            Back to the workflow&apos;s change request
          </Button>
        )}
        {q.loading && !cr ? (
          <div className="space-y-4">
            <Skeleton className="h-5 w-1/3" />
            <SkeletonRows n={4} />
          </div>
        ) : q.error && !cr ? (
          <p className="text-footnote text-critical">{errorMessage(q.error)}</p>
        ) : cr ? (
          <>
            {cr.status === 'merged' && <Merged cr={cr} workflow={workflow} />}
            {cr.status === 'closed' && <p className="rounded-md bg-surface-secondary px-3 py-2 text-footnote text-secondary">Closed without publishing.</p>}
            {requires.length > 0 && <LinkedChangeRequests ids={requires} changes={linked.data} onFollow={(r) => onFollow?.(r)} />}
            <section>
              <div className="mb-1 flex items-center gap-2">
                <h3 className="text-caption2 font-semibold uppercase tracking-wide text-tertiary">Checks</h3>
                {open && q.fetching && <Spinner size={10} />}
                <span className="flex-1" />
                <span className={cx('text-caption', failed > 0 ? 'text-critical' : passed ? 'text-healthy' : 'text-tertiary')}>{checks.length === 0 ? 'Waiting for checks…' : failed > 0 ? `${failed} failed` : checksSummary(checks)}</span>
              </div>
              <ul className="divide-y divide-stroke-light" data-testid="cr-checks">
                {checks.map((c) => (
                  <CheckRow key={c.id} check={c} />
                ))}
              </ul>
            </section>
            <section>
              <h3 className="mb-2 text-caption2 font-semibold uppercase tracking-wide text-tertiary">Changes</h3>
              {cr.diff ? <DiffView diff={cr.diff} maxHeight="max-h-[360px]" /> : <p className="text-caption text-tertiary">No changes against the published version.</p>}
            </section>
            <Link to={pathFor('change-request', { id: cr.id })} className="inline-flex items-center gap-1 text-footnote font-medium text-accent hover:underline">
              Open the change request
              <Icon name="external" size={12} />
            </Link>
          </>
        ) : null}
      </OverlayBody>
      {open && (
        <OverlayFooter>
          <Button variant="ghost" onClick={() => void act('close')} loading={acting === 'close'} disabled={acting !== null}>
            Close request
          </Button>
          <Button
            variant="primary"
            onClick={() => void act('merge')}
            loading={acting === 'merge'}
            disabled={!passed || own || waiting > 0 || acting !== null}
            title={
              closedRequired.length > 0
                ? `${listed(closedRequired)} ${closedRequired.length === 1 ? 'was' : 'were'} closed: close this change request, fix the provider and publish again`
                : own
                  ? 'You opened it: someone else on the team merges it'
                  : waiting > 0
                    ? 'The change requests it requires merge first'
                    : undefined
            }
            data-testid="cr-merge"
          >
            {closedRequired.length > 0 ? 'Cannot merge' : own ? 'A teammate merges it' : waiting > 0 ? 'Merge the providers first' : passed ? 'Merge and publish' : failed > 0 ? 'Checks failed' : 'Waiting for checks'}
          </Button>
        </OverlayFooter>
      )}
    </>
  )
}

/** Follows a workflow's change request, and from it any change request it requires (with a way back). */
export function ChangeRequestDrawer({ id, workflow, onClose }: { id: string; workflow: string; onClose: () => void }) {
  const [shown, setShown] = useState<string | null>(null)
  return (
    <Drawer onClose={onClose} width="max-w-2xl" label="Change request">
      {shown ? <DrawerBody key={shown} id={shown} workflow={workflow} onBack={() => setShown(null)} /> : <DrawerBody key={id} id={id} workflow={workflow} onFollow={setShown} />}
    </Drawer>
  )
}
