import { useEffect, useMemo, useRef, useState, type KeyboardEvent } from 'react'
import { Button, Card, CodeBlock, EmptyState, Icon, Input, Kbd, PageHeader, Skeleton, Spinner, StatusDot, Tag, Textarea, cx, modKeyLabel, nextId, useToast, type Tone } from '@iaf/ui'
import type { PageProps } from '../app/router'
import { navigate, pathFor } from '../app/router'
import { api, errorMessage } from '../lib/api'
import { invalidate, useQuery } from '../lib/queries'
import type { AssistantDraft, AssistantEvent, AssistantMode, AssistantSession, AssistantSessionRequest, EvidenceEntry, Problem, RegistryProvider } from '../lib/types'
import { useChangeRequestToast } from '../components/ChangeRequestToast'
import { ErrorState, Mono, TextLink, When } from '../components/common'
import { IssuesList, groupsOf } from '../designer/chrome/IssuesDrawer'
import { fixOf } from '../designer/model/fixes'
import { documentToGraph, findStepNode, type Graph } from '../designer/model/graph'

/*
 * The natural-language builder: a conversation at the left, the current draft at the right.
 * Every message streams status, message, draft, validation and done events over SSE (the SSE event
 * name is the type, the data the payload); the draft pane shows the latest draft with its
 * assumptions, open questions, the validation's problems on their steps (the designer's Issues
 * list) and the YAML, and hands it to the designer, saves it as a draft or submits it as a change
 * request. The session's id goes in the address (`?session=`), so a reload or a shared link goes on
 * with the session the backend kept (GET /assistant/sessions/{id}); the session's own events of the
 * platform stream (started, message, draft updated, ready) make its timeline.
 */

export interface Msg {
  id: string
  role: 'user' | 'assistant'
  text: string
  at: string
}

export interface Validation {
  valid: boolean
  problems?: Problem[]
  errors?: string[]
  rounds?: number
}

/** What the conversation shows: the exchange, what the assistant is doing now, the latest draft and its validation. */
export interface Conversation {
  messages: Msg[]
  status: string | null
  draft: AssistantDraft | null
  validation: Validation | null
  /** why the last message ended before the assistant finished (`done` with `failed`) */
  failure: string | null
}

export const NO_CONVERSATION: Conversation = { messages: [], status: null, draft: null, validation: null, failure: null }

const EXAMPLES = [
  'Restart a VM after checking no backup is running, with an approval for production.',
  'Create a database from a size and an environment, register it, and tell the channel.',
  'Patch one CVE on a list of servers in waves of 5, 25 and 100 percent, rebooting only when needed.',
  'Build a hardened image from a base OS, scan it, and register it when the scan is clean.',
]

const DRAFT_TONE: Record<AssistantDraft['status'], Tone> = { working: 'accent', ready: 'healthy', needs_input: 'warning', failed: 'critical' }
const DRAFT_LABEL: Record<AssistantDraft['status'], string> = { working: 'Working', ready: 'Ready', needs_input: 'Needs your input', failed: 'Failed' }

/** The text of a status or message payload (`{ status }`, `{ role, text }`). */
const asText = (data: unknown): string => {
  if (typeof data === 'string') return data
  if (data && typeof data === 'object') {
    const d = data as Record<string, unknown>
    for (const k of ['text', 'message', 'status']) if (typeof d[k] === 'string') return d[k] as string
  }
  return ''
}

const strings = (v: unknown): string[] | undefined => (Array.isArray(v) ? v.filter((s): s is string => typeof s === 'string') : undefined)

/** The draft event's payload: the structured output of devin/workflow-draft.schema.json. */
export function normalizeDraft(data: unknown): AssistantDraft | null {
  if (!data || typeof data !== 'object') return null
  const d = data as Record<string, unknown>
  const validation = d.validation && typeof d.validation === 'object' ? (d.validation as AssistantDraft['validation']) : { valid: false }
  const status = (['working', 'ready', 'needs_input', 'failed'].includes(String(d.status)) ? d.status : 'working') as AssistantDraft['status']
  return {
    status,
    summary: typeof d.summary === 'string' ? d.summary : '',
    workflowYaml: typeof d.workflowYaml === 'string' ? d.workflowYaml : '',
    changes: strings(d.changes),
    assumptions: strings(d.assumptions),
    questions: Array.isArray(d.questions) ? (d.questions as AssistantDraft['questions']) : undefined,
    missing: Array.isArray(d.missing) ? (d.missing as AssistantDraft['missing']) : undefined,
    validation: { valid: !!validation.valid, rounds: validation.rounds, errors: validation.errors },
    dryRun: d.dryRun as AssistantDraft['dryRun'],
  }
}

const validationOf = (d: AssistantDraft): Validation => ({ valid: d.validation.valid, errors: d.validation.errors, rounds: d.validation.rounds })

/** The conversation after one event of the stream, in the payload shapes the backend sends. */
export function applyEvent(c: Conversation, ev: AssistantEvent, at: string): Conversation {
  switch (ev.type) {
    case 'status': {
      const status = asText(ev.data)
      return status ? { ...c, status } : c
    }
    case 'message': {
      const text = asText(ev.data)
      return text ? { ...c, messages: [...c.messages, { id: nextId('msg'), role: 'assistant', text, at }] } : c
    }
    case 'draft': {
      // the problems on their steps come with the validation event that follows the draft
      const draft = normalizeDraft(ev.data)
      return draft ? { ...c, draft, validation: validationOf(draft) } : c
    }
    case 'validation': {
      const v = ev.data as { valid?: unknown; problems?: unknown } | null
      if (!v || typeof v !== 'object') return c
      return { ...c, validation: { ...(c.validation ?? {}), valid: !!v.valid, problems: Array.isArray(v.problems) ? (v.problems as Problem[]) : [] } }
    }
    case 'done': {
      const d = (ev.data ?? {}) as { status?: unknown; error?: unknown }
      const failure = d.status === 'failed' ? (typeof d.error === 'string' && d.error ? d.error : 'The assistant stopped before it finished.') : null
      return { ...c, status: null, failure }
    }
  }
}

/** The conversation of a session the backend kept, to go on with it: its turns, its latest draft. */
export function restoreConversation(view: AssistantSession): Conversation {
  const draft = view.draft ? normalizeDraft(view.draft) : null
  return {
    messages: view.history.map((t, i) => ({ id: `${view.sessionId}:${i}`, role: t.role === 'user' ? 'user' : 'assistant', text: t.text, at: t.at })),
    status: null,
    draft,
    validation: draft ? validationOf(draft) : null,
    failure: view.status === 'failed' ? 'The last message ended before the assistant finished.' : null,
  }
}

/** The body of POST /assistant/sessions: an edit of the named workflow from its current YAML, or a new workflow. */
export function sessionRequest(workflow: string | undefined, yaml: string | undefined): AssistantSessionRequest {
  return workflow ? { mode: 'edit', workflow, ...(yaml ? { draftYaml: yaml } : {}) } : { mode: 'create' }
}

export type TimelineItem = { seq: number; at: string; event: string; label: string; detail?: string; actor?: string }

const SESSION_EVENTS: Record<string, string> = { 'assistant.started': 'Session started', 'assistant.message': 'Message', 'assistant.draft-updated': 'Draft updated', 'assistant.ready': 'Draft ready' }
const clip = (text: string, max = 160): string => (text.length > max ? `${text.slice(0, max - 1)}…` : text)

/** The session's own entries of the platform stream (aggregate assistant-session), oldest first, as timeline lines. */
export function timelineOf(entries: readonly EvidenceEntry[], sessionId: string): TimelineItem[] {
  return entries
    .filter((e) => e.data.aggregate === 'assistant-session' && (e.data.aggregateId === sessionId || e.data.draftId === sessionId))
    .sort((a, b) => a.seq - b.seq)
    .map((e) => {
      const d = e.data
      const event = String(d.event ?? '')
      let detail: string | undefined
      if (event === 'assistant.started') detail = [d.mode, d.agent, d.workflow ? `editing ${String(d.workflow)}` : null].filter(Boolean).map(String).join(' · ')
      else if (event === 'assistant.message') detail = `${String(d.role ?? 'assistant')}: ${clip(String(d.text ?? ''))}`
      else if (event === 'assistant.draft-updated' || event === 'assistant.ready') {
        const errors = typeof d.errors === 'number' ? d.errors : Array.isArray(d.errors) ? d.errors.length : undefined
        detail = [d.status ? String(d.status) : null, d.valid === true ? 'valid' : d.valid === false ? 'not valid' : null, errors !== undefined ? `${errors} ${errors === 1 ? 'error' : 'errors'}` : null].filter(Boolean).join(' · ')
      }
      return { seq: e.seq, at: e.at, event, label: SESSION_EVENTS[event] ?? event.replace(/^assistant\./, ''), detail: detail || undefined, actor: e.actor }
    })
}

const EMPTY_GRAPH: Graph = { nodes: [], edges: [], problems: [] }

export type HandOff = 'open' | 'save' | 'submit'

export default function AssistantPage({ route }: PageProps) {
  const toast = useToast()
  const notify = useChangeRequestToast()
  const resumeId = route.query.session || null
  const [sessionId, setSessionId] = useState<string | null>(null)
  const [resumed, setResumed] = useState<{ mode: AssistantMode; workflow?: string } | null>(null)
  const [resumeError, setResumeError] = useState<{ id: string; error: unknown } | null>(null)
  const workflowName = route.query.workflow || resumed?.workflow || undefined
  const existing = useQuery(['workflow', workflowName ?? ''], () => api.workflow(workflowName!), { enabled: !!workflowName })
  const [conversation, setConversation] = useState<Conversation>(NO_CONVERSATION)
  const { messages, status, draft, validation, failure } = conversation
  const [streaming, setStreaming] = useState(false)
  const [text, setText] = useState('')
  const [saving, setSaving] = useState<HandOff | null>(null)
  const abortRef = useRef<AbortController | null>(null)
  const listRef = useRef<HTMLDivElement>(null)
  // a session created here whose id the address does not carry yet
  const unaddressed = useRef<string | null>(null)

  // a session named in the address (a reload, a shared link): go on with what the backend kept
  useEffect(() => {
    if (!resumeId || resumeId === sessionId) return
    let live = true
    api.assistantSession(resumeId).then(
      (view) => {
        if (!live) return
        abortRef.current?.abort()
        setSessionId(view.sessionId)
        setResumed({ mode: view.mode, workflow: view.workflow })
        setConversation(restoreConversation(view))
        setResumeError(null)
      },
      (error: unknown) => {
        if (live) setResumeError({ id: resumeId, error })
      },
    )
    return () => {
      live = false
    }
  }, [resumeId, sessionId])
  const resumeFailed = !!resumeError && resumeError.id === resumeId && !sessionId
  const resuming = !!resumeId && resumeId !== sessionId && !resumeFailed

  // a new session goes into the address once it is on screen, so a reload resumes it
  const workflowQuery = route.query.workflow
  useEffect(() => {
    if (!sessionId || unaddressed.current !== sessionId) return
    unaddressed.current = null
    navigate(pathFor('assistant', {}, { workflow: workflowQuery, session: sessionId }), { replace: true })
  }, [sessionId, workflowQuery])

  // the session's events in the platform stream: refreshed after every answer and every 15 s meanwhile
  const events = useQuery(['assistant-events', sessionId ?? ''], () => api.auditPlatform({ aggregate: 'assistant-session' }), { enabled: !!sessionId, refetchInterval: 15_000 })
  const timeline = useMemo(() => (sessionId && events.data ? timelineOf(events.data.entries, sessionId) : []), [events.data, sessionId])

  useEffect(() => {
    listRef.current?.scrollTo({ top: listRef.current.scrollHeight, behavior: 'smooth' })
  }, [messages, status])
  useEffect(() => () => abortRef.current?.abort(), [])

  const reset = () => {
    abortRef.current?.abort()
    const workflow = route.query.workflow || resumed?.workflow
    setSessionId(null)
    setResumed(null)
    setResumeError(null)
    setConversation(NO_CONVERSATION)
    setStreaming(false)
    if (resumeId) navigate(pathFor('assistant', {}, { workflow }), { replace: true })
  }

  const ensureSession = async (): Promise<string> => {
    if (sessionId) return sessionId
    const created = await api.createAssistantSession(sessionRequest(workflowName, existing.data?.yaml))
    unaddressed.current = created.sessionId
    setSessionId(created.sessionId)
    return created.sessionId
  }

  const send = async (content: string) => {
    const t = content.trim()
    if (!t || streaming || resuming) return
    const at = new Date().toISOString()
    setConversation((c) => ({ ...c, messages: [...c.messages, { id: nextId('msg'), role: 'user', text: t, at }], status: 'Thinking…', failure: null }))
    setText('')
    setStreaming(true)
    try {
      const id = await ensureSession()
      const ctrl = new AbortController()
      abortRef.current = ctrl
      await api.sendAssistantMessage(id, t, (ev) => setConversation((c) => applyEvent(c, ev, new Date().toISOString())), { signal: ctrl.signal })
    } catch (e) {
      if (!(e instanceof DOMException && e.name === 'AbortError')) toast.error('The assistant did not answer', errorMessage(e))
    } finally {
      setStreaming(false)
      setConversation((c) => ({ ...c, status: null }))
      // the mounted query, not this closure's: the session may have been created during this send
      invalidate('assistant-events')
    }
  }

  // "Ask for changes": another message in the same session, started for the user
  const askChanges = () => {
    setText((t) => (t.trim() ? t : 'Change the draft: '))
    document.getElementById('assistant-message')?.focus()
  }

  const onKey = (e: KeyboardEvent<HTMLTextAreaElement>) => {
    if ((e.metaKey || e.ctrlKey) && e.key === 'Enter') {
      e.preventDefault()
      void send(text)
    }
  }

  const yaml = draft?.workflowYaml ?? ''
  const handOff = async (how: HandOff) => {
    if (!yaml) return
    setSaving(how)
    try {
      let name = workflowName
      if (workflowName) {
        const saved = await api.saveDraft(workflowName, { yaml })
        const errors = saved.problems.filter((p) => p.level === 'error').length
        if (how !== 'submit') toast.success('Draft saved', errors ? `${saved.version} saved with ${errors} problem${errors === 1 ? '' : 's'} to fix.` : `${saved.version} saved.`)
      } else {
        const accepted = await api.createWorkflow({ yaml })
        name = accepted.id
        if (how !== 'submit') toast.success('Draft created', how === 'open' ? 'Opening it on the canvas.' : `${name} is in your workflows.`)
      }
      if (how === 'open' && name) navigate(pathFor('workflow', { name }))
      if (how === 'submit' && name) {
        // the draft goes through the same review as any workflow version: a change request, then a canary
        const accepted = await api.publishWorkflow(name, { strategy: 'canary' })
        const outcome = notify(accepted, 'The draft')
        if (outcome.kind === 'opened') navigate(pathFor('change-request', { id: outcome.id }))
      }
    } catch (e) {
      toast.error(how === 'open' ? 'Could not open the draft' : how === 'submit' ? 'Could not submit the draft' : 'Could not save the draft', errorMessage(e))
    } finally {
      setSaving(null)
    }
  }

  // the validation's problems on their steps, as the designer's Issues list shows them: the draft is parsed by the backend for the step names and glyphs
  const problems = useMemo(() => validation?.problems ?? [], [validation])
  const parsed = useQuery(['assistant-parse', yaml], () => api.parseWorkflow({ yaml }), { enabled: !!yaml && problems.length > 0 })
  const registry = useQuery(['registry-providers'], () => api.registryProviders(), { enabled: problems.length > 0 })
  const graph = useMemo(() => {
    const definition = parsed.data?.definition
    if (!definition) return EMPTY_GRAPH
    try {
      return documentToGraph(definition)
    } catch {
      return EMPTY_GRAPH
    }
  }, [parsed.data])
  // a fix here is a request to the assistant; opening a step means opening the draft on the canvas
  const askFix = (p: Problem) => void send(`Apply this fix${p.step ? ` on step ${p.step}` : ''}: ${fixOf(p)?.label ?? p.message}`)

  const paneHeight = 'lg:h-[calc(100vh-15rem)] lg:min-h-[32rem]'

  return (
    <>
      <PageHeader
        title="Assistant"
        subtitle={
          workflowName ? (
            <span>
              Editing <TextLink to={pathFor('workflow', { name: workflowName })}>{existing.data?.title ?? workflowName}</TextLink>: describe the change and review the draft.
            </span>
          ) : (
            'Describe a workflow in plain words; review the draft, then open it on the canvas.'
          )
        }
        actions={
          <>
            {sessionId && (
              <Tag outline mono title="Assistant session">
                {sessionId}
              </Tag>
            )}
            <Button icon="refresh" onClick={reset} disabled={messages.length === 0 && !sessionId && !resumeFailed}>
              New session
            </Button>
          </>
        }
      />

      <div className="grid grid-cols-1 gap-5 lg:grid-cols-2">
        <Card flush className={cx('flex flex-col overflow-hidden', paneHeight)}>
          <div ref={listRef} className="min-h-0 flex-1 overflow-y-auto p-5">
            {resuming ? (
              <div className="space-y-4" role="status" aria-label="Resuming the session">
                <Skeleton className="ml-auto h-10 w-2/3" />
                <Skeleton className="h-16 w-5/6" />
                <Skeleton className="ml-auto h-10 w-1/2" />
              </div>
            ) : resumeFailed ? (
              <ErrorState error={resumeError.error} title="Could not resume the session" className="h-full" />
            ) : messages.length === 0 ? (
              <EmptyState
                icon="sparkles"
                title={workflowName ? 'What should change?' : 'What should it do?'}
                message="Say who runs it, what it touches and what must be checked first. The assistant only drafts; nothing runs."
                className="h-full"
                action={
                  <div className="flex max-w-lg flex-wrap justify-center gap-2">
                    {EXAMPLES.map((ex) => (
                      <Button key={ex} size="sm" variant="capsule" onClick={() => setText(ex)} className="whitespace-normal text-left">
                        {ex}
                      </Button>
                    ))}
                  </div>
                }
              />
            ) : (
              <ol className="space-y-4" aria-label="Conversation">
                {messages.map((m) => (
                  <li key={m.id} className={cx('flex', m.role === 'user' ? 'justify-end' : 'justify-start')}>
                    <div className={cx('max-w-[85%] rounded-lg px-4 py-2.5 text-sm leading-relaxed', m.role === 'user' ? 'bg-accent text-white' : 'bg-surface-secondary text-primary')}>
                      <p className="whitespace-pre-wrap">{m.text}</p>
                      <p className={cx('mt-1 text-caption2', m.role === 'user' ? 'text-white/70' : 'text-tertiary')}>
                        <When at={m.at} />
                      </p>
                    </div>
                  </li>
                ))}
                {status && (
                  <li className="flex items-center gap-2 text-caption text-secondary" aria-live="polite">
                    <Spinner size={12} className="text-accent" />
                    <Tag tone="accent" size="sm" dot pulse>
                      {status}
                    </Tag>
                  </li>
                )}
                {failure && !status && (
                  <li role="alert" className="flex items-start gap-2 rounded-md bg-critical-tint px-3 py-2 text-caption text-critical">
                    <Icon name="close-circle" size={13} className="mt-0.5 shrink-0" />
                    {failure}
                  </li>
                )}
              </ol>
            )}
          </div>
          {sessionId && (
            <details className="border-t border-stroke-light" data-testid="session-timeline">
              <summary className="cursor-pointer px-4 py-2 text-caption text-secondary">
                Session timeline · {timeline.length} {timeline.length === 1 ? 'event' : 'events'}
                {events.fetching ? ' · refreshing' : ''}
              </summary>
              {timeline.length === 0 ? (
                <p className="px-4 pb-3 text-caption text-tertiary">Nothing in the platform stream yet; it refreshes every 15 seconds.</p>
              ) : (
                <ol className="max-h-40 space-y-1.5 overflow-y-auto px-4 pb-3" aria-label="Session events">
                  {timeline.map((t) => (
                    <li key={t.seq} className="flex items-start gap-2 text-caption">
                      <StatusDot kind={t.event === 'assistant.ready' ? 'healthy' : 'neutral'} className="mt-1" />
                      <span className="min-w-0 flex-1">
                        <span className="font-medium text-primary">{t.label}</span>
                        {t.detail && <span className="text-secondary"> · {t.detail}</span>}
                      </span>
                      <When at={t.at} className="shrink-0 text-tertiary" />
                    </li>
                  ))}
                </ol>
              )}
            </details>
          )}
          <div className="border-t border-stroke-light p-4">
            <Textarea
              id="assistant-message"
              rows={3}
              value={text}
              onChange={(e) => setText(e.target.value)}
              onKeyDown={onKey}
              placeholder={workflowName ? 'Add an approval before the restart…' : 'Restart a service after a health check…'}
              aria-label="Message"
              disabled={streaming || resuming}
            />
            <div className="mt-2 flex items-center justify-between gap-3">
              <span className="text-caption2 text-tertiary">
                <Kbd>{modKeyLabel()}</Kbd> <Kbd>↩</Kbd> to send
              </span>
              <Button variant="primary" icon="send" disabled={!text.trim() || resuming} loading={streaming} onClick={() => void send(text)}>
                Send
              </Button>
            </div>
          </div>
        </Card>

        <Card flush className={cx('flex flex-col overflow-hidden', paneHeight)}>
          <DraftPane
            draft={draft}
            validation={validation}
            streaming={streaming}
            saving={saving}
            graph={graph}
            providers={registry.data ?? []}
            onAnswer={(question, reply) => void send(`${question}\n${reply}`)}
            onAskChanges={askChanges}
            onHandOff={(how) => void handOff(how)}
            onFix={askFix}
          />
        </Card>
      </div>
    </>
  )
}

export interface DraftPaneProps {
  draft: AssistantDraft | null
  validation: Validation | null
  streaming: boolean
  saving: HandOff | null
  /** the draft as the backend parsed it, for the step names of the Issues list */
  graph: Graph
  providers: RegistryProvider[]
  onAnswer: (question: string, reply: string) => void
  onAskChanges: () => void
  onHandOff: (how: HandOff) => void
  onFix: (p: Problem) => void
}

/** The right pane: the latest draft, its questions, the validation's problems on their steps, the YAML and where it goes next. */
export function DraftPane({ draft, validation, streaming, saving, graph, providers, onAnswer, onAskChanges, onHandOff, onFix }: DraftPaneProps) {
  if (!draft) {
    return (
      <div className="flex min-h-0 flex-1 flex-col">
        {streaming ? (
          <div className="space-y-3 p-5" role="status" aria-label="Drafting">
            <Skeleton className="h-5 w-1/2" />
            <Skeleton className="h-4 w-5/6" />
            <Skeleton className="h-4 w-2/3" />
            <Skeleton className="mt-4 h-48" />
          </div>
        ) : (
          <EmptyState icon="document" title="No draft yet" message="The draft appears here as the assistant writes it: a summary, what it assumed, what it still needs to know, and the YAML." className="h-full" />
        )}
      </div>
    )
  }
  const yaml = draft.workflowYaml
  const nameOf = (step: string) => findStepNode(graph, step)?.data.step.name ?? step
  const groups = groupsOf(validation?.problems ?? [], nameOf)
  return (
    <>
      <div className="flex items-start justify-between gap-3 border-b border-stroke-light p-5">
        <div className="min-w-0">
          <h2 className="text-subheadline font-semibold text-primary">Draft</h2>
          <p className="mt-1 text-sm leading-relaxed text-secondary">{draft.summary || 'No summary yet.'}</p>
        </div>
        <Tag tone={DRAFT_TONE[draft.status]} dot pulse={draft.status === 'working'}>
          {DRAFT_LABEL[draft.status]}
        </Tag>
      </div>
      <div className="min-h-0 flex-1 space-y-5 overflow-y-auto p-5">
        {draft.questions && draft.questions.length > 0 && (
          <section>
            <h3 className="mb-2 text-caption font-medium uppercase tracking-wide text-tertiary">Questions for you</h3>
            <ul className="space-y-3">
              {draft.questions.map((q, i) => (
                <QuestionRow key={`${i}-${q.question}`} question={q.question} options={q.options} disabled={streaming} onAnswer={(reply) => onAnswer(q.question, reply)} />
              ))}
            </ul>
          </section>
        )}
        {draft.changes && draft.changes.length > 0 && <Bullets title="What changed" items={draft.changes} icon="edit" />}
        {draft.assumptions && draft.assumptions.length > 0 && <Bullets title="Assumptions" items={draft.assumptions} icon="info" />}
        {draft.missing && draft.missing.length > 0 && (
          <section>
            <h3 className="mb-2 text-caption font-medium uppercase tracking-wide text-tertiary">Missing building blocks</h3>
            <ul className="space-y-1.5">
              {draft.missing.map((m, i) => (
                <li key={i} className="flex items-start gap-2 text-sm">
                  <Tag size="sm" tone="warning">
                    {m.kind.replace(/_/g, ' ')}
                  </Tag>
                  <span className="min-w-0">
                    <span className="font-medium text-primary">{m.name}</span>
                    <span className="text-secondary"> · {m.why}</span>
                  </span>
                </li>
              ))}
            </ul>
          </section>
        )}
        <section>
          <h3 className="mb-2 flex items-center gap-2 text-caption font-medium uppercase tracking-wide text-tertiary">
            Validation
            {validation && <StatusDot kind={validation.valid ? 'healthy' : 'critical'} label={validation.valid ? 'passes' : 'has problems'} />}
            {validation?.rounds !== undefined && <span className="normal-case tracking-normal">· {validation.rounds} rounds</span>}
          </h3>
          {groups.length > 0 ? (
            <div className="-mx-5 border-y border-stroke-light">
              <IssuesList groups={groups} graph={graph} providers={providers} nameOf={nameOf} onOpen={() => onHandOff('open')} onFix={onFix} />
            </div>
          ) : validation?.errors && validation.errors.length > 0 ? (
            <ul className="space-y-1 rounded-md border border-stroke-light px-3 py-2 text-caption" aria-label="Validation errors">
              {validation.errors.map((e, i) => (
                <li key={i} className="flex gap-2 text-primary">
                  <Icon name="close-circle" size={13} className="mt-0.5 shrink-0 text-critical" />
                  {e}
                </li>
              ))}
            </ul>
          ) : (
            <p className="text-caption text-tertiary">{validation ? 'No problems reported.' : 'Not validated yet.'}</p>
          )}
          {draft.dryRun && (
            <p className="mt-2 flex flex-wrap items-center gap-2 text-caption text-secondary">
              <StatusDot kind={draft.dryRun.passed ? 'healthy' : 'warning'} label={`dry run ${draft.dryRun.passed ? 'passed' : 'did not pass'}`} />
              {draft.dryRun.path && draft.dryRun.path.length > 0 && <Mono>{draft.dryRun.path.join(' → ')}</Mono>}
              {draft.dryRun.error && <span className="text-critical">{draft.dryRun.error}</span>}
            </p>
          )}
        </section>
        <section>
          <h3 className="mb-2 text-caption font-medium uppercase tracking-wide text-tertiary">YAML</h3>
          {yaml ? <CodeBlock code={yaml} language="yaml" lineNumbers maxHeight="max-h-96" /> : <p className="text-caption text-tertiary">No valid draft yet.</p>}
        </section>
      </div>
      <div className="flex flex-wrap items-center justify-end gap-2 border-t border-stroke-light px-5 py-3">
        <Button variant="ghost" icon="edit" disabled={streaming} onClick={onAskChanges} title="Tell the assistant what to change; the same session goes on">
          Ask for changes
        </Button>
        <Button icon="document" disabled={!yaml || streaming} loading={saving === 'save'} onClick={() => onHandOff('save')}>
          Save as draft
        </Button>
        <Button icon="git-branch" disabled={!yaml || streaming || draft.status !== 'ready'} loading={saving === 'submit'} onClick={() => onHandOff('submit')} title={draft.status === 'ready' ? 'Saves the draft and opens a change request for it, released as a canary' : 'Once the draft is ready'}>
          Submit as change request
        </Button>
        <Button variant="primary" iconRight="arrow-right" disabled={!yaml || streaming} loading={saving === 'open'} onClick={() => onHandOff('open')}>
          Open in designer
        </Button>
      </div>
    </>
  )
}

function Bullets({ title, items, icon }: { title: string; items: string[]; icon: 'edit' | 'info' }) {
  return (
    <section>
      <h3 className="mb-2 text-caption font-medium uppercase tracking-wide text-tertiary">{title}</h3>
      <ul className="space-y-1.5">
        {items.map((it, i) => (
          <li key={i} className="flex items-start gap-2 text-sm text-primary">
            <Icon name={icon} size={13} className="mt-1 shrink-0 text-tertiary" />
            {it}
          </li>
        ))}
      </ul>
    </section>
  )
}

function QuestionRow({ question, options, disabled, onAnswer }: { question: string; options?: string[]; disabled?: boolean; onAnswer: (reply: string) => void }) {
  const [reply, setReply] = useState('')
  return (
    <li className="rounded-md border border-warning/40 bg-warning-tint p-3">
      <p className="text-sm text-primary">{question}</p>
      {options && options.length > 0 && (
        <div className="mt-2 flex flex-wrap gap-1.5">
          {options.map((o) => (
            <Button key={o} size="sm" variant="capsule" disabled={disabled} onClick={() => onAnswer(o)} className="bg-surface">
              {o}
            </Button>
          ))}
        </div>
      )}
      <form
        className="mt-2 flex items-center gap-2"
        onSubmit={(e) => {
          e.preventDefault()
          if (reply.trim()) {
            onAnswer(reply.trim())
            setReply('')
          }
        }}
      >
        <Input size="sm" value={reply} onChange={(e) => setReply(e.target.value)} placeholder="Your answer" aria-label={`Answer: ${question}`} disabled={disabled} className="bg-surface" />
        <Button size="sm" type="submit" icon="send" disabled={disabled || !reply.trim()}>
          Answer
        </Button>
      </form>
    </li>
  )
}
