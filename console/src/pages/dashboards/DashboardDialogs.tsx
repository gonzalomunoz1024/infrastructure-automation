import { useRef, useState, type FormEvent } from 'react'
import { Button, Checkbox, Field, Input, Modal, OverlayBody, OverlayFooter, OverlayHeader, Select, Textarea } from '@iaf/ui'
import { ConfirmDialog, type Problem } from '@iaf/console-kit'
import { useConsole } from '../../app/console'
import { FailureNote, useCommand } from '../../app/commands'
import { ActionButton } from '../../app/Refusal'
import { navigate, pathFor } from '../../app/router'
import { bff, idempotencyKey } from '../../lib/bff'
import { DASHBOARD_QUERIES, KIND_WORDS, MAX_NAME, MAX_TITLE, dashboardsApi, defaultSize, newWidgetId, type Dashboard, type Permit, type ShareAccess, type Sharing, type Widget, type WidgetKind } from '../../lib/dashboards'
import { monitoringApi, rangeOf, seriesTitle, unitWord, windowOf } from '../../lib/monitoring'
import { useQuery } from '../../lib/queries'

/*
 * The dashboards' dialogs: create one, add a widget to the one being edited, choose who sees it,
 * and delete it. A button for a write is drawn from the BFF's `can`: refused by policy it is
 * locked and opens the refusal; not offered to this person it is greyed, and the page says why.
 */

/** A write's button from the BFF's permit: locked with the refusal, greyed when not offered, else live. */
export function PermitButton({ permit, what, onClick, children, icon, variant = 'secondary', verb }: { permit: Permit; what: string; onClick: () => void; children: string; icon?: 'edit' | 'users' | 'trash' | 'plus'; variant?: 'primary' | 'secondary' | 'ghost' | 'danger'; verb: string }) {
  if (!permit.allowed && !permit.denied) {
    return (
      <Button size="sm" variant={variant} icon={icon} disabled data-verb={verb} data-why>
        {children}
      </Button>
    )
  }
  return (
    <ActionButton decision={permit} what={what} size="sm" variant={variant} icon={icon} onClick={onClick} verb={verb}>
      {children}
    </ActionButton>
  )
}

const problemFor = (problems: Problem[], field: string) => problems.find((p) => p.field === field)?.message

/** Names a new dashboard, creates it empty and opens it for editing. */
export function CreateDashboardDialog({ onClose }: { onClose: () => void }) {
  const cmd = useCommand()
  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const [problems, setProblems] = useState<Problem[]>([])
  const key = useRef(idempotencyKey())
  const ready = name.trim().length > 0 && name.length <= MAX_NAME
  const submit = async (e: FormEvent) => {
    e.preventDefault()
    if (!ready) return
    const made = await cmd.run('Create the dashboard', () => dashboardsApi.create({ name: name.trim(), ...(description.trim() ? { description: description.trim() } : {}), widgets: [] }, key.current), { invalidates: DASHBOARD_QUERIES, quiet: true, onProblems: setProblems })
    if (made) navigate(pathFor('dashboard', { id: made.dashboardId }, { edit: '1' }))
  }
  return (
    <Modal onClose={onClose} label="Create a dashboard" width="max-w-lg">
      <form onSubmit={(e) => void submit(e)} noValidate>
        <OverlayHeader title="Create a dashboard" subtitle="It starts empty and only you see it. Add widgets next, then share it with your team." icon="grid" />
        <OverlayBody>
          <div className="space-y-5">
            <Field label="Name" htmlFor="dashboard-name" required error={name.length > MAX_NAME ? `Up to ${MAX_NAME} characters` : problemFor(problems, '/name')}>
              <Input id="dashboard-name" data-autofocus value={name} onChange={(e) => setName(e.target.value)} placeholder="Web tier" maxLength={MAX_NAME + 20} />
            </Field>
            <Field label="Description" htmlFor="dashboard-description" hint="Optional: what the board is for." error={problemFor(problems, '/description')}>
              <Textarea id="dashboard-description" value={description} onChange={(e) => setDescription(e.target.value)} rows={2} />
            </Field>
            <FailureNote failure={cmd.failure} shownFields={['name', 'description']} compact />
          </div>
        </OverlayBody>
        <OverlayFooter>
          <Button type="button" variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          <Button type="submit" variant="primary" loading={cmd.busy} disabled={!ready}>
            Create dashboard
          </Button>
        </OverlayFooter>
      </form>
    </Modal>
  )
}

const KINDS: WidgetKind[] = ['metric', 'count', 'drift', 'requests', 'alarms']

/** Chooses what a new widget shows; the widget joins the board being edited, saved with it. */
export function AddWidgetDialog({ onAdd, onClose }: { onAdd: (w: Widget) => void; onClose: () => void }) {
  const { types } = useConsole()
  const [kind, setKind] = useState<WidgetKind>('metric')
  const metricTypes = types.filter((t) => t.observe.metrics)
  const [typeName, setTypeName] = useState(metricTypes[0]?.name ?? types[0]?.name ?? '')
  const [wfrn, setWfrn] = useState('')
  const [metric, setMetric] = useState('')
  const [tagKey, setTagKey] = useState('')
  const [tagValue, setTagValue] = useState('')
  const [title, setTitle] = useState('')

  const typeChoices = kind === 'metric' ? metricTypes : types
  const type = typeChoices.find((t) => t.name === typeName) ?? typeChoices[0]
  const resources = useQuery(['console-resources', 'widget-picker', type?.name ?? ''], () => bff.resources.list(type!.name, { limit: 100, sort: 'name' }), { enabled: kind === 'metric' && !!type })
  const rows = resources.data?.items ?? []
  const resource = rows.find((r) => r.wfrn === wfrn) ?? rows[0]
  const series = useQuery(['console-metrics-series', type?.name ?? '', resource?.wfrn ?? ''], () => monitoringApi.metrics(type!.name, resource!.wfrn, windowOf(rangeOf('1h'), Date.now())), { enabled: kind === 'metric' && !!type && !!resource })
  const seriesList = series.data?.series ?? []
  const picked = seriesList.find((s) => s.name === metric) ?? seriesList[0]
  const resourceName = resource ? (resource.name ?? resource.wfrn.slice(resource.wfrn.lastIndexOf('/') + 1)) : ''

  const tagError = tagKey && !/^[a-z0-9][a-z0-9-]{0,31}$/.test(tagKey) ? 'A tag name is 1 to 32 lowercase letters, digits or hyphens' : tagKey && !tagValue.trim() ? 'Give the tag’s value too' : !tagKey && tagValue ? 'Give the tag’s name too' : undefined
  const suggested =
    kind === 'metric' ? (resource && picked ? `${resourceName} ${seriesTitle(picked.name)}` : '') : kind === 'count' ? (type ? `${type.title} by state${tagKey && tagValue ? `, ${tagKey}: ${tagValue}` : ''}` : '') : KIND_WORDS[kind].label
  const finalTitle = (title.trim() || suggested).slice(0, MAX_TITLE)
  const ready = !!finalTitle && title.length <= MAX_TITLE && (kind === 'metric' ? !!(type && resource && picked) : kind === 'count' ? !!type && !tagError : true)

  const submit = (e: FormEvent) => {
    e.preventDefault()
    if (!ready) return
    const base = { widgetId: newWidgetId(), title: finalTitle, ...defaultSize(kind) }
    if (kind === 'metric') onAdd({ ...base, kind, type: type!.name, wfrn: resource!.wfrn, metric: picked!.name })
    else if (kind === 'count') onAdd({ ...base, kind, type: type!.name, ...(tagKey && tagValue.trim() ? { tag: { key: tagKey, value: tagValue.trim() } } : {}) })
    else onAdd({ ...base, kind })
  }

  return (
    <Modal onClose={onClose} label="Add a widget" width="max-w-lg">
      <form onSubmit={submit} noValidate>
        <OverlayHeader title="Add a widget" subtitle="It goes at the end of the board. Save the dashboard to keep it." icon="plus" />
        <OverlayBody>
          <div className="space-y-5">
            <fieldset>
              <legend className="mb-2 text-sm font-medium text-primary">What it shows</legend>
              <div className="space-y-2">
                {KINDS.map((k, i) => (
                  <label key={k} className="flex cursor-pointer items-start gap-3 rounded-md border border-stroke-light px-3 py-2 has-[:checked]:border-accent">
                    <input type="radio" name="widget-kind" value={k} checked={kind === k} onChange={() => setKind(k)} className="mt-1 accent-[var(--color-accent)]" {...(i === 0 ? { 'data-autofocus': true } : {})} />
                    <span className="min-w-0">
                      <span className="block text-sm font-medium text-primary">{KIND_WORDS[k].label}</span>
                      <span className="block text-caption text-secondary">{KIND_WORDS[k].hint}</span>
                    </span>
                  </label>
                ))}
              </div>
            </fieldset>
            {(kind === 'metric' || kind === 'count') && (
              <Field label="Type">
                <Select ariaLabel={`Type: ${type?.title ?? 'none'}`} value={type?.name ?? ''} onChange={(v) => {
                  setTypeName(v)
                  setWfrn('')
                  setMetric('')
                }} options={typeChoices.map((t) => ({ value: t.name, label: t.title }))} />
              </Field>
            )}
            {kind === 'metric' && (
              <>
                <Field label="Resource" hint={resources.loading ? 'Reading the resources' : rows.length === 0 ? 'No resources of this type yet' : undefined}>
                  <Select ariaLabel={`Resource: ${resourceName || 'none'}`} value={resource?.wfrn ?? ''} onChange={(v) => {
                    setWfrn(v)
                    setMetric('')
                  }} options={rows.map((r) => ({ value: r.wfrn, label: r.name ?? r.wfrn.slice(r.wfrn.lastIndexOf('/') + 1) }))} />
                </Field>
                <Field label="Series" hint={series.loading ? 'Reading the series' : undefined}>
                  <Select ariaLabel={`Series: ${picked ? seriesTitle(picked.name) : 'none'}`} value={picked?.name ?? ''} onChange={setMetric} options={seriesList.map((s) => ({ value: s.name, label: seriesTitle(s.name), hint: unitWord(s.unit) || undefined }))} />
                </Field>
              </>
            )}
            {kind === 'count' && (
              <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
                <Field label="Only with the tag" htmlFor="widget-tag-key" hint="Optional" error={tagError}>
                  <Input id="widget-tag-key" value={tagKey} onChange={(e) => setTagKey(e.target.value.trim())} placeholder="environment" invalid={!!tagError} />
                </Field>
                <Field label="Tag value" htmlFor="widget-tag-value">
                  <Input id="widget-tag-value" value={tagValue} onChange={(e) => setTagValue(e.target.value)} placeholder="prod" />
                </Field>
              </div>
            )}
            <Field label="Title" htmlFor="widget-title" hint={`Up to ${MAX_TITLE} characters. Left empty, it is “${suggested || 'named by what it shows'}”.`} error={title.length > MAX_TITLE ? `Up to ${MAX_TITLE} characters` : undefined}>
              <Input id="widget-title" value={title} onChange={(e) => setTitle(e.target.value)} placeholder={suggested} invalid={title.length > MAX_TITLE} />
            </Field>
          </div>
        </OverlayBody>
        <OverlayFooter>
          <Button type="button" variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          <Button type="submit" variant="primary" disabled={!ready}>
            Add widget
          </Button>
        </OverlayFooter>
      </form>
    </Modal>
  )
}

const ACCOUNT_OPTIONS: { value: Sharing['account']; label: string }[] = [
  { value: 'none', label: 'Not shared' },
  { value: 'view', label: 'Can view' },
  { value: 'edit', label: 'Can edit' },
]
const GROUP_OPTIONS: { value: ShareAccess; label: string }[] = [
  { value: 'view', label: 'Can view' },
  { value: 'edit', label: 'Can edit' },
]

/** Who sees the dashboard: everyone in the account, and the groups chosen, each to view or to edit. */
export function ShareDialog({ dashboard, onClose }: { dashboard: Dashboard; onClose: () => void }) {
  const { account, session } = useConsole()
  const cmd = useCommand()
  const groups = useQuery(['iam-groups', 'share'], () => bff.iam.groups.list({ limit: 200 }))
  // without the account's groups to hand, the person's own are offered
  const choices = groups.data?.items.map((g) => ({ name: g.name, label: g.idpGroups?.[0] ? `${g.idpGroups[0]} (${g.name})` : g.name, description: g.description })) ?? (groups.error ? (session.account.groups ?? []).map((n) => ({ name: n, label: n, description: undefined })) : [])
  const [accountAccess, setAccountAccess] = useState<Sharing['account']>(dashboard.sharing.account)
  const [picked, setPicked] = useState<Record<string, ShareAccess>>(() => Object.fromEntries(dashboard.sharing.groups.map((g) => [g.group, g.access])))
  const submit = async (e: FormEvent) => {
    e.preventDefault()
    const body: Sharing = { account: accountAccess, groups: Object.entries(picked).map(([group, access]) => ({ group, access })) }
    const done = await cmd.run('Share the dashboard', () => dashboardsApi.share(dashboard.dashboardId, body, dashboard.version), { invalidates: DASHBOARD_QUERIES, quiet: true, done: () => ({ title: 'Sharing saved', message: `${dashboard.name} is shared as you chose.` }) })
    if (done) onClose()
  }
  return (
    <Modal onClose={onClose} label={`Share ${dashboard.name}`} width="max-w-lg">
      <form onSubmit={(e) => void submit(e)} noValidate>
        <OverlayHeader title={`Share ${dashboard.name}`} subtitle="Those you share it with find it under Shared with me. What each widget shows still follows their own access." icon="users" />
        <OverlayBody>
          <div className="space-y-5">
            <Field label={`Everyone in ${account.name}`}>
              <Select ariaLabel={`Everyone in ${account.name}: ${ACCOUNT_OPTIONS.find((o) => o.value === accountAccess)?.label}`} value={accountAccess} onChange={(v) => setAccountAccess(v as Sharing['account'])} options={ACCOUNT_OPTIONS} />
            </Field>
            <fieldset>
              <legend className="mb-2 text-sm font-medium text-primary">Groups</legend>
              {groups.loading && <p className="text-caption text-secondary">Reading the groups</p>}
              <ul className="space-y-2">
                {choices.map((g) => {
                  const on = g.name in picked
                  return (
                    <li key={g.name} className="flex flex-wrap items-center justify-between gap-2 rounded-md border border-stroke-light px-3 py-2">
                      <Checkbox
                        id={`share-${g.name}`}
                        checked={on}
                        label={g.label}
                        description={g.description}
                        onChange={(c) =>
                          setPicked((p) => {
                            const next = { ...p }
                            if (c) next[g.name] = 'view'
                            else delete next[g.name]
                            return next
                          })
                        }
                      />
                      {on && <Select size="sm" className="w-32" ariaLabel={`${g.label}: ${picked[g.name] === 'edit' ? 'Can edit' : 'Can view'}`} value={picked[g.name]} onChange={(v) => setPicked((p) => ({ ...p, [g.name]: v as ShareAccess }))} options={GROUP_OPTIONS} />}
                    </li>
                  )
                })}
              </ul>
            </fieldset>
            <FailureNote failure={cmd.failure} compact />
          </div>
        </OverlayBody>
        <OverlayFooter>
          <Button type="button" variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          <Button type="submit" variant="primary" loading={cmd.busy}>
            Save sharing
          </Button>
        </OverlayFooter>
      </form>
    </Modal>
  )
}

/** Asks once, then deletes the dashboard for everyone it is shared with. */
export function DeleteDashboardDialog({ dashboard, onClose }: { dashboard: Dashboard; onClose: () => void }) {
  const cmd = useCommand()
  const shared = dashboard.sharing.account !== 'none' || dashboard.sharing.groups.length > 0
  return (
    <ConfirmDialog
      title={`Delete ${dashboard.name}?`}
      tone="danger"
      icon="trash"
      confirmLabel="Delete dashboard"
      message={`${shared ? 'Everyone it is shared with loses it too. ' : ''}The resources its widgets show are not touched. This cannot be undone.`}
      onClose={onClose}
      onConfirm={async () => {
        let gone = false
        await cmd.run(
          'Delete the dashboard',
          async () => {
            await dashboardsApi.remove(dashboard.dashboardId, dashboard.version)
            gone = true
          },
          { invalidates: ['dashboards'], done: () => ({ title: 'Dashboard deleted', message: `${dashboard.name} is gone.` }) },
        )
        if (gone) navigate(pathFor('dashboards'))
      }}
    />
  )
}
