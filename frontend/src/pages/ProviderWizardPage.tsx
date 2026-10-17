import { Stepper } from '@iaf/ui'
import type { PageProps } from '../app/router'
import { pathFor } from '../app/router'
import { PlaceholderPage } from './placeholder'

const STEPS = [
  { id: 'source', label: 'Source', description: 'OpenAPI, HTTP, Ansible, Terraform…' },
  { id: 'connect', label: 'Connect', description: 'Endpoints and credentials' },
  { id: 'actions', label: 'Actions', description: 'Pick and describe operations' },
  { id: 'try', label: 'Try it', description: 'Record a real response' },
  { id: 'publish', label: 'Publish', description: 'Checks, then a change request' },
]

export default function ProviderWizardPage(_: PageProps) {
  return (
    <>
      <div className="mb-6">
        <Stepper steps={STEPS} current={0} />
      </div>
      <PlaceholderPage
        title="Add a provider"
        subtitle="Five steps from a spec to a published provider."
        icon="provider"
        back={{ href: `#${pathFor('providers')}`, label: 'Providers' }}
        will="Import an OpenAPI spec (URL or paste) or describe the provider to the assistant; set endpoints per environment and the credential; test the connection; choose actions and their effect, retry and approval; try one for real and record the sample; run the checks and publish as a change request."
      />
    </>
  )
}
