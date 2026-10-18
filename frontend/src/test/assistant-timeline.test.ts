import { describe, expect, it } from 'vitest'
import { timelineOf } from '../pages/AssistantPage'
import type { EvidenceEntry } from '../lib/types'

const entry = (seq: number, data: Record<string, unknown>, actor = 'gonzalo'): EvidenceEntry => ({ seq, at: `2026-10-18T10:0${seq}:00Z`, kind: 'decision', actor, data, hash: `h${seq}`, previous: `h${seq - 1}` })

const ENTRIES: EvidenceEntry[] = [
  entry(3, { event: 'assistant.draft-updated', aggregate: 'assistant-session', aggregateId: 'as-77', draftId: 'as-77', status: 'working', contentHash: 'sha256:1', valid: false, errors: ['step.unknownProvider @restart: no such provider', 'x'] }),
  entry(1, { event: 'assistant.started', aggregate: 'assistant-session', aggregateId: 'as-77', draftId: 'as-77', user: 'gonzalo', kind: 'workflow-draft', agent: 'devin', mode: 'edit', workflow: 'vm-restart' }),
  entry(2, { event: 'assistant.message', aggregate: 'assistant-session', aggregateId: 'as-77', draftId: 'as-77', role: 'user', text: 'Add an approval before the restart.' }),
  entry(4, { event: 'assistant.started', aggregate: 'assistant-session', aggregateId: 'as-78', draftId: 'as-78', user: 'ana', mode: 'create' }, 'ana'),
  entry(5, { event: 'workflow.published', aggregate: 'workflow', aggregateId: 'as-77', name: 'as-77' }),
  entry(6, { event: 'assistant.ready', aggregate: 'assistant-session', aggregateId: 'as-77', draftId: 'as-77', status: 'ready', contentHash: 'sha256:2', valid: true, errors: 0 }),
]

describe('the assistant session timeline', () => {
  it('keeps the session’s own events, oldest first, and reads each one', () => {
    const items = timelineOf(ENTRIES, 'as-77')
    expect(items.map((i) => i.seq)).toEqual([1, 2, 3, 6])
    expect(items.map((i) => i.label)).toEqual(['Session started', 'Message', 'Draft updated', 'Draft ready'])
    expect(items[0].detail).toBe('edit · devin · editing vm-restart')
    expect(items[1].detail).toBe('user: Add an approval before the restart.')
    expect(items[2].detail).toBe('working · not valid · 2 errors')
    expect(items[3].detail).toBe('ready · valid · 0 errors')
    expect(items[0].actor).toBe('gonzalo')
  })

  it('finds a session by its draft id too, clips long messages and names an event it does not know', () => {
    const long = 'x'.repeat(300)
    const items = timelineOf([entry(1, { event: 'assistant.message', aggregate: 'assistant-session', aggregateId: 'draft-1', draftId: 'as-9', role: 'assistant', text: long }), entry(2, { event: 'assistant.playbooks-synced', aggregate: 'assistant-session', aggregateId: 'as-9' })], 'as-9')
    expect(items).toHaveLength(2)
    expect(items[0].detail?.length).toBe('assistant: '.length + 160)
    expect(items[0].detail?.endsWith('…')).toBe(true)
    expect(items[1]).toMatchObject({ label: 'playbooks-synced', detail: undefined })
    expect(timelineOf(ENTRIES, 'as-404')).toEqual([])
  })
})
