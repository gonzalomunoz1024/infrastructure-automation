import { lazy, Suspense } from 'react'
import type { PageProps } from '../app/router'
import { DesignerSkeleton } from '../designer/DesignerSkeleton'

/** The designer is its own chunk (it brings @xyflow/react); the page shows its skeleton meanwhile. */
const DesignerRoute = lazy(() => import('../designer/DesignerRoute'))

/**
 * Full-bleed route for `#/workflows/new` and `#/workflows/:name`: the n8n-style designer,
 * edge to edge under the shell's header. It fills the shell's main area out of flow, so a tall
 * view (Settings, a long executions list) scrolls inside the designer instead of growing the
 * page under the header.
 */
export default function WorkflowDesignerPage({ route }: PageProps) {
  return (
    <div className="absolute inset-0 overflow-hidden" data-testid="designer-root">
      <Suspense fallback={<DesignerSkeleton />}>
        <DesignerRoute route={route} />
      </Suspense>
    </div>
  )
}
