import { describe, expect, it } from 'vitest'
import { parse } from 'yaml'
import { emitYaml, needsQuotes } from '../components/yaml-emit'

const roundTrip = (value: unknown) => parse(emitYaml(value))

describe('emitYaml', () => {
  it('writes scalars the way YAML reads them back', () => {
    expect(emitYaml('plain')).toBe('plain\n')
    expect(emitYaml(12)).toBe('12\n')
    expect(emitYaml(true)).toBe('true\n')
    expect(emitYaml(null)).toBe('null\n')
    expect(emitYaml({})).toBe('{}\n')
    expect(emitYaml([])).toBe('[]\n')
  })

  it('quotes strings that would read as something else', () => {
    for (const s of ['', ' lead', 'trail ', 'yes', 'no', 'on', 'off', 'null', '~', 'true', 'False', '12', '1.5', '1e3', '0x1f', '2026-10-17', '- dash', '#hash', 'a: b', 'a #b', '{{ $input.id }}', '[x]', 'ends:', 'tab\there', 'line\nbreak', 'quote"d', 'back\\slash', '@at', '`tick', '%pct', '*star', '&amp', '!bang', '|pipe', '>gt', '?q', ',comma', "'single"]) {
      expect(needsQuotes(s), s).toBe(true)
      expect(parse(emitYaml(s)), s).toBe(s)
    }
    for (const s of ['plain', 'vault:ssh/ops-automation', 'vm.get', 'github.com/acme/ops-playbooks', 'team-compute', 'ee-platform:2.17', 'v3.1.0', 'https://x.example.com/api', 'Restart a VM after checks', 'CVE-2026-1234', 'a:b']) {
      expect(needsQuotes(s), s).toBe(false)
      expect(parse(emitYaml(s)), s).toBe(s)
    }
  })

  it('nests maps and lists with two-space indentation', () => {
    const yaml = emitYaml({
      metadata: { name: 'vsphere', tags: ['a', 'b'] },
      spec: { endpoints: { dev: 'https://dev.example.com' }, actions: [{ id: 'vm.get', effect: 'read', input: { type: 'object', required: ['id'] } }, { id: 'vm.list' }] },
    })
    expect(yaml).toBe(
      [
        'metadata:',
        '  name: vsphere',
        '  tags:',
        '    - a',
        '    - b',
        'spec:',
        '  endpoints:',
        '    dev: https://dev.example.com',
        '  actions:',
        '    - id: vm.get',
        '      effect: read',
        '      input:',
        '        type: object',
        '        required:',
        '          - id',
        '    - id: vm.list',
        '',
      ].join('\n'),
    )
  })

  it('round-trips a provider document through the yaml package', () => {
    const doc = {
      apiVersion: 'iaf.platform/v1',
      kind: 'Provider',
      metadata: { name: 'backup', title: 'Backup service', owner: 'team-platform', version: '1.0.0', description: 'No OpenAPI spec; endpoints: one example each #really' },
      spec: {
        type: 'http',
        endpoints: { dev: 'https://backup-dev.example.com/api', prod: 'https://backup.example.com/api' },
        auth: { type: 'header', header: 'X-Api-Key', credential: 'vault:backup/api-key' },
        actions: [
          {
            id: 'jobs.list',
            operation: { method: 'GET', path: '/jobs?target={target}&state={state}' },
            effect: 'read',
            retry: 'safe',
            approval: false,
            input: { type: 'object', properties: { target: { type: 'string' }, state: { type: 'string', enum: ['running', 'done'] } } },
            sample: { items: [], total: 0, ok: true, ratio: 0.5, note: null },
          },
          { id: 'jobs.create', effect: 'create', retry: 'unsafe', example: { id: 'bk-9001', nested: [[1, 2], [3]], empty: {} } },
        ],
        errors: [{ match: { status: 429 }, class: 'rate_limited', retry: true }],
        slo: { successRate: 99.5, p95Ms: 1200 },
        alerts: { channel: '#platform-alerts' },
        'x-odd key': 'with: colon',
        multiline: 'first line\nsecond line',
        skipped: undefined,
      },
    }
    const back = roundTrip(doc)
    const { skipped: _skipped, ...spec } = doc.spec
    expect(back).toEqual({ ...doc, spec })
    expect(emitYaml(doc)).not.toContain('skipped')
  })

  it('round-trips a workflow skeleton', () => {
    const doc = {
      apiVersion: 'iaf.platform/v1',
      kind: 'Workflow',
      metadata: { name: 'vm-restart', title: 'VM restart', noun: 'vm', verb: 'restart', owner: 'team-compute', folder: 'Compute', version: '0.1.0', tags: [] },
      api: { method: 'POST', input: { type: 'object', required: ['vmId'], properties: { vmId: { type: 'string', 'x-resource': { type: 'vm' } } } }, preview: true },
      workflow: { do: [{ check: { name: 'Check', call: 'provider', with: { provider: 'vsphere', action: 'vm.get', input: { id: '{{ $input.vmId }}' } } } }] },
    }
    expect(roundTrip(doc)).toEqual(doc)
  })

  it('is deterministic', () => {
    const doc = { b: 1, a: [{ y: 2, x: 1 }] }
    expect(emitYaml(doc)).toBe(emitYaml(doc))
  })
})
