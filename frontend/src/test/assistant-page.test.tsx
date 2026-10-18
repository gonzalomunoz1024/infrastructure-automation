import { renderToString } from 'react-dom/server'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { WorkflowDocument } from '@iaf/workflow-checks'
import { api, assistantEventOf } from '../lib/api'
import type { AssistantDraft, AssistantEvent, AssistantSession, Problem } from '../lib/types'
import { documentToGraph } from '../designer/model/graph'
import { applyEvent, DraftPane, NO_CONVERSATION, normalizeDraft, restoreConversation, sessionRequest, type Conversation, type DraftPaneProps } from '../pages/AssistantPage'

/*
 * The Assistant page: the stream read the way the backend writes it (the SSE event name is the
 * type, the data the payload alone), the conversation those events make, a session resumed from
 * GET /assistant/sessions/{id}, the body that creates a session, and the draft pane with the
 * validation's problems on their steps and the hand-offs.
 */

const AT = '2026-10-18T10:00:00Z'
const YAML = 'apiVersion: iaf.platform/v1\nkind: Workflow\nmetadata:\n  name: vm-restart-safe\n'

const DEFINITION = {
  apiVersion: 'iaf.platform/v1',
  kind: 'Workflow',
  metadata: { name: 'vm-restart-safe', noun: 'vm', verb: 'restart-safely', owner: 'team-compute', version: '0.1.0' },
  workflow: {
    do: [
      { check: { name: 'Check the backups', call: 'provider', with: { provider: 'backup', action: 'listJobs', input: {} } } },
      { restart: { name: 'Restart the VM', call: 'provider', with: { provider: 'vcenter', action: 'vm.restart', input: {} } } },
    ],
  },
} as WorkflowDocument

const PROBLEMS: Problem[] = [
  { level: 'error', code: 'step.unknownProvider', step: 'restart', field: 'with.provider', message: 'No provider called vcenter; vsphere is the closest' },
  { level: 'warning', code: 'workflow.noApproval', message: 'A change step runs without an approval' },
]

const DRAFT = { status: 'ready', summary: 'Restarts a VM after a backup check.', workflowYaml: YAML, assumptions: ['The VM id is the vSphere id'], validation: { valid: false, rounds: 1, errors: ['step.unknownProvider @restart: No provider called vcenter'] } }

/** One message's stream, frame by frame as Spring writes it (no space after the colon). */
const FRAMES = [
  'id:1\nevent:status\ndata:{"status":"Reading the registry"}\n\n',
  'id:2\nevent:message\ndata:{"role":"assistant","text":"Found 3 providers and 9 workflows."}\n\n',
  `id:3\nevent:draft\ndata:${JSON.stringify(DRAFT)}\n\n`,
  `id:4\nevent:validation\ndata:${JSON.stringify({ valid: false, problems: PROBLEMS })}\n\n`,
  'id:5\nevent:message\ndata:{"role":"assistant","text":"The restart names a provider that does not exist."}\n\n',
  'id:6\nevent:done\ndata:{"status":"done"}\n\n',
]

function streamOf(chunks: string[]): ReadableStream<Uint8Array> {
  const enc = new TextEncoder()
  return new ReadableStream({
    start(controller) {
      for (const c of chunks) controller.enqueue(enc.encode(c))
      controller.close()
    },
  })
}

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('the assistant stream', () => {
  it('reads the frames the backend writes: the event name is the type and the data the payload alone', async () => {
    const calls: { url: string; body: unknown; accept: string | null }[] = []
    // the frames cut at arbitrary points, as a network delivers them
    const text = FRAMES.join('')
    const chunks = [text.slice(0, 17), text.slice(17, 120), text.slice(120, 121), text.slice(121)]
    vi.stubGlobal('fetch', async (input: RequestInfo | URL, init?: RequestInit) => {
      calls.push({ url: String(input), body: JSON.parse(String(init?.body)), accept: new Headers(init?.headers).get('accept') })
      return new Response(streamOf(chunks), { status: 200, headers: { 'content-type': 'text/event-stream' } })
    })
    const events: AssistantEvent[] = []
    await api.sendAssistantMessage('as-81', 'Restart a VM safely', (e) => events.push(e))
    expect(calls).toEqual([{ url: '/api/v1/assistant/sessions/as-81/messages', body: { text: 'Restart a VM safely' }, accept: 'text/event-stream' }])
    expect(events.map((e) => e.type)).toEqual(['status', 'message', 'draft', 'validation', 'message', 'done'])
    expect(events[0].data).toEqual({ status: 'Reading the registry' })
    expect(events[1].data).toEqual({ role: 'assistant', text: 'Found 3 providers and 9 workflows.' })
    expect(events[2].data).toEqual(DRAFT)
    expect(events[3].data).toEqual({ valid: false, problems: PROBLEMS })
    expect(events[5].data).toEqual({ status: 'done' })
  })

  it('takes a frame without an event name for a message, drops the types it does not know, and never unwraps the data', () => {
    expect(assistantEventOf(undefined, { role: 'assistant', text: 'Hello' })).toEqual({ type: 'message', data: { role: 'assistant', text: 'Hello' } })
    expect(assistantEventOf('heartbeat', {})).toBeNull()
    // an envelope in the data is the payload as it came, not an event
    expect(assistantEventOf('status', { type: 'draft', data: {} })).toEqual({ type: 'status', data: { type: 'draft', data: {} } })
  })
})

const run = (events: AssistantEvent[], from: Conversation = NO_CONVERSATION): Conversation => events.reduce((c, e) => applyEvent(c, e, AT), from)

describe('the conversation', () => {
  it('follows the events of one message: what the assistant says, the draft and the problems on their steps', () => {
    const c = run([
      { type: 'status', data: { status: 'Reading the registry' } },
      { type: 'message', data: { role: 'assistant', text: 'Found 3 providers and 9 workflows.' } },
      { type: 'draft', data: DRAFT },
      { type: 'validation', data: { valid: false, problems: PROBLEMS } },
      { type: 'message', data: { role: 'assistant', text: 'The restart names a provider that does not exist.' } },
      { type: 'done', data: { status: 'done' } },
    ])
    expect(c.messages.map((m) => [m.role, m.text, m.at])).toEqual([
      ['assistant', 'Found 3 providers and 9 workflows.', AT],
      ['assistant', 'The restart names a provider that does not exist.', AT],
    ])
    expect(c.status).toBeNull()
    expect(c.failure).toBeNull()
    expect(c.draft?.workflowYaml).toBe(YAML)
    expect(c.draft?.assumptions).toEqual(['The VM id is the vSphere id'])
    expect(c.validation).toEqual({ valid: false, rounds: 1, errors: ['step.unknownProvider @restart: No provider called vcenter'], problems: PROBLEMS })
  })

  it('shows what the assistant is doing until it is done, and why it stopped when it failed', () => {
    const working = run([{ type: 'status', data: { status: 'Drafting', externalId: 'devin-1' } }])
    expect(working.status).toBe('Drafting')
    const failed = run([{ type: 'done', data: { status: 'failed', error: 'The agent did not answer within 10 minutes' } }], working)
    expect(failed.status).toBeNull()
    expect(failed.failure).toBe('The agent did not answer within 10 minutes')
    expect(run([{ type: 'done', data: { status: 'failed' } }]).failure).toBe('The assistant stopped before it finished.')
    // an empty message or status changes nothing
    expect(run([{ type: 'message', data: { role: 'assistant', text: '' } }, { type: 'status', data: {} }])).toEqual(NO_CONVERSATION)
  })

  it('drops the problems of the previous draft when a new one arrives, until its own validation does', () => {
    const first = run([{ type: 'draft', data: DRAFT }, { type: 'validation', data: { valid: false, problems: PROBLEMS } }])
    const second = run([{ type: 'draft', data: { ...DRAFT, validation: { valid: true, rounds: 2, errors: [] } } }], first)
    expect(second.validation).toEqual({ valid: true, rounds: 2, errors: [] })
    expect(run([{ type: 'validation', data: { valid: true, problems: [] } }], second).validation).toEqual({ valid: true, rounds: 2, errors: [], problems: [] })
  })

  it('reads the draft by its schema and nothing else', () => {
    expect(normalizeDraft({ yaml: YAML, status: 'odd' })).toMatchObject({ workflowYaml: '', status: 'working', summary: '', validation: { valid: false } })
    expect(normalizeDraft({ workflowYaml: YAML, status: 'needs_input', questions: [{ question: 'Which cluster?', options: ['a', 'b'] }] })).toMatchObject({ workflowYaml: YAML, status: 'needs_input', questions: [{ question: 'Which cluster?', options: ['a', 'b'] }] })
    expect(normalizeDraft(null)).toBeNull()
    expect(normalizeDraft('a draft')).toBeNull()
  })
})

const SESSION: AssistantSession = {
  sessionId: 'as-90',
  mode: 'edit',
  workflow: 'vm-restart',
  status: 'done',
  draft: DRAFT as AssistantDraft,
  history: [
    { role: 'user', text: 'Check the backups before restarting.', at: '2026-10-18T09:00:00Z' },
    { role: 'assistant', text: 'I added a backup check before the restart.', at: '2026-10-18T09:01:00Z' },
  ],
  createdAt: '2026-10-18T08:59:00Z',
  updatedAt: '2026-10-18T09:01:00Z',
}

describe('a session the backend kept', () => {
  it('comes back as its turns and its latest draft', () => {
    const c = restoreConversation(SESSION)
    expect(c.messages.map((m) => [m.role, m.text, m.at])).toEqual([
      ['user', 'Check the backups before restarting.', '2026-10-18T09:00:00Z'],
      ['assistant', 'I added a backup check before the restart.', '2026-10-18T09:01:00Z'],
    ])
    expect(new Set(c.messages.map((m) => m.id)).size).toBe(2)
    expect(c.draft?.summary).toBe('Restarts a VM after a backup check.')
    expect(c.validation).toEqual({ valid: false, rounds: 1, errors: ['step.unknownProvider @restart: No provider called vcenter'] })
    expect(c.status).toBeNull()
    expect(c.failure).toBeNull()
  })

  it('without a draft yet, or ended on a failure', () => {
    const c = restoreConversation({ ...SESSION, mode: 'create', workflow: undefined, draft: undefined, status: 'failed', history: [] })
    expect(c.draft).toBeNull()
    expect(c.validation).toBeNull()
    expect(c.failure).toBe('The last message ended before the assistant finished.')
  })

  it('is fetched by its id', async () => {
    const urls: string[] = []
    vi.stubGlobal('fetch', async (input: RequestInfo | URL) => {
      urls.push(String(input))
      return new Response(JSON.stringify(SESSION), { status: 200, headers: { 'content-type': 'application/json' } })
    })
    expect(await api.assistantSession('as-90')).toEqual(SESSION)
    expect(urls).toEqual(['/api/v1/assistant/sessions/as-90'])
  })
})

describe('a new session', () => {
  it('edits the workflow the page names, from its current YAML, or creates one', () => {
    expect(sessionRequest('vm-restart', 'kind: Workflow\n')).toEqual({ mode: 'edit', workflow: 'vm-restart', draftYaml: 'kind: Workflow\n' })
    expect(sessionRequest('vm-restart', undefined)).toEqual({ mode: 'edit', workflow: 'vm-restart' })
    expect(sessionRequest(undefined, 'ignored')).toEqual({ mode: 'create' })
  })
})

const noop = () => {}

function pane(props: Partial<DraftPaneProps>): string {
  return renderToString(<DraftPane draft={null} validation={null} streaming={false} saving={null} graph={documentToGraph(DEFINITION)} providers={[]} onAnswer={noop} onAskChanges={noop} onHandOff={noop} onFix={noop} {...props} />)
}

/** The opening tag of the button labelled `label`. */
function button(html: string, label: string): string {
  const at = html.indexOf(`${label}<`)
  expect(at, `no button ${label}`).toBeGreaterThan(0)
  const start = html.lastIndexOf('<button', at)
  return html.slice(start, html.indexOf('>', start) + 1)
}

describe('the draft pane', () => {
  it('lists the validation problems on their steps, by the names the parsed draft gives them', () => {
    const draft = normalizeDraft(DRAFT)
    const html = pane({ draft, validation: { valid: false, rounds: 1, problems: PROBLEMS } })
    expect(html).toContain('data-testid="issues-list"')
    expect(html).toContain('Restart the VM')
    expect(html).toContain('data-code="step.unknownProvider"')
    expect(html).toContain('No provider called vcenter; vsphere is the closest')
    expect(html).toContain('A change step runs without an approval')
    expect(html).toContain('The VM id is the vSphere id')
  })

  it('lists the validation errors of the draft when no problems on steps came', () => {
    const html = pane({ draft: normalizeDraft(DRAFT), validation: { valid: false, rounds: 1, errors: ['step.unknownProvider @restart: No provider called vcenter'] } })
    expect(html).not.toContain('data-testid="issues-list"')
    expect(html).toContain('aria-label="Validation errors"')
    expect(html).toContain('step.unknownProvider @restart: No provider called vcenter')
  })

  it('opens, saves and submits a ready draft, submits nothing that is not ready, and waits while the assistant writes', () => {
    const ready = pane({ draft: normalizeDraft(DRAFT), validation: { valid: true } })
    for (const label of ['Open in designer', 'Save as draft', 'Submit as change request']) expect(button(ready, label)).not.toContain('disabled=""')
    const asking = pane({ draft: normalizeDraft({ ...DRAFT, status: 'needs_input' }), validation: null })
    expect(button(asking, 'Submit as change request')).toContain('disabled=""')
    expect(button(asking, 'Open in designer')).not.toContain('disabled=""')
    const streaming = pane({ draft: normalizeDraft(DRAFT), validation: { valid: true }, streaming: true })
    for (const label of ['Open in designer', 'Save as draft', 'Submit as change request', 'Ask for changes']) expect(button(streaming, label)).toContain('disabled=""')
  })

  it('is empty until the first draft, and a skeleton while the assistant drafts it', () => {
    expect(pane({})).toContain('No draft yet')
    expect(pane({ streaming: true })).toContain('aria-label="Drafting"')
  })
})
