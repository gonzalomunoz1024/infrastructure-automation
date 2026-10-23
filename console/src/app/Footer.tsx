import { Icon, StatusDot, ThemeToggle, cx, useTheme } from '@iaf/ui'
import { healthApi, platformWord } from '../lib/health'
import { useQuery } from '../lib/queries'
import { Link, pathFor } from './router'

/*
 * The status bar at the bottom: the build, the contracts pin, the platform's health from the BFF
 * (`GET /console/v1/health`, linking to the Service health page), the
 * theme toggle, and the platform feed where developers work: the dev server and mock mode (the
 * journeys run the built mock app).
 */

const developerTools = import.meta.env.DEV || import.meta.env.VITE_CONSOLE_MOCK === '1'

export function Footer({ onOpenFeed, feedOpen }: { onOpenFeed?: () => void; feedOpen?: boolean }) {
  const { mode, cycle } = useTheme()
  const health = useQuery('console-health', () => healthApi.get(), { refetchInterval: 60_000 })
  const platform = health.data?.services.find((s) => s.serviceId === 'platform')
  const state = health.loading ? 'checking' : health.error ? 'unreachable' : platformWord(platform?.status)
  return (
    <footer className="flex h-9 shrink-0 items-center justify-between gap-3 border-t border-stroke-light bg-surface-tertiary px-3 text-caption text-secondary sm:px-4" aria-label="Status bar">
      <div className="flex min-w-0 items-center gap-3">
        <span className="truncate">
          Console <span className="font-mono">v{__APP_VERSION__}</span>
        </span>
        <span className="hidden text-tertiary sm:inline" title="The hub commit the contracts are pinned to">
          contracts <span className="font-mono">{__CONTRACTS_COMMIT__}</span>
        </span>
        <Link to={pathFor('health')} className="flex items-center gap-1.5 rounded-sm hover:text-primary" aria-live="polite" data-platform-health={state}>
          <StatusDot kind={state === 'up' ? 'healthy' : state === 'checking' ? 'neutral' : state === 'degraded' ? 'warning' : 'critical'} size="sm" pulse={state === 'checking'} />
          <span className="sr-only sm:not-sr-only">platform {state}</span>
          <span className="sr-only">, service health</span>
        </Link>
      </div>
      <div className="flex shrink-0 items-center gap-1">
        {developerTools && onOpenFeed && (
          <button
            type="button"
            onClick={onOpenFeed}
            aria-pressed={feedOpen}
            className={cx('flex h-7 items-center gap-1.5 rounded-full px-2.5 text-caption transition-colors hover:bg-surface-secondary hover:text-primary', feedOpen ? 'bg-surface-secondary text-primary' : 'text-secondary')}
          >
            <Icon name="pulse" size={13} />
            Live feed
          </button>
        )}
        <ThemeToggle mode={mode} onCycle={cycle} />
      </div>
    </footer>
  )
}
