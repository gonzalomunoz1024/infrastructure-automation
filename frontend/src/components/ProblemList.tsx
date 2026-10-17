import { Button, Icon, Tag, cx } from '@iaf/ui'
import type { Problem } from '../lib/types'

/*
 * Problems as the checks, the validate endpoints and a 400 report them: level, code, where
 * (step, field), the message, and the one-click fix when the server offers one.
 */

/** 'input.vmId', '$.vmId', '/vmId', 'properties.vmId' and 'vmId' all name the same form field. */
export function normalizeField(field: string | undefined): string | undefined {
  if (!field) return undefined
  return field
    .replace(/^\$\.?/, '')
    .replace(/^\/+/, '')
    .replace(/\//g, '.')
    .replace(/^(?:(?:input|body|properties)\.)+/, '')
}

/** The problems that sit on one form field. */
export function problemsFor(problems: Problem[] | undefined, field: string): Problem[] {
  if (!problems) return []
  return problems.filter((p) => normalizeField(p.field) === field)
}

/** The problems that name no field (shown above the form). */
export function unfieldedProblems(problems: Problem[] | undefined, fields: string[]): Problem[] {
  if (!problems) return []
  return problems.filter((p) => {
    const f = normalizeField(p.field)
    return !f || !fields.includes(f)
  })
}

export interface ProblemListProps {
  problems: Problem[]
  /** apply a fix; the button only shows when given */
  onFix?: (problem: Problem) => void
  /** tighter rows for drawers and side panels */
  compact?: boolean
  /** text when the list is empty (nothing renders without it) */
  emptyLabel?: string
  className?: string
}

/** Problems in a quiet list: critical glyph for errors, warning for warnings. */
export function ProblemList({ problems, onFix, compact, emptyLabel, className }: ProblemListProps) {
  if (problems.length === 0) {
    if (!emptyLabel) return null
    return (
      <p className={cx('flex items-center gap-1.5 text-caption text-healthy', className)}>
        <Icon name="check" size={13} strokeWidth={2.2} />
        {emptyLabel}
      </p>
    )
  }
  return (
    <ul className={cx('divide-y divide-stroke-light rounded-md border border-stroke-light bg-surface', className)} aria-label="Problems">
      {problems.map((p, i) => {
        const error = p.level === 'error'
        return (
          <li key={`${p.code}-${p.step ?? ''}-${p.field ?? ''}-${i}`} className={cx('flex items-start gap-3', compact ? 'px-3 py-2' : 'px-4 py-3')}>
            <Icon name={error ? 'close-circle' : 'alert-triangle'} size={16} className={cx('mt-0.5 shrink-0', error ? 'text-critical' : 'text-warning')} />
            <div className="min-w-0 flex-1">
              <p className={cx('text-primary', compact ? 'text-caption' : 'text-sm')}>{p.message}</p>
              <div className="mt-1 flex flex-wrap items-center gap-1.5">
                <Tag size="sm" mono outline>
                  {p.code}
                </Tag>
                {p.step && (
                  <Tag size="sm" icon="workflow">
                    {p.step}
                  </Tag>
                )}
                {p.field && (
                  <Tag size="sm" mono>
                    {p.field}
                  </Tag>
                )}
              </div>
            </div>
            {p.fix && onFix && (
              <Button size="sm" variant="capsule" onClick={() => onFix(p)} className="shrink-0">
                {p.fix.label}
              </Button>
            )}
          </li>
        )
      })}
    </ul>
  )
}
