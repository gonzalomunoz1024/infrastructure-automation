/**
 * The Provider tab of a provider step: how the provider behind it is doing, from the provider
 * page. Its health band with the reasons the server gave, the success rate and p95 against its
 * SLO, the step's action's own band, the alerts (the action's first), the release in flight and the
 * open change requests, with Edit SLO and Refresh spec (each a change request, as on the provider
 * page, held back while the workflow holds a draft of the provider or another change request is
 * open, since the platform takes one change at a time) and links to the provider page and the
 * provider events of the audit. A provider that is only a draft has none of these yet: the tab
 * says so and opens the draft; one the platform does not know says that.
 */
import { useState, type ReactNode } from 'react'
import { Button, Icon, Skeleton, StatusDot, Tag, duration, percent, type DotKind } from '@iaf/ui'
import { Link, pathFor } from '../../app/router'
import { api, errorMessage } from '../../lib/api'
import { useQuery } from '../../lib/queries'
import type { ChangeRequest, ProviderAlert, ProviderDetail } from '../../lib/types'
import { HealthBand, measured } from '../../components/HealthBand'
import { ActionBand, RefreshSpecModal, ReleaseCard, SloModal } from '../../components/provider-admin'
import { When, shortJobId } from '../../components/common'
import { currentRelease } from '../../pages/ReleasesPage'
import type { ProviderDraft } from '../model/providerDrafts'
import { useDesignerStore } from '../model/store'
import { openChange, type ProviderStepInfo } from './providerStep'

const severityDot = (s: string): DotKind => (/crit|error|fail|page/i.test(s) ? 'critical' : /warn/i.test(s) ? 'warning' : 'neutral')

/** The alerts the tab shows: the action's own first, then the newest; at most `limit`. */
export function alertsFor(alerts: readonly ProviderAlert[], action: string, limit = 5): ProviderAlert[] {
  const own = (a: ProviderAlert) => (a.action === action ? 0 : 1)
  return [...alerts].sort((a, b) => own(a) - own(b) || b.at.localeCompare(a.at)).slice(0, limit)
}

/** A figure against its target: the measured value, and whether it meets the SLO. */
export function againstSlo(detail: Pick<ProviderDetail, 'health' | 'slo'>): { rate: string; rateMet: boolean | null; p95: string; p95Met: boolean | null } {
  const h = detail.health
  const calls = h.calls > 0
  return {
    rate: measured(h, percent(h.successRate, { digits: 1 })),
    rateMet: calls && detail.slo ? h.successRate >= detail.slo.successRate : null,
    p95: measured(h, duration(h.p95Ms)),
    p95Met: calls && detail.slo ? h.p95Ms <= detail.slo.p95Ms : null,
  }
}

function Section({ title, children, actions }: { title: string; children: ReactNode; actions?: ReactNode }) {
  return (
    <section className="space-y-2">
      <div className="flex items-center gap-2 border-b border-stroke-light pb-1.5">
        <h3 className="text-caption2 font-semibold uppercase tracking-wide text-tertiary">{title}</h3>
        <span className="flex-1" />
        {actions}
      </div>
      {children}
    </section>
  )
}

function Figure({ label, value, target, met }: { label: string; value: string; target?: string; met: boolean | null }) {
  return (
    <div className="rounded-md border border-stroke-light bg-surface-secondary px-3 py-2">
      <p className="text-caption2 font-medium uppercase tracking-wide text-tertiary">{label}</p>
      <p className={met === false ? 'text-subheadline font-semibold tabular-nums text-critical' : 'text-subheadline font-semibold tabular-nums text-primary'}>{value}</p>
      <p className="text-caption2 text-tertiary">{target ? `SLO ${target}${met === null ? '' : met ? ' · met' : ' · missed'}` : 'No SLO yet'}</p>
    </div>
  )
}

/**
 * Why Edit SLO and Refresh spec are held back, or null when they are not: each opens a change
 * request on the published version, which the platform refuses while another is open, and which
 * would block the change request of the workflow's draft of the provider.
 */
export function editsHeldBack(draft: ProviderDraft | undefined, open: ChangeRequest | undefined): 'draft' | 'open' | null {
  if (draft) return 'draft'
  return open ? 'open' : null
}

export function ProviderTab({ info, stepId, readOnly = false }: { info: ProviderStepInfo; stepId: string; readOnly?: boolean }) {
  const store = useDesignerStore()
  const openProvider = () => store.openProviderSheet({ entry: 'node', provider: info.published ? info.provider : null, draft: info.draft?.id ?? null, target: { kind: 'step', step: stepId } })
  if (info.state === 'unknown') {
    return (
      <div className="space-y-4" data-testid="ndv-provider-tab">
        <div className="rounded-md border border-dashed border-stroke-light px-4 py-6 text-center">
          <p className="text-footnote font-medium text-primary">{info.provider} is not a registered provider</p>
          <p className="mt-1 text-caption text-tertiary">The platform has no provider of that name, and no draft of it is kept in this browser (drafts live in the browser they were made in). Pick a provider in Parameters.</p>
        </div>
      </div>
    )
  }
  if (info.state === 'draft') {
    return (
      <div className="space-y-4" data-testid="ndv-provider-tab">
        <div className="rounded-md border border-dashed border-stroke-light px-4 py-6 text-center">
          <p className="text-footnote font-medium text-primary">{info.draft?.title || info.provider} is a draft</p>
          <p className="mt-1 text-caption text-tertiary">Its health, alerts and releases start once its change request merges and workflows call it. Until then, Try for real calls it from its draft.</p>
          {!readOnly && (
            <Button size="sm" variant="secondary" icon="edit" onClick={openProvider} className="mt-3">
              Open provider
            </Button>
          )}
        </div>
      </div>
    )
  }
  return <PublishedProvider info={info} readOnly={readOnly} onOpen={openProvider} />
}

function PublishedProvider({ info, readOnly, onOpen }: { info: ProviderStepInfo; readOnly: boolean; onOpen: () => void }) {
  const name = info.provider
  const alerts = useQuery(['provider-alerts', name], () => api.providerAlerts(name))
  const releases = useQuery(['releases', 'provider', name], () => api.releases({ provider: name }))
  const [editSlo, setEditSlo] = useState(false)
  const [refreshing, setRefreshing] = useState(false)
  const detail = info.detail
  if (!detail) {
    return (
      <div className="space-y-3" data-testid="ndv-provider-tab">
        {info.detailError ? (
          <p className="rounded-md border border-critical/30 bg-critical-tint px-3 py-2 text-caption text-critical" role="alert">
            Could not load {name}: {errorMessage(info.detailError)}
          </p>
        ) : (
          <>
            <Skeleton className="h-16 w-full" />
            <Skeleton className="h-24 w-full" />
          </>
        )}
      </div>
    )
  }
  const stats = detail.perAction.find((p) => p.action === info.action)
  const action = detail.actions.find((a) => a.id === info.action)
  const figures = againstSlo(detail)
  const release = currentRelease(releases.data)
  const open = detail.changeRequests.filter((c) => c.status === 'open')
  const shown = alertsFor(alerts.data ?? [], info.action)
  const change = openChange(info)
  const heldBack = editsHeldBack(info.draft, change)
  return (
    <div className="space-y-6" data-testid="ndv-provider-tab">
      <Section
        title={`${detail.title} · ${detail.version}`}
        actions={
          !readOnly && (
            <Button size="sm" variant="ghost" icon="edit" onClick={onOpen}>
              Open provider
            </Button>
          )
        }
      >
        <HealthBand health={detail.health} showReasons />
        <div className="grid grid-cols-2 gap-2">
          <Figure label="Success rate" value={figures.rate} target={detail.slo ? percent(detail.slo.successRate, { digits: 1 }) : undefined} met={figures.rateMet} />
          <Figure label="p95" value={figures.p95} target={detail.slo ? duration(detail.slo.p95Ms) : undefined} met={figures.p95Met} />
        </div>
        <p className="text-caption text-tertiary">Over the last 24 hours, {detail.health.calls === 0 ? 'with no calls yet' : `${detail.health.calls} calls`}{detail.slo?.channel ? ` · alerts go to ${detail.slo.channel}` : ''}.</p>
        {!readOnly && (
          <div className="space-y-2">
            <div className="flex flex-wrap gap-2">
              <Button size="sm" variant="secondary" icon="settings" onClick={() => setEditSlo(true)} disabled={heldBack !== null}>
                Edit SLO
              </Button>
              {detail.type === 'openapi' && (
                <Button size="sm" variant="secondary" icon="refresh" onClick={() => setRefreshing(true)} disabled={heldBack !== null}>
                  Refresh spec
                </Button>
              )}
            </div>
            {heldBack === 'draft' && info.draft && (
              <p className="text-caption text-secondary" data-testid="ndv-provider-held-back">
                The workflow holds a draft of {name} ({info.draft.version}). Edit SLO and Refresh spec would each open a separate change request on {detail.version}, and the draft’s could not open until that one merged: change them in the draft with Open provider.
              </p>
            )}
            {heldBack === 'open' && change && (
              <p className="text-caption text-secondary" data-testid="ndv-provider-held-back">
                <Link to={pathFor('change-request', { id: change.id })} className="font-mono text-accent hover:underline">
                  {change.id}
                </Link>{' '}
                ({change.version}) is still open: {name} takes no other change until it merges or closes.
              </p>
            )}
          </div>
        )}
      </Section>

      <Section title={`This step’s action · ${info.action}`}>
        {action ? (
          <div className="flex flex-wrap items-start justify-between gap-3">
            <ActionBand action={action} stats={stats} />
            <p className="text-caption text-tertiary">{stats ? `${stats.calls} calls in 14 days · ${percent(stats.successRate, { digits: 1 })} · p95 ${duration(stats.p95Ms)}` : 'No calls in 14 days'}</p>
          </div>
        ) : (
          <p className="text-caption text-tertiary">{detail.title} has no action {info.action || 'named'}: pick one in Parameters.</p>
        )}
      </Section>

      <Section title="Alerts">
        {alerts.loading ? (
          <Skeleton className="h-10 w-full" />
        ) : shown.length === 0 ? (
          <p className="text-caption text-tertiary">No alerts. When the provider breaks its SLO or a call fails in a new way, it shows here and in the alert channel.</p>
        ) : (
          <ol className="space-y-2" aria-label="Alerts">
            {shown.map((al) => (
              <li key={al.id} className="flex items-start gap-2 text-caption">
                <StatusDot kind={severityDot(al.severity)} className="mt-1" label={al.severity} />
                <div className="min-w-0 flex-1">
                  <p className="flex flex-wrap items-center gap-1.5">
                    {al.action && <span className="font-mono text-primary">{al.action}</span>}
                    {al.action === info.action && (
                      <Tag size="sm" tone="accent">
                        this step’s action
                      </Tag>
                    )}
                    <span className="text-tertiary">
                      <When at={al.at} />
                    </span>
                  </p>
                  <p className="text-secondary">{al.message}</p>
                  {al.jobId && (
                    <Link to={pathFor('job', { id: al.jobId })} className="font-mono text-accent hover:underline">
                      job {shortJobId(al.jobId)}
                    </Link>
                  )}
                </div>
              </li>
            ))}
          </ol>
        )}
      </Section>

      {release && <ReleaseCard release={release} />}

      <Section title="Open change requests">
        {open.length === 0 ? (
          <p className="text-caption text-tertiary">None open: every edit of the provider goes through one, and merging it publishes the next version.</p>
        ) : (
          <ul className="space-y-1.5" data-testid="ndv-provider-changes">
            {open.map((c) => (
              <li key={c.id} className="flex flex-wrap items-baseline gap-x-2 text-caption">
                <Link to={pathFor('change-request', { id: c.id })} className="font-mono text-accent hover:underline">
                  {c.id}
                </Link>
                <span className="min-w-0 flex-1 truncate text-primary">{c.title}</span>
                <span className="font-mono text-tertiary">{c.version}</span>
              </li>
            ))}
          </ul>
        )}
      </Section>

      <div className="flex flex-wrap items-center gap-4 border-t border-stroke-light pt-3 text-footnote">
        <Link to={pathFor('provider', { name })} className="inline-flex items-center gap-1 font-medium text-accent hover:underline">
          Provider page
          <Icon name="arrow-right" size={12} />
        </Link>
        {/* the platform audit filters by type, not by provider: the link says so; the provider page holds this one's change requests */}
        <Link to={pathFor('audit', {}, { tab: 'platform', aggregate: 'provider' })} className="inline-flex items-center gap-1 font-medium text-accent hover:underline">
          Audit of every provider
          <Icon name="arrow-right" size={12} />
        </Link>
      </div>

      {editSlo && <SloModal provider={detail} onClose={() => setEditSlo(false)} />}
      {refreshing && <RefreshSpecModal provider={detail} onClose={() => setRefreshing(false)} />}
    </div>
  )
}
