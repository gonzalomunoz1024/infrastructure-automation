import { parse } from 'yaml'
import { describe, expect, it } from 'vitest'
import type { JsonObject, WorkflowDocument } from '@iaf/workflow-checks'
import { documentToGraph, graphToDocument } from '../designer/model/graph'
import { readDocField, writeDocField } from '../designer/ndv/fields'

/*
 * The API settings the designer edits beyond method and path: `api.deprecated` and
 * `api.response.body` survive open-and-save unchanged, and the Settings fields read and write
 * them where the checks expect them.
 */

const FIXTURE_FILES = import.meta.glob('../../../packages/workflow-checks/fixtures/*.yaml', { query: '?raw', import: 'default', eager: true }) as Record<string, string>

/** A conformance fixture of the checks, loaded with the yaml package (the browser never does this). */
function fixture(name: string): WorkflowDocument {
  const entry = Object.entries(FIXTURE_FILES).find(([path]) => path.endsWith(`/${name}`))
  if (!entry) throw new Error(`no fixture ${name}`)
  return parse(entry[1]) as WorkflowDocument
}

describe('round trips', () => {
  it('keeps api.deprecated as written', () => {
    const doc = fixture('deprecated-api.yaml')
    const out = graphToDocument(documentToGraph(doc), doc)
    expect(out).toEqual(doc)
    expect(out.api?.deprecated).toEqual({ since: '2026-11-01', sunset: '2026-10-01', replacement: 'vm-info-v3' })
  })
  it('keeps api.response.body as written', () => {
    const doc = fixture('response-body.yaml')
    const out = graphToDocument(documentToGraph(doc), doc)
    expect(out).toEqual(doc)
    expect(out.api?.response).toEqual({ status: 202, body: { type: 'object', properties: { powerState: { type: 'string' }, host: { type: 'string' } } } })
  })
})

describe('the settings fields', () => {
  it('read and write api.deprecated.* without touching the rest of api', () => {
    const doc = fixture('response-body.yaml') as unknown as JsonObject
    expect(readDocField(doc, 'api.deprecated.since')).toBeUndefined()
    writeDocField(doc, 'api.deprecated.since', '2026-11-01')
    writeDocField(doc, 'api.deprecated.sunset', '2027-05-01')
    writeDocField(doc, 'api.deprecated.replacement', '/api/v2/vms/{vmId}:info')
    expect(readDocField(doc, 'api.deprecated')).toEqual({ since: '2026-11-01', sunset: '2027-05-01', replacement: '/api/v2/vms/{vmId}:info' })
    expect(readDocField(doc, 'api.response.status')).toBe(202)
    writeDocField(doc, 'api.deprecated.replacement', undefined)
    expect(readDocField(doc, 'api.deprecated')).toEqual({ since: '2026-11-01', sunset: '2027-05-01' })
  })
  it('read and write api.response.body as a schema', () => {
    const doc = fixture('deprecated-api.yaml') as unknown as JsonObject
    expect(readDocField(doc, 'api.response.body')).toBeUndefined()
    writeDocField(doc, 'api.response.body', { type: 'object', properties: { host: { type: 'string' } } })
    expect(readDocField(doc, 'api.response')).toEqual({ body: { type: 'object', properties: { host: { type: 'string' } } } })
    writeDocField(doc, 'api.response.body', undefined)
    expect(readDocField(doc, 'api.response')).toEqual({})
    expect(readDocField(doc, 'api.deprecated.replacement')).toBe('vm-info-v3')
  })
})
