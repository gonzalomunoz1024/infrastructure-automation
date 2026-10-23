import { EmptyState, PageHeader } from '@iaf/ui'
import type { PageProps } from '../../app/router'

/* Phase 9: the page arrives with its work package. */

export default function Page(_props: PageProps) {
  return (
    <>
      <PageHeader title="Chargeback" subtitle="Each month's cost by cost centre, account, service and tag, for finance." />
      <EmptyState icon="sparkles" title="Arrives with phase 9" message="This page is being built." />
    </>
  )
}
