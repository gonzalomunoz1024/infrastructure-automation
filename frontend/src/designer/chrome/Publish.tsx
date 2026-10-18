/**
 * Publishing: a sheet that saves the draft and asks how to roll it out (canary with its stages,
 * or all at once) and why, then a drawer that follows the change request it opened: the checks
 * (refreshed every 2 s), the diff, Merge once the checks pass, Close. A merged request shows
 * the version it published and the release that is rolling it out.
 */
import { useEffect, useState } from 'react'
import { Button, cx, DiffView, Drawer, Icon, Input, Modal, OverlayBody, OverlayFooter, OverlayHeader, Segmented, Skeleton, SkeletonRows, Spinner, StateTag, Textarea, useOverlayClose, useToast } from '@iaf/ui'
import { api, errorMessage } from '../../lib/api'
import { invalidate, useQuery } from '../../lib/queries'
import type { ChangeRequestDetail, Check, Release, Strategy } from '../../lib/types'
import { Link, pathFor } from '../../app/router'
import { useDesignerSelector, useDesignerStore } from '../model/store'

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

function SheetBody({ onOpened }: { onOpened: (changeRequestId: string, strategy: Strategy) => void }) {
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

  const publish = async () => {
    if (!name || busy || errors > 0 || stagesInvalid) return
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
      const accepted = await api.publishWorkflow(name, { strategy, notes: notes.trim() || undefined })
      invalidate(`workflow/${name}`)
      onOpened(accepted.id, strategy)
      close()
    } catch (e) {
      toast.error('Could not publish', errorMessage(e))
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
        <Button variant="primary" icon="release" loading={busy} disabled={errors > 0 || stagesInvalid || !name} onClick={() => void publish()} data-testid="publish-confirm">
          Open change request
        </Button>
      </OverlayFooter>
    </>
  )
}

export function PublishSheet({ onClose, onOpened }: { onClose: () => void; onOpened: (changeRequestId: string, strategy: Strategy) => void }) {
  return (
    <Modal onClose={onClose} width="max-w-lg" label="Publish">
      <SheetBody onOpened={onOpened} />
    </Modal>
  )
}

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
  const releases = useQuery(['releases', workflow], () => api.releases(workflow), { refetchInterval: found ? undefined : 3000 })
  const release: Release | undefined = releases.data?.find((r) => r.to === cr.version)
  useEffect(() => {
    if (release) setFound(true)
  }, [release])
  return (
    <div className="rounded-lg border border-healthy/30 bg-healthy-tint p-4" data-testid="publish-merged">
      <p className="flex items-center gap-2 text-subheadline font-semibold text-primary">
        <Icon name="check" size={16} className="text-healthy" />
        {release && release.strategy === 'canary' ? `Published ${cr.version}, canary at ${release.stagePercent}%` : `Published ${cr.version}`}
      </p>
      <p className="mt-1 text-caption text-secondary">
        {release ? (release.strategy === 'canary' ? 'The release moves to the next stage when its gates pass.' : 'Every new job uses this version.') : releases.loading ? 'Looking up the release…' : 'New jobs pick up the new version.'}
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

function DrawerBody({ id, workflow }: { id: string; workflow: string }) {
  const toast = useToast()
  const store = useDesignerStore()
  const [acting, setActing] = useState<'merge' | 'close' | null>(null)
  const [live, setLive] = useState(true)
  const q = useQuery(['change-request', id], () => api.changeRequest(id), { refetchInterval: live ? 2000 : undefined })
  const cr = q.data
  useEffect(() => {
    if (cr && cr.status !== 'open') setLive(false)
  }, [cr])
  const checks = cr?.checks ?? []
  const failed = checks.filter((c) => c.status === 'failed').length
  const passed = checks.length > 0 && failed === 0
  const open = cr?.status === 'open'

  const act = async (kind: 'merge' | 'close') => {
    setActing(kind)
    try {
      if (kind === 'merge') await api.mergeChangeRequest(id)
      else await api.closeChangeRequest(id)
      await q.refetch()
      invalidate(`workflow/${workflow}`)
      invalidate('workflows')
      invalidate('releases')
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
            <section>
              <div className="mb-1 flex items-center gap-2">
                <h3 className="text-caption2 font-semibold uppercase tracking-wide text-tertiary">Checks</h3>
                {open && q.fetching && <Spinner size={10} />}
                <span className="flex-1" />
                <span className={cx('text-caption', failed > 0 ? 'text-critical' : passed ? 'text-healthy' : 'text-tertiary')}>{checks.length === 0 ? 'Waiting for checks…' : failed > 0 ? `${failed} failed` : `${checks.length} passed`}</span>
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
          <Button variant="primary" onClick={() => void act('merge')} loading={acting === 'merge'} disabled={!passed || acting !== null} data-testid="cr-merge">
            {passed ? 'Merge and publish' : failed > 0 ? 'Checks failed' : 'Waiting for checks'}
          </Button>
        </OverlayFooter>
      )}
    </>
  )
}

export function ChangeRequestDrawer({ id, workflow, onClose }: { id: string; workflow: string; onClose: () => void }) {
  return (
    <Drawer onClose={onClose} width="max-w-2xl" label="Change request">
      <DrawerBody id={id} workflow={workflow} />
    </Drawer>
  )
}
