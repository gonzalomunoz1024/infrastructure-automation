import { renderToString } from 'react-dom/server'
import { describe, expect, it } from 'vitest'
import type { WorkflowDocument } from '@iaf/workflow-checks'
import { findStepNode } from '../model/graph'
import { EMPTY_REGISTRY, type Registry } from '../model/registry'
import { DesignerContext, DesignerStore } from '../model/store'
import { credentialOptions, gitResolveQuery, StepParameters } from './StepParameters'

/*
 * The git playbook of an ansible step: the repository's read token (`playbook.git.credential`) is
 * its own field and is what the pin sends to GET /playbooks:resolve; the step's `credential` stays
 * the machine credential the playbook reaches the hosts with.
 */

const CREDENTIALS = [
  { name: 'ssh/ops-automation', type: 'ssh-key' },
  { name: 'vsphere/token', type: 'token' },
  { name: 'github/read-token', type: 'token' },
]

const REGISTRY: Registry = { ...EMPTY_REGISTRY, credentials: CREDENTIALS }

const GIT = { repo: 'github.com/acme/ops-playbooks', ref: 'v3.1.0', path: 'vm/restart.yml' }

const restartWith = (playbook: unknown): WorkflowDocument =>
  ({
    apiVersion: 'iaf.platform/v1',
    kind: 'Workflow',
    metadata: { name: 'vm-restart', noun: 'vm', verb: 'restart', owner: 'team-compute', version: '1.0.0' },
    workflow: { do: [{ restart: { name: 'Restart with Ansible', call: 'ansible', with: { playbook, inventory: 'dynamic', credential: 'vault:ssh/ops-automation' } } }] },
  }) as WorkflowDocument

/** The markup of one parameter, from its data-field to the next parameter. */
function field(html: string, name: string): string {
  const start = html.indexOf(`data-field="${name}"`)
  expect(start, `no field ${name}`).toBeGreaterThanOrEqual(0)
  const next = html.indexOf('data-field="', start + 1)
  return html.slice(start, next < 0 ? undefined : next)
}

function render(doc: WorkflowDocument): string {
  const store = new DesignerStore({ name: 'vm-restart', doc, registry: REGISTRY })
  const node = findStepNode(store.getState().graph, 'restart')
  if (!node) throw new Error('no restart step')
  return renderToString(
    <DesignerContext.Provider value={store}>
      <StepParameters stepId="restart" body={node.data.body} step={node.data.step} registry={REGISTRY} doc={store.getState().doc} />
    </DesignerContext.Provider>,
  )
}

describe('the git credential of a playbook', () => {
  it('is what the pin sends to the resolve call, never the machine credential', () => {
    expect(gitResolveQuery({ git: { ...GIT, credential: 'vault:github/read-token' }, commit: '' })).toEqual({ repo: GIT.repo, ref: GIT.ref, credential: 'vault:github/read-token' })
    const open = gitResolveQuery({ git: { ...GIT }, commit: '' })
    expect(open).toEqual({ repo: GIT.repo, ref: GIT.ref })
    expect(open && 'credential' in open).toBe(false)
    expect(gitResolveQuery({ git: { ...GIT, credential: '  ' } })).toEqual({ repo: GIT.repo, ref: GIT.ref })
    expect(gitResolveQuery({ git: { repo: GIT.repo, ref: ' ', path: GIT.path } })).toBeNull()
    expect(gitResolveQuery('ops/vm-restart')).toBeNull()
    expect(gitResolveQuery({ upload: { file: 'x', sha256: 'y' } })).toBeNull()
    expect(gitResolveQuery(undefined)).toBeNull()
  })

  it('offers the vault references of the credentials, the kinds that fit first', () => {
    expect(credentialOptions(CREDENTIALS, ['token', 'basic']).map((o) => o.value)).toEqual(['vault:github/read-token', 'vault:vsphere/token', 'vault:ssh/ops-automation'])
    expect(credentialOptions(CREDENTIALS, ['ssh-key']).map((o) => o.value)).toEqual(['vault:ssh/ops-automation', 'vault:github/read-token', 'vault:vsphere/token'])
    expect(credentialOptions(CREDENTIALS).map((o) => o.label)).toEqual(['github/read-token', 'ssh/ops-automation', 'vsphere/token'])
    expect(credentialOptions(CREDENTIALS)[0]).toEqual({ value: 'vault:github/read-token', label: 'github/read-token', hint: 'token' })
  })

  it('has a picker of its own on a git playbook, beside the machine credential of the hosts', () => {
    const html = render(restartWith({ git: { ...GIT, credential: 'vault:github/read-token' }, commit: '4e1d9a2' }))
    const git = field(html, 'playbook.git.credential')
    expect(git).toContain('Git credential')
    expect(git).toContain('aria-label="Git credential"')
    expect(git).toContain('github/read-token')
    expect(git).not.toContain('ssh/ops-automation')
    const machine = field(html, 'credential')
    expect(machine).toContain('Machine credential')
    expect(machine).toContain('ssh/ops-automation')
    expect(machine).not.toContain('github/read-token')
  })

  it('is left empty for a public repository and is not offered for the other sources', () => {
    const open = field(render(restartWith({ git: { ...GIT }, commit: '4e1d9a2' })), 'playbook.git.credential')
    expect(open).not.toContain('github/read-token')
    expect(open).toContain('Leave it empty for a public repository')
    expect(render(restartWith('ops/vm-restart'))).not.toContain('data-field="playbook.git.credential"')
    expect(render(restartWith({ upload: { file: 'playbooks/abc.tar.gz', sha256: 'abc' } }))).not.toContain('data-field="playbook.git.credential"')
  })
})
