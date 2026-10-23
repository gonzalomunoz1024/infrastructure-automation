import { StatusDot, cx, type DotKind } from '@iaf/ui'
import { titleCase } from '@iaf/console-kit'

/*
 * A request's status, the way the console shows every status: the colour in the dot, the word in
 * the primary text colour on a soft tint, so it reads on both themes at AA.
 */

const STYLE: Record<string, { tint: string; dot?: DotKind; varName?: string; pulse?: boolean }> = {
  queued: { tint: 'bg-surface-secondary', dot: 'neutral' },
  running: { tint: 'bg-accent-tint', varName: '--color-accent', pulse: true },
  'waiting-approval': { tint: 'bg-warning-tint', dot: 'warning', pulse: true },
  'needs-attention': { tint: 'bg-warning-tint', dot: 'warning' },
  succeeded: { tint: 'bg-healthy-tint', dot: 'healthy' },
  failed: { tint: 'bg-critical-tint', dot: 'critical' },
  cancelled: { tint: 'bg-surface-secondary', dot: 'neutral' },
  'rolled-back': { tint: 'bg-warning-tint', dot: 'warning' },
}

export function JobStatusBadge({ status, size = 'md', className }: { status: string; size?: 'sm' | 'md'; className?: string }) {
  const style = STYLE[status]
  return (
    <span className={cx('inline-flex max-w-full items-center gap-1.5 rounded-full font-medium text-primary', size === 'sm' ? 'h-5 px-1.5 text-caption2' : 'h-6 px-2 text-caption', style ? style.tint : 'border border-stroke-light', className)} data-status={status}>
      <StatusDot kind={style?.dot ?? 'neutral'} varName={style?.varName} pulse={style?.pulse} size="sm" />
      <span className="truncate">{titleCase(status)}</span>
    </span>
  )
}
