/**
 * The assistant beside the canvas: describe a change in words, the assistant drafts the
 * workflow (checked and dry-run on the server), and "Apply to canvas" replaces the document in
 * one undoable step. A new session starts whenever the canvas changed since the last one, so
 * the assistant always works from what is on screen.
 */
import { useEffect, useRef, useState, type KeyboardEvent } from 'react'
import { Button, cx, Icon, Spinner, Tag, Textarea, useToast } from '@iaf/ui'
import { api, errorMessage } from '../../lib/api'
import type { AssistantDraft, AssistantEvent } from '../../lib/types'
import { useDesignerSelector, useDesignerStore } from '../model/store'

type Entry = { kind: 'user'; text: string } | { kind: 'assistant'; text: string } | { kind: 'status'; text: string } | { kind: 'error'; text: string } | { kind: 'draft'; draft: AssistantDraft; applied: boolean }

const SUGGESTIONS = ['Add an approval before the destructive steps', 'Tell #ops when the job fails', 'Retry the provider calls three times with backoff', 'Explain what this workflow does']

function textOf(data: unknown): string {
  if (typeof data === 'string') return data
  if (data && typeof data === 'object') {
    const o = data as Record<string, unknown>
    for (const k of ['text', 'message', 'status', 'summary']) if (typeof o[k] === 'string') return o[k] as string
  }
  return ''
}

function isDraft(data: unknown): data is AssistantDraft {
  return !!data && typeof data === 'object' && typeof (data as { workflowYaml?: unknown }).workflowYaml === 'string'
}

function DraftCard({ draft, applied, onApply, onAnswer, busy }: { draft: AssistantDraft; applied: boolean; onApply: () => void; onAnswer: (text: string) => void; busy: boolean }) {
  const valid = draft.validation?.valid ?? false
  return (
    <div className="rounded-lg border border-stroke-light bg-surface p-3 shadow-sm">
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
                    <button key={o} type="button" disabled={busy} onClick={() => onAnswer(o)} className="rounded-full border border-stroke-light px-2 py-0.5 text-caption text-secondary hover:border-accent hover:text-accent">
                      {o}
                    </button>
                  ))}
                </div>
              )}
            </div>
          ))}
        </div>
      )}
      {draft.workflowYaml && (
        <div className="mt-3 flex justify-end">
          <Button size="sm" variant={applied ? 'ghost' : 'primary'} icon={applied ? 'check' : undefined} disabled={applied} onClick={onApply}>
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
          } else if (ev.type === 'status' || ev.type === 'validation') {
            const m = textOf(ev.data)
            if (m) push({ kind: 'status', text: m })
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

  const apply = async (index: number, draft: AssistantDraft) => {
    try {
      const parsed = await api.parseWorkflow({ yaml: draft.workflowYaml })
      if (!parsed.definition) {
        toast.error('The draft does not parse', parsed.problems[0]?.message ?? 'Ask the assistant to fix it')
        return
      }
      store.setDocument(parsed.definition)
      session.current = session.current ? { ...session.current, version: store.getState().version } : null
      setEntries((list) => list.map((e, i) => (i === index && e.kind === 'draft' ? { ...e, applied: true } : e)))
      toast.toast({ title: 'Draft applied', message: 'Undo brings the previous version back', tone: 'healthy' })
    } catch (e) {
      toast.error('Could not apply the draft', errorMessage(e))
    }
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
        <button type="button" onClick={onClose} aria-label="Close the assistant" className="flex h-7 w-7 items-center justify-center rounded-full text-tertiary hover:bg-surface-secondary hover:text-primary">
          <Icon name="close" size={15} />
        </button>
      </div>
      <div ref={listRef} className="min-h-0 flex-1 space-y-3 overflow-y-auto px-4 py-4" aria-live="polite">
        {entries.length === 0 && (
          <div className="space-y-3">
            <p className="text-footnote text-secondary">Describe a change and the assistant drafts it against the registry: real providers, real actions, checked before you see it.</p>
            <div className="flex flex-col gap-1.5">
              {SUGGESTIONS.map((s) => (
                <button key={s} type="button" onClick={() => void send(s)} className="rounded-md border border-stroke-light px-3 py-2 text-left text-caption text-secondary transition-colors hover:border-accent hover:text-primary">
                  {s}
                </button>
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
                <div key={i} className="mr-8 whitespace-pre-wrap rounded-lg bg-surface-secondary px-3 py-2 text-footnote text-primary">
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
            case 'draft':
              return <DraftCard key={i} draft={e.draft} applied={e.applied} busy={busy} onApply={() => void apply(i, e.draft)} onAnswer={(t) => void send(t)} />
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
