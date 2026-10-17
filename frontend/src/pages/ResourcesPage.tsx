import type { PageProps } from '../app/router'
import { PlaceholderPage } from './placeholder'

export default function ResourcesPage(_: PageProps) {
  return (
    <PlaceholderPage
      title="Resources"
      subtitle="What the workflows created, by type, with where each one came from."
      icon="resource"
      will="Resource types as tabs or a filter; a table of resources with name, status, owner, visibility, the job and workflow that created them and when; the way into lineage and retirement."
    />
  )
}
