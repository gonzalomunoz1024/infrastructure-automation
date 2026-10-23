import { EmptyState, PageHeader, SkeletonRows } from '@iaf/ui'
import { ErrorState } from '@iaf/console-kit'
import { useConsole } from '../../app/console'
import { Link, pathFor, type PageProps } from '../../app/router'
import { useQuery } from '../../lib/queries'
import { defaultSource, inputFieldOf, tagsApi, TAG_VALUE_MAX, type RequiredTag } from '../../lib/tags'

/*
 * Required tags (WP 4.4): the tags every new resource of the account must carry, as the BFF's tag
 * policy names them, with what each takes (a list of values or a pattern), the value the create
 * form fills in and where that comes from, and what happens to a create that leaves one out.
 */

/** What a required tag takes, in words. */
export function takesWords(tag: RequiredTag): string {
  if (tag.values?.length) return `One of ${tag.values.join(', ')}`
  if (tag.pattern) return `Text matching ${tag.pattern}`
  return `Any text up to ${TAG_VALUE_MAX} characters`
}

/** The default a create form fills in, in words. */
export function defaultWords(tag: RequiredTag): string {
  const field = inputFieldOf(tag)
  if (field) return `The create’s own ${field}`
  return tag.default ?? 'None: the create names it'
}

export default function TagPolicyPage(_props: PageProps) {
  const { account } = useConsole()
  const policy = useQuery(['tag-policy', account.id], () => tagsApi.policy())
  const required = policy.data?.required ?? []
  return (
    <>
      <PageHeader title="Required tags" subtitle={`The tags every new resource of ${account.name} must carry.`} />
      <div className="mb-6 max-w-3xl space-y-2 text-sm text-secondary">
        <p>A create that leaves one of these tags out, or gives one a value it may not take, is refused before anything is made. The create wizard asks for each on its Tags step, filled in from its default; the API, the CLI and the MCP tools are held to the same rule.</p>
        <p>
          A resource keeps its required tags: a change may give one another value it takes, never remove it. A resource older than the rule is not made to carry it; tag such resources together in the{' '}
          <Link to={pathFor('tag-editor')} className="font-medium text-accent hover:opacity-70">
            Tag Editor
          </Link>
          .
        </p>
      </div>
      {policy.loading && !policy.data ? (
        <SkeletonRows n={4} />
      ) : policy.error && !policy.data ? (
        <ErrorState error={policy.error} title="Could not read the required tags" onRetry={policy.refetch} />
      ) : required.length === 0 ? (
        <EmptyState icon="shield" title="No tags are required here" message="This account asks for no tag on a new resource; a create may name its own." />
      ) : (
        <div className="overflow-x-auto rounded-lg border border-stroke-light">
          <table className="w-full min-w-[44rem] text-left text-sm">
            <caption className="sr-only">{`The tags every new resource of ${account.name} must carry`}</caption>
            <thead className="text-caption text-tertiary">
              <tr>
                <th scope="col" className="px-4 py-2 font-medium">
                  Tag
                </th>
                <th scope="col" className="px-4 py-2 font-medium">
                  What it takes
                </th>
                <th scope="col" className="px-4 py-2 font-medium">
                  Default
                </th>
                <th scope="col" className="px-4 py-2 font-medium">
                  Where the default comes from
                </th>
              </tr>
            </thead>
            <tbody className="divide-y divide-stroke-light">
              {required.map((t) => (
                <tr key={t.key} data-tag={t.key} className="align-top">
                  <th scope="row" className="px-4 py-3 font-normal">
                    <span className="block font-medium text-primary">{t.title}</span>
                    <span className="block font-mono text-caption text-tertiary">{t.key}</span>
                    {t.description && <span className="mt-1 block text-caption text-secondary">{t.description}</span>}
                  </th>
                  <td className="px-4 py-3 text-primary">{t.pattern && !t.values?.length ? <span className="font-mono text-caption">{takesWords(t)}</span> : takesWords(t)}</td>
                  <td className="px-4 py-3 text-primary">{t.default ? <span className="font-mono text-caption">{t.default}</span> : <span className="text-secondary">{defaultWords(t)}</span>}</td>
                  <td className="px-4 py-3 text-secondary">{defaultSource(t)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </>
  )
}
