import { useRef, useState } from 'react'
import { Button, Card, Field, Icon, IconButton, Input, Segmented, Select, StatusDot, Textarea, duration, useToast } from '@iaf/ui'
import { api, errorMessage } from '../../lib/api'
import type { SpecImport } from '../../lib/types'
import { CredentialPicker } from '../CredentialPicker'
import { Mono } from '../common'
import { DURATION_RE, ENDPOINT_RE, HTTPS_RE, TASK_QUEUE_RE, VERSION_RE, authOf, authTypeOf, blankAction, endpointsOf, envNames, kebab, nextKey, specImportRequest, type AuthType, type Env, type StepProps } from './state'

/* ---- 2 · Connect --------------------------------------------------------- */

export function ConnectStep({ state, update }: StepProps) {
  const toast = useToast()
  const [busy, setBusy] = useState<'import' | 'test' | null>(null)
  const fileRef = useRef<HTMLInputElement>(null)
  const hasEndpoints = state.type === 'openapi' || state.type === 'http'

  const applySpec = (spec: SpecImport) => {
    const keepEnvs = state.environments.some((e) => e.url.trim())
    update({
      specSha256: spec.sha256,
      specTitle: spec.title,
      title: state.title.trim() || spec.title,
      name: state.nameTouched || state.name ? state.name : kebab(spec.title),
      version: state.version === '1.0.0' && VERSION_RE.test(spec.version) ? spec.version : state.version,
      environments: keepEnvs || spec.servers.length === 0 ? state.environments : envNames(spec.servers).map((name, i) => ({ key: nextKey(), name, url: spec.servers[i] })),
      auth: { ...state.auth, type: authTypeOf(spec.auth?.type), header: spec.auth?.where && !state.auth.header ? spec.auth.where : state.auth.header },
      actions: spec.operations.map((op) =>
        blankAction({
          id: op.operationId,
          title: op.summary || op.operationId,
          operation: op.operationId,
          method: op.method.toUpperCase(),
          path: op.path,
          effect: op.effect,
          retry: op.retry,
          approval: op.effect === 'delete',
          inputText: op.input ? JSON.stringify(op.input, null, 2) : '',
          outputText: op.output ? JSON.stringify(op.output, null, 2) : '',
        }),
      ),
    })
    toast.success('Spec imported', `${spec.operations.length} operations from ${spec.title} ${spec.version}.`)
  }

  const importSpec = async () => {
    setBusy('import')
    try {
      const spec = await api.importSpec(specImportRequest(state))
      applySpec(spec)
      update({ specCommit: spec.commit ?? '' })
    } catch (e) {
      toast.error('Could not import the spec', errorMessage(e))
    } finally {
      setBusy(null)
    }
  }

  const uploadSpec = (file: File | undefined) => {
    if (!file) return
    void file.text().then((content) => update({ specContent: content, specFile: file.name, specSha256: '', specCommit: '' }))
  }
  const canImport = state.specMode === 'url' ? !!state.specUrl.trim() : state.specMode === 'git' ? HTTPS_RE.test(state.specGit.repo.trim()) : !!state.specContent.trim()

  const test = async () => {
    setBusy('test')
    try {
      // the results are kept as review evidence for the change request: an edit's, or a new provider's for its owner team
      const evidence = state.editing ? { provider: state.editing } : state.name ? { provider: state.name, owner: state.owner } : {}
      const result = await api.testConnection({ endpoints: endpointsOf(state), auth: authOf(state), ...evidence })
      update({ tested: result })
      const bad = result.results.filter((r) => !r.ok).length
      if (bad) toast.toast({ title: `${bad} of ${result.results.length} environments did not answer`, tone: 'warning' })
      else toast.success('Every environment answered')
    } catch (e) {
      toast.error('Test failed', errorMessage(e))
    } finally {
      setBusy(null)
    }
  }

  const setEnv = (key: number, patch: Partial<Env>) => update({ environments: state.environments.map((e) => (e.key === key ? { ...e, ...patch } : e)), tested: null })
  const canTest = hasEndpoints && Object.keys(endpointsOf(state)).length > 0 && (state.auth.type === 'none' || !!state.auth.credential)

  return (
    <div className="space-y-5">
      {state.type === 'openapi' && !state.editing && (
        <Card
          title="Spec"
          subtitle="The platform reads the spec, pins its hash at publish and never edits it. From Git it also pins the commit it read."
          actions={
            <Segmented
              ariaLabel="Spec source"
              size="sm"
              value={state.specMode}
              onChange={(m) => update({ specMode: m })}
              options={[
                { value: 'url', label: 'URL' },
                { value: 'paste', label: 'Paste' },
                { value: 'upload', label: 'Upload' },
                { value: 'git', label: 'Git' },
              ]}
            />
          }
        >
          <div className="space-y-3">
            {state.specMode === 'url' && (
              <Field label="Spec URL" htmlFor="spec-url" required>
                <Input id="spec-url" mono value={state.specUrl} onChange={(e) => update({ specUrl: e.target.value })} placeholder="https://vcenter.example.com/rest/openapi.json" />
              </Field>
            )}
            {state.specMode === 'paste' && (
              <Field label="Spec (JSON or YAML)" htmlFor="spec-content" required>
                <Textarea id="spec-content" mono rows={8} value={state.specContent} onChange={(e) => update({ specContent: e.target.value })} placeholder="openapi: 3.0.0 …" />
              </Field>
            )}
            {state.specMode === 'upload' && (
              <Field label="Spec file" hint="A JSON or YAML file from your machine; the platform keeps the text, not the file">
                <div className="flex flex-wrap items-center gap-3">
                  <input ref={fileRef} type="file" accept=".json,.yaml,.yml,application/json,application/yaml,text/yaml" className="hidden" aria-label="Spec file" onChange={(e) => uploadSpec(e.target.files?.[0])} />
                  <Button icon="upload" onClick={() => fileRef.current?.click()}>
                    Choose a file
                  </Button>
                  {state.specFile ? (
                    <span className="text-caption text-secondary">
                      <Mono className="text-primary">{state.specFile}</Mono> · {state.specContent.length.toLocaleString()} characters
                    </span>
                  ) : (
                    <span className="text-caption text-tertiary">Nothing chosen yet.</span>
                  )}
                </div>
              </Field>
            )}
            {state.specMode === 'git' && (
              <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
                <Field label="Repository" htmlFor="spec-git-repo" required hint="https only; the token, if any, is a credential, never part of the URL">
                  <Input id="spec-git-repo" mono value={state.specGit.repo} onChange={(e) => update({ specGit: { ...state.specGit, repo: e.target.value } })} placeholder="https://github.com/acme/vcenter-api" invalid={!!state.specGit.repo && !HTTPS_RE.test(state.specGit.repo.trim())} />
                </Field>
                <Field label="Ref" htmlFor="spec-git-ref" hint="A tag, branch or commit; main when empty">
                  <Input id="spec-git-ref" mono value={state.specGit.ref} onChange={(e) => update({ specGit: { ...state.specGit, ref: e.target.value } })} placeholder="v8.0.2" />
                </Field>
                <Field label="Path" htmlFor="spec-git-path" hint="The spec file inside the repository">
                  <Input id="spec-git-path" mono value={state.specGit.path} onChange={(e) => update({ specGit: { ...state.specGit, path: e.target.value } })} placeholder="openapi/vcenter.yaml" />
                </Field>
                <Field label="Read credential" hint="For a private repository: the vault: reference of a read token">
                  <CredentialPicker id="spec-git-credential" ariaLabel="Read credential" value={state.specGit.credential} onChange={(ref) => update({ specGit: { ...state.specGit, credential: ref } })} />
                </Field>
              </div>
            )}
            <div className="flex flex-wrap items-center justify-between gap-3">
              {state.specSha256 ? (
                <span className="inline-flex flex-wrap items-center gap-2 text-caption text-secondary">
                  <StatusDot kind="healthy" />
                  {state.specTitle} · {state.actions.length} operations · <Mono title={state.specSha256}>sha256 {state.specSha256.slice(0, 12)}…</Mono>
                  {state.specCommit && (
                    <Mono title={state.specCommit}>
                      commit {state.specCommit.slice(0, 12)}…
                    </Mono>
                  )}
                </span>
              ) : (
                <span className="text-caption text-tertiary">Import to list the operations and the servers.</span>
              )}
              <Button icon="download" loading={busy === 'import'} disabled={!canImport} onClick={() => void importSpec()}>
                Import spec
              </Button>
            </div>
          </div>
        </Card>
      )}

      {state.type === 'ansible' && !state.editing && (
        <Card title="Source" subtitle="A Git repository pinned to a tag and a commit; the platform runs playbooks with ansible-runner.">
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
            <Field label="Repository" htmlFor="ans-repo" required>
              <Input id="ans-repo" mono value={state.ansible.repo} onChange={(e) => update({ ansible: { ...state.ansible, repo: e.target.value } })} placeholder="github.com/acme/ops-playbooks" />
            </Field>
            <Field label="Ref" htmlFor="ans-ref" hint="A tag or a branch">
              <Input id="ans-ref" mono value={state.ansible.ref} onChange={(e) => update({ ansible: { ...state.ansible, ref: e.target.value } })} placeholder="v3.1.0" />
            </Field>
            <Field label="Path" htmlFor="ans-path" hint="Folder inside the repository, if not the root">
              <Input id="ans-path" mono value={state.ansible.path} onChange={(e) => update({ ansible: { ...state.ansible, path: e.target.value } })} placeholder="playbooks/" />
            </Field>
            <Field label="Commit" htmlFor="ans-commit" hint="Pinned at publish when empty">
              <Input id="ans-commit" mono value={state.ansible.commit} onChange={(e) => update({ ansible: { ...state.ansible, commit: e.target.value } })} placeholder="4e1d9a2c…" />
            </Field>
            <Field label="Execution environment" htmlFor="ans-ee" className="sm:col-span-2">
              <Input id="ans-ee" mono value={state.ansible.executionEnvironment} onChange={(e) => update({ ansible: { ...state.ansible, executionEnvironment: e.target.value } })} placeholder="ee-platform:2.17" />
            </Field>
          </div>
        </Card>
      )}

      {state.type === 'terraform' && !state.editing && (
        <Card title="Module" subtitle="State lives in the platform's backend, one workspace per job.">
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-[minmax(0,1fr)_10rem]">
            <Field label="Source" htmlFor="tf-source" required>
              <Input id="tf-source" mono value={state.terraform.source} onChange={(e) => update({ terraform: { ...state.terraform, source: e.target.value } })} placeholder="git::https://github.com/acme/tf-modules//lb" />
            </Field>
            <Field label="Version" htmlFor="tf-version">
              <Input id="tf-version" mono value={state.terraform.version} onChange={(e) => update({ terraform: { ...state.terraform, version: e.target.value } })} placeholder="2.3.0" />
            </Field>
          </div>
        </Card>
      )}

      {state.type === 'idp' && !state.editing && (
        <Card title="Tile" subtitle="A mock catalog answers until the portal's tiles API is wired.">
          <Field label="Tile id" htmlFor="idp-tile" required>
            <Input id="idp-tile" mono value={state.idp.tileId} onChange={(e) => update({ idp: { tileId: e.target.value } })} placeholder="block-storage-request" />
          </Field>
        </Card>
      )}

      {state.type === 'sdk' && !state.editing && (
        <Card title="Worker" subtitle="Your team runs the worker, built with a provider SDK; the platform only schedules the actions on its task queue.">
          <div className="grid gap-4 sm:grid-cols-2">
            <Field label="Task queue" htmlFor="sdk-queue" hint="Leave empty for the default" error={state.sdk.taskQueue.trim() && !TASK_QUEUE_RE.test(state.sdk.taskQueue.trim()) ? 'Letters, digits, dots, dashes and underscores' : undefined}>
              <Input id="sdk-queue" mono value={state.sdk.taskQueue} onChange={(e) => update({ sdk: { ...state.sdk, taskQueue: e.target.value } })} placeholder={`provider-${state.name || '<name>'}`} />
            </Field>
            <Field label="Heartbeat timeout" htmlFor="sdk-heartbeat" hint="A call is lost after this long without a heartbeat" error={state.sdk.heartbeatTimeout.trim() && !DURATION_RE.test(state.sdk.heartbeatTimeout.trim()) ? 'A duration like 30s or 2m' : undefined}>
              <Input id="sdk-heartbeat" mono value={state.sdk.heartbeatTimeout} onChange={(e) => update({ sdk: { ...state.sdk, heartbeatTimeout: e.target.value } })} placeholder="30s" />
            </Field>
          </div>
        </Card>
      )}

      {hasEndpoints && (
        <Card
          title="Environments"
          subtitle="One base URL per environment; a workflow picks the environment at run time."
          actions={
            <Button size="sm" icon="plus" onClick={() => update({ environments: [...state.environments, { key: nextKey(), name: state.environments.length === 1 ? 'prod' : '', url: '' }] })}>
              Add environment
            </Button>
          }
        >
          <div className="space-y-2">
            {state.environments.map((e) => (
              <div key={e.key} className="grid grid-cols-[7rem_minmax(0,1fr)_auto] items-center gap-2">
                <Input mono size="sm" value={e.name} onChange={(ev) => setEnv(e.key, { name: ev.target.value })} placeholder="dev" aria-label="Environment name" />
                <Input mono size="sm" value={e.url} onChange={(ev) => setEnv(e.key, { url: ev.target.value })} placeholder="https://system-dev.example.com/api" aria-label="Environment URL" invalid={!!e.url && !ENDPOINT_RE.test(e.url.trim())} />
                <IconButton label="Remove environment" size="sm" disabled={state.environments.length === 1} onClick={() => update({ environments: state.environments.filter((x) => x.key !== e.key), tested: null })}>
                  <Icon name="trash" size={14} />
                </IconButton>
              </div>
            ))}
          </div>
        </Card>
      )}

      <Card title="Authentication" subtitle="Only the credential's name goes in the file; its value stays in the store.">
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          <Field label="Type">
            <Select
              ariaLabel="Authentication type"
              value={state.auth.type}
              onChange={(t) => update({ auth: { ...state.auth, type: t as AuthType }, tested: null })}
              options={[
                { value: 'none', label: 'None', hint: 'Or a credential the runner itself uses' },
                { value: 'bearer', label: 'Bearer token', hint: 'Authorization: Bearer …' },
                { value: 'basic', label: 'Basic', hint: 'user:password' },
                { value: 'header', label: 'Header', hint: 'A named header, like X-Api-Key' },
              ]}
            />
          </Field>
          {state.auth.type === 'header' && (
            <Field label="Header" htmlFor="auth-header" required>
              <Input id="auth-header" mono value={state.auth.header} onChange={(e) => update({ auth: { ...state.auth, header: e.target.value }, tested: null })} placeholder="X-Api-Key" />
            </Field>
          )}
          <Field label="Credential" required={state.auth.type !== 'none'} className={state.auth.type === 'header' ? 'sm:col-span-2' : undefined}>
            <CredentialPicker id="auth-credential" value={state.auth.credential} onChange={(ref) => update({ auth: { ...state.auth, credential: ref }, tested: null })} required={state.auth.type !== 'none'} />
          </Field>
        </div>
        {hasEndpoints && (
          <div className="mt-4 flex flex-wrap items-center justify-between gap-3 border-t border-stroke-light pt-4">
            {state.tested ? (
              <ul className="space-y-1 text-caption" aria-label="Connection results">
                {state.tested.results.map((r) => (
                  <li key={r.environment} className="flex items-center gap-2">
                    <StatusDot kind={r.ok ? 'healthy' : 'critical'} label={r.ok ? 'ok' : 'failed'} />
                    <Mono className="text-primary">{r.environment}</Mono>
                    <span className="tabular-nums text-tertiary">{duration(r.durationMs)}</span>
                    <span className="text-secondary">{r.message}</span>
                  </li>
                ))}
              </ul>
            ) : (
              <span className="text-caption text-tertiary">Signs in to every environment and asks for something harmless.</span>
            )}
            <Button icon="pulse" loading={busy === 'test'} disabled={!canTest} onClick={() => void test()}>
              Test connection
            </Button>
          </div>
        )}
      </Card>
    </div>
  )
}
