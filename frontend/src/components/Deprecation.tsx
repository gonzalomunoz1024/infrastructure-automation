/**
 * How a deprecated operation shows: the capsule on a catalog card or a page header, and the
 * notice with since, sunset and the replacement (linked to its catalog entry when one answers
 * that path). From `api.deprecated` (docs/config-as-code.md): the operation still answers, with
 * Deprecation, Sunset and Link headers, until the sunset date.
 */
import { Icon, Tag, cx } from '@iaf/ui'
import { pathFor } from '../app/router'
import type { CatalogEntry, Deprecation } from '../lib/types'
import { ExternalLink, TextLink } from './common'

const DAY = 86_400_000

/** The date part of a YYYY-MM-DD or ISO date-time, as written. */
export function dateOf(value: string | undefined): string {
  if (!value) return '—'
  const m = /^(\d{4}-\d{2}-\d{2})/.exec(value.trim())
  return m ? m[1] : value
}

/** Where the sunset stands: passed, within 30 days, or later; `days` is the whole days left (negative once passed). */
export function sunsetState(sunset: string | undefined, now = Date.now()): { state: 'passed' | 'soon' | 'later' | 'unknown'; days: number | null } {
  if (!sunset) return { state: 'unknown', days: null }
  const at = Date.parse(/^\d{4}-\d{2}-\d{2}$/.test(sunset.trim()) ? `${sunset.trim()}T00:00:00Z` : sunset)
  if (Number.isNaN(at)) return { state: 'unknown', days: null }
  const days = Math.ceil((at - now) / DAY) || 0
  return { state: days < 0 ? 'passed' : days <= 30 ? 'soon' : 'later', days }
}

/** The catalog entry that answers the replacement path, when a published workflow does. */
export function replacementEntry<T extends Pick<CatalogEntry, 'workflow' | 'path' | 'title'>>(replacement: string | undefined, catalog: readonly T[] | undefined): T | undefined {
  if (!replacement || !catalog) return undefined
  const path = replacement.trim()
  const bare = path.replace(/^https?:\/\/[^/]+/, '')
  return catalog.find((e) => e.path === path || e.path === bare)
}

/** "deprecated", tinted as a warning, with the dates on hover. */
export function DeprecatedTag({ deprecated, size = 'sm', className }: { deprecated: Deprecation; size?: 'sm' | 'md'; className?: string }) {
  return (
    <Tag size={size} tone="warning" icon="warning" className={className} title={`Deprecated since ${dateOf(deprecated.since)}; sunset ${dateOf(deprecated.sunset)}${deprecated.replacement ? `; use ${deprecated.replacement}` : ''}`}>
      deprecated
    </Tag>
  )
}

export interface DeprecationNoticeProps {
  deprecated: Deprecation
  /** the catalog, to link the replacement to the workflow that answers it */
  catalog?: readonly Pick<CatalogEntry, 'workflow' | 'path' | 'title'>[]
  /** for tests: the clock the sunset is judged against */
  now?: number
  className?: string
}

/** The notice on a run page or a card: since, sunset (and how far off), the replacement and the message. */
export function DeprecationNotice({ deprecated, catalog, now, className }: DeprecationNoticeProps) {
  const sunset = sunsetState(deprecated.sunset, now)
  const entry = replacementEntry(deprecated.replacement, catalog)
  const replacement = deprecated.replacement?.trim()
  const passed = sunset.state === 'passed'
  return (
    <div className={cx('rounded-lg border p-4', passed ? 'border-critical/30 bg-critical-tint' : 'border-warning/30 bg-warning-tint', className)} role="note" aria-label="Deprecated operation" data-testid="deprecation-notice">
      <p className={cx('flex items-center gap-2 text-sm font-semibold', passed ? 'text-critical' : 'text-warning')}>
        <Icon name="warning" size={15} />
        {passed ? 'Past its sunset' : 'Deprecated'}
      </p>
      <dl className="mt-2 grid grid-cols-[auto_1fr] gap-x-3 gap-y-1 text-caption">
        <dt className="text-tertiary">Since</dt>
        <dd className="text-primary">{dateOf(deprecated.since)}</dd>
        <dt className="text-tertiary">Sunset</dt>
        <dd className="text-primary">
          {dateOf(deprecated.sunset)}
          {sunset.days !== null && <span className="text-secondary">{sunset.state === 'passed' ? ` · ${-sunset.days} ${-sunset.days === 1 ? 'day' : 'days'} ago` : sunset.days === 0 ? ' · today' : ` · in ${sunset.days} ${sunset.days === 1 ? 'day' : 'days'}`}</span>}
        </dd>
        {replacement && (
          <>
            <dt className="text-tertiary">Use instead</dt>
            <dd className="min-w-0">
              {entry ? (
                <TextLink to={pathFor('catalog-run', { workflow: entry.workflow })} className="font-medium">
                  {entry.title} <span className="font-mono text-caption text-secondary">{entry.path}</span>
                </TextLink>
              ) : /^https?:\/\//.test(replacement) ? (
                <ExternalLink href={replacement} className="font-mono text-caption">
                  {replacement}
                </ExternalLink>
              ) : (
                <span className="break-all font-mono text-caption text-primary">{replacement}</span>
              )}
            </dd>
          </>
        )}
      </dl>
      {deprecated.message && <p className="mt-2 text-sm text-secondary">{deprecated.message}</p>}
      <p className="mt-2 text-caption text-tertiary">Every answer carries Deprecation and Sunset headers{replacement ? ' and a Link to the successor' : ''}; the operation keeps answering until the sunset date.</p>
    </div>
  )
}
