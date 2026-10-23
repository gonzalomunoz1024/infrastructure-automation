import { useEffect, useRef, useState } from 'react'
import { Button, Drawer, EmptyState, Icon, JsonView, OverlayBody, OverlayHeader, StatusDot, Tag, cx, relativeTime, type DotKind } from '@iaf/ui'
import { WfrnText } from '@iaf/console-kit'
import { bff } from '../lib/bff'
import { openLiveStream } from '../lib/events'
import type { StreamState } from '../lib/sse'
import type { LiveEvent } from '../lib/types'

/*
 * The live feed, for developers: the BFF's GET /console/v1/events over SSE, every update of the
 * session's account as it happens, newest first, with the connection's state and a pause for
 * reading. Resumes after the last event seen when the stream drops. The BFF names each event
 * after its type, so the stream is opened with the whole list of types (lib/events.ts).
 */

const MAX_EVENTS = 200

/** A live event with the cursor its SSE `id:` carried, and a key of its own for the list. */
interface FeedEntry {
  cursor: string
  key: string
  event: LiveEvent
}

const STATE: Record<StreamState, { dot: DotKind; pulse?: boolean; label: string }> = {
  connecting: { dot: 'neutral', pulse: true, label: 'Connecting' },
  open: { dot: 'healthy', pulse: true, label: 'Live' },
  retrying: { dot: 'warning', pulse: true, label: 'Reconnecting' },
  closed: { dot: 'neutral', label: 'Closed' },
}

export function PlatformFeed({ onClose }: { onClose: () => void }) {
  const [events, setEvents] = useState<FeedEntry[]>([])
  const [state, setState] = useState<StreamState>('connecting')
  const [delay, setDelay] = useState(0)
  const [paused, setPaused] = useState(false)
  const [held, setHeld] = useState(0)
  const [open, setOpen] = useState<string | null>(null)
  const pausedRef = useRef(false)
  const buffer = useRef<FeedEntry[]>([])
  const counter = useRef(0)

  useEffect(() => {
    const stream = openLiveStream<LiveEvent>(
      bff.eventsUrl(),
      (event, raw) => {
        if (!event || typeof event !== 'object' || typeof event.type !== 'string') return
        counter.current += 1
        const entry: FeedEntry = { cursor: raw.lastEventId, key: `${raw.lastEventId}-${counter.current}`, event }
        if (pausedRef.current) {
          buffer.current = [entry, ...buffer.current].slice(0, MAX_EVENTS)
          setHeld((n) => Math.min(n + 1, MAX_EVENTS))
          return
        }
        setEvents((list) => [entry, ...list].slice(0, MAX_EVENTS))
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
    <Drawer onClose={onClose} width="max-w-xl" label="Live feed">
      <OverlayHeader
        title="Live feed"
        subtitle={
          <span className="font-mono text-caption">
            GET {bff.eventsUrl()}
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
          <EmptyState icon="pulse" title="Waiting for events" message="Every change to the requests and resources of this account appears here as it happens." />
        ) : (
          <ol className="divide-y divide-stroke-light" aria-label="Events">
            {events.map(({ cursor, key, event: e }) => {
              const expanded = open === key
              const subject = typeof e.data?.jobId === 'string' ? e.data.jobId : undefined
              return (
                <li key={key} className="px-5 py-2.5">
                  <button type="button" onClick={() => setOpen(expanded ? null : key)} aria-expanded={expanded} className="flex w-full items-start gap-3 rounded-md text-left">
                    <span className="mt-0.5 w-10 shrink-0 font-mono text-caption2 text-tertiary">{cursor ? `#${cursor}` : ''}</span>
                    <span className="min-w-0 flex-1">
                      <span className="flex flex-wrap items-center gap-x-2 gap-y-1">
                        <Tag size="sm" mono outline>
                          {e.type}
                        </Tag>
                        {subject && <span className="truncate font-mono text-caption text-primary">{subject}</span>}
                      </span>
                      {e.wfrn && (
                        <span className="mt-1 block">
                          <WfrnText wfrn={e.wfrn} max={44} copy={false} />
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
