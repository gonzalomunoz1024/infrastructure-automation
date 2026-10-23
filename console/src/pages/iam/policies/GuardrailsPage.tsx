import { useState } from 'react'
import { Button, Checkbox, PageHeader, Skeleton, SkeletonText, useToast } from '@iaf/ui'
import { ConfirmDialog, DotTag, ErrorState, Facts, Section, When, type Problem } from '@iaf/console-kit'
import { useConsole } from '../../../app/console'
import { ActionButton, useRefusal } from '../../../app/Refusal'
import { Link, href, navigate, pathFor, type PageProps } from '../../../app/router'
import { isApiError } from '../../../lib/api'
import { bff } from '../../../lib/bff'
import type { Decision } from '../../../lib/permissions'
import { guardrailProblems, policiesApi, type Guardrail, type PolicyDoc } from '../../../lib/policies'
import { invalidate, useQuery } from '../../../lib/queries'
import { PolicyEditor, emptyStatement, useActionCatalog } from './editor'
import { StatementList } from './parts'

/*
 * Guardrails (WP 7.8): the organization's statements over its accounts, asked before any role.
 * A guardrail's deny wins over every allow in the accounts it covers; a guardrail with an allow
 * makes an allow-list, and what none of its allows names is outside the guardrails. Everyone who
 * may list IAM in the account sees the guardrails over it; only an organization admin changes
 * them, through the BFF, which writes them as the console's own principal.
 */

function refreshGuardrails() {
  invalidate('iam')
  invalidate('org-guardrails')
}

/** The organization's guardrails when the person is one of its admins; the refusal in words otherwise. */
function useOrgGuardrails() {
  const q = useQuery(['org-guardrails'], () => policiesApi.org.list())
  const refused = q.error && isApiError(q.error) && q.error.status === 403
  const decision = (verb: 'create' | 'update' | 'delete', action: string): Decision => {
    if (q.data?.can.includes(verb)) return { allowed: true }
    return {
      allowed: false,
      denied: {
        layer: 'console-iam',
        action,
        resource: 'wfrn:wf:iam:::guardrail/*',
        reason: 'Only an organization admin changes the organization’s guardrails',
        requestable: false,
        refusedBy: 'policy',
      },
    }
  }
  return { q, admin: !!q.data, refused: !!refused, decision }
}

function accountsWords(accounts: readonly string[], nameOf: (id: string) => string): string {
  if (accounts.includes('*')) return 'Every account, now and later'
  return accounts.map(nameOf).join(', ')
}

function useAccountNames() {
  const accounts = useQuery(['accounts'], () => bff.accounts())
  const list = accounts.data?.items ?? []
  return { list, nameOf: (id: string) => list.find((a) => a.accountId === id)?.name ?? id }
}

function isAllowList(g: Pick<Guardrail, 'statements'>): boolean {
  return g.statements.some((s) => s.effect === 'allow')
}

export default function IamGuardrailsPage(_props: PageProps) {
  const { account } = useConsole()
  const mine = useQuery(['iam', 'guardrails', account.id], () => policiesApi.accountGuardrails())
  const org = useOrgGuardrails()
  const { nameOf } = useAccountNames()
  const over = mine.data?.items ?? []
  const elsewhere = (org.q.data?.items ?? []).filter((g) => !over.some((o) => o.guardrailId === g.guardrailId))
  const row = (g: Guardrail) => (
    <li key={g.guardrailId} className="flex flex-wrap items-start justify-between gap-2 px-4 py-3" data-guardrail-row={g.name}>
      <div className="min-w-0">
        <div className="flex flex-wrap items-center gap-2">
          <Link to={pathFor('iam-guardrail', { id: g.guardrailId })} className="font-mono text-sm font-medium text-accent hover:underline">
            {g.name}
          </Link>
          <DotTag size="sm" tone={isAllowList(g) ? 'warning' : 'critical'}>
            {isAllowList(g) ? 'allow-list' : 'deny'}
          </DotTag>
        </div>
        {g.description && <p className="mt-0.5 text-caption text-secondary">{g.description}</p>}
      </div>
      <p className="text-caption text-secondary">{accountsWords(g.accounts, nameOf)}</p>
    </li>
  )
  return (
    <>
      <PageHeader
        title="Guardrails"
        subtitle={`The organization’s statements over its accounts. They are asked before any role: a guardrail’s deny wins over every allow in ${account.name}.`}
        actions={
          <ActionButton decision={org.decision('create', 'iam:create')} what="Create a guardrail" verb="create" variant="primary" icon="plus" onClick={() => navigate(pathFor('iam-guardrail-new'))}>
            Create guardrail
          </ActionButton>
        }
      />
      <div className="space-y-8">
        <Section title={`Over ${account.name}`} description="Every request in this account is checked against these first.">
          {mine.error ? (
            <ErrorState error={mine.error} title="Could not load the guardrails" onRetry={() => void mine.refetch()} />
          ) : mine.loading ? (
            <SkeletonText lines={3} />
          ) : over.length ? (
            <ul className="divide-y divide-stroke-light rounded-lg border border-stroke-light" aria-label={`Guardrails over ${account.name}`}>
              {over.map(row)}
            </ul>
          ) : (
            <p className="text-sm text-secondary">No guardrail covers this account.</p>
          )}
        </Section>
        <Section title="The rest of the organization" description="Guardrails that cover other accounts only.">
          {org.admin ? (
            elsewhere.length ? (
              <ul className="divide-y divide-stroke-light rounded-lg border border-stroke-light" aria-label="Guardrails over other accounts">
                {elsewhere.map(row)}
              </ul>
            ) : (
              <p className="text-sm text-secondary">Every guardrail of the organization covers this account too.</p>
            )
          ) : (
            <p className="text-sm text-secondary" data-org-locked>
              {org.refused ? 'Only an organization admin sees the guardrails over other accounts, and changes any guardrail.' : 'Loading…'}
            </p>
          )}
        </Section>
      </div>
    </>
  )
}

/* ---------------------------------------------------------------- one guardrail */

export function IamGuardrailPage({ route }: PageProps) {
  const isNew = route.name === 'iam-guardrail-new'
  const id = route.params.id
  const { account } = useConsole()
  const mine = useQuery(['iam', 'guardrails', account.id], () => policiesApi.accountGuardrails())
  const org = useOrgGuardrails()
  const back = { href: href(pathFor('iam-guardrails')), label: 'Guardrails' }
  const found = isNew ? undefined : (org.q.data?.items ?? mine.data?.items ?? []).find((g) => g.guardrailId === id)
  const settled = !org.q.loading && !mine.loading

  if (isNew) {
    if (org.admin) return <GuardrailForm />
    return (
      <>
        <PageHeader back={back} title="Create guardrail" />
        {settled ? (
          <ErrorState error={org.q.error ?? new Error('Only an organization admin creates guardrails.')} title="Only an organization admin creates guardrails" />
        ) : (
          <SkeletonText lines={4} />
        )}
      </>
    )
  }
  if (!found) {
    return (
      <>
        <PageHeader back={back} title={settled ? 'Guardrail' : <Skeleton className="h-7 w-48" />} />
        {settled ? <ErrorState error={mine.error ?? new Error(`No guardrail ${id} covers this account.`)} title="Could not find this guardrail" onRetry={() => void mine.refetch()} /> : <SkeletonText lines={4} />}
      </>
    )
  }
  return <GuardrailView g={found} org={org} />
}

function GuardrailView({ g, org }: { g: Guardrail; org: ReturnType<typeof useOrgGuardrails> }) {
  const [editing, setEditing] = useState(false)
  const [deleting, setDeleting] = useState(false)
  const refusal = useRefusal()
  const toast = useToast()
  const { nameOf } = useAccountNames()
  const back = { href: href(pathFor('iam-guardrails')), label: 'Guardrails' }
  if (editing && org.admin) return <GuardrailForm base={g} onDone={() => setEditing(false)} />
  const del = async () => {
    try {
      await policiesApi.org.remove(g.guardrailId)
      refreshGuardrails()
      toast.success('Guardrail deleted', `${g.name} no longer decides anything.`)
      navigate(pathFor('iam-guardrails'), { replace: true })
    } catch (e) {
      if (refusal.showError(e, `Delete ${g.name}`)) setDeleting(false)
      else throw e
    }
  }
  return (
    <>
      <PageHeader
        back={back}
        eyebrow="Guardrail"
        title={<span className="font-mono">{g.name}</span>}
        subtitle={g.description}
        actions={
          <div className="flex flex-wrap gap-2">
            <ActionButton decision={org.decision('update', 'iam:update')} what={`Edit ${g.name}`} verb="update" icon="edit" onClick={() => setEditing(true)}>
              Edit
            </ActionButton>
            <ActionButton decision={org.decision('delete', 'iam:delete')} what={`Delete ${g.name}`} verb="delete" variant="danger" icon="trash" onClick={() => setDeleting(true)}>
              Delete
            </ActionButton>
          </div>
        }
      />
      <div className="space-y-8">
        <Section title="About">
          <Facts
            columns={3}
            items={[
              { label: 'Covers', value: accountsWords(g.accounts, nameOf) },
              { label: 'Kind', value: isAllowList(g) ? 'An allow-list: what none of its allows names is outside the guardrails' : 'Denies only' },
              { label: 'Version', value: String(g.version) },
              { label: 'Updated', value: g.updatedAt ? <When at={g.updatedAt} /> : 'not yet' },
            ]}
          />
        </Section>
        <Section title="Statements" description="A guardrail’s deny wins over every role’s allow in the accounts it covers.">
          <StatementList statements={g.statements} label={`Statements of ${g.name}`} />
        </Section>
      </div>
      {deleting && <ConfirmDialog title={`Delete ${g.name}?`} message="Every account it covers stops being held by it at once. Type its name to confirm." confirmLabel="Delete guardrail" tone="danger" icon="trash" requireText={g.name} onConfirm={del} onClose={() => setDeleting(false)} />}
    </>
  )
}

function GuardrailForm({ base, onDone }: { base?: Guardrail; onDone?: () => void }) {
  const { account, region } = useConsole()
  const catalog = useActionCatalog()
  const refusal = useRefusal()
  const toast = useToast()
  const { list: accounts, nameOf } = useAccountNames()
  const [doc, setDoc] = useState<PolicyDoc>(() => (base ? { name: base.name, description: base.description ?? '', statements: base.statements } : { name: '', description: '', statements: [{ ...emptyStatement(), effect: 'deny', resources: [`wfrn:${region.partition}:*`] }] }))
  const [scope, setScope] = useState<string[]>(base?.accounts ?? ['*'])
  const [version, setVersion] = useState(base?.version)
  const [server, setServer] = useState<{ doc: PolicyDoc; problems: Problem[] } | null>(null)
  const [readable, setReadable] = useState(true)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const input = { name: doc.name, ...(doc.description ? { description: doc.description } : {}), statements: doc.statements, accounts: scope }
  const known = accounts.map((a) => a.accountId)
  const local = guardrailProblems(input, known.length ? known : scope.filter((a) => a !== '*'), catalog)
  const problems = server && server.doc === doc ? [...local, ...server.problems.filter((p) => !local.some((l) => l.field === p.field))] : local
  const every = scope.includes('*')
  const cancel = () => (onDone ? onDone() : navigate(pathFor('iam-guardrails')))

  const save = async () => {
    if (problems.length || !readable) return
    setBusy(true)
    setError(null)
    try {
      const saved = base && version !== undefined ? await policiesApi.org.update(base.guardrailId, { ...input, description: doc.description }, version) : await policiesApi.org.create(input)
      refreshGuardrails()
      toast.success(base ? 'Guardrail saved' : 'Guardrail created', `${saved.name} now holds ${accountsWords(saved.accounts, nameOf)}.`)
      if (onDone) onDone()
      else navigate(pathFor('iam-guardrail', { id: saved.guardrailId }))
    } catch (e) {
      if (isApiError(e) && e.status === 409 && /moved on/.test(e.error)) {
        const v = (e.detail as { version?: unknown } | undefined)?.version
        setError(`Someone saved this guardrail first: it is at version ${typeof v === 'number' ? v : '?'}. Cancel and open it again to see their change.`)
        if (typeof v === 'number') setVersion(v)
      } else if (isApiError(e) && e.status === 400 && e.problems?.length) {
        setServer({ doc, problems: e.problems.map((p) => ({ ...p, level: 'error' as const })) })
      } else if (!refusal.showError(e, base ? `Save ${doc.name}` : 'Create the guardrail')) {
        setError(isApiError(e) ? e.error : String(e))
      }
    } finally {
      setBusy(false)
    }
  }

  const accountsField = (
    <fieldset>
      <legend className="text-sm font-medium text-primary">Accounts it covers</legend>
      <div className="mt-2 space-y-2">
        <Checkbox id="guardrail-every" checked={every} onChange={(on) => setScope(on ? ['*'] : [account.id])} label="Every account in the organization, now and later" />
        {!every && (
          <div className="grid gap-2 sm:grid-cols-2">
            {accounts.map((a) => (
              <Checkbox key={a.accountId} id={`guardrail-account-${a.accountId}`} checked={scope.includes(a.accountId)} onChange={(on) => setScope(on ? [...scope, a.accountId] : scope.filter((x) => x !== a.accountId))} label={a.name} />
            ))}
          </div>
        )}
        {problems
          .filter((p) => p.field?.startsWith('accounts'))
          .map((p) => (
            <p key={`${p.field}-${p.message}`} className="text-caption text-critical">
              {p.message}
            </p>
          ))}
      </div>
    </fieldset>
  )

  return (
    <>
      <PageHeader back={{ href: href(pathFor('iam-guardrails')), label: 'Guardrails' }} eyebrow={base ? `Version ${version}` : 'New guardrail'} title={base ? `Edit ${base.name}` : 'Create guardrail'} subtitle="Statements over the organization’s accounts, asked before any role. Write denies; an allow makes the guardrail an allow-list." />
      <form
        noValidate
        className="space-y-6"
        onSubmit={(e) => {
          e.preventDefault()
          void save()
        }}
      >
        {isAllowList({ statements: doc.statements }) && (
          <p role="note" className="rounded-md bg-warning-tint px-3 py-2 text-sm text-primary" data-allow-list-warning>
            This guardrail has an allow statement, so it is an allow-list: in every account it covers, anything none of its allows names is refused, whatever the roles say.
          </p>
        )}
        <PolicyEditor doc={doc} onChange={setDoc} problems={problems.filter((p) => !p.field?.startsWith('accounts'))} catalog={catalog} onJsonState={setReadable} idPrefix="guardrail" noun="guardrail" extra={accountsField} />
        {error && (
          <p role="alert" className="rounded-md bg-critical-tint px-3 py-2 text-sm text-primary">
            {error}
          </p>
        )}
        <div className="flex flex-wrap justify-end gap-2 border-t border-stroke-light pt-4">
          <Button type="button" onClick={cancel}>
            Cancel
          </Button>
          <Button type="submit" variant="primary" icon="check" loading={busy} disabled={problems.length > 0 || !readable}>
            {base ? 'Save new version' : 'Create guardrail'}
          </Button>
        </div>
      </form>
    </>
  )
}
