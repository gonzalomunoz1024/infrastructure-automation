import { describe, expect, it } from 'vitest'
import { buildMockData } from '../../mock/data'
import { createMockServer, csrfTokenOf, memorySessionStore } from '../../mock/server'
import { ApiError, type ApiErrorBody } from '../../lib/api'
import { codeSamplesFor, workflowRunYaml } from '../../lib/code'
import { composeTypes, type ConsoleType } from '../../lib/descriptors'
import { explain, movedOnTo, refusedInState } from '../../lib/plain'
import type { ConsoleResourceDetail } from '../../lib/types'
import { changedFields, diffRows, editBody, editSchemaOf, editStart, editWaitNote, rebaseEdits } from './EditDrawer'

/*
 * Edit configuration (WP 4.1): the form is the update workflow's input without its subject, it
 * starts from the desired attributes, only the fields that change are sent, the diff shows now,
 * after and what the provider reports, a newer version keeps the person's edits, and a stale
 * If-Match reads "Someone changed this first".
 */

const data = buildMockData()
const vm = composeTypes(data.resourceTypes, data.catalog).find((t) => t.name === 'vm') as ConsoleType
const schema = editSchemaOf(vm.update!, vm)
const WEB02 = 'wfrn:wf:compute:global:123456789012:vm/vm-demo03'

const detail = (over: Partial<ConsoleResourceDetail> = {}): ConsoleResourceDetail => ({ wfrn: WEB02, type: 'vm', service: 'compute', name: 'web-02', state: 'active', version: 1, attributes: { name: 'web-02', cpu: 4, memoryGb: 16, environment: 'prod' }, tags: {}, createdAt: '2026-09-30T00:05:25Z', updatedAt: '2026-09-30T00:05:25Z', can: ['update'], ...over })

describe('the edit form', () => {
  it('is the update workflow’s input without its subject: the mutable fields and the operational reason', () => {
    expect(vm.update?.workflow).toBe('update-vm')
    expect(Object.keys(schema.properties ?? {})).toEqual(['cpu', 'memoryGb', 'reason'])
    expect(schema.required).toEqual([])
  })

  it('starts from the desired attributes, skipping a value the field would refuse', () => {
    expect(editStart(schema, { name: 'web-02', cpu: 4, memoryGb: 16 })).toEqual({ cpu: 4, memoryGb: 16 })
    expect(editStart(schema, { cpu: '4', memoryGb: 24 })).toEqual({})
  })
})

describe('what changes', () => {
  const start = { cpu: 4, memoryGb: 16 }

  it('is only the mutable fields no longer where they started; a reason alone changes nothing', () => {
    expect(changedFields(schema, start, { cpu: 4, memoryGb: 32, reason: 'more room' })).toEqual({ memoryGb: 32 })
    expect(changedFields(schema, start, { cpu: 4, memoryGb: 16, reason: 'more room' })).toEqual({})
    // a field left empty is unchanged: absent means unchanged
    expect(changedFields(schema, start, { cpu: 4 })).toEqual({})
  })

  it('shows now, after, and beside now what the provider reports when it differs', () => {
    expect(diffRows(schema, detail(), { memoryGb: 32 })).toEqual([{ field: 'memoryGb', label: 'Memory (GiB)', now: 16, after: 32, schema: schema.properties?.memoryGb }])
    // api-01 drifted: the provider reports twice the memory asked for
    const drifted = detail({ attributes: { cpu: 2, memoryGb: 8 }, observed: { cpu: 2, memoryGb: 16, status: 'ready' }, finding: { findingId: 'drift_1', wfrn: WEB02, status: 'open', severity: 'warning', fields: [{ path: 'memoryGb', expected: 8, observed: 16 }], openedAt: '2026-10-23T09:00:00Z' } })
    expect(diffRows(schema, drifted, { memoryGb: 32, cpu: 4 }).map((r) => [r.field, r.now, r.after, r.observed])).toEqual([
      ['memoryGb', 8, 32, 16],
      ['cpu', 2, 4, undefined],
    ])
    // without a finding, the observed record says it
    expect(diffRows(schema, detail({ observed: { cpu: 6 } }), { cpu: 8 })[0].observed).toBe(6)
  })

  it('carries the person’s edits onto a newer version, everything else taking the new values', () => {
    const value = { cpu: 4, memoryGb: 32, reason: 'more room' }
    // someone else moved cpu to 8 meanwhile
    const rebased = rebaseEdits(schema, start, value, { cpu: 8, memoryGb: 16 })
    expect(rebased).toEqual({ cpu: 8, memoryGb: 32, reason: 'more room' })
    expect(changedFields(schema, { cpu: 8, memoryGb: 16 }, rebased)).toEqual({ memoryGb: 32 })
    // an edit the newer version already made drops out of the diff
    expect(changedFields(schema, { cpu: 4, memoryGb: 32 }, rebaseEdits(schema, start, value, { cpu: 4, memoryGb: 32 }))).toEqual({})
  })

  it('sends only the changed fields, the reason beside them', () => {
    expect(editBody(schema, { memoryGb: 32 }, { cpu: 4, memoryGb: 32, reason: '  the shop needs more cache ' })).toEqual({ input: { memoryGb: 32 }, reason: 'the shop needs more cache' })
    expect(editBody(schema, { memoryGb: 32 }, { memoryGb: 32 })).toEqual({ input: { memoryGb: 32 } })
  })

  it('waits while a request is in flight or the resource is not active or failed', () => {
    expect(editWaitNote(detail({ lastJob: { jobId: 'job_1', workflow: 'vm-restart', status: 'running' } }), 'virtual machine')).toBe('Waits for the request in flight to end')
    expect(editWaitNote(detail({ state: 'updating' }), 'virtual machine')).toBe('Runs once the virtual machine is active')
    expect(editWaitNote(detail({ state: 'failed' }), 'virtual machine')).toBeUndefined()
    expect(editWaitNote(detail(), 'virtual machine')).toBeUndefined()
  })
})

describe('someone changed it first', () => {
  it('reads a stale If-Match as "Someone changed this first", with the version it moved on to', () => {
    let now = Date.parse('2026-10-23T10:00:00Z')
    const server = createMockServer(buildMockData(), memorySessionStore(), () => now)
    server.handle({ method: 'GET', url: '/auth/login?login_hint=gonzalo', headers: {} })
    const patch = (input: Record<string, unknown>, version: number) => server.handle({ method: 'PATCH', url: `/console/v1/r/vm/${encodeURIComponent(WEB02)}`, headers: { 'x-csrf-token': csrfTokenOf('gonzalo'), 'if-match': String(version) }, body: { input } })
    expect(patch({ cpu: 8 }, 1).status).toBe(202)
    now += 6_000 + 8_000 + 1
    const stale = patch({ memoryGb: 32 }, 1)
    const error = new ApiError(stale.status, stale.body as ApiErrorBody)
    expect(movedOnTo(error)).toBe(2)
    const words = explain(error, 'Edit web-02')
    expect(words).toMatchObject({ title: 'Someone changed this first', message: 'It moved on to version 2 while you had it open.', retryable: false })
    // the error is a sentence, not a code to quote
    expect(words.code).toBeUndefined()
  })

  it('says why a resource that is not active or failed cannot change, and leaves other conflicts alone', () => {
    const busy = new ApiError(409, { error: 'the resource is updating; it changes only while active or failed', detail: { state: 'updating' } })
    expect(refusedInState(busy)).toBe('updating')
    expect(explain(busy)).toMatchObject({ title: 'It is updating right now', retryable: true })
    expect(movedOnTo(new ApiError(409, { error: 'resource.busy' }))).toBeUndefined()
    expect(movedOnTo(new ApiError(400, { error: 'x', detail: { version: 3 } }))).toBeUndefined()
    expect(explain(new ApiError(409, { error: 'resource.busy' })).title).toBe('Another change is in flight')
  })
})

describe('the edit as code', () => {
  it('is a PATCH carrying the version, the update workflow’s CLI verb, its platform call and its manifest', () => {
    const req = { kind: 'update' as const, type: vm, wfrn: WEB02, body: { input: { memoryGb: 32 }, reason: 'more room' }, action: vm.update, version: 3, context: { accountId: '123456789012', region: 'global', origin: 'https://console.example.test' } }
    const samples = codeSamplesFor(req)
    expect(samples[0].code).toContain(`iaf compute update-vm ${WEB02}`)
    expect(samples[0].code).toContain('--memory-gb 32')
    const rest = samples[1].code
    expect(rest).toContain(`curl -X PATCH https://console.example.test/console/v1/r/vm/${encodeURIComponent(WEB02)}`)
    expect(rest).toContain("'If-Match: 3'")
    expect(rest).toContain('curl -X PATCH https://console.example.test/api/v1/vms/vm-demo03')
    // the platform call names the VM it changes; the console call does not need to
    expect(rest).toContain('"vmId": "vm-demo03"')
    expect(rest.split('# straight from the platform')[0]).not.toContain('vmId')
    const yaml = workflowRunYaml(req)
    expect(yaml).toContain('  workflow: update-vm')
    expect(yaml).toContain(`  subject: "${WEB02}"`)
    expect(yaml).toContain('  reason: more room')
    expect(yaml).toContain('spec:\n  input:\n    memoryGb: 32\n')
  })
})
