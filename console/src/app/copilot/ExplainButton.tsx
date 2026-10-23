import { Button } from '@iaf/ui'
import type { ConsoleType } from '../../lib/descriptors'
import { resourcePath } from '../router'
import { useCopilot } from './context'

/*
 * Explain this resource (WP 9.1): opens the copilot asking it to explain the resource, what it
 * is, its configuration, cost, history and dependents. A deleted resource has nothing left to
 * explain, so it shows no button.
 */

export function ExplainButton({ type, wfrn, name, deleted }: { type: ConsoleType; wfrn: string; name: string; deleted?: boolean }) {
  const copilot = useCopilot()
  if (deleted) return null
  return (
    <Button
      variant="secondary"
      icon="sparkles"
      onClick={() => copilot.open({ intent: 'explain', context: { wfrn, type: type.name, service: type.service, path: resourcePath(type.service, type.name, wfrn) }, label: `Explain ${name}`, name })}
    >
      Explain<span className="sr-only"> {name}</span>
    </Button>
  )
}
