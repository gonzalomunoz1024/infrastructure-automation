/**
 * The provider page's pieces that run a published provider: an action's band against the SLO it
 * is judged by, the release in flight, the SLO editor, the spec refresh, and what a tried call
 * answered with "Use the real response". The provider page and the designer share them; every edit
 * among them is a change request the toast names and links.
 */
import { useState, type ReactNode } from 'react'
import { Button, Card, Field, Input, JsonView, Modal, OverlayBody, OverlayFooter, OverlayHeader, ProgressBar, StateTag, StatusDot, Table, Tag, duration, percent, useOverlayClose, useToast } from '@iaf/ui'
import { pathFor } from '../app/router'
import { api, errorMessage } from '../lib/api'
import { invalidate } from '../lib/queries'
import type { ActionInfo, OverrideRequest, ProviderActionStats, ProviderDetail, Release, TryResult } from '../lib/types'
import { useChangeRequestToast } from './ChangeRequestToast'
import { HealthBand } from './HealthBand'
import { Mono, TextLink } from './common'
import { gateDot, isActiveRelease, stageLabel } from '../pages/ReleasesPage'

/** An action's band against the SLO it is judged by, and that SLO when it is the action's own. */
export function ActionBand({ action, stats }: { action: Pick<ActionInfo, 'slo'>; stats?: Pick<ProviderActionStats, 'band'> }) {
  return (
    <div className="min-w-0">
      <HealthBand health={{ status: stats?.band ?? 'unknown' }} />
      {action.slo && (
        <div className="mt-0.5 whitespace-nowrap text-caption2 text-tertiary" title="The action's own SLO: its calls are judged against it, not against the provider's">
          own SLO {percent(action.slo.successRate, { digits: 1 })} · p95 {duration(action.slo.p95Ms)}
        </div>
      )}
    </div>
  )
}

/** The provider's release: the version rolling out (or the last one), its stage, and the gates judged over its calls. */
export function ReleaseCard({ release }: { release: Release }) {
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

/* ----------------------------------------------------------------------------
   SLO editor
   ------------------------------------------------------------------------- */

/** Edit SLO: the success rate, the p95 and the alert channel, saved as a change request for the next patch. */
export function SloModal({ provider, onClose }: { provider: ProviderDetail; onClose: () => void }) {
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

/** Refresh spec: read the spec again, a change request for the next minor version when it changed. */
export function RefreshSpecModal({ provider, onClose }: { provider: ProviderDetail; onClose: () => void }) {
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
   What a tried call answered
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

/**
 * What a tried call answered: its status, the answer, the fields that differ from the declared
 * output, and the way to adopt the answer: "Use the real response" for a published provider (left
 * out without `onUseRealResponse`), and whatever else the caller offers (`children`).
 */
export function TryOutcome({ result, action, fixing, onUseRealResponse, children }: { result: TryResult; action: string; fixing: boolean; onUseRealResponse?: (override: OverrideRequest) => void; children?: ReactNode }) {
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
      {onUseRealResponse && result.mismatches.length > 0 && (
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
      {children}
    </section>
  )
}
