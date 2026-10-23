import { useEffect, useMemo, useRef } from 'react'
import { Button, Icon, IconButton, PageHeader, SkeletonRows, Tabs, Tag, cx, useToast } from '@iaf/ui'
import { CopyableId, Facts, LifecycleBadge, When, WfrnText, isLifecycleClass, mutableFields } from '@iaf/console-kit'
import { useConsole, useRouteType } from '../app/console'
import { ExplainButton } from '../app/copilot/ExplainButton'
import { ExplainedError } from '../app/commands'
import { MenuButton, MenuSeparator } from '../app/MenuButton'
import { ActionButton, ActionMenuItem } from '../app/Refusal'
import { Link, navigate, pathFor, resourcePath, type PageProps, type Route } from '../app/router'
import { formatAccountId } from '../lib/accounts'
import { api, isApiError } from '../lib/api'
import { bff } from '../lib/bff'
import { idOfWfrn, singularOf, wfrnOf, type ConsoleAction, type ConsoleType } from '../lib/descriptors'
import { ALLOWED } from '../lib/permissions'
import { isOpenStatus, usePoll } from '../lib/poll'
import { invalidate, useQuery } from '../lib/queries'
import { pushRecent } from '../lib/recent'
import { verbsOf } from '../lib/verbs'
import type { CommandAccepted } from '../lib/types'
import { costFacts } from './cost/ResourceCost'
import { MissingType } from './parts'
import { ActionDrawer } from './resource/ActionDrawer'
import { EDIT_NOT_OFFERED, EditDrawer } from './resource/EditDrawer'
import { TagsDrawer } from './resource/TagsDrawer'
import { DeletedBanner, JobBanner, tombstoneOf } from './resource/JobBanner'
import { DriftFact, TabContent, tabId } from './resource/tabs'

/*
 * One resource (WP 3.3): its facts, what is happening to it, and the tabs its descriptor lists.
 * The record comes from the platform and the console's view of it (state, the request in flight,
 * drift, tags, what this person may do) from the BFF; either alone is enough to draw the page.
 * Actions holds the type's workflows, each opening a drawer with its form, a preview and the
 * same request as code; a destructive verb sits beside it and asks for the name before it runs.
 * Edit configuration opens the type's update workflow (`?action=edit`), and Create similar opens
 * the create wizard from this resource.
 * While a request runs on the resource the page follows it and reads the record again when it
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

  const record = useQuery(['resource', type.plural, id], () => api.resourceOf(type.plural, id))
  const resource = record.data
  const named = resource ? wfrnOf(resource, type, wfrnContext) : null
  // the wfrn the link carried names the resource for the BFF; a bare id gets the derived one
  const wfrn = wfrnParam.startsWith('wfrn:') ? wfrnParam : (named?.wfrn ?? wfrnParam)

  const view = useQuery(['console-resource', type.name, wfrn], () => bff.resources.get(type.name, wfrn))
  const deleted = isApiError(view.error) && view.error.status === 410
  // a query keeps its last answer on an error: once deleted, the live view (drift, tags, verbs) is gone
  const detail = deleted ? undefined : view.data
  const tombstone = deleted && isApiError(view.error) ? tombstoneOf(view.error.body) : undefined
  const lastJob = deleted ? undefined : detail?.lastJob
  const open = isOpenStatus(lastJob?.status)

  // follow the request in flight; when it ends, read the record and its trail again
  usePoll(open, () => void view.refetch())
  const wasOpen = useRef(open)
  const refetchRecord = record.refetch
  useEffect(() => {
    if (wasOpen.current && !open) {
      void refetchRecord()
      for (const prefix of [`console-activity/${type.name}`, `console-drift/${type.name}`, 'resources', 'jobs']) invalidate(prefix)
    }
    wasOpen.current = open
  }, [open, refetchRecord, type.name])

  useEffect(() => {
    const label = detail?.name ?? resource?.name
    if (!label) return
    pushRecent({ kind: 'resource', href: resourcePath(type.service, type.name, wfrn), label, hint: `${type.title} · ${resource?.status ?? detail?.state ?? ''}` })
  }, [detail?.name, detail?.state, resource?.name, resource?.status, type, wfrn])

  // the Drift tab counts the fields that drifted, as its table lists them
  const drifted = detail?.finding?.status === 'open' ? detail.finding.fields.length : 0
  const tabs = useMemo(() => type.tabs.map((label) => ({ id: tabId(label), label, count: tabId(label) === 'drift' && drifted ? drifted : undefined })), [type.tabs, drifted])
  const tab = tabs.find((t) => t.id === route.query.tab) ?? tabs[0]
  const go = (query: Record<string, string | undefined>) => navigate(pathFor('resource', route.params, { ...route.query, ...query }, { accountId: route.accountId }), { replace: true })
  const setTab = (next: string) => go({ tab: next === tabs[0]?.id ? undefined : next })
  const openAction = (verb: string) => go({ action: verb })
  const closeAction = () => go({ action: undefined, prefill: undefined })

  const verbs = verbsOf(type)
  const operate = verbs.filter((a) => !a.destructive)
  const destructive = verbs.filter((a) => a.destructive)
  // a type whose fields are mutable but whose provider publishes no update workflow still says why nothing edits it
  const editable = !!type.update || mutableFields(type.schema).length > 0
  // `edit` is Edit configuration's own, checked before the verbs
  const editing = route.query.action === 'edit' && !deleted
  const tagging = route.query.action === 'tags' && !deleted
  const current: ConsoleAction | undefined = route.query.action && !editing && !tagging && !deleted ? verbs.find((a) => a.verb === route.query.action) : undefined

  if (!resource && !detail) {
    if (record.loading || view.loading) return <SkeletonRows n={4} />
    const why = deleted ? record.error : (view.error ?? record.error)
    return <ExplainedError error={why} what={`Load this ${noun}`} onRetry={() => void Promise.all([record.refetch(), view.refetch()])} />
  }

  const name = detail?.name ?? resource?.name ?? id
  const status = resource?.status ?? detail?.state ?? 'unknown'
  const flight = detail && (detail.state === 'pending' || detail.state === 'updating' || detail.state === 'deleting') && !deleted ? detail.state : null
  const attributes = resource?.attributes ?? detail?.attributes ?? {}
  // the BFF runs a verb only on an active or failed resource (409 otherwise): say so before the form opens
  const lifecycle = detail?.state ?? (isLifecycleClass(type.lifecycle[status]) ? type.lifecycle[status] : undefined)
  const waitNote = open ? 'Waits for the request in flight to end' : lifecycle && lifecycle !== 'active' && lifecycle !== 'failed' ? `Runs once the ${noun} is active` : undefined
  const createdBy = resource?.createdBy

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
            {named?.derived && !wfrnParam.startsWith('wfrn:') && (
              <Tag size="sm" outline title="Derived by the console from the account and region in the header, until the platform mints it">
                derived
              </Tag>
            )}
          </span>
        }
        actions={
          <>
            <ExplainButton type={type} wfrn={wfrn} name={name} deleted={deleted} />
            <IconButton label="Refresh" onClick={() => void Promise.all([record.refetch(), view.refetch()])}>
              <Icon name="refresh" size={16} className={cx((record.fetching || view.fetching) && 'animate-spin')} />
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
          { label: 'Owner', value: resource?.owner ?? (typeof attributes.owner === 'string' ? attributes.owner : undefined) },
          ...(resource ? [{ label: 'Visibility', value: resource.visibility || <span className="text-tertiary">not set</span> }] : []),
          { label: 'Created', value: <When at={resource?.createdAt ?? detail?.createdAt} /> },
          { label: 'Updated', value: <When at={resource?.updatedAt ?? detail?.updatedAt} /> },
          ...(createdBy
            ? [
                {
                  label: 'Created by',
                  value: (
                    <span className="flex flex-wrap items-center gap-x-1.5">
                      <span className="font-mono">{createdBy.workflow}</span>
                      {createdBy.version && <span className="text-tertiary">v{createdBy.version}</span>}
                      <Link to={pathFor('request', { id: createdBy.jobId })} className="font-mono text-accent transition-opacity hover:opacity-70">
                        {createdBy.jobId}
                      </Link>
                    </span>
                  ),
                },
              ]
            : []),
          ...(detail
            ? [
                {
                  label: 'Drift',
                  value: <DriftFact detail={detail} />,
                },
              ]
            : []),
          ...costFacts(type, wfrn, !!deleted),
          { label: 'Account', value: <span className="font-mono">{formatAccountId(resource?.accountId ?? wfrnContext.account)}</span> },
          { label: 'Region', value: resource?.region ?? detail?.region ?? wfrnContext.region },
          ...(detail?.providerId || resource?.providerId ? [{ label: 'Provider id', value: <CopyableId id={(detail?.providerId ?? resource?.providerId)!} /> }] : []),
        ]}
      />

      <Tabs<string> tabs={tabs} value={tab?.id ?? ''} onChange={setTab} ariaLabel={`${type.title} sections`} className="mb-5" />
      <div role="tabpanel" aria-label={tab?.label}>
        <TabContent label={tab?.label ?? ''} type={type} wfrn={wfrn} record={resource} detail={detail} viewError={view.error} />
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
      {editing && detail && <EditDrawer key="edit" type={type} wfrn={wfrn} name={name} detail={detail} onClose={closeAction} onAccepted={(accepted) => type.update && onAccepted(accepted, type.update)} />}
    </>
  )
}
