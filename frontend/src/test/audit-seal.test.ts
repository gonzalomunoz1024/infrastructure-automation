import { describe, expect, it } from 'vitest'
import { ApiError } from '../lib/api'
import { sealSummary } from '../pages/AuditPage'
import { absoluteUrl, lastCheckpoint, shareRefusal } from '../pages/AuditRecordPage'

describe('a seal', () => {
  it('says how many records it sealed and checkpointed, and with which key', () => {
    expect(sealSummary({ sealed: 3, checkpointed: 2, keyId: 'audit-2026-10' })).toBe(
      'Sealed 3 closed records and checkpointed 2 open ones with key audit-2026-10. The seal also runs hourly.',
    )
    expect(sealSummary({ sealed: 1, checkpointed: 0, keyId: 'k1' })).toBe('Sealed 1 closed record with key k1. The seal also runs hourly.')
    expect(sealSummary({ sealed: 0, checkpointed: 1, keyId: 'k1' })).toBe('Checkpointed 1 open record with key k1. The seal also runs hourly.')
    expect(sealSummary({ sealed: 0, checkpointed: 0, keyId: 'k1' })).toBe('Nothing new to sign: every record was already sealed or checkpointed. The seal also runs hourly.')
  })
})

describe("an open record's checkpoint", () => {
  const seal = (at: string, seq: number) => ({ at, signature: `hmac-${seq}`, keyId: 'k1', seq, hash: `hash-${seq}` })

  it('is the latest checkpoint while the record is open', () => {
    expect(lastCheckpoint({ checkpoints: [seal('2026-10-18T11:00:00Z', 5), seal('2026-10-18T10:00:00Z', 3)] })?.seq).toBe(5)
    expect(lastCheckpoint({ checkpoints: [] })).toBeUndefined()
    expect(lastCheckpoint({})).toBeUndefined()
  })

  it('gives way to the seal once the job closed', () => {
    expect(lastCheckpoint({ sealed: seal('2026-10-18T12:00:00Z', 6), checkpoints: [seal('2026-10-18T11:00:00Z', 5)] })).toBeUndefined()
  })
})

describe('a share link', () => {
  it("is the API's path on the origin that serves the UI", () => {
    expect(absoluteUrl('/api/v1/audit/shared/tok-example', 'https://iaf.example.com')).toBe('https://iaf.example.com/api/v1/audit/shared/tok-example')
    expect(absoluteUrl('https://evidence.example.com/x', 'https://iaf.example.com')).toBe('https://evidence.example.com/x')
    expect(absoluteUrl('/api/v1/audit/shared/tok-example', '')).toBe('/api/v1/audit/shared/tok-example')
  })

  it("reads a 403 as the backend's answer, and anything else as a failure", () => {
    const refusal = 'ana may not share the evidence of job-1: only its requester (gonzalo), a member of compute or an admin may'
    expect(shareRefusal(new ApiError(403, { error: refusal }))).toBe(refusal)
    expect(shareRefusal(new ApiError(500, { error: 'unexpected error' }))).toBeNull()
    expect(shareRefusal(new Error('offline'))).toBeNull()
  })
})
