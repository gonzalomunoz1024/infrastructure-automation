import { describe, expect, it } from 'vitest'
import { buildProviderDocument, initialState, specBlock, specImportRequest, stepIssues, type WizardState } from '../pages/ProviderWizardPage'
import { ansibleImportRequest, blankImportForm, importIssues, importedPatch, nameFrom, terraformImportRequest } from '../pages/ProviderImport'
import type { AnsibleImport, TerraformImport } from '../lib/types'

function openapi(patch: Partial<WizardState>): WizardState {
  const s = initialState()
  return { ...s, type: 'openapi', title: 'Backup service', name: 'backup-service', owner: 'team-platform', specSha256: 'f'.repeat(64), auth: { ...s.auth, type: 'none' }, ...patch }
}

describe('the spec source of an openapi provider', () => {
  it('pins a spec read from Git to its commit, and keeps the URL for one read from a URL', () => {
    const git = openapi({ specMode: 'git', specGit: { repo: ' https://github.com/acme/backup-api ', ref: 'v1.0.0', path: 'openapi.yaml', credential: 'vault:backup/git-token' }, specCommit: 'a'.repeat(40) })
    expect(specBlock(git)).toEqual({ git: { repo: 'https://github.com/acme/backup-api', ref: 'v1.0.0', path: 'openapi.yaml', credential: 'vault:backup/git-token' }, commit: 'a'.repeat(40), sha256: 'f'.repeat(64) })
    const doc = buildProviderDocument(git).spec as { spec: { git: { repo: string }; url?: string } }
    expect(doc.spec.git.repo).toBe('https://github.com/acme/backup-api')
    expect(doc.spec.url).toBeUndefined()

    expect(specBlock(openapi({ specMode: 'url', specUrl: 'https://backup.example.com/openapi.json' }))).toEqual({ url: 'https://backup.example.com/openapi.json', sha256: 'f'.repeat(64) })
    // pasted or uploaded, the file carries only the hash
    expect(specBlock(openapi({ specMode: 'upload', specContent: 'openapi: 3.0.0', specFile: 'openapi.yaml' }))).toEqual({ url: undefined, sha256: 'f'.repeat(64) })
  })

  it('leaves blank Git coordinates out of the import request', () => {
    expect(specImportRequest(openapi({ specMode: 'git', specGit: { repo: 'https://github.com/acme/backup-api', ref: '', path: '', credential: '' } }))).toEqual({ type: 'openapi', git: { repo: 'https://github.com/acme/backup-api', ref: undefined, path: undefined, credential: undefined } })
    expect(specImportRequest(openapi({ specMode: 'upload', specContent: 'openapi: 3.0.0' }))).toEqual({ type: 'openapi', content: 'openapi: 3.0.0' })
    expect(specImportRequest(openapi({ specMode: 'url', specUrl: ' https://backup.example.com/openapi.json ' }))).toEqual({ type: 'openapi', url: 'https://backup.example.com/openapi.json' })
  })

  it('asks for what each source needs before the connect step goes on', () => {
    const withEnv = { environments: [{ key: 1, name: 'dev', url: 'https://backup-dev.example.com/api' }] } as Partial<WizardState>
    expect(stepIssues(openapi({ specMode: 'git', ...withEnv }), 1)).toEqual(['Name the https repository the spec lives in.'])
    expect(stepIssues(openapi({ specMode: 'git', specGit: { repo: 'git@github.com:acme/backup-api.git', ref: 'main', path: '', credential: '' }, ...withEnv }), 1)).toEqual(['Name the https repository the spec lives in.'])
    expect(stepIssues(openapi({ specMode: 'git', specGit: { repo: 'https://github.com/acme/backup-api', ref: 'main', path: '', credential: 'ghp_not_a_reference' }, ...withEnv }), 1)).toEqual(['The read token is a vault: reference.'])
    expect(stepIssues(openapi({ specMode: 'git', specGit: { repo: 'https://github.com/acme/backup-api', ref: 'main', path: '', credential: 'vault:backup/git-token' }, ...withEnv }), 1)).toEqual([])
    expect(stepIssues(openapi({ specMode: 'upload', ...withEnv }), 1)).toEqual(['Upload the spec file.'])
    expect(stepIssues(openapi({ specMode: 'url', ...withEnv }), 1)).toEqual(['Point at the spec: its URL.'])
  })
})

const ANSIBLE: AnsibleImport = {
  repo: 'https://github.com/acme/ops-playbooks',
  ref: 'v3.1.0',
  commit: 'a'.repeat(40),
  playbooks: [],
  chosen: [],
  requirements: [],
  skipped: [],
  notes: [],
  yaml: 'kind: Provider\nspec:\n  type: ansible\n',
}
const TERRAFORM: TerraformImport = { source: 'acme/lb/aws', version: '2.3.0', notes: [], yaml: 'kind: Provider\nspec:\n  type: terraform\n', variables: [], outputs: [] }

describe('the Ansible and Terraform imports', () => {
  it('sends the repository with the playbooks picked, and nothing blank', () => {
    const f = { ...blankImportForm('team-platform'), repo: ' https://github.com/acme/ops-playbooks ', ref: 'v3.1.0', path: 'playbooks/', readCredential: 'vault:ops/git-token', playbooks: 'vm/restart.yml, vm/snapshot.yml\n', credential: 'vault:ops/ssh', executionEnvironment: 'ee-platform:2.17' }
    expect(ansibleImportRequest(f)).toEqual({
      git: { repo: 'https://github.com/acme/ops-playbooks', ref: 'v3.1.0', path: 'playbooks/', credential: 'vault:ops/git-token' },
      playbooks: ['vm/restart.yml', 'vm/snapshot.yml'],
      name: undefined,
      owner: 'team-platform',
      credential: 'vault:ops/ssh',
      executionEnvironment: 'ee-platform:2.17',
    })
    expect(ansibleImportRequest({ ...blankImportForm(), repo: 'https://github.com/acme/ops-playbooks', ref: '' }).git).toEqual({ repo: 'https://github.com/acme/ops-playbooks', ref: undefined, path: undefined, credential: undefined })
    expect(ansibleImportRequest(blankImportForm()).playbooks).toBeUndefined()
  })

  it('sends a Terraform module from Git or from the registry, never both', () => {
    const git = { ...blankImportForm('team-platform'), repo: 'https://github.com/acme/tf-modules', ref: 'v2.3.0', path: 'modules/lb', credential: 'vault:aws/runner' }
    expect(terraformImportRequest(git)).toEqual({ git: { repo: 'https://github.com/acme/tf-modules', ref: 'v2.3.0', path: 'modules/lb', credential: undefined }, name: undefined, owner: 'team-platform', credential: 'vault:aws/runner' })
    const registry = { ...git, source: 'registry' as const, module: 'acme/lb/aws', version: '2.3.0', registryCredential: 'vault:tf/registry-token', name: 'lb' }
    expect(terraformImportRequest(registry)).toEqual({ registry: { module: 'acme/lb/aws', version: '2.3.0', credential: 'vault:tf/registry-token' }, name: 'lb', owner: 'team-platform', credential: 'vault:aws/runner' })
    expect('git' in terraformImportRequest(registry)).toBe(false)
  })

  it('says what is missing before asking the backend', () => {
    expect(importIssues('ansible', blankImportForm())).toEqual(['Name the https repository.', 'Say which team owns it.'])
    expect(importIssues('terraform', { ...blankImportForm('team-platform'), source: 'registry', module: 'acme/lb' })).toEqual(['A registry module is namespace/name/provider.'])
    expect(importIssues('terraform', { ...blankImportForm('team-platform'), source: 'registry', module: 'acme/lb/aws', name: 'LB module' })).toEqual(['The name is lowercase letters, digits and dashes.'])
    expect(importIssues('ansible', { ...blankImportForm('team-platform'), repo: 'https://github.com/acme/ops-playbooks' })).toEqual([])
  })

  it('names the provider after the repository or the module unless told otherwise', () => {
    expect(nameFrom('https://github.com/acme/Ops_Playbooks.git')).toBe('ops-playbooks')
    expect(nameFrom('acme/lb/aws')).toBe('aws')
    expect(nameFrom('')).toBe('')
  })

  it('hands the wizard the draft as its document, with the source as read', () => {
    const ansible = importedPatch('ansible', ANSIBLE, { ...blankImportForm('team-platform'), repo: 'https://github.com/acme/ops-playbooks', path: 'playbooks/', credential: 'vault:ops/ssh', executionEnvironment: 'ee-platform:2.17' })
    expect(ansible).toMatchObject({ type: 'ansible', name: 'ops-playbooks', owner: 'team-platform', draftYaml: ANSIBLE.yaml, imported: { kind: 'ansible', result: ANSIBLE } })
    expect(ansible.ansible).toEqual({ repo: ANSIBLE.repo, ref: 'v3.1.0', path: 'playbooks/', commit: 'a'.repeat(40), executionEnvironment: 'ee-platform:2.17' })
    expect(ansible.auth?.credential).toBe('vault:ops/ssh')

    const terraform = importedPatch('terraform', TERRAFORM, { ...blankImportForm('team-platform'), source: 'registry', module: 'acme/lb/aws', name: 'lb' })
    expect(terraform).toMatchObject({ type: 'terraform', name: 'lb', terraform: { source: 'acme/lb/aws', version: '2.3.0' }, draftYaml: TERRAFORM.yaml, imported: { kind: 'terraform', result: TERRAFORM } })
  })
})
