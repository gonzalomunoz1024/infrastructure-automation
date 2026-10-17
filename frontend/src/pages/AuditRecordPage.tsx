import type { PageProps } from '../app/router'
import { pathFor } from '../app/router'
import { PlaceholderPage } from './placeholder'

export default function AuditRecordPage({ route }: PageProps) {
  return (
    <PlaceholderPage
      eyebrow="Evidence"
      title={<span className="font-mono">{route.params.jobId}</span>}
      subtitle="The sealed, hash-chained record of one run."
      icon="audit"
      back={{ href: `#${pathFor('audit')}`, label: 'Audit' }}
      will="Every entry in order (requested, preview, policy, change, approvals, steps, decision) with actor, data and hash; chain verification with the first broken link if any; the seal; retention; export and share."
    />
  )
}
