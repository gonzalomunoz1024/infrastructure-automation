import { describe, expect, it } from 'vitest'
import { buildMockData } from '../mock/data'
import { composeTypes, type ConsoleAction, type ConsoleType } from './descriptors'
import { CLI, cliVerb, codeSamplesFor, platformPath, subjectFor, workflowRunYaml } from './code'

/* View as code (WP 3.5): the same request as a CLI line, two REST calls, a WorkflowRun manifest and JSON. */

const data = buildMockData()
const types = composeTypes(data.resourceTypes, data.catalog)
const vm = types.find((t) => t.name === 'vm') as ConsoleType
const action = (verb: string) => vm.actions.find((a) => a.verb === verb) as ConsoleAction
const context = { accountId: '123456789012', region: 'global', origin: 'https://console.example.test' }
const WFRN = 'wfrn:wf:compute:global:123456789012:vm/vm-demo01'

describe('the words of the CLI and the paths', () => {
  it('names the CLI verb after the type', () => {
    expect(CLI).toBe('iaf')
    expect(cliVerb('create', vm)).toBe('create-vm')
    expect(cliVerb('restart', vm)).toBe('restart-vm')
  })

  it('fills the subject into the platform’s path', () => {
    expect(platformPath(action('restart'), 'vm-demo01')).toBe('/api/v1/vms/vm-demo01:restart')
    expect(platformPath(action('delete'))).toBe('/api/v1/vms/<id>')
  })

  it('finds the input that names the resource a verb acts on', () => {
    expect(subjectFor(action('restart'), vm)).toBe('vmId')
    expect(subjectFor(action('create'), vm)).toBeUndefined()
  })
})

describe('a create', () => {
  const body = { input: { name: 'web-09', image: 'img-ubuntu', size: 'small' }, reason: 'new web tier' }
  const samples = codeSamplesFor({ kind: 'create', type: vm, action: vm.create, body, context })

  it('offers the CLI, REST, the workflow manifest and JSON, in that order', () => {
    expect(samples.map((s) => s.id)).toEqual(['cli', 'rest', 'workflow', 'json'])
    expect(samples.map((s) => s.label)).toEqual(['CLI', 'REST', 'Workflow YAML', 'JSON'])
  })

  it('writes the CLI line with the account, the region and one flag per field', () => {
    const cli = samples[0].code
    expect(cli.startsWith('iaf compute create-vm')).toBe(true)
    for (const flag of ['--account 123456789012', '--region global', '--name web-09', '--image img-ubuntu', '--size small', "--reason 'new web tier'"]) expect(cli).toContain(flag)
  })

  it('writes the call through the console and the platform’s own, each with a fresh key', () => {
    const rest = samples[1].code
    expect(rest).toContain('curl -X POST https://console.example.test/console/v1/r/vm')
    expect(rest).toContain('curl -X POST https://console.example.test/api/v1/vms')
    expect(rest).toContain("-H 'X-On-Behalf-Of: account:123456789012'")
    expect(rest.match(/Idempotency-Key: <one fresh key per submission>/g)).toHaveLength(2)
    expect(rest).toContain('"name": "web-09"')
  })

  it('writes a WorkflowRun manifest with no subject, and the body as JSON', () => {
    const yaml = samples[2].code
    for (const line of ['apiVersion: iaf.platform/v1', 'kind: WorkflowRun', '  workflow: create-vm', '  account: "123456789012"', '  region: global', '  reason: new web tier', 'spec:', '    name: web-09']) expect(yaml).toContain(line)
    expect(yaml).not.toContain('subject:')
    expect(JSON.parse(samples[3].code)).toEqual(body)
  })
})

describe('a verb', () => {
  const body = { input: { reason: 'decommissioned after the migration', keepSnapshotDays: 7 }, confirm: true }
  const samples = codeSamplesFor({ kind: 'verb', type: vm, action: action('delete'), wfrn: WFRN, body, context })

  it('names the resource on the CLI and confirms a destructive verb', () => {
    const cli = samples[0].code
    expect(cli.startsWith(`iaf compute delete-vm ${WFRN}`)).toBe(true)
    expect(cli).toContain('--keep-snapshot-days 7')
    expect(cli).toContain("--reason 'decommissioned after the migration'")
    expect(cli).toContain('--confirm')
    // a long line wraps one flag per line
    expect(cli).toContain(' \\\n  --account 123456789012')
  })

  it('posts the verb to the console and puts the subject back on the platform’s call', () => {
    const rest = samples[1].code
    expect(rest).toContain(`https://console.example.test/console/v1/r/vm/${encodeURIComponent(WFRN)}:delete`)
    expect(rest).toContain('curl -X DELETE https://console.example.test/api/v1/vms/vm-demo01')
    expect(rest).toContain('"vmId": "vm-demo01"')
    expect(rest).toContain('"confirm": true')
  })

  it('carries the subject and the confirmation in the manifest', () => {
    const yaml = samples[2].code
    expect(yaml).toContain(`  subject: "${WFRN}"`)
    expect(yaml).toContain('  workflow: delete-vm')
    expect(yaml).toContain('  confirm: true')
  })
})

describe('an update and a record', () => {
  it('patches through the console, which has the only path for it', () => {
    const req = { kind: 'update' as const, type: vm, wfrn: WFRN, body: { input: { size: 'large' } }, context }
    const rest = codeSamplesFor(req)[1].code
    expect(rest).toContain(`curl -X PATCH https://console.example.test/console/v1/r/vm/${encodeURIComponent(WFRN)}`)
    expect(rest).toContain('no direct path of its own')
    expect(workflowRunYaml(req)).toContain('  workflow: update-vm')
  })

  it('shows a record as JSON, YAML, the CLI’s get and both reads', () => {
    const record = { id: 'vm-demo01', name: 'web-01', attributes: { size: 'small', disks: [] } }
    const samples = codeSamplesFor({ kind: 'record', type: vm, wfrn: WFRN, record, context })
    expect(samples.map((s) => s.id)).toEqual(['json', 'yaml', 'cli', 'rest'])
    expect(JSON.parse(samples[0].code)).toEqual(record)
    expect(samples[1].code).toContain('name: web-01')
    expect(samples[1].code).toContain('  disks: []')
    expect(samples[2].code.startsWith(`iaf compute get-vm ${WFRN}`)).toBe(true)
    expect(samples[3].code).toContain(`curl -X GET https://console.example.test/console/v1/r/vm/${encodeURIComponent(WFRN)}`)
    expect(samples[3].code).toContain('curl -X GET https://console.example.test/api/v1/vms/vm-demo01')
  })
})

describe('a sensitive value', () => {
  it('never reaches the code: the type’s sensitive fields and the workflow’s are both masked', () => {
    const secretive: ConsoleType = { ...vm, sensitive: ['adminPassword'] }
    const body = { input: { name: 'web-09', adminPassword: 'hunter2', apiToken: 's3cr3t' } }
    const create: ConsoleAction = { ...(vm.create as ConsoleAction), input: { type: 'object', properties: { name: { type: 'string' }, adminPassword: { type: 'string' }, apiToken: { type: 'string', 'x-console': { sensitive: true } } } } }
    const samples = codeSamplesFor({ kind: 'create', type: secretive, action: create, body, context })
    for (const s of samples) {
      expect(s.code).not.toContain('hunter2')
      expect(s.code).not.toContain('s3cr3t')
    }
    expect(samples[0].code).toContain("--admin-password '<sensitive: fill in>'")
    expect(samples[3].code).toContain('"name": "web-09"')
    // the form keeps what was typed
    expect(body.input.adminPassword).toBe('hunter2')
  })
})
