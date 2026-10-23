import { useMemo, useState } from 'react'
import { Button, Checkbox, ConfirmDialog, EmptyState, Field, Input, Modal, OverlayBody, OverlayFooter, OverlayHeader, PageHeader, SkeletonRows, Textarea, useOverlayClose, useToast } from '@iaf/ui'
import { CursorPager, ErrorState, Facts, Section, VirtualTable, WfrnText, When, type Problem, type VirtualColumn } from '@iaf/console-kit'
import { useConsole } from '../../app/console'
import { FailureNote, useCommand } from '../../app/commands'
import { ActionButton, useRefusal } from '../../app/Refusal'
import { Link, navigate, pathFor, resourcePath, type PageProps } from '../../app/router'
import { isApiError } from '../../lib/api'
import { singularOf } from '../../lib/descriptors'
import { useKeyedPaging } from '../../lib/keyed'
import { denialOf } from '../../lib/permissions'
import { invalidate, useQuery } from '../../lib/queries'
import { useSubmissionKey } from '../../lib/submission'
import { groupChangeProblems, groupDecision, newGroupProblems, newTagRow, tagsApi, tagsText, type ResourceGroup, type ResourceGroupQuery, type TagRow } from '../../lib/tags'
import type { ConsoleResource } from '../../lib/types'
import { TagRows } from '../tags/TagRows'

/*
 * Resource groups (WP 4.3), as AWS Resource Groups keeps them: a saved tag query over the
 * account's resources in this region (the types, every type when none, and the tags a resource
 * carries), with the resources it matches. The list creates one; a group's page shows its query
 * and its resources, changes its description and query against the version it read (a group that
 * moved on is reloaded, and says so), and deletes it once its name is typed. Writes are greyed for
 * the roles that cannot make them before any call, and a refusal shows the BFF's Denial.
 */

const GROUP_PAGE = 50

/** A group's query in words: "Virtual machines and Volumes carrying team=web". */
export function queryWords(query: ResourceGroupQuery, titleOf: (type: string) => string): string {
  const types = query.types.length ? query.types.map(titleOf).join(', ') : 'Every type'
  return `${types} carrying ${tagsText(query.tags) || 'any tag'}`
}

/** The problems of a group's body by the field the form shows them on: name, description, types, tags. */
export function problemsByField(problems: readonly Problem[]): { name?: string; description?: string; types?: string; tags?: string; byTag: Record<string, string> } {
  const out: { name?: string; description?: string; types?: string; tags?: string; byTag: Record<string, string> } = { byTag: {} }
  for (const p of problems) {
    const f = p.field ?? ''
    if (f === '/name') out.name ??= p.message
    else if (f === '/description') out.description ??= p.message
    else if (f.startsWith('/query/types')) out.types ??= p.message
    else if (f === '/query/tags') out.tags ??= p.message
    else if (f.startsWith('/query/tags/')) out.byTag[f.slice('/query/tags/'.length).replace(/~1/g, '/').replace(/~0/g, '~')] ??= p.message
  }
  return out
}

interface GroupDraft {
  name: string
  description: string
  types: string[]
  rows: TagRow[]
}

function draftOfGroup(g?: ResourceGroup): GroupDraft {
  return { name: g?.name ?? '', description: g?.description ?? '', types: g?.query.types ?? [], rows: g ? Object.entries(g.query.tags).map(([k, v]) => newTagRow(k, v)) : [newTagRow()] }
}

function queryOf(d: GroupDraft): ResourceGroupQuery {
  return { types: d.types, tags: Object.fromEntries(d.rows.filter((r) => r.key.trim()).map((r) => [r.key.trim(), r.value])) }
}

function CloseButton({ label = 'Cancel' }: { label?: string }) {
  const close = useOverlayClose()
  return <Button onClick={close}>{label}</Button>
}

/** The form of a group: its name (on create only), its description, and its query. */
function GroupForm({ id, draft, onChange, problems, creating }: { id: string; draft: GroupDraft; onChange: (d: GroupDraft) => void; problems: Problem[]; creating: boolean }) {
  const { types } = useConsole()
  const by = problemsByField(problems)
  const rowErrors = Object.fromEntries(draft.rows.filter((r) => by.byTag[r.key.trim()]).map((r) => [r.id, { key: by.byTag[r.key.trim()] }]))
  return (
    <div className="space-y-5">
      {creating ? (
        <Field label="Name" required htmlFor={`${id}-name`} hint="Letters, digits, '.', '_' or '-', up to 128; it never changes." error={by.name}>
          <Input id={`${id}-name`} data-autofocus value={draft.name} invalid={!!by.name} onChange={(e) => onChange({ ...draft, name: e.target.value })} autoComplete="off" spellCheck={false} />
        </Field>
      ) : null}
      <Field label="Description" hint="Optional, up to 512 characters." htmlFor={`${id}-description`} error={by.description}>
        <Textarea id={`${id}-description`} data-autofocus={creating ? undefined : true} rows={2} value={draft.description} onChange={(e) => onChange({ ...draft, description: e.target.value })} />
      </Field>
      <fieldset>
        <legend className="text-caption font-medium text-secondary">Types</legend>
        <p className="mt-0.5 text-caption text-tertiary">None chosen matches every type.</p>
        <div className="mt-2 grid gap-2 sm:grid-cols-2">
          {types.map((t) => (
            <Checkbox key={t.name} checked={draft.types.includes(t.name)} label={t.title} onChange={(on) => onChange({ ...draft, types: on ? [...draft.types, t.name] : draft.types.filter((x) => x !== t.name) })} />
          ))}
        </div>
        {by.types && <p className="mt-1 text-caption text-critical">{by.types}</p>}
      </fieldset>
      <fieldset>
        <legend className="text-caption font-medium text-secondary">Tags a resource carries</legend>
        <p className="mb-2 mt-0.5 text-caption text-tertiary">At least one and at most 20: a resource belongs to the group when it carries every one.</p>
        <TagRows rows={draft.rows} onChange={(rows) => onChange({ ...draft, rows })} errors={rowErrors} idPrefix={`${id}-tags`} addLabel="Add a tag" />
        {by.tags && <p className="mt-1 text-caption text-critical">{by.tags}</p>}
      </fieldset>
    </div>
  )
}

function CreateGroupDialog({ onClose }: { onClose: () => void }) {
  const { types } = useConsole()
  const cmd = useCommand()
  const toast = useToast()
  const keyFor = useSubmissionKey()
  const [draft, setDraft] = useState<GroupDraft>(() => draftOfGroup())
  const [problems, setProblems] = useState<Problem[]>([])
  const known = useMemo(() => new Set(types.map((t) => t.name)), [types])
  const submit = async () => {
    const body = { name: draft.name.trim(), description: draft.description, query: queryOf(draft) }
    const found = newGroupProblems(body, known)
    setProblems(found)
    if (found.length) return
    const created = await cmd.run('Create the resource group', () => tagsApi.groups.create(body, keyFor(body)), { quiet: true, invalidates: ['resource-groups'], onProblems: setProblems })
    if (!created) return
    toast.success('Resource group created', `${created.name} holds the resources that carry ${tagsText(created.query.tags)}.`)
    navigate(pathFor('group', { id: created.groupId }))
  }
  return (
    <Modal onClose={onClose} width="max-w-xl" label="Create a resource group">
      <OverlayHeader title="Create a resource group" icon="layers" subtitle="A saved tag query: the resources of the types chosen that carry every tag named." />
      <OverlayBody>
        <form
          id="create-group-form"
          noValidate
          onSubmit={(e) => {
            e.preventDefault()
            void submit()
          }}
        >
          <GroupForm id="create-group" draft={draft} onChange={setDraft} problems={problems} creating />
          <FailureNote failure={cmd.failure} shownFields={['name', 'description', 'query']} onDismiss={cmd.clear} className="mt-5" />
        </form>
      </OverlayBody>
      <OverlayFooter>
        <CloseButton />
        <Button type="submit" form="create-group-form" variant="primary" icon="plus" loading={cmd.busy}>
          Create group
        </Button>
      </OverlayFooter>
    </Modal>
  )
}

export default function ResourceGroupsPage(_props: PageProps) {
  const { account, region, types } = useConsole()
  const refusal = useRefusal()
  const [creating, setCreating] = useState(false)
  const groups = useQuery(['resource-groups', account.id, region.name], () => tagsApi.groups.list())
  const create = groupDecision(account.roles, 'create', 'Create resource groups')
  const titleOf = (name: string) => types.find((t) => t.name === name)?.title ?? name
  const denied = denialOf(groups.error)
  const items = groups.data?.items ?? []
  const createButton = (
    <ActionButton decision={create} what="Create a resource group" verb="create" variant="primary" icon="plus" onClick={() => setCreating(true)}>
      Create group
    </ActionButton>
  )
  return (
    <>
      <PageHeader title="Resource groups" subtitle="Resources that share tags, kept as one group across every type." actions={createButton} />
      {denied ? (
        <EmptyState
          icon="lock"
          title="You can’t list resource groups here"
          message={denied.reason}
          action={
            <Button size="sm" variant="primary" onClick={() => refusal.show({ denied, what: 'List resource groups' })}>
              See why
            </Button>
          }
        />
      ) : groups.error && !groups.data ? (
        <ErrorState error={groups.error} title="Could not load the resource groups" onRetry={groups.refetch} />
      ) : groups.loading && !groups.data ? (
        <SkeletonRows n={3} />
      ) : items.length === 0 ? (
        <EmptyState icon="layers" title="No resource groups yet" message="A group keeps a tag query, such as every machine and volume that carries team=checkout, so its resources are one click away." action={createButton} />
      ) : (
        <div className="overflow-x-auto rounded-lg border border-stroke-light">
          <table className="w-full min-w-[40rem] text-left text-sm">
            <caption className="sr-only">Resource groups of this account and region</caption>
            <thead className="text-caption text-tertiary">
              <tr>
                <th scope="col" className="px-4 py-2 font-medium">
                  Name
                </th>
                <th scope="col" className="px-4 py-2 font-medium">
                  Query
                </th>
                <th scope="col" className="px-4 py-2 font-medium">
                  Description
                </th>
                <th scope="col" className="px-4 py-2 font-medium">
                  Updated
                </th>
              </tr>
            </thead>
            <tbody className="divide-y divide-stroke-light">
              {items.map((g) => (
                <tr key={g.groupId}>
                  <td className="px-4 py-2.5">
                    <Link to={pathFor('group', { id: g.groupId })} className="font-medium text-accent hover:opacity-70">
                      {g.name}
                    </Link>
                  </td>
                  <td className="px-4 py-2.5 text-secondary">{queryWords(g.query, titleOf)}</td>
                  <td className="px-4 py-2.5 text-secondary">{g.description || <span className="text-tertiary">none</span>}</td>
                  <td className="px-4 py-2.5 text-secondary">
                    <When at={g.updatedAt} />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {creating && <CreateGroupDialog onClose={() => setCreating(false)} />}
    </>
  )
}

function EditGroupDialog({ group, onClose, onMoved }: { group: ResourceGroup; onClose: () => void; onMoved: () => void }) {
  const { types } = useConsole()
  const cmd = useCommand()
  const toast = useToast()
  const [draft, setDraft] = useState<GroupDraft>(() => draftOfGroup(group))
  const [problems, setProblems] = useState<Problem[]>([])
  const [moved, setMoved] = useState<number | null>(null)
  const known = useMemo(() => new Set(types.map((t) => t.name)), [types])
  const submit = async () => {
    const body = { description: draft.description, query: queryOf(draft) }
    const found = groupChangeProblems(body, group, known)
    setProblems(found)
    if (found.length) return
    let wentOn: number | null = null
    const saved = await cmd.run(
      'Change the resource group',
      async () => {
        try {
          return await tagsApi.groups.update(group.groupId, body, group.version)
        } catch (e) {
          // another change landed since this page read the group: read it again rather than overwrite it
          if (isApiError(e) && e.status === 409) {
            const v = (e.detail as { version?: unknown } | undefined)?.version
            wentOn = typeof v === 'number' ? v : group.version + 1
            return null
          }
          throw e
        }
      },
      { quiet: true, onProblems: setProblems },
    )
    if (wentOn !== null) {
      setMoved(wentOn)
      onMoved()
      return
    }
    if (!saved) return
    invalidate('resource-group')
    invalidate('resource-groups')
    invalidate('group-resources')
    toast.success('Resource group changed', saved.version === group.version ? 'Nothing was different, so it keeps its version.' : `${saved.name} is at version ${saved.version}.`)
    onClose()
  }
  return (
    <Modal onClose={onClose} width="max-w-xl" label={`Edit ${group.name}`}>
      <OverlayHeader title={`Edit ${group.name}`} icon="edit" subtitle={`Version ${group.version}. The name never changes; create another group for a new one.`} />
      <OverlayBody>
        <form
          id="edit-group-form"
          noValidate
          onSubmit={(e) => {
            e.preventDefault()
            void submit()
          }}
        >
          {moved !== null && (
            <p className="mb-5 rounded-md bg-warning-tint px-3 py-2 text-sm text-primary" role="alert">
              {`Someone changed this group while you edited it (it is at version ${moved} now). The page has read it again; close this, check what changed, and edit again.`}
            </p>
          )}
          <GroupForm id="edit-group" draft={draft} onChange={setDraft} problems={problems} creating={false} />
          <FailureNote failure={cmd.failure} shownFields={['description', 'query']} onDismiss={cmd.clear} className="mt-5" />
        </form>
      </OverlayBody>
      <OverlayFooter>
        <CloseButton label={moved !== null ? 'Close' : 'Cancel'} />
        <Button type="submit" form="edit-group-form" variant="primary" icon="check" loading={cmd.busy} disabled={moved !== null}>
          Save changes
        </Button>
      </OverlayFooter>
    </Modal>
  )
}

export function ResourceGroupPage({ route }: PageProps) {
  const { account, region, types } = useConsole()
  const refusal = useRefusal()
  const toast = useToast()
  const cmd = useCommand()
  const id = route.params.id
  const group = useQuery(['resource-group', account.id, region.name, id], () => tagsApi.groups.get(id))
  const paging = useKeyedPaging(`${account.id}|${region.name}|${id}`)
  const resources = useQuery(['group-resources', id, paging.after ?? null], () => tagsApi.groups.resources(id, { limit: GROUP_PAGE, after: paging.after }), { enabled: !!group.data })
  const [editing, setEditing] = useState(false)
  const typeOf = (name: string) => types.find((t) => t.name === name)
  const titleOf = (name: string) => typeOf(name)?.title ?? name
  const go = (query: Record<string, string | undefined>) => navigate(pathFor('group', route.params, { ...route.query, ...query }, { accountId: route.accountId }), { replace: true })
  const update = groupDecision(account.roles, 'update', 'Change resource groups')
  const remove = groupDecision(account.roles, 'delete', 'Delete resource groups')
  const g = group.data
  const denied = denialOf(group.error)

  if (!g) {
    if (group.loading) return <SkeletonRows n={4} />
    return (
      <>
        <PageHeader eyebrow="Resource group" title={id} />
        {denied ? (
          <EmptyState icon="lock" title="You can’t read this resource group" message={denied.reason} action={<Button size="sm" variant="primary" onClick={() => refusal.show({ denied, what: 'Read this resource group' })}>See why</Button>} />
        ) : isApiError(group.error) && group.error.status === 404 ? (
          <EmptyState icon="layers" title="No such resource group" message="It is not a group of this account and region, or it was deleted." action={<Link to={pathFor('groups')} className="text-sm font-medium text-accent hover:opacity-70">All resource groups</Link>} />
        ) : (
          <ErrorState error={group.error} title="Could not load the resource group" onRetry={group.refetch} />
        )}
      </>
    )
  }

  const columns: VirtualColumn<ConsoleResource>[] = [
    {
      key: 'name',
      header: 'Name',
      width: 'minmax(10rem, 1.2fr)',
      accessor: (r) => r.name ?? r.wfrn,
      render: (r) => {
        const t = typeOf(r.type)
        return (
          <Link to={resourcePath(t?.service ?? r.service, r.type, r.wfrn)} className="truncate font-medium text-accent hover:opacity-70" onClick={(e) => e.stopPropagation()}>
            {r.name ?? r.wfrn}
          </Link>
        )
      },
    },
    { key: 'type', header: 'Type', width: 'minmax(8rem, 0.8fr)', accessor: (r) => singularOf(titleOf(r.type)) },
    { key: 'state', header: 'State', width: 'minmax(7rem, 0.6fr)', accessor: (r) => r.state },
    { key: 'wfrn', header: 'wfrn', width: 'minmax(14rem, 1.6fr)', minRem: 14, accessor: (r) => r.wfrn, render: (r) => <WfrnText wfrn={r.wfrn} max={36} /> },
  ]

  const deleteGroup = async () => {
    const done = await cmd.run(`Delete ${g.name}`, () => tagsApi.groups.remove(g.groupId).then(() => true), { quiet: true, invalidates: ['resource-groups'] })
    if (!done) throw new Error(cmd.failure?.message ?? 'The group was not deleted.')
    toast.success('Resource group deleted', `${g.name} is gone; its resources are untouched.`)
    navigate(pathFor('groups'), { replace: true })
  }

  return (
    <>
      <PageHeader
        eyebrow="Resource group"
        title={g.name}
        subtitle={g.description || undefined}
        actions={
          <div className="flex flex-wrap gap-2">
            <ActionButton decision={update} what="Edit this resource group" verb="update" icon="edit" onClick={() => setEditing(true)}>
              Edit
            </ActionButton>
            <ActionButton decision={remove} what="Delete this resource group" verb="delete" variant="danger" icon="trash" onClick={() => go({ action: 'delete' })}>
              Delete
            </ActionButton>
          </div>
        }
      />
      <Section title="Query" className="mb-8">
        <Facts
          columns={3}
          items={[
            { label: 'Types', value: g.query.types.length ? g.query.types.map(titleOf).join(', ') : 'Every type' },
            { label: 'Tags a resource carries', value: <span className="font-mono text-caption">{tagsText(g.query.tags)}</span> },
            { label: 'Version', value: String(g.version) },
            { label: 'Created', value: <span>{g.createdBy}, <When at={g.createdAt} /></span> },
            { label: 'Updated', value: <When at={g.updatedAt} /> },
            { label: 'wfrn', value: <WfrnText wfrn={g.wfrn} max={48} /> },
          ]}
        />
      </Section>
      <Section title="Resources" description="The resources of its types that carry every tag of its query, of the types you may list.">
        {resources.error && !resources.data ? (
          <ErrorState error={resources.error} title="Could not load the group’s resources" onRetry={resources.refetch} />
        ) : (
          <VirtualTable<ConsoleResource>
            columns={columns}
            rows={resources.data?.items ?? []}
            rowKey={(r) => r.wfrn}
            label={`Resources of ${g.name}`}
            loading={resources.loading}
            maxHeight="max(16rem, calc(100dvh - 30rem))"
            empty={<EmptyState compact icon="layers" title="No resources match" message="No resource of these types carries every tag of the query yet. Tag some in the Tag Editor." action={<Link to={pathFor('tag-editor')} className="text-sm font-medium text-accent hover:opacity-70">Open the Tag Editor</Link>} />}
            footer={
              <CursorPager
                count={resources.data?.items.length ?? 0}
                hasNext={!!resources.data?.next}
                hasPrevious={paging.hasPrevious}
                onNext={() => resources.data?.next && paging.next(resources.data.next)}
                onPrevious={paging.previous}
                pageSize={GROUP_PAGE}
                page={paging.page}
                loading={resources.fetching}
                noun="resource"
              />
            }
          />
        )}
      </Section>
      {editing && <EditGroupDialog group={g} onClose={() => setEditing(false)} onMoved={() => void group.refetch()} />}
      {route.query.action === 'delete' && remove.allowed && (
        <ConfirmDialog
          title={`Delete ${g.name}?`}
          message="The group and its query go; the resources it matches are not touched. Type the group's name to confirm."
          confirmLabel="Delete group"
          tone="danger"
          icon="trash"
          requireText={g.name}
          onConfirm={deleteGroup}
          onClose={() => go({ action: undefined })}
        />
      )}
    </>
  )
}
