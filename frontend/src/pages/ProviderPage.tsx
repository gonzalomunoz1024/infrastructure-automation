import { useMemo, useState } from 'react'
import { BarChart, Button, Card, CardRow, CodeBlock, ConfirmDialog, Drawer, EmptyState, Field, Input, JsonView, LineChart, Modal, OverlayBody, OverlayFooter, OverlayHeader, PageHeader, ProgressBar, Select, SkeletonRows, Stat, StateTag, StatusDot, Table, Tabs, Tag, dayLabel, duration, formatNumber, percent, useOverlayClose, useToast, type DotKind, type StatDelta, type TableColumn } from '@iaf/ui'
import type { PageProps } from '../app/router'
import { navigate, pathFor } from '../app/router'
import { api, errorMessage } from '../lib/api'
import { invalidate, useQuery } from '../lib/queries'
import type { ActionInfo, ChangeRequest, OverrideRequest, ProviderActionStats, ProviderAlert, ProviderDetail, Release, TryResult } from '../lib/types'
import { useChangeRequestToast } from '../components/ChangeRequestToast'
import { HealthBand } from '../components/HealthBand'
import { SchemaForm, defaultsFor, type SchemaValue } from '../components/SchemaForm'
import { EffectTag, ErrorState, Mono, TextLink, When, shortJobId } from '../components/common'
import { currentRelease, gateDot, isActiveRelease, stageLabel } from './ReleasesPage'

type Tab = 'overview' | 'actions' | 'alerts' | 'changes' | 'yaml'

const TYPE_LABEL: Record<string, string> = { openapi: 'OpenAPI', http: 'HTTP', ansible: 'Ansible', terraform: 'Terraform', idp: 'IDP tile', sdk: 'SDK' }
const operationText = (op: ActionInfo['operation']): string => (typeof op === 'string' ? op : `${op.method.toUpperCase()} ${op.path}`)
const severityDot = (s: string): DotKind => (/crit|error|fail|page/i.test(s) ? 'critical' : /warn/i.test(s) ? 'warning' : 'neutral')
const rateDelta = (v: number | undefined): StatDelta | undefined => (v === undefined ? undefined : { value: Math.round(v * 10) / 10, suffix: ' pts', label: 'vs the day before' })
const msDelta = (v: number | undefined): StatDelta | undefined => (v === undefined ? undefined : { value: v, suffix: ' ms', betterWhen: 'down', label: 'vs the day before' })
const approvalText = (a: ActionInfo['approval']): string => (a === true || a === 'required' ? 'required' : a === false || !a ? 'no' : String(a))

export default function ProviderPage({ route }: PageProps) {
  const name = route.params.name
  const [tab, setTab] = useState<Tab>('overview')
  const [editSlo, setEditSlo] = useState(false)
  const [refreshing, setRefreshing] = useState(false)
  const [trying, setTrying] = useState<{ action?: string } | null>(null)
  const [openAction, setOpenAction] = useState<ActionInfo | null>(null)
  const provider = useQuery(['provider', name], () => api.provider(name), { refetchInterval: 60_000 })
  const alerts = useQuery(['provider-alerts', name], () => api.providerAlerts(name), { enabled: !!provider.data })
  const releases = useQuery(['releases', 'provider', name], () => api.releases({ provider: name }), { enabled: !!provider.data, refetchInterval: 30_000 })
  const data = provider.data
  const release = currentRelease(releases.data)

  const daily = useMemo(() => (data?.daily ?? []).slice().sort((a, b) => a.date.localeCompare(b.date)), [data])
  // each point is a whole 24-hour window, labelled by the day it starts
  const labels = daily.map((d) => dayLabel(d.date))
  const last = daily[daily.length - 1]
  const prev = daily[daily.length - 2]
  // a window without calls has no rates: compare only two windows that both have them
  const change = (now: number | null | undefined, before: number | null | undefined) => (now == null || before == null ? undefined : now - before)
  const stats = useMemo(() => new Map((data?.perAction ?? []).map((p) => [p.action, p])), [data])
  const openChanges = (data?.changeRequests ?? []).filter((c) => c.status === 'open')

  if (provider.error && !data) {
    return (
      <>
        <PageHeader eyebrow="Provider" title={name} back={{ href: `#${pathFor('providers')}`, label: 'Providers' }} />
        <ErrorState error={provider.error} title="Could not load the provider" onRetry={() => void provider.refetch()} />
      </>
    )
  }

  const actionColumns: TableColumn<ActionInfo>[] = [
    {
      key: 'id',
      header: 'Action',
      sortable: true,
      accessor: (a) => a.id,
      render: (a) => (
        <div className="min-w-0">
          <div className="flex items-center gap-2">
            <Mono className="text-primary">{a.id}</Mono>
            {a.overridden && (
              <Tag size="sm" tone="accent" title="The response schema follows a recorded real answer">
                override
              </Tag>
            )}
          </div>
          <div className="text-caption text-secondary">{a.title}</div>
        </div>
      ),
    },
    { key: 'operation', header: 'Operation', render: (a) => <Mono>{operationText(a.operation)}</Mono> },
    { key: 'effect', header: 'Effect', width: '6rem', sortable: true, accessor: (a) => a.effect, render: (a) => <EffectTag effect={a.effect} /> },
    {
      key: 'retry',
      header: 'Retry',
      width: '5rem',
      render: (a) => (
        <Tag size="sm" outline tone={a.retry === 'safe' ? 'neutral' : 'warning'}>
          {a.retry}
        </Tag>
      ),
    },
    { key: 'approval', header: 'Approval', width: '6rem', render: (a) => <span className={approvalText(a.approval) === 'no' ? 'text-tertiary' : 'text-primary'}>{approvalText(a.approval)}</span> },
    { key: 'calls', header: 'Calls', numeric: true, width: '5rem', sortable: true, accessor: (a) => stats.get(a.id)?.calls ?? 0, render: (a) => <span className="text-primary">{formatNumber(stats.get(a.id)?.calls ?? 0, { compact: true })}</span> },
    {
      key: 'success',
      header: 'Success',
      numeric: true,
      width: '6rem',
      sortable: true,
      accessor: (a) => stats.get(a.id)?.successRate ?? -1,
      render: (a) => {
        const s = stats.get(a.id)
        if (!s) return <span className="text-tertiary">—</span>
        return <span className={data?.slo && s.successRate < data.slo.successRate ? 'font-medium text-critical' : 'text-primary'}>{percent(s.successRate, { digits: 1 })}</span>
      },
    },
    { key: 'p95', header: 'p95', numeric: true, width: '5rem', render: (a) => <span className="text-primary">{stats.get(a.id) ? duration(stats.get(a.id)!.p95Ms) : '—'}</span> },
    {
      key: 'errors',
      header: 'Top errors',
      render: (a) => {
        const top = stats.get(a.id)?.topErrors ?? []
        return top.length ? (
          <span className="inline-flex flex-wrap gap-1">
            {top.slice(0, 3).map((e) => (
              <Tag key={e.class} size="sm" tone="critical" outline mono>
                {e.class} ×{e.count}
              </Tag>
            ))}
          </span>
        ) : (
          <span className="text-tertiary">none</span>
        )
      },
    },
  ]

  const changeColumns: TableColumn<ChangeRequest>[] = [
    { key: 'id', header: 'Change request', render: (c) => <Mono className="text-primary">{c.id}</Mono> },
    { key: 'title', header: 'Title', render: (c) => <span className="text-primary">{c.title}</span> },
    { key: 'status', header: 'Status', width: '8rem', render: (c) => <StateTag status={c.status} /> },
    { key: 'version', header: 'Version', width: '6rem', render: (c) => <Mono>{c.version}</Mono> },
    { key: 'author', header: 'Author', width: '10rem', render: (c) => <span className="text-primary">{c.author}</span> },
    { key: 'created', header: 'Opened', width: '9rem', render: (c) => <When at={c.createdAt} /> },
  ]

  return (
    <>
      <PageHeader
        eyebrow="Provider"
        title={data?.title ?? name}
        subtitle={
          data && (
            <span className="inline-flex flex-wrap items-center gap-2">
              <Mono>{data.name}</Mono>
              <Tag size="sm" outline>
                {TYPE_LABEL[data.type] ?? data.type}
              </Tag>
              <span>{data.owner}</span>
              <Mono>{data.version}</Mono>
              {release && isActiveRelease(release) && (
                <TextLink to={pathFor('release', { id: release.id })}>
                  <Tag size="sm" tone="accent" dot pulse={release.status === 'canary'} title="A new version is rolling out through a canary">
                    {release.status === 'paused' ? 'canary paused' : 'canary'} {release.to} at {release.stagePercent}%
                  </Tag>
                </TextLink>
              )}
              {data.environments.map((e) => (
                <Tag key={e} size="sm" mono outline>
                  {e}
                </Tag>
              ))}
            </span>
          )
        }
        back={{ href: `#${pathFor('providers')}`, label: 'Providers' }}
        actions={
          data && (
            <>
              <Button icon="edit" onClick={() => navigate(pathFor('provider-edit', { name: data.name }))} title="Test and try it again, then publish the next patch with the evidence">
                Edit provider
              </Button>
              {data.type === 'openapi' && (
                <Button icon="refresh" onClick={() => setRefreshing(true)}>
                  Refresh spec
                </Button>
              )}
              <Button icon="settings" onClick={() => setEditSlo(true)}>
                SLO
              </Button>
              <Button variant="primary" icon="play" onClick={() => setTrying({})} disabled={data.actions.length === 0}>
                Try action
              </Button>
            </>
          )
        }
      >
        <Tabs
          ariaLabel="Provider views"
          value={tab}
          onChange={setTab}
          tabs={[
            { id: 'overview', label: 'Overview', icon: 'chart' },
            { id: 'actions', label: 'Actions', icon: 'bolt', count: data?.actions.length },
            { id: 'alerts', label: 'Alerts', icon: 'bell', count: alerts.data?.length || undefined },
            { id: 'changes', label: 'Change requests', icon: 'git-branch', count: openChanges.length || undefined },
            { id: 'yaml', label: 'YAML', icon: 'document' },
          ]}
        />
      </PageHeader>

      {!data ? (
        <SkeletonRows n={5} />
      ) : tab === 'overview' ? (
        <div className="grid grid-cols-1 gap-5 lg:grid-cols-[minmax(0,1fr)_20rem]">
          <div className="min-w-0 space-y-5">
            <div className="grid grid-cols-2 gap-4 xl:grid-cols-4">
              <Stat label="Calls" value={formatNumber(data.health.calls, { compact: true })} icon="pulse" sparkline={daily.map((d) => d.calls)} delta={last && prev ? { value: last.calls - prev.calls, label: 'vs the day before' } : undefined} />
              <Stat
                label="Success rate"
                value={percent(data.health.successRate, { digits: 1 })}
                icon="check"
                hint={data.slo ? `target ${percent(data.slo.successRate, { digits: 1 })}` : undefined}
                delta={rateDelta(change(last?.successRate, prev?.successRate))}
              />
              <Stat label="p50" value={duration(data.health.p50Ms)} icon="clock" delta={msDelta(change(last?.p50Ms, prev?.p50Ms))} />
              <Stat
                label="p95"
                value={duration(data.health.p95Ms)}
                icon="clock"
                hint={data.slo ? `target ${duration(data.slo.p95Ms)}` : undefined}
                delta={msDelta(change(last?.p95Ms, prev?.p95Ms))}
              />
            </div>

            {daily.length === 0 ? (
              <Card flush>
                <EmptyState compact icon="chart" title="No calls recorded yet" message="The charts fill in as workflows call this provider." />
              </Card>
            ) : (
              <>
                <Card title="Calls per day">
                  <LineChart values={daily.map((d) => d.calls)} labels={labels} area height={160} yFormat={(v) => formatNumber(v, { compact: true })} ariaLabel="Calls per day" />
                </Card>
                <div className="grid grid-cols-1 gap-5 xl:grid-cols-2">
                  <Card title="Success rate" subtitle={data.slo ? `SLO ${percent(data.slo.successRate, { digits: 1 })}` : undefined}>
                    <LineChart values={daily.map((d) => d.successRate ?? Number.NaN)} labels={labels} height={150} yFormat={(v) => percent(v, { digits: 0 })} ariaLabel="Success rate per day" />
                  </Card>
                  <Card title="Latency" subtitle={data.slo ? `SLO p95 ${duration(data.slo.p95Ms)}` : undefined}>
                    <LineChart
                      series={[
                        { id: 'p50', label: 'p50', values: daily.map((d) => d.p50Ms ?? Number.NaN) },
                        { id: 'p95', label: 'p95', values: daily.map((d) => d.p95Ms ?? Number.NaN) },
                      ]}
                      labels={labels}
                      height={150}
                      yFormat={(v) => duration(v)}
                      legend
                      ariaLabel="Latency per day"
                    />
                  </Card>
                </div>
              </>
            )}

            {data.perAction.length > 0 && (
              <Card title="Calls by action" subtitle="The busiest actions first.">
                <BarChart
                  horizontal
                  showValues
                  values={data.perAction.slice().sort((a, b) => b.calls - a.calls).map((p) => p.calls)}
                  labels={data.perAction.slice().sort((a, b) => b.calls - a.calls).map((p) => p.action)}
                  height={Math.max(120, data.perAction.length * 28 + 24)}
                  yFormat={(v) => formatNumber(v, { compact: true })}
                  ariaLabel="Calls by action"
                />
              </Card>
            )}
          </div>

          <aside className="min-w-0 space-y-4">
            {openChanges.length > 0 && (
              <Card title="Pending change requests" subtitle="Edits waiting for review; merging publishes the next version." data-testid="pending-change-requests">
                <ul className="space-y-2">
                  {openChanges.map((c) => (
                    <li key={c.id} className="flex flex-wrap items-baseline gap-x-2 text-sm">
                      <TextLink to={pathFor('change-request', { id: c.id })} mono>
                        {c.id}
                      </TextLink>
                      <span className="min-w-0 flex-1 truncate text-primary">{c.title}</span>
                      <Mono className="text-tertiary">{c.version}</Mono>
                    </li>
                  ))}
                </ul>
              </Card>
            )}
            {release && <ReleaseCard release={release} />}
            <Card title="Health">
              <HealthBand health={data.health} showReasons />
              {data.health.errorTrend?.length > 1 && (
                <p className="mt-3 text-caption text-tertiary">
                  Errors, last {data.health.errorTrend.length} windows: <Mono>{data.health.errorTrend.join(' · ')}</Mono>
                </p>
              )}
            </Card>
            <Card
              title="SLO"
              actions={
                <Button size="sm" variant="ghost" icon="edit" onClick={() => setEditSlo(true)}>
                  Edit
                </Button>
              }
            >
              {data.slo ? (
                <>
                  <CardRow label="Success rate" description="target">
                    <Mono className="text-primary">{percent(data.slo.successRate, { digits: 1 })}</Mono>
                  </CardRow>
                  <CardRow label="p95 latency" description="target">
                    <Mono className="text-primary">{duration(data.slo.p95Ms)}</Mono>
                  </CardRow>
                  <CardRow label="Alert channel">
                    <Mono className="text-primary">{data.slo.channel ?? (typeof data.alerts?.channel === 'string' ? data.alerts.channel : '—')}</Mono>
                  </CardRow>
                </>
              ) : (
                <p className="text-sm text-secondary">No SLO yet. Set one and the health band starts judging against it.</p>
              )}
            </Card>
            <Card title="Endpoints">
              <Endpoints provider={data} onShowYaml={() => setTab('yaml')} />
            </Card>
          </aside>
        </div>
      ) : tab === 'actions' ? (
        <Table
          columns={actionColumns}
          rows={data.actions}
          rowKey={(a) => a.id}
          onRowClick={(a) => setOpenAction(a)}
          defaultSort={{ key: 'id', dir: 'asc' }}
          emptyIcon="bolt"
          emptyTitle="No actions"
          emptyMessage="This provider offers nothing yet; propose a change with the wizard."
          caption="Actions"
        />
      ) : tab === 'alerts' ? (
        <Card flush>
          {alerts.error && !alerts.data ? (
            <ErrorState compact error={alerts.error} title="Could not load the alerts" onRetry={() => void alerts.refetch()} />
          ) : alerts.loading ? (
            <div className="p-5">
              <SkeletonRows n={3} />
            </div>
          ) : (alerts.data ?? []).length === 0 ? (
            <EmptyState icon="bell" title="No alerts" message="When the provider breaks its SLO or a call fails in a new way, it shows up here and in the alert channel." />
          ) : (
            <ol className="divide-y divide-stroke-light">
              {(alerts.data ?? []).map((al: ProviderAlert) => (
                <li key={al.id} className="flex items-start gap-3 px-5 py-3">
                  <StatusDot kind={severityDot(al.severity)} className="mt-1.5" label={al.severity} />
                  <div className="min-w-0 flex-1">
                    <div className="flex flex-wrap items-center gap-2 text-sm">
                      <Mono className="text-primary">{al.action}</Mono>
                      <Tag size="sm" outline>
                        {al.severity}
                      </Tag>
                      <span className="text-caption text-tertiary">
                        <When at={al.at} />
                      </span>
                    </div>
                    <p className="mt-0.5 text-sm text-secondary">{al.message}</p>
                    {al.jobId && (
                      <p className="mt-1 text-caption">
                        <TextLink to={pathFor('job', { id: al.jobId })} mono>
                          job {shortJobId(al.jobId)}
                        </TextLink>
                      </p>
                    )}
                  </div>
                </li>
              ))}
            </ol>
          )}
        </Card>
      ) : tab === 'changes' ? (
        <Table
          columns={changeColumns}
          rows={data.changeRequests}
          rowKey={(c) => c.id}
          onRowClick={(c) => navigate(pathFor('change-request', { id: c.id }))}
          emptyIcon="git-branch"
          emptyTitle="No change requests"
          emptyMessage="Every edit to this provider goes through a change request; none is open."
          caption="Change requests"
        />
      ) : (
        <CodeBlock code={data.yaml} language="yaml" title={`providers/${data.owner}/${data.name}.yaml`} lineNumbers maxHeight="max-h-[70vh]" />
      )}

      {editSlo && data && <SloModal provider={data} onClose={() => setEditSlo(false)} />}
      {refreshing && data && <RefreshSpecModal provider={data} onClose={() => setRefreshing(false)} />}
      {trying && data && <TryActionDrawer provider={data} preset={trying.action} onClose={() => setTrying(null)} />}
      {openAction && data && (
        <ActionDrawer
          action={openAction}
          stats={stats.get(openAction.id)}
          onTry={() => {
            setTrying({ action: openAction.id })
            setOpenAction(null)
          }}
          onClose={() => setOpenAction(null)}
        />
      )}
    </>
  )
}

/** The provider's release: the version rolling out (or the last one), its stage, and the gates judged over its calls. */
function ReleaseCard({ release }: { release: Release }) {
  const active = isActiveRelease(release)
  const passed = release.gates.filter((g) => g.passed).length
  return (
    <Card title={active ? 'Release in flight' : 'Last release'} actions={<StateTag status={release.status} size="sm" />} data-testid="provider-release">
      <div className="space-y-3">
        <p className="text-sm text-primary">
          <Mono className="text-secondary">{release.from ?? 'new'}</Mono> → <Mono className="text-primary">{release.to}</Mono>
          <span className="text-secondary"> · {stageLabel(release)}</span>
        </p>
        <ProgressBar size="sm" value={release.status === 'promoted' ? 100 : release.status === 'rolled-back' ? 0 : release.stagePercent} tone={release.status === 'rolled-back' ? 'critical' : release.status === 'paused' ? 'warning' : 'accent'} label={`${release.stagePercent}% of calls on ${release.to}`} showLabel showValue />
        {release.gates.length > 0 && (
          <ul className="space-y-1" aria-label="Gates">
            {release.gates.map((g) => (
              <li key={g.id} className="flex items-center gap-2 text-caption">
                <StatusDot kind={gateDot(g)} />
                <span className="min-w-0 flex-1 truncate text-primary" title={g.detail}>
                  {g.label}
                </span>
                <span className="text-tertiary">{g.passed === null ? 'pending' : g.passed ? 'passed' : 'failed'}</span>
              </li>
            ))}
          </ul>
        )}
        <div className="flex items-center justify-between gap-2 text-caption">
          <span className="text-tertiary">
            {passed}/{release.gates.length} gates · judged over calls
          </span>
          <TextLink to={pathFor('release', { id: release.id })}>{active ? 'Follow the release' : 'Open the release'}</TextLink>
        </div>
      </div>
    </Card>
  )
}

function Endpoints({ provider, onShowYaml }: { provider: ProviderDetail; onShowYaml: () => void }) {
  // structured fields from the API: the browser does not read the YAML
  const endpoints = Object.entries(provider.endpoints ?? {})
  const auth = provider.auth
  return (
    <>
      {endpoints.length > 0
        ? endpoints.map(([env, url]) => (
            <CardRow key={env} label={env}>
              <Mono className="truncate text-primary" title={url}>
                {url}
              </Mono>
            </CardRow>
          ))
        : provider.environments.length > 0 && (
            <CardRow label="Environments" description="no base URLs: the platform does not call it over HTTP">
              <span className="inline-flex flex-wrap justify-end gap-1">
                {provider.environments.map((env) => (
                  <Tag key={env} size="sm" mono outline>
                    {env}
                  </Tag>
                ))}
              </span>
            </CardRow>
          )}
      <CardRow label="Authentication" description={auth?.header ? `header ${auth.header}` : undefined}>
        {auth ? (
          <span className="inline-flex items-center gap-2">
            <Tag size="sm" outline>
              {auth.type}
            </Tag>
            {auth.credential && (
              <Mono className="text-primary" title="The credential's name; its value never leaves the store">
                {auth.credential}
              </Mono>
            )}
          </span>
        ) : (
          <span className="text-caption text-tertiary">in the file</span>
        )}
      </CardRow>
      {(endpoints.length === 0 || !auth) && (
        <Button size="sm" variant="ghost" icon="document" onClick={onShowYaml} className="mt-2">
          Open the YAML
        </Button>
      )}
    </>
  )
}

/* ----------------------------------------------------------------------------
   SLO editor
   ------------------------------------------------------------------------- */

function SloModal({ provider, onClose }: { provider: ProviderDetail; onClose: () => void }) {
  return (
    <Modal onClose={onClose} width="max-w-md" label="Edit SLO">
      <SloForm provider={provider} />
    </Modal>
  )
}

function SloForm({ provider }: { provider: ProviderDetail }) {
  const close = useOverlayClose()
  const toast = useToast()
  const notify = useChangeRequestToast()
  const [successRate, setSuccessRate] = useState(String(provider.slo?.successRate ?? 99))
  const [p95Ms, setP95Ms] = useState(String(provider.slo?.p95Ms ?? 1000))
  const [channel, setChannel] = useState(provider.slo?.channel ?? (typeof provider.alerts?.channel === 'string' ? provider.alerts.channel : ''))
  const [busy, setBusy] = useState(false)
  const rate = Number(successRate)
  const p95 = Number(p95Ms)
  const ok = Number.isFinite(rate) && rate > 0 && rate <= 100 && Number.isFinite(p95) && p95 > 0

  // the SLO changes through a change request for the next patch version; a blank channel removes it
  const save = async () => {
    if (!ok) return
    setBusy(true)
    try {
      const accepted = await api.setProviderSlo(provider.name, { successRate: rate, p95Ms: p95, channel: channel.trim() || undefined })
      invalidate('provider')
      notify(accepted, `The SLO of ${provider.title} (${percent(rate, { digits: 1 })}, ${duration(p95)})`)
      close()
    } catch (e) {
      toast.error('Could not save the SLO', errorMessage(e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <form
      onSubmit={(e) => {
        e.preventDefault()
        void save()
      }}
      className="flex min-h-0 flex-1 flex-col"
    >
      <OverlayHeader title="Service level objective" subtitle="The health band turns amber, then red, when the provider falls below these. Saving opens a change request." icon="settings" />
      <OverlayBody className="space-y-4">
        <Field label="Success rate (%)" htmlFor="slo-rate" required>
          <Input id="slo-rate" type="number" min={1} max={100} step={0.1} value={successRate} onChange={(e) => setSuccessRate(e.target.value)} autoFocus />
        </Field>
        <Field label="p95 latency (ms)" htmlFor="slo-p95" required>
          <Input id="slo-p95" type="number" min={1} step={1} value={p95Ms} onChange={(e) => setP95Ms(e.target.value)} />
        </Field>
        <Field label="Alert channel" htmlFor="slo-channel" hint="Where a breach is announced, like #compute-alerts">
          <Input id="slo-channel" mono value={channel} onChange={(e) => setChannel(e.target.value)} placeholder="#team-alerts" />
        </Field>
      </OverlayBody>
      <OverlayFooter>
        <Button type="button" variant="ghost" onClick={close} disabled={busy}>
          Cancel
        </Button>
        <Button type="submit" variant="primary" loading={busy} disabled={!ok}>
          Save SLO
        </Button>
      </OverlayFooter>
    </form>
  )
}

/* ----------------------------------------------------------------------------
   Refresh the spec: read it again and open a change request for the next minor version
   ------------------------------------------------------------------------- */

function RefreshSpecModal({ provider, onClose }: { provider: ProviderDetail; onClose: () => void }) {
  return (
    <Modal onClose={onClose} width="max-w-md" label="Refresh spec">
      <RefreshSpecForm provider={provider} />
    </Modal>
  )
}

function RefreshSpecForm({ provider }: { provider: ProviderDetail }) {
  const close = useOverlayClose()
  const toast = useToast()
  const notify = useChangeRequestToast()
  const [ref, setRef] = useState('')
  const [busy, setBusy] = useState(false)

  const refresh = async () => {
    setBusy(true)
    try {
      const accepted = await api.refreshSpec(provider.name, ref.trim() ? { ref: ref.trim() } : {})
      invalidate('provider')
      notify(accepted, `The spec of ${provider.title}`)
      close()
    } catch (e) {
      toast.error('Could not refresh the spec', errorMessage(e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <form
      onSubmit={(e) => {
        e.preventDefault()
        void refresh()
      }}
      className="flex min-h-0 flex-1 flex-col"
    >
      <OverlayHeader title="Refresh the spec" subtitle="Reads the spec again from where the file says: its URL, or Git at the file's ref. A changed spec opens a change request for the next minor version; the same document changes nothing." icon="refresh" />
      <OverlayBody className="space-y-4">
        <Field label="Ref" htmlFor="refresh-ref" hint="For a spec in Git: a tag, branch or commit to read instead of the file's ref; it is then written to the file. Leave empty to keep it.">
          <Input id="refresh-ref" mono value={ref} onChange={(e) => setRef(e.target.value)} placeholder="v8.0.3" autoFocus />
        </Field>
      </OverlayBody>
      <OverlayFooter>
        <Button type="button" variant="ghost" onClick={close} disabled={busy}>
          Cancel
        </Button>
        <Button type="submit" variant="primary" icon="refresh" loading={busy}>
          Refresh spec
        </Button>
      </OverlayFooter>
    </form>
  )
}

/* ----------------------------------------------------------------------------
   One action, in detail
   ------------------------------------------------------------------------- */

function ActionDrawer({ action, stats, onTry, onClose }: { action: ActionInfo; stats?: ProviderActionStats; onTry: () => void; onClose: () => void }) {
  return (
    <Drawer onClose={onClose} width="max-w-xl" label={`Action ${action.id}`}>
      <OverlayHeader
        title={action.title}
        subtitle={<Mono>{action.id}</Mono>}
        icon="bolt"
        actions={
          <Button size="sm" variant="primary" icon="play" onClick={onTry}>
            Try
          </Button>
        }
      />
      <OverlayBody className="space-y-5">
        <div className="flex flex-wrap items-center gap-2">
          <EffectTag effect={action.effect} />
          <Tag size="sm" outline tone={action.retry === 'safe' ? 'neutral' : 'warning'}>
            retry {action.retry}
          </Tag>
          <Tag size="sm" outline>
            approval {approvalText(action.approval)}
          </Tag>
          {action.plan && (
            <Tag size="sm" outline mono>
              plan via {action.plan}
            </Tag>
          )}
          {action.overridden && (
            <Tag size="sm" tone="accent">
              response overridden
            </Tag>
          )}
        </div>
        <CardRow label="Operation">
          <Mono className="text-primary">{operationText(action.operation)}</Mono>
        </CardRow>
        {stats && (
          <div className="grid grid-cols-3 gap-3">
            <Stat label="Calls" value={formatNumber(stats.calls, { compact: true })} />
            <Stat label="Success" value={percent(stats.successRate, { digits: 1 })} />
            <Stat label="p95" value={duration(stats.p95Ms)} />
          </div>
        )}
        <section>
          <h3 className="mb-2 text-caption font-medium uppercase tracking-wide text-tertiary">Input</h3>
          <JsonView value={action.input} name="input" defaultExpanded={2} maxHeight="max-h-64" />
        </section>
        <section>
          <h3 className="mb-2 text-caption font-medium uppercase tracking-wide text-tertiary">Output</h3>
          <JsonView value={action.output} name="output" defaultExpanded={2} maxHeight="max-h-64" />
        </section>
        {action.sample !== undefined && (
          <section>
            <h3 className="mb-2 text-caption font-medium uppercase tracking-wide text-tertiary">Sample (dry runs answer with this)</h3>
            <JsonView value={action.sample} name="sample" defaultExpanded={2} maxHeight="max-h-64" />
          </section>
        )}
      </OverlayBody>
    </Drawer>
  )
}

/* ----------------------------------------------------------------------------
   Try an action against a real environment
   ------------------------------------------------------------------------- */

/**
 * "Use the real response": the override a tried answer becomes (POST /providers/{name}/overrides,
 * a change request with the answer's schema as the action's override). Only an answer that differs
 * from the declared output and is a JSON object can be one.
 */
export function realResponseOverride(result: TryResult | null, action: string): OverrideRequest | null {
  if (!result || result.mismatches.length === 0 || !action) return null
  const r = result.response
  return r !== null && typeof r === 'object' && !Array.isArray(r) ? { action, response: r as Record<string, unknown> } : null
}

/** What a tried call answered: its status, the answer, the fields that differ from the declared output, and the way to adopt the answer. */
export function TryOutcome({ result, action, fixing, onUseRealResponse }: { result: TryResult; action: string; fixing: boolean; onUseRealResponse: (override: OverrideRequest) => void }) {
  const override = realResponseOverride(result, action)
  return (
    <section className="animate-fade-in space-y-3 rounded-lg border border-stroke-light p-4" data-testid="try-outcome">
      <div className="flex flex-wrap items-center gap-2">
        <Tag tone={result.ok ? 'healthy' : 'critical'} dot>
          {result.ok ? 'OK' : 'Failed'}
        </Tag>
        <Mono className="text-primary">HTTP {result.status}</Mono>
        <span className="text-caption tabular-nums text-tertiary">{duration(result.durationMs)}</span>
        {result.verified !== undefined && (
          <Tag size="sm" tone={result.verified ? 'healthy' : 'warning'} icon={result.verified ? 'check' : 'help'}>
            {result.verified ? 'verified afterwards' : 'not verified'}
          </Tag>
        )}
        {result.mismatches.length === 0 ? (
          <Tag size="sm" tone="healthy" outline>
            matches the declared output
          </Tag>
        ) : (
          <Tag size="sm" tone="warning" outline>
            {result.mismatches.length} field{result.mismatches.length === 1 ? '' : 's'} differ
          </Tag>
        )}
      </div>
      <JsonView value={result.response} name="response" defaultExpanded={2} maxHeight="max-h-72" />
      {result.mismatches.length > 0 && (
        <Table
          dense
          columns={[
            { key: 'field', header: 'Field', render: (m) => <Mono className="text-primary">{m.field}</Mono> },
            { key: 'expected', header: 'Declared', render: (m) => <Mono>{JSON.stringify(m.expected)}</Mono> },
            { key: 'actual', header: 'Real answer', render: (m) => <Mono className="text-primary">{JSON.stringify(m.actual)}</Mono> },
          ]}
          rows={result.mismatches}
          rowKey={(m) => m.field}
          caption="Mismatches"
        />
      )}
      {result.mismatches.length > 0 && (
        <div className="flex flex-wrap items-center justify-end gap-3">
          {override ? (
            <>
              <span className="text-caption text-tertiary">Opens a change request with the schema of this answer as the action's override.</span>
              <Button icon="edit" loading={fixing} onClick={() => onUseRealResponse(override)}>
                Use the real response
              </Button>
            </>
          ) : (
            <span className="text-caption text-tertiary">The answer is not a JSON object, so it cannot become the action's override.</span>
          )}
        </div>
      )}
    </section>
  )
}

function TryActionDrawer({ provider, preset, onClose }: { provider: ProviderDetail; preset?: string; onClose: () => void }) {
  const toast = useToast()
  const notify = useChangeRequestToast()
  const [actionId, setActionId] = useState(preset ?? provider.actions[0]?.id ?? '')
  const action = provider.actions.find((a) => a.id === actionId)
  const [environment, setEnvironment] = useState(provider.environments[0] ?? 'dev')
  const [input, setInput] = useState<SchemaValue>(() => defaultsFor(action?.input))
  const [result, setResult] = useState<TryResult | null>(null)
  const [busy, setBusy] = useState<'try' | 'fix' | null>(null)
  const [confirm, setConfirm] = useState(false)

  const pick = (id: string) => {
    setActionId(id)
    setInput(defaultsFor(provider.actions.find((a) => a.id === id)?.input))
    setResult(null)
  }

  const run = async () => {
    setBusy('try')
    try {
      setResult(await api.tryAction({ yaml: provider.yaml, action: actionId, environment, input }))
    } catch (e) {
      toast.error('The call did not go through', errorMessage(e))
    } finally {
      setBusy(null)
    }
  }

  // "Use the real response": the schema inferred from the answer goes into the file's overrides through a change request
  const fix = async (override: OverrideRequest) => {
    setBusy('fix')
    try {
      const accepted = await api.overrideAction(provider.name, override)
      invalidate('provider')
      notify(accepted, `The override of ${override.action}, from the real answer,`)
    } catch (e) {
      toast.error('Could not record the override', errorMessage(e))
    } finally {
      setBusy(null)
    }
  }

  const risky = !!action && action.effect !== 'read' && action.effect !== 'none'

  return (
    <Drawer onClose={onClose} width="max-w-2xl" label="Try an action">
      <OverlayHeader title="Try an action" subtitle="A real call with the provider's credential; the answer is compared with the declared output." icon="play" />
      <OverlayBody className="space-y-5">
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          <Field label="Action">
            <Select ariaLabel="Action" value={actionId} onChange={pick} mono options={provider.actions.map((a) => ({ value: a.id, label: a.id, hint: `${a.title} · ${a.effect}` }))} />
          </Field>
          <Field label="Environment">
            <Select ariaLabel="Environment" value={environment} onChange={setEnvironment} mono options={(provider.environments.length ? provider.environments : ['dev']).map((e) => ({ value: e, label: e }))} />
          </Field>
        </div>
        {action && (
          <div className="flex flex-wrap items-center gap-2">
            <EffectTag effect={action.effect} />
            <Mono>{operationText(action.operation)}</Mono>
            {risky && (
              <Tag size="sm" tone="warning" icon="alert-triangle">
                changes something in {environment}
              </Tag>
            )}
          </div>
        )}
        <SchemaForm schema={action?.input} value={input} onChange={setInput} idPrefix="try" pickers={false} />

        {result && <TryOutcome result={result} action={actionId} fixing={busy === 'fix'} onUseRealResponse={(override) => void fix(override)} />}
      </OverlayBody>
      <OverlayFooter>
        <Button variant="ghost" onClick={onClose}>
          Close
        </Button>
        <Button variant="primary" icon="play" loading={busy === 'try'} disabled={!action} onClick={() => (risky ? setConfirm(true) : void run())}>
          Try it
        </Button>
      </OverlayFooter>
      {confirm && action && (
        <ConfirmDialog
          title={`Run ${action.id} in ${environment}?`}
          tone={action.effect === 'delete' ? 'danger' : 'warning'}
          icon="alert-triangle"
          message={`This is a real ${action.effect} call, not a dry run. ${action.effect === 'delete' ? 'Whatever it deletes is gone.' : 'It changes the system it talks to.'}`}
          confirmLabel="Run it"
          onConfirm={async () => {
            await run()
          }}
          onClose={() => setConfirm(false)}
        />
      )}
    </Drawer>
  )
}
