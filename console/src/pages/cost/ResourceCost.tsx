import type { FactItem } from '@iaf/console-kit'
import type { ConsoleType } from '../../lib/descriptors'

/*
 * A resource's price on its page (WP 7.3): its monthly rate, what it cost this month and the
 * rule that priced it, from `GET /console/v1/r/{type}/{wfrn}/cost`. Phase 9 builds it out; until
 * then the page's facts hold nothing about cost.
 */

export function costFacts(_type: ConsoleType, _wfrn: string, _deleted: boolean): FactItem[] {
  return []
}
