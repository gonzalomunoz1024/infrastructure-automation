import { lazy, Suspense, useState } from 'react'
import { Button, Card, CodeBlock, ConfirmDialog, DiffView, Icon, PageHeader, Skeleton, SkeletonRows, StateTag, StatusDot, Table, Tabs, Tag, useToast, type IconName, type TableColumn } from '@iaf/ui'
import type { PageProps } from '../app/router'
import { navigate, pathFor } from '../app/router'
import { api, errorMessage } from '../lib/api'
import { useMutation, useQuery } from '../lib/queries'
import type { ChangeRequestDetail, Check } from '../lib/types'
import { ErrorState, ExternalLink, Facts, Mono, TextLink, When, checkDot } from '../components/common'
import { ProblemList } from '../components/ProblemList'

/** The read-only canvas is the designer's (it brings @xyflow/react): its own chunk, loaded when the tab opens. */
const DocumentCanvas = lazy(() => import('../designer/views/DocumentCanvas'))

/** Back to where a reviewer finds change requests: the Workflows page's tab, both kinds listed. */
const BACK = { href: `#${pathFor('workflows', {}, { tab: 'change-requests' })}`, label: 'Change requests' }

export type ContentTab = 'canvas' | 'diff' | 'yaml'

const CONTENT_TABS: Record<ContentTab, { id: ContentTab; label: string; icon: IconName }> = {
  canvas: { id: 'canvas', label: 'Canvas', icon: 'workflow' },
  diff: { id: 'diff', label: 'Diff', icon: 'git-branch' },
  yaml: { id: 'yaml', label: 'YAML', icon: 'document' },
}

/** A workflow's change request opens on the canvas, the candidate as its author designed it; a provider has no canvas. */
export function contentTabs(kind: ChangeRequestDetail['kind']): ContentTab[] {
  return kind === 'workflow' ? ['canvas', 'diff', 'yaml'] : ['diff', 'yaml']
}

/** A list the backend wrote into a sentence: `Affected consumers: a, b` → ['a', 'b']. */
function namesAfter(text: string, lead: RegExp): string[] | null {
  const m = lead.exec(text)
  if (!m) return null
  return m[1]
    .split(/,\s*/)
    .map((s) => s.trim().replace(/[.;]$/, ''))
    .filter(Boolean)
}

/** A check's detail as a person reads it: sentences, with the names the backend lists as items. */
export function readableDetail(detail: string | undefined): { text: string | null; lists: { label: string; items: string[]; tone: 'critical' | 'neutral' | 'healthy' }[] } {
  if (!detail) return { text: null, lists: [] }
  const trimmed = detail.trim()
  // a JSON blob: say its fields one per line instead of the braces
  if (/^[[{]/.test(trimmed)) {
    try {
      const v: unknown = JSON.parse(trimmed)
      if (Array.isArray(v)) return { text: null, lists: [{ label: 'Items', items: v.map((x) => (typeof x === 'string' ? x : JSON.stringify(x))), tone: 'neutral' }] }
      if (v && typeof v === 'object') {
        return { text: null, lists: [{ label: 'Fields', items: Object.entries(v as Record<string, unknown>).map(([k, x]) => `${k}: ${typeof x === 'string' ? x : JSON.stringify(x)}`), tone: 'neutral' }] }
      }
    } catch {
      /* not JSON after all: shown as text */
    }
  }
  const lists: { label: string; items: string[]; tone: 'critical' | 'neutral' | 'healthy' }[] = []
  let text = trimmed
  const affected = namesAfter(text, /Affected consumers?:\s*([^.;]+)/i)
  if (affected) {
    lists.push({ label: 'A breaking change reaches', items: affected, tone: 'critical' })
    text = text.replace(/\s*Affected consumers?:\s*[^.;]+[.;]?/i, '')
  }
  const staying = namesAfter(text, /Staying on the old major:\s*([^.;]+)/i)
  if (staying) {
    lists.push({ label: 'Staying on the old major', items: staying, tone: 'neutral' })
    text = text.replace(/\s*Staying on the old major:\s*[^.;]+[.;]?/i, '')
  }
  // the playbooks check: its failures are joined by "; ", the linter's findings last (each its own item)
  const lint = /;?\s*the linter found problems:\s*(.+)$/i.exec(text)
  if (lint) {
    lists.push({ label: 'The linter found', items: lint[1].split(/;\s*/).map((s) => s.trim()).filter(Boolean), tone: 'critical' })
    text = text.slice(0, lint.index)
  }
  const pinned = namesAfter(text, /pinned at publish:\s*(.+)$/i)
  if (pinned) {
    lists.push({ label: 'Pinned at publish', items: pinned, tone: 'healthy' })
    text = text.replace(/;?\s*pinned at publish:\s*.+$/i, '')
  }
  const unpinned = namesAfter(text, /not pinned to a commit:\s*([^;]+)/i)
  if (unpinned) {
    lists.push({ label: 'Not pinned to a commit', items: unpinned, tone: 'critical' })
    text = text.replace(/;?\s*not pinned to a commit:\s*[^;]+/i, '')
  }
  const digests = namesAfter(text, /upload digest is not 64 hex digits \(playbook\.digest\):\s*([^;]+)/i)
  if (digests) {
    lists.push({ label: 'Upload digest is not 64 hex digits', items: digests, tone: 'critical' })
    text = text.replace(/;?\s*upload digest is not 64 hex digits \(playbook\.digest\):\s*[^;]+/i, '')
  }
  text = text.trim().replace(/^[;,]\s*/, '').replace(/[;,]$/, '')
  return { text: text || null, lists }
}

function CheckDetail({ check }: { check: Check }) {
  const { text, lists } = readableDetail(check.detail)
  if (!text && lists.length === 0) return <span className="text-tertiary">—</span>
  return (
    <div className="space-y-1.5 text-secondary">
      {text && <p className="whitespace-normal break-words">{text}</p>}
      {lists.map((l) => (
        <div key={l.label} className="flex flex-wrap items-center gap-x-1.5 gap-y-1">
          <span className="text-caption text-tertiary">{l.label}:</span>
          <ul className="inline-flex flex-wrap gap-1" aria-label={l.label}>
            {l.items.map((item) => (
              <li key={item}>
                <Tag size="sm" mono tone={l.tone === 'critical' ? 'critical' : l.tone === 'healthy' ? 'healthy' : 'neutral'} outline={l.tone === 'neutral'}>
                  {item}
                </Tag>
              </li>
            ))}
          </ul>
        </div>
      ))}
    </div>
  )
}

/** The candidate version on the read-only canvas. The backend reads the YAML (`POST /workflows:parse`); the browser parses none. */
function CandidateCanvas({ cr }: { cr: ChangeRequestDetail }) {
  const parsed = useQuery(['workflow-parse', cr.yaml], () => api.parseWorkflow({ yaml: cr.yaml }))
  if (parsed.error && !parsed.data) return <ErrorState error={parsed.error} title="Could not read the workflow" onRetry={() => void parsed.refetch()} />
  if (!parsed.data) return <Skeleton className="h-[60vh] min-h-[22rem] w-full" />
  if (!parsed.data.definition) {
    return (
      <div className="space-y-3" data-testid="candidate-unparsed">
        <p className="text-sm text-secondary">The YAML does not parse, so there is no canvas to draw: the YAML tab shows the file as it is.</p>
        {parsed.data.problems.length > 0 && <ProblemList problems={parsed.data.problems} compact />}
      </div>
    )
  }
  return (
    <div>
      <p className="mb-2 text-caption text-secondary">
        {cr.name} {cr.version} as its author designed it, read only. Double-click a step to see it as written.
      </p>
      <div className="relative h-[60vh] min-h-[22rem] overflow-hidden rounded-lg border border-stroke-light" data-testid="candidate-canvas">
        <Suspense fallback={<Skeleton className="h-full w-full" />}>
          <DocumentCanvas doc={parsed.data.definition} label={`${cr.name} ${cr.version} on the canvas`} />
        </Suspense>
      </div>
    </div>
  )
}

/** The Mermaid source of a workflow change request's flowchart (`preview.mermaid`); null for a provider's, or when the backend sent none. */
export function diagramSourceOf(cr: Pick<ChangeRequestDetail, 'kind' | 'preview'>): string | null {
  const mermaid = cr.kind === 'workflow' ? cr.preview?.mermaid : undefined
  return typeof mermaid === 'string' && mermaid.trim() ? mermaid : null
}

/**
 * The flowchart the backend writes for the change request, as text behind a disclosure: GitHub
 * draws the same block in the pull request's description, and the canvas stays the view here.
 */
export function DiagramSource({ mermaid, url }: { mermaid: string; url?: string }) {
  return (
    <details className="group mt-3 rounded-lg border border-stroke-light bg-surface" data-testid="diagram-source">
      <summary className="flex cursor-pointer items-center gap-2 px-4 py-2.5 text-sm text-secondary transition-colors hover:text-primary">
        <Icon name="document" size={15} className="text-tertiary" />
        Diagram source
        <span className="text-caption text-tertiary">Mermaid</span>
        <Icon name="chevron-down" size={13} className="ml-auto text-tertiary transition-transform group-open:rotate-180" />
      </summary>
      <div className="space-y-2 px-4 pb-4">
        <p className="text-caption text-tertiary">GitHub draws this flowchart in the description of {url ? <ExternalLink href={url}>the pull request</ExternalLink> : 'the pull request'}.</p>
        <CodeBlock code={mermaid} language="mermaid" title="flowchart" maxHeight="max-h-80" />
      </div>
    </details>
  )
}

export default function ChangeRequestPage({ route }: PageProps) {
  const id = route.params.id
  const toast = useToast()
  // the tab chosen, if any: until then the first the change request's kind has
  const [chosen, setChosen] = useState<ContentTab | null>(null)
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
    { key: 'label', header: 'Check', width: '12rem', render: (c) => <span className="text-primary">{c.label}</span> },
    { key: 'detail', header: 'Detail', render: (c) => <CheckDetail check={c} /> },
  ]

  if (cr.error && !data) {
    return (
      <>
        <PageHeader eyebrow="Change request" title={<span className="font-mono">{id}</span>} back={BACK} />
        <ErrorState error={cr.error} title="Could not load the change request" onRetry={() => void cr.refetch()} />
      </>
    )
  }

  const checksPassed = !!data && data.checks.length > 0 && data.checks.every((c) => c.status !== 'failed')
  const tabs = data ? contentTabs(data.kind) : []
  const tab: ContentTab = chosen && tabs.includes(chosen) ? chosen : (tabs[0] ?? 'diff')
  const diagram = data ? diagramSourceOf(data) : null
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
        back={BACK}
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
            <Tabs<ContentTab> ariaLabel="Content" value={tab} onChange={setChosen} tabs={tabs.map((t) => CONTENT_TABS[t])} className="mb-3" />
            {tab === 'canvas' ? (
              <>
                <CandidateCanvas cr={data} />
                {diagram && <DiagramSource mermaid={diagram} url={data.url} />}
              </>
            ) : tab === 'diff' ? (
              data.diff ? (
                <DiffView diff={data.diff} wrap maxHeight="max-h-[70vh]" />
              ) : (
                <p className="rounded-md bg-surface-secondary px-4 py-6 text-center text-sm text-tertiary">No diff: this is the first version.</p>
              )
            ) : (
              <CodeBlock code={data.yaml} language="yaml" lineNumbers maxHeight="max-h-[70vh]" />
            )}
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
