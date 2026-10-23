import { EmptyState, PageHeader } from '@iaf/ui'
import type { PageProps } from '../../app/router'

export default function AlarmsPage(_props: PageProps) {
  return (
    <>
      <PageHeader title="Alarms" subtitle="Metric alarms on the account’s resources, firing ones first." />
      <EmptyState icon="bell" title="Alarms" message="This page is being built." />
    </>
  )
}
