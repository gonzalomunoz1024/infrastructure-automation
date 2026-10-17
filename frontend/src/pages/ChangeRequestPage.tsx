import { useState } from 'react'
import { Button, Card, CodeBlock, ConfirmDialog, DiffView, PageHeader, SkeletonRows, StateTag, StatusDot, Table, Tabs, Tag, useToast, type TableColumn } from '@iaf/ui'
import type { PageProps } from '../app/router'
import { navigate, pathFor } from '../app/router'
import { api, errorMessage } from '../lib/api'
import { useMutation, useQuery } from '../lib/queries'
import type { Check } from '../lib/types'
import { ErrorState, ExternalLink, Facts, Mono, TextLink, When, checkDot } from '../components/common'

export default function ChangeRequestPage({ route }: PageProps) {
  const id = route.params.id
  const toast = useToast()
  const [tab, setTab] = useState<'diff' | 'yaml'>('diff')
  const [confirm, setConfirm] = useState<'merge' | 'close' | null>(null)
  const [releaseId, setReleaseId] = useState<string | null>(null)
  const [polling, setPolling] = useState(true)
  // every 3 s while the request is open (its checks run on the branch), then at rest
  const cr = useQuery(['change-request', id], () => api.changeRequest(id), { refetchInterval: polling ? 3000 : undefined })
  const data = cr.data
  const shouldPoll = !data || data.status === 'open'
  if (shouldPoll !== polling) setPolling(shouldPoll)

  const merge = useMutation(() => api.mergeChangeRequest(id), {
    invalidates: ['change-request', 'workflows', 'releases', 'providers'],
    onSuccess: (accepted) => {
      const rel = accepted.links?.release ?? (accepted.id && accepted.id !== id ? accepted.id : null)
      setReleaseId(rel)
      toast.toast({
        title: 'Merged',
        message: rel ? 'The version is published and its canary has started.' : 'The version is published.',
        tone: 'healthy',
        action: rel ? { label: 'Open release', onClick: () => navigate(rel.startsWith('/') ? rel : pathFor('release', { id: rel })) } : undefined,
      })
    },
    onError: (e) => toast.error('Merge failed', errorMessage(e)),
  })
  const close = useMutation(() => api.closeChangeRequest(id), {
    invalidates: ['change-request', 'workflows', 'providers'],
    onSuccess: () => toast.success('Change request closed'),
    onError: (e) => toast.error('Could not close it', errorMessage(e)),
  })

  const checkColumns: TableColumn<Check>[] = [
    { key: 'status', header: 'Result', width: '8rem', render: (c) => <StatusDot kind={checkDot(c.status)} label={c.status} /> },
    { key: 'label', header: 'Check', render: (c) => <span className="text-primary">{c.label}</span> },
    { key: 'detail', header: 'Detail', render: (c) => <span className="text-secondary">{c.detail || '—'}</span> },
  ]

  if (cr.error && !data) {
    return (
      <>
        <PageHeader eyebrow="Change request" title={<span className="font-mono">{id}</span>} back={{ href: `#${pathFor('workflows')}`, label: 'Workflows' }} />
        <ErrorState error={cr.error} title="Could not load the change request" onRetry={() => void cr.refetch()} />
      </>
    )
  }

  const checksPassed = !!data && data.checks.length > 0 && data.checks.every((c) => c.status !== 'failed')
  const canMerge = !!data && data.status === 'open' && checksPassed
  const targetHref = data ? (data.kind === 'provider' ? pathFor('provider', { name: data.name }) : pathFor('workflow', { name: data.name })) : '#'
  const releaseHref = releaseId ? (releaseId.startsWith('/') ? releaseId : pathFor('release', { id: releaseId })) : null

  return (
    <>
      <PageHeader
        eyebrow="Change request"
        title={data?.title ?? <span className="font-mono">{id}</span>}
        subtitle={
          data && (
            <span className="inline-flex flex-wrap items-center gap-2">
              <StateTag status={data.status} size="sm" />
              <Tag size="sm" outline>
                {data.kind}
              </Tag>
              <Mono>{id}</Mono>
            </span>
          )
        }
        back={{ href: `#${pathFor('workflows')}`, label: 'Workflows' }}
        actions={
          data && (
            <>
              {data.url && <ExternalLink href={data.url} className="text-sm">Open in Git</ExternalLink>}
              {releaseHref && (
                <Button icon="release" onClick={() => navigate(releaseHref)}>
                  Open release
                </Button>
              )}
              {data.status === 'open' && (
                <>
                  <Button variant="ghost" icon="close" onClick={() => setConfirm('close')} disabled={merge.loading || close.loading}>
                    Close
                  </Button>
                  <Button variant="primary" icon="git-branch" disabled={!canMerge} loading={merge.loading} onClick={() => setConfirm('merge')} title={canMerge ? undefined : 'Every check must pass first'}>
                    Merge
                  </Button>
                </>
              )}
            </>
          )
        }
      />

      {!data ? (
        <SkeletonRows n={4} />
      ) : (
        <div className="space-y-5">
          <Card>
            <Facts
              columns={4}
              items={[
                { label: data.kind === 'provider' ? 'Provider' : 'Workflow', value: <TextLink to={targetHref}>{data.name}</TextLink> },
                { label: 'Version', value: <Mono className="text-primary">{data.version}</Mono> },
                { label: 'Author', value: data.author },
                { label: 'Branch', value: <Mono className="text-primary">{data.branch}</Mono> },
                { label: 'Strategy', value: <Tag size="sm">{data.strategy}</Tag> },
                { label: 'Opened', value: <When at={data.createdAt} /> },
                { label: 'Merged', value: data.mergedAt ? <When at={data.mergedAt} /> : <span className="text-tertiary">not yet</span> },
                { label: 'Checks', value: `${data.checks.filter((c) => c.status === 'passed').length} of ${data.checks.length} passed` },
              ]}
            />
          </Card>

          <Table columns={checkColumns} rows={data.checks} rowKey={(c) => c.id} dense emptyIcon="approval" emptyTitle="No checks ran" emptyMessage="The checks appear as soon as the branch is pushed." caption="Checks" />

          <div>
            <Tabs
              ariaLabel="Content"
              value={tab}
              onChange={setTab}
              tabs={[
                { id: 'diff', label: 'Diff', icon: 'git-branch' },
                { id: 'yaml', label: 'YAML', icon: 'document' },
              ]}
              className="mb-3"
            />
            {tab === 'diff' ? data.diff ? <DiffView diff={data.diff} wrap maxHeight="max-h-[70vh]" /> : <p className="rounded-md bg-surface-secondary px-4 py-6 text-center text-sm text-tertiary">No diff: this is the first version.</p> : <CodeBlock code={data.yaml} language="yaml" lineNumbers maxHeight="max-h-[70vh]" />}
          </div>
        </div>
      )}

      {confirm === 'merge' && data && (
        <ConfirmDialog
          title={`Merge ${data.version}?`}
          icon="git-branch"
          message={`${data.name} ${data.version} is published${data.strategy === 'canary' ? ' and its canary release starts with the first stage' : ' to everyone at once'}.`}
          confirmLabel="Merge and publish"
          onConfirm={async () => {
            await merge.mutate()
          }}
          onClose={() => setConfirm(null)}
        />
      )}
      {confirm === 'close' && data && (
        <ConfirmDialog
          title="Close without merging?"
          tone="danger"
          icon="close-circle"
          message="The branch stays in Git; the version is not published. You can open a new change request later."
          confirmLabel="Close"
          onConfirm={async () => {
            await close.mutate()
          }}
          onClose={() => setConfirm(null)}
        />
      )}
    </>
  )
}
