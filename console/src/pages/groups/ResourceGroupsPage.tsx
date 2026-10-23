import { EmptyState, PageHeader } from '@iaf/ui'
import type { PageProps } from '../../app/router'

/* Resource groups (WP 4.3): the account's saved tag queries, and one group with its resources. */

export default function ResourceGroupsPage(_props: PageProps) {
  return (
    <>
      <PageHeader title="Resource groups" subtitle="Resources that share tags, kept as one group across every type." />
      <EmptyState icon="layers" title="Resource groups" message="This page is being built." />
    </>
  )
}

export function ResourceGroupPage({ route }: PageProps) {
  return (
    <>
      <PageHeader eyebrow="Resource group" title={route.params.id} />
      <EmptyState icon="layers" title="Resource group" message="This page is being built." />
    </>
  )
}
