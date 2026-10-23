import { useMemo, useState } from 'react'
import { Button, Drawer, OverlayBody, OverlayFooter, OverlayHeader, SkeletonRows, useOverlayClose } from '@iaf/ui'
import { displayWfrn, type Problem } from '@iaf/console-kit'
import { useConsole } from '../../app/console'
import { FailureNote, useCommand } from '../../app/commands'
import { ActionButton } from '../../app/Refusal'
import { Link, pathFor } from '../../app/router'
import { bff } from '../../lib/bff'
import { singularOf, type ConsoleType } from '../../lib/descriptors'
import { useQuery } from '../../lib/queries'
import { allowedSentence, andList, newTagRow, tagAccepts, tagDefault, TAG_NAME, TAG_VALUE_MAX, tagsApi, type RequiredTag, type TagRow } from '../../lib/tags'
import { TagRows } from '../tags/TagRows'

/*
 * A resource's tags, edited in place (WP 4.3): every tag as a name and a value, saved together
 * with one PUT of the whole set. The tags the account requires stay: their rows keep their name
 * and have no Remove button, and say why; their values are checked against the policy as the
 * person types. A required tag the resource never carried (one older than the policy) is not
 * forced on it, but the drawer offers to add the missing ones with their defaults.
 */

export interface TagsDrawerProps {
  type: ConsoleType
  wfrn: string
  name: string
  tags: Record<string, string>
  onClose: () => void
  onSaved: (tags: Record<string, string>) => void
}

/** The rows a resource's tags start as: the required ones first, in the policy's order, then the rest by name. */
export function rowsOf(tags: Record<string, string>, required: readonly RequiredTag[]): TagRow[] {
  const order = new Map(required.map((t, i) => [t.key, i]))
  return Object.entries(tags)
    .sort(([a], [b]) => (order.get(a) ?? 1e6) - (order.get(b) ?? 1e6) || a.localeCompare(b))
    .map(([k, v]) => newTagRow(k, v))
}

/** What each row gets wrong, by row id: names as the state service takes them (an existing name is kept as it is), values within the policy. */
export function drawerErrors(rows: readonly TagRow[], before: Record<string, string>, required: readonly RequiredTag[], problems: readonly Problem[]): Record<string, { key?: string; value?: string }> {
  const out: Record<string, { key?: string; value?: string }> = {}
  const byKey = new Map(required.map((t) => [t.key, t]))
  const seen = new Set<string>()
  for (const row of rows) {
    const key = row.key.trim()
    const e: { key?: string; value?: string } = {}
    if (!key && row.value) e.key = 'Name the tag, or remove the row'
    else if (key && !(key in before) && !TAG_NAME.test(key)) e.key = 'A tag name is 1 to 32 lowercase letters, digits or hyphens'
    else if (key && seen.has(key)) e.key = `Another row names ${key} already`
    const tag = byKey.get(key)
    if (row.value.length > TAG_VALUE_MAX) e.value = `A tag value is at most ${TAG_VALUE_MAX} characters`
    else if (tag && !tagAccepts(tag, row.value)) e.value = row.value.trim() ? allowedSentence(tag) : `${tag.title} is required and stays on the resource`
    const said = key ? problems.find((p) => p.field === `/tags/${key}`) : undefined
    if (said && !e.key && !e.value) e.value = said.message
    if (key) seen.add(key)
    if (e.key || e.value) out[row.id] = e
  }
  return out
}

function CancelButton() {
  const close = useOverlayClose()
  return <Button onClick={close}>Cancel</Button>
}

export function TagsDrawer({ type, wfrn, name, tags, onClose, onSaved }: TagsDrawerProps) {
  const { decide, account } = useConsole()
  const cmd = useCommand()
  const policy = useQuery(['tag-policy', account.id], () => tagsApi.policy())
  const required = useMemo(() => policy.data?.required ?? [], [policy.data])
  const [rows, setRows] = useState<TagRow[] | null>(null)
  const [problems, setProblems] = useState<Problem[]>([])
  const decision = decide(type, 'update')
  const initial = useMemo(() => rowsOf(tags, required), [tags, required])
  const shown = rows ?? initial
  const reqKeys = new Set(required.map((t) => t.key))
  const noun = singularOf(type.title).toLowerCase()
  // a required tag the resource carries stays: its row keeps its name and cannot be removed
  const locked = Object.fromEntries(shown.filter((r) => reqKeys.has(r.key) && r.key in tags).map((r) => [r.id, `Required by this account, so it stays on the ${noun}; change its value instead.`]))
  const errors = drawerErrors(shown, tags, required, problems)
  const present = new Set(shown.map((r) => r.key.trim()))
  const lacking = required.filter((t) => !present.has(t.key))
  const next = Object.fromEntries(shown.filter((r) => r.key.trim()).map((r) => [r.key.trim(), r.value]))
  const changed = JSON.stringify(Object.entries(next).sort()) !== JSON.stringify(Object.entries(tags).sort())

  const save = async () => {
    if (Object.keys(errors).length) {
      requestAnimationFrame(() => document.querySelector<HTMLElement>('[data-tags-drawer] [aria-invalid="true"]')?.focus())
      return
    }
    const saved = await cmd.run(`Save the tags of ${name}`, () => bff.resources.setTags(type.name, wfrn, next), {
      quiet: true,
      invalidates: ['console-resource', 'console-resources', 'tags-resources', 'group-resources'],
      onProblems: setProblems,
      done: () => ({ title: 'Tags saved', message: `${name} carries ${Object.keys(next).length} tags.` }),
    })
    if (saved) onSaved(saved)
  }

  return (
    <Drawer onClose={onClose} width="max-w-xl" label={`Manage tags: ${name}`}>
      <OverlayHeader
        title="Manage tags"
        icon="tag"
        subtitle={
          <span className="flex min-w-0 flex-wrap items-center gap-x-1.5">
            <span className="font-medium text-primary">{name}</span>
            <span className="truncate font-mono text-caption text-tertiary" title={wfrn}>
              {displayWfrn(wfrn, 36)}
            </span>
          </span>
        }
      />
      <OverlayBody>
        <form
          id="tags-drawer-form"
          data-tags-drawer
          noValidate
          onSubmit={(e) => {
            e.preventDefault()
            void save()
          }}
        >
          <p className="mb-5 text-sm text-secondary">
            Tags travel with the resource and its events, for finding, grouping and paying for things together. A name is lowercase letters, digits or hyphens.{' '}
            <Link to={pathFor('tag-policy')} className="font-medium text-accent hover:opacity-70">
              The account’s required tags
            </Link>{' '}
            stay on it.
          </p>
          {policy.loading && !policy.data ? (
            <SkeletonRows n={3} rowClassName="h-10" />
          ) : (
            <TagRows
              rows={shown}
              onChange={(r) => {
                setRows(r)
                if (problems.length) setProblems([])
              }}
              errors={errors}
              locked={locked}
              idPrefix="tags-drawer"
              disabled={!decision.allowed}
            />
          )}
          {lacking.length > 0 && decision.allowed && (
            <div className="mt-5 rounded-lg border border-dashed border-stroke-light px-3 py-2.5 text-caption text-secondary">
              <p>{`This ${noun} does not carry ${andList(lacking.map((t) => t.title))}, which the account requires of every new resource.`}</p>
              <Button size="sm" variant="ghost" icon="plus" className="mt-1.5" onClick={() => setRows([...shown, ...lacking.map((t) => newTagRow(t.key, tagDefault(t, {})))])}>
                {lacking.length === 1 ? `Add ${lacking[0].title}` : 'Add the missing required tags'}
              </Button>
            </div>
          )}
          <FailureNote failure={cmd.failure} onDismiss={cmd.clear} className="mt-5" />
        </form>
      </OverlayBody>
      <OverlayFooter>
        <CancelButton />
        <ActionButton decision={decision} what="Manage tags" verb="tag" variant="primary" icon="check" loading={cmd.busy} disabled={!changed} onClick={() => void save()}>
          Save tags
        </ActionButton>
      </OverlayFooter>
    </Drawer>
  )
}
