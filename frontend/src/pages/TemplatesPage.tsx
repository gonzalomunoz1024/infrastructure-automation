import type { PageProps } from '../app/router'
import { PlaceholderPage } from './placeholder'

export default function TemplatesPage(_: PageProps) {
  return (
    <PlaceholderPage
      title="Templates"
      subtitle="Starting points for new workflows."
      icon="grid"
      will="Template cards with title and description, a YAML preview, and Use template, which creates a draft and opens it in the designer."
    />
  )
}
