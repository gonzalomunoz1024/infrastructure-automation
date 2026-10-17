import type { PageProps } from '../app/router'
import { PlaceholderPage } from './placeholder'

export default function CatalogPage(_: PageProps) {
  return (
    <PlaceholderPage
      title="Catalog"
      subtitle="Every published workflow, ready to run from a form, the API or an MCP tool."
      icon="list"
      will="Cards for each published workflow with noun, verb, owner, version, the generated endpoint and the MCP tool name; a search box and persona filter; one click opens the run form."
    />
  )
}
