import { Card, Icon, IconButton, cx, useLocalStorage, type IconName } from '@iaf/ui'
import { useConsole } from './console'
import { Link } from './router'
import type { ConsoleService } from '../lib/descriptors'
import { tourFor, type TourKind } from '../lib/tour'

/*
 * "Getting started with Compute": the tour generated from the service's types and workflows,
 * shown on the service's page and dismissable per service, per browser.
 */

const ICONS: Record<TourKind, IconName> = { create: 'plus', operate: 'play', follow: 'job', access: 'shield' }

export function tourStorageKey(serviceId: string): string {
  return `console-tour-dismissed:${serviceId}`
}

export function GettingStarted({ service, className }: { service: ConsoleService; className?: string }) {
  const { types } = useConsole()
  const [dismissed, setDismissed] = useLocalStorage<boolean>(tourStorageKey(service.id), false)
  if (dismissed) return null
  const steps = tourFor(service, types)
  return (
    <Card
      title={`Getting started with ${service.title}`}
      subtitle={`Written from what ${service.owners.join(' and ')} publish, so it stays true as the service grows.`}
      actions={
        <IconButton label="Dismiss the getting-started tour" size="sm" onClick={() => setDismissed(true)}>
          <Icon name="close" size={14} />
        </IconButton>
      }
      className={className}
      data-testid="getting-started"
    >
      <ol className="space-y-3" aria-label={`Getting started with ${service.title}`}>
        {steps.map((s, i) => (
          <li key={s.id} className="flex gap-3">
            <span className={cx('mt-0.5 flex size-6 shrink-0 items-center justify-center rounded-full bg-accent-tint text-caption2 font-semibold text-accent')} aria-hidden="true">
              {i + 1}
            </span>
            <div className="min-w-0">
              <div className="flex items-center gap-1.5 text-sm font-medium text-primary">
                <Icon name={ICONS[s.kind]} size={13} className="text-tertiary" aria-hidden="true" />
                {s.href ? (
                  <Link to={s.href} className="text-accent transition-opacity hover:opacity-70">
                    {s.title}
                  </Link>
                ) : (
                  s.title
                )}
              </div>
              <p className="mt-0.5 text-caption text-secondary">{s.detail}</p>
            </div>
          </li>
        ))}
      </ol>
    </Card>
  )
}
