import { StatusDot, cx, type DotKind } from '@iaf/ui'
import { LIFECYCLE_CLASSES, type LifecycleClass } from './types'

/*
 * A resource's state as the console shows it: the type's own word, on the tint of the lifecycle
 * class it folds onto, with a dot in the class colour. The text stays in the primary colour on
 * every tint so it meets AA contrast in both themes; the dot carries the colour.
 */

const BY_NAME: [RegExp, LifecycleClass][] = [
  [/^(ready|active|available|running|healthy|online|ok|fulfilled|succeeded|deprecated|attached|bound)$/, 'active'],
  [/^(pending|queued|building|creating|provisioning|requested|starting|scheduled|new)$/, 'pending'],
  [/^(updating|patching|restarting|resizing|modifying|upgrading|in-progress|changing|migrating)$/, 'updating'],
  [/^(deleting|retiring|terminating|destroying|stopping|decommissioning)$/, 'deleting'],
  [/^(deleted|retired|terminated|destroyed|gone|removed|archived|stopped)$/, 'deleted'],
  [/^(failed|error|errored|degraded|broken|unhealthy|needs-attention)$/, 'failed'],
]

export function isLifecycleClass(s: unknown): s is LifecycleClass {
  return typeof s === 'string' && (LIFECYCLE_CLASSES as readonly string[]).includes(s)
}

/**
 * The lifecycle class of a type's state word: the type's own `console.lifecycle` map first, then
 * the state's name (ready is active, building is pending, retired is deleted, …), else null.
 */
export function lifecycleOf(status: string | undefined | null, map?: Record<string, LifecycleClass | string>): LifecycleClass | null {
  if (!status) return null
  const mapped = map?.[status] ?? map?.[status.toLowerCase()]
  if (isLifecycleClass(mapped)) return mapped
  const s = status.toLowerCase()
  for (const [re, cls] of BY_NAME) if (re.test(s)) return cls
  return null
}

const STYLE: Record<LifecycleClass, { tint: string; dot?: DotKind; varName?: string; pulse?: boolean }> = {
  pending: { tint: 'bg-accent-tint', varName: '--color-accent', pulse: true },
  active: { tint: 'bg-healthy-tint', dot: 'healthy' },
  updating: { tint: 'bg-accent-tint', varName: '--color-accent', pulse: true },
  deleting: { tint: 'bg-warning-tint', dot: 'warning', pulse: true },
  deleted: { tint: 'bg-surface-secondary', dot: 'neutral' },
  failed: { tint: 'bg-critical-tint', dot: 'critical' },
}

export interface LifecycleBadgeProps {
  /** the type's own state word, shown as the label */
  status: string
  /** the class it folds onto; worked out from the word when left out */
  lifecycle?: LifecycleClass | null
  /** the type's `console.lifecycle` map, used when `lifecycle` is left out */
  map?: Record<string, LifecycleClass | string>
  size?: 'sm' | 'md'
  className?: string
}

export function LifecycleBadge({ status, lifecycle, map, size = 'md', className }: LifecycleBadgeProps) {
  const cls = lifecycle === undefined ? lifecycleOf(status, map) : lifecycle
  const style = cls ? STYLE[cls] : null
  const sizing = size === 'sm' ? 'h-5 px-1.5 text-caption2' : 'h-6 px-2 text-caption'
  return (
    <span
      className={cx('inline-flex max-w-full items-center gap-1.5 rounded-full font-medium text-primary', sizing, style ? style.tint : 'border border-stroke-light text-secondary', className)}
      title={cls && cls !== status.toLowerCase() ? `${status} (${cls})` : undefined}
      data-lifecycle={cls ?? 'unknown'}
    >
      {/* the word carries the meaning; the dot is decoration for assistive technology */}
      <span aria-hidden="true" className="flex">
        <StatusDot kind={style?.dot} varName={style?.varName} pulse={style?.pulse} size="sm" />
      </span>
      <span className="truncate">{status}</span>
    </span>
  )
}
