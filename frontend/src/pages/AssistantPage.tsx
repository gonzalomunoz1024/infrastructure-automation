import type { PageProps } from '../app/router'
import { PlaceholderPage } from './placeholder'

export default function AssistantPage(_: PageProps) {
  return (
    <PlaceholderPage
      title="Assistant"
      subtitle="Describe a workflow in plain words; review the draft on the canvas."
      icon="sparkles"
      will="A conversation that streams status, drafts and validation results; the draft YAML with its summary, assumptions, open questions and missing building blocks; and a one-click hand-off to the designer."
    />
  )
}
