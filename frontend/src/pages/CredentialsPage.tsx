import type { PageProps } from '../app/router'
import { PlaceholderPage } from './placeholder'

export default function CredentialsPage(_: PageProps) {
  return (
    <PlaceholderPage
      title="Credentials"
      subtitle="Names and paths only; values never leave the vault."
      icon="key"
      will="The credential list with type, path and last update; a form to add one (name, type, value stored through the credentials port); which providers and workflows reference each name."
    />
  )
}
