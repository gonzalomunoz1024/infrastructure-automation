import { useState } from 'react'
import { Button, Card, CopyButton, Field, Input, Modal, OverlayBody, OverlayFooter, OverlayHeader, PageHeader, Skeleton, SkeletonRows, StatusDot, Tag, longDate, useOverlayClose, useToast } from '@iaf/ui'
import type { PageProps } from '../app/router'
import { pathFor } from '../app/router'
import { api, errorMessage } from '../lib/api'
import { useQuery } from '../lib/queries'
import type { EvidenceRecord, ShareLink } from '../lib/types'
import { EvidenceTimeline } from '../components/EvidenceTimeline'
import { downloadBlob } from '../components/download'
import { CopyableId, ErrorState, Facts, Mono, TextLink, When, shortJobId } from '../components/common'

export default function AuditRecordPage({ route }: PageProps) {
  const jobId = route.params.jobId
  const toast = useToast()
  const [sharing, setSharing] = useState(false)
  const [exporting, setExporting] = useState<'json' | 'pdf' | null>(null)
  const record = useQuery(['audit-record', jobId], () => api.auditRecord(jobId))
  const verify = useQuery(['audit-verify', jobId], () => api.verifyAudit(jobId))
  const data = record.data
  const v = verify.data

  const exportAs = async (format: 'json' | 'pdf') => {
    setExporting(format)
    try {
      downloadBlob(await api.exportAudit(jobId, format), `evidence-${jobId}.${format}`)
    } catch (e) {
      toast.error(`Could not export ${format.toUpperCase()}`, errorMessage(e))
    } finally {
      setExporting(null)
    }
  }

  if (record.error && !data) {
    return (
      <>
        <PageHeader eyebrow="Evidence" title={<span className="font-mono">{jobId}</span>} back={{ href: `#${pathFor('audit')}`, label: 'Audit' }} />
        <ErrorState error={record.error} title="Could not load the evidence" onRetry={() => void record.refetch()} />
      </>
    )
  }

  return (
    <>
      <PageHeader
        eyebrow="Evidence"
        title={data ? `${data.workflow} ${data.version}` : <span className="font-mono">{jobId}</span>}
        subtitle={
          data && (
            <span className="inline-flex flex-wrap items-center gap-2">
              <span>
                job <Mono className="text-primary">{shortJobId(jobId)}</Mono>
              </span>
              <span className="text-tertiary">·</span>
              <span>{data.requester}</span>
              <Tag size="sm" outline>
                {data.channel}
              </Tag>
              {data.sealed ? (
                <Tag size="sm" tone="healthy" icon="lock" title={`Signed with ${data.sealed.keyId} at ${longDate(data.sealed.at)}`}>
                  Sealed
                </Tag>
              ) : (
                <Tag size="sm" outline title="The hourly seal has not covered this record yet">
                  Not sealed yet
                </Tag>
              )}
            </span>
          )
        }
        back={{ href: `#${pathFor('audit')}`, label: 'Audit' }}
        actions={
          data && (
            <>
              <Button icon="link" onClick={() => setSharing(true)}>
                Share
              </Button>
              <Button icon="download" loading={exporting === 'json'} onClick={() => void exportAs('json')}>
                JSON
              </Button>
              <Button icon="download" loading={exporting === 'pdf'} onClick={() => void exportAs('pdf')}>
                PDF
              </Button>
            </>
          )
        }
      />

      {!data ? (
        <SkeletonRows n={5} />
      ) : (
        <div className="grid grid-cols-1 gap-5 lg:grid-cols-[minmax(0,1fr)_22rem]">
          <Card title="Timeline" subtitle={`${data.entries.length} entries, each hashing the one before it.`}>
            <EvidenceTimeline entries={data.entries} brokenAt={v && !v.valid ? v.brokenAt : undefined} />
          </Card>

          <aside className="min-w-0 space-y-4">
            <Card
              title="Chain"
              actions={
                <Button size="sm" variant="ghost" icon="refresh" loading={verify.fetching} onClick={() => void verify.refetch()}>
                  Verify
                </Button>
              }
            >
              {verify.error && !v ? (
                <ErrorState compact error={verify.error} title="Could not verify" onRetry={() => void verify.refetch()} />
              ) : !v ? (
                <Skeleton className="h-10" />
              ) : v.valid ? (
                <div className="flex items-start gap-2" role="status">
                  <StatusDot kind="healthy" className="mt-1.5" />
                  <div>
                    <p className="text-sm font-medium text-healthy">Chain intact</p>
                    <p className="text-caption text-secondary">All {v.entries} entries verify against their hashes.</p>
                  </div>
                </div>
              ) : (
                <div className="flex items-start gap-2" role="alert">
                  <StatusDot kind="critical" className="mt-1.5" />
                  <div>
                    <p className="text-sm font-medium text-critical">Chain broken{v.brokenAt !== undefined ? ` at entry ${v.brokenAt}` : ''}</p>
                    <p className="text-caption text-secondary">An entry no longer matches the hash the next one recorded. Report it to the platform team.</p>
                  </div>
                </div>
              )}
            </Card>
            <RecordFacts record={data} />
          </aside>
        </div>
      )}

      {sharing && <ShareModal jobId={jobId} onClose={() => setSharing(false)} />}
    </>
  )
}

function RecordFacts({ record }: { record: EvidenceRecord }) {
  return (
    <Card title="Record">
      <Facts
        columns={2}
        items={[
          { label: 'Workflow', value: <TextLink to={pathFor('workflow', { name: record.workflow })} mono>{record.workflow}</TextLink> },
          { label: 'Version', value: <Mono className="text-primary">{record.version}</Mono> },
          { label: 'Job', value: <TextLink to={pathFor('job', { id: record.jobId })} mono>{shortJobId(record.jobId)}</TextLink> },
          { label: 'Requester', value: record.requester },
          ...(record.change
            ? [{ label: 'Change request', value: <TextLink to={pathFor('change-request', { id: record.change.id })} mono>{record.change.id}</TextLink>, wide: true }]
            : []),
          { label: 'Kept until', value: <When at={record.retention.until} /> },
          { label: 'Sealed', value: record.sealed ? <When at={record.sealed.at} /> : <span className="text-tertiary">not yet</span> },
          ...(record.sealed ? [{ label: 'Signing key', value: <Mono className="text-primary">{record.sealed.keyId}</Mono>, wide: true }] : []),
          { label: 'Latest hash', value: <CopyableId id={record.latestHash} className="break-all" />, wide: true },
        ]}
      />
    </Card>
  )
}

function ShareModal({ jobId, onClose }: { jobId: string; onClose: () => void }) {
  return (
    <Modal onClose={onClose} width="max-w-lg" label="Share evidence">
      <ShareBody jobId={jobId} />
    </Modal>
  )
}

function ShareBody({ jobId }: { jobId: string }) {
  const close = useOverlayClose()
  const toast = useToast()
  const [link, setLink] = useState<ShareLink | null>(null)
  const [busy, setBusy] = useState(false)

  const create = async () => {
    setBusy(true)
    try {
      setLink(await api.shareAudit(jobId))
    } catch (e) {
      toast.error('Could not create the link', errorMessage(e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <>
      <OverlayHeader title="Share this evidence" subtitle="A read-only link for an auditor; it works without an account and expires on its own." icon="link" />
      <OverlayBody className="space-y-4">
        {link ? (
          <>
            <Field label="Link" htmlFor="share-url" hint={<span>Expires <When at={link.expiresAt} /> ({longDate(link.expiresAt)})</span>}>
              <div className="flex items-center gap-2">
                <Input id="share-url" mono readOnly value={link.url} onFocus={(e) => e.currentTarget.select()} />
                <CopyButton text={link.url} />
              </div>
            </Field>
          </>
        ) : (
          <p className="text-sm text-secondary">Anyone with the link can read this record (and only this record) until it expires.</p>
        )}
      </OverlayBody>
      <OverlayFooter>
        <Button variant="ghost" onClick={close}>
          {link ? 'Done' : 'Cancel'}
        </Button>
        {!link && (
          <Button variant="primary" icon="link" loading={busy} onClick={() => void create()}>
            Create link
          </Button>
        )}
      </OverlayFooter>
    </>
  )
}
