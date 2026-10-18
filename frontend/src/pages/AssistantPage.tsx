import { useEffect, useRef, useState, type KeyboardEvent } from 'react'
import { Button, Card, CodeBlock, EmptyState, Icon, Input, Kbd, PageHeader, Skeleton, Spinner, StatusDot, Tag, Textarea, cx, modKeyLabel, nextId, useToast, type Tone } from '@iaf/ui'
import type { PageProps } from '../app/router'
import { navigate, pathFor } from '../app/router'
import { api, errorMessage } from '../lib/api'
import { useQuery } from '../lib/queries'
import type { AssistantDraft, AssistantEvent, Problem } from '../lib/types'
import { ProblemList } from '../components/ProblemList'
import { Mono, TextLink, When } from '../components/common'

/*
 * The natural-language builder: a conversation at the left, the current draft at the right.
 * Every message streams status, message, draft, validation and done events over SSE; the draft
 * pane shows the latest draft with its assumptions, open questions, validation and YAML, and
 * hands it to the designer or saves it as a draft.
 */

interface Msg {
  id: string
  role: 'user' | 'assistant'
  text: string
  at: string
}

interface Validation {
  valid: boolean
  problems?: Problem[]
  errors?: string[]
  rounds?: number
}

const EXAMPLES = [
  'Restart a VM after checking no backup is running, with an approval for production.',
  'Create a database from a size and an environment, register it, and tell the channel.',
  'Patch one CVE on a list of servers in waves of 5, 25 and 100 percent, rebooting only when needed.',
  'Build a hardened image from a base OS, scan it, and register it when the scan is clean.',
]

const DRAFT_TONE: Record<AssistantDraft['status'], Tone> = { working: 'accent', ready: 'healthy', needs_input: 'warning', failed: 'critical' }
const DRAFT_LABEL: Record<AssistantDraft['status'], string> = { working: 'Working', ready: 'Ready', needs_input: 'Needs your input', failed: 'Failed' }

const asText = (data: unknown): string => {
  if (typeof data === 'string') return data
  if (data && typeof data === 'object') {
    const d = data as Record<string, unknown>
    for (const k of ['text', 'message', 'content', 'status', 'summary']) if (typeof d[k] === 'string') return d[k] as string
    return JSON.stringify(data)
  }
  return String(data ?? '')
}

/** The draft event in the structured-output shape (`workflowYaml`, older playbooks say `capabilityYaml`). */
function normalizeDraft(data: unknown): AssistantDraft | null {
  if (!data || typeof data !== 'object') return null
  const d = data as Record<string, unknown>
  const yaml = (d.workflowYaml ?? d.capabilityYaml ?? d.yaml ?? '') as string
  const validation = (d.validation as AssistantDraft['validation'] | undefined) ?? { valid: false }
  const status = (['working', 'ready', 'needs_input', 'failed'].includes(String(d.status)) ? d.status : 'working') as AssistantDraft['status']
  return {
    status,
    summary: typeof d.summary === 'string' ? d.summary : '',
    workflowYaml: yaml,
    changes: Array.isArray(d.changes) ? (d.changes as string[]) : undefined,
    assumptions: Array.isArray(d.assumptions) ? (d.assumptions as string[]) : undefined,
    questions: Array.isArray(d.questions) ? (d.questions as AssistantDraft['questions']) : undefined,
    missing: Array.isArray(d.missing) ? (d.missing as AssistantDraft['missing']) : undefined,
    validation,
    dryRun: d.dryRun as AssistantDraft['dryRun'],
  }
}

export default function AssistantPage({ route }: PageProps) {
  const toast = useToast()
  const workflowName = route.query.workflow || undefined
  const existing = useQuery(['workflow', workflowName ?? ''], () => api.workflow(workflowName!), { enabled: !!workflowName })
  const [sessionId, setSessionId] = useState<string | null>(null)
  const [messages, setMessages] = useState<Msg[]>([])
  const [status, setStatus] = useState<string | null>(null)
  const [draft, setDraft] = useState<AssistantDraft | null>(null)
  const [validation, setValidation] = useState<Validation | null>(null)
  const [streaming, setStreaming] = useState(false)
  const [text, setText] = useState('')
  const [saving, setSaving] = useState<'open' | 'save' | null>(null)
  const abortRef = useRef<AbortController | null>(null)
  const listRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    listRef.current?.scrollTo({ top: listRef.current.scrollHeight, behavior: 'smooth' })
  }, [messages, status])
  useEffect(() => () => abortRef.current?.abort(), [])

  const reset = () => {
    abortRef.current?.abort()
    setSessionId(null)
    setMessages([])
    setDraft(null)
    setValidation(null)
    setStatus(null)
    setStreaming(false)
  }

  const ensureSession = async (): Promise<string> => {
    if (sessionId) return sessionId
    const body = workflowName ? { mode: 'edit' as const, workflow: workflowName, draftYaml: existing.data?.yaml } : { mode: 'create' as const }
    const created = await api.createAssistantSession(body)
    setSessionId(created.sessionId)
    return created.sessionId
  }

  const handle = (ev: AssistantEvent) => {
    switch (ev.type) {
      case 'status':
        setStatus(asText(ev.data))
        break
      case 'message':
        setMessages((m) => [...m, { id: nextId('msg'), role: 'assistant', text: asText(ev.data), at: new Date().toISOString() }])
        break
      case 'draft': {
        const d = normalizeDraft(ev.data)
        if (d) {
          setDraft(d)
          if (d.validation) setValidation((v) => ({ ...(v ?? {}), valid: d.validation.valid, errors: d.validation.errors, rounds: d.validation.rounds }))
        }
        break
      }
      case 'validation': {
        const v = ev.data as Validation | null
        if (v && typeof v === 'object') setValidation({ valid: !!v.valid, problems: v.problems, errors: v.errors, rounds: v.rounds })
        break
      }
      case 'done':
        setStatus(null)
        break
    }
  }

  const send = async (content: string) => {
    const t = content.trim()
    if (!t || streaming) return
    setMessages((m) => [...m, { id: nextId('msg'), role: 'user', text: t, at: new Date().toISOString() }])
    setText('')
    setStreaming(true)
    setStatus('Thinking…')
    try {
      const id = await ensureSession()
      const ctrl = new AbortController()
      abortRef.current = ctrl
      await api.sendAssistantMessage(id, t, handle, { signal: ctrl.signal })
    } catch (e) {
      if (!(e instanceof DOMException && e.name === 'AbortError')) toast.error('The assistant did not answer', errorMessage(e))
    } finally {
      setStreaming(false)
      setStatus(null)
    }
  }

  const onKey = (e: KeyboardEvent<HTMLTextAreaElement>) => {
    if ((e.metaKey || e.ctrlKey) && e.key === 'Enter') {
      e.preventDefault()
      void send(text)
    }
  }

  const yaml = draft?.workflowYaml ?? ''
  const handOff = async (how: 'open' | 'save') => {
    if (!yaml) return
    setSaving(how)
    try {
      let name = workflowName
      if (workflowName) {
        const saved = await api.saveDraft(workflowName, { yaml })
        const errors = saved.problems.filter((p) => p.level === 'error').length
        toast.success('Draft saved', errors ? `${saved.version} saved with ${errors} problem${errors === 1 ? '' : 's'} to fix.` : `${saved.version} saved.`)
      } else {
        const accepted = await api.createWorkflow({ yaml })
        name = accepted.id
        toast.success('Draft created', how === 'open' ? 'Opening it on the canvas.' : `${name} is in your workflows.`)
      }
      if (how === 'open' && name) navigate(pathFor('workflow', { name }))
    } catch (e) {
      toast.error(how === 'open' ? 'Could not open the draft' : 'Could not save the draft', errorMessage(e))
    } finally {
      setSaving(null)
    }
  }

  const answer = (question: string, reply: string) => void send(`${question}\n${reply}`)
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
            <Button icon="refresh" onClick={reset} disabled={messages.length === 0}>
              New session
            </Button>
          </>
        }
      />

      <div className="grid grid-cols-1 gap-5 lg:grid-cols-2">
        <Card flush className={cx('flex flex-col overflow-hidden', paneHeight)}>
          <div ref={listRef} className="min-h-0 flex-1 overflow-y-auto p-5">
            {messages.length === 0 ? (
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
              </ol>
            )}
          </div>
          <div className="border-t border-stroke-light p-4">
            <Textarea
              rows={3}
              value={text}
              onChange={(e) => setText(e.target.value)}
              onKeyDown={onKey}
              placeholder={workflowName ? 'Add an approval before the restart…' : 'Restart a service after a health check…'}
              aria-label="Message"
              disabled={streaming}
            />
            <div className="mt-2 flex items-center justify-between gap-3">
              <span className="text-caption2 text-tertiary">
                <Kbd>{modKeyLabel()}</Kbd> <Kbd>↩</Kbd> to send
              </span>
              <Button variant="primary" icon="send" disabled={!text.trim()} loading={streaming} onClick={() => void send(text)}>
                Send
              </Button>
            </div>
          </div>
        </Card>

        <Card flush className={cx('flex flex-col overflow-hidden', paneHeight)}>
          {!draft ? (
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
          ) : (
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
                        <QuestionRow key={`${i}-${q.question}`} question={q.question} options={q.options} disabled={streaming} onAnswer={(reply) => answer(q.question, reply)} />
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
                  {validation?.problems && validation.problems.length > 0 ? (
                    <ProblemList problems={validation.problems} compact />
                  ) : validation?.errors && validation.errors.length > 0 ? (
                    <ul className="space-y-1 rounded-md border border-stroke-light px-3 py-2 text-caption">
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
                <Button icon="document" disabled={!yaml || streaming} loading={saving === 'save'} onClick={() => void handOff('save')}>
                  Save as draft
                </Button>
                <Button variant="primary" iconRight="arrow-right" disabled={!yaml || streaming} loading={saving === 'open'} onClick={() => void handOff('open')}>
                  Open in designer
                </Button>
              </div>
            </>
          )}
        </Card>
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
