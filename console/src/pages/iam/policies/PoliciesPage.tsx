import { useMemo, useState } from 'react'
import { Button, Drawer, Modal, OverlayBody, OverlayFooter, OverlayHeader, PageHeader, Skeleton, SkeletonText, useToast } from '@iaf/ui'
import { CodeView, ConfirmDialog, DotTag, ErrorState, Facts, Section, When, type Problem } from '@iaf/console-kit'
import { useConsole } from '../../../app/console'
import { ActionButton, useRefusal } from '../../../app/Refusal'
import { Link, href, navigate, pathFor, type PageProps } from '../../../app/router'
import { isApiError } from '../../../lib/api'
import { bff } from '../../../lib/bff'
import { holdersInConflict, holdersOf, inputOf, policiesApi, policyJson, policyProblems, type PolicyDoc } from '../../../lib/policies'
import { useQuery } from '../../../lib/queries'
import { roleLabel } from '../../../lib/session'
import { useSubmissionKey } from '../../../lib/submission'
import type { IamPolicy, IamRole } from '../../../lib/types'
import { useAdminDecision } from '../common'
import { PolicyEditor, emptyStatement, useActionCatalog } from './editor'
import { StatementList, handDraft } from './parts'
import { Simulator } from './SimulatorPage'
import { AttachDialog, CreateRoleDialog, refreshIam } from './roles'

/*
 * Policies (WP 7.8): the account's managed and custom policies; one policy's statements, where it
 * is used (the roles that carry it and the ones it bounds) and its JSON; the editor that writes a
 * custom one, saved against the version read, checked in the simulator before it is saved; and a
 * delete refused while any role holds the policy, saying which.
 */

export default function IamPoliciesPage(_props: PageProps) {
  const { account } = useConsole()
  const can = useAdminDecision()
  const policies = useQuery(['iam', 'policies', account.id], () => bff.iam.policies.list({ limit: 200 }))
  const roles = useQuery(['iam', 'roles', account.id], () => bff.iam.roles.list({ limit: 200 }))
  const list = useMemo(() => [...(policies.data?.items ?? [])].sort((a, b) => Number(!!a.builtIn) - Number(!!b.builtIn) || a.name.localeCompare(b.name)), [policies.data])
  const create = can('iam:create', 'Create policies')
  return (
    <>
      <PageHeader
        title="Policies"
        subtitle={`The documents that decide what each role may do in ${account.name}: statements of actions on wfrn patterns, an explicit deny winning.`}
        actions={
          <div className="flex flex-wrap gap-2">
            <Link to={pathFor('iam-simulator')} className="inline-flex items-center rounded-full px-3 py-1.5 text-sm font-medium text-accent hover:underline">
              Open the simulator
            </Link>
            <ActionButton decision={create} what="Create a policy" verb="create" variant="primary" icon="plus" onClick={() => navigate(pathFor('iam-policy-new'))}>
              Create policy
            </ActionButton>
          </div>
        }
      />
      {policies.error ? (
        <ErrorState error={policies.error} title="Could not load the policies" onRetry={() => void policies.refetch()} />
      ) : policies.loading ? (
        <SkeletonText lines={6} />
      ) : (
        <ul className="divide-y divide-stroke-light rounded-lg border border-stroke-light" aria-label="Policies">
          {list.map((p) => {
            const used = holdersOf(p.policyId, roles.data?.items ?? [])
            const where = [...used.attached.map((r) => roleLabel(r.name)), ...used.bounding.map((r) => `${roleLabel(r.name)} (boundary)`)]
            return (
              <li key={p.policyId} className="flex flex-wrap items-start justify-between gap-2 px-4 py-3" data-policy-row={p.name}>
                <div className="min-w-0">
                  <div className="flex flex-wrap items-center gap-2">
                    <Link to={pathFor('iam-policy', { id: p.policyId })} className="font-mono text-sm font-medium text-accent hover:underline">
                      {p.name}
                    </Link>
                    <DotTag size="sm" tone={p.builtIn ? 'neutral' : 'accent'}>
                      {p.builtIn ? 'managed' : 'custom'}
                    </DotTag>
                  </div>
                  {p.description && <p className="mt-0.5 text-caption text-secondary">{p.description}</p>}
                </div>
                <p className="text-caption text-secondary">
                  {p.statements.length === 1 ? '1 statement' : `${p.statements.length} statements`} · {where.length ? `used by ${where.join(', ')}` : 'used by no role'}
                </p>
              </li>
            )
          })}
        </ul>
      )}
    </>
  )
}

/* ---------------------------------------------------------------- one policy */

/** Where a policy is used, said in a dialog: a delete waits until no role holds it. */
function InUseDialog({ policy, names, onClose }: { policy: IamPolicy; names: string[]; onClose: () => void }) {
  const { account } = useConsole()
  const roles = useQuery(['iam', 'roles', account.id], () => bff.iam.roles.list({ limit: 200 }))
  const byName = (n: string) => roles.data?.items.find((r) => r.name === n || r.roleId === n)
  return (
    <Modal onClose={onClose} width="max-w-lg" label={`${policy.name} is in use`}>
      <OverlayHeader title={`${policy.name} is in use`} icon="lock" subtitle="A policy a role holds, or one that bounds a role, cannot be deleted." />
      <OverlayBody>
        <p className="text-sm text-primary">Detach it from these roles first, or take it off as their boundary:</p>
        <ul className="mt-3 space-y-1.5" data-in-use>
          {names.map((n) => (
            <li key={n}>
              <Link to={pathFor('iam-role', { id: byName(n)?.roleId ?? n })} onClick={onClose} className="font-medium text-accent hover:underline">
                {roleLabel(n)}
              </Link>
              <span className="text-caption text-secondary"> {byName(n)?.boundary === policy.policyId ? 'is bounded by it' : 'carries it'}</span>
            </li>
          ))}
        </ul>
      </OverlayBody>
      <OverlayFooter>
        <Button data-autofocus onClick={onClose}>
          Close
        </Button>
      </OverlayFooter>
    </Modal>
  )
}

export function IamPolicyPage({ route }: PageProps) {
  const id = route.params.id
  const { account } = useConsole()
  const can = useAdminDecision()
  const refusal = useRefusal()
  const toast = useToast()
  const policy = useQuery(['iam', 'policies', account.id, id], () => bff.iam.policies.get(id))
  const policies = useQuery(['iam', 'policies', account.id], () => bff.iam.policies.list({ limit: 200 }))
  const roles = useQuery(['iam', 'roles', account.id], () => bff.iam.roles.list({ limit: 200 }))
  const [dialog, setDialog] = useState<'attach' | 'role' | 'delete' | null>(null)
  const [inUse, setInUse] = useState<string[] | null>(null)
  const back = { href: href(pathFor('iam-policies')), label: 'Policies' }
  const p = policy.data
  if (policy.error) {
    return (
      <>
        <PageHeader back={back} title="Policy" />
        <ErrorState error={policy.error} title="Could not load this policy" onRetry={() => void policy.refetch()} />
      </>
    )
  }
  if (!p) {
    return (
      <>
        <PageHeader back={back} title={<Skeleton className="h-7 w-48" />} />
        <SkeletonText lines={4} />
      </>
    )
  }
  const used = holdersOf(p.policyId, roles.data?.items ?? [])
  const holderNames = [...used.attached, ...used.bounding].map((r) => r.name)
  const managed = !!p.builtIn
  const edit = can('iam:update', 'Change policies')
  const remove = can('iam:delete', 'Delete policies')
  const attach = can('iam:update', 'Attach policies to roles')
  const tryIt = () => navigate(pathFor('iam-simulator', {}, { draft: handDraft({ name: p.name, statements: p.statements }) }))
  const del = async () => {
    try {
      await policiesApi.policies.remove(p.policyId)
      refreshIam()
      toast.success('Policy deleted', `${p.name} is gone.`)
      navigate(pathFor('iam-policies'), { replace: true })
    } catch (e) {
      if (isApiError(e) && e.status === 409) {
        setDialog(null)
        setInUse(holdersInConflict(e.detail).length ? holdersInConflict(e.detail) : holderNames)
        refreshIam()
        return
      }
      if (refusal.showError(e, `Delete ${p.name}`)) {
        setDialog(null)
        return
      }
      throw e
    }
  }
  return (
    <>
      <PageHeader
        back={back}
        eyebrow={managed ? 'Managed policy' : 'Custom policy'}
        title={<span className="font-mono">{p.name}</span>}
        subtitle={p.description}
        actions={
          <div className="flex flex-wrap gap-2">
            <Button icon="play" onClick={tryIt}>
              Check in the simulator
            </Button>
            {!managed && (
              <>
                <ActionButton decision={edit} what={`Edit ${p.name}`} verb="update" icon="edit" onClick={() => navigate(pathFor('iam-policy-edit', { id: p.policyId }))}>
                  Edit
                </ActionButton>
                <ActionButton decision={remove} what={`Delete ${p.name}`} verb="delete" variant="danger" icon="trash" onClick={() => (holderNames.length ? setInUse(holderNames) : setDialog('delete'))}>
                  Delete
                </ActionButton>
              </>
            )}
          </div>
        }
      />
      <div className="space-y-8">
        <Section title="About">
          <Facts
            columns={3}
            items={[
              { label: 'Kind', value: managed ? 'Managed: every account has it, and it never changes here' : 'Custom: written for this account' },
              { label: 'Version', value: String(p.version) },
              { label: 'Updated', value: p.updatedAt ? <When at={p.updatedAt} /> : 'not yet' },
            ]}
          />
        </Section>
        <Section
          title="Where it is used"
          description="The roles that carry it, and the roles it bounds."
          actions={
            <div className="flex flex-wrap gap-2">
              <ActionButton decision={attach} what="Attach this policy to a role" size="sm" icon="link" onClick={() => setDialog('attach')}>
                Attach to a role
              </ActionButton>
              <ActionButton decision={can('iam:create', 'Create roles')} what="Create a role with this policy" size="sm" icon="plus" onClick={() => setDialog('role')}>
                Create a role with it
              </ActionButton>
            </div>
          }
        >
          <ul className="space-y-1.5 text-sm" aria-label="Roles using this policy" data-used-by>
            {used.attached.map((r: IamRole) => (
              <li key={`a-${r.roleId}`}>
                <Link to={pathFor('iam-role', { id: r.roleId })} className="font-medium text-accent hover:underline">
                  {roleLabel(r.name)}
                </Link>
                <span className="text-secondary"> carries it</span>
              </li>
            ))}
            {used.bounding.map((r: IamRole) => (
              <li key={`b-${r.roleId}`}>
                <Link to={pathFor('iam-role', { id: r.roleId })} className="font-medium text-accent hover:underline">
                  {roleLabel(r.name)}
                </Link>
                <span className="text-secondary"> is bounded by it</span>
              </li>
            ))}
            {!holderNames.length && <li className="text-caption text-tertiary">No role uses it yet, so it decides nothing.</li>}
          </ul>
        </Section>
        <Section title="Statements" description="Read in order. Any matching deny wins; then the first allow; what nothing allows is denied.">
          <StatementList statements={p.statements} label={`Statements of ${p.name}`} />
        </Section>
        <Section title="As JSON">
          <CodeView samples={[{ id: 'json', label: 'JSON', language: 'json', code: policyJson({ name: p.name, description: p.description ?? '', statements: p.statements }) }]} />
        </Section>
      </div>
      {dialog === 'attach' && <AttachDialog policies={policies.data?.items ?? []} fixedPolicy={p} onClose={() => setDialog(null)} />}
      {dialog === 'role' && <CreateRoleDialog presetPolicies={[p.policyId]} onClose={() => setDialog(null)} onCreated={() => setDialog(null)} />}
      {dialog === 'delete' && <ConfirmDialog title={`Delete ${p.name}?`} message="No role uses it. Type its name to confirm; this cannot be undone." confirmLabel="Delete policy" tone="danger" icon="trash" requireText={p.name} onConfirm={del} onClose={() => setDialog(null)} />}
      {inUse && <InUseDialog policy={p} names={inUse} onClose={() => setInUse(null)} />}
    </>
  )
}

/* ---------------------------------------------------------------- the editor's page */

const STARTER = (accountId: string, partition: string): PolicyDoc => ({ name: '', description: '', statements: [{ ...emptyStatement(), resources: [`wfrn:${partition}:compute:*:${accountId}:vm/*`] }] })

/** Writes a new policy (`/iam/policies/new`) or a new version of one (`/iam/policies/:id/edit`). */
export function IamPolicyEditPage({ route }: PageProps) {
  const id = route.name === 'iam-policy-edit' ? route.params.id : undefined
  const { account, region } = useConsole()
  const existing = useQuery(['iam', 'policies', account.id, id ?? 'new'], () => (id ? bff.iam.policies.get(id) : Promise.resolve(null)))
  if (id && !existing.data) {
    return existing.error ? (
      <>
        <PageHeader title="Edit policy" />
        <ErrorState error={existing.error} title="Could not load this policy" onRetry={() => void existing.refetch()} />
      </>
    ) : (
      <SkeletonText lines={6} />
    )
  }
  const base = existing.data ?? undefined
  return <PolicyForm key={base ? `${base.policyId}-${base.version}` : 'new'} base={base} starter={STARTER(account.id, region.partition)} />
}

function PolicyForm({ base, starter }: { base?: IamPolicy; starter: PolicyDoc }) {
  const catalog = useActionCatalog()
  const can = useAdminDecision()
  const refusal = useRefusal()
  const toast = useToast()
  const keyFor = useSubmissionKey()
  const [doc, setDoc] = useState<PolicyDoc>(() => (base ? { name: base.name, description: base.description ?? '', statements: base.statements } : starter))
  const [version, setVersion] = useState(base?.version)
  const [server, setServer] = useState<{ doc: PolicyDoc; problems: Problem[] } | null>(null)
  const [moved, setMoved] = useState<number | null>(null)
  const [readable, setReadable] = useState(true)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const local = useMemo(() => policyProblems(doc, catalog), [doc, catalog])
  const problems = server && server.doc === doc ? [...local, ...server.problems.filter((p) => !local.some((l) => l.field === p.field))] : local
  const decision = base ? can('iam:update', 'Change policies') : can('iam:create', 'Create policies')
  const backPath = base ? pathFor('iam-policy', { id: base.policyId }) : pathFor('iam-policies')
  const back = { href: href(backPath), label: base ? base.name : 'Policies' }
  const [simulating, setSimulating] = useState(false)

  const save = async () => {
    if (problems.length || !readable) return
    setBusy(true)
    setError(null)
    const body = inputOf(doc)
    try {
      const saved = base && version !== undefined ? await policiesApi.policies.replace(base.policyId, body, version, keyFor(body)) : await policiesApi.policies.create(body, keyFor(body))
      refreshIam()
      toast.success(base ? 'Policy saved' : 'Policy created', base ? `${saved.name} is at version ${saved.version}.` : `${saved.name} decides nothing until a role carries it: attach it below.`)
      navigate(pathFor('iam-policy', { id: saved.policyId }))
    } catch (e) {
      if (isApiError(e) && e.status === 409 && base && /moved on/.test(e.error)) {
        const v = (e.detail as { version?: unknown } | undefined)?.version
        setMoved(typeof v === 'number' ? v : (version ?? 0) + 1)
      } else if (isApiError(e) && e.status === 400 && e.problems?.length) {
        setServer({ doc, problems: e.problems.map((p) => ({ ...p, level: 'error' as const })) })
      } else if (!refusal.showError(e, base ? `Save ${doc.name}` : 'Create the policy')) {
        setError(isApiError(e) ? e.error : String(e))
      }
    } finally {
      setBusy(false)
    }
  }

  const loadLatest = async () => {
    if (!base) return
    const latest = await bff.iam.policies.get(base.policyId)
    setDoc({ name: latest.name, description: latest.description ?? '', statements: latest.statements })
    setVersion(latest.version)
    setMoved(null)
  }

  return (
    <>
      <PageHeader back={back} eyebrow={base ? `Version ${version}` : 'New custom policy'} title={base ? `Edit ${base.name}` : 'Create policy'} subtitle="Statements in AWS’s shape: an effect, actions, wfrn patterns and conditions. Edit them visually or as JSON; both views show the same document." />
      <form
        noValidate
        onSubmit={(e) => {
          e.preventDefault()
          void save()
        }}
        className="space-y-6"
      >
        {moved !== null && (
          <div role="alert" className="flex flex-wrap items-center gap-3 rounded-md bg-warning-tint px-3 py-2 text-sm text-primary">
            <span className="min-w-0 flex-1">{`Someone saved this policy first: it is at version ${moved}. Load the latest to see it (your changes here are dropped), or save yours over it.`}</span>
            <Button size="sm" onClick={() => void loadLatest()}>
              Load the latest
            </Button>
            <Button
              size="sm"
              variant="ghost"
              onClick={() => {
                setVersion(moved)
                setMoved(null)
              }}
            >
              Keep mine
            </Button>
          </div>
        )}
        <PolicyEditor doc={doc} onChange={setDoc} problems={problems} catalog={catalog} onJsonState={setReadable} idPrefix="policy" />
        {error && (
          <p role="alert" className="rounded-md bg-critical-tint px-3 py-2 text-sm text-primary">
            {error}
          </p>
        )}
        <div className="flex flex-wrap justify-end gap-2 border-t border-stroke-light pt-4">
          <Button type="button" onClick={() => navigate(backPath)}>
            Cancel
          </Button>
          <Button type="button" icon="play" disabled={!readable || problems.some((p) => p.field?.startsWith('statements'))} onClick={() => setSimulating(true)}>
            Check in the simulator
          </Button>
          <ActionButton decision={decision} what={base ? `Save ${base.name}` : 'Create the policy'} variant="primary" icon="check" loading={busy} disabled={problems.length > 0 || !readable || moved !== null} onClick={() => void save()}>
            {base ? 'Save new version' : 'Create policy'}
          </ActionButton>
        </div>
      </form>
      {simulating && (
        <Drawer onClose={() => setSimulating(false)} width="max-w-3xl" label={`Check ${doc.name || 'this policy'} in the simulator`}>
          <OverlayHeader title={`Check ${doc.name || 'this policy'} in the simulator`} icon="play" subtitle="Your unsaved document is laid over the account. Nothing is saved." />
          <OverlayBody>
            <Simulator drafts={[{ name: doc.name || 'draft', statements: inputOf(doc).statements }]} actions={[...new Set(doc.statements.flatMap((s) => s.actions))].filter((a) => !a.includes('*')).slice(0, 10)} idPrefix="editor-sim" />
          </OverlayBody>
          <OverlayFooter>
            <Button data-autofocus onClick={() => setSimulating(false)}>
              Back to the editor
            </Button>
          </OverlayFooter>
        </Drawer>
      )}
    </>
  )
}
