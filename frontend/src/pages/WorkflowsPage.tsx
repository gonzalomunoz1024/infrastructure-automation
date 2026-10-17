import type { PageProps } from '../app/router'
import { PlaceholderPage } from './placeholder'

export default function WorkflowsPage(_: PageProps) {
  return (
    <PlaceholderPage
      title="Workflows"
      subtitle="Design, validate and publish config-as-code workflows."
      icon="workflow"
      will="A table of every workflow with folder, owner, status, published version, step count and last change; filters by folder and status; a search box; and the way into the designer, the versions and the change requests of each one."
    />
  )
}
