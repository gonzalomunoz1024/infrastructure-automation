import { useMemo, useState } from 'react'
import { Button, CodeBlock, DiffView, Drawer, OverlayBody, OverlayHeader, PageHeader, Segmented, Select, SkeletonRows, StateTag, Table, Tag, useToast, type TableColumn } from '@iaf/ui'
import type { PageProps } from '../app/router'
import { navigate, pathFor } from '../app/router'
import { api, errorMessage } from '../lib/api'
import { useMutation, useQuery } from '../lib/queries'
import type { WorkflowDefinition, WorkflowVersion } from '../lib/types'
import { ErrorState, Mono, TextLink, When } from '../components/common'
import { unifiedDiff } from '../components/diff'
import { allStepLocations } from '../designer/model/graph'
import { stepTypeOfBody } from '../designer/model/fixes'

/** One row of a version's step list: what the step is and, for a run step, which workflow it runs at which version range. */
export interface VersionStepRow {
  id: string
  name: string
  type: string
  depth: number
  runs?: { workflow: string; range?: string }
}

/** The steps of a version in document order, nested ones indented, run steps with their version range (`^2`). */
export function versionSteps(definition: WorkflowDefinition | undefined): VersionStepRow[] {
  if (!definition) return []
  return allStepLocations(definition).map((loc) => {
    const type = stepTypeOfBody(loc.body) ?? 'step'
    const run = loc.body.run
    return {
      id: loc.id,
      name: typeof loc.body.name === 'string' && loc.body.name ? loc.body.name : loc.id,
      type,
      depth: loc.parents.length,
      ...(type === 'run' && run && typeof run === 'object' ? { runs: { workflow: typeof run.workflow === 'string' ? run.workflow : '?', range: typeof run.version === 'string' && run.version ? run.version : undefined } } : {}),
    }
  })
}

export function VersionStepList({ definition }: { definition: WorkflowDefinition | undefined }) {
  const rows = versionSteps(definition)
  if (rows.length === 0) return <p className="rounded-md bg-surface-secondary px-4 py-6 text-center text-sm text-tertiary">This version has no steps.</p>
  return (
    <ol className="divide-y divide-stroke-light rounded-md border border-stroke-light" aria-label="Steps">
      {rows.map((s) => (
        <li key={s.id} className="flex flex-wrap items-center gap-x-3 gap-y-1 px-3 py-2" style={{ paddingLeft: `${0.75 + s.depth * 1.25}rem` }}>
          <span className="text-sm text-primary">{s.name}</span>
          <Mono className="text-tertiary">{s.id}</Mono>
          <Tag size="sm" outline mono>
            {s.type}
          </Tag>
          {s.runs && (
            <span className="inline-flex flex-wrap items-center gap-1.5 text-caption text-secondary">
              runs
              <TextLink to={pathFor('workflow', { name: s.runs.workflow })} mono>
                {s.runs.workflow}
              </TextLink>
              {s.runs.range ? (
                <Tag size="sm" mono title="Version range: the published version the child job uses must match it">
                  {s.runs.range}
                </Tag>
              ) : (
                <span className="text-tertiary">any version</span>
              )}
            </span>
          )}
        </li>
      ))}
    </ol>
  )
}

export default function WorkflowVersionsPage({ route }: PageProps) {
  const name = route.params.name
  const detail = useQuery(['workflow', name], () => api.workflow(name))
  const versions = useQuery(['workflow-versions', name], () => api.workflowVersions(name))
  const [selected, setSelected] = useState<string | null>(null)

  const columns: TableColumn<WorkflowVersion>[] = [
    {
      key: 'version',
      header: 'Version',
      sortable: true,
      accessor: (v) => v.version,
      render: (v) => (
        <div>
          <span className="font-mono text-sm text-primary">{v.version}</span>
          <div className="font-mono text-caption2 text-tertiary" title={v.contentHash}>
            {v.contentHash?.slice(0, 12)}
          </div>
        </div>
      ),
    },
    { key: 'status', header: 'Status', sortable: true, accessor: (v) => v.status, render: (v) => <StateTag status={v.status} size="sm" /> },
    {
      key: 'publishedAt',
      header: 'Published',
      sortable: true,
      accessor: (v) => v.publishedAt ?? '',
      render: (v) =>
        v.publishedAt ? (
          <span>
            {v.publishedBy && <span className="text-primary">{v.publishedBy} · </span>}
            <When at={v.publishedAt} />
          </span>
        ) : (
          <span className="text-tertiary">—</span>
        ),
    },
    {
      key: 'release',
      header: 'Release',
      render: (v) =>
        v.releaseId ? (
          <TextLink to={pathFor('release', { id: v.releaseId })} mono onClick={(e) => e.stopPropagation()}>
            {v.releaseId}
          </TextLink>
        ) : (
          <span className="text-tertiary">—</span>
        ),
    },
    { key: 'notes', header: 'Notes', render: (v) => <span className="text-secondary">{v.notes || '—'}</span> },
  ]

  const title = detail.data?.title ?? name
  return (
    <>
      <PageHeader
        eyebrow="Versions"
        title={title}
        subtitle={
          <span>
            <Mono>{name}</Mono>
            {detail.data && (
              <>
                {' · '}
                {detail.data.publishedVersion ? `${detail.data.publishedVersion} published` : 'nothing published yet'} · latest {detail.data.latestVersion}
              </>
            )}
          </span>
        }
        back={{ href: `#${pathFor('workflows')}`, label: 'Workflows' }}
        actions={
          <Button variant="primary" icon="workflow" onClick={() => navigate(pathFor('workflow', { name }))}>
            Open in designer
          </Button>
        }
      />

      {versions.error && !versions.data ? (
        <ErrorState error={versions.error} title="Could not load the versions" onRetry={() => void versions.refetch()} />
      ) : (
        <Table
          columns={columns}
          rows={versions.data ?? []}
          rowKey={(v) => v.version}
          loading={versions.loading}
          onRowClick={(v) => setSelected(v.version)}
          selectedKey={selected}
          defaultSort={{ key: 'version', dir: 'desc' }}
          emptyIcon="layers"
          emptyTitle="No versions yet"
          emptyMessage="Save the draft in the designer to create the first one."
          caption={`Versions of ${title}`}
        />
      )}

      {selected && versions.data && <VersionDrawer name={name} version={selected} versions={versions.data} onClose={() => setSelected(null)} />}
    </>
  )
}

function VersionDrawer({ name, version, versions, onClose }: { name: string; version: string; versions: WorkflowVersion[]; onClose: () => void }) {
  const toast = useToast()
  const [mode, setMode] = useState<'yaml' | 'steps' | 'compare'>('yaml')
  const others = useMemo(() => versions.filter((v) => v.version !== version), [versions, version])
  const [other, setOther] = useState<string>(() => {
    const published = others.find((v) => v.status === 'published')
    return (published ?? others[0])?.version ?? ''
  })
  const current = useQuery(['workflow-version', name, version], () => api.workflowVersion(name, version))
  const compared = useQuery(['workflow-version', name, other], () => api.workflowVersion(name, other), { enabled: mode === 'compare' && !!other })
  const replay = useMutation(() => api.replayCheck(name, version), {
    onSuccess: (r) =>
      r.passed
        ? toast.success('Replay check passed', `${r.replayed} past runs agree with ${version}.`)
        : toast.error('Replay check found divergences', `${r.divergences.length} of ${r.replayed} runs differ: ${r.divergences.slice(0, 2).map((d) => `${d.jobId} at ${d.step}`).join(', ')}${r.divergences.length > 2 ? '…' : ''}`),
    onError: (e) => toast.error('Replay check failed', errorMessage(e)),
  })
  const meta = versions.find((v) => v.version === version)
  const diff = useMemo(() => {
    if (!current.data || !compared.data) return ''
    return unifiedDiff(compared.data.yaml, current.data.yaml, { oldPath: `${name}@${other}`, newPath: `${name}@${version}` })
  }, [current.data, compared.data, name, other, version])

  return (
    <Drawer onClose={onClose} width="max-w-3xl" label={`Version ${version}`}>
      <OverlayHeader
        title={`${name} ${version}`}
        subtitle={
          meta && (
            <span className="inline-flex items-center gap-2">
              <StateTag status={meta.status} size="sm" />
              {meta.publishedAt && (
                <span>
                  published {meta.publishedBy ? `by ${meta.publishedBy} ` : ''}
                  <When at={meta.publishedAt} />
                </span>
              )}
            </span>
          )
        }
        icon="layers"
        actions={
          <Button size="sm" icon="history" loading={replay.loading} onClick={() => void replay.mutate()} title="Replay past runs against this version">
            Replay check
          </Button>
        }
      />
      <OverlayBody className="space-y-4">
        <div className="flex flex-wrap items-center gap-3">
          <Segmented
            ariaLabel="View"
            size="sm"
            value={mode}
            onChange={setMode}
            options={[
              { value: 'yaml', label: 'YAML' },
              { value: 'steps', label: 'Steps' },
              { value: 'compare', label: 'Compare', disabled: others.length === 0 },
            ]}
          />
          {mode === 'compare' && (
            <div className="flex items-center gap-2 text-caption text-secondary">
              against
              <Select ariaLabel="Compare with version" size="sm" mono value={other} onChange={setOther} options={others.map((v) => ({ value: v.version, label: v.version, hint: v.status }))} className="w-40" />
            </div>
          )}
          {meta?.notes && <Tag outline>{meta.notes}</Tag>}
        </div>
        {current.error ? (
          <ErrorState error={current.error} compact onRetry={() => void current.refetch()} />
        ) : current.loading ? (
          <SkeletonRows n={6} rowClassName="h-5" />
        ) : mode === 'yaml' ? (
          <CodeBlock code={current.data?.yaml ?? ''} language="yaml" title={`${name}@${version}`} lineNumbers />
        ) : mode === 'steps' ? (
          <VersionStepList definition={current.data?.definition} />
        ) : compared.error ? (
          <ErrorState error={compared.error} compact onRetry={() => void compared.refetch()} />
        ) : compared.loading || !compared.data ? (
          <SkeletonRows n={6} rowClassName="h-5" />
        ) : diff ? (
          <DiffView diff={diff} defaultMode="unified" wrap />
        ) : (
          <p className="rounded-md bg-surface-secondary px-4 py-6 text-center text-sm text-tertiary">
            {other} and {version} have the same content.
          </p>
        )}
      </OverlayBody>
    </Drawer>
  )
}
