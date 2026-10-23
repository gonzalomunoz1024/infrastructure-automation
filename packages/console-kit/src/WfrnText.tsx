import { CopyIconButton, cx, truncateMiddle } from '@iaf/ui'
import { isValidWfrn, parseWfrn } from '@iaf/wfrn'

/*
 * A wfrn as the console writes it everywhere: monospace, elided in the middle when long (the
 * partition and service stay at the left, the type and id at the right), the whole name in the
 * tooltip and for screen readers, and a copy button at its side.
 */

/** The display form of a wfrn within `max` characters; 0 shows it whole. */
export function displayWfrn(wfrn: string, max = 40): string {
  if (max <= 0 || wfrn.length <= max) return wfrn
  if (isValidWfrn(wfrn)) {
    const p = parseWfrn(wfrn)
    const tail = `${p.type}/${p.id}`
    const short = `wfrn:${p.partition}:${p.service}:…:${tail}`
    if (short.length <= max) return short
    const shorter = `wfrn:…:${tail}`
    if (shorter.length <= max) return shorter
  }
  return truncateMiddle(wfrn, max)
}

export interface WfrnTextProps {
  wfrn: string
  /** characters to keep before eliding the middle (default 40); 0 never elides */
  max?: number
  /** the copy button at the right (default true) */
  copy?: boolean
  /** makes the name a link, to the resource's page */
  href?: string
  /** 'sm' for table cells (default), 'md' for detail pages */
  size?: 'sm' | 'md'
  className?: string
}

export function WfrnText({ wfrn, max = 40, copy = true, href, size = 'sm', className }: WfrnTextProps) {
  const shown = displayWfrn(wfrn, max)
  const elided = shown !== wfrn
  const textClass = cx('inline-flex min-w-0 font-mono tabular-nums', size === 'sm' ? 'text-caption' : 'text-footnote')
  const text = (
    <>
      {elided && <span className="sr-only">{wfrn}</span>}
      <span aria-hidden={elided || undefined} title={elided ? wfrn : undefined} className="truncate">
        {shown}
      </span>
    </>
  )
  return (
    <span className={cx('inline-flex min-w-0 max-w-full items-center gap-1', className)} data-wfrn={wfrn}>
      {href ? (
        <a href={href} className={cx(textClass, 'text-accent transition-opacity hover:opacity-70')}>
          {text}
        </a>
      ) : (
        <span className={cx(textClass, 'text-primary')}>{text}</span>
      )}
      {copy && <CopyIconButton text={wfrn} label="Copy wfrn" className="shrink-0" />}
    </span>
  )
}
