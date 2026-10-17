import type { PageProps } from '../app/router'
import { pathFor } from '../app/router'
import { PlaceholderPage } from './placeholder'

export default function ResourcePage({ route }: PageProps) {
  return (
    <PlaceholderPage
      eyebrow="Resource"
      title={<span className="font-mono">{route.params.id}</span>}
      subtitle="Attributes, lineage and the run that made it."
      icon="resource"
      back={{ href: `#${pathFor('resources')}`, label: 'Resources' }}
      will="The attributes against the type's schema, what it was built from and what depends on it, the creating job, and Retire (refused with the list of dependents while something is built from it)."
    />
  )
}
