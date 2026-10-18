/**
 * The assistant beside the canvas: describe a change in words, the assistant drafts the
 * workflow (checked and dry-run on the server), the draft shows as a diff against what is on the
 * canvas, and "Apply to canvas" replaces the document in one undoable step. An answer that changes
 * nothing (an explanation) is a message with nothing to apply. A new session starts whenever the
 * canvas changed since the last one, so the assistant always works from what is on screen.
 */
import { useEffect, useRef, useState, type KeyboardEvent } from 'react'
import { Button, cx, DiffView, diffStats, Icon, IconButton, parseUnifiedDiff, Spinner, Tag, Textarea, useToast } from '@iaf/ui'
import type { WorkflowDocument } from '@iaf/workflow-checks'
import { api, errorMessage } from '../../lib/api'
import type { AssistantDraft, AssistantEvent } from '../../lib/types'
import { unifiedDiff } from '../../components/diff'
import { useDesignerSelector, useDesignerStore } from '../model/store'

type Entry = { kind: 'user'; text: string } | { kind: 'assistant'; text: string } | { kind: 'status'; text: string } | { kind: 'error'; text: string } | { kind: 'draft'; draft: AssistantDraft; applied: boolean }

const SUGGESTIONS = ['Add an approval before the destructive steps', 'Tell #ops when the job fails', 'Retry the provider calls three times with backoff', 'Explain what this workflow does']

function textOf(data: unknown): string {
  if (typeof data === 'string') return data
  if (data && typeof data === 'object') {
    const o = data as Record<string, unknown>
    for (const k of ['text', 'message', 'status']) if (typeof o[k] === 'string') return o[k] as string
  }
  return ''
}

function isDraft(data: unknown): data is AssistantDraft {
  return !!data && typeof data === 'object' && typeof (data as { workflowYaml?: unknown }).workflowYaml === 'string'
}

/** The draft as the canvas would hold it: parsed by the backend and rendered back, so it compares line for line with the canvas. */
export type Proposal = { definition: WorkflowDocument; yaml: string } | { error: string }

/** What a draft would change on the canvas. */
export type Comparison = { kind: 'pending' } | { kind: 'invalid'; message: string } | { kind: 'same' } | { kind: 'diff'; diff: string; additions: number; deletions: number }

/** The canvas YAML against the draft's, both as the backend renders them; pending until both are known. */
export function compareWithCanvas(canvasYaml: string | null, proposal: Proposal | null): Comparison {
  if (proposal && 'error' in proposal) return { kind: 'invalid', message: proposal.error }
  if (canvasYaml === null || proposal === null) return { kind: 'pending' }
  const diff = unifiedDiff(canvasYaml, proposal.yaml, { oldPath: 'canvas', newPath: 'draft' })
  if (!diff) return { kind: 'same' }
  const stats = diffStats(parseUnifiedDiff(diff))
  return { kind: 'diff', diff, additions: stats.additions, deletions: stats.deletions }
}

/** The draft's YAML through `POST /workflows:parse` and `POST /workflows:render`, once per draft. */
function useProposal(yaml: string): Proposal | null {
  const [state, setState] = useState<{ yaml: string; proposal: Proposal } | null>(null)
  useEffect(() => {
    if (!yaml) return
    let live = true
    const load = async (): Promise<Proposal> => {
      const parsed = await api.parseWorkflow({ yaml })
      if (!parsed.definition) return { error: parsed.problems[0]?.message ?? 'The draft does not parse' }
      const rendered = await api.renderWorkflow({ definition: parsed.definition })
      return { definition: parsed.definition, yaml: rendered.yaml }
    }
    load().then(
      (proposal) => live && setState({ yaml, proposal }),
      (e: unknown) => live && setState({ yaml, proposal: { error: errorMessage(e) } }),
    )
    return () => {
      live = false
    }
  }, [yaml])
  return state && state.yaml === yaml ? state.proposal : null
}

/** The canvas as YAML (`POST /workflows:render`) for its current version, a moment after the last edit; null meanwhile. */
function useCanvasYaml(enabled: boolean): string | null {
  const store = useDesignerStore()
  const version = useDesignerSelector((s) => s.version)
  const [state, setState] = useState<{ version: number; yaml: string } | null>(null)
  useEffect(() => {
    if (!enabled) return
    let live = true
    const timer = setTimeout(() => {
      api.renderWorkflow({ definition: store.getState().doc }).then(
        (r) => live && setState({ version, yaml: r.yaml }),
        () => {},
      )
    }, 250)
    return () => {
      live = false
      clearTimeout(timer)
    }
  }, [enabled, version, store])
  return state && state.version === version ? state.yaml : null
}

function DraftCard({ draft, applied, canvasYaml, echoed, onApply, onAnswer, busy }: { draft: AssistantDraft; applied: boolean; canvasYaml: string | null; echoed: boolean; onApply: (definition: WorkflowDocument) => void; onAnswer: (text: string) => void; busy: boolean }) {
  const proposal = useProposal(draft.workflowYaml)
  const comparison = applied ? null : compareWithCanvas(canvasYaml, proposal)
  const valid = draft.validation?.valid ?? false
  if (comparison?.kind === 'same' && !(draft.questions && draft.questions.length > 0)) {
    // an answer that changes nothing, like an explanation: its prose is the message, with nothing to apply
    return (
      <div className="mr-8 space-y-1" data-testid="assistant-answer">
        {draft.summary && !echoed && <div className="whitespace-pre-wrap rounded-lg bg-surface-secondary px-3 py-2 text-footnote text-primary">{draft.summary}</div>}
        <p className="flex items-center gap-1.5 text-caption text-tertiary">
          <Icon name="check" size={12} />
          No change to the canvas
        </p>
      </div>
    )
  }
  return (
    <div className="rounded-lg border border-stroke-light bg-surface p-3 shadow-sm" data-testid="assistant-draft">
      <div className="mb-1.5 flex items-center gap-2">
        <Icon name="sparkles" size={14} className="text-accent" />
        <span className="text-footnote font-semibold text-primary">Draft</span>
        <span className="flex-1" />
        <Tag size="sm" tone={valid ? 'healthy' : 'warning'}>
          {valid ? 'checks pass' : `${draft.validation?.errors?.length ?? 0} problems`}
        </Tag>
        {draft.dryRun?.passed !== undefined && (
          <Tag size="sm" tone={draft.dryRun.passed ? 'healthy' : 'critical'}>
            {draft.dryRun.passed ? 'dry run passed' : 'dry run failed'}
          </Tag>
        )}
      </div>
      {draft.summary && <p className="text-caption text-secondary">{draft.summary}</p>}
      {draft.changes && draft.changes.length > 0 && (
        <ul className="mt-2 list-disc space-y-0.5 pl-4 text-caption text-primary">
          {draft.changes.map((c, i) => (
            <li key={i}>{c}</li>
          ))}
        </ul>
      )}
      {draft.assumptions && draft.assumptions.length > 0 && (
        <div className="mt-2">
          <p className="text-caption2 font-semibold uppercase tracking-wide text-tertiary">Assumptions</p>
          <ul className="mt-0.5 list-disc space-y-0.5 pl-4 text-caption text-secondary">
            {draft.assumptions.map((a, i) => (
              <li key={i}>{a}</li>
            ))}
          </ul>
        </div>
      )}
      {draft.missing && draft.missing.length > 0 && (
        <div className="mt-2 rounded-sm bg-warning-tint px-2 py-1.5 text-caption text-warning">
          {draft.missing.map((m, i) => (
            <p key={i}>
              Missing {m.kind} <span className="font-mono">{m.name}</span>: {m.why}
            </p>
          ))}
        </div>
      )}
      {draft.validation?.errors && draft.validation.errors.length > 0 && (
        <ul className="mt-2 space-y-0.5 text-caption text-critical">
          {draft.validation.errors.slice(0, 5).map((e, i) => (
            <li key={i}>{e}</li>
          ))}
        </ul>
      )}
      {draft.questions && draft.questions.length > 0 && (
        <div className="mt-2 space-y-2">
          {draft.questions.map((q, i) => (
            <div key={i}>
              <p className="text-caption font-medium text-primary">{q.question}</p>
              {q.options && (
                <div className="mt-1 flex flex-wrap gap-1">
                  {q.options.map((o) => (
                    <Button key={o} size="sm" variant="capsule" disabled={busy} onClick={() => onAnswer(o)}>
                      {o}
                    </Button>
                  ))}
                </div>
              )}
            </div>
          ))}
        </div>
      )}
      {draft.workflowYaml && comparison && (
        <div className="mt-3" data-testid="draft-diff">
          {comparison.kind === 'pending' && (
            <p className="flex items-center gap-1.5 text-caption text-tertiary">
              <Spinner size={11} /> Comparing with the canvas…
            </p>
          )}
          {comparison.kind === 'invalid' && (
            <p className="text-caption text-critical" role="alert">
              The draft does not parse: {comparison.message}
            </p>
          )}
          {comparison.kind === 'same' && <p className="text-caption text-tertiary">No change to the canvas.</p>}
          {comparison.kind === 'diff' && (
            <>
              <p className="mb-1 text-caption2 font-semibold uppercase tracking-wide text-tertiary">
                Changes to the canvas <span className="font-medium normal-case tracking-normal text-accent">+{comparison.additions}</span> <span className="font-medium normal-case tracking-normal text-critical">−{comparison.deletions}</span>
              </p>
              <DiffView diff={comparison.diff} toolbar={false} maxHeight="max-h-64" />
            </>
          )}
        </div>
      )}
      {draft.workflowYaml && (applied || comparison?.kind === 'diff') && (
        <div className="mt-3 flex justify-end">
          <Button size="sm" variant={applied ? 'ghost' : 'primary'} icon={applied ? 'check' : undefined} disabled={applied || !proposal || 'error' in proposal} onClick={() => proposal && 'definition' in proposal && onApply(proposal.definition)}>
            {applied ? 'Applied' : 'Apply to canvas'}
          </Button>
        </div>
      )}
    </div>
  )
}

export function AssistantPanel({ onClose }: { onClose: () => void }) {
  const store = useDesignerStore()
  const toast = useToast()
  const name = useDesignerSelector((s) => s.name)
  const isNew = useDesignerSelector((s) => s.isNew)
  const [entries, setEntries] = useState<Entry[]>([])
  const [text, setText] = useState('')
  const [busy, setBusy] = useState(false)
  const session = useRef<{ id: string; version: number } | null>(null)
  const abort = useRef<AbortController | null>(null)
  const listRef = useRef<HTMLDivElement>(null)
  const canvasYaml = useCanvasYaml(entries.some((e) => e.kind === 'draft' && !e.applied))

  useEffect(() => () => abort.current?.abort(), [])
  useEffect(() => {
    listRef.current?.scrollTo({ top: listRef.current.scrollHeight })
  }, [entries])

  const push = (e: Entry) => setEntries((list) => [...list, e])

  const sessionId = async (): Promise<string> => {
    const st = store.getState()
    if (session.current && session.current.version === st.version) return session.current.id
    const { yaml } = await api.renderWorkflow({ definition: st.doc })
    const created = await api.createAssistantSession({ mode: isNew ? 'create' : 'edit', workflow: name ?? undefined, draftYaml: yaml })
    session.current = { id: created.sessionId, version: st.version }
    return created.sessionId
  }

  const send = async (message: string) => {
    const t = message.trim()
    if (!t || busy) return
    setText('')
    push({ kind: 'user', text: t })
    setBusy(true)
    const ctrl = new AbortController()
    abort.current = ctrl
    try {
      const id = await sessionId()
      await api.sendAssistantMessage(
        id,
        t,
        (ev: AssistantEvent) => {
          if (ev.type === 'draft' && isDraft(ev.data)) push({ kind: 'draft', draft: ev.data, applied: false })
          else if (ev.type === 'message') {
            const m = textOf(ev.data)
            if (m) push({ kind: 'assistant', text: m })
          } else if (ev.type === 'status') {
            const m = textOf(ev.data)
            if (m) push({ kind: 'status', text: m })
          } else if (ev.type === 'done') {
            const d = (ev.data ?? {}) as { status?: unknown; error?: unknown }
            if (d.status === 'failed') push({ kind: 'error', text: typeof d.error === 'string' && d.error ? d.error : 'The assistant stopped before it finished' })
          }
        },
        { signal: ctrl.signal },
      )
    } catch (e) {
      if (!ctrl.signal.aborted) push({ kind: 'error', text: errorMessage(e) })
    } finally {
      setBusy(false)
      abort.current = null
    }
  }

  const apply = (index: number, definition: WorkflowDocument) => {
    store.setDocument(definition)
    session.current = session.current ? { ...session.current, version: store.getState().version } : null
    setEntries((list) => list.map((e, i) => (i === index && e.kind === 'draft' ? { ...e, applied: true } : e)))
    toast.toast({ title: 'Draft applied', message: 'Undo brings the previous version back', tone: 'healthy' })
  }

  const onKeyDown = (e: KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === 'Enter' && !e.shiftKey && !e.metaKey && !e.ctrlKey) {
      e.preventDefault()
      void send(text)
    }
  }

  return (
    <aside aria-label="Assistant" data-testid="assistant-panel" className="flex w-[380px] max-w-full shrink-0 flex-col border-l border-stroke-light bg-surface animate-slide-in-right">
      <div className="flex items-center gap-2 border-b border-stroke-light px-4 py-3">
        <Icon name="sparkles" size={16} className="text-accent" />
        <h2 className="flex-1 text-subheadline font-semibold text-primary">Assistant</h2>
        <IconButton label="Close the assistant" size="sm" onClick={onClose}>
          <Icon name="close" size={15} />
        </IconButton>
      </div>
      <div ref={listRef} className="min-h-0 flex-1 space-y-3 overflow-y-auto px-4 py-4" aria-live="polite">
        {entries.length === 0 && (
          <div className="space-y-3">
            <p className="text-footnote text-secondary">Describe a change and the assistant drafts it against the registry: real providers, real actions, checked before you see it.</p>
            <div className="flex flex-col gap-1.5">
              {SUGGESTIONS.map((s) => (
                <Button key={s} size="sm" variant="capsule" onClick={() => void send(s)} className="justify-start whitespace-normal text-left">
                  {s}
                </Button>
              ))}
            </div>
          </div>
        )}
        {entries.map((e, i) => {
          switch (e.kind) {
            case 'user':
              return (
                <div key={i} className="ml-8 rounded-lg bg-accent px-3 py-2 text-footnote text-white">
                  {e.text}
                </div>
              )
            case 'assistant':
              return (
                <div key={i} className="mr-8 whitespace-pre-wrap rounded-lg bg-surface-secondary px-3 py-2 text-footnote text-primary" data-testid="assistant-message">
                  {e.text}
                </div>
              )
            case 'status':
              return (
                <p key={i} className="flex items-center gap-1.5 text-caption text-tertiary">
                  <Icon name="info" size={12} />
                  {e.text}
                </p>
              )
            case 'error':
              return (
                <p key={i} className="rounded-md bg-critical-tint px-3 py-2 text-caption text-critical" role="alert">
                  {e.text}
                </p>
              )
            case 'draft': {
              // the summary of an answer that changes nothing is not repeated when a message already said it
              const summary = (e.draft.summary ?? '').trim()
              const echoed = !!summary && entries.some((o) => o.kind === 'assistant' && o.text.trim() === summary)
              return <DraftCard key={i} draft={e.draft} applied={e.applied} canvasYaml={canvasYaml} echoed={echoed} busy={busy} onApply={(definition) => apply(i, definition)} onAnswer={(t) => void send(t)} />
            }
          }
        })}
        {busy && (
          <p className="flex items-center gap-2 text-caption text-tertiary">
            <Spinner size={12} /> Working on it…
          </p>
        )}
      </div>
      <div className="border-t border-stroke-light p-3">
        <div className={cx('rounded-md', busy && 'opacity-70')}>
          <Textarea rows={3} value={text} onChange={(e) => setText(e.target.value)} onKeyDown={onKeyDown} placeholder="Ask for a change…" aria-label="Message the assistant" disabled={busy} />
        </div>
        <div className="mt-2 flex items-center justify-between">
          <span className="text-caption2 text-tertiary">Enter sends, Shift+Enter for a new line</span>
          {busy ? (
            <Button size="sm" variant="ghost" onClick={() => abort.current?.abort()}>
              Stop
            </Button>
          ) : (
            <Button size="sm" variant="primary" icon="send" disabled={!text.trim()} onClick={() => void send(text)}>
              Send
            </Button>
          )}
        </div>
      </div>
    </aside>
  )
}
