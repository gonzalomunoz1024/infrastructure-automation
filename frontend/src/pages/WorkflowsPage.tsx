import { useMemo, useState } from 'react'
import {
  Button,
  CodeBlock,
  Field,
  Icon,
  IconButton,
  Input,
  Modal,
  OverlayBody,
  OverlayFooter,
  OverlayHeader,
  PageHeader,
  Segmented,
  Select,
  StateTag,
  Table,
  Tabs,
  Tag,
  cx,
  useOverlayClose,
  useToast,
  type IconName,
  type TableColumn,
} from '@iaf/ui'
import type { PageProps } from '../app/router'
import { navigate, pathFor } from '../app/router'
import { api, errorMessage } from '../lib/api'
import { useMutation, useQuery } from '../lib/queries'
import type { ImportSource, WorkflowSummary } from '../lib/types'
import { ErrorState, Mono, When } from '../components/common'
import { CHANGE_REQUEST_FILTERS, ChangeRequestTable, filterOf, type ChangeRequestFilter } from '../components/ChangeRequestTable'
import { emitYaml } from '../components/yaml-emit'
import { IMPORT_SOURCES, importChoices, importField, importRequest } from '../components/workflow-import'

type StatusFilter = 'all' | 'published' | 'draft'

export type WorkflowsTab = 'workflows' | 'change-requests'
/** `?tab=` as the view: the workflows unless it names the change requests. */
export const workflowsTabOf = (q: string | undefined): WorkflowsTab => (q === 'change-requests' ? q : 'workflows')

const unique = (values: (string | undefined)[]) => Array.from(new Set(values.filter((v): v is string => !!v))).sort()

function hasDraft(w: WorkflowSummary): boolean {
  return w.status === 'draft' || (!!w.publishedVersion && w.latestVersion !== w.publishedVersion)
}

export default function WorkflowsPage({ route }: PageProps) {
  const list = useQuery('workflows', () => api.workflows())
  // the address holds the view and the change requests' status, so a link, a reload or Back lands on them
  const tab = workflowsTabOf(route.query.tab)
  const crFilter = filterOf(route.query.status)
  // the open ones count on the tab from either view; the other statuses are asked for when shown
  const openCrs = useQuery(['change-requests', 'open'], () => api.changeRequests('open'), { refetchInterval: 30_000 })
  const otherCrs = useQuery(['change-requests', crFilter], () => api.changeRequests(crFilter === 'all' ? undefined : crFilter), { enabled: tab === 'change-requests' && crFilter !== 'open' })
  const crs = crFilter === 'open' ? openCrs : otherCrs
  const showChangeRequests = (status: ChangeRequestFilter) => navigate(pathFor('workflows', {}, { tab: 'change-requests', status: status === 'open' ? undefined : status }), { replace: true })
  const switchTab = (t: WorkflowsTab) => (t === 'change-requests' ? showChangeRequests('open') : navigate(pathFor('workflows'), { replace: true }))
  const [q, setQ] = useState('')
  const [folder, setFolder] = useState('')
  const [owner, setOwner] = useState('')
  const [status, setStatus] = useState<StatusFilter>('all')
  const [creating, setCreating] = useState(false)

  const folders = useMemo(() => unique((list.data ?? []).map((w) => w.folder)), [list.data])
  const owners = useMemo(() => unique((list.data ?? []).map((w) => w.owner)), [list.data])

  const rows = useMemo(() => {
    const needle = q.trim().toLowerCase()
    return (list.data ?? []).filter((w) => {
      if (folder && w.folder !== folder) return false
      if (owner && w.owner !== owner) return false
      if (status === 'published' && !w.publishedVersion) return false
      if (status === 'draft' && !hasDraft(w)) return false
      if (needle && !`${w.title} ${w.name} ${w.noun} ${w.verb} ${w.tags.join(' ')}`.toLowerCase().includes(needle)) return false
      return true
    })
  }, [list.data, q, folder, owner, status])

  const columns: TableColumn<WorkflowSummary>[] = [
    {
      key: 'title',
      header: 'Workflow',
      sortable: true,
      accessor: (w) => w.title,
      render: (w) => (
        <div className="min-w-0">
          <div className="flex items-center gap-2">
            <span className="font-medium text-primary">{w.title}</span>
            {w.mcp && (
              <Tag size="sm" outline mono title="Exposed as an MCP tool">
                mcp
              </Tag>
            )}
          </div>
          <Mono className="text-tertiary">{w.name}</Mono>
        </div>
      ),
    },
    { key: 'folder', header: 'Folder', sortable: true, accessor: (w) => w.folder, render: (w) => <span className="text-secondary">{w.folder || '—'}</span> },
    {
      key: 'owner',
      header: 'Owner',
      sortable: true,
      accessor: (w) => w.owner,
      render: (w) => (
        <Tag outline size="sm">
          {w.owner}
        </Tag>
      ),
    },
    {
      key: 'published',
      header: 'Published',
      sortable: true,
      accessor: (w) => w.publishedVersion ?? '',
      render: (w) =>
        w.publishedVersion ? (
          <span className="inline-flex items-center gap-2">
            <StateTag status="published" size="sm">
              {w.publishedVersion}
            </StateTag>
          </span>
        ) : (
          <span className="text-tertiary">not yet</span>
        ),
    },
    {
      key: 'draft',
      header: 'Draft',
      render: (w) =>
        hasDraft(w) ? (
          <Tag size="sm" tone="warning" dot title="A draft newer than the published version">
            {w.latestVersion}
          </Tag>
        ) : (
          <span className="text-tertiary">—</span>
        ),
    },
    { key: 'steps', header: 'Steps', numeric: true, sortable: true, accessor: (w) => w.stepCount },
    { key: 'updatedAt', header: 'Updated', sortable: true, align: 'right', accessor: (w) => w.updatedAt, render: (w) => <When at={w.updatedAt} /> },
    {
      key: 'actions',
      header: <span className="sr-only">Actions</span>,
      width: '3rem',
      align: 'right',
      render: (w) => (
        <IconButton
          label={`Versions of ${w.title}`}
          size="sm"
          onClick={(e) => {
            e.stopPropagation()
            navigate(pathFor('workflow-versions', { name: w.name }))
          }}
        >
          <Icon name="history" size={15} />
        </IconButton>
      ),
    },
  ]

  return (
    <>
      <PageHeader
        title="Workflows"
        subtitle="Design, validate and publish config-as-code workflows."
        actions={
          <>
            <Button icon="layers" onClick={() => navigate(pathFor('dependencies'))}>
              Dependencies
            </Button>
            <Button variant="primary" icon="plus" onClick={() => setCreating(true)}>
              New workflow
            </Button>
          </>
        }
      >
        <Tabs<WorkflowsTab>
          ariaLabel="Workflows views"
          value={tab}
          onChange={switchTab}
          className="mb-3"
          tabs={[
            { id: 'workflows', label: 'Workflows', icon: 'workflow', count: list.data?.length },
            { id: 'change-requests', label: 'Change requests', icon: 'git-branch', count: openCrs.data?.length },
          ]}
        />
        {tab === 'change-requests' ? (
          <Segmented ariaLabel="Change request status" size="sm" value={crFilter} onChange={showChangeRequests} options={CHANGE_REQUEST_FILTERS} />
        ) : (
          <div className="flex flex-wrap items-center gap-2">
            <Input leading="search" value={q} onChange={(e) => setQ(e.target.value)} placeholder="Search workflows" aria-label="Search workflows" className="sm:max-w-xs" />
            <Segmented
              ariaLabel="Status"
              size="sm"
              value={status}
              onChange={setStatus}
              options={[
                { value: 'all', label: 'All' },
                { value: 'published', label: 'Published' },
                { value: 'draft', label: 'Drafts' },
              ]}
            />
            <Select ariaLabel="Folder" size="sm" value={folder} onChange={setFolder} options={[{ value: '', label: 'Every folder' }, ...folders.map((f) => ({ value: f, label: f }))]} className="w-44" />
            <Select ariaLabel="Owner" size="sm" value={owner} onChange={setOwner} options={[{ value: '', label: 'Every owner' }, ...owners.map((o) => ({ value: o, label: o }))]} className="w-44" />
            {(q || folder || owner || status !== 'all') && (
              <Button
                size="sm"
                variant="ghost"
                icon="close"
                onClick={() => {
                  setQ('')
                  setFolder('')
                  setOwner('')
                  setStatus('all')
                }}
              >
                Clear
              </Button>
            )}
          </div>
        )}
      </PageHeader>

      {tab === 'change-requests' ? (
        crs.error && !crs.data ? (
          <ErrorState error={crs.error} title="Could not load the change requests" onRetry={() => void crs.refetch()} />
        ) : (
          <ChangeRequestTable rows={crs.data ?? []} loading={crs.loading} filter={crFilter} />
        )
      ) : list.error && !list.data ? (
        <ErrorState error={list.error} title="Could not load the workflows" onRetry={() => void list.refetch()} />
      ) : (
        <Table
          columns={columns}
          rows={rows}
          rowKey={(w) => w.name}
          loading={list.loading}
          onRowClick={(w) => navigate(pathFor('workflow', { name: w.name }))}
          defaultSort={{ key: 'updatedAt', dir: 'desc' }}
          emptyIcon="workflow"
          emptyTitle={list.data?.length ? 'No workflow matches' : 'No workflows yet'}
          emptyMessage={list.data?.length ? 'Loosen the search or the filters.' : 'Start from a blank canvas, a template, an import or a description.'}
          empty={
            list.data?.length ? undefined : (
              <div className="flex flex-col items-center gap-3 p-10 text-center">
                <div className="flex h-12 w-12 items-center justify-center rounded-full bg-surface-secondary">
                  <Icon name="workflow" size={22} className="text-tertiary" />
                </div>
                <p className="text-sm font-medium text-primary">No workflows yet</p>
                <p className="max-w-md text-footnote text-tertiary">Start from a blank canvas, a template, an import or a description.</p>
                <Button variant="primary" icon="plus" onClick={() => setCreating(true)}>
                  New workflow
                </Button>
              </div>
            )
          }
          caption="Workflows"
        />
      )}

      {creating && <NewWorkflowModal onClose={() => setCreating(false)} />}
    </>
  )
}

/* ----------------------------------------------------------------------------
   New workflow: four ways in
   ------------------------------------------------------------------------- */

type Way = 'blank' | 'template' | 'import' | 'describe'

const WAYS: { id: Way; icon: IconName; title: string; text: string }[] = [
  { id: 'blank', icon: 'edit', title: 'Blank', text: 'An empty canvas with a name and an owner.' },
  { id: 'template', icon: 'grid', title: 'From template', text: 'Start from a proven shape and adjust it.' },
  { id: 'import', icon: 'upload', title: 'Import', text: 'Wrap an IDP tile, a playbook, a Terraform module or an OpenAPI provider.' },
  { id: 'describe', icon: 'sparkles', title: 'Describe it', text: 'Tell the assistant what it should do in plain words.' },
]

export function NewWorkflowModal({ onClose }: { onClose: () => void }) {
  const [way, setWay] = useState<Way | null>(null)
  return (
    <Modal onClose={onClose} width="max-w-2xl" label="New workflow">
      <OverlayHeader title="New workflow" subtitle={way === 'blank' ? 'Name it; the canvas is next.' : way === 'import' ? 'The platform drafts a workflow around what already exists.' : 'How would you like to start?'} icon="workflow" />
      {way === null && (
        <OverlayBody>
          <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
            {WAYS.map((w) => (
              <button
                key={w.id}
                type="button"
                onClick={() => {
                  if (w.id === 'template') navigate(pathFor('templates'))
                  else if (w.id === 'describe') navigate(pathFor('assistant'))
                  else setWay(w.id)
                }}
                className="flex items-start gap-3 rounded-lg border border-stroke-light bg-surface p-4 text-left transition-all duration-150 hover:border-accent/40 hover:shadow-md active:scale-[0.99]"
              >
                <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-md bg-accent-tint text-accent">
                  <Icon name={w.icon} size={18} />
                </span>
                <span className="min-w-0">
                  <span className="block text-sm font-medium text-primary">{w.title}</span>
                  <span className="mt-0.5 block text-caption text-secondary">{w.text}</span>
                </span>
              </button>
            ))}
          </div>
        </OverlayBody>
      )}
      {way === 'blank' && <BlankForm onBack={() => setWay(null)} />}
      {way === 'import' && <ImportForm onBack={() => setWay(null)} />}
    </Modal>
  )
}

const KEBAB = /^[a-z][a-z0-9-]{1,62}$/

function kebab(s: string): string {
  return s
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '')
}

function BlankForm({ onBack }: { onBack: () => void }) {
  const toast = useToast()
  const close = useOverlayClose()
  const [title, setTitle] = useState('')
  const [name, setName] = useState('')
  const [touched, setTouched] = useState(false)
  const [noun, setNoun] = useState('')
  const [verb, setVerb] = useState('')
  const [owner, setOwner] = useState('')
  const [folder, setFolder] = useState('')
  const effectiveName = touched ? name : kebab(title)
  const valid = KEBAB.test(effectiveName) && title.trim().length > 0 && owner.trim().length > 0
  const create = useMutation((yaml: string) => api.createWorkflow({ yaml }), {
    invalidates: ['workflows'],
    onSuccess: (accepted) => {
      toast.success('Draft created', `${title.trim()} is ready on the canvas.`)
      close()
      navigate(pathFor('workflow', { name: accepted.id || effectiveName }))
    },
    onError: (e) => toast.error('Could not create the workflow', errorMessage(e)),
  })

  const submit = () => {
    if (!valid || create.loading) return
    // the skeleton the designer opens; yaml-emit is the browser's one YAML writer (see its header)
    const doc = {
      apiVersion: 'iaf.platform/v1',
      kind: 'Workflow',
      metadata: {
        name: effectiveName,
        title: title.trim(),
        noun: noun.trim() || undefined,
        verb: verb.trim() || undefined,
        owner: owner.trim(),
        folder: folder.trim() || undefined,
        version: '0.1.0',
      },
      api: { input: { type: 'object', properties: {} }, response: { status: 202 } },
      mcp: { expose: false },
      workflow: { do: [] },
    }
    void create.mutate(emitYaml(doc))
  }

  return (
    <form
      className="flex min-h-0 flex-1 flex-col"
      onSubmit={(e) => {
        e.preventDefault()
        submit()
      }}
    >
      <OverlayBody className="space-y-4">
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          <Field label="Title" required htmlFor="nw-title">
            <Input id="nw-title" value={title} onChange={(e) => setTitle(e.target.value)} placeholder="Restart a service" autoComplete="off" data-autofocus />
          </Field>
          <Field label="Name" required htmlFor="nw-name" hint="kebab-case, unique; the file and the URL use it" error={effectiveName && !KEBAB.test(effectiveName) ? 'Lowercase letters, digits and dashes' : undefined}>
            <Input
              id="nw-name"
              mono
              value={effectiveName}
              onChange={(e) => {
                setTouched(true)
                setName(e.target.value)
              }}
              placeholder="service-restart"
              autoComplete="off"
            />
          </Field>
          <Field label="Noun" htmlFor="nw-noun" hint="With the verb, it names the endpoint and the MCP tool">
            <Input id="nw-noun" mono value={noun} onChange={(e) => setNoun(e.target.value)} placeholder="service" autoComplete="off" />
          </Field>
          <Field label="Verb" htmlFor="nw-verb">
            <Input id="nw-verb" mono value={verb} onChange={(e) => setVerb(e.target.value)} placeholder="restart" autoComplete="off" />
          </Field>
          <Field label="Owner" required htmlFor="nw-owner" hint="The team that maintains it">
            <Input id="nw-owner" value={owner} onChange={(e) => setOwner(e.target.value)} placeholder="team-platform" autoComplete="off" />
          </Field>
          <Field label="Folder" htmlFor="nw-folder">
            <Input id="nw-folder" value={folder} onChange={(e) => setFolder(e.target.value)} placeholder="Platform" autoComplete="off" />
          </Field>
        </div>
      </OverlayBody>
      <OverlayFooter>
        <Button variant="ghost" icon="chevron-left" onClick={onBack} disabled={create.loading}>
          Back
        </Button>
        <Button type="submit" variant="primary" disabled={!valid} loading={create.loading} iconRight="arrow-right">
          Create and open
        </Button>
      </OverlayFooter>
    </form>
  )
}

function ImportForm({ onBack }: { onBack: () => void }) {
  const toast = useToast()
  const close = useOverlayClose()
  const [source, setSource] = useState<ImportSource>('ansible')
  const [ref, setRef] = useState('')
  const playbooks = useQuery('registry-playbooks', () => api.registryPlaybooks(), { enabled: source === 'ansible' })
  const providers = useQuery('registry-providers', () => api.registryProviders(), { enabled: source !== 'ansible' })
  const importIt = useMutation((args: { source: ImportSource; ref: string }) => api.importWorkflow(args.source, args.ref), {
    onError: (e) => toast.error('Import failed', errorMessage(e)),
  })
  const create = useMutation((yaml: string) => api.createWorkflow({ yaml }), {
    invalidates: ['workflows'],
    onSuccess: (accepted) => {
      toast.success('Draft created', 'The imported workflow is on the canvas.')
      close()
      navigate(pathFor('workflow', { name: accepted.id }))
    },
    onError: (e) => toast.error('Could not create the workflow', errorMessage(e)),
  })

  // each option's value is the `ref` the backend resolves: a tile id, <provider>/<playbook> or a provider name
  const options = useMemo(() => importChoices(source, playbooks.data ?? [], providers.data ?? []), [source, playbooks.data, providers.data])
  const loading = source === 'ansible' ? playbooks.loading : providers.loading
  const result = importIt.data

  return (
    <>
      <OverlayBody className="space-y-4">
        <Field label="Source">
          <Segmented
            ariaLabel="Import source"
            value={source}
            onChange={(s) => {
              setSource(s)
              setRef('')
              importIt.reset()
            }}
            options={IMPORT_SOURCES.map(({ value, label }) => ({ value, label }))}
            full
          />
        </Field>
        <Field label={importField(source)} required hint={options.length === 0 && !loading ? `Nothing of that kind is registered yet.` : source === 'idp' ? 'A tile of the developer portal, from the registry' : 'From the registry'}>
          <Select ariaLabel="Reference" value={ref} onChange={setRef} options={options} placeholder={loading ? 'Loading…' : 'Choose…'} disabled={loading || options.length === 0} />
        </Field>
        {result && (
          <div className="space-y-3 animate-fade-in">
            <CodeBlock code={result.yaml} language="yaml" title="Imported workflow" lineNumbers maxHeight="max-h-72" />
            {result.notes.length > 0 && (
              <ul className="space-y-1 rounded-md bg-surface-secondary px-4 py-3 text-caption text-secondary">
                {result.notes.map((n, i) => (
                  <li key={i} className="flex gap-2">
                    <Icon name="info" size={13} className="mt-0.5 shrink-0 text-tertiary" />
                    {n}
                  </li>
                ))}
              </ul>
            )}
          </div>
        )}
      </OverlayBody>
      <OverlayFooter className={cx(result && 'justify-between')}>
        <Button variant="ghost" icon="chevron-left" onClick={onBack} disabled={importIt.loading || create.loading}>
          Back
        </Button>
        <div className="flex items-center gap-2">
          <Button variant={result ? 'secondary' : 'primary'} icon="upload" disabled={!ref} loading={importIt.loading} onClick={() => void importIt.mutate(importRequest(source, ref))}>
            {result ? 'Import again' : 'Import'}
          </Button>
          {result && (
            <Button variant="primary" iconRight="arrow-right" loading={create.loading} onClick={() => void create.mutate(result.yaml)}>
              Open in designer
            </Button>
          )}
        </div>
      </OverlayFooter>
    </>
  )
}
