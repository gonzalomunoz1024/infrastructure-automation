import { useState } from 'react'
import { Icon, JsonView, StatusDot, Tag, cx, shortDate, type DotKind, type Tone } from '@iaf/ui'
import type { EvidenceEntry, EvidenceKind } from '../lib/types'
import { When } from './common'

/*
 * The evidence of one run as a vertical timeline: what kind of entry, when, who, one line of
 * what it says, and the data and hashes one click away. A broken chain is marked from the entry
 * where the verification failed.
 */

const KIND: Record<EvidenceKind, { tone: Tone; label: string }> = {
  requested: { tone: 'accent', label: 'requested' },
  preview: { tone: 'neutral', label: 'preview' },
  policy: { tone: 'neutral', label: 'policy' },
  change: { tone: 'neutral', label: 'change' },
  approval: { tone: 'warning', label: 'approval' },
  step: { tone: 'neutral', label: 'step' },
  changed: { tone: 'accent', label: 'changed' },
  closed: { tone: 'neutral', label: 'closed' },
  decision: { tone: 'healthy', label: 'decision' },
}

const str = (v: unknown): string | undefined => (typeof v === 'string' ? v : typeof v === 'number' || typeof v === 'boolean' ? String(v) : undefined)

/** One plain line per kind, from what the entry data usually carries. */
export function summarizeEntry(e: EvidenceEntry): string {
  const d = e.data ?? {}
  switch (e.kind) {
    case 'requested':
      return [`Run requested${e.actor ? ` by ${e.actor}` : ''}`, str(d.channel) && `via ${str(d.channel)}`, str(d.workflow) && `for ${str(d.workflow)}${str(d.version) ? ` ${str(d.version)}` : ''}`].filter(Boolean).join(' ')
    case 'preview': {
      const steps = Array.isArray(d.steps) ? d.steps.length : str(d.steps)
      const affected = Array.isArray(d.affectedResources) ? d.affectedResources.length : str(d.affected)
      return [`Preview computed`, steps !== undefined && `${steps} steps`, affected !== undefined && `${affected} resources affected`].filter(Boolean).join(' · ')
    }
    case 'policy': {
      const policy = d.policy as Record<string, unknown> | undefined
      const decision = str(d.decision) ?? str(policy?.decision)
      return decision ? `Policy decided: ${decision}${str(d.engine) ? ` (${str(d.engine)})` : ''}` : 'Policy evaluated'
    }
    case 'change':
      return `Change ${str(d.id) ?? str(d.ticket) ?? ''} ${str(d.state) ?? str(d.status) ?? 'opened'}`.trim()
    case 'approval': {
      const status = str(d.status) ?? str(d.decision)
      const by = str(d.decidedBy) ?? e.actor
      return status && status !== 'pending' ? `Approval ${status}${by ? ` by ${by}` : ''}` : `Approval requested${str(d.group) ? ` from ${str(d.group)}` : ''}`
    }
    case 'step':
      return `Step ${str(d.name) ?? str(d.step) ?? str(d.id) ?? ''} ${str(d.status) ?? ''}`.replace(/\s+/g, ' ').trim()
    case 'changed':
      return `Changed ${str(d.type) ?? 'resource'} ${str(d.id) ?? str(d.resource) ?? ''}${str(d.effect) ? ` (${str(d.effect)})` : ''}`.trim()
    case 'closed':
      return `Run closed${str(d.status) ? `: ${str(d.status)}` : ''}`
    case 'decision':
      return `${str(d.decision) ?? str(d.outcome) ?? 'Decision recorded'}${e.actor ? ` by ${e.actor}` : ''}`
    default:
      return e.kind
  }
}

function short(hash: string | undefined, n = 10): string {
  if (!hash) return '—'
  return hash.length > n + 2 ? `${hash.slice(0, n)}…` : hash
}

export interface EvidenceTimelineProps {
  entries: EvidenceEntry[]
  /** the seq where verification found the chain broken */
  brokenAt?: number
  className?: string
}

export function EvidenceTimeline({ entries, brokenAt, className }: EvidenceTimelineProps) {
  const [open, setOpen] = useState<Set<number>>(() => new Set())
  const toggle = (seq: number) =>
    setOpen((s) => {
      const next = new Set(s)
      if (next.has(seq)) next.delete(seq)
      else next.add(seq)
      return next
    })

  if (entries.length === 0) return <p className={cx('text-caption text-tertiary', className)}>No evidence recorded yet.</p>

  return (
    <ol className={cx('relative', className)} aria-label="Evidence entries">
      {entries.map((e, i) => {
        const kind = KIND[e.kind] ?? { tone: 'neutral' as Tone, label: e.kind }
        const broken = brokenAt !== undefined && e.seq >= brokenAt
        const dot: DotKind = broken ? 'critical' : e.kind === 'approval' ? 'warning' : 'healthy'
        const isOpen = open.has(e.seq)
        const last = i === entries.length - 1
        return (
          <li key={e.seq} className="relative flex gap-3">
            <div className="flex w-4 shrink-0 flex-col items-center">
              <div className="flex h-9 items-center">
                <StatusDot kind={dot} />
              </div>
              {!last && <div className={cx('w-px flex-1', broken ? 'bg-critical/40' : 'bg-stroke-light')} aria-hidden="true" />}
            </div>
            <div className={cx('min-w-0 flex-1', !last && 'pb-1')}>
              <button type="button" onClick={() => toggle(e.seq)} aria-expanded={isOpen} className="flex w-full items-start gap-3 rounded-md px-2 py-1.5 text-left transition-colors hover:bg-surface-secondary">
                <div className="min-w-0 flex-1">
                  <div className="flex flex-wrap items-center gap-x-2 gap-y-1">
                    <Tag size="sm" tone={kind.tone}>
                      {kind.label}
                    </Tag>
                    <span className="text-sm text-primary">{summarizeEntry(e)}</span>
                    {broken && (
                      <Tag size="sm" tone="critical" icon="alert-triangle">
                        chain broken
                      </Tag>
                    )}
                  </div>
                  <div className="mt-0.5 flex flex-wrap items-center gap-x-3 text-caption text-tertiary">
                    <span className="tabular-nums">#{e.seq}</span>
                    <span title={e.at}>
                      {shortDate(e.at)} · <When at={e.at} />
                    </span>
                    {e.actor && <span>{e.actor}</span>}
                    <span className="font-mono">{short(e.hash)}</span>
                  </div>
                </div>
                <Icon name="chevron-down" size={14} className={cx('mt-1 shrink-0 text-tertiary transition-transform duration-150', isOpen && 'rotate-180')} />
              </button>
              {isOpen && (
                <div className="mb-2 mt-1 space-y-3 rounded-md border border-stroke-light p-3">
                  <dl className="grid grid-cols-1 gap-2 text-caption sm:grid-cols-2">
                    <div className="min-w-0">
                      <dt className="text-tertiary">Hash</dt>
                      <dd className="break-all font-mono text-primary">{e.hash}</dd>
                    </div>
                    <div className="min-w-0">
                      <dt className="text-tertiary">Previous</dt>
                      <dd className="break-all font-mono text-secondary">{e.previous || '— (first entry)'}</dd>
                    </div>
                  </dl>
                  <JsonView value={e.data} rawToggle={false} defaultExpanded={2} maxHeight="max-h-72" />
                </div>
              )}
            </div>
          </li>
        )
      })}
    </ol>
  )
}
