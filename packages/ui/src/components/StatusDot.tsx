import { cx } from '../lib/cx'

export type DotKind = 'healthy' | 'warning' | 'critical' | 'neutral'

const KIND_VAR: Record<DotKind, string> = {
  healthy: '--color-healthy',
  critical: '--color-critical',
  warning: '--color-warning',
  neutral: '--color-text-tertiary',
}

export interface StatusDotProps {
  kind?: DotKind
  /** escape hatch: a CSS variable name (e.g. '--color-state-running') */
  varName?: string
  /** subtle infinite pulse (live indicators) */
  pulse?: boolean
  size?: 'sm' | 'md'
  /** text rendered next to the dot */
  label?: string
  className?: string
}

/** The one status dot — replaces every hand-rolled h-2 w-2 rounded-full span. */
export function StatusDot({ kind = 'healthy', varName, pulse, size = 'md', label, className }: StatusDotProps) {
  const dot = (
    <span
      className={cx('shrink-0 rounded-full', size === 'sm' ? 'h-1.5 w-1.5' : 'h-2 w-2', pulse && 'animate-pulse-subtle', !label && className)}
      style={{ background: `var(${varName ?? KIND_VAR[kind]})` }}
      aria-hidden={label ? 'true' : undefined}
      role={label ? undefined : 'img'}
      aria-label={label ? undefined : kind}
    />
  )
  if (!label) return dot
  return (
    <span className={cx('inline-flex items-center gap-1.5 text-caption text-secondary', className)}>
      {dot}
      {label}
    </span>
  )
}
