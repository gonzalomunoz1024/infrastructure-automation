import { useEffect, useMemo, useRef } from 'react'
import { Button, Icon, IconButton, PageHeader, SkeletonRows, Tabs, Tag, cx, useToast } from '@iaf/ui'
import { CopyableId, Facts, LifecycleBadge, When, WfrnText, isLifecycleClass, mutableFields } from '@iaf/console-kit'
import { useConsole, useRouteType } from '../app/console'
import { ExplainButton } from '../app/copilot/ExplainButton'
import { ExplainedError } from '../app/commands'
import { MenuButton, MenuSeparator } from '../app/MenuButton'
import { ActionButton, ActionMenuItem } from '../app/Refusal'
import { navigate, pathFor, type PageProps, type Route } from '../app/router'
import { formatAccountId } from '../lib/accounts'
import { isApiError } from '../lib/api'
import { bff } from '../lib/bff'
import { idOfWfrn, partsOfWfrn, singularOf, wfrnOf, type ConsoleAction, type ConsoleType } from '../lib/descriptors'
import { ALLOWED } from '../lib/permissions'
import { isOpenStatus, usePoll } from '../lib/poll'
import { invalidate, useQuery } from '../lib/queries'
import { FavouriteStar } from './personal/FavouriteStar'
import { useFavourites, useRecordVisit } from './personal/usePersonal'
import { verbsOf } from '../lib/verbs'
import type { CommandAccepted } from '../lib/types'
import { costFacts } from './cost/ResourceCost'
import { MissingType } from './parts'
import { ActionDrawer } from './resource/ActionDrawer'
import { EDIT_NOT_OFFERED, EditDrawer } from './resource/EditDrawer'
import { RevertEdit } from './resource/RevertEdit'
import { TagsDrawer } from './resource/TagsDrawer'
import { DeletedBanner, JobBanner, tombstoneOf } from './resource/JobBanner'
import { DriftFact, TabContent, ownerOf, tabId } from './resource/tabs'
import { DriftFact, TabContent, tabId } from './resource/tabs'
import { MovedNotice, TransferDrawer, TransferMenuItem } from './resource/TransferDrawer'
import { movedOf } from '../lib/transfer'

/*
 * One resource (WP 3.3): its facts, what is happening to it, and the tabs its descriptor lists.
 * Everything comes from the BFF (GET /console/v1/r/{type}/{wfrn}): the attributes, the state and
 * what the provider last reported, the request in flight, drift, tags and what this person may do.
 * Actions holds the type's workflows, each opening a drawer with its form, a preview and the
 * same request as code; a destructive verb sits beside it and asks for the name before it runs.
 * Edit configuration opens the type's update workflow (`?action=edit`), and Create similar opens
 * the create wizard from this resource.
 * While a request runs on the resource the page follows it and reads its trail again when it
 * ends.
 */

export default function ResourceDetailPage({ route }: PageProps) {
  const { loading, error, refetch } = useConsole()
  const type = useRouteType(route.params.service, route.params.type)
  if (!type) return <MissingType name={route.params.type} service={route.params.service} loading={loading} error={error} refetch={refetch} />
  return <ResourceDetail key={route.params.wfrn} type={type} route={route} />
}

function ResourceDetail({ type, route }: { type: ConsoleType; route: Route }) {
  const { wfrnContext, decide } = useConsole()
  const toast = useToast()
  const wfrnParam = route.params.wfrn
  const id = idOfWfrn(wfrnParam) ?? wfrnParam
  const noun = singularOf(type.title).toLowerCase()

  // the wfrn the link carried names the resource for the BFF; a bare id gets the one derived from the account and region
  const derived = !wfrnParam.startsWith('wfrn:')
  const wfrn = derived ? wfrnOf({ id, type: type.name, status: '', attributes: {}, owner: '', visibility: '', createdBy: { jobId: '', workflow: '' }, builtFrom: [], createdAt: '' }, type, wfrnContext).wfrn : wfrnParam

  const view = useQuery(['console-resource', type.name, wfrn], () => bff.resources.get(type.name, wfrn))
  const deleted = isApiError(view.error) && view.error.status === 410
  // a query keeps its last answer on an error: once deleted, the live view (drift, tags, verbs) is gone
  const detail = deleted ? undefined : view.data
  const tombstone = deleted && isApiError(view.error) ? tombstoneOf(view.error.body) : undefined
  // a 410 naming where it went: the resource moved to another account (WP 7.7)
  const moved = movedOf(view.error)
  const lastJob = deleted ? undefined : detail?.lastJob
  const open = isOpenStatus(lastJob?.status)

  // follow the request in flight; when it ends, read its trail again
  usePoll(open, () => void view.refetch())
  const wasOpen = useRef(open)
  useEffect(() => {
    if (wasOpen.current && !open) {
      for (const prefix of [`console-activity/${type.name}`, `console-drift/${type.name}`, 'resources', 'jobs']) invalidate(prefix)
    }
    wasOpen.current = open
  }, [open, type.name])

  const reported = typeof detail?.observed?.status === 'string' && detail.observed.status ? detail.observed.status : undefined
  // the BFF keeps the visit for the console home (phase 7); the star makes it a favourite there
  useRecordVisit('resource', wfrn, detail?.name)
  const favourites = useFavourites()

  // the Drift tab counts the fields that drifted, as its table lists them
  const drifted = detail?.finding?.status === 'open' ? detail.finding.fields.length : 0
  const tabs = useMemo(() => type.tabs.map((label) => ({ id: tabId(label), label, count: tabId(label) === 'drift' && drifted ? drifted : undefined })), [type.tabs, drifted])
  const tab = tabs.find((t) => t.id === route.query.tab) ?? tabs[0]
  const go = (query: Record<string, string | undefined>) => navigate(pathFor('resource', route.params, { ...route.query, ...query }, { accountId: route.accountId }), { replace: true })
  const setTab = (next: string) => go({ tab: next === tabs[0]?.id ? undefined : next })
  const openAction = (verb: string) => go({ action: verb })
  const closeAction = () => go({ action: undefined, revert: undefined, prefill: undefined })

  const verbs = verbsOf(type)
  const operate = verbs.filter((a) => !a.destructive)
  const destructive = verbs.filter((a) => a.destructive)
  // a type whose fields are mutable but whose provider publishes no update workflow still says why nothing edits it
  const editable = !!type.update || mutableFields(type.schema).length > 0
  // `edit` is Edit configuration's own, checked before the verbs
  const editing = route.query.action === 'edit' && !deleted
  const tagging = route.query.action === 'tags' && !deleted
  const transferring = route.query.action === 'transfer' && !deleted
  const current: ConsoleAction | undefined = route.query.action && !editing && !tagging && !deleted ? verbs.find((a) => a.verb === route.query.action) : undefined

  if (moved) {
    return (
      <>
        <PageHeader eyebrow={type.title} title={detail?.name ?? id} subtitle={<WfrnText wfrn={wfrn} size="md" max={0} />} />
        <MovedNotice type={type} name={detail?.name ?? id} moved={moved} />
      </>
    )
  }

  if (!detail && !deleted) {
    if (view.loading) return <SkeletonRows n={4} />
    return <ExplainedError error={view.error} what={`Load this ${noun}`} onRetry={() => void view.refetch()} />
  }

  // a deleted resource keeps the name the page last read of it
  const name = detail?.name ?? view.data?.name ?? id
  // what the provider last reported (ready, building), else the lifecycle state
  const status = reported ?? detail?.state ?? (deleted ? 'deleted' : 'unknown')
  const flight = detail && (detail.state === 'pending' || detail.state === 'updating' || detail.state === 'deleting') && !deleted ? detail.state : null
  const attributes = detail?.attributes ?? {}
  // the BFF runs a verb only on an active or failed resource (409 otherwise): say so before the form opens
  const lifecycle = detail?.state ?? (isLifecycleClass(type.lifecycle[status]) ? type.lifecycle[status] : undefined)
  const waitNote = open ? 'Waits for the request in flight to end' : lifecycle && lifecycle !== 'active' && lifecycle !== 'failed' ? `Runs once the ${noun} is active` : undefined

  const onAccepted = (accepted: CommandAccepted, action: ConsoleAction) => {
    closeAction()
    toast.toast({
      tone: 'healthy',
      title: `${action.title} requested`,
      message: accepted.approval ? `It waits for an approver${accepted.approval.group ? ` from ${accepted.approval.group}` : ''}, then runs.` : `Request ${accepted.jobId} is running.`,
      action: { label: 'View request', onClick: () => navigate(pathFor('request', { id: accepted.jobId })) },
    })
    void view.refetch()
  }

  return (
    <>
      <PageHeader
        eyebrow={type.title}
        title={name}
        subtitle={
          <span className="flex flex-wrap items-center gap-2">
            <WfrnText wfrn={wfrn} size="md" max={0} />
            {derived && (
              <Tag size="sm" outline title="Derived by the console from the account and region in the header, until the platform mints it">
                derived
              </Tag>
            )}
          </span>
        }
        actions={
          <>
            {!deleted && <FavouriteStar on={favourites.isFavourite(wfrn)} name={name} onToggle={() => favourites.toggle({ wfrn, type: type.name, name })} />}
            <ExplainButton type={type} wfrn={wfrn} name={name} deleted={deleted} />
            <IconButton label="Refresh" onClick={() => void view.refetch()}>
              <Icon name="refresh" size={16} className={cx(view.fetching && 'animate-spin')} />
            </IconButton>
            {deleted ? (
              // nothing runs on a deleted resource: the menu stays where it was, disabled
              <Button variant="secondary" iconRight="chevron-down" disabled>
                Actions
              </Button>
            ) : (
              <MenuButton
                label={`Actions for ${name}`}
                align="right"
                width="w-80"
                content={
                  <>
                    {operate.map((a) => (
                      <ActionMenuItem key={a.workflow} decision={decide(type, a.verb)} what={a.title} verb={a.verb} icon="play" description={a.description} unavailable={waitNote} onSelect={() => openAction(a.verb)}>
                        {a.title}
                      </ActionMenuItem>
                    ))}
                    {operate.length > 0 && <MenuSeparator />}
                    {editable &&
                      (type.update ? (
                        <ActionMenuItem decision={decide(type, 'update')} what={`Edit this ${noun}`} verb="update" icon="edit" unavailable={waitNote} onSelect={() => openAction('edit')}>
                          Edit configuration
                        </ActionMenuItem>
                      ) : (
                        <ActionMenuItem decision={ALLOWED} what={`Edit this ${noun}`} verb="update" icon="edit" unavailable={EDIT_NOT_OFFERED}>
                          Edit configuration
                        </ActionMenuItem>
                      ))}
                    <ActionMenuItem decision={decide(type, 'update')} what="Manage tags" verb="tag" icon="tag" onSelect={() => openAction('tags')}>
                      Manage tags
                    </ActionMenuItem>
                    {type.create && (
                      <ActionMenuItem decision={decide(type, 'create')} what={`Create a ${noun} like this one`} verb="clone" icon="copy" onSelect={() => navigate(pathFor('create', { service: type.service, type: type.name }, { from: wfrn }))}>
                        Create similar
                      </ActionMenuItem>
                    )}
                    <TransferMenuItem type={type} wfrn={wfrn} unavailable={waitNote} onSelect={() => openAction('transfer')} />
                  </>
                }
              >
                Actions
              </MenuButton>
            )}
            {!deleted &&
              destructive.map((a) => (
                <ActionButton key={a.workflow} decision={decide(type, a.verb)} what={a.title} verb={a.verb} variant="danger" icon="trash" disabled={!!waitNote} onClick={() => openAction(a.verb)}>
                  {a.title}
                </ActionButton>
              ))}
          </>
        }
      />

      {deleted ? <DeletedBanner noun={noun} at={tombstone?.deletedAt} until={tombstone?.tombstoneUntil} className="mb-6" /> : <JobBanner type={type} job={lastJob} name={name} className={lastJob && (open || lastJob.status === 'failed') ? 'mb-6' : undefined} />}

      <Facts
        columns={4}
        className="mb-8"
        items={[
          { label: 'Status', value: <LifecycleBadge status={flight ?? status} lifecycle={flight ?? (isLifecycleClass(type.lifecycle[status]) ? type.lifecycle[status] : undefined)} /> },
          { label: 'Owner', value: ownerOf(detail) },
          { label: 'Created', value: <When at={detail?.createdAt} /> },
          { label: 'Updated', value: <When at={detail?.updatedAt} /> },
          ...(detail
            ? [
                {
                  label: 'Drift',
                  value: <DriftFact detail={detail} />,
                },
              ]
            : []),
          ...costFacts(type, wfrn, !!deleted),
          { label: 'Account', value: <span className="font-mono">{formatAccountId(partsOfWfrn(wfrn)?.account ?? wfrnContext.account)}</span> },
          { label: 'Region', value: detail?.region ?? partsOfWfrn(wfrn)?.region ?? wfrnContext.region },
          ...(detail?.providerId ? [{ label: 'Provider id', value: <CopyableId id={detail.providerId} /> }] : []),
        ]}
      />

      <Tabs<string> tabs={tabs} value={tab?.id ?? ''} onChange={setTab} ariaLabel={`${type.title} sections`} className="mb-5" />
      <div role="tabpanel" aria-label={tab?.label}>
        <TabContent label={tab?.label ?? ''} type={type} wfrn={wfrn} detail={detail} viewError={view.error} />
      </div>

      {current && <ActionDrawer key={current.verb} type={type} action={current} wfrn={wfrn} name={name} attributes={attributes} prefill={route.query.prefill} onClose={closeAction} onAccepted={onAccepted} />}
      {tagging && detail && (
        <TagsDrawer
          type={type}
          wfrn={wfrn}
          name={name}
          tags={detail.tags}
          onClose={closeAction}
          onSaved={() => {
            closeAction()
            void view.refetch()
          }}
        />
      )}
      {transferring && <TransferDrawer type={type} wfrn={wfrn} name={name} onClose={closeAction} />}
      {editing && detail && <EditDrawer key="edit" type={type} wfrn={wfrn} name={name} detail={detail} onClose={closeAction} onAccepted={(accepted) => type.update && onAccepted(accepted, type.update)} />}
      {editing && detail && !route.query.revert && <EditDrawer key="edit" type={type} wfrn={wfrn} name={name} detail={detail} onClose={closeAction} onAccepted={(accepted) => type.update && onAccepted(accepted, type.update)} />}
      {editing && detail && route.query.revert && <RevertEdit key={`revert-${route.query.revert}`} to={route.query.revert} type={type} wfrn={wfrn} name={name} detail={detail} onClose={closeAction} onAccepted={(accepted) => type.update && onAccepted(accepted, type.update)} />}
    </>
  )
}
