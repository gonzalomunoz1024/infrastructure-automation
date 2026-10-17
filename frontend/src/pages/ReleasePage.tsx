import type { PageProps } from '../app/router'
import { pathFor } from '../app/router'
import { PlaceholderPage } from './placeholder'

export default function ReleasePage({ route }: PageProps) {
  return (
    <PlaceholderPage
      eyebrow="Release"
      title={<span className="font-mono">{route.params.id}</span>}
      subtitle="One canary: stages, gates and the jobs on each side."
      icon="release"
      back={{ href: `#${pathFor('releases')}`, label: 'Releases' }}
      will="The stage progress, each gate with its value against its threshold, jobs on the current and candidate version, the event history, and the promote / pause / resume / rollback actions with confirmation."
    />
  )
}
