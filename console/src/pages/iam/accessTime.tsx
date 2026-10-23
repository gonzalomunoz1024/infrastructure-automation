import { useMemo } from 'react'
import { Button, EmptyState } from '@iaf/ui'
import { ErrorState } from '@iaf/console-kit'
import { useConsole } from '../../app/console'
import { useRefusal } from '../../app/Refusal'
import { bff } from '../../lib/bff'
import { denialOf } from '../../lib/permissions'
import { useQuery } from '../../lib/queries'

/*
 * What the access reviews and elevations pages share: people by name (the pages never show an id
 * where a name exists) and the panel a refused read shows, with the refusal one press away (its
 * offer to hold a role for a while included).
 */

/** The account's people by user id; an id the list does not know is shown as is. */
export function usePeopleNames(): (userId: string) => string {
  const { account } = useConsole()
  const users = useQuery(['iam', 'users', account.id], () => bff.iam.users.list({ limit: 200 }))
  return useMemo(() => {
    const byId = new Map((users.data?.items ?? []).map((u) => [u.userId, u.name ?? u.email]))
    return (userId: string) => byId.get(userId) ?? userId
  }, [users.data])
}

/** A read the account's policies refused: what it takes, and the refusal behind a button. */
export function RefusedRead({ error, title, message, what, onRetry }: { error: unknown; title: string; message: string; what: string; onRetry: () => void }) {
  const refusal = useRefusal()
  const denied = denialOf(error)
  if (!denied) return <ErrorState error={error} title={title} onRetry={onRetry} />
  return (
    <EmptyState
      icon="lock"
      title={title}
      message={message}
      action={
        <Button icon="lock" onClick={() => refusal.show({ denied, what })}>
          See why
        </Button>
      }
    />
  )
}
