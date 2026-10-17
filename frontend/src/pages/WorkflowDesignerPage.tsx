import { lazy, Suspense } from 'react'
import type { PageProps } from '../app/router'
import { DesignerSkeleton } from '../designer/DesignerSkeleton'

/** The designer is its own chunk (it brings @xyflow/react); the page shows its skeleton meanwhile. */
const DesignerRoute = lazy(() => import('../designer/DesignerRoute'))

/**
 * Full-bleed route for `#/workflows/new` and `#/workflows/:name`: the n8n-style designer,
 * edge to edge under the shell's header.
 */
export default function WorkflowDesignerPage({ route }: PageProps) {
  return (
    <Suspense fallback={<DesignerSkeleton />}>
      <DesignerRoute route={route} />
    </Suspense>
  )
}
