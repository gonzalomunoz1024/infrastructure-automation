import { EmptyState, PageHeader } from '@iaf/ui'
import type { PageProps } from '../../app/router'

export default function DriftPage(_props: PageProps) {
  return (
    <>
      <PageHeader title="Drift" subtitle="Where what the providers report differs from what was asked for, in this account." />
      <EmptyState icon="pulse" title="Drift" message="This page is being built." />
    </>
  )
}
