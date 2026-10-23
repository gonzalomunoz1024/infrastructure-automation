import { EmptyState, PageHeader } from '@iaf/ui'
import type { PageProps } from '../../app/router'

/* Phase 9: the page arrives with its work package. */

export default function Page(_props: PageProps) {
  return (
    <>
      <PageHeader title="Executive home" subtitle="What the console did for your teams: resources managed, requests self-served, lead time, drift fixed and spend." />
      <EmptyState icon="sparkles" title="Arrives with phase 9" message="This page is being built." />
    </>
  )
}
