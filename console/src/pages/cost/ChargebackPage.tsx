import { Fragment, useState } from 'react'
import { Card, EmptyState, PageHeader, Segmented, Select, SkeletonRows, Stat, cx } from '@iaf/ui'
import { CopyableId, DotTag, ErrorState, useNow } from '@iaf/console-kit'
import { useConsole } from '../../app/console'
import { ActionButton, useRefusal } from '../../app/Refusal'
import { navigate, pathFor, type PageProps } from '../../app/router'
import { formatAccountId } from '../../lib/accounts'
import { costApi, type ChargebackQuery, type ChargebackReport, type CostScope } from '../../lib/cost'
import { ALLOWED, denialOf, type Decision } from '../../lib/permissions'
import { explainLine } from '../../lib/plain'
import { useQuery } from '../../lib/queries'
import { Control, EstimatesNote, RefusedState, SkippedAccounts, isRefused } from './common'
import { TAG_KEYS, chargebackFileName, chargebackGroupsOf, chargebackMonths, isoDay, monthLabel, money, saveText, sha256Hex } from './model'

/*
 * Chargeback (WP 9.9): a month's cost by cost centre, account, service and, on request, a tag's
 * value, with the subtotals finance reads. A closed month is final: published once, with the
 * SHA-256 of its CSV, and never restated; the current month is provisional and grows until it
 * closes. The CSV is the cost service's own file, saved byte for byte (UTF-8, no byte order
 * mark), and the browser checks its SHA-256 against the published one before saying it is good.
 * The chargeback and its CSV need `cost:export` (account-admin and auditor): for everyone else the
 * page says so, with the refusal one button away.
 */

interface Saved {
  tone: 'healthy' | 'warning' | 'critical' | 'neutral'
  text: string
}

function queryOf(params: Record<string, string>, months: { value: string }[]): ChargebackQuery & { scope: CostScope } {
  const month = months.some((m) => m.value === params.month) ? params.month : months[1].value
  return { month, scope: params.scope === 'organization' ? 'organization' : 'account', tag: TAG_KEYS.includes(params.tag ?? '') ? params.tag : undefined }
}

export default function ChargebackPage({ route }: PageProps) {
  const { account, session } = useConsole()
  const refusal = useRefusal()
  const now = useNow(60_000)
  const months = chargebackMonths(isoDay(now))
  const q = queryOf(route.query, months)
  const report = useQuery(['chargeback', session.account.accountId, session.region, q], () => costApi.chargeback(q))
  const [saving, setSaving] = useState(false)
  const [saved, setSaved] = useState<Saved | null>(null)

  const set = (patch: Partial<ChargebackQuery>) => {
    setSaved(null)
    navigate(pathFor('chargeback', {}, { ...q, ...patch, scope: (patch.scope ?? q.scope) === 'account' ? undefined : 'organization' }), { replace: true })
  }
  const denied = isRefused(report.error) ? denialOf(report.error) : null
  const download: Decision = denied ? { allowed: false, denied } : ALLOWED
  const r = report.data
  const monthName = monthLabel(q.month)

  const save = async () => {
    setSaving(true)
    setSaved(null)
    try {
      const text = await costApi.chargebackCsv(q)
      const name = chargebackFileName(q.month, q.scope, account.id, q.tag)
      saveText(name, text)
      const hash = await sha256Hex(text)
      if (r?.final && r.sha256) {
        const matches = hash === r.sha256
        setSaved(
          matches
            ? { tone: 'healthy', text: `Saved ${name}. Its SHA-256 matches the published month.` }
            : { tone: 'critical', text: `Saved ${name}, but its SHA-256 is ${hash}, not the published one. Download it again; if it still differs, tell the platform team.` },
        )
      } else {
        setSaved({ tone: 'neutral', text: `Saved ${name}. ${monthName} is still open, so nothing is published to check it against; its SHA-256 is ${hash}.` })
      }
    } catch (e) {
      if (!refusal.showError(e, 'Download chargeback CSV')) setSaved({ tone: 'critical', text: explainLine(e, 'Download the CSV') })
    } finally {
      setSaving(false)
    }
  }

  return (
    <>
      <PageHeader
        title="Chargeback"
        subtitle="Each month's cost by cost centre, account and service, as finance receives it."
        actions={
          <ActionButton decision={download} what="Download chargeback CSV" icon="download" onClick={() => void save()} loading={saving} disabled={!r}>
            Download CSV
          </ActionButton>
        }
      />

      <div className="mb-8 flex flex-wrap items-start gap-x-3 gap-y-3" role="group" aria-label="Chargeback parameters">
        <Control label="Month">
          <Select size="sm" ariaLabel={`Month: ${months.find((m) => m.value === q.month)?.label ?? q.month}`} value={q.month} onChange={(month) => set({ month })} options={months} />
        </Control>
        <Control label="Scope">
          <Segmented<CostScope>
            size="sm"
            value={q.scope}
            onChange={(scope) => set({ scope })}
            ariaLabel={`Scope: ${q.scope === 'organization' ? 'Organization' : account.name}`}
            options={[
              { value: 'account', label: account.name },
              { value: 'organization', label: 'Organization' },
            ]}
          />
        </Control>
        <Control label="Split by tag">
          <Select size="sm" ariaLabel={`Split by tag: ${q.tag ?? 'None'}`} value={q.tag ?? ''} onChange={(tag) => set({ tag: tag || undefined })} options={[{ value: '', label: 'None' }, ...TAG_KEYS.map((k) => ({ value: k, label: k }))]} />
        </Control>
      </div>

      <p role="status" className={cx('mb-6 text-sm', !saved && 'sr-only')}>
        {saved && (
          <span className="inline-flex items-start gap-2">
            <DotTag tone={saved.tone === 'neutral' ? 'accent' : saved.tone} size="sm">
              {saved.tone === 'healthy' ? 'Checked' : saved.tone === 'critical' ? 'Not checked' : 'Saved'}
            </DotTag>
            <span className="text-primary">{saved.text}</span>
          </span>
        )}
      </p>

      {report.error && isRefused(report.error) ? (
        <RefusedState error={report.error} what="Download chargeback CSV" title={q.scope === 'organization' ? 'You cannot export the chargeback of these accounts' : `You cannot export the chargeback of ${account.name}`} />
      ) : report.error ? (
        <ErrorState error={report.error} title="Could not load the chargeback" onRetry={() => void report.refetch()} />
      ) : !r ? (
        <Card>
          <SkeletonRows n={6} />
        </Card>
      ) : (
        <div className={cx('space-y-6', report.fetching && 'opacity-60 motion-safe:transition-opacity')} aria-busy={report.fetching || undefined}>
          <Status report={r} />
          <SkippedAccounts ids={r.skippedAccounts} action="cost:export" />
          {r.rows.length ? <ChargebackTable report={r} tag={q.tag} /> : <EmptyState icon="document" title={`Nothing was charged in ${monthName}`} message="No priced resource ran in the accounts this covers." />}
          <EstimatesNote>Each row is what the resources cost by the price lists that month.</EstimatesNote>
        </div>
      )}
    </>
  )
}

/** Final with its checksum, or provisional until the month closes. */
function Status({ report }: { report: ChargebackReport }) {
  const generated = new Date(report.generatedAt).toLocaleString('en-US', { dateStyle: 'medium', timeStyle: 'short', timeZone: 'UTC' })
  return (
    <div className="grid grid-cols-1 gap-3 md:grid-cols-[minmax(0,14rem)_minmax(0,1fr)]">
      <Stat label={`Total for ${monthLabel(report.month)}`} value={money(report.total, report.currency)} />
      <div className="min-w-0 rounded-md bg-surface-secondary p-3">
        <div className="flex flex-wrap items-center gap-2">
          <DotTag tone={report.final ? 'healthy' : 'warning'}>{report.final ? 'Final' : 'Provisional'}</DotTag>
          <span className="text-sm text-secondary">{report.final ? `Published to finance ${generated} UTC; it never changes.` : `As of ${generated} UTC; it grows until the month closes.`}</span>
        </div>
        {report.final && report.sha256 && (
          <div className="mt-2 min-w-0 text-caption text-secondary">
            <span className="mr-2">SHA-256 of the published CSV</span>
            <CopyableId id={report.sha256} className="max-w-full break-all" />
          </div>
        )}
      </div>
    </div>
  )
}

/** The rows by cost centre and account, each group closed by its subtotal. */
function ChargebackTable({ report, tag }: { report: ChargebackReport; tag?: string }) {
  const { services } = useConsole()
  const serviceTitle = (id: string) => services.find((s) => s.id === id)?.title ?? id
  const centres = chargebackGroupsOf(report.rows)
  const fmt = (v: number) => money(v, report.currency)
  const span = tag ? 4 : 3
  return (
    <div className="overflow-x-auto rounded-lg border border-stroke-light" role="region" aria-label={`Chargeback rows for ${monthLabel(report.month)}`} tabIndex={0}>
      <table className="w-full border-collapse text-sm">
        <caption className="sr-only">
          Chargeback for {monthLabel(report.month)}, by cost centre, account and service{tag ? ` and the ${tag} tag` : ''}
        </caption>
        <thead className="whitespace-nowrap bg-surface-secondary text-left text-caption text-secondary">
          <tr>
            <th scope="col" className="px-4 py-2.5 font-medium">
              Cost centre
            </th>
            <th scope="col" className="px-4 py-2.5 font-medium">
              Account
            </th>
            <th scope="col" className="px-4 py-2.5 font-medium">
              Service
            </th>
            {tag && (
              <th scope="col" className="px-4 py-2.5 font-medium">
                Tag: {tag}
              </th>
            )}
            <th scope="col" className="px-4 py-2.5 text-right font-medium">
              Amount
            </th>
          </tr>
        </thead>
        <tbody>
          {centres.map((c) => (
            <Fragment key={c.costCentre}>
              {c.accounts.map((a, ai) => (
                <Fragment key={a.accountId}>
                  {a.rows.map((row, ri) => (
                    <tr key={`${row.service}-${row.tag?.value ?? ''}`} className="border-t border-stroke-light">
                      <td className="whitespace-nowrap px-4 py-2 font-mono text-caption">
                        <span className={ai === 0 && ri === 0 ? undefined : 'sr-only'}>{c.costCentre}</span>
                      </td>
                      <td className="whitespace-nowrap px-4 py-2">
                        <span className={ri === 0 ? undefined : 'sr-only'}>
                          {a.accountName ?? a.accountId} <span className="font-mono text-caption text-tertiary">{formatAccountId(a.accountId)}</span>
                        </span>
                      </td>
                      <td className="px-4 py-2">{serviceTitle(row.service)}</td>
                      {tag && <td className="px-4 py-2">{row.tag?.value ? <span className="font-mono text-caption">{row.tag.value}</span> : <span className="text-tertiary">No {tag} tag</span>}</td>}
                      <td className="px-4 py-2 text-right tabular-nums">{fmt(row.amount)}</td>
                    </tr>
                  ))}
                  <tr className="border-t border-stroke-light bg-surface-tertiary">
                    <th scope="row" colSpan={span} className="px-4 py-2 text-left font-normal text-secondary">
                      Subtotal, {a.accountName ?? a.accountId}
                    </th>
                    <td className="px-4 py-2 text-right font-medium tabular-nums">{fmt(a.total)}</td>
                  </tr>
                </Fragment>
              ))}
              <tr className="border-t border-stroke bg-surface-secondary">
                <th scope="row" colSpan={span} className="px-4 py-2 text-left font-medium text-primary">
                  Subtotal, cost centre <span className="font-mono">{c.costCentre}</span>
                </th>
                <td className="px-4 py-2 text-right font-semibold tabular-nums">{fmt(c.total)}</td>
              </tr>
            </Fragment>
          ))}
        </tbody>
        <tfoot>
          <tr className="border-t-2 border-stroke">
            <th scope="row" colSpan={span} className="px-4 py-3 text-left font-semibold text-primary">
              Total
            </th>
            <td className="px-4 py-3 text-right font-semibold tabular-nums">{fmt(report.total)}</td>
          </tr>
        </tfoot>
      </table>
    </div>
  )
}
