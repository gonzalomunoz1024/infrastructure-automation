import { useEffect } from 'react'
import { Icon, Skeleton } from '@iaf/ui'
import { TextLink, type FactItem } from '@iaf/console-kit'
import { useConsole } from '../../app/console'
import { Hint } from '../../app/Hint'
import { pathFor } from '../../app/router'
import { costApi, type ResourceCost } from '../../lib/cost'
import type { ConsoleType } from '../../lib/descriptors'
import { invalidate, keyOf, useQuery } from '../../lib/queries'
import { isRefused } from './common'
import { DEFAULT_EXPLORER, dayYearLabel, explorerQueryOf, isoDay, money } from './model'

/*
 * A resource's price on its page (WP 7.3): its monthly rate, what it cost this month so far and
 * the price that priced it, from `GET /console/v1/r/{type}/{wfrn}/cost`, with a link to the Cost
 * Explorer narrowed to it. The fact loads on its own after the page, so a slow or failing cost
 * service never holds the page up; a resource no price covers says "Not priced". When the
 * person may not read cost here (`cost:list` refused on the wfrn) the page shows no cost at all:
 * the refusal is remembered for the session, and the page is asked to draw its facts again
 * without this one.
 */

/** The resources whose cost the BFF refused to this session: their pages leave the fact out. */
const refused = new Set<string>()
const refusalKey = (type: string, wfrn: string) => `${type}|${wfrn}`

/** The resource page's facts about cost: one, "Monthly cost", or none when the person may not read it. */
export function costFacts(type: ConsoleType, wfrn: string, deleted: boolean): FactItem[] {
  if (!wfrn || refused.has(refusalKey(type.name, wfrn))) return []
  return [{ label: 'Monthly cost', value: <MonthlyCost type={type} wfrn={wfrn} deleted={deleted} /> }]
}

/** How the price was found, in words, for the note behind the info button. */
export function pricingNote(c: ResourceCost): string {
  if (c.source === 'unpriced' || !c.rule) return 'No price list prices this type, so it counts as nothing in the Cost Explorer and in budgets.'
  const by = c.rule.by ? `, by ${c.rule.by} ${String(c.rule.value)}` : ''
  const from = c.pricedFrom === 'observed' ? ', as the provider last saw it' : c.pricedFrom === 'desired' ? ', as it was asked for' : ''
  const since = c.since ? ` The rate holds since ${dayYearLabel(isoDay(Date.parse(c.since)))}.` : ''
  return `Priced by the price list ${c.rule.priceList}: ${c.rule.action} on ${c.rule.provider}${by}${from}.${since} An estimate from list prices, not an invoice.`
}

function MonthlyCost({ type, wfrn, deleted }: { type: ConsoleType; wfrn: string; deleted: boolean }) {
  const { account } = useConsole()
  const cost = useQuery(['resource-cost', account.id, type.name, wfrn, deleted], () => costApi.resource(type.name, wfrn))
  const denied = isRefused(cost.error)

  // the first refusal: remembered, and the page's facts drawn again without this one
  useEffect(() => {
    if (!denied || refused.has(refusalKey(type.name, wfrn))) return
    refused.add(refusalKey(type.name, wfrn))
    invalidate(keyOf(['console-resource', type.name, wfrn]))
  }, [denied, type.name, wfrn])

  if (denied) return null
  if (cost.loading) return <Skeleton className="h-4 w-28" />
  if (cost.error || !cost.data) return <span className="text-tertiary">Not available now</span>
  const c = cost.data
  const note = pricingNote(c)
  const explorer = pathFor('cost', {}, explorerQueryOf({ ...DEFAULT_EXPLORER, groupBy: 'none', wfrn }))
  const info = (
    <Hint label={note}>
      <button type="button" aria-label={`How it is priced: ${note}`} className="inline-flex h-5 w-5 items-center justify-center rounded-full text-tertiary transition-colors hover:text-primary">
        <Icon name="info" size={13} />
      </button>
    </Hint>
  )

  if (c.source === 'unpriced') {
    return (
      <span className="inline-flex items-center gap-1" data-cost="unpriced">
        <span className="text-secondary">Not priced</span>
        {info}
      </span>
    )
  }
  const rate = c.charged ? `${money(c.monthly, c.currency)} a month` : deleted || c.state === 'deleted' ? 'Not charged since it was deleted' : 'Not charged now'
  return (
    <span className="block min-w-0" data-cost="priced">
      <span className="inline-flex items-start gap-1">
        <span className="font-medium tabular-nums">{rate}</span>
        {info}
      </span>
      <span className="block text-caption text-secondary">
        <span className="tabular-nums">{money(c.monthToDate, c.currency)}</span> this month so far.{' '}
        <TextLink to={explorer} className="whitespace-nowrap underline underline-offset-2">
          See in Cost Explorer
        </TextLink>
      </span>
    </span>
  )
}
