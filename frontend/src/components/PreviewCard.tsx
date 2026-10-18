import { Card, Icon, StatusDot, Tag, cx, formatNumber, plural, type DotKind, type Tone } from '@iaf/ui'
import { pathFor } from '../app/router'
import type { PolicyDecision, Preview } from '../lib/types'
import { EffectTag, TextLink } from './common'

/*
 * The blast radius before a run: what each step will do, which resources it touches, what it
 * costs per month, and what policy says. The server judged all of it; this only renders.
 */

const DECISION: Record<PolicyDecision, { tone: Tone; dot: DotKind; label: string; detail: string }> = {
  allow: { tone: 'healthy', dot: 'healthy', label: 'Allowed', detail: 'Policy lets this run start.' },
  'needs-approval': { tone: 'warning', dot: 'warning', label: 'Needs approval', detail: 'The run will pause until someone approves it.' },
  deny: { tone: 'critical', dot: 'critical', label: 'Denied', detail: 'Policy will refuse this run.' },
}

/** The note under a step the dry run did not take. */
export const SKIPPED_NOTE = 'not taken in the dry run'

/** `input.image` and `step get-vm` read "via …"; `built from img-77` already says how. */
export function viaLabel(via: string): string {
  return via.startsWith('built from') ? via : `via ${via}`
}

function ruleDot(result: string): DotKind {
  const r = result.toLowerCase()
  if (r === 'allow' || r === 'pass' || r === 'passed' || r === 'ok' || r === 'true') return 'healthy'
  if (r === 'deny' || r === 'fail' || r === 'failed' || r === 'false') return 'critical'
  if (r.includes('approv') || r === 'warn' || r === 'warning') return 'warning'
  return 'neutral'
}

export interface PreviewCardProps {
  preview: Preview
  /** fewer words, for side panels */
  compact?: boolean
  title?: string
  className?: string
}

export function PreviewCard({ preview, compact, title = 'Preview', className }: PreviewCardProps) {
  const decision = DECISION[preview.policy.decision] ?? DECISION.allow
  const counts = preview.steps.reduce(
    (acc, s) => {
      acc[s.effect] = (acc[s.effect] ?? 0) + 1
      return acc
    },
    {} as Record<string, number>,
  )
  const summary = (['create', 'change', 'delete', 'read'] as const).filter((e) => counts[e]).map((e) => `${counts[e]} ${e}`)

  return (
    <Card
      title={title}
      subtitle={`${preview.workflow} · ${preview.version}${summary.length ? ` · ${summary.join(', ')}` : ''}`}
      actions={
        <Tag tone={decision.tone} dot>
          {decision.label}
        </Tag>
      }
      className={className}
    >
      <div className={cx('grid gap-5', compact ? 'grid-cols-1' : 'md:grid-cols-[1.3fr_1fr]')}>
        <div className="min-w-0 space-y-4">
          <div>
            <h4 className="mb-1.5 text-caption font-medium uppercase tracking-wide text-tertiary">Steps</h4>
            {preview.steps.length === 0 ? (
              <p className="text-caption text-tertiary">No steps touch anything.</p>
            ) : (
              <ol className="divide-y divide-stroke-light rounded-md border border-stroke-light">
                {preview.steps.map((s) => (
                  <li key={s.id} className={cx('flex items-start gap-3 px-3 py-2', s.skipped && 'opacity-60')} data-skipped={s.skipped ? 'true' : undefined}>
                    <EffectTag effect={s.effect} />
                    <div className="min-w-0 flex-1">
                      <div className={cx('truncate text-sm', s.skipped ? 'text-secondary' : 'text-primary')}>{s.name}</div>
                      {s.detail && <div className="text-caption text-secondary">{s.detail}</div>}
                      {s.skipped && <div className="text-caption italic text-tertiary">{SKIPPED_NOTE}</div>}
                    </div>
                  </li>
                ))}
              </ol>
            )}
          </div>

          <div>
            <h4 className="mb-1.5 text-caption font-medium uppercase tracking-wide text-tertiary">Affected resources</h4>
            {preview.affectedResources.length === 0 ? (
              <p className="text-caption text-tertiary">No registered resource is affected.</p>
            ) : (
              <ul className="flex flex-wrap gap-1.5">
                {preview.affectedResources.map((r) => (
                  <li key={`${r.type}-${r.id}`}>
                    <TextLink to={pathFor('resource', { id: r.id })} className="inline-flex items-center gap-1 rounded-full border border-stroke-light px-2 py-0.5 text-caption">
                      <Icon name="resource" size={11} />
                      <span className="text-primary">{r.name || r.id}</span>
                      <span className="text-tertiary">{r.type}</span>
                      {r.via && <span className="text-tertiary">· {viaLabel(r.via)}</span>}
                    </TextLink>
                  </li>
                ))}
              </ul>
            )}
          </div>
        </div>

        <div className="min-w-0 space-y-4">
          {preview.cost && (
            <div className="rounded-md bg-surface-secondary p-3">
              <div className="text-caption text-tertiary">Monthly cost</div>
              <div className="mt-0.5 text-title3 font-semibold tabular-nums text-primary">
                {formatNumber(preview.cost.monthly, { digits: 2 })} <span className="text-footnote font-normal text-secondary">{preview.cost.currency}</span>
              </div>
              {preview.cost.detail && (
                <div className="mt-1 text-caption text-secondary" title="Where the number comes from">
                  <span className="text-tertiary">Priced from: </span>
                  {preview.cost.detail}
                </div>
              )}
            </div>
          )}

          <div className="rounded-md border border-stroke-light p-3">
            <div className="flex items-center justify-between gap-2">
              <StatusDot kind={decision.dot} label={decision.label} />
              <Tag size="sm" outline mono>
                {preview.policy.engine}
              </Tag>
            </div>
            <p className="mt-1 text-caption text-secondary">{decision.detail}</p>
            {preview.policy.rules.length > 0 && (
              <ul className="mt-2 space-y-1.5 border-t border-stroke-light pt-2" aria-label={plural(preview.policy.rules.length, 'policy rule')}>
                {preview.policy.rules.map((r) => (
                  <li key={r.id} className="flex items-start gap-2 text-caption">
                    <StatusDot kind={ruleDot(r.result)} size="sm" className="mt-1.5" />
                    <div className="min-w-0">
                      <span className="font-mono text-secondary">{r.id}</span>
                      <span className="ml-1.5 text-primary">{r.message}</span>
                    </div>
                  </li>
                ))}
              </ul>
            )}
          </div>
        </div>
      </div>
    </Card>
  )
}
