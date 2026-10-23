import type { ReactNode } from 'react'
import { cx, type Tone } from '@iaf/ui'

/*
 * A capsule whose colour is in its dot. Atlas's Tag colours its text on a tint, which does not
 * reach WCAG AA for every tone (the console's decision 6); here the label stays in the primary
 * text colour on the secondary surface and the tone is a dot, like LifecycleBadge and
 * StatusBadge. For roles, effects, statuses: anything named by a word and coloured by a tone.
 */

const DOT: Record<Tone, string> = {
  neutral: 'bg-tertiary',
  accent: 'bg-accent',
  healthy: 'bg-healthy',
  warning: 'bg-warning',
  critical: 'bg-critical',
}

export interface DotTagProps {
  tone?: Tone
  children: ReactNode
  size?: 'sm' | 'md'
  /** pulse the dot (something is waiting) */
  pulse?: boolean
  mono?: boolean
  uppercase?: boolean
  className?: string
  title?: string
}

export function DotTag({ tone = 'neutral', children, size = 'md', pulse, mono, uppercase, className, title }: DotTagProps) {
  return (
    <span title={title} className={cx('inline-flex max-w-full items-center gap-1 whitespace-nowrap rounded-full bg-surface-secondary font-medium text-primary', size === 'sm' ? 'px-1.5 py-px text-caption2' : 'px-2 py-0.5 text-caption', mono && 'font-mono', uppercase && 'uppercase tracking-wide', className)}>
      <span aria-hidden="true" className={cx('h-1.5 w-1.5 shrink-0 rounded-full', DOT[tone], pulse && 'animate-pulse-subtle')} />
      <span className="truncate">{children}</span>
    </span>
  )
}
