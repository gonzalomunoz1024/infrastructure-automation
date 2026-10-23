import { useState } from 'react'
import { Button, Checkbox, Field, Input, Modal, OverlayBody, OverlayFooter, OverlayHeader, Select, useToast } from '@iaf/ui'
import { Facts, Section, type Problem } from '@iaf/console-kit'
import { useConsole } from '../../../app/console'
import { ActionButton, useRefusal } from '../../../app/Refusal'
import { Link, navigate, pathFor } from '../../../app/router'
import { isApiError } from '../../../lib/api'
import { bff } from '../../../lib/bff'
import { policiesApi } from '../../../lib/policies'
import { invalidate, useQuery } from '../../../lib/queries'
import { ADMIN_ROLE, isManagedRole, roleLabel } from '../../../lib/session'
import { useSubmissionKey } from '../../../lib/submission'
import type { IamGroup, IamPolicy, IamRole, IamUser } from '../../../lib/types'
import { GroupPicker, rolesFromGroups, useAdminDecision } from '../common'
import { PolicyName } from './parts'

/*
 * Roles in phase 7: a custom role made from the account's policies, with a permission boundary
 * and the groups that grant it; on a role's page, its policies attached and detached and its
 * boundary set, changed or removed, each against the version read; on a person's page, the
 * boundaries over the roles they hold.
 */

/** Reads the IAM queries again after a write: roles, policies, groups, users and the type decisions. */
export function refreshIam() {
  for (const prefix of ['iam', 'console-types', 'accounts']) invalidate(prefix)
}

/** A write's failure: a refusal opens the dialog (true), anything else is the words to show. */
function useFailure(): (e: unknown, what: string) => string | null {
  const refusal = useRefusal()
  return (e, what) => (refusal.showError(e, what) ? null : isApiError(e) ? e.error : String(e))
}

/** Puts a role into each group that should grant it, each against the version read. */
async function grantTo(groups: readonly IamGroup[], roleId: string) {
  for (const g of groups) await bff.iam.groups.update(g.groupId, { roles: [...(g.roles ?? []), roleId] }, g.version)
}

export function CreateRoleDialog({ onClose, presetPolicies = [], onCreated }: { onClose: () => void; presetPolicies?: string[]; onCreated?: (role: IamRole) => void }) {
  const { account } = useConsole()
  const toast = useToast()
  const keyFor = useSubmissionKey()
  const failure = useFailure()
  const policies = useQuery(['iam', 'policies', account.id], () => bff.iam.policies.list({ limit: 200 }))
  const groups = useQuery(['iam', 'groups', account.id], () => bff.iam.groups.list({ limit: 200 }))
  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const [chosen, setChosen] = useState<string[]>(presetPolicies)
  const [boundary, setBoundary] = useState('')
  const [grantedBy, setGrantedBy] = useState<string[]>([])
  const [problems, setProblems] = useState<Problem[]>([])
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const all = policies.data?.items ?? []
  const nameProblem = problems.find((p) => p.field === 'name')?.message ?? (name && !/^[a-z0-9-]{2,64}$/.test(name) ? 'name matches ^[a-z0-9-]{2,64}$' : undefined)
  const boundaryName = boundary ? (all.find((p) => p.policyId === boundary)?.name ?? boundary) : 'None'

  const submit = async () => {
    if (!name || nameProblem) return
    setBusy(true)
    setError(null)
    try {
      const body = { name, ...(description.trim() ? { description: description.trim() } : {}), policies: chosen, ...(boundary ? { boundary } : {}) }
      const role = await policiesApi.roles.create(body, keyFor(body))
      await grantTo((groups.data?.items ?? []).filter((g) => grantedBy.includes(g.groupId)), role.roleId)
      refreshIam()
      toast.success('Role created', grantedBy.length ? `${role.name} is granted by ${grantedBy.length === 1 ? 'one group' : `${grantedBy.length} groups`}.` : `${role.name} is granted by no group yet.`)
      if (onCreated) onCreated(role)
      else navigate(pathFor('iam-role', { id: role.roleId }))
    } catch (e) {
      if (isApiError(e) && e.problems?.length) setProblems(e.problems)
      setError(failure(e, 'Create the role'))
    } finally {
      setBusy(false)
    }
  }

  return (
    <Modal onClose={onClose} width="max-w-xl" label="Create a role">
      <OverlayHeader title="Create a role" icon="shield" subtitle="A bundle of policies that groups grant. People never hold a role directly." />
      <OverlayBody>
        <form
          id="create-role-form"
          noValidate
          className="space-y-5"
          onSubmit={(e) => {
            e.preventDefault()
            void submit()
          }}
        >
          <Field label="Name" required htmlFor="role-name" hint="2 to 64 lowercase letters, digits or -" error={nameProblem}>
            <Input id="role-name" data-autofocus mono value={name} invalid={!!nameProblem} onChange={(e) => setName(e.target.value)} autoComplete="off" spellCheck={false} />
          </Field>
          <Field label="Description" htmlFor="role-description">
            <Input id="role-description" value={description} onChange={(e) => setDescription(e.target.value)} />
          </Field>
          <fieldset>
            <legend className="text-sm font-medium text-primary">Policies</legend>
            <div className="mt-2 space-y-2">
              {all.map((p) => (
                <Checkbox key={p.policyId} id={`role-policy-${p.policyId}`} checked={chosen.includes(p.policyId)} onChange={(on) => setChosen(on ? [...chosen, p.policyId] : chosen.filter((x) => x !== p.policyId))} label={<span className="font-mono">{p.name}</span>} description={p.description} />
              ))}
            </div>
          </fieldset>
          <Field label="Permission boundary" htmlFor="role-boundary" hint="Its allows count only where this policy allows too">
            <Select ariaLabel={`Permission boundary: ${boundaryName}`} value={boundary} onChange={setBoundary} options={[{ value: '', label: 'None' }, ...all.map((p) => ({ value: p.policyId, label: p.name }))]} />
          </Field>
          <GroupPicker groups={groups.data?.items ?? []} value={grantedBy} onChange={setGrantedBy} legend="Groups that grant it" hint="Their members hold the role from their next request." />
          {error && (
            <p role="alert" className="rounded-md bg-critical-tint px-3 py-2 text-sm text-primary">
              {error}
            </p>
          )}
        </form>
      </OverlayBody>
      <OverlayFooter>
        <Button onClick={onClose}>Cancel</Button>
        <Button type="submit" form="create-role-form" variant="primary" icon="plus" loading={busy} disabled={!name || !!nameProblem}>
          Create role
        </Button>
      </OverlayFooter>
    </Modal>
  )
}

/** Sets, changes or removes a role's boundary, against the version read. */
function BoundaryDialog({ role, policies, onClose }: { role: IamRole; policies: readonly IamPolicy[]; onClose: () => void }) {
  const toast = useToast()
  const failure = useFailure()
  const [value, setValue] = useState(role.boundary ?? '')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const label = value ? (policies.find((p) => p.policyId === value)?.name ?? value) : 'None'
  const save = async () => {
    setBusy(true)
    setError(null)
    try {
      const saved = await policiesApi.roles.update(role.roleId, { boundary: value }, role.version)
      refreshIam()
      toast.success(saved.boundary ? 'Permission boundary set' : 'Permission boundary removed', saved.boundary ? `${roleLabel(role.name)} now allows only what ${label} allows too.` : `${roleLabel(role.name)} is no longer bounded.`)
      onClose()
    } catch (e) {
      setError(isApiError(e) && e.status === 409 && /moved on/.test(e.error) ? 'Someone changed this role first. Close this; the page has the latest, then try again.' : failure(e, 'Change the permission boundary'))
      if (isApiError(e) && e.status === 409) refreshIam()
    } finally {
      setBusy(false)
    }
  }
  return (
    <Modal onClose={onClose} width="max-w-lg" label={`Permission boundary of ${roleLabel(role.name)}`}>
      <OverlayHeader title={`Permission boundary of ${roleLabel(role.name)}`} icon="shield" subtitle={`Version ${role.version}. An allow of the role counts only where the boundary allows it too and none of its denies match.`} />
      <OverlayBody className="space-y-4">
        <Field label="Boundary" htmlFor="boundary-policy">
          <Select ariaLabel={`Boundary: ${label}`} value={value} onChange={setValue} options={[{ value: '', label: 'None' }, ...policies.map((p) => ({ value: p.policyId, label: p.name, hint: p.description }))]} />
        </Field>
        {error && (
          <p role="alert" className="rounded-md bg-critical-tint px-3 py-2 text-sm text-primary">
            {error}
          </p>
        )}
      </OverlayBody>
      <OverlayFooter>
        <Button onClick={onClose}>Cancel</Button>
        <Button variant="primary" icon="check" loading={busy} disabled={value === (role.boundary ?? '')} onClick={() => void save()}>
          Save boundary
        </Button>
      </OverlayFooter>
    </Modal>
  )
}

/** Attaches one of the account's policies to a custom role. */
export function AttachDialog({ role, policies, onClose, fixedPolicy }: { role?: IamRole; policies: readonly IamPolicy[]; onClose: () => void; fixedPolicy?: IamPolicy }) {
  const { account } = useConsole()
  const toast = useToast()
  const failure = useFailure()
  const roles = useQuery(['iam', 'roles', account.id], () => bff.iam.roles.list({ limit: 200 }))
  const customRoles = (roles.data?.items ?? []).filter((r) => r.builtIn === false && (!fixedPolicy || !r.policies?.includes(fixedPolicy.policyId)))
  const [roleId, setRoleId] = useState(role?.roleId ?? '')
  const [policyId, setPolicyId] = useState(fixedPolicy?.policyId ?? '')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const target = role ?? customRoles.find((r) => r.roleId === roleId)
  const candidates = policies.filter((p) => !target?.policies?.includes(p.policyId))
  const policy = fixedPolicy ?? policies.find((p) => p.policyId === policyId)
  const attach = async () => {
    if (!target || !policy) return
    setBusy(true)
    setError(null)
    try {
      await policiesApi.roles.update(target.roleId, { policies: [...(target.policies ?? []), policy.policyId] }, target.version)
      refreshIam()
      toast.success('Policy attached', `${roleLabel(target.name)} carries ${policy.name}; whoever holds the role is decided by it from their next request.`)
      onClose()
    } catch (e) {
      setError(failure(e, 'Attach the policy'))
    } finally {
      setBusy(false)
    }
  }
  return (
    <Modal onClose={onClose} width="max-w-lg" label="Attach a policy to a role">
      <OverlayHeader title="Attach a policy to a role" icon="link" subtitle="Managed roles keep their policies; a custom role takes any of the account’s." />
      <OverlayBody className="space-y-4">
        {role ? (
          <Facts items={[{ label: 'Role', value: roleLabel(role.name) }]} />
        ) : (
          <Field label="Role" htmlFor="attach-role" hint={customRoles.length ? undefined : 'No custom role can take it yet: create one with it instead.'}>
            <Select ariaLabel={`Role: ${target ? roleLabel(target.name) : 'choose one'}`} value={roleId} onChange={setRoleId} placeholder="Choose a role" options={customRoles.map((r) => ({ value: r.roleId, label: roleLabel(r.name), hint: r.description }))} />
          </Field>
        )}
        {fixedPolicy ? (
          <Facts items={[{ label: 'Policy', value: fixedPolicy.name }]} />
        ) : (
          <Field label="Policy" htmlFor="attach-policy">
            <Select ariaLabel={`Policy: ${policy?.name ?? 'choose one'}`} value={policyId} onChange={setPolicyId} placeholder="Choose a policy" options={candidates.map((p) => ({ value: p.policyId, label: p.name, hint: p.description }))} />
          </Field>
        )}
        {error && (
          <p role="alert" className="rounded-md bg-critical-tint px-3 py-2 text-sm text-primary">
            {error}
          </p>
        )}
      </OverlayBody>
      <OverlayFooter>
        <Button onClick={onClose}>Cancel</Button>
        <Button variant="primary" icon="link" loading={busy} disabled={!target || !policy} onClick={() => void attach()}>
          Attach
        </Button>
      </OverlayFooter>
    </Modal>
  )
}

/** On a role's page: its policies, attached and detached, and its permission boundary. */
export function RoleAccess({ role }: { role: IamRole }) {
  const { account } = useConsole()
  const can = useAdminDecision()
  const toast = useToast()
  const failure = useFailure()
  const policies = useQuery(['iam', 'policies', account.id], () => bff.iam.policies.list({ limit: 200 }))
  const [dialog, setDialog] = useState<'attach' | 'boundary' | null>(null)
  const all = policies.data?.items ?? []
  const custom = role.builtIn === false
  const isAdminRole = role.name === ADMIN_ROLE
  const detach = async (policyId: string) => {
    try {
      await policiesApi.roles.update(role.roleId, { policies: (role.policies ?? []).filter((p) => p !== policyId) }, role.version)
      refreshIam()
      toast.success('Policy detached', `${roleLabel(role.name)} no longer carries ${all.find((p) => p.policyId === policyId)?.name ?? 'it'}.`)
    } catch (e) {
      const words = failure(e, 'Detach the policy')
      if (words) toast.error('Could not detach the policy', words)
    }
  }
  const write = can('iam:update', `Change the role ${roleLabel(role.name)}`)
  const boundary = role.boundary ? all.find((p) => p.policyId === role.boundary) : undefined
  return (
    <>
      <Section
        title="Permission boundary"
        description="The most the role may ever allow: its statements count only where the boundary allows too. A refusal past a boundary cannot be requested."
        actions={
          isAdminRole ? undefined : (
            <ActionButton decision={write} what={`Change the boundary of ${roleLabel(role.name)}`} size="sm" icon="shield" onClick={() => setDialog('boundary')}>
              {role.boundary ? 'Change boundary' : 'Set boundary'}
            </ActionButton>
          )
        }
      >
        <div data-boundary={role.boundary ?? 'none'}>
          {isAdminRole ? (
            <p className="text-sm text-secondary">The Admin role takes no boundary, so an account always has someone who can change everything.</p>
          ) : role.boundary ? (
            <p className="text-sm text-primary">
              Bounded by <PolicyName id={role.boundary} policies={all} />
              {boundary?.description ? <span className="text-secondary">: {boundary.description}</span> : null}.
            </p>
          ) : (
            <p className="text-sm text-secondary">None: the role allows whatever its policies allow.</p>
          )}
          <Link to={pathFor('iam-simulator', {}, { role: role.roleId })} className="mt-2 inline-flex text-caption font-medium text-accent hover:underline">
            Simulate what this role may do
          </Link>
        </div>
      </Section>
      {custom && (
        <Section
          title="Attached policies"
          description="A custom role carries any of the account’s policies. Detaching one takes effect from the next request."
          actions={
            <ActionButton decision={write} what={`Attach a policy to ${roleLabel(role.name)}`} size="sm" icon="link" onClick={() => setDialog('attach')}>
              Attach a policy
            </ActionButton>
          }
        >
          <ul className="space-y-2" aria-label={`Policies of ${roleLabel(role.name)}`}>
            {(role.policies ?? []).map((id) => (
              <li key={id} className="flex flex-wrap items-center justify-between gap-2 rounded-md border border-stroke-light px-3 py-2">
                <PolicyName id={id} policies={all} />
                <ActionButton decision={write} what="Detach the policy" size="sm" variant="ghost" icon="close" onClick={() => void detach(id)}>
                  Detach
                </ActionButton>
              </li>
            ))}
            {!(role.policies ?? []).length && <li className="text-caption text-tertiary">No policy yet: the role allows nothing.</li>}
          </ul>
        </Section>
      )}
      {dialog === 'boundary' && <BoundaryDialog role={role} policies={all} onClose={() => setDialog(null)} />}
      {dialog === 'attach' && <AttachDialog role={role} policies={all} onClose={() => setDialog(null)} />}
    </>
  )
}

/** On a person's page: the roles they hold and the boundary over each. Boundaries are set on roles. */
export function UserBoundaries({ user }: { user: IamUser }) {
  const { account } = useConsole()
  const roles = useQuery(['iam', 'roles', account.id], () => bff.iam.roles.list({ limit: 200 }))
  const groups = useQuery(['iam', 'groups', account.id], () => bff.iam.groups.list({ limit: 200 }))
  const policies = useQuery(['iam', 'policies', account.id], () => bff.iam.policies.list({ limit: 200 }))
  const held = rolesFromGroups(user.groups, groups.data?.items ?? [])
  const all = roles.data?.items ?? []
  const name = user.name ?? user.email
  return (
    <Section
      title="Permission boundaries"
      description={`What bounds each role ${name} holds. A boundary belongs to a role, so it bounds everyone the role is granted to.`}
      actions={
        <Link to={pathFor('iam-simulator', {}, { user: user.userId })} className="text-caption font-medium text-accent hover:underline">
          Simulate what {name} may do
        </Link>
      }
    >
      <ul className="space-y-1.5 text-sm" aria-label={`Boundaries over the roles of ${name}`}>
        {held.map((id) => {
          const r = all.find((x) => x.roleId === id || x.name === id)
          return (
            <li key={id} className="flex flex-wrap items-center gap-x-2" data-user-role={r?.name ?? id}>
              <Link to={pathFor('iam-role', { id: r?.roleId ?? id })} className="font-medium text-accent hover:underline">
                {roleLabel(r?.name ?? id)}
              </Link>
              <span className="text-secondary">{r?.boundary ? <>bounded by <PolicyName id={r.boundary} policies={policies.data?.items} /></> : isManagedRole(r?.name ?? id) && (r?.name ?? id) === ADMIN_ROLE ? 'never bounded' : 'no boundary'}</span>
            </li>
          )
        })}
        {!held.length && <li className="text-caption text-tertiary">No role in this account.</li>}
      </ul>
    </Section>
  )
}
