import { EmptyState, PageHeader } from '@iaf/ui'
import type { PageProps } from '../../app/router'

export default function UnmanagedPage(_props: PageProps) {
  return (
    <>
      <PageHeader title="Unmanaged resources" subtitle="What the providers have that no account manages, with a guess at whose it is." />
      <EmptyState icon="eye" title="Unmanaged resources" message="This page is being built." />
    </>
  )
}
