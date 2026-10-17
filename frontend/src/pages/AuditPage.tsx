import type { PageProps } from '../app/router'
import { PlaceholderPage } from './placeholder'

export default function AuditPage(_: PageProps) {
  return (
    <PlaceholderPage
      title="Audit"
      subtitle="Evidence for every run: who asked, what was previewed, who approved, what changed."
      icon="audit"
      will="A searchable list of evidence records by workflow, requester and date range; the hash-chain status; export of a record (JSON or PDF) or a signed pack; share links; and the hourly seal."
    />
  )
}
