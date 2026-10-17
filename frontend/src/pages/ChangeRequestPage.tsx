import type { PageProps } from '../app/router'
import { pathFor } from '../app/router'
import { PlaceholderPage } from './placeholder'

export default function ChangeRequestPage({ route }: PageProps) {
  return (
    <PlaceholderPage
      eyebrow="Change request"
      title={<span className="font-mono">{route.params.id}</span>}
      subtitle="A version on a branch, waiting for its checks and a merge."
      icon="git-branch"
      back={{ href: `#${pathFor('workflows')}`, label: 'Workflows' }}
      will="The title, author, branch and strategy; every check with its status and detail; the diff against the published version; the YAML; and Merge (publishes and starts the canary) or Close."
    />
  )
}
