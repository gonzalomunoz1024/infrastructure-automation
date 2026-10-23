import { Icon, StatusDot, ThemeToggle, cx, useTheme } from '@iaf/ui'
import { api } from '../lib/api'
import { useQuery } from '../lib/queries'

/*
 * The status bar at the bottom: the build, the contracts pin, whether the platform answers, the
 * theme toggle, and the platform feed where developers work: the dev server and mock mode (the
 * journeys run the built mock app).
 */

const developerTools = import.meta.env.DEV || import.meta.env.VITE_CONSOLE_MOCK === '1'

export function Footer({ onOpenFeed, feedOpen }: { onOpenFeed?: () => void; feedOpen?: boolean }) {
  const { mode, cycle } = useTheme()
  const health = useQuery('health', () => api.health(), { refetchInterval: 60_000 })
  const up = health.data?.status === 'UP'
  const state = health.loading ? 'checking' : health.error ? 'unreachable' : up ? 'up' : (health.data?.status ?? 'unknown').toLowerCase()
  return (
    <footer className="flex h-9 shrink-0 items-center justify-between gap-3 border-t border-stroke-light bg-surface-tertiary px-3 text-caption text-secondary sm:px-4" aria-label="Status bar">
      <div className="flex min-w-0 items-center gap-3">
        <span className="truncate">
          Console <span className="font-mono">v{__APP_VERSION__}</span>
        </span>
        <span className="hidden text-tertiary sm:inline" title="The hub commit the contracts are pinned to">
          contracts <span className="font-mono">{__CONTRACTS_COMMIT__}</span>
        </span>
        <span className="flex items-center gap-1.5" aria-live="polite">
          <StatusDot kind={state === 'up' ? 'healthy' : state === 'checking' ? 'neutral' : 'critical'} size="sm" pulse={state === 'checking'} />
          <span className="hidden sm:inline">platform {state}</span>
        </span>
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
            Platform feed
          </button>
        )}
        <ThemeToggle mode={mode} onCycle={cycle} />
      </div>
    </footer>
  )
}
