/**
 * The two imports of the Add provider wizard: an Ansible repository (one action per playbook,
 * pinned to the commit read) and a Terraform module (from Git or the module registry, plan and
 * apply over its variables). The backend drafts the provider; the wizard opens on the review with
 * the draft's YAML and the import's notes. Nothing is saved until the change request.
 */
import { useState } from 'react'
import { Button, Card, Field, Icon, Input, Segmented, Table, Tag, useToast, type TableColumn } from '@iaf/ui'
import { api, errorMessage } from '../lib/api'
import type { AnsibleImport, AnsibleImportRequest, GitRef, ImportedPlaybook, ProviderImport, TerraformImport, TerraformImportRequest, TerraformOutput, TerraformVariable } from '../lib/types'
import { CredentialPicker } from '../components/CredentialPicker'
import { EffectTag, Mono } from '../components/common'
import type { WizardState } from './ProviderWizardPage'
import { blankImportForm, type ImportForm } from '../components/provider-editor/state'

export { blankImportForm, type ImportForm }

export type ImportKind = 'ansible' | 'terraform'

const HTTPS_RE = /^https:\/\/\S+$/

const trimmed = (s: string): string | undefined => s.trim() || undefined

function gitRef(f: ImportForm): GitRef {
  return { repo: f.repo.trim(), ref: trimmed(f.ref), path: trimmed(f.path), credential: f.readCredential || undefined }
}

/** POST /providers:import-ansible, with nothing blank sent. */
export function ansibleImportRequest(f: ImportForm): AnsibleImportRequest {
  const playbooks = f.playbooks
    .split(/[,\s]+/)
    .map((p) => p.trim())
    .filter(Boolean)
  return { git: gitRef(f), playbooks: playbooks.length ? playbooks : undefined, name: trimmed(f.name), owner: trimmed(f.owner), credential: f.credential || undefined, executionEnvironment: trimmed(f.executionEnvironment) }
}

/** POST /providers:import-terraform: the module's folder in Git, or a registry module at a version. */
export function terraformImportRequest(f: ImportForm): TerraformImportRequest {
  const base = { name: trimmed(f.name), owner: trimmed(f.owner), credential: f.credential || undefined }
  if (f.source === 'registry') return { registry: { module: f.module.trim(), version: trimmed(f.version), credential: f.registryCredential || undefined }, ...base }
  return { git: gitRef(f), ...base }
}

/** What stops the import from being asked for. */
export function importIssues(kind: ImportKind, f: ImportForm): string[] {
  const out: string[] = []
  if (kind === 'ansible' || f.source === 'git') {
    if (!HTTPS_RE.test(f.repo.trim())) out.push('Name the https repository.')
  } else if (!/^[^/\s]+\/[^/\s]+\/[^/\s]+$/.test(f.module.trim())) out.push('A registry module is namespace/name/provider.')
  if (!f.owner.trim()) out.push('Say which team owns it.')
  if (f.name.trim() && !/^[a-z][a-z0-9-]*$/.test(f.name.trim())) out.push('The name is lowercase letters, digits and dashes.')
  return out
}

/** The last path segment of a repository or module, as a provider name. */
export function nameFrom(source: string): string {
  const last = source
    .replace(/\.git$/, '')
    .split(/[/:]/)
    .filter(Boolean)
    .pop()
  return (last ?? '')
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '')
}

/** What the wizard takes from an import: the type, the names, the source as read, and the draft as the document. */
export function importedPatch(kind: ImportKind, result: AnsibleImport | TerraformImport, f: ImportForm): Partial<WizardState> {
  const owner = f.owner.trim()
  if (kind === 'ansible') {
    const r = result as AnsibleImport
    const name = f.name.trim() || nameFrom(r.repo)
    return {
      type: 'ansible',
      name,
      nameTouched: true,
      title: name,
      owner,
      ownerTouched: true,
      auth: { type: 'none', credential: f.credential, header: '' },
      ansible: { repo: r.repo, ref: r.ref, path: f.path.trim(), commit: r.commit, executionEnvironment: f.executionEnvironment.trim() },
      actions: [],
      tries: {},
      samples: {},
      overrides: {},
      draftYaml: r.yaml,
      imported: { kind: 'ansible', result: r },
    }
  }
  const r = result as TerraformImport
  const name = f.name.trim() || nameFrom(f.source === 'registry' ? f.module : r.source)
  return {
    type: 'terraform',
    name,
    nameTouched: true,
    title: name,
    owner,
    ownerTouched: true,
    auth: { type: 'none', credential: f.credential, header: '' },
    terraform: { source: r.source, version: r.version },
    actions: [],
    tries: {},
    samples: {},
    overrides: {},
    draftYaml: r.yaml,
    imported: { kind: 'terraform', result: r },
  }
}

export interface ImportStepProps {
  kind: ImportKind
  defaultOwner: string
  onImported: (patch: Partial<WizardState>) => void
  /** the form as kept elsewhere (the designer's draft), with `onFormChange`; the wizard page leaves both out and the step keeps its own */
  form?: ImportForm | null
  onFormChange?: (form: ImportForm) => void
}

export function ImportStep({ kind, defaultOwner, onImported, form: kept, onFormChange }: ImportStepProps) {
  const toast = useToast()
  const [own, setOwn] = useState<ImportForm>(() => blankImportForm(defaultOwner))
  const form = onFormChange ? (kept ?? blankImportForm(defaultOwner)) : own
  const setForm = onFormChange ?? setOwn
  const [busy, setBusy] = useState(false)
  // the owner arrives with /me, after the form was made
  const f = form.ownerTouched || !defaultOwner ? form : { ...form, owner: defaultOwner }
  const set = (patch: Partial<ImportForm>) => setForm({ ...f, ...patch, ...('owner' in patch ? { ownerTouched: true } : {}) })
  const issues = importIssues(kind, f)
  const git = kind === 'ansible' || f.source === 'git'

  const run = async () => {
    if (issues.length) return
    setBusy(true)
    try {
      if (kind === 'ansible') {
        const result = await api.importAnsible(ansibleImportRequest(f))
        toast.success('Playbooks read', `${result.playbooks.length} of them become actions, pinned to ${result.commit.slice(0, 12)}.`)
        onImported(importedPatch('ansible', result, f))
      } else {
        const result = await api.importTerraform(terraformImportRequest(f))
        toast.success('Module read', `${result.variables.length} variables become the inputs of plan and apply.`)
        onImported(importedPatch('terraform', result, f))
      }
    } catch (e) {
      toast.error(kind === 'ansible' ? 'Could not read the repository' : 'Could not read the module', errorMessage(e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="space-y-5">
      <Card
        title={kind === 'ansible' ? 'Repository' : 'Module'}
        subtitle={kind === 'ansible' ? 'Every playbook under the path becomes an action, with its vars as the input; the file pins the commit read.' : 'Plan and apply over the module’s variables; sensitive ones are given from Vault, never asked as input.'}
        actions={
          kind === 'terraform' && (
            <Segmented
              ariaLabel="Module source"
              size="sm"
              value={f.source}
              onChange={(source) => set({ source })}
              options={[
                { value: 'git', label: 'Git' },
                { value: 'registry', label: 'Registry' },
              ]}
            />
          )
        }
      >
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          {git ? (
            <>
              <Field label="Repository" htmlFor="imp-repo" required hint="https only; a token is a credential, never part of the URL">
                <Input id="imp-repo" mono value={f.repo} onChange={(e) => set({ repo: e.target.value })} placeholder={kind === 'ansible' ? 'https://github.com/acme/ops-playbooks' : 'https://github.com/acme/tf-modules'} invalid={!!f.repo && !HTTPS_RE.test(f.repo.trim())} autoFocus />
              </Field>
              <Field label="Ref" htmlFor="imp-ref" hint="A tag, branch or commit; main when empty">
                <Input id="imp-ref" mono value={f.ref} onChange={(e) => set({ ref: e.target.value })} placeholder="v3.1.0" />
              </Field>
              <Field label="Path" htmlFor="imp-path" hint={kind === 'ansible' ? 'A folder inside the repository; the whole repository when empty' : 'The module’s folder inside the repository'}>
                <Input id="imp-path" mono value={f.path} onChange={(e) => set({ path: e.target.value })} placeholder={kind === 'ansible' ? 'playbooks/' : 'modules/lb'} />
              </Field>
              <Field label="Read credential" hint="For a private repository: the vault: reference of a read token">
                <CredentialPicker id="imp-read-credential" ariaLabel="Read credential" value={f.readCredential} onChange={(readCredential) => set({ readCredential })} />
              </Field>
            </>
          ) : (
            <>
              <Field label="Module" htmlFor="imp-module" required hint="namespace/name/provider in the configured module registry">
                <Input id="imp-module" mono value={f.module} onChange={(e) => set({ module: e.target.value })} placeholder="acme/lb/aws" autoFocus />
              </Field>
              <Field label="Version" htmlFor="imp-version" hint="An exact version; the newest release when empty">
                <Input id="imp-version" mono value={f.version} onChange={(e) => set({ version: e.target.value })} placeholder="2.3.0" />
              </Field>
              <Field label="Registry credential" hint="For a private module: the vault: reference of a registry token" className="sm:col-span-2">
                <CredentialPicker id="imp-registry-credential" ariaLabel="Registry credential" value={f.registryCredential} onChange={(registryCredential) => set({ registryCredential })} />
              </Field>
            </>
          )}
          {kind === 'ansible' && (
            <Field label="Playbooks" htmlFor="imp-playbooks" hint="Paths or action ids to keep, comma-separated; all of them when empty" className="sm:col-span-2">
              <Input id="imp-playbooks" mono value={f.playbooks} onChange={(e) => set({ playbooks: e.target.value })} placeholder="vm/restart.yml, vm/snapshot.yml" />
            </Field>
          )}
        </div>
      </Card>

      <Card title="Name it" subtitle="The file lands at providers/<owner>/<name>.yaml.">
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          <Field label="Name" htmlFor="imp-name" hint="Lowercase, dashes; the repository’s or module’s own name when empty">
            <Input id="imp-name" mono value={f.name} onChange={(e) => set({ name: e.target.value })} placeholder={nameFrom(git ? f.repo : f.module) || 'ops-playbooks'} />
          </Field>
          <Field label="Owner" htmlFor="imp-owner" required hint="The team whose folder it lives in">
            <Input id="imp-owner" mono value={f.owner} onChange={(e) => set({ owner: e.target.value })} placeholder="team-platform" />
          </Field>
          <Field label={kind === 'ansible' ? 'Machine credential' : 'Runner credential'} hint={kind === 'ansible' ? 'What the playbooks connect with; only its vault: name goes in the file' : 'What the runner gives Terraform (the cloud credentials); only its vault: name goes in the file'}>
            <CredentialPicker id="imp-credential" ariaLabel={kind === 'ansible' ? 'Machine credential' : 'Runner credential'} value={f.credential} onChange={(credential) => set({ credential })} />
          </Field>
          {kind === 'ansible' && (
            <Field label="Execution environment" htmlFor="imp-ee" hint="The image that runs the playbooks">
              <Input id="imp-ee" mono value={f.executionEnvironment} onChange={(e) => set({ executionEnvironment: e.target.value })} placeholder="ee-platform:2.17" />
            </Field>
          )}
        </div>
      </Card>

      <div className="flex flex-wrap items-start justify-between gap-3">
        {issues.length > 0 ? (
          <ul className="min-w-0 flex-1 space-y-0.5 text-caption text-secondary" aria-label="Before you continue">
            {issues.map((i) => (
              <li key={i} className="flex items-start gap-1.5">
                <Icon name="info" size={12} className="mt-0.5 shrink-0 text-tertiary" />
                {i}
              </li>
            ))}
          </ul>
        ) : (
          <span />
        )}
        <Button variant="primary" icon="download" loading={busy} disabled={issues.length > 0} onClick={() => void run()}>
          {kind === 'ansible' ? 'Import playbooks' : 'Import module'}
        </Button>
      </div>
    </div>
  )
}

/** What the import read, on the review step: the source as pinned, the backend's notes, and the playbooks or variables the draft is built on. */
export function ImportSummary({ imported }: { imported: ProviderImport }) {
  if (imported.kind === 'ansible') {
    const r = imported.result
    const columns: TableColumn<ImportedPlaybook>[] = [
      { key: 'path', header: 'Playbook', render: (p) => <Mono className="text-primary">{p.path}</Mono> },
      { key: 'action', header: 'Action', render: (p) => <Mono>{p.action}</Mono> },
      { key: 'effect', header: 'Effect', width: '6rem', render: (p) => <EffectTag effect={p.effect} /> },
      { key: 'vars', header: 'Inputs', width: '5rem', numeric: true, render: (p) => <span className="text-primary">{Object.keys(p.vars?.properties ?? {}).length}</span> },
      { key: 'secrets', header: 'From Vault', render: (p) => (p.secrets.length ? <Mono className="text-secondary">{p.secrets.join(', ')}</Mono> : <span className="text-tertiary">—</span>) },
    ]
    return (
      <Card title="Imported from the repository" subtitle={`${r.repo} at ${r.ref}, pinned to ${r.commit.slice(0, 12)}`} flush data-testid="import-summary">
        <div className="px-5 pt-4">
          <Notes notes={r.notes} />
          {r.requirements.length > 0 && (
            <p className="mt-2 text-caption text-secondary">
              Requirements: <Mono>{r.requirements.join(', ')}</Mono>
            </p>
          )}
          {r.skipped.length > 0 && (
            <p className="mt-1 text-caption text-tertiary">
              Skipped: {r.skipped.join('; ')}
            </p>
          )}
        </div>
        <Table columns={columns} rows={r.playbooks} rowKey={(p) => p.path} dense bordered={false} stickyHeader={false} caption="Imported playbooks" className="mt-3" />
      </Card>
    )
  }
  const r = imported.result
  const columns: TableColumn<TerraformVariable>[] = [
    { key: 'name', header: 'Variable', render: (v) => <Mono className="text-primary">{v.name}</Mono> },
    { key: 'type', header: 'Type', width: '7rem', render: (v) => <Mono>{v.type}</Mono> },
    { key: 'required', header: 'Required', width: '6rem', render: (v) => <span className={v.required ? 'text-primary' : 'text-tertiary'}>{v.required ? 'yes' : 'no'}</span> },
    {
      key: 'secret',
      header: 'Given from',
      width: '8rem',
      render: (v) =>
        v.secret || v.sensitive ? (
          <Tag size="sm" tone="warning" outline>
            Vault
          </Tag>
        ) : (
          <span className="text-tertiary">the input</span>
        ),
    },
    { key: 'default', header: 'Default', render: (v) => (v.default === undefined ? <span className="text-tertiary">—</span> : <Mono>{JSON.stringify(v.default)}</Mono>) },
    { key: 'description', header: 'Description', render: (v) => <span className="text-secondary">{v.description ?? ''}</span> },
  ]
  const outputs: TableColumn<TerraformOutput>[] = [
    { key: 'name', header: 'Output', render: (o) => <Mono className="text-primary">{o.name}</Mono> },
    { key: 'sensitive', header: 'Sensitive', width: '6rem', render: (o) => <span className={o.sensitive ? 'text-warning' : 'text-tertiary'}>{o.sensitive ? 'yes' : 'no'}</span> },
    { key: 'description', header: 'Description', render: (o) => <span className="text-secondary">{o.description ?? ''}</span> },
  ]
  return (
    <Card title="Imported from the module" subtitle={`${r.source} ${r.version}${r.commit ? `, pinned to ${r.commit.slice(0, 12)}` : ''}`} flush data-testid="import-summary">
      <div className="px-5 pt-4">
        <Notes notes={r.notes} />
      </div>
      <Table columns={columns} rows={r.variables} rowKey={(v) => v.name} dense bordered={false} stickyHeader={false} caption="Module variables" className="mt-3" />
      {r.outputs.length > 0 && <Table columns={outputs} rows={r.outputs} rowKey={(o) => o.name} dense bordered={false} stickyHeader={false} caption="Module outputs" />}
    </Card>
  )
}

function Notes({ notes }: { notes: string[] }) {
  if (notes.length === 0) return null
  return (
    <ul className="space-y-1 text-caption text-secondary" aria-label="Import notes">
      {notes.map((n, i) => (
        <li key={i} className="flex items-start gap-1.5">
          <Icon name="info" size={12} className="mt-0.5 shrink-0 text-tertiary" />
          {n}
        </li>
      ))}
    </ul>
  )
}
