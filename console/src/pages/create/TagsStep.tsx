import { Field, Input, Select, SkeletonRows } from '@iaf/ui'
import { Section, type Problem } from '@iaf/console-kit'
import { FailureNote } from '../../app/commands'
import { Link, pathFor } from '../../app/router'
import { explain } from '../../lib/plain'
import { allowedSentence, defaultSource, inputFieldOf, requiredValue, rowErrors, tagAccepts, type RequiredTag, type TagPolicy, type TagsDraft } from '../../lib/tags'
import { TagRows } from '../tags/TagRows'

/*
 * The create's Tags step (WP 4.4): every tag the account requires, as a field pre-filled from its
 * default (a tag that copies one of the create's fields follows that field until the person
 * changes the tag here), then the person's own tags. A value the tag may not take stops the step;
 * a blank required tag does not, but the review names it and keeps Create disabled until it is set.
 */

export interface TagsStepProps {
  policy: TagPolicy | undefined
  loading: boolean
  error: unknown
  onRetry: () => void
  draft: TagsDraft
  onChange: (draft: TagsDraft) => void
  input: Record<string, unknown>
  /** the BFF's problems at `/tags/<key>` */
  problems: Problem[]
  /** say so of a blank required tag (after a create the BFF refused for it) */
  showMissing: boolean
}

/** What is wrong with a required tag's value in the form, or null. */
export function requiredTagError(tag: RequiredTag, value: string, problems: Problem[], showMissing: boolean): string | null {
  if (value.trim() && !tagAccepts(tag, value)) return allowedSentence(tag)
  if (!value.trim() && showMissing) return `${tag.title} is required`
  return problems.find((p) => p.field === `/tags/${tag.key}`)?.message ?? null
}

/** Whether the step may move on: every value given is one its tag takes, and every row is well formed. */
export function tagsStepProblems(policy: TagPolicy | undefined, draft: TagsDraft, input: Record<string, unknown>): string[] {
  const required = policy?.required ?? []
  const wrong = required.filter((t) => {
    const v = requiredValue(t, draft, input)
    return v.trim() !== '' && !tagAccepts(t, v)
  })
  const rows = rowErrors(draft.extra, required)
  return [...wrong.map((t) => allowedSentence(t)), ...Object.values(rows).flatMap((e) => [e.key, e.value].filter((x): x is string => !!x))]
}

export function TagsStep({ policy, loading, error, onRetry, draft, onChange, input, problems, showMissing }: TagsStepProps) {
  if (loading && !policy) return <SkeletonRows n={4} rowClassName="h-16" />
  if (error && !policy) return <FailureNote failure={explain(error, 'Read the account’s required tags')} onRetry={onRetry} />
  const required = policy?.required ?? []
  const setValue = (key: string, value: string) => onChange({ ...draft, values: { ...draft.values, [key]: value }, edited: draft.edited.includes(key) ? draft.edited : [...draft.edited, key] })
  const follow = (key: string) => onChange({ ...draft, edited: draft.edited.filter((k) => k !== key) })
  return (
    <div className="max-w-2xl space-y-8">
      {required.length > 0 && (
        <Section
          title="Required tags"
          description={
            <>
              Every new resource of this account carries these tags; a create that leaves one out is refused. <Link to={pathFor('tag-policy')} className="font-medium text-accent hover:opacity-70">About the required tags</Link>
            </>
          }
        >
          <div className="grid gap-5 sm:grid-cols-2">
            {required.map((tag) => {
              const value = requiredValue(tag, draft, input)
              const id = `create-tag-${tag.key}`
              const err = requiredTagError(tag, value, problems, showMissing)
              const field = inputFieldOf(tag)
              const followed = field && !draft.edited.includes(tag.key)
              const source = followed ? `Follows the ${field} field until you change it here.` : field ? `You set it here; it no longer follows the ${field} field.` : tag.default ? `${defaultSource(tag)}: ${tag.default}.` : null
              const hint = (
                <>
                  {tag.description ? `${tag.description}. ` : ''}
                  {tag.pattern ? `Must match ${tag.pattern}. ` : ''}
                  {source}
                  {field && !followed && (
                    <>
                      {' '}
                      <button type="button" className="font-medium text-accent hover:opacity-70" onClick={() => follow(tag.key)}>
                        Follow it again
                      </button>
                    </>
                  )}
                </>
              )
              return (
                <Field key={tag.key} label={tag.title} required htmlFor={tag.values?.length ? undefined : id} hint={hint} error={err ?? undefined}>
                  {tag.values?.length ? (
                    <div id={id} data-tag={tag.key}>
                      <Select ariaLabel={`${tag.title}: ${value || 'not set'}`} value={value} onChange={(v) => setValue(tag.key, v)} options={tag.values.map((v) => ({ value: v, label: v }))} placeholder="Choose…" />
                    </div>
                  ) : (
                    <Input id={id} data-tag={tag.key} mono={!!tag.pattern} value={value} invalid={!!err} aria-required="true" onChange={(e) => setValue(tag.key, e.target.value)} placeholder={tag.pattern ? 'cc-0000' : undefined} autoComplete="off" spellCheck={false} />
                  )}
                </Field>
              )
            })}
          </div>
        </Section>
      )}
      <Section title="More tags" description="Optional. Your own tags, a name of lowercase letters, digits or hyphens and a value, kept on the resource beside the required ones.">
        <TagRows rows={draft.extra} onChange={(extra) => onChange({ ...draft, extra })} errors={rowErrors(draft.extra, required)} idPrefix="create-tags" />
      </Section>
    </div>
  )
}
