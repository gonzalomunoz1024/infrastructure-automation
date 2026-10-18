/**
 * Every change to a provider goes through a change request: publish, refresh spec, "Use the
 * real response" and the SLO all answer with one (or say nothing changed). What that answer
 * means, and the toast that names the change request and opens it.
 */
import { useToast } from '@iaf/ui'
import { navigate, pathFor } from '../app/router'
import type { CommandAccepted } from '../lib/types'

export type ChangeOutcome = { kind: 'opened'; id: string } | { kind: 'unchanged' }

/** What a provider command answered: the change request it opened, or that there was nothing to change. */
export function changeRequestOutcome(accepted: Pick<CommandAccepted, 'id' | 'status'>): ChangeOutcome {
  return accepted.status === 'unchanged' ? { kind: 'unchanged' } : { kind: 'opened', id: accepted.id }
}

/** "Opened change request cr-90", with a button to it; or "Nothing to change". `what` names the edit, e.g. "The SLO". */
export function useChangeRequestToast(): (accepted: Pick<CommandAccepted, 'id' | 'status'>, what: string) => ChangeOutcome {
  const toast = useToast()
  return (accepted, what) => {
    const outcome = changeRequestOutcome(accepted)
    if (outcome.kind === 'unchanged') toast.toast({ title: 'Nothing to change', message: `${what} is already what the file says.` })
    else
      toast.toast({
        title: `Opened change request ${outcome.id}`,
        message: `${what} goes through review; merging publishes the next version.`,
        tone: 'healthy',
        duration: 8000,
        action: { label: 'Open change request', onClick: () => navigate(pathFor('change-request', { id: outcome.id })) },
      })
    return outcome
  }
}
