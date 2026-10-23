import { Icon } from '@iaf/ui'
import { Link, pathFor } from '../../app/router'
import { quotaRefusalOf } from '../../lib/quotas'
import type { ConsolePreview } from '../../lib/types'

/*
 * Under a create's review, when its preview says the account's quota for the type is used up:
 * what the quota is, and a link that opens the request dialog for it on Service Quotas, where a
 * rule may raise it at once.
 */

export function QuotaRefusalNote({ preview, type, title }: { preview: ConsolePreview | undefined; type: string; title: string }) {
  const refusal = quotaRefusalOf(preview, type)
  if (!refusal) return null
  return (
    <p className="mt-3 flex flex-wrap items-center gap-x-2 gap-y-1 text-sm text-secondary" data-quota-refusal={refusal.key}>
      <Icon name="info" size={14} className="shrink-0 text-tertiary" />
      <span>The account holds as many {title.toLowerCase()} as its quota allows.</span>
      <Link to={pathFor('service-quotas', {}, { request: refusal.key })} className="font-medium text-accent underline-offset-2 hover:underline">
        Request more {title.toLowerCase()}
      </Link>
    </p>
  )
}
