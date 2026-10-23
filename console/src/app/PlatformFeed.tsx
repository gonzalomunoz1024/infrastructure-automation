import { useEffect, useRef, useState } from 'react'
import { Button, Drawer, EmptyState, Icon, JsonView, OverlayBody, OverlayHeader, StatusDot, Tag, cx, relativeTime, type DotKind } from '@iaf/ui'
import { WfrnText } from '@iaf/console-kit'
import { api } from '../lib/api'
import { openPlatformStream } from '../lib/events'
import type { StreamState } from '../lib/sse'
import type { PlatformEvent } from '../lib/types'

/*
 * The platform feed, for developers: GET /api/v1/events/stream over SSE, every event as it
 * happens, newest first, with the connection's state and a pause for reading. Resumes after the
 * last event seen when the stream drops. The platform names each event after its type, so the
 * stream is opened with the contract's whole list of types (lib/events.ts).
 */

const MAX_EVENTS = 200

const STATE: Record<StreamState, { dot: DotKind; pulse?: boolean; label: string }> = {
  connecting: { dot: 'neutral', pulse: true, label: 'Connecting' },
  open: { dot: 'healthy', pulse: true, label: 'Live' },
  retrying: { dot: 'warning', pulse: true, label: 'Reconnecting' },
  closed: { dot: 'neutral', label: 'Closed' },
}

export function PlatformFeed({ onClose }: { onClose: () => void }) {
  const [events, setEvents] = useState<PlatformEvent[]>([])
  const [state, setState] = useState<StreamState>('connecting')
  const [delay, setDelay] = useState(0)
  const [paused, setPaused] = useState(false)
  const [held, setHeld] = useState(0)
  const [open, setOpen] = useState<string | null>(null)
  const pausedRef = useRef(false)
  const buffer = useRef<PlatformEvent[]>([])

  useEffect(() => {
    const stream = openPlatformStream<PlatformEvent>(
      api.eventsStreamUrl(),
      (event) => {
        if (!event || typeof event !== 'object' || typeof event.type !== 'string') return
        if (pausedRef.current) {
          buffer.current = [event, ...buffer.current].slice(0, MAX_EVENTS)
          setHeld((n) => Math.min(n + 1, MAX_EVENTS))
          return
        }
        setEvents((list) => [event, ...list].slice(0, MAX_EVENTS))
      },
      {
        onState: (next, detail) => {
          setState(next)
          setDelay(detail.delayMs)
        },
      },
    )
    return () => stream.close()
  }, [])

  const togglePause = () => {
    const next = !paused
    pausedRef.current = next
    setPaused(next)
    if (!next) {
      const kept = buffer.current
      buffer.current = []
      setHeld(0)
      if (kept.length) setEvents((list) => [...kept, ...list].slice(0, MAX_EVENTS))
    }
  }

  const s = STATE[state]
  return (
    <Drawer onClose={onClose} width="max-w-xl" label="Platform feed">
      <OverlayHeader
        title="Platform feed"
        subtitle={
          <span className="font-mono text-caption">
            GET {api.eventsStreamUrl()}
          </span>
        }
        icon="pulse"
        actions={
          <span className="flex items-center gap-1.5 text-caption text-secondary" aria-live="polite">
            <StatusDot kind={s.dot} pulse={s.pulse} size="sm" />
            {s.label}
            {state === 'retrying' && delay > 0 ? ` in ${Math.round(delay / 1000)}s` : ''}
          </span>
        }
      />
      <div className="flex items-center justify-between gap-2 border-b border-stroke-light px-5 py-2 text-caption text-secondary">
        <span>
          {events.length} {events.length === 1 ? 'event' : 'events'}
          {paused && held > 0 ? ` · ${held} held` : ''}
        </span>
        <div className="flex items-center gap-1">
          <Button size="sm" variant="ghost" icon={paused ? 'play' : 'pause'} onClick={togglePause} aria-pressed={paused}>
            {paused ? 'Resume' : 'Pause'}
          </Button>
          <Button size="sm" variant="ghost" icon="trash" onClick={() => setEvents([])} disabled={events.length === 0}>
            Clear
          </Button>
        </div>
      </div>
      <OverlayBody padded={false}>
        {events.length === 0 ? (
          <EmptyState icon="pulse" title="Waiting for events" message="Every job and resource event the platform publishes appears here as it happens." />
        ) : (
          <ol className="divide-y divide-stroke-light" aria-label="Events">
            {events.map((e) => {
              const key = `${e.seq}-${e.id}`
              const expanded = open === key
              return (
                <li key={key} className="px-5 py-2.5">
                  <button type="button" onClick={() => setOpen(expanded ? null : key)} aria-expanded={expanded} className="flex w-full items-start gap-3 rounded-md text-left">
                    <span className="mt-0.5 w-10 shrink-0 font-mono text-caption2 text-tertiary">#{e.seq}</span>
                    <span className="min-w-0 flex-1">
                      <span className="flex flex-wrap items-center gap-x-2 gap-y-1">
                        <Tag size="sm" mono outline>
                          {e.type}
                        </Tag>
                        {e.aggregateId && <span className="truncate font-mono text-caption text-primary">{e.aggregateId}</span>}
                        {e.actor && <span className="text-caption text-secondary">by {e.actor}</span>}
                      </span>
                      {e.context?.wfrn && (
                        <span className="mt-1 block">
                          <WfrnText wfrn={e.context.wfrn} max={44} copy={false} />
                        </span>
                      )}
                    </span>
                    <span className="flex shrink-0 items-center gap-1.5 text-caption text-tertiary">
                      <time dateTime={e.at} title={e.at}>
                        {relativeTime(e.at)}
                      </time>
                      <Icon name="chevron-down" size={12} className={cx('transition-transform duration-150', expanded && 'rotate-180')} />
                    </span>
                  </button>
                  {expanded && (
                    <div className="mt-2 pl-13">
                      <JsonView value={e} defaultExpanded={2} rawToggle maxHeight="max-h-72" />
                    </div>
                  )}
                </li>
              )
            })}
          </ol>
        )}
      </OverlayBody>
    </Drawer>
  )
}
