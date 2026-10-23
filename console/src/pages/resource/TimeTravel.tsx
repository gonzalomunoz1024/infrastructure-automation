import { useMemo, useState } from 'react'
import { Button, EmptyState, Input, SkeletonRows } from '@iaf/ui'
import { DotTag, Section, formatValueText, titleCase, xConsoleOf } from '@iaf/console-kit'
import { useConsole } from '../../app/console'
import { ExplainedError } from '../../app/commands'
import { ActionButton } from '../../app/Refusal'
import { Link, navigate, pathFor, useRoute } from '../../app/router'
import { singularOf, type ConsoleType } from '../../lib/descriptors'
import { useQuery } from '../../lib/queries'
import { EVENT_TYPE_WORDS, stateApi } from '../../lib/state'
import { diffSnapshots, replayAt, stopsOf, type DiffRow, type Snapshot } from '../../lib/timeTravel'
import type { ConsoleResourceDetail, Schema } from '../../lib/types'
import { EDIT_NOT_OFFERED, editWaitNote } from './EditDrawer'
import { HeldButton } from './HeldButton'

/*
 * Time travel on a resource (WP 9.8): its events replayed to any point, picked on a slider that
 * stops at each event or by a date, shown as it was then (what was asked for and what the
 * provider reported) beside how it is now, with what changed between the two points in words.
 * "Revert to this" opens Edit configuration filled with the values of then (`?action=edit&revert=`),
 * so the person reviews the change, its preview and its approvals like any other edit.
 */

const DAY = 24 * 3600_000

const fmt = (iso: string) => new Date(iso).toLocaleString(undefined, { day: 'numeric', month: 'short', year: 'numeric', hour: '2-digit', minute: '2-digit' })

/** The end of a day the date input names (`2026-10-22`), in UTC, never later than now. */
export function endOfDay(day: string, now: number): number {
  const start = Date.parse(`${day}T00:00:00Z`)
  return Number.isNaN(start) ? now : Math.min(now, start + DAY - 1)
}

const SECTION_WORDS: Record<DiffRow['section'], string> = { state: 'Status', desired: 'Asked for', observed: 'Provider reported', tags: 'Tag' }

function valueText(v: unknown, schema: Schema | undefined): string {
  if (v === undefined || v === null || v === '') return 'not set'
  return formatValueText(v, xConsoleOf(schema))
}

export function TimeTravel({ type, wfrn, detail }: { type: ConsoleType; wfrn: string; detail: ConsoleResourceDetail | undefined }) {
  const { decide } = useConsole()
  const route = useRoute()
  const noun = singularOf(type.title).toLowerCase()
  const events = useQuery(['console-activity', type.name, wfrn, 'events'], () => stateApi.eventsOf(type.name, wfrn))
  const [now] = useState(() => Date.now())
  const stops = useMemo(() => stopsOf(events.data ?? []), [events.data])
  const atParam = route.query.at ? Date.parse(route.query.at) : NaN
  const at = Number.isNaN(atParam) ? undefined : atParam
  const then = useMemo(() => (events.data ? replayAt(events.data, at ?? now) : null), [events.data, at, now])
  const current = useMemo(() => (events.data ? replayAt(events.data, now) : null), [events.data, now])
  const index = then ? stops.findIndex((s) => s.version === then.version) : stops.length - 1

  const go = (instant: number | undefined) => navigate(pathFor('resource', route.params, { ...route.query, at: instant === undefined ? undefined : new Date(instant).toISOString() }, { accountId: route.accountId }), { replace: true })
  const toStop = (i: number) => {
    const stop = stops[i]
    if (!stop) return
    // the last stop is now: the point leaves the address
    go(i === stops.length - 1 ? undefined : Date.parse(stop.at))
  }

  if (events.error) return <ExplainedError error={events.error} what={`Replay the history of this ${noun}`} onRetry={() => void events.refetch()} compact />
  if (events.loading) return <SkeletonRows n={3} />
  if (stops.length === 0) return <EmptyState compact icon="history" title="No history to replay" message={`The state service has not recorded any event for this ${noun} yet.`} />

  const props = type.schema.properties ?? {}
  const rows = then && current ? diffSnapshots(then, current) : []
  const stop = stops[Math.max(0, index)]
  const day = new Date(at ?? now).toISOString().slice(0, 10)
  const valueTextOf = `Version ${stop.version} of ${stops.length}: ${EVENT_TYPE_WORDS[stop.type]}, ${fmt(stop.at)}`

  return (
    <Section title="Time travel" description={`This ${noun} as it was at any point of its history, replayed from its events, and how it differs from now.`}>
      <div className="space-y-4 rounded-xl border border-stroke-light p-4">
        <div className="flex flex-wrap items-end gap-3">
          <label className="min-w-[14rem] flex-1">
            <span className="mb-1.5 block text-caption font-medium text-secondary">Point in its history</span>
            <input
              type="range"
              min={0}
              max={stops.length - 1}
              step={1}
              value={Math.max(0, index)}
              onChange={(e) => toStop(Number(e.target.value))}
              aria-valuetext={valueTextOf}
              className="w-full accent-[var(--color-accent)]"
              data-testid="time-slider"
            />
          </label>
          <label>
            <span className="mb-1.5 block text-caption font-medium text-secondary">Or a day (UTC)</span>
            <Input type="date" size="sm" value={day} max={new Date(now).toISOString().slice(0, 10)} onChange={(e) => e.target.value && go(endOfDay(e.target.value, now))} aria-label="Day to go back to" />
          </label>
          <div className="flex flex-wrap gap-2">
            <Button size="sm" variant="secondary" onClick={() => go(endOfDay(new Date(now - DAY).toISOString().slice(0, 10), now))}>
              Yesterday
            </Button>
            <Button size="sm" variant="secondary" onClick={() => go(endOfDay(new Date(now - 7 * DAY).toISOString().slice(0, 10), now))}>
              A week ago
            </Button>
            {at !== undefined && (
              <Button size="sm" variant="ghost" onClick={() => go(undefined)}>
                Back to now
              </Button>
            )}
          </div>
        </div>
        <p className="text-sm text-primary" data-testid="time-point">
          {then ? (
            <>
              As it was {at !== undefined ? <>at the end of {fmt(new Date(at).toISOString())}</> : 'now'}: version {then.version}, after <span className="font-medium">{EVENT_TYPE_WORDS[stops[index]?.type ?? 'ResourceRequested'].toLowerCase()}</span> on {fmt(then.at)}.
            </>
          ) : (
            <>It did not exist yet on that day: its first event is of {fmt(stops[0].at)}.</>
          )}
        </p>

        {then && current && <SnapshotTable then={then} current={current} props={props} past={at !== undefined} />}

        {then && at !== undefined && (
          <div>
            <h3 className="mb-2 text-subheadline font-semibold text-primary">What changed since</h3>
            {rows.length === 0 ? (
              <p className="text-caption text-tertiary">Nothing: it is as it was then.</p>
            ) : (
              <ul className="space-y-1 text-sm" aria-label="What changed since then">
                {rows.map((r) => (
                  <li key={`${r.section}${r.path}`} className="text-primary">
                    <span className="text-secondary">{SECTION_WORDS[r.section]}</span>
                    {r.path && <> {props[r.path.slice(1)]?.title ?? titleCase(r.path.slice(1))}</>}: {valueText(r.before, props[r.path.slice(1)])} → <span className="font-medium">{valueText(r.after, props[r.path.slice(1)])}</span>
                  </li>
                ))}
              </ul>
            )}
          </div>
        )}

        {then && at !== undefined && <RevertButton type={type} detail={detail} at={at} changed={rows.some((r) => r.section === 'desired')} noun={noun} decide={decide} />}
      </div>
    </Section>
  )
}

function SnapshotTable({ then, current, props, past }: { then: Snapshot; current: Snapshot; props: Record<string, Schema>; past: boolean }) {
  const fields = [...new Set([...Object.keys(props).filter((k) => k in then.attributes || k in current.attributes), ...Object.keys(then.attributes), ...Object.keys(current.attributes)])]
  return (
    <div className="overflow-x-auto rounded-lg border border-stroke-light" tabIndex={0} role="region" aria-label={past ? 'Then and now' : 'Now'}>
      <table className="w-full text-left text-sm" data-testid="time-table">
        <caption className="sr-only">{past ? 'What was asked for then, what the provider reported then, and what is asked for now' : 'What is asked for now and what the provider reports'}</caption>
        <thead className="text-caption text-tertiary">
          <tr>
            <th scope="col" className="px-4 py-2 font-medium">
              Field
            </th>
            <th scope="col" className="px-4 py-2 font-medium">
              {past ? 'Asked for then' : 'Asked for'}
            </th>
            <th scope="col" className="px-4 py-2 font-medium">
              {past ? 'Provider reported then' : 'Provider reports'}
            </th>
            {past && (
              <th scope="col" className="px-4 py-2 font-medium">
                Asked for now
              </th>
            )}
          </tr>
        </thead>
        <tbody className="divide-y divide-stroke-light">
          <tr>
            <th scope="row" className="px-4 py-2 font-normal text-primary">
              Status
            </th>
            <td className="px-4 py-2 text-primary">{then.state}</td>
            <td className="px-4 py-2 text-secondary">{then.observedStatus ?? 'not read yet'}</td>
            {past && <td className="px-4 py-2 text-primary">{current.state}</td>}
          </tr>
          {fields.map((f) => {
            const differs = past && JSON.stringify(then.attributes[f]) !== JSON.stringify(current.attributes[f])
            const observed = then.observed?.[f]
            return (
              <tr key={f} data-field={f} data-changed={differs || undefined}>
                <th scope="row" className="px-4 py-2 font-normal text-primary">
                  {props[f]?.title ?? titleCase(f)}
                </th>
                <td className="px-4 py-2 tabular-nums text-primary">
                  <span className="flex flex-wrap items-center gap-2">
                    {valueText(then.attributes[f], props[f])}
                    {differs && (
                      <DotTag tone="warning" size="sm">
                        Changed since
                      </DotTag>
                    )}
                  </span>
                </td>
                <td className="px-4 py-2 tabular-nums text-secondary">{observed === undefined ? '' : valueText(observed, props[f])}</td>
                {past && <td className="px-4 py-2 tabular-nums text-primary">{valueText(current.attributes[f], props[f])}</td>}
              </tr>
            )
          })}
        </tbody>
      </table>
    </div>
  )
}

function RevertButton({ type, detail, at, changed, noun, decide }: { type: ConsoleType; detail: ConsoleResourceDetail | undefined; at: number; changed: boolean; noun: string; decide: ReturnType<typeof useConsole>['decide'] }) {
  const route = useRoute()
  if (!type.update) return <HeldButton reason={EDIT_NOT_OFFERED} variant="primary" icon="history" verb="update">Revert to this</HeldButton>
  const decision = decide(type, 'update')
  if (!decision.allowed) {
    return (
      <ActionButton decision={decision} what={`Bring this ${noun} back`} verb="update" variant="primary" icon="history">
        Revert to this
      </ActionButton>
    )
  }
  const wait = editWaitNote(detail, noun) ?? (changed ? undefined : `Nothing to bring back: what is asked for is as it was then`)
  if (wait) return <HeldButton reason={wait} variant="primary" icon="history" verb="update">Revert to this</HeldButton>
  return (
    <div className="flex flex-wrap items-center gap-3">
      <Link
        to={pathFor('resource', route.params, { ...route.query, action: 'edit', revert: new Date(at).toISOString() }, { accountId: route.accountId })}
        replace
        data-verb="revert"
        className="inline-flex select-none items-center justify-center gap-1.5 whitespace-nowrap rounded-md bg-accent px-3 py-1.5 text-caption font-medium text-white transition-all duration-150 hover:opacity-90 active:scale-[0.97]"
      >
        Revert to this
      </Link>
      <span className="text-caption text-secondary">Opens Edit configuration with these values, for you to review before anything runs.</span>
    </div>
  )
}
