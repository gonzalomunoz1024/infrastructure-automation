import { Icon, IconButton } from '@iaf/ui'
import type { Route } from '../router'
import { useCopilot } from './context'

/*
 * The copilot's side panel (WP 9.1): plain English to a filtered list, a pre-filled wizard or
 * action, or an explanation of a resource. It never acts; everything it proposes opens for the
 * person to review. Phase 9 builds it out.
 */

export function CopilotPanel(_props: { route: Route }) {
  const copilot = useCopilot()
  return (
    <aside id="copilot-panel" aria-label="Copilot" className="fixed inset-y-0 right-0 z-50 flex w-full max-w-md flex-col border-l border-stroke-light bg-surface shadow-xl">
      <div className="flex items-center justify-between gap-3 border-b border-stroke-light px-4 py-3">
        <h2 className="flex items-center gap-2 text-subheadline font-semibold text-primary">
          <Icon name="sparkles" size={16} className="text-accent" />
          Copilot
        </h2>
        <IconButton label="Close the copilot" onClick={copilot.close}>
          <Icon name="close" size={16} />
        </IconButton>
      </div>
      <p className="p-4 text-sm text-secondary">The copilot arrives with phase 9.</p>
    </aside>
  )
}
