import { EmptyState, PageHeader } from '@iaf/ui'
import type { PageProps } from '../../app/router'

/* Phase 9: the page arrives with its work package. */

export default function Page(_props: PageProps) {
  return (
    <>
      <PageHeader title="Cost and usage" subtitle="What the account's resources cost by the price lists, by service, type, tag and day." />
      <EmptyState icon="sparkles" title="Arrives with phase 9" message="This page is being built." />
    </>
  )
}
