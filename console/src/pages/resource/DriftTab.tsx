import { useEffect, useId, useRef, useState, type ReactNode } from 'react'
import { Button, EmptyState, SkeletonRows } from '@iaf/ui'
import { DotTag, Section, When, formatValue, xConsoleOf } from '@iaf/console-kit'
import { useConsole } from '../../app/console'
import { Link, pathFor } from '../../app/router'
import { ExplainedError, FailureNote } from '../../app/commands'
import { bff } from '../../lib/bff'
import type { ConsoleType } from '../../lib/descriptors'
import { RESOLUTION_WORDS, SEVERITY_TONE, SEVERITY_WORDS, decisionKeys, driftInWords, fieldLabel, fieldNameOf, resolutionLine } from '../../lib/drift'
import { usePoll } from '../../lib/poll'
import type { Decision } from '../../lib/permissions'
import { invalidate, useQuery } from '../../lib/queries'
import type { Finding } from '../../lib/types'
import { AcceptDialog, DecisionButton, IgnoreDialog, LiveLine, RevertStatus, decisionsOf, reasonsOf, useRevert } from './DriftDecisions'
import type { TabProps } from './tabs'

/*
 * The resource page's Drift tab (WP 5.2): the open findings, each with what differs and the
 * three decisions, then the findings closed in the last 30 days. Revert is one click; the card
 * says Reverting with a link to its request, follows it while it runs, and the finding closes
 * when the request succeeded (a failed one is said, and offered again). Accept and Ignore ask
 * once in a small dialog. What a decision did is said in a live region, and the page's own
 * facts (drifted or in sync, the attributes) are read again once a finding closes.
 */

/** How often the tab reads the findings while a revert runs. */
const FOLLOW_MS = 2_000
/** Closed findings shown before "Show all". */
const HISTORY_SHOWN = 5
/** A revert still to follow: the drift service closes a finding only once the revert succeeded and the next read agrees. */
const pending = (f: { status: string; revertStatus?: string | null }) => f.status === 'open' && (f.revertStatus === 'running' || f.revertStatus === 'succeeded')

/** The Drift tab: the open findings with their decisions, then the ones closed in the last 30 days. */
export function DriftTab({ type, wfrn, detail, viewError }: TabProps) {
  const { decide } = useConsole()
  const update = decide(type, 'update')
  const [live, setLive] = useState('')
  const list = useQuery(['console-drift', type.name, wfrn], () => bff.resources.drift(type.name, wfrn), { enabled: !!detail })
  const items = list.data?.items ?? (detail?.finding ? [detail.finding] : [])
  const open = items.filter((f) => f.status === 'open')
  const closed = items.filter((f) => f.status === 'closed').sort((a, b) => (b.closedAt ?? '').localeCompare(a.closedAt ?? ''))
  const running = open.some(pending)
  usePoll(running, () => void list.refetch(), FOLLOW_MS)

  // a revert that was running and has ended: say how, and read the page's facts again
  const following = useRef(new Set<string>())
  useEffect(() => {
    const now = list.data?.items ?? []
    for (const id of Array.from(following.current)) {
      const f = now.find((x) => x.findingId === id)
      if (f && pending(f)) continue
      following.current.delete(id)
      if (!f || f.status === 'closed') setLive('The revert finished: the provider reports what was asked for again, and the finding is closed.')
      else if (f.revertStatus === 'failed') setLive('The revert failed: the provider still reports these values. You can revert again.')
      for (const key of decisionKeys(type)) if (!key.startsWith('console-drift')) invalidate(key)
    }
    for (const f of now) if (pending(f)) following.current.add(f.findingId)
  }, [list.data, type])

  if (!detail) return viewError ? <ExplainedError error={viewError} what="Load the drift of this resource" compact /> : <SkeletonRows n={2} />
  const checked = detail.observedAt
  const description = (
    <>
      The drift service compares what was asked for with what the provider reports, at least hourly; it never changes anything by itself.
      {checked && (
        <>
          {' '}
          Last checked <When at={checked} />.
        </>
      )}
    </>
  )
  let body: ReactNode
  if (list.loading && !list.data && !detail.finding) {
    body = <SkeletonRows n={2} rowClassName="h-24" />
  } else if (list.error && !list.data) {
    body = <ExplainedError error={list.error} what="Load the drift findings" onRetry={() => void list.refetch()} compact />
  } else if (open.length) {
    body = (
      <div className="space-y-4">
        {open.map((f) => (
          <FindingCard key={f.findingId} finding={f} type={type} name={detail.name} update={update} onLive={setLive} />
        ))}
      </div>
    )
  } else if (detail.observedAt || closed.length) {
    body = <EmptyState compact icon="check" iconClassName="text-healthy" title="In sync" message={closed.length ? 'The provider reports what was asked for; every difference found earlier has been settled.' : 'The provider reports what was asked for.'} />
  } else {
    body = <EmptyState compact icon="pulse" title="Not checked yet" message="The drift service has not compared this resource yet; its first check runs within the hour." />
  }
  return (
    <div className="space-y-8">
      <Section title="Drift" description={description}>
        {body}
        <LiveLine>{live}</LiveLine>
      </Section>
      {closed.length > 0 && <History findings={closed} type={type} />}
    </div>
  )
}

/** One open finding: what differs, how a revert stands, and the decisions this person may take. */
function FindingCard({ finding, type, name, update, onLive }: { finding: Finding; type: ConsoleType; name?: string; update: Decision; onLive: (line: string) => void }) {
  const props = type.schema.properties ?? {}
  const reasonId = useId()
  const reverting = useRevert(type)
  const [started, setStarted] = useState<string | null>(null)
  const [dialog, setDialog] = useState<'accept' | 'ignore' | null>(null)
  // a revert just started, until the findings read again say so
  const justStarted = !!started && finding.revertJobId !== started
  const shown: Finding = justStarted ? { ...finding, revertStatus: 'running', revertJobId: started, can: (finding.can ?? []).filter((v) => v === 'ignore') } : finding
  const decisions = decisionsOf(update, shown)
  const reasons = reasonsOf(decisions)
  const failed = shown.revertStatus === 'failed'
  const live = shown.fields.filter((f) => !f.ignored)

  const doRevert = async () => {
    const res = await reverting.revert(finding)
    if (res) {
      setStarted(res.jobId)
      onLive(`Reverting ${live.map((f) => fieldLabel(f.path, type)).join(', ')}: request ${res.jobId} started.`)
    }
  }

  return (
    <article className="rounded-lg border border-stroke-light" data-finding={finding.findingId} aria-label={`Finding: ${driftInWords(shown, type)}`}>
      <header className="flex flex-wrap items-center gap-x-3 gap-y-1 border-b border-stroke-light px-4 py-3">
        <DotTag tone={SEVERITY_TONE[finding.severity] ?? 'neutral'}>{SEVERITY_WORDS[finding.severity] ?? finding.severity}</DotTag>
        <h3 className="text-sm font-medium text-primary">{live.length === 1 ? 'One field differs from what was asked for' : `${live.length} fields differ from what was asked for`}</h3>
        <span className="text-caption text-tertiary">
          Found <When at={finding.openedAt} />
          {finding.observedAt && (
            <>
              , seen again <When at={finding.observedAt} />
            </>
          )}
        </span>
      </header>
      <div className="overflow-x-auto">
        <table className="w-full text-left text-sm">
          <caption className="sr-only">Fields that drifted</caption>
          <thead className="text-caption text-tertiary">
            <tr>
              <th scope="col" className="px-4 py-2 font-medium">
                Field
              </th>
              <th scope="col" className="px-4 py-2 font-medium">
                Asked for
              </th>
              <th scope="col" className="px-4 py-2 font-medium">
                Provider reports
              </th>
            </tr>
          </thead>
          <tbody className="divide-y divide-stroke-light">
            {shown.fields.map((f) => {
              const x = xConsoleOf(props[fieldNameOf(f.path).split('.')[0]])
              return (
                <tr key={f.path}>
                  <td className="px-4 py-2 text-primary">
                    {fieldLabel(f.path, type)}
                    {f.ignored && (
                      <DotTag tone="neutral" size="sm" className="ml-2">
                        Ignored
                      </DotTag>
                    )}
                  </td>
                  <td className="px-4 py-2 tabular-nums text-primary">{formatValue(f.expected, x)}</td>
                  <td className="px-4 py-2 tabular-nums text-primary">{formatValue(f.observed, x)}</td>
                </tr>
              )
            })}
          </tbody>
        </table>
      </div>
      {(shown.revertStatus === 'running' || failed) && (
        <div className="border-t border-stroke-light px-4 py-3">
          <RevertStatus finding={shown} />
        </div>
      )}
      <footer className="space-y-2 border-t border-stroke-light px-4 py-3">
        <div className="flex flex-wrap items-center gap-2">
          <DecisionButton decision={decisions.revert} verb="revert" variant="primary" icon="refresh" onClick={() => void doRevert()} loading={reverting.busy} describedBy={reasonId}>
            {failed ? 'Revert again' : 'Revert to what was asked for'}
          </DecisionButton>
          <DecisionButton decision={decisions.accept} verb="accept" onClick={() => setDialog('accept')} describedBy={reasonId}>
            Accept the provider’s values
          </DecisionButton>
          <DecisionButton decision={decisions.ignore} verb="ignore" variant="ghost" onClick={() => setDialog('ignore')} describedBy={reasonId}>
            Ignore these fields
          </DecisionButton>
        </div>
        {reasons.length > 0 && (
          <p id={reasonId} className="text-caption text-secondary">
            {reasons.join(' ')}
          </p>
        )}
        <FailureNote failure={reverting.failure} onDismiss={reverting.clear} compact />
      </footer>
      {dialog === 'accept' && (
        <AcceptDialog
          finding={shown}
          type={type}
          name={name}
          onClose={() => setDialog(null)}
          onDone={(line) => {
            setDialog(null)
            onLive(line)
          }}
        />
      )}
      {dialog === 'ignore' && (
        <IgnoreDialog
          finding={shown}
          type={type}
          name={name}
          onClose={() => setDialog(null)}
          onDone={(line) => {
            setDialog(null)
            onLive(line)
          }}
        />
      )}
    </article>
  )
}

/** The findings closed in the last 30 days, newest first: how each ended, who decided, when and why. */
function History({ findings, type }: { findings: Finding[]; type: ConsoleType }) {
  const [all, setAll] = useState(false)
  const shown = all ? findings : findings.slice(0, HISTORY_SHOWN)
  return (
    <Section title="Closed findings" description="Findings of this resource settled in the last 30 days: reverted, accepted, ignored, or settled by themselves.">
      <ul className="divide-y divide-stroke-light rounded-lg border border-stroke-light" aria-label="Closed findings">
        {shown.map((f) => {
          const words = f.resolution ? RESOLUTION_WORDS[f.resolution] : undefined
          return (
            <li key={f.findingId} className="space-y-1 px-4 py-3" data-closed-finding={f.findingId}>
              <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
                <DotTag tone={words?.tone ?? 'neutral'}>{words?.label ?? 'Closed'}</DotTag>
                <span className="text-sm text-primary">{driftInWords(f, type)}</span>
              </div>
              <p className="text-caption text-secondary">
                {resolutionLine(f)}
                {f.closedAt && (
                  <>
                    , <When at={f.closedAt} />
                  </>
                )}
                {f.revertJobId && f.resolution === 'reverted' && (
                  <>
                    {' '}
                    through{' '}
                    <Link to={pathFor('request', { id: f.revertJobId })} className="text-accent hover:underline">
                      its request
                    </Link>
                  </>
                )}
                .
              </p>
              {f.reason && <p className="text-caption text-secondary">“{f.reason}”</p>}
            </li>
          )
        })}
      </ul>
      {findings.length > HISTORY_SHOWN && (
        <Button size="sm" variant="ghost" className="mt-3" onClick={() => setAll((v) => !v)}>
          {all ? 'Show fewer' : `Show all ${findings.length}`}
        </Button>
      )}
    </Section>
  )
}
