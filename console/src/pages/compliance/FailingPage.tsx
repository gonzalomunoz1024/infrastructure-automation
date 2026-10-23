import { useMemo, useState, type FormEvent } from 'react'
import { Button, Field, Input, Modal, OverlayBody, OverlayFooter, OverlayHeader, PageHeader, Select, SkeletonRows, Table, useToast, type TableColumn } from '@iaf/ui'
import { DotTag, When, type Problem } from '@iaf/console-kit'
import { ExplainedError, FailureNote, useCommand } from '../../app/commands'
import { pathOfWfrn, useConsole } from '../../app/console'
import { ActionButton } from '../../app/Refusal'
import { Link, navigate, pathFor, type PageProps } from '../../app/router'
import { bff } from '../../lib/bff'
import { idOfWfrn } from '../../lib/descriptors'
import type { Decision } from '../../lib/permissions'
import { usePoll } from '../../lib/poll'
import { useQuery } from '../../lib/queries'
import { firstRefusal, roleDecision } from '../../lib/roleGate'
import { useSubmissionKey } from '../../lib/submission'
import { SEVERITY_WORDS, fillDefault, scorecardsApi, type Evaluation, type RemediationInput, type RemediationSpec, type RuleSummary } from '../../lib/scorecards'

/*
 * The resources failing one rule (WP 9.6), the longest failing first, each with why. A rule with
 * a fix offers Remediate on each: a dialog with the fix's inputs, their defaults filled from the
 * resource, then the rule's workflow as a request like any other (or, for a tags fix, the tags set
 * at once). Who may fix is the account's `scorecards:remediate` and the type's own verb.
 */

export default function FailingPage({ route }: PageProps) {
  const { pack, rule } = route.params
  const { account, types, decide } = useConsole()
  const packs = useQuery(['scorecards', 'packs'], () => scorecardsApi.packs())
  const list = useQuery(['scorecards', 'failing', pack, rule], () => scorecardsApi.failing(pack, rule, { limit: 100 }))
  const [fixing, setFixing] = useState<Evaluation | null>(null)
  // a workflow fix passes the rule once its request succeeds: follow the list until its row leaves
  const [following, setFollowing] = useState<string[]>([])
  usePoll(following.some((w) => list.data?.items.some((e) => e.wfrn === w)), () => void list.refetch())
  const packInfo = packs.data?.items.find((p) => p.name === pack)
  const ruleInfo: RuleSummary | undefined = packInfo?.rules.find((r) => r.id === rule)
  const fix = ruleInfo?.remediation

  const decisionFor = (e: Evaluation): Decision => {
    const role = roleDecision(account.roles, 'scorecards:remediate', `Fix ${e.name ?? 'this resource'}`)
    const t = types.find((x) => x.name === e.type)
    const own = t && fix ? decide(t, fix.kind === 'tags' ? 'tag' : (fix.verb ?? 'update')) : { allowed: true as const }
    return firstRefusal([role, own])
  }

  const columns: TableColumn<Evaluation>[] = [
    {
      key: 'name',
      header: 'Resource',
      render: (e) => {
        const href = pathOfWfrn(e.wfrn, types)
        const label = e.name ?? idOfWfrn(e.wfrn) ?? e.wfrn
        return href ? (
          <Link to={href} className="font-medium text-primary hover:text-accent">
            {label}
          </Link>
        ) : (
          label
        )
      },
    },
    { key: 'why', header: 'Why it fails', render: (e) => <span className="text-secondary">{e.message ?? 'It does not meet the rule'}</span> },
    { key: 'since', header: 'Failing since', render: (e) => <When at={e.since} /> },
    {
      key: 'fix',
      header: <span className="sr-only">Fix</span>,
      align: 'right',
      render: (e) =>
        fix ? (
          <ActionButton decision={decisionFor(e)} what={`Fix ${e.name ?? 'this resource'}`} verb="remediate" size="sm" icon="bolt" onClick={() => setFixing(e)}>
            Remediate
          </ActionButton>
        ) : null,
    },
  ]

  return (
    <>
      <PageHeader
        eyebrow={packInfo?.title ?? pack}
        title={ruleInfo?.title ?? rule}
        subtitle={ruleInfo?.description ?? 'The resources that fail this rule, the longest failing first.'}
        actions={ruleInfo && <DotTag tone={SEVERITY_WORDS[ruleInfo.severity].tone}>{SEVERITY_WORDS[ruleInfo.severity].label}</DotTag>}
      />
      {ruleInfo && <p className="mb-4 text-sm text-secondary">{fix ? `Its fix: ${fix.title}${fix.workflow ? `, through the ${fix.workflow} workflow` : ''}. A fix is never automatic.` : 'This rule has no fix in the console: change the resource by hand.'}</p>}
      {list.error && !list.data ? (
        <ExplainedError error={list.error} what="Load the failing resources" onRetry={() => void list.refetch()} />
      ) : list.loading && !list.data ? (
        <SkeletonRows n={4} />
      ) : (
        <Table caption={`Resources failing ${ruleInfo?.title ?? rule}`} columns={columns} rows={list.data?.items ?? []} rowKey={(e) => e.wfrn} emptyIcon="check" emptyTitle="Nothing fails this rule" emptyMessage="Every resource it applies to passes." />
      )}
      {fixing && fix && <RemediateDialog pack={pack} rule={rule} spec={fix} evaluation={fixing} onClose={() => setFixing(null)} onStarted={(w) => setFollowing((f) => [...f, w])} />}
    </>
  )
}

function RemediateDialog({ pack, rule, spec, evaluation: e, onClose, onStarted }: { pack: string; rule: string; spec: RemediationSpec; evaluation: Evaluation; onClose: () => void; onStarted: (wfrn: string) => void }) {
  const cmd = useCommand()
  const toast = useToast()
  const keyFor = useSubmissionKey()
  const resource = useQuery(['console-resource', e.type, e.wfrn], () => bff.resources.get(e.type, e.wfrn))
  const defaults = useMemo(() => Object.fromEntries(spec.inputs.map((i) => [i.name, fillDefault(i.default, resource.data)])), [spec.inputs, resource.data])
  const [values, setValues] = useState<Record<string, string>>({})
  const [problems, setProblems] = useState<Problem[]>([])
  const name = e.name ?? idOfWfrn(e.wfrn) ?? 'this resource'
  const valueOf = (i: RemediationInput) => values[i.name] ?? (defaults[i.name] === undefined || defaults[i.name] === null ? '' : String(defaults[i.name]))
  const missing = spec.inputs.filter((i) => i.required && !valueOf(i))

  const submit = async (ev: FormEvent) => {
    ev.preventDefault()
    if (missing.length) return
    const input = Object.fromEntries(spec.inputs.filter((i) => valueOf(i) !== '').map((i) => [i.name, i.type === 'integer' || i.type === 'number' ? Number(valueOf(i)) : i.type === 'boolean' ? valueOf(i) === 'true' : valueOf(i)]))
    const body = { wfrn: e.wfrn, ...(spec.inputs.length ? { input } : {}) }
    const done = await cmd.run(`Fix ${name}`, () => scorecardsApi.remediate(pack, rule, body, keyFor({ pack, rule, ...body })), { invalidates: ['scorecards', 'resources', 'jobs', 'console-resource'], quiet: true, onProblems: setProblems })
    if (!done) return
    onClose()
    if (done.jobId) {
      const jobId = done.jobId
      onStarted(e.wfrn)
      toast.toast({ tone: 'healthy', title: `Fixing ${name}`, message: `The ${done.workflow ?? 'fix'} request started; the rule passes once it succeeds.`, action: { label: 'View request', onClick: () => navigate(pathFor('request', { id: jobId })) } })
    } else toast.toast({ tone: 'healthy', title: `${name} is fixed`, message: 'Its tags are set; the rule passes now.' })
  }

  return (
    <Modal onClose={onClose} label={`Fix ${name}`} width="max-w-lg">
      <form onSubmit={(ev) => void submit(ev)} noValidate>
        <OverlayHeader title={`Fix ${name}`} subtitle={`${spec.title}${spec.kind === 'workflow' ? `: a ${spec.workflow ?? spec.verb} request, approved like any other.` : ': the tags are set at once.'}`} icon="bolt" />
        <OverlayBody>
          <div className="space-y-5">
            <p className="text-sm text-secondary">{e.message ?? 'It does not meet the rule.'}</p>
            {spec.inputs.length === 0 && <p className="text-sm text-primary">It needs nothing from you.</p>}
            {spec.inputs.map((i, n) => {
              const v = valueOf(i)
              const err = problems.find((p) => p.field === `/input/${i.name}`)?.message
              return i.enum ? (
                <Field key={i.name} label={i.title} required={i.required} error={err}>
                  <Select ariaLabel={`${i.title}: ${v || 'Choose'}`} value={v} onChange={(x) => setValues({ ...values, [i.name]: x })} placeholder="Choose" options={i.enum.map((o) => ({ value: String(o), label: String(o) }))} />
                </Field>
              ) : (
                <Field key={i.name} label={i.title} htmlFor={`fix-${i.name}`} required={i.required} error={err}>
                  <Input id={`fix-${i.name}`} data-autofocus={n === 0 ? true : undefined} value={v} onChange={(x) => setValues({ ...values, [i.name]: x.target.value })} invalid={!!err} autoComplete="off" />
                </Field>
              )
            })}
            <FailureNote failure={cmd.failure} shownFields={['input']} compact />
          </div>
        </OverlayBody>
        <OverlayFooter>
          <Button type="button" variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          <Button type="submit" variant="primary" loading={cmd.busy} disabled={missing.length > 0}>
            {spec.kind === 'workflow' ? 'Start the fix' : 'Set the tags'}
          </Button>
        </OverlayFooter>
      </form>
    </Modal>
  )
}
