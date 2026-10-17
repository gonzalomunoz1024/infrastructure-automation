import type { PageProps } from '../app/router'
import { pathFor } from '../app/router'
import { PlaceholderPage } from './placeholder'

export default function JobPage({ route }: PageProps) {
  return (
    <PlaceholderPage
      eyebrow="Job"
      title={<span className="font-mono">{route.params.id}</span>}
      subtitle="The run, step by step, as it happens."
      icon="job"
      back={{ href: `#${pathFor('jobs')}`, label: 'Jobs' }}
      will="The step timeline streamed over SSE with inputs, outputs and errors per step; the needs-attention panel (retry, edit input, skip, abort); approvals; the preview and policy decision; batch rows and waves; replay against a previous version; and the evidence record."
    />
  )
}
