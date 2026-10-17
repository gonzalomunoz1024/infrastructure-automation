import { EmptyState, Kbd } from '@iaf/ui'
import type { PageProps } from '../app/router'

/**
 * Full-bleed route: the shell gives this page the whole content area with no
 * padding. The designer agent replaces it with the n8n-style canvas.
 */
export default function WorkflowDesignerPage({ route }: PageProps) {
  return (
    <div className="bg-dot-grid relative h-full w-full">
      <EmptyState
        className="h-full"
        icon="workflow"
        title={route.params.name}
        message={
          <>
            The designer canvas renders here, edge to edge: nodes, ports, edges, the node detail view and the node creator. <Kbd>Tab</Kbd> opens the node creator, <Kbd>⌘S</Kbd> saves, <Kbd>⌘↩</Kbd> test-runs.
          </>
        }
      />
    </div>
  )
}
