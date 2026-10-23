import { useId, useState } from 'react'
import { Button, Icon, cx, formatNumber, plural } from '@iaf/ui'
import { LifecycleBadge, isLifecycleClass } from '@iaf/console-kit'
import { useConsole } from '../../app/console'
import { Link, resourcePath } from '../../app/router'
import { formatAccountId } from '../../lib/accounts'
import { isApiError } from '../../lib/api'
import { partsOfWfrn, singularOf } from '../../lib/descriptors'
import type { BlastRadius, Dependent } from '../../lib/types'

/*
 * What depends on a resource, before a delete or a destructive verb runs (WP 4.5): in words first,
 * then the dependents the account may see, grouped by how far they are from it, each linking to
 * its page with its state in words beside a dot; the ones in other accounts are counted, never
 * named. A list rather than a picture, so it reads the same to a screen reader and on a phone.
 */

const STEP_WORDS = ['', 'one', 'two', 'three', 'four', 'five']

/** How far a dependent is: "Directly", "Two steps away", up to the BFF's deepest walk (5). */
export function distanceLabel(depth: number): string {
  if (depth <= 1) return 'Directly'
  const word = STEP_WORDS[depth] ?? formatNumber(depth)
  return `${word.charAt(0).toUpperCase()}${word.slice(1)} steps away`
}

export interface DependentGroup {
  depth: number
  label: string
  items: Dependent[]
}

/** The named dependents by how far they are, nearest first; one without a depth counts as direct. */
export function dependentsByDepth(dependents: Dependent[]): DependentGroup[] {
  const groups = new Map<number, Dependent[]>()
  for (const d of dependents) {
    const depth = Math.max(1, d.depth ?? 1)
    groups.set(depth, [...(groups.get(depth) ?? []), d])
  }
  return [...groups].sort(([a], [b]) => a - b).map(([depth, items]) => ({ depth, label: distanceLabel(depth), items }))
}

/** The accounts other than the session's that hold dependents: counted, never named. */
export function otherAccountsOf(radius: Pick<BlastRadius, 'byAccount'>): { accountId: string; count: number }[] {
  return radius.byAccount.filter((a) => !a.current && a.count > 0).map(({ accountId, count }) => ({ accountId, count }))
}

/** "Deleting web-01", or "Running reimage on web-01" for another destructive verb. */
function acting(name: string, verb?: string): string {
  return !verb || verb === 'delete' ? `Deleting ${name}` : `Running ${verb} on ${name}`
}

/**
 * The radius in one sentence: "Deleting web-01 would leave 2 resources without what they were
 * built from: 2 depend on it directly." A radius read from a refusal that cannot tell how many
 * depend on it directly (direct 0) leaves the second half out rather than guess.
 */
export function radiusSummary(radius: Pick<BlastRadius, 'total' | 'direct'>, name: string, verb?: string): string {
  const { total, direct } = radius
  if (total === 0) return `${acting(name, verb)} leaves no other resource without what it was built from.`
  const head = !verb || verb === 'delete' ? `${acting(name, verb)} would leave ${plural(total, 'resource')} without what ${total === 1 ? 'it was' : 'they were'} built from` : `${acting(name, verb)} would change what ${plural(total, 'resource')} ${total === 1 ? 'was' : 'were'} built from`
  if (direct <= 0) return `${head}.`
  const through = total > direct ? ` and ${formatNumber(total - direct)} more through them` : ''
  return `${head}: ${formatNumber(direct)} ${direct === 1 ? 'depends' : 'depend'} on it directly${through}.`
}

/** The confirmation's one line: "Nothing else depends on it.", or how many do and where. */
export function radiusLine(radius: Pick<BlastRadius, 'total' | 'byAccount'>): string {
  if (radius.total === 0) return 'Nothing else depends on it.'
  const elsewhere = otherAccountsOf(radius).reduce((n, a) => n + a.count, 0)
  const where = elsewhere > 0 ? `, ${formatNumber(elsewhere)} of them in ${otherAccountsOf(radius).length === 1 ? 'another account' : 'other accounts'}` : ''
  return `${plural(radius.total, 'resource')} ${radius.total === 1 ? 'depends' : 'depend'} on it${where}.`
}

/**
 * The radius a 409 `dependents.exist` carries (`detail: { dependents, total, byAccount }`), when a
 * dependent arrived between the preview and the run. The refusal does not say how many depend on
 * the resource directly when some live in other accounts, so `direct` is 0 then.
 */
export function radiusOfRefusal(error: unknown): BlastRadius | undefined {
  if (!isApiError(error) || error.status !== 409 || error.error !== 'dependents.exist') return undefined
  const detail = error.detail && typeof error.detail === 'object' ? (error.detail as Partial<BlastRadius>) : {}
  const dependents = Array.isArray(detail.dependents) ? detail.dependents.filter((d): d is Dependent => !!d && typeof d.wfrn === 'string' && typeof d.type === 'string') : []
  const byAccount = Array.isArray(detail.byAccount) ? detail.byAccount : []
  const total = typeof detail.total === 'number' ? detail.total : dependents.length
  const direct = dependents.length === total ? dependents.filter((d) => (d.depth ?? 1) === 1).length : 0
  return { total, direct, byAccount, dependents, edges: [], depth: Math.max(1, ...dependents.map((d) => d.depth ?? 1)) }
}

/** How many of a group show before "Show all". */
const SHOWN = 10

export interface BlastRadiusCardProps {
  radius: BlastRadius
  /** the resource's kind in words: "virtual machine" */
  noun: string
  /** the resource's name: "web-01" */
  name: string
  /** the verb about to run; a delete when left out */
  verb?: string
  /** tighter, for a drawer */
  compact?: boolean
}

function DependentList({ group, labelId }: { group: DependentGroup; labelId: string }) {
  const { types } = useConsole()
  const [all, setAll] = useState(false)
  const shown = all ? group.items : group.items.slice(0, SHOWN)
  const typeWords = (type: string) => {
    const t = types.find((x) => x.name === type)
    return t ? singularOf(t.title) : type
  }
  return (
    <>
      <ul aria-labelledby={labelId} className="mt-1 divide-y divide-stroke-light overflow-hidden rounded-md border border-stroke-light bg-surface">
        {shown.map((d) => {
          const service = partsOfWfrn(d.wfrn)?.service ?? types.find((x) => x.name === d.type)?.service ?? d.type
          return (
            <li key={d.wfrn} className="flex flex-wrap items-center gap-x-2 gap-y-1 px-3 py-2 text-sm" data-dependent={d.wfrn}>
              <Link to={resourcePath(service, d.type, d.wfrn)} className="min-w-0 max-w-full truncate font-medium text-accent transition-opacity hover:opacity-70">
                {d.name ?? d.id ?? d.wfrn}
              </Link>
              <span className="text-caption text-secondary">{typeWords(d.type)}</span>
              {d.state && <LifecycleBadge status={d.state} lifecycle={isLifecycleClass(d.state) ? d.state : undefined} size="sm" />}
            </li>
          )
        })}
      </ul>
      {group.items.length > SHOWN && (
        <Button size="sm" variant="ghost" className="mt-1" aria-expanded={all} onClick={() => setAll((v) => !v)}>
          {all ? 'Show fewer' : `Show all ${formatNumber(group.items.length)}`}
        </Button>
      )}
    </>
  )
}

/** The blast radius in words, then by name: what would be left without what it was built from. */
export function BlastRadiusCard({ radius, noun, name, verb, compact }: BlastRadiusCardProps) {
  const id = useId()
  const groups = dependentsByDepth(radius.dependents)
  const others = otherAccountsOf(radius)
  const calm = radius.total === 0
  return (
    <section aria-labelledby={`${id}-title`} className={cx('rounded-lg border text-sm', calm ? 'border-stroke-light' : 'border-warning/40 bg-warning-tint/40', compact ? 'p-3' : 'p-4')} data-testid="blast-radius" data-total={radius.total}>
      <div className="flex items-start gap-3">
        <Icon name={calm ? 'check' : 'alert-triangle'} size={18} className={cx('mt-0.5 shrink-0', calm ? 'text-healthy' : 'text-warning')} />
        <div className="min-w-0 flex-1">
          <h3 id={`${id}-title`} className="font-semibold text-primary">
            {calm ? `Nothing depends on this ${noun}` : `What depends on ${name}`}
          </h3>
          <p className="mt-0.5 text-secondary">{radiusSummary(radius, name, verb)}</p>
        </div>
      </div>
      {groups.map((g) => (
        <div key={g.depth} className="mt-3">
          <h4 id={`${id}-d${g.depth}`} className="text-caption font-medium text-secondary">
            {g.label} <span className="text-tertiary">· {formatNumber(g.items.length)}</span>
          </h4>
          <DependentList group={g} labelId={`${id}-d${g.depth}`} />
        </div>
      ))}
      {others.length > 0 && (
        <ul className="mt-3 space-y-1 text-caption text-secondary" aria-label="In other accounts">
          {others.map((a) => (
            <li key={a.accountId} className="flex items-start gap-2">
              <Icon name="lock" size={12} className="mt-0.5 shrink-0 text-tertiary" />
              <span>
                {formatNumber(a.count)} {radius.dependents.length > 0 ? 'more ' : ''}in account <span className="font-mono text-primary">{formatAccountId(a.accountId)}</span>, which you cannot see into from this account
              </span>
            </li>
          ))}
        </ul>
      )}
      {radius.truncated && <p className="mt-2 text-caption text-secondary">The count stopped at 500: more depend on it than are listed here.</p>}
      {!calm && <p className="mt-2 text-caption text-tertiary">Counted up to {radius.depth === 1 ? 'one step' : `${STEP_WORDS[radius.depth] ?? formatNumber(radius.depth)} steps`} away.</p>}
    </section>
  )
}
