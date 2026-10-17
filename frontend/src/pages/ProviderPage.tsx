import type { PageProps } from '../app/router'
import { pathFor } from '../app/router'
import { PlaceholderPage } from './placeholder'

export default function ProviderPage({ route }: PageProps) {
  return (
    <PlaceholderPage
      eyebrow="Provider"
      title={route.params.name}
      subtitle="Actions, health and the file behind them."
      icon="provider"
      back={{ href: `#${pathFor('providers')}`, label: 'Providers' }}
      will="Health over time (calls, success rate, p50/p95), per-action stats and top errors, the SLO and its alert channel, the action list with schemas and samples, recorded overrides, the provider YAML, and open change requests."
    />
  )
}
