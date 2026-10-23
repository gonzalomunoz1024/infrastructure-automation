import { EmptyState, PageHeader } from '@iaf/ui'
import type { PageProps } from '../../app/router'

export default function RightsizingPage(_props: PageProps) {
  return (
    <>
      <PageHeader title="Rightsizing" subtitle="Idle and oversized resources, with what a resize or delete would save." />
      <EmptyState icon="chart" title="Rightsizing" message="This page is being built." />
    </>
  )
}
