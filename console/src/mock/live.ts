import type { LiveEvent, PlatformEvent } from '../lib/types'

/*
 * The BFF's live updates, for mock mode: the platform's feed mapped as the BFF's PlatformEvents
 * maps it, for one account. A job or batch event of the account becomes `job.updated` (its job,
 * the platform's event and the status in `data`), a registry event keeps its type, content and
 * region changes reach every account as `types.changed` and `region.changed`, and anything else
 * (audit) stays out. Each comes with its cursor, the SSE `id:`.
 */

export interface SequencedLive {
  id: string
  event: LiveEvent
}

const CONTENT = ['workflow.', 'provider.', 'content.', 'resource-type.', 'release.']

/** One platform event as the account `accountId` sees it live, or null when it is not theirs to see. */
export function toLiveEvent(e: PlatformEvent, accountId: string): LiveEvent | null {
  const account = e.context?.accountId
  const wfrn = e.context?.wfrn
  const data = e.data && typeof e.data === 'object' ? (e.data as Record<string, unknown>) : {}
  if (e.type.startsWith('job.') || e.type.startsWith('batch.')) {
    if (account !== accountId) return null
    const out: Record<string, unknown> = { jobId: e.aggregateId, event: e.type }
    for (const k of ['status', 'step', 'kind', 'wave', 'approvalId']) if (data[k] !== undefined && data[k] !== null) out[k] = data[k]
    return { type: 'job.updated', wfrn, at: e.at, key: e.key, data: out }
  }
  if (e.type.startsWith('resource.') && !e.type.startsWith('resource-type.')) {
    if ((account ?? (typeof data.accountId === 'string' ? data.accountId : undefined)) !== accountId) return null
    return { type: e.type, wfrn: wfrn ?? (typeof data.wfrn === 'string' ? data.wfrn : undefined), at: e.at, key: e.key, data }
  }
  if (CONTENT.some((p) => e.type.startsWith(p))) return { type: 'types.changed', at: e.at, key: e.key, data: { event: e.type, subject: e.aggregateId ?? '' } }
  if (e.type.startsWith('region.')) return { type: 'region.changed', at: e.at, key: e.key, data: { event: e.type, region: e.aggregateId ?? '' } }
  return null
}

/** The account's live events after the cursor `after` (all of them when it is 0), oldest first. */
export function liveEventsOf(events: readonly PlatformEvent[], accountId: string, after = 0): SequencedLive[] {
  const out: SequencedLive[] = []
  for (const e of events) {
    if (e.seq <= after) continue
    const live = toLiveEvent(e, accountId)
    if (live) out.push({ id: String(e.seq), event: live })
  }
  return out
}
