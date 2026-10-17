import type { PageProps } from '../app/router'
import { PlaceholderPage } from './placeholder'

export default function ApprovalsPage(_: PageProps) {
  return (
    <PlaceholderPage
      title="Approvals"
      subtitle="Runs waiting on a person in your groups."
      icon="approval"
      will="Pending approvals with the workflow, the step, who asked, how long is left and the preview of what the run will change; approve or reject with a comment; a history of past decisions."
    />
  )
}
