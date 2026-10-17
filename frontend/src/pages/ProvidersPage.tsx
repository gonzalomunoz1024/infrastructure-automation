import { Button } from '@iaf/ui'
import type { PageProps } from '../app/router'
import { navigate, pathFor } from '../app/router'
import { PlaceholderPage } from './placeholder'

export default function ProvidersPage(_: PageProps) {
  return (
    <PlaceholderPage
      title="Providers"
      subtitle="The systems workflows talk to: where each API lives, how to sign in, which operations it offers."
      icon="provider"
      actions={
        <Button variant="primary" icon="plus" onClick={() => navigate(pathFor('provider-new'))}>
          Add provider
        </Button>
      }
      will="A table of providers with type, owner, version, environments, action count and health (success rate, p95, error trend sparkline); alerts; and the way into each provider's actions, metrics and change requests."
    />
  )
}
