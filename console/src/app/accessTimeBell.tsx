import { relativeTime } from '@iaf/ui'
import { decidableBy, dueWords, isOpen, reviewsApi, type AccessReview } from '../lib/accessReviews'
import { elevationsApi, minutesWords, type Elevation } from '../lib/elevations'
import { useQuery } from '../lib/queries'
import { ADMIN_ROLE, roleLabel } from '../lib/session'
import { useConsole } from './console'
import { Link, pathFor } from './router'

/*
 * What waits for a reviewer or an approver in the notifications bell, beside the access requests
 * (WP 9.11 and 9.12): the open access review with what is left for this person to decide (never
 * their own access), and the asks for an elevation that name them among their approvers (never
 * their own). Both are the account admins'; nobody else is asked, so nothing is fetched for them.
 */

export interface AccessTimeQueue {
  reviews: { review: AccessReview; left: number }[]
  elevations: Elevation[]
  count: number
}

const EMPTY: AccessTimeQueue = { reviews: [], elevations: [], count: 0 }

export function useAccessTimeQueue(): AccessTimeQueue {
  const { account, session } = useConsole()
  const userId = session.user.userId
  const decides = (session.account.roles ?? []).includes(ADMIN_ROLE)
  const scope = [account.id, userId, (session.account.roles ?? []).join(',')]
  const reviews = useQuery(
    ['iam', 'bell', 'reviews', ...scope],
    async () => {
      const open = (await reviewsApi.list({ limit: 20 })).items.filter((r) => isOpen(r))
      return Promise.all(open.map(async (review) => ({ review, left: decidableBy((await reviewsApi.items(review.reviewId, { decision: 'pending', limit: 200 })).items, userId).length })))
    },
    { enabled: decides, refetchInterval: decides ? 30_000 : undefined },
  )
  const elevations = useQuery(['iam', 'bell', 'elevations', ...scope], () => elevationsApi.list({ status: 'pending', limit: 200 }), { enabled: decides, refetchInterval: decides ? 30_000 : undefined })
  if (!decides) return EMPTY
  const r = (reviews.data ?? []).filter((x) => x.left > 0)
  const e = (elevations.data?.items ?? []).filter((x) => x.user.userId !== userId && (x.approvers ?? []).includes(userId))
  return { reviews: r, elevations: e, count: r.length + e.length }
}

function Heading({ children }: { children: string }) {
  return <div className="px-3 pb-1 pt-2 text-caption2 font-semibold uppercase tracking-wide text-tertiary">{children}</div>
}

/** The bell's sections for reviews and elevations; nothing when nothing waits. */
export function AccessTimeBellItems({ queue, close }: { queue: AccessTimeQueue; close: () => void }) {
  return (
    <>
      {queue.reviews.length > 0 && (
        <>
          <Heading>Access reviews</Heading>
          <ul aria-label="Access reviews to work through">
            {queue.reviews.map(({ review, left }) => (
              <li key={review.reviewId}>
                <Link to={pathFor('iam-review', { id: review.reviewId })} onClick={close} className="block px-3 py-2 text-sm transition-colors hover:bg-surface-secondary">
                  <span className="block text-primary">
                    {review.name}: <span className="font-medium">{left} to review</span>
                  </span>
                  <span className="block text-caption text-tertiary">Due {dueWords(review.dueAt)}; what is left then loses its access</span>
                </Link>
              </li>
            ))}
          </ul>
        </>
      )}
      {queue.elevations.length > 0 && (
        <>
          <Heading>Elevations to decide</Heading>
          <ul aria-label="Elevations to decide">
            {queue.elevations.map((e) => (
              <li key={e.elevationId}>
                <Link to={pathFor('iam-elevations')} onClick={close} className="block px-3 py-2 text-sm transition-colors hover:bg-surface-secondary">
                  <span className="block text-primary">
                    {e.user.name ?? e.user.userId} asks for <span className="font-medium">{roleLabel(e.role)}</span> for {minutesWords(e.durationMinutes)}
                  </span>
                  <span className="block text-caption text-tertiary">{relativeTime(e.requestedAt)}</span>
                </Link>
              </li>
            ))}
          </ul>
        </>
      )}
    </>
  )
}
