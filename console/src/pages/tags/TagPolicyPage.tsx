import { EmptyState, PageHeader } from '@iaf/ui'
import type { PageProps } from '../../app/router'

/* Required tags (WP 4.4): the tags every new resource of the account must carry, and their defaults. */

export default function TagPolicyPage(_props: PageProps) {
  return (
    <>
      <PageHeader title="Required tags" subtitle="The tags every new resource must carry." />
      <EmptyState icon="shield" title="Required tags" message="This page is being built." />
    </>
  )
}
