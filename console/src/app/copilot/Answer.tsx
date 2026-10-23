import { useId, type ReactNode } from 'react'
import { Button, Icon, Spinner, cx } from '@iaf/ui'
import { DotTag, When } from '@iaf/console-kit'
import type { AssistantProposal, AssistantTurn, ResourceExplanation, SearchInterpretation } from '../../lib/assistant'
import { findType, idOfWfrn } from '../../lib/descriptors'
import { ALLOWED } from '../../lib/permissions'
import { pathOfWfrn, useConsole } from '../console'
import { ActionButton } from '../Refusal'
import { Link, navigate, type Route } from '../router'
import { chipText, chipsOf, fieldTitle, filledOf, listableOf, proposalPath, type Chip } from './proposals'
import { canRetry, type ThreadState, type TurnNote } from './thread'

/*
 * The conversation in the panel: the person's words on the right; the copilot's on the left, as
 * short paragraphs of plain text (never markup), then what it proposes, each a card that opens
 * something for the person to check. A filter card shows the filter chip by chip, as the list
 * will; when the reading behind it was not sure (`confidence: low`) the card says so, names the
 * words it left out, and waits for the person to apply it. A create or action card names what it
 * filled and what the form still needs. An explanation shows its facts and sections, costs as
 * estimates from list prices, never invoices.
 */

const CARD = 'space-y-2.5 rounded-lg border border-stroke-light bg-surface p-3'
const LINK_BUTTON = 'inline-flex items-center gap-1.5 rounded-md bg-surface-secondary px-3 py-1.5 text-caption font-medium text-primary transition-colors hover:bg-stroke-light'

/** "$77.00": the currency's own sign, two decimals. */
export function money(amount: number, currency = 'USD'): string {
  try {
    return new Intl.NumberFormat('en-US', { style: 'currency', currency, minimumFractionDigits: 2, maximumFractionDigits: 2 }).format(amount)
  } catch {
    return `${amount.toFixed(2)} ${currency}`
  }
}

/** 'a', 'a and b', 'a, b and c'. */
export function joinAnd(items: readonly string[]): string {
  return items.length < 2 ? (items[0] ?? '') : `${items.slice(0, -1).join(', ')} and ${items[items.length - 1]}`
}

export interface ConversationProps {
  state: ThreadState
  route: Route
  onRetry: () => void
  /** after a proposal or a link opened something (the phone's panel closes) */
  onOpen?: () => void
}

export function Conversation({ state, route, onRetry, onOpen }: ConversationProps) {
  const { turns, unsent, sending, waitingFor, problem, notes } = state
  const retryable = canRetry(state)
  const last = turns.length - 1
  // the turn is on its way, or the copilot has not shown it yet
  const awaiting = sending || (!!waitingFor && !turns.some((t) => t.turnId === waitingFor))
  const retryProblem = problem && (problem.kind === 'rate' || problem.kind === 'unreachable' || problem.kind === 'unread') ? { onClick: onRetry, disabled: !retryable } : undefined
  return (
    <ol aria-label="Conversation" className="space-y-5">
      {turns.map((turn, i) =>
        turn.role === 'person' ? (
          <PersonTurn key={turn.turnId} text={turn.text ?? ''} />
        ) : (
          <CopilotTurn key={turn.turnId} turn={turn} note={notes[turn.turnId]} route={route} onRetry={i === last && retryable && !problem ? onRetry : undefined} onOpen={onOpen} />
        ),
      )}
      {unsent && <PersonTurn text={unsent.text} />}
      {awaiting && (
        <CopilotRow>
          <Thinking />
        </CopilotRow>
      )}
      {problem && (
        <CopilotRow>
          <Trouble message={problem.message} retry={retryProblem} />
        </CopilotRow>
      )}
    </ol>
  )
}

function PersonTurn({ text }: { text: string }) {
  return (
    <li className="flex scroll-mt-4 justify-end" data-turn="person">
      <p className="max-w-[85%] whitespace-pre-wrap break-words rounded-2xl rounded-br-md bg-accent-tint px-3 py-2 text-sm text-primary">
        <span className="sr-only">You: </span>
        {text}
      </p>
    </li>
  )
}

function CopilotRow({ children }: { children: ReactNode }) {
  return (
    <li className="flex gap-2.5" data-turn="copilot">
      <span aria-hidden="true" className="mt-0.5 flex size-6 shrink-0 items-center justify-center rounded-full bg-surface-secondary text-accent">
        <Icon name="sparkles" size={13} />
      </span>
      <div className="min-w-0 flex-1 space-y-3">
        <span className="sr-only">Copilot: </span>
        {children}
      </div>
    </li>
  )
}

function CopilotTurn({ turn, note, route, onRetry, onOpen }: { turn: AssistantTurn; note?: TurnNote; route: Route; onRetry?: () => void; onOpen?: () => void }) {
  if (turn.status === 'thinking') {
    return (
      <CopilotRow>
        <Thinking />
      </CopilotRow>
    )
  }
  if (turn.status === 'failed') {
    return (
      <CopilotRow>
        <Trouble message={turn.error?.message ?? 'The copilot could not answer.'} retry={onRetry ? { onClick: onRetry } : undefined} />
      </CopilotRow>
    )
  }
  const proposals = turn.proposals ?? []
  return (
    <CopilotRow>
      {turn.text && <Paragraphs text={turn.text} />}
      {turn.explanation && <ExplanationCard explanation={turn.explanation} name={note?.name} onOpen={onOpen} />}
      {proposals.length > 0 && (
        <ul aria-label="Proposals" className="space-y-2">
          {proposals.map((p, i) => (
            <li key={`${p.kind}-${i}`}>
              <ProposalCard proposal={p} reading={note?.reading} route={route} onOpen={onOpen} />
            </li>
          ))}
        </ul>
      )}
    </CopilotRow>
  )
}

/** Plain text in short paragraphs: a blank line between two, a line break kept within one. */
function Paragraphs({ text, className }: { text: string; className?: string }) {
  return (
    <div className={cx('space-y-2', className)}>
      {text
        .split(/\n\s*\n/)
        .map((p) => p.trim())
        .filter(Boolean)
        .map((p, i) => (
          <p key={i} className="whitespace-pre-line text-sm leading-relaxed text-primary">
            {p}
          </p>
        ))}
    </div>
  )
}

function Thinking() {
  return (
    <p className="flex items-center gap-2 text-sm text-secondary">
      <span aria-hidden="true" className="inline-flex">
        <Spinner size={13} />
      </span>
      Thinking…
    </p>
  )
}

function Trouble({ message, retry }: { message: string; retry?: { onClick: () => void; disabled?: boolean } }) {
  return (
    <div className="rounded-lg border border-stroke-light bg-surface px-3 py-2.5">
      <p className="flex items-start gap-2 text-sm text-primary">
        <Icon name="alert-triangle" size={15} className="mt-0.5 shrink-0 text-critical" />
        <span>{message}</span>
      </p>
      {retry && (
        <Button size="sm" variant="secondary" icon="refresh" className="mt-2.5" onClick={retry.onClick} disabled={retry.disabled}>
          Try again
        </Button>
      )}
    </div>
  )
}

function ChipList({ chips, label }: { chips: Chip[]; label: string }) {
  return (
    <ul aria-label={label} className="flex flex-wrap gap-1.5">
      {chips.map((c) => (
        <li key={chipText(c)} className="inline-flex max-w-full items-center gap-1 rounded-full bg-accent-tint px-2.5 py-0.5 text-caption text-primary">
          <span className={c.label === 'search' ? 'text-secondary' : 'font-medium'}>{c.label}</span>
          {c.op && <span className="text-secondary">{c.op}</span>}
          <span className="min-w-0 truncate">{c.value}</span>
        </li>
      ))}
    </ul>
  )
}

function Unavailable() {
  return <p className="text-caption text-secondary">The console cannot open this here.</p>
}

interface CardProps {
  proposal: AssistantProposal
  reading?: SearchInterpretation
  route: Route
  onOpen?: () => void
}

function ProposalCard(props: CardProps) {
  const { kind } = props.proposal
  if (kind === 'filter') return <FilterCard {...props} />
  if (kind === 'create' || kind === 'action') return <FormCard {...props} />
  return <OpenCard {...props} />
}

/**
 * A filter, chip by chip, and the list it opens. A reading the search indexer was not sure of
 * (`confidence: low`) is never applied on its own: the card says so first, names the words left
 * out, and its button applies it only when the person chooses to.
 */
function FilterCard({ proposal: p, reading, route, onOpen }: CardProps) {
  const { types, region } = useConsole()
  const type = p.type ? findType(types, p.service ?? '', p.type) : undefined
  const to = proposalPath(p, types, region.name)
  const { tokens, leftOut } = listableOf(p.filter, region.name)
  const chips = chipsOf(tokens, p.sort)
  const unsure = reading?.confidence === 'low'
  const unparsed = unsure ? (reading.unparsed ?? []) : []
  const here = route.name === 'type' && !!type && route.params.type === type.name && route.params.service === type.service
  return (
    <div className={CARD} data-proposal="filter" data-confidence={reading?.confidence}>
      {unsure && (
        <p className="flex items-center gap-1.5 text-caption font-semibold text-primary">
          <Icon name="alert-triangle" size={14} className="shrink-0 text-warning" />
          Not sure this is what you meant
        </p>
      )}
      <p className="text-sm font-medium text-primary">{p.label}</p>
      {chips.length > 0 ? <ChipList chips={chips} label="Filters it applies" /> : <p className="text-caption text-secondary">No filter: the whole list.</p>}
      {unparsed.length > 0 && <p className="text-caption text-secondary">Left out: {joinAnd(unparsed.map((w) => `“${w}”`))}.</p>}
      {leftOut.length > 0 && <p className="text-caption text-secondary">The list cannot filter on {joinAnd(leftOut)}, so it leaves that out.</p>}
      {to ? (
        <Link to={to} onClick={onOpen} className={LINK_BUTTON}>
          <Icon name="filter" size={13} />
          {unsure ? 'Apply this filter' : here ? 'Show on this list' : 'Open the list'}
        </Link>
      ) : (
        <Unavailable />
      )}
    </div>
  )
}

/** A create wizard or an action's drawer, filled with what the copilot could fill; nothing is sent from here. */
function FormCard({ proposal: p, onOpen }: CardProps) {
  const { types, decide, region } = useConsole()
  const type = p.type ? findType(types, p.service ?? '', p.type) : undefined
  const to = proposalPath(p, types, region.name)
  const creating = p.kind === 'create'
  const verb = creating ? 'create' : (p.verb ?? '')
  const schema = creating ? type?.create?.input : type?.actions.find((a) => a.verb === p.verb)?.input
  const filled = filledOf(schema, p.input)
  const missing = (p.missing ?? []).map((key) => fieldTitle(schema, key))
  return (
    <div className={CARD} data-proposal={p.kind}>
      <p className="text-sm font-medium text-primary">{p.label}</p>
      {filled.length > 0 && <ChipList chips={filled} label="Filled in" />}
      {missing.length > 0 && <p className="text-caption text-primary">Still needed: {joinAnd(missing)}.</p>}
      <p className="text-caption text-secondary">
        {creating ? 'Opens the form filled in. Nothing is created until you check it and submit it.' : 'Opens the action filled in. Nothing runs until you check its preview and submit it.'}
      </p>
      {to && type ? (
        <ActionButton
          decision={verb ? decide(type, verb) : ALLOWED}
          what={p.label}
          verb={verb}
          size="sm"
          icon={creating ? 'plus' : 'play'}
          onClick={() => {
            onOpen?.()
            navigate(to)
          }}
        >
          {creating ? 'Open the form' : 'Open the action'}
        </ActionButton>
      ) : (
        <Unavailable />
      )}
    </div>
  )
}

function OpenCard({ proposal: p, onOpen }: CardProps) {
  const { types, region } = useConsole()
  const to = proposalPath(p, types, region.name)
  return (
    <div className={CARD} data-proposal="open">
      <p className="text-sm font-medium text-primary">{p.label}</p>
      {to ? (
        <Link to={to} onClick={onOpen} className={LINK_BUTTON}>
          <Icon name="arrow-right" size={13} />
          Open the page
        </Link>
      ) : (
        <Unavailable />
      )}
    </div>
  )
}

/** What a resource is, what it costs, where it came from and what depends on it. */
function ExplanationCard({ explanation: x, name, onOpen }: { explanation: ResourceExplanation; name?: string; onOpen?: () => void }) {
  const { types } = useConsole()
  const id = useId()
  const page = pathOfWfrn(x.wfrn, types)
  const f = x.facts ?? {}
  const priced = typeof f.monthlyCost === 'number'
  const facts: { label: string; value: ReactNode }[] = []
  if (typeof f.monthlyCost === 'number') facts.push({ label: 'Monthly cost', value: money(f.monthlyCost, f.currency) })
  if (f.drift !== undefined) facts.push({ label: 'Drift', value: <DotTag tone={f.drift ? 'warning' : 'healthy'}>{f.drift ? 'Drifted' : 'In sync'}</DotTag> })
  if (f.createdAt || f.createdBy) facts.push({ label: 'Created', value: <By at={f.createdAt} who={f.createdBy} /> })
  if (f.lastChangeAt || f.lastChangeBy) facts.push({ label: 'Last change', value: <By at={f.lastChangeAt} who={f.lastChangeBy} /> })
  if (f.dependents !== undefined) facts.push({ label: 'Dependents', value: f.dependents === 0 ? 'None' : `${f.dependents} ${f.dependents === 1 ? 'resource' : 'resources'}` })
  return (
    <section aria-labelledby={id} className={cx(CARD, 'space-y-3')} data-explanation={x.wfrn}>
      <h3 id={id} className="flex min-w-0 items-center gap-2 text-sm font-semibold text-primary">
        <Icon name="resource" size={14} className="shrink-0 text-secondary" />
        <span className="truncate">{name ?? idOfWfrn(x.wfrn) ?? x.wfrn}</span>
      </h3>
      <p className="text-sm leading-relaxed text-primary">{x.summary}</p>
      {facts.length > 0 && (
        <dl className="grid grid-cols-2 gap-x-4 gap-y-3">
          {facts.map((fact) => (
            <div key={fact.label} className="min-w-0">
              <dt className="text-caption text-tertiary">{fact.label}</dt>
              <dd className="mt-0.5 text-sm text-primary">{fact.value}</dd>
            </div>
          ))}
        </dl>
      )}
      {(x.sections ?? []).map((s) => (
        <div key={s.title}>
          <h4 className="text-caption font-semibold text-primary">{s.title}</h4>
          <Paragraphs text={s.text} className="mt-1" />
        </div>
      ))}
      {priced && <p className="text-caption text-secondary">Costs are estimates from list prices, not invoices.</p>}
      {page && (
        <Link to={page} onClick={onOpen} className={LINK_BUTTON}>
          <Icon name="arrow-right" size={13} />
          Open its page
        </Link>
      )}
    </section>
  )
}

function By({ at, who }: { at?: string; who?: string }) {
  return (
    <>
      {at && <When at={at} />}
      {at && who ? ' by ' : who ? 'By ' : null}
      {who}
    </>
  )
}
