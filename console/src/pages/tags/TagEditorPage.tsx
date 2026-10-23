import { EmptyState, PageHeader } from '@iaf/ui'
import type { PageProps } from '../../app/router'

/* The Tag Editor (WP 4.3): find resources of every type by their tags, and tag them together. */

export default function TagEditorPage(_props: PageProps) {
  return (
    <>
      <PageHeader title="Tag Editor" subtitle="Find resources of every type and tag them together." />
      <EmptyState icon="tag" title="Tag Editor" message="This page is being built." />
    </>
  )
}
