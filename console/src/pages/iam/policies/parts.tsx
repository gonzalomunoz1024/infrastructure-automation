import { DotTag } from '@iaf/console-kit'
import { Link, pathFor } from '../../../app/router'
import { statementWords, type DraftPolicy } from '../../../lib/policies'
import type { IamPolicy, IamStatement } from '../../../lib/types'
import { useTypeTitle } from './editor'

/*
 * What the policy, guardrail, role and simulator pages share: a document's statements read out,
 * a policy named by its name with a link, and the hand-over of an unsaved document from the
 * editor to the simulator.
 */

export function EffectTag({ effect }: { effect: IamStatement['effect'] }) {
  return (
    <DotTag size="sm" tone={effect === 'allow' ? 'healthy' : 'critical'} uppercase>
      {effect}
    </DotTag>
  )
}

/** A document's statements, each with its effect, its parts and what it says in words. */
export function StatementList({ statements, label }: { statements: readonly IamStatement[]; label: string }) {
  const typeTitle = useTypeTitle()
  return (
    <ol className="space-y-3" aria-label={label}>
      {statements.map((s, i) => (
        <li key={`${s.sid ?? ''}-${i}`} className="rounded-lg border border-stroke-light p-3" data-statement-row={s.sid ?? i}>
          <div className="flex flex-wrap items-center gap-2">
            <span className="text-sm font-semibold text-primary">Statement {i + 1}</span>
            {s.sid && <span className="font-mono text-caption text-secondary">{s.sid}</span>}
            <EffectTag effect={s.effect} />
          </div>
          <p className="mt-1 break-words text-sm text-primary">{statementWords(s, typeTitle)}</p>
          <dl className="mt-2 grid grid-cols-1 gap-x-4 gap-y-1 text-caption sm:grid-cols-[7rem_1fr]">
            <dt className="text-tertiary">Actions</dt>
            <dd className="break-all font-mono text-secondary">{s.actions.join(', ')}</dd>
            {!!s.notActions?.length && (
              <>
                <dt className="text-tertiary">Except</dt>
                <dd className="break-all font-mono text-secondary">{s.notActions.join(', ')}</dd>
              </>
            )}
            <dt className="text-tertiary">Resources</dt>
            <dd className="min-w-0 font-mono text-secondary">
              {s.resources.map((r) => (
                <span key={r} className="block break-all">
                  {r}
                </span>
              ))}
            </dd>
            {s.conditions && Object.keys(s.conditions).length > 0 && (
              <>
                <dt className="text-tertiary">Conditions</dt>
                <dd className="break-all font-mono text-secondary">{Object.entries(s.conditions).map(([k, v]) => `${k} = ${Array.isArray(v) ? v.join(' | ') : String(v)}`).join('; ')}</dd>
              </>
            )}
          </dl>
        </li>
      ))}
    </ol>
  )
}

/** A policy by its name, linked; an id nobody knows stays as it is. */
export function PolicyName({ id, policies }: { id: string; policies: readonly IamPolicy[] | undefined }) {
  const p = policies?.find((x) => x.policyId === id || x.name === id)
  if (!p) return <span className="font-mono">{id}</span>
  return (
    <Link to={pathFor('iam-policy', { id: p.policyId })} className="font-medium text-accent hover:underline">
      {p.name}
    </Link>
  )
}

const DRAFT_KEY = 'console-simulator-draft'

/** Keeps an unsaved document for the simulator to read once; answers the token its link carries. */
export function handDraft(draft: DraftPolicy): string {
  const token = Math.random().toString(36).slice(2, 10)
  try {
    sessionStorage.setItem(`${DRAFT_KEY}:${token}`, JSON.stringify(draft))
  } catch {
    /* storage unavailable: the simulator starts without it */
  }
  return token
}

/** The document the editor handed over, read once. */
export function takeDraft(token: string | undefined): DraftPolicy | null {
  if (!token) return null
  try {
    const raw = sessionStorage.getItem(`${DRAFT_KEY}:${token}`)
    if (!raw) return null
    const v = JSON.parse(raw) as DraftPolicy
    return v && Array.isArray(v.statements) ? v : null
  } catch {
    return null
  }
}
