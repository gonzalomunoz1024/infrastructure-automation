import { useMemo, useState } from 'react'
import { Button, Checkbox, Icon, Modal, OverlayBody, OverlayFooter, OverlayHeader, useOverlayClose } from '@iaf/ui'
import { displayWfrn, type Problem } from '@iaf/console-kit'
import { useConsole } from '../../app/console'
import { FailureNote, useCommand } from '../../app/commands'
import { useRefusal } from '../../app/Refusal'
import { Link, pathFor } from '../../app/router'
import { useQuery } from '../../lib/queries'
import { useSubmissionKey } from '../../lib/submission'
import { APPLY_MAX, applyProblems, newTagRow, rowErrors, tagsApi, type TagApplyFailure, type TagApplyInput, type TagApplyResult, type TagRow } from '../../lib/tags'
import { TagRows } from './TagRows'

/*
 * Tags set and removed on many resources at once (WP 4.3), from the Tag Editor and from a
 * resource list's selection: the tags to set (a name and a value each), the tags to remove (of
 * those the resources carry; the account's required tags stay), one `tags:apply` for up to 100
 * resources. Each resource is its own change, so the dialog ends on what happened to each: how
 * many took the change, and for each one that did not, why, in words.
 */

export interface ApplyTagsDialogProps {
  resources: { wfrn: string; type: string; name?: string; tags?: Record<string, string> }[]
  onClose: () => void
  /** the BFF's answer, once it came: the dialog then shows it until the person closes it */
  onApplied: (result: TagApplyResult) => void
}

/** Why one resource did not take the change, in words. */
export function failureWords(f: Pick<TagApplyFailure, 'error' | 'message'>): string {
  switch (f.error) {
    case 'denied':
      return `Not allowed: ${f.message}`
    case 'not-found':
      return 'Not found in this account and region'
    case 'gone':
      return 'Deleted, so its tags no longer change'
    case 'conflict':
      return `Changed by someone else at the same moment; try it again (${f.message})`
    case 'invalid':
      return `The platform refused the tags: ${f.message}`
    default:
      return `The change failed: ${f.message}`
  }
}

function CloseButton({ label }: { label: string }) {
  const close = useOverlayClose()
  return <Button onClick={close}>{label}</Button>
}

export function ApplyTagsDialog({ resources: given, onClose, onApplied }: ApplyTagsDialogProps) {
  // the resources as the dialog opened on them: the caller's selection may change once they are tagged
  const [resources] = useState(given)
  const { account } = useConsole()
  const refusal = useRefusal()
  const cmd = useCommand()
  const keyFor = useSubmissionKey()
  const policy = useQuery(['tag-policy', account.id], () => tagsApi.policy())
  const required = useMemo(() => policy.data?.required ?? [], [policy.data])
  const [rows, setRows] = useState<TagRow[]>(() => [newTagRow()])
  const [remove, setRemove] = useState<string[]>([])
  const [problems, setProblems] = useState<Problem[]>([])
  const [result, setResult] = useState<TagApplyResult | null>(null)
  const count = resources.length
  const noun = count === 1 ? 'resource' : 'resources'
  const nameOf = (wfrn: string) => resources.find((r) => r.wfrn === wfrn)?.name ?? displayWfrn(wfrn, 40)
  const reqKeys = new Set(required.map((t) => t.key))
  // the names the resources carry, which may be removed; a required tag stays
  const carried = [...new Set(resources.flatMap((r) => Object.keys(r.tags ?? {})))].sort()
  const set = Object.fromEntries(rows.filter((r) => r.key.trim()).map((r) => [r.key.trim(), r.value]))
  // a tag named above is set, not removed: its box stands aside while the row names it
  const removing = remove.filter((k) => !(k in set))
  const change: TagApplyInput = { resources: resources.map((r) => r.wfrn), set, remove: removing }
  const local = applyProblems(required, change)
  // a row says what is wrong with it: its name or a required tag's value; the BFF's word by pointer
  const errors = rowErrors(rows, [])
  for (const row of rows) {
    const p = [...local, ...problems].find((x) => x.field === `/set/${row.key.trim()}`)
    if (p && !errors[row.id]) errors[row.id] = p.code === 'tag.name' ? { key: 'A tag name is 1 to 32 lowercase letters, digits or hyphens' } : { value: p.message }
  }
  const general = local.filter((p) => p.field === '/resources' || p.field === '/set')
  const blocked = Object.keys(errors).length > 0 || local.length > 0

  const apply = async () => {
    if (blocked) return
    const key = keyFor(change)
    const answer = await cmd.run(`Tag ${count} ${noun}`, () => tagsApi.apply(change, key), {
      quiet: true,
      invalidates: ['tags-resources', 'console-resources', 'console-resource', 'group-resources'],
      onProblems: setProblems,
    })
    if (!answer) return
    setResult(answer)
    onApplied(answer)
  }

  if (result) {
    const failed = result.failed
    return (
      <Modal onClose={onClose} width="max-w-xl" label="Tags applied">
        <OverlayHeader title={failed.length ? `Tagged ${result.applied.length} of ${count} ${noun}` : `Tagged ${plural(result.applied.length)}`} icon={failed.length ? 'warning' : 'check'} subtitle={failed.length ? 'The others did not take the change; each says why.' : 'Every resource took the change.'} />
        <OverlayBody>
          {failed.length > 0 && (
            <ul className="divide-y divide-stroke-light rounded-lg border border-stroke-light" aria-label="Resources that did not take the change">
              {failed.map((f) => (
                <li key={f.wfrn} className="flex flex-wrap items-start justify-between gap-2 px-3 py-2.5 text-sm" data-failed={f.error}>
                  <div className="min-w-0">
                    <p className="font-medium text-primary">{nameOf(f.wfrn)}</p>
                    <p className="text-caption text-secondary">{failureWords(f)}</p>
                  </div>
                  {f.denied && (
                    <Button size="sm" variant="ghost" onClick={() => refusal.show({ denied: f.denied!, what: `Tag ${nameOf(f.wfrn)}` })}>
                      See why
                    </Button>
                  )}
                </li>
              ))}
            </ul>
          )}
          {result.applied.length > 0 && <p className={failed.length ? 'mt-4 text-sm text-secondary' : 'text-sm text-secondary'}>{`${plural(result.applied.length)} now carry ${Object.keys(set).length ? Object.entries(set).map(([k, v]) => `${k}=${v}`).join(', ') : 'their tags'}${removing.length ? `, without ${removing.join(', ')}` : ''}.`}</p>}
        </OverlayBody>
        <OverlayFooter>
          <CloseButton label="Done" />
        </OverlayFooter>
      </Modal>
    )
  }

  return (
    <Modal onClose={onClose} width="max-w-xl" label={`Manage tags of ${count} ${noun}`}>
      <OverlayHeader title={`Manage tags of ${count} ${noun}`} icon="tag" subtitle="What you set is added to each, or replaces the value it had; the other tags stay." />
      <OverlayBody>
        <form
          id="apply-tags-form"
          noValidate
          onSubmit={(e) => {
            e.preventDefault()
            void apply()
          }}
          className="space-y-6"
        >
          {count > APPLY_MAX && <p className="rounded-md bg-critical-tint px-3 py-2 text-sm text-critical">{`At most ${APPLY_MAX} resources take one change; select fewer than ${count}.`}</p>}
          <section aria-labelledby="apply-set-title">
            <h3 id="apply-set-title" className="text-subheadline font-semibold text-primary">
              Tags to set
            </h3>
            <p className="mb-3 mt-0.5 text-caption text-secondary">A name of lowercase letters, digits or hyphens, and its value.</p>
            <TagRows
              rows={rows}
              onChange={(r) => {
                setRows(r)
                if (problems.length) setProblems([])
              }}
              errors={errors}
              idPrefix="apply-tags"
              valueLabel="Value to set"
            />
          </section>
          {carried.length > 0 && (
            <fieldset>
              <legend className="text-subheadline font-semibold text-primary">Tags to remove</legend>
              <p className="mb-3 mt-0.5 text-caption text-secondary">
                {'The tags these resources carry. '}
                <Link to={pathFor('tag-policy')} className="font-medium text-accent hover:opacity-70">
                  The required tags
                </Link>
                {' stay on them.'}
              </p>
              <div className="grid gap-2 sm:grid-cols-2">
                {carried.map((k) => (
                  <Checkbox
                    key={k}
                    checked={removing.includes(k)}
                    disabled={reqKeys.has(k) || k in set}
                    description={reqKeys.has(k) ? 'Required by this account' : k in set ? 'Set above' : undefined}
                    label={<span className="font-mono text-caption">{k}</span>}
                    onChange={(on) => setRemove((was) => (on ? [...was, k] : was.filter((x) => x !== k)))}
                  />
                ))}
              </div>
            </fieldset>
          )}
          {general.length > 0 && Object.keys(errors).length === 0 && (
            <p className="flex items-center gap-2 text-caption text-tertiary">
              <Icon name="info" size={14} />
              Name a tag to set or one to remove.
            </p>
          )}
          <FailureNote failure={cmd.failure} onDismiss={cmd.clear} />
        </form>
      </OverlayBody>
      <OverlayFooter>
        <CloseButton label="Cancel" />
        <Button type="submit" form="apply-tags-form" variant="primary" icon="tag" loading={cmd.busy} disabled={blocked}>
          {`Apply to ${count} ${noun}`}
        </Button>
      </OverlayFooter>
    </Modal>
  )
}

function plural(n: number): string {
  return `${n} ${n === 1 ? 'resource' : 'resources'}`
}
