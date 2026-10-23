import { useCallback, type ReactNode } from 'react'
import { Checkbox, useToast, type Tone } from '@iaf/ui'
import { DotTag } from '@iaf/console-kit'
import { useConsole } from '../../app/console'
import { useRefusal } from '../../app/Refusal'
import { RoleTag } from '../../app/RoleTag'
import { Link, pathFor } from '../../app/router'
import { isApiError } from '../../lib/api'
import { adminOnly, type Decision } from '../../lib/permissions'
import { invalidate } from '../../lib/queries'
import { MANAGED_ROLES, ROLE_SUMMARY, type ManagedRole } from '../../lib/session'
import type { AccessKey, AccessRequestStatus, IamGroup, IamUser } from '../../lib/types'

/*
 * What the identity and access pages share: role and group chips, the pickers, the status tags,
 * the client-side "admins only" decision that greys a write before it is tried, and the one
 * way every IAM write runs (a refusal opens the dialog, anything else is a toast, and the IAM
 * queries are read again).
 */

const RANK: Record<string, number> = { admin: 0, operator: 1, auditor: 2, viewer: 3 }

/** admin first, then the order people read them in */
export function sortRoles(roles: Iterable<string>): string[] {
  return Array.from(new Set(roles)).sort((a, b) => (RANK[a] ?? 9) - (RANK[b] ?? 9) || a.localeCompare(b))
}

/** the roles a person holds through the groups they are in */
export function rolesFromGroups(groupIds: readonly string[] | undefined, groups: readonly IamGroup[]): string[] {
  const ids = new Set(groupIds ?? [])
  return sortRoles(groups.filter((g) => ids.has(g.groupId) || ids.has(g.name)).flatMap((g) => g.roles ?? []))
}

export function groupIdsOf(user: Pick<IamUser, 'groups'> | undefined, groups: readonly IamGroup[]): string[] {
  return (user?.groups ?? []).map((g) => groups.find((x) => x.name === g || x.groupId === g)?.groupId ?? g)
}

export function personLabel(p: { name?: string; email?: string; userId: string }): string {
  return p.name ?? p.email ?? p.userId
}

export function RoleTags({ roles, link, size = 'sm', empty = 'none' }: { roles: readonly string[] | undefined; link?: boolean; size?: 'sm' | 'md'; empty?: string }) {
  const list = sortRoles(roles ?? [])
  if (list.length === 0) return <span className="text-caption text-tertiary">{empty}</span>
  return (
    <span className="flex flex-wrap gap-1">
      {list.map((r) =>
        link ? (
          <Link key={r} to={pathFor('iam-role', { id: r })} className="inline-flex rounded-full transition-opacity hover:opacity-70">
            <RoleTag role={r} size={size} />
          </Link>
        ) : (
          <RoleTag key={r} role={r} size={size} />
        ),
      )}
    </span>
  )
}

export function GroupLinks({ groups, all }: { groups: readonly string[] | undefined; all?: readonly IamGroup[] }) {
  if (!groups?.length) return <span className="text-caption text-tertiary">none</span>
  return (
    <span className="flex flex-wrap gap-x-2 gap-y-1">
      {groups.map((g) => {
        const found = all?.find((x) => x.groupId === g || x.name === g)
        return (
          <Link key={g} to={pathFor('iam-group', { id: found?.groupId ?? g })} className="font-mono text-caption text-accent hover:underline">
            {found?.name ?? g}
          </Link>
        )
      })}
    </span>
  )
}

export function GroupPicker({ groups, value, onChange, legend, hint, disabled }: { groups: readonly IamGroup[]; value: readonly string[]; onChange: (next: string[]) => void; legend: string; hint?: ReactNode; disabled?: boolean }) {
  return (
    <fieldset>
      <legend className="text-sm font-medium text-primary">{legend}</legend>
      {hint && <p className="mt-0.5 text-caption text-secondary">{hint}</p>}
      <div className="mt-2 space-y-2">
        {groups.map((g) => (
          <Checkbox
            key={g.groupId}
            id={`pick-group-${g.groupId}`}
            checked={value.includes(g.groupId)}
            disabled={disabled}
            onChange={(on) => onChange(on ? [...value, g.groupId] : value.filter((x) => x !== g.groupId))}
            label={<span className="font-mono">{g.name}</span>}
            description={
              <span className="flex flex-wrap items-center gap-x-2 gap-y-1">
                {g.description && <span>{g.description}</span>}
                <RoleTags roles={g.roles} />
              </span>
            }
          />
        ))}
        {groups.length === 0 && <p className="text-caption text-tertiary">No groups yet.</p>}
      </div>
    </fieldset>
  )
}

export function RolePicker({ value, onChange, legend = 'Roles', hint, disabled }: { value: readonly string[]; onChange: (next: string[]) => void; legend?: string; hint?: ReactNode; disabled?: boolean }) {
  return (
    <fieldset>
      <legend className="text-sm font-medium text-primary">{legend}</legend>
      {hint && <p className="mt-0.5 text-caption text-secondary">{hint}</p>}
      <div className="mt-2 space-y-2">
        {MANAGED_ROLES.map((r: ManagedRole) => (
          <Checkbox key={r} id={`pick-role-${r}`} checked={value.includes(r)} disabled={disabled} onChange={(on) => onChange(on ? sortRoles([...value, r]) : value.filter((x) => x !== r))} label={<RoleTags roles={[r]} />} description={ROLE_SUMMARY[r]} />
        ))}
      </div>
    </fieldset>
  )
}

export function UserStatusTag({ status }: { status: IamUser['status'] }) {
  return (
    <DotTag size="sm" tone={status === 'active' ? 'healthy' : 'warning'}>
      {status}
    </DotTag>
  )
}

const KEY_TONE: Record<AccessKey['status'], Tone> = { active: 'healthy', rotating: 'warning', revoked: 'neutral', expired: 'critical' }
export function KeyStatusTag({ status }: { status: AccessKey['status'] }) {
  return (
    <DotTag size="sm" tone={KEY_TONE[status]}>
      {status}
    </DotTag>
  )
}

const REQUEST_TONE: Record<AccessRequestStatus, Tone> = { pending: 'warning', approved: 'healthy', rejected: 'critical', expired: 'neutral' }
export function RequestStatusTag({ status }: { status: AccessRequestStatus }) {
  return (
    <span data-status={status} className="inline-flex">
      <DotTag size="sm" pulse={status === 'pending'} tone={REQUEST_TONE[status]}>
        {status}
      </DotTag>
    </span>
  )
}

/** The client-side "admins only" decision for IAM writes; the server's answer still wins. */
export function useAdminDecision(): (action: string, what: string) => Decision {
  const { me } = useConsole()
  const roles = me.roles ?? []
  return (action, what) => adminOnly(roles, action, what)
}

export interface WriteDone {
  title: string
  message?: ReactNode
}

/**
 * Runs an IAM write. A 403 with a Denial opens the refusal dialog (the server named the layer
 * and the statement); any other failure is a toast. On success the IAM, access-request, type
 * decision and account queries are read again, since any of them may have changed.
 */
export function useIamWrite(): <T>(what: string, fn: () => Promise<T>, done?: WriteDone) => Promise<T | undefined> {
  const refusal = useRefusal()
  const toast = useToast()
  return useCallback(
    async <T,>(what: string, fn: () => Promise<T>, done?: WriteDone): Promise<T | undefined> => {
      try {
        const result = await fn()
        for (const prefix of ['iam', 'access-requests', 'console-types', 'accounts']) invalidate(prefix)
        if (done) toast.success(done.title, done.message)
        return result
      } catch (e) {
        if (!refusal.showError(e, what)) toast.error(`Could not ${what.charAt(0).toLowerCase()}${what.slice(1)}`, isApiError(e) ? e.error : String(e))
        return undefined
      }
    },
    [refusal, toast],
  )
}

export function lowerFirst(s: string): string {
  return s.charAt(0).toLowerCase() + s.slice(1)
}
