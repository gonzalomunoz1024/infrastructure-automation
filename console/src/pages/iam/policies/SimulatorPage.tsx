import { useMemo, useState } from 'react'
import { Button, Checkbox, Field, Icon, Input, PageHeader, Segmented, Select, Textarea, cx, type IconName } from '@iaf/ui'
import { DotTag, Section, StatusBadge } from '@iaf/console-kit'
import { useConsole } from '../../../app/console'
import { useRefusal } from '../../../app/Refusal'
import { Link, pathFor, type PageProps } from '../../../app/router'
import { isApiError } from '../../../lib/api'
import { bff } from '../../../lib/bff'
import { checksOf, patternProblem, patternWords, policiesApi, readJson, scopeLabel, type DraftPolicy, type Guardrail, type PolicySimulation, type SimulationResult } from '../../../lib/policies'
import { useQuery } from '../../../lib/queries'
import { roleLabel } from '../../../lib/session'
import type { IamPolicy, IamRole, IamStatement } from '../../../lib/types'
import { ActionPicker, useActionCatalog, useTypeTitle } from './editor'
import { takeDraft } from './parts'

/*
 * The policy simulator (WP 7.8): "can this person, role or set of groups do these actions on
 * these wfrns, and what decided?" It sends the identity service's policy-simulations call, which
 * answers each check exactly as a real request would be answered, with unsaved policies and a
 * draft boundary laid over the account and nothing written. Each answer names the deciding
 * statement and the part of console IAM that decided (a guardrail, a role's boundary, a policy),
 * drawn as the refusal view draws its layers.
 */

type PrincipalKind = 'user' | 'role' | 'groups'

export interface SimResource {
  wfrn: string
  name?: string
  tags?: Record<string, string>
}

const NO_BOUNDARY = '__none'
const OWN_BOUNDARY = ''

function tagsText(tags: Record<string, string> | undefined): string {
  return Object.entries(tags ?? {})
    .map(([k, v]) => `${k}=${v}`)
    .join(', ')
}

function tagsOf(text: string): Record<string, string> | undefined {
  const out: Record<string, string> = {}
  for (const part of text.split(',')) {
    const [k, ...v] = part.split('=')
    if (k?.trim() && v.length) out[k.trim()] = v.join('=').trim()
  }
  return Object.keys(out).length ? out : undefined
}

/** Picks one of the account's resources by its type and name; its tags go with it, so tag conditions decide. */
function ResourcePick({ onAdd }: { onAdd: (r: SimResource) => void }) {
  const { types } = useConsole()
  const [type, setType] = useState(types[0]?.name ?? '')
  const [wfrn, setWfrn] = useState('')
  const rows = useQuery(['sim-resources', type], () => bff.resources.list(type, { limit: 100 }), { enabled: !!type })
  const items = rows.data?.items ?? []
  const picked = items.find((r) => r.wfrn === wfrn)
  const t = types.find((x) => x.name === type)
  return (
    <div className="grid grid-cols-1 gap-3 min-[480px]:grid-cols-[minmax(0,1fr)_minmax(0,1.4fr)_auto] min-[480px]:items-end">
      <Field label="Type" htmlFor="sim-type">
        <Select
          ariaLabel={`Type: ${t?.title ?? type}`}
          value={type}
          onChange={(v) => {
            setType(v)
            setWfrn('')
          }}
          options={types.map((x) => ({ value: x.name, label: x.title }))}
        />
      </Field>
      <Field label="Resource" htmlFor="sim-resource">
        <Select ariaLabel={`Resource: ${picked?.name ?? 'choose one'}`} value={wfrn} onChange={setWfrn} placeholder={rows.loading ? 'Loading…' : 'Choose one'} options={items.map((r) => ({ value: r.wfrn, label: r.name ?? r.wfrn, hint: tagsText(r.tags) }))} />
      </Field>
      <Button icon="plus" disabled={!picked} onClick={() => picked && onAdd({ wfrn: picked.wfrn, name: picked.name, tags: picked.tags })}>
        Add resource
      </Button>
    </div>
  )
}

function TypedResource({ onAdd }: { onAdd: (r: SimResource) => void }) {
  const [wfrn, setWfrn] = useState('')
  const [tags, setTags] = useState('')
  const problem = wfrn.trim() ? patternProblem(wfrn.trim()) : null
  // a row, not a form: the simulator also opens inside the policy editor's form
  const add = () => {
    if (!wfrn.trim() || problem) return
    onAdd({ wfrn: wfrn.trim(), tags: tagsOf(tags) })
    setWfrn('')
    setTags('')
  }
  return (
    <div
      className="grid grid-cols-1 gap-3 min-[480px]:grid-cols-[minmax(0,1.6fr)_minmax(0,1fr)_auto] min-[480px]:items-end"
      onKeyDown={(e) => {
        if (e.key !== 'Enter' || (e.target as HTMLElement).tagName !== 'INPUT') return
        e.preventDefault()
        add()
      }}
    >
      <Field label="Or a wfrn or pattern" htmlFor="sim-wfrn" error={problem ?? undefined}>
        <Input id="sim-wfrn" mono value={wfrn} invalid={!!problem} onChange={(e) => setWfrn(e.target.value)} placeholder="wfrn:wf:compute:global:123456789012:vm/*" autoComplete="off" spellCheck={false} />
      </Field>
      <Field label="Its tags" hint="environment=prod, team=web" htmlFor="sim-tags">
        <Input id="sim-tags" mono value={tags} onChange={(e) => setTags(e.target.value)} autoComplete="off" spellCheck={false} />
      </Field>
      <Button icon="plus" disabled={!wfrn.trim() || !!problem} onClick={add}>
        Add
      </Button>
    </div>
  )
}

/* ----------------------------------------------------------------------------
   The answer, as layers
   ------------------------------------------------------------------------- */

type Verdict = 'allowed' | 'denied' | 'not asked'

/** The simulator's own stand-ins (the role holding the unsaved policies, a role's principal) are never shown by id. */
const isSimulated = (role: string) => role.startsWith('simulated:')

function LayerRow({ n, label, verdict, detail, deciding }: { n: number; label: string; verdict: Verdict; detail: string; deciding: boolean }) {
  const icon: IconName = verdict === 'allowed' ? 'check' : verdict === 'denied' ? 'close-circle' : 'minus'
  return (
    <li className={cx('flex gap-3 rounded-lg border p-3', deciding ? (verdict === 'denied' ? 'border-critical/40 bg-critical-tint/40' : 'border-healthy/40 bg-healthy-tint/40') : 'border-stroke-light')} data-sim-layer={label} data-layer-verdict={verdict} data-deciding={deciding || undefined}>
      <span className={cx('mt-0.5 flex size-6 shrink-0 items-center justify-center rounded-full bg-surface', verdict === 'allowed' ? 'text-healthy' : verdict === 'denied' ? 'text-critical' : 'text-tertiary')} aria-hidden="true">
        <Icon name={icon} size={14} />
      </span>
      <div className="min-w-0 flex-1">
        <div className="flex flex-wrap items-center gap-2">
          <span className="text-sm font-semibold text-primary">
            {n}. {label}
          </span>
          <DotTag size="sm" tone={verdict === 'denied' ? 'critical' : verdict === 'allowed' ? 'healthy' : 'neutral'}>
            {verdict}
          </DotTag>
          {deciding && (
            <DotTag size="sm" tone={verdict === 'denied' ? 'critical' : 'healthy'}>
              decided here
            </DotTag>
          )}
        </div>
        <p className="mt-1 break-words text-sm text-primary">{detail}</p>
      </div>
    </li>
  )
}

interface Names {
  policy: (id: string | undefined) => string
  guardrail: (id: string | undefined) => string
  role: (name: string | undefined) => IamRole | undefined
}

/** The three parts of console IAM, in the order they decide, with the one that decided marked. */
export function layersOf(r: SimulationResult, names: Names): { label: string; verdict: Verdict; detail: string; deciding: boolean }[] {
  const statement = r.statement ? `statement ${r.statement} of ` : ''
  const role = r.role && !isSimulated(r.role) ? ` for the role ${roleLabel(r.role)}` : ''
  const rails = { label: 'The organization’s guardrails', verdict: 'allowed' as Verdict, detail: 'No guardrail over the account refuses it.', deciding: false }
  const roles = { label: 'The policies of the roles', verdict: 'not asked' as Verdict, detail: 'Only asked once the guardrails allow it.', deciding: false }
  const bound = { label: 'The role’s permission boundary', verdict: 'not asked' as Verdict, detail: 'Only asked for a role whose statement allows it.', deciding: false }
  if (r.scope === 'guardrail') {
    rails.verdict = 'denied'
    rails.deciding = true
    rails.detail = r.guardrailId ? `The guardrail ${names.guardrail(r.guardrailId)} denies it${r.statement ? ` (statement ${r.statement})` : ''}.` : 'It is outside the organization’s guardrails: one of them is an allow-list, and none allows it.'
    return [rails, roles, bound]
  }
  if (r.scope === 'boundary') {
    roles.verdict = 'allowed'
    roles.detail = `A statement of the role ${roleLabel(r.role ?? '')} allows it.`
    bound.verdict = 'denied'
    bound.deciding = true
    bound.detail = `It is outside the permission boundary ${names.policy(r.policyId)} of the role ${roleLabel(r.role ?? '')}, so that allow does not count.`
    return [rails, roles, bound]
  }
  if (!r.allowed) {
    roles.verdict = 'denied'
    roles.deciding = true
    roles.detail = r.statement || r.policyId ? `The deny in ${statement}${names.policy(r.policyId)} matches${role}. An explicit deny wins.` : `No statement of the roles allows it, and what nothing allows is denied.${r.suggestedRole ? ` The ${roleLabel(r.suggestedRole)} role would allow it.` : ''}`
    return [rails, roles, bound]
  }
  roles.verdict = 'allowed'
  roles.deciding = true
  roles.detail = `${statement ? statement.charAt(0).toUpperCase() + statement.slice(1) : 'The policy '}${names.policy(r.policyId)} allows it${role}.`
  const held = names.role(r.role)
  bound.verdict = 'allowed'
  bound.detail = held?.boundary ? `The boundary ${names.policy(held.boundary)} allows it too.` : r.role && isSimulated(r.role) ? 'An unsaved policy has no boundary.' : 'The role has no boundary.'
  return [rails, roles, bound]
}

function ResultCard({ r, names, resourceName }: { r: SimulationResult; names: Names; resourceName: (wfrn: string) => string | undefined }) {
  const typeTitle = useTypeTitle()
  const name = resourceName(r.resource)
  const decided = r.scope === 'guardrail' ? `Guardrail ${r.guardrailId ? names.guardrail(r.guardrailId) : '(allow-list)'}` : r.scope === 'boundary' ? `Permission boundary ${names.policy(r.policyId)}` : r.statement || r.policyId ? `Policy ${names.policy(r.policyId)}${r.statement ? `, statement ${r.statement}` : ''}` : 'No statement allows it'
  return (
    <li className="rounded-lg border border-stroke-light p-4" data-sim-result={`${r.action} ${r.resource}`} data-allowed={r.allowed}>
      <div className="flex flex-wrap items-start justify-between gap-2">
        <div className="min-w-0">
          <p className="font-mono text-sm font-semibold text-primary">{r.action}</p>
          <p className="mt-0.5 break-all text-caption text-secondary">{name ? `${name} (${r.resource})` : patternWords(r.resource, typeTitle)}</p>
        </div>
        <StatusBadge status={r.allowed ? 'succeeded' : 'failed'} label={r.allowed ? 'Allowed' : 'Denied'} size="sm" />
      </div>
      <p className="mt-2 text-sm text-primary" data-decided-by={scopeLabel(r)}>
        <span className="text-secondary">Decided by: </span>
        {decided}
        {r.role ? <span className="text-secondary">{isSimulated(r.role) ? ' · an unsaved policy' : ` · role ${roleLabel(r.role)}`}</span> : null}
      </p>
      <details className="mt-2">
        <summary className="cursor-pointer text-caption font-medium text-accent">Show how it was decided</summary>
        <ol className="mt-2 space-y-2">
          {layersOf(r, names).map((l, i) => (
            <LayerRow key={l.label} n={i + 1} {...l} />
          ))}
        </ol>
        <p className="mt-2 text-caption text-tertiary">{r.reason}. The platform’s own role for the account is asked after console IAM allows; the simulator answers for console IAM.</p>
      </details>
    </li>
  )
}

/* ----------------------------------------------------------------------------
   The simulator
   ------------------------------------------------------------------------- */

export interface SimulatorProps {
  /** unsaved policies to lay over the principal, from the editor */
  drafts?: DraftPolicy[]
  user?: string
  role?: string
  actions?: string[]
  resources?: SimResource[]
  idPrefix?: string
}

export function Simulator({ drafts: given = [], user, role, actions: startActions, resources: startResources, idPrefix = 'sim' }: SimulatorProps) {
  const { account, me } = useConsole()
  const catalog = useActionCatalog()
  const refusal = useRefusal()
  const users = useQuery(['iam', 'users', account.id], () => bff.iam.users.list({ limit: 200 }))
  const roles = useQuery(['iam', 'roles', account.id], () => bff.iam.roles.list({ limit: 200 }))
  const groups = useQuery(['iam', 'groups', account.id], () => bff.iam.groups.list({ limit: 200 }))
  const policies = useQuery(['iam', 'policies', account.id], () => bff.iam.policies.list({ limit: 200 }))
  const rails = useQuery(['iam', 'guardrails', account.id], () => policiesApi.accountGuardrails())
  const [kind, setKind] = useState<PrincipalKind>(role ? 'role' : 'user')
  const [userId, setUserId] = useState(user ?? me.id)
  const [roleId, setRoleId] = useState(role ?? '')
  const [groupIds, setGroupIds] = useState<string[]>([])
  const [boundary, setBoundary] = useState(OWN_BOUNDARY)
  const [drafts, setDrafts] = useState<DraftPolicy[]>(given)
  const [pasted, setPasted] = useState('')
  const [actions, setActions] = useState<string[]>(startActions ?? [])
  const [resources, setResources] = useState<SimResource[]>(startResources ?? [])
  const [answer, setAnswer] = useState<PolicySimulation | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  const allPolicies = policies.data?.items ?? []
  const allRoles = roles.data?.items ?? []
  const chosenRole = allRoles.find((r) => r.roleId === roleId || r.name === roleId)
  const checks = checksOf(actions, resources)
  const pastedRead = useMemo(() => {
    if (!pasted.trim()) return null
    const r = readJson(pasted)
    if (r.error) return { error: `Line ${r.error.line}: ${r.error.message}` }
    const v = r.value as { name?: unknown; statements?: unknown } | null
    if (!v || typeof v !== 'object' || !Array.isArray(v.statements)) return { error: 'A policy is a JSON object with statements' }
    return { draft: { name: typeof v.name === 'string' ? v.name : `pasted-${drafts.length + 1}`, statements: v.statements as IamStatement[] } }
  }, [pasted, drafts.length])

  const names: Names = {
    policy: (id) => {
      if (!id) return 'a policy'
      if (id.startsWith('simulated:policy-')) return `${drafts[Number(id.split('-').pop())]?.name ?? 'draft'} (unsaved)`
      if (id === 'simulated:boundary') return `${allPolicies.find((p) => p.policyId === boundary)?.name ?? 'draft'} (draft boundary)`
      return allPolicies.find((p: IamPolicy) => p.policyId === id || p.name === id)?.name ?? id
    },
    guardrail: (id) => rails.data?.items.find((g: Guardrail) => g.guardrailId === id)?.name ?? id ?? 'a guardrail',
    role: (name) => allRoles.find((r) => r.name === name),
  }
  const resourceName = (wfrn: string) => resources.find((r) => r.wfrn === wfrn)?.name

  const run = async () => {
    setBusy(true)
    setError(null)
    try {
      const boundaryPolicy = allPolicies.find((p) => p.policyId === boundary)
      const draftBoundary = kind === 'role' && boundary !== OWN_BOUNDARY ? (boundary === NO_BOUNDARY ? { name: 'no-boundary', statements: [{ effect: 'allow' as const, actions: ['*:*'], resources: ['wfrn:*'] }] } : boundaryPolicy ? { name: boundaryPolicy.name, statements: boundaryPolicy.statements } : undefined) : undefined
      const principal = kind === 'user' ? { userId } : kind === 'role' ? { role: chosenRole?.roleId ?? roleId } : { groups: groupIds }
      const result = await policiesApi.simulate({ principal, ...(drafts.length ? { policies: drafts } : {}), ...(draftBoundary ? { boundary: draftBoundary } : {}), checks })
      setAnswer(result)
    } catch (e) {
      if (!refusal.showError(e, 'Run the simulation')) setError(isApiError(e) ? [e.error, ...(e.problems ?? []).map((p) => `${p.field ?? ''}: ${p.message}`)].join(' · ') : String(e))
    } finally {
      setBusy(false)
    }
  }

  const ready = checks.length > 0 && checks.length <= 100 && (kind === 'user' ? !!userId : kind === 'role' ? !!chosenRole : groupIds.length > 0)
  const userName = users.data?.items.find((u) => u.userId === userId)?.name ?? userId
  const boundaryLabel = boundary === OWN_BOUNDARY ? `Its own (${chosenRole?.boundary ? names.policy(chosenRole.boundary) : 'none'})` : boundary === NO_BOUNDARY ? 'No boundary' : names.policy(boundary)

  return (
    <div className="space-y-8" data-simulator>
      <Section title="Who" description="A person as they are now, a role on its own, or a set of groups.">
        <div className="space-y-4">
          <Segmented<PrincipalKind>
            ariaLabel="Simulate for"
            value={kind}
            onChange={(k) => {
              setKind(k)
              setAnswer(null)
            }}
            options={[
              { value: 'user', label: 'A person' },
              { value: 'role', label: 'A role' },
              { value: 'groups', label: 'Groups' },
            ]}
          />
          {kind === 'user' && (
            <Field label="Person" htmlFor={`${idPrefix}-user`}>
              <Select ariaLabel={`Person: ${userName}`} value={userId} onChange={setUserId} options={(users.data?.items ?? []).map((u) => ({ value: u.userId, label: u.name ?? u.email, hint: u.email }))} />
            </Field>
          )}
          {kind === 'role' && (
            <div className="grid gap-4 sm:grid-cols-2">
              <Field label="Role" htmlFor={`${idPrefix}-role`}>
                <Select ariaLabel={`Role: ${chosenRole ? roleLabel(chosenRole.name) : 'choose one'}`} value={chosenRole?.roleId ?? ''} onChange={setRoleId} placeholder="Choose a role" options={allRoles.map((r) => ({ value: r.roleId, label: roleLabel(r.name), hint: r.description }))} />
              </Field>
              <Field label="Permission boundary to try" hint="A draft in place of its own; nothing is saved" htmlFor={`${idPrefix}-boundary`}>
                <Select ariaLabel={`Permission boundary to try: ${boundaryLabel}`} value={boundary} onChange={setBoundary} options={[{ value: OWN_BOUNDARY, label: `Its own (${chosenRole?.boundary ? names.policy(chosenRole.boundary) : 'none'})` }, { value: NO_BOUNDARY, label: 'No boundary' }, ...allPolicies.map((p) => ({ value: p.policyId, label: p.name }))]} />
              </Field>
            </div>
          )}
          {kind === 'groups' && (
            <fieldset>
              <legend className="text-sm font-medium text-primary">Groups</legend>
              <div className="mt-2 grid gap-2 sm:grid-cols-2">
                {(groups.data?.items ?? []).map((g) => (
                  <Checkbox key={g.groupId} id={`${idPrefix}-group-${g.groupId}`} checked={groupIds.includes(g.groupId)} onChange={(on) => setGroupIds(on ? [...groupIds, g.groupId] : groupIds.filter((x) => x !== g.groupId))} label={<span className="font-mono">{g.name}</span>} />
                ))}
              </div>
            </fieldset>
          )}
          <div>
            <p className="text-sm font-medium text-primary">Unsaved policies it would also hold</p>
            <ul className="mt-2 space-y-1.5" aria-label="Unsaved policies">
              {drafts.map((d, i) => (
                <li key={i} className="flex flex-wrap items-center justify-between gap-2 rounded-md border border-stroke-light px-3 py-2" data-draft={d.name}>
                  <span className="text-sm">
                    <span className="font-mono text-primary">{d.name ?? `draft-${i}`}</span> <span className="text-caption text-secondary">{d.statements.length === 1 ? '1 statement' : `${d.statements.length} statements`}</span>
                  </span>
                  <Button size="sm" variant="ghost" icon="close" onClick={() => setDrafts(drafts.filter((_, j) => j !== i))}>
                    Remove
                  </Button>
                </li>
              ))}
              {!drafts.length && <li className="text-caption text-tertiary">None: it is decided by what the account holds now.</li>}
            </ul>
            <details className="mt-2">
              <summary className="cursor-pointer text-caption font-medium text-accent">Paste a policy as JSON</summary>
              <div className="mt-2 space-y-2">
                <Field label="Policy JSON" htmlFor={`${idPrefix}-paste`} error={pastedRead && 'error' in pastedRead ? pastedRead.error : undefined}>
                  <Textarea id={`${idPrefix}-paste`} mono rows={6} value={pasted} onChange={(e) => setPasted(e.target.value)} spellCheck={false} placeholder='{ "name": "draft", "statements": [ … ] }' />
                </Field>
                <Button
                  size="sm"
                  icon="plus"
                  disabled={!pastedRead || !('draft' in pastedRead) || drafts.length >= 10}
                  onClick={() => {
                    if (pastedRead && 'draft' in pastedRead && pastedRead.draft) {
                      setDrafts([...drafts, pastedRead.draft])
                      setPasted('')
                    }
                  }}
                >
                  Add unsaved policy
                </Button>
              </div>
            </details>
          </div>
        </div>
      </Section>

      <Section title="What" description="Every action is checked on every resource: up to 100 checks.">
        <div className="space-y-5">
          <fieldset>
            <legend className="text-sm font-medium text-primary">Actions</legend>
            <div className="mt-2">
              <ActionPicker label="Actions to check" value={actions} onChange={setActions} catalog={catalog} problems={[]} field="actions" idPrefix={`${idPrefix}-actions`} />
            </div>
          </fieldset>
          <fieldset>
            <legend className="text-sm font-medium text-primary">Resources</legend>
            <ul className="mt-2 space-y-1.5" aria-label="Resources to check">
              {resources.map((r, i) => (
                <li key={`${r.wfrn}-${i}`} className="flex flex-wrap items-center justify-between gap-2 rounded-md border border-stroke-light px-3 py-2" data-sim-resource={r.wfrn}>
                  <span className="min-w-0 text-sm">
                    <span className="font-medium text-primary">{r.name ?? 'Pattern'}</span> <span className="break-all font-mono text-caption text-secondary">{r.wfrn}</span>
                    {r.tags && <span className="block text-caption text-tertiary">tags {tagsText(r.tags)}</span>}
                  </span>
                  <Button size="sm" variant="ghost" icon="close" onClick={() => setResources(resources.filter((_, j) => j !== i))}>
                    Remove
                  </Button>
                </li>
              ))}
              {!resources.length && <li className="text-caption text-tertiary">No resource yet.</li>}
            </ul>
            <div className="mt-3 space-y-3 rounded-md border border-stroke-light p-3">
              <ResourcePick onAdd={(r) => setResources(resources.some((x) => x.wfrn === r.wfrn) ? resources : [...resources, r])} />
              <TypedResource onAdd={(r) => setResources([...resources, r])} />
            </div>
          </fieldset>
          <div className="flex flex-wrap items-center justify-end gap-3">
            <p className="text-caption text-secondary">{checks.length === 1 ? '1 check' : `${checks.length} checks`}</p>
            <Button variant="primary" icon="play" loading={busy} disabled={!ready} onClick={() => void run()}>
              Run the simulation
            </Button>
          </div>
          {error && (
            <p role="alert" className="rounded-md bg-critical-tint px-3 py-2 text-sm text-primary">
              {error}
            </p>
          )}
        </div>
      </Section>

      {answer && (
        <Section title="Results" description={`Decided at authorization version ${answer.version}. Nothing was written.`}>
          <p role="status" className="mb-3 text-sm text-primary" data-sim-summary>
            {`${answer.results.filter((r) => r.allowed).length} allowed, ${answer.results.filter((r) => !r.allowed).length} denied`}
          </p>
          <ul className="space-y-3" aria-label="Simulation results">
            {answer.results.map((r, i) => (
              <ResultCard key={`${r.action}-${r.resource}-${i}`} r={r} names={names} resourceName={resourceName} />
            ))}
          </ul>
        </Section>
      )}
    </div>
  )
}

export default function IamSimulatorPage({ route }: PageProps) {
  const [draft] = useState(() => takeDraft(route.query.draft))
  return (
    <>
      <PageHeader title="Policy simulator" subtitle="Ask what a person, a role or a set of groups may do, and which statement decides. Nothing is changed." actions={<Link to={pathFor('iam-policies')} className="text-sm font-medium text-accent hover:underline">All policies</Link>} />
      <Simulator key={`${route.query.user ?? ''}|${route.query.role ?? ''}|${route.query.draft ?? ''}`} drafts={draft ? [draft] : []} user={route.query.user} role={route.query.role} />
    </>
  )
}
