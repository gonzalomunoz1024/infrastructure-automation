import type { PageProps } from '../app/router'
import { pathFor } from '../app/router'
import { PlaceholderPage } from './placeholder'

export default function WorkflowVersionsPage({ route }: PageProps) {
  return (
    <PlaceholderPage
      eyebrow="Versions"
      title={route.params.name}
      subtitle="Every version of this workflow: drafts, candidates, what is published, what was retired."
      icon="layers"
      back={{ href: `#${pathFor('workflows')}`, label: 'Workflows' }}
      will="The version list with status, content hash, who published it and when, the release it rode in on, a diff between any two versions, and the replay check that proves a version still agrees with past runs."
    />
  )
}
