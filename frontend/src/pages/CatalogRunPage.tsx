import type { PageProps } from '../app/router'
import { pathFor } from '../app/router'
import { PlaceholderPage } from './placeholder'

export default function CatalogRunPage({ route }: PageProps) {
  return (
    <PlaceholderPage
      eyebrow="Run"
      title={route.params.workflow}
      subtitle="A form generated from the workflow's input schema."
      icon="play"
      back={{ href: `#${pathFor('catalog')}`, label: 'Catalog' }}
      will="The input form built from the JSON Schema (resource pickers for x-resource fields), a preview of the blast radius, cost and policy decision before running, a bulk mode with CSV rows and rollout waves, and the job it starts."
    />
  )
}
