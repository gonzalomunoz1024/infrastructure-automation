import { useState } from 'react'
import { Button, Card, ConfirmDialog, EmptyState, Icon, JsonView, Modal, OverlayBody, OverlayFooter, OverlayHeader, PageHeader, Segmented, Skeleton, SkeletonRows, Tag, useOverlayClose, useToast } from '@iaf/ui'
import type { PageProps } from '../app/router'
import { navigate, pathFor } from '../app/router'
import { api, errorMessage, isApiError } from '../lib/api'
import { invalidate, useQuery } from '../lib/queries'
import type { Resource } from '../lib/types'
import { LineageGraph } from '../components/LineageGraph'
import { CopyableId, ErrorState, Facts, Mono, TextLink, When, resourceTone, shortJobId, summarize } from '../components/common'

interface Dependent {
  id: string
  name?: string
  type?: string
  status?: string
}

/** The 409 of :retire lists what is built from the resource; accept ids, resources or `{ dependents }`. */
export function dependentsOf(detail: unknown): Dependent[] {
  const list = Array.isArray(detail) ? detail : detail && typeof detail === 'object' && Array.isArray((detail as { dependents?: unknown }).dependents) ? (detail as { dependents: unknown[] }).dependents : []
  return list
    .map((d): Dependent | null => {
      if (typeof d === 'string') return { id: d }
      if (d && typeof d === 'object' && typeof (d as Dependent).id === 'string') {
        const r = d as Dependent
        return { id: r.id, name: r.name, type: r.type, status: r.status }
      }
      return null
    })
    .filter((d): d is Dependent => d !== null)
}

export default function ResourcePage({ route }: PageProps) {
  const id = route.params.id
  const toast = useToast()
  const [view, setView] = useState<'fields' | 'json'>('fields')
  const [confirm, setConfirm] = useState(false)
  const [blocked, setBlocked] = useState<Dependent[] | null>(null)
  const resource = useQuery(['resource', id], () => api.resource(id))
  const lineage = useQuery(['resource-lineage', id], () => api.resourceLineage(id))
  const data = resource.data

  const retire = async () => {
    try {
      await api.retireResource(id)
      invalidate('resource')
      toast.success('Retired', `${data?.name ?? id} is marked retired; nothing new can be built from it.`)
    } catch (e) {
      if (isApiError(e) && e.status === 409) {
        setBlocked(dependentsOf(e.detail))
        return
      }
      toast.error('Could not retire it', errorMessage(e))
      throw e
    }
  }

  if (resource.error && !data) {
    return (
      <>
        <PageHeader eyebrow="Resource" title={<span className="font-mono">{id}</span>} back={{ href: `#${pathFor('resources')}`, label: 'Resources' }} />
        <ErrorState error={resource.error} title="Could not load the resource" onRetry={() => void resource.refetch()} />
      </>
    )
  }

  const attrs = Object.entries(data?.attributes ?? {})
  const retired = data?.status === 'retired'

  return (
    <>
      <PageHeader
        eyebrow={data ? `Resource · ${data.type}` : 'Resource'}
        title={data?.name ?? <span className="font-mono">{id}</span>}
        subtitle={
          data && (
            <span className="inline-flex flex-wrap items-center gap-2">
              <Tag size="sm" tone={resourceTone(data.status)} dot>
                {data.status}
              </Tag>
              <Mono>{data.id}</Mono>
            </span>
          )
        }
        back={{ href: `#${pathFor('resources')}`, label: 'Resources' }}
        actions={
          data && !retired ? (
            <Button variant="warning-outline" icon="trash" onClick={() => setConfirm(true)}>
              Retire
            </Button>
          ) : undefined
        }
      />

      {!data ? (
        <SkeletonRows n={5} />
      ) : (
        <div className="space-y-5">
          <div className="grid grid-cols-1 gap-5 lg:grid-cols-[minmax(0,1fr)_22rem]">
            <Card
              title="Attributes"
              actions={
                <Segmented
                  ariaLabel="Attributes view"
                  size="sm"
                  value={view}
                  onChange={setView}
                  options={[
                    { value: 'fields', label: 'Fields' },
                    { value: 'json', label: 'JSON' },
                  ]}
                />
              }
            >
              {attrs.length === 0 ? (
                <p className="text-sm text-tertiary">No attributes recorded.</p>
              ) : view === 'fields' ? (
                <Facts columns={3} items={attrs.map(([k, v]) => ({ label: k, value: typeof v === 'object' && v !== null ? <Mono className="text-primary">{summarize(v, 80)}</Mono> : <span className="break-words">{String(v)}</span> }))} />
              ) : (
                <JsonView value={data.attributes} name="attributes" defaultExpanded={2} maxHeight="max-h-80" />
              )}
            </Card>

            <Card title="Origin">
              <Facts
                columns={2}
                items={[
                  { label: 'Type', value: <TextLink to={pathFor('resources', {}, { type: data.type })} mono>{data.type}</TextLink> },
                  { label: 'Owner', value: data.owner },
                  { label: 'Visibility', value: <Tag size="sm" outline>{data.visibility}</Tag> },
                  { label: 'Created', value: <When at={data.createdAt} /> },
                  { label: 'Workflow', value: <TextLink to={pathFor('workflow', { name: data.createdBy.workflow })} mono>{data.createdBy.workflow}</TextLink>, wide: true },
                  { label: 'Job', value: <TextLink to={pathFor('job', { id: data.createdBy.jobId })} mono>{shortJobId(data.createdBy.jobId)}</TextLink> },
                  { label: 'Evidence', value: <TextLink to={pathFor('audit-record', { jobId: data.createdBy.jobId })}>audit record</TextLink> },
                  { label: 'Id', value: <CopyableId id={data.id} />, wide: true },
                ]}
              />
            </Card>
          </div>

          <Card title="Lineage" subtitle="What it was built from, and what is built from it." flush>
            {lineage.error && !lineage.data ? (
              <ErrorState compact error={lineage.error} title="Could not load the lineage" onRetry={() => void lineage.refetch()} />
            ) : !lineage.data ? (
              <div className="p-5">
                <Skeleton className="h-40" />
              </div>
            ) : lineage.data.builtFrom.length === 0 && lineage.data.dependents.length === 0 ? (
              <EmptyState compact icon="layers" title="Stands alone" message="Nothing it was built from is recorded, and nothing is built from it yet." />
            ) : (
              <div className="overflow-x-auto p-5">
                <LineageGraph lineage={lineage.data} onNavigate={(rid) => navigate(pathFor('resource', { id: rid }))} />
              </div>
            )}
          </Card>
        </div>
      )}

      {confirm && data && (
        <ConfirmDialog
          title={`Retire ${data.name ?? data.id}?`}
          tone="danger"
          icon="trash"
          message="It stays in the registry for its lineage and its evidence, marked retired; pickers stop offering it and nothing new can be built from it. Retiring does not delete the real thing."
          confirmLabel="Retire"
          onConfirm={retire}
          onClose={() => setConfirm(false)}
        />
      )}
      {blocked && data && <BlockedModal resource={data} dependents={blocked} onClose={() => setBlocked(null)} />}
    </>
  )
}

function BlockedModal({ resource, dependents, onClose }: { resource: Resource; dependents: Dependent[]; onClose: () => void }) {
  return (
    <Modal onClose={onClose} width="max-w-lg" label="Cannot retire">
      <BlockedBody resource={resource} dependents={dependents} />
    </Modal>
  )
}

function BlockedBody({ resource, dependents }: { resource: Resource; dependents: Dependent[] }) {
  const close = useOverlayClose()
  return (
    <>
      <OverlayHeader title="Still in use" subtitle={`${resource.name ?? resource.id} cannot be retired while something is built from it.`} icon="alert-triangle" />
      <OverlayBody>
        {dependents.length === 0 ? (
          <p className="text-sm text-secondary">The registry says other resources depend on it. Retire those first.</p>
        ) : (
          <ul className="divide-y divide-stroke-light rounded-md border border-stroke-light">
            {dependents.map((d) => (
              <li key={d.id} className="flex items-center gap-3 px-4 py-2.5">
                <Icon name="resource" size={14} className="text-tertiary" />
                <span className="min-w-0 flex-1">
                  <TextLink to={pathFor('resource', { id: d.id })} onClick={close}>
                    {d.name ?? d.id}
                  </TextLink>
                  {d.name && <Mono className="ml-2 text-tertiary">{d.id}</Mono>}
                </span>
                {d.type && (
                  <Tag size="sm" outline mono>
                    {d.type}
                  </Tag>
                )}
                {d.status && (
                  <Tag size="sm" tone={resourceTone(d.status)} dot>
                    {d.status}
                  </Tag>
                )}
              </li>
            ))}
          </ul>
        )}
      </OverlayBody>
      <OverlayFooter>
        <Button variant="primary" onClick={close}>
          OK
        </Button>
      </OverlayFooter>
    </>
  )
}
