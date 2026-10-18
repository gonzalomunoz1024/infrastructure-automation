import { StatusDot, cx, type DotKind } from '@iaf/ui'
import type { Health, HealthStatus } from '../lib/types'

/*
 * The server's verdict on a provider: healthy, degraded, failing or unknown, with the reasons
 * it gave. The UI never computes the band; it renders it.
 */

/** A figure of a provider's calls, or a dash while there are none: 0% and 0ms would read as measured. */
export const measured = (health: Pick<Health, 'calls'>, text: string): string => (health.calls === 0 ? '—' : text)

const BAND: Record<HealthStatus, { dot: DotKind; label: string; text: string }> = {
  healthy: { dot: 'healthy', label: 'Healthy', text: 'text-healthy' },
  degraded: { dot: 'warning', label: 'Degraded', text: 'text-warning' },
  failing: { dot: 'critical', label: 'Failing', text: 'text-critical' },
  unknown: { dot: 'neutral', label: 'Unknown', text: 'text-tertiary' },
}

export function healthDot(status: HealthStatus | string): DotKind {
  return BAND[status as HealthStatus]?.dot ?? 'neutral'
}

export interface HealthBandProps {
  /** a provider's health, or only a band (an action's) */
  health: Pick<Health, 'status'> & Partial<Pick<Health, 'reasons'>>
  /** the reasons, as sentences under the band */
  showReasons?: boolean
  size?: 'sm' | 'md'
  className?: string
}

export function HealthBand({ health, showReasons, size = 'sm', className }: HealthBandProps) {
  const band = BAND[health.status] ?? BAND.unknown
  if (!showReasons) {
    return (
      <span className={cx('inline-flex items-center gap-1.5', size === 'md' ? 'text-sm' : 'text-caption', className)}>
        <StatusDot kind={band.dot} pulse={health.status === 'failing'} size={size === 'md' ? 'md' : 'sm'} />
        <span className={cx('font-medium', band.text)}>{band.label}</span>
      </span>
    )
  }
  const reasons = health.reasons ?? []
  return (
    <div className={className}>
      <div className="flex items-center gap-2">
        <StatusDot kind={band.dot} pulse={health.status === 'failing'} />
        <span className={cx('text-subheadline font-semibold', band.text)}>{band.label}</span>
      </div>
      {reasons.length > 0 ? (
        <ul className="mt-1.5 space-y-1 text-sm text-secondary">
          {reasons.map((r, i) => (
            <li key={i}>{/[.!?]$/.test(r.trim()) ? r : `${r}.`}</li>
          ))}
        </ul>
      ) : (
        <p className="mt-1.5 text-sm text-secondary">{health.status === 'unknown' ? 'No calls recorded yet.' : 'Every signal is within its SLO.'}</p>
      )}
    </div>
  )
}
