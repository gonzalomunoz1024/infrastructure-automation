import { useEffect, useId, useMemo, useRef, useState, type FormEvent } from 'react'
import { Button, EmptyState, Field, Icon, IconButton, Input, Modal, OverlayBody, OverlayFooter, OverlayHeader, PageHeader, ProgressBar, Select, Textarea, cx, formatNumber, plural } from '@iaf/ui'
import { DotTag, ErrorState, Mono, When } from '@iaf/console-kit'
import { useConsole } from '../../app/console'
import { FailureNote, useCommand } from '../../app/commands'
import { ActionButton } from '../../app/Refusal'
import { Link, resourcePath, type PageProps } from '../../app/router'
import { isApiError } from '../../lib/api'
import { singularOf, type ConsoleType } from '../../lib/descriptors'
import { CONFIDENCE_WORDS, driftApi, moneyText, observedBrief, type DiscoveryRun, type UnmanagedListParams, type UnmanagedObject } from '../../lib/drift'
import type { Decision } from '../../lib/permissions'
import { codeOf } from '../../lib/plain'
import { usePoll } from '../../lib/poll'
import { invalidate, useQuery } from '../../lib/queries'
import { LiveLine } from '../resource/DriftDecisions'

/*
 * Unmanaged resources (WP 5.3, the shadow-infrastructure finder): what a provider holds that no
 * account manages. Each type with a discovery read has a Find unmanaged button that starts a run
 * and shows it going; the objects found are listed with what the provider reports, a guess at
 * the owning account with its reasons in words, and what each costs a month. Import takes an
 * object under management with a new wfrn and links to its page; Assign to team hands it to
 * another account, the guessed one first, and it then shows in both accounts' lists until either
 * imports it. Import and Assign need the type's create permission and the object's `can`.
 */

const FOLLOW_MS = 1_000
const OWNERS = [
  { value: 'any', label: 'Any owner' },
  { value: 'mine', label: 'This account’s' },
  { value: 'unknown', label: 'Owner unknown' },
] as const
type Owner = (typeof OWNERS)[number]['value']

const keyOf = (o: Pick<UnmanagedObject, 'type' | 'providerId'>) => `${o.type}:${o.providerId}`

/** Unmanaged resources: a discovery run per type, then what the runs found, to import or assign. */
export default function UnmanagedPage(_props: PageProps) {
  const { types, decide, account, region } = useConsole()
  const discoverable = useMemo(() => types.filter((t) => t.observe.discover), [types])
  const [type, setType] = useState('')
  const [owner, setOwner] = useState<Owner>('any')
  const [live, setLive] = useState('')
  const [imported, setImported] = useState<{ name: string; wfrn: string; type: ConsoleType }[]>([])
  const [dialog, setDialog] = useState<{ kind: 'import' | 'assign'; object: UnmanagedObject; type: ConsoleType } | null>(null)

  const scope = `${account.id}/${region.name}`
  const params: UnmanagedListParams = { owner, limit: 200, ...(type ? { type } : {}) }
  const list = useQuery(['console-unmanaged', scope, params], () => driftApi.unmanaged.list(params))
  const rows = list.data?.items ?? []
  const typeOf = (name: string) => types.find((t) => t.name === name)

  return (
    <>
      <PageHeader
        title="Unmanaged resources"
        subtitle="What the providers hold that no account manages, with a guess at whose it is and what it costs. Import takes an object under management; Assign to team hands it to the account it belongs to."
        actions={
          <IconButton label="Refresh" onClick={() => invalidate('console-unmanaged')}>
            <Icon name="refresh" size={16} className={cx(list.fetching && 'animate-spin')} />
          </IconButton>
        }
      />

      <section aria-labelledby="unmanaged-find" className="mb-8">
        <h2 id="unmanaged-find" className="mb-1 text-subheadline font-semibold text-primary">
          Find unmanaged
        </h2>
        <p className="mb-3 text-caption text-secondary">A discovery run lists what the type’s provider has in this region and keeps what no account manages. It reads only; nothing changes at the provider.</p>
        {discoverable.length ? (
          <ul className="grid gap-3 md:grid-cols-2">
            {discoverable.map((t) => (
              <li key={t.name}>
                <DiscoveryCard type={t} decision={decide(t, 'observe')} onEnded={(run) => setLive(run.status === 'succeeded' ? `Discovery of ${t.plural} finished: ${plural(run.unmanaged ?? 0, 'unmanaged object')} found.` : `Discovery of ${t.plural} failed${run.error ? `: ${run.error}` : ''}.`)} />
              </li>
            ))}
          </ul>
        ) : (
          <EmptyState compact icon="eye" title="No type can be discovered" message="No provider of this account’s types publishes a discovery read yet." />
        )}
      </section>

      <section aria-labelledby="unmanaged-found">
        <div className="mb-3 flex flex-wrap items-end justify-between gap-3">
          <div className="min-w-0">
            <h2 id="unmanaged-found" className="text-subheadline font-semibold text-primary">
              Found
            </h2>
            {list.data && <p className="text-caption text-secondary">{plural(rows.length, 'object')}, the costliest first</p>}
          </div>
          <div className="flex flex-wrap items-center gap-2">
            <Select size="sm" ariaLabel={`Type: ${type ? (typeOf(type)?.title ?? type) : 'All types'}`} value={type} onChange={setType} options={[{ value: '', label: 'All types' }, ...discoverable.map((t) => ({ value: t.name, label: t.title }))]} />
            <Select size="sm" ariaLabel={`Owner: ${OWNERS.find((o) => o.value === owner)?.label ?? owner}`} value={owner} onChange={(v) => setOwner(v as Owner)} options={OWNERS.map((o) => ({ value: o.value, label: o.label }))} />
          </div>
        </div>

        {imported.length > 0 && (
          <ul className="mb-3 space-y-2" aria-label="Imported here">
            {imported.map((x) => (
              <li key={x.wfrn} className="flex flex-wrap items-center gap-x-2 gap-y-1 rounded-lg border border-stroke-light bg-surface-raised px-4 py-2 text-sm text-primary" data-imported={x.wfrn}>
                <Icon name="check" size={14} className="text-healthy" />
                <span>
                  {x.name} is managed now, as <Mono>{x.wfrn.slice(x.wfrn.lastIndexOf('/') + 1)}</Mono>.
                </span>
                <Link to={resourcePath(x.type.service, x.type.name, x.wfrn)} className="font-medium text-accent hover:underline">
                  Open its page
                </Link>
              </li>
            ))}
          </ul>
        )}

        {list.error && !list.data ? (
          <ErrorState error={list.error} title="Could not load the unmanaged objects" onRetry={list.refetch} />
        ) : list.loading && !list.data ? (
          <p className="text-sm text-secondary">Loading what the last runs found…</p>
        ) : rows.length === 0 ? (
          <EmptyState compact icon="eye" title={type || owner !== 'any' ? 'Nothing matches these filters' : 'Nothing found yet'} message={type || owner !== 'any' ? 'Loosen a filter to see more.' : 'Run Find unmanaged for a type; what its provider has that no account manages shows here.'} />
        ) : (
          <ul className="space-y-3" aria-label="Unmanaged objects">
            {rows.map((o) => {
              const t = typeOf(o.type)
              return (
                <li key={keyOf(o)}>
                  <ObjectCard object={o} type={t} accountId={account.id} create={t ? decide(t, 'create') : { allowed: false }} onImport={() => t && setDialog({ kind: 'import', object: o, type: t })} onAssign={() => t && setDialog({ kind: 'assign', object: o, type: t })} />
                </li>
              )
            })}
          </ul>
        )}
        <LiveLine>{live}</LiveLine>
      </section>

      {dialog?.kind === 'import' && (
        <ImportDialog
          object={dialog.object}
          type={dialog.type}
          onClose={() => setDialog(null)}
          onDone={(name, wfrn) => {
            setImported((xs) => [{ name, wfrn, type: dialog.type }, ...xs])
            setLive(`${name} is managed now. Its page is linked above the list.`)
            setDialog(null)
          }}
        />
      )}
      {dialog?.kind === 'assign' && (
        <AssignDialog
          object={dialog.object}
          type={dialog.type}
          accountId={account.id}
          onClose={() => setDialog(null)}
          onDone={(line) => {
            setLive(line)
            setDialog(null)
          }}
        />
      )}
    </>
  )
}

/** One type's discovery: the button, and the run it started going on the clock until it ends. */
function DiscoveryCard({ type, decision, onEnded }: { type: ConsoleType; decision: Decision; onEnded: (run: DiscoveryRun) => void }) {
  const cmd = useCommand()
  const [runId, setRunId] = useState<string | null>(null)
  const run = useQuery(['console-discover', type.name, runId ?? 'none'], () => driftApi.discover.get(runId!), { enabled: !!runId })
  const current = run.data
  const running = !!runId && (!current || current.status === 'running')
  usePoll(running, () => void run.refetch(), FOLLOW_MS)
  const ended = useRef<string | null>(null)
  useEffect(() => {
    if (!current || current.status === 'running' || ended.current === current.runId) return
    ended.current = current.runId
    invalidate('console-unmanaged')
    onEnded(current)
  }, [current, onEnded])

  const start = async () => {
    const res = await cmd.run(`Find unmanaged ${type.plural}`, async () => {
      try {
        return await driftApi.discover.start(type.name)
      } catch (e) {
        // a run of this type is already going: follow that one
        const detail = isApiError(e) && codeOf(e.error) === 'discover.running' ? (e.detail as Partial<DiscoveryRun> | undefined) : undefined
        if (detail?.runId) return detail as DiscoveryRun
        throw e
      }
    }, { quiet: true })
    if (res) {
      ended.current = null
      setRunId(res.runId)
    }
  }

  return (
    <article className="h-full rounded-lg border border-stroke-light px-4 py-3" data-discover={type.name}>
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="min-w-0">
          <h3 className="text-sm font-medium text-primary">{type.title}</h3>
          <p className="text-caption text-tertiary">{type.owner}</p>
        </div>
        <ActionButton decision={decision} what={`Find unmanaged ${type.plural}`} size="sm" icon="search" onClick={() => void start()} loading={cmd.busy} disabled={running} verb="discover">
          Find unmanaged
        </ActionButton>
      </div>
      {runId && (
        <div className="mt-3 space-y-1">
          {running ? (
            <>
              <ProgressBar indeterminate size="sm" label={`Reading the provider’s ${type.plural}`} />
              <p className="text-caption text-secondary" data-run="running">
                Reading the provider: {formatNumber(current?.seen ?? 0)} seen, {formatNumber(current?.unmanaged ?? 0)} unmanaged so far.
              </p>
            </>
          ) : current?.status === 'succeeded' ? (
            <p className="text-caption text-secondary" data-run="succeeded">
              Done <When at={current.endedAt ?? current.startedAt} />: {formatNumber(current.seen ?? 0)} seen, {plural(current.unmanaged ?? 0, 'unmanaged object')}.
            </p>
          ) : current ? (
            <p className="text-caption text-secondary" data-run="failed">
              The run failed{current.error ? `: ${current.error}` : ''}. Try again in a while.
            </p>
          ) : null}
        </div>
      )}
      <FailureNote failure={cmd.failure} onDismiss={cmd.clear} compact className="mt-3" />
    </article>
  )
}

/** Why an object cannot be imported or assigned though the role allows it, or undefined when it can. */
function notOffered(o: UnmanagedObject, verb: 'import' | 'assign'): string | undefined {
  return o.can.includes(verb) ? undefined : `The platform does not offer ${verb === 'import' ? 'Import' : 'Assign to team'} on this object to you.`
}

/** One unmanaged object: what the provider reports, whose it probably is and why, its cost, and what to do with it. */
function ObjectCard({ object: o, type, accountId, create, onImport, onAssign }: { object: UnmanagedObject; type?: ConsoleType; accountId: string; create: Decision; onImport: () => void; onAssign: () => void }) {
  const reasonId = useId()
  const guess = o.ownerGuess
  const words = guess ? CONFIDENCE_WORDS[guess.confidence] : undefined
  const a = o.assignment
  const blocked = create.allowed ? Array.from(new Set([notOffered(o, 'import'), notOffered(o, 'assign')].filter((x): x is string => !!x))) : []
  const action = (verb: 'import' | 'assign', onClick: () => void, label: string, primary: boolean) =>
    create.allowed && notOffered(o, verb) ? (
      <Button size="sm" variant={primary ? 'primary' : 'secondary'} disabled aria-describedby={reasonId} data-verb={verb}>
        {label}
      </Button>
    ) : (
      <ActionButton decision={create} what={verb === 'import' ? `Import ${o.name ?? o.providerId}` : `Assign ${o.name ?? o.providerId} to a team`} size="sm" variant={primary ? 'primary' : 'secondary'} onClick={onClick} verb={verb}>
        {label}
      </ActionButton>
    )
  return (
    <article className="rounded-lg border border-stroke-light" data-object={o.providerId} aria-label={o.name ?? o.providerId}>
      <header className="flex flex-wrap items-start justify-between gap-x-4 gap-y-1 border-b border-stroke-light px-4 py-3">
        <div className="min-w-0">
          <h3 className="truncate text-sm font-medium text-primary">{o.name ?? o.providerId}</h3>
          <p className="flex flex-wrap items-center gap-x-2 text-caption text-tertiary">
            <span>{type ? singularOf(type.title) : o.type}</span>
            <Mono className="break-all">{o.providerId}</Mono>
          </p>
        </div>
        <p className="text-sm tabular-nums text-primary" title={o.monthlyCost?.basis}>
          {moneyText(o.monthlyCost)}
        </p>
      </header>
      <div className="space-y-2 px-4 py-3 text-sm">
        <p className="text-secondary">
          <span className="text-primary">{observedBrief(o.observed, type)}</span>
          {o.firstSeenAt && (
            <>
              {' '}
              · first seen <When at={o.firstSeenAt} />
            </>
          )}
        </p>
        <div className="flex flex-wrap items-center gap-x-2 gap-y-1">
          {guess && words ? (
            <>
              <DotTag tone={words.tone}>{words.label}</DotTag>
              <span className="text-primary">
                Probably {guess.accountId === accountId ? 'this account’s' : `${guess.accountName ?? guess.accountId}’s`}
                {guess.team ? ` (${guess.team})` : ''}
              </span>
            </>
          ) : (
            <>
              <DotTag tone="neutral">Owner unknown</DotTag>
              <span className="text-secondary">No tag, name or placement points to an account.</span>
            </>
          )}
        </div>
        {guess && guess.reasons.length > 0 && (
          <ul className="list-inside list-disc text-caption text-secondary" aria-label="Why the console guesses so">
            {guess.reasons.map((r) => (
              <li key={r}>{r}</li>
            ))}
          </ul>
        )}
        {a && (
          <p className="text-caption text-primary" data-assignment={a.accountId}>
            <Icon name="send" size={12} className="mr-1 inline text-accent" />
            {a.accountId === accountId ? `Assigned to this account from ${a.fromAccountId}` : `Assigned to ${a.accountName ?? a.accountId}`} by {a.by}, <When at={a.at} />
            {a.note ? `: “${a.note}”` : '.'}
          </p>
        )}
      </div>
      <footer className="space-y-2 border-t border-stroke-light px-4 py-3">
        <div className="flex flex-wrap items-center gap-2">
          {action('import', onImport, 'Import', true)}
          {action('assign', onAssign, 'Assign to team', false)}
        </div>
        {blocked.length > 0 && (
          <p id={reasonId} className="text-caption text-secondary">
            {blocked.join(' ')}
          </p>
        )}
      </footer>
    </article>
  )
}

const NAME = /^[a-z0-9][a-z0-9-]{0,62}$/

/** Import, asked once: the name it will be managed under, then a new wfrn. */
function ImportDialog({ object: o, type, onClose, onDone }: { object: UnmanagedObject; type: ConsoleType; onClose: () => void; onDone: (name: string, wfrn: string) => void }) {
  const cmd = useCommand()
  const id = useId()
  const suggested = (o.name ?? '').toLowerCase()
  const [name, setName] = useState(NAME.test(suggested) ? suggested : '')
  const [tried, setTried] = useState(false)
  const nameError = NAME.test(name.trim()) ? undefined : 'A name is 1 to 63 lower-case letters, digits and dashes, starting with a letter or digit.'
  const submit = async (e: FormEvent) => {
    e.preventDefault()
    setTried(true)
    if (cmd.busy || nameError) return
    const chosen = name.trim()
    const res = await cmd.run(`Import ${o.name ?? o.providerId}`, () => driftApi.unmanaged.import({ type: o.type, providerId: o.providerId, ...(chosen !== o.name ? { name: chosen } : {}) }), { invalidates: ['console-unmanaged', 'console-resources', 'console-resource-counts'], quiet: true })
    if (res) onDone(chosen, res.wfrn)
  }
  return (
    <Modal onClose={onClose} label={`Import ${o.name ?? o.providerId}`} width="max-w-lg">
      <form onSubmit={(e) => void submit(e)} noValidate>
        <OverlayHeader title={`Import ${o.name ?? o.providerId}`} subtitle={`It becomes a managed ${singularOf(type.title).toLowerCase()} of this account, with a wfrn of its own, and leaves the list of unmanaged objects. Nothing changes at the provider.`} icon="download" />
        <OverlayBody>
          <div className="space-y-5">
            <Field label="Name" htmlFor={`${id}-name`} hint="What the console calls it from now on." error={tried ? nameError : undefined} required>
              <Input id={`${id}-name`} data-autofocus value={name} onChange={(e) => setName(e.target.value)} autoComplete="off" />
            </Field>
            <p className="text-caption text-secondary">
              Provider id <Mono>{o.providerId}</Mono>, about {moneyText(o.monthlyCost)}.
            </p>
            <FailureNote failure={cmd.failure} compact shownFields={['name']} />
          </div>
        </OverlayBody>
        <OverlayFooter>
          <Button type="button" variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          <Button type="submit" variant="primary" loading={cmd.busy}>
            Import
          </Button>
        </OverlayFooter>
      </form>
    </Modal>
  )
}

/** Assign to team: the guessed account first, or an account id typed, with a note for its team. */
function AssignDialog({ object: o, type, accountId, onClose, onDone }: { object: UnmanagedObject; type: ConsoleType; accountId: string; onClose: () => void; onDone: (line: string) => void }) {
  const cmd = useCommand()
  const id = useId()
  const guess = o.ownerGuess && o.ownerGuess.accountId !== accountId ? o.ownerGuess : undefined
  const [choice, setChoice] = useState<'guess' | 'typed'>(guess ? 'guess' : 'typed')
  const [typed, setTyped] = useState('')
  const [note, setNote] = useState('')
  const [tried, setTried] = useState(false)
  const target = choice === 'guess' && guess ? guess.accountId : typed.trim()
  const idError = choice === 'typed' && !/^\d{12}$/.test(typed.trim()) ? 'An account id is 12 digits.' : choice === 'typed' && typed.trim() === accountId ? 'That is this account; name the other team’s.' : undefined
  const serverError = cmd.failure?.problems?.find((p) => p.field === '/accountId' || p.field === 'accountId')?.message
  const submit = async (e: FormEvent) => {
    e.preventDefault()
    setTried(true)
    if (cmd.busy || idError) return
    const trimmed = note.trim()
    const res = await cmd.run(`Assign ${o.name ?? o.providerId} to a team`, () => driftApi.unmanaged.assign({ type: o.type, providerId: o.providerId, accountId: target, ...(trimmed ? { note: trimmed } : {}) }), { invalidates: ['console-unmanaged'], quiet: true })
    if (res) onDone(`${o.name ?? o.providerId} is assigned to ${res.assignment?.accountName ?? target}; it shows in both accounts’ lists until either imports it.`)
  }
  return (
    <Modal onClose={onClose} label={`Assign ${o.name ?? o.providerId} to a team`} width="max-w-lg">
      <form onSubmit={(e) => void submit(e)} noValidate>
        <OverlayHeader title={`Assign ${o.name ?? o.providerId} to a team`} subtitle={`The ${singularOf(type.title).toLowerCase()} then also shows in that account’s list, with your note, until either account imports it.`} icon="send" />
        <OverlayBody>
          <div className="space-y-5">
            <fieldset className="space-y-2">
              <legend className="mb-1 text-sm font-medium text-primary">To which account</legend>
              {guess && (
                <label className="flex cursor-pointer items-start gap-2 text-sm text-primary">
                  <input type="radio" name={`${id}-to`} value="guess" checked={choice === 'guess'} onChange={() => setChoice('guess')} data-autofocus className="mt-0.5 size-4 accent-accent" />
                  <span>
                    {guess.accountName ?? guess.accountId}
                    {guess.team ? ` (${guess.team})` : ''}, the guessed owner
                    <span className="block text-caption text-secondary">
                      {CONFIDENCE_WORDS[guess.confidence].label}: {guess.reasons[0] ?? 'no reason given'}. Account <Mono>{guess.accountId}</Mono>
                    </span>
                  </span>
                </label>
              )}
              <label className="flex cursor-pointer items-center gap-2 text-sm text-primary">
                <input type="radio" name={`${id}-to`} value="typed" checked={choice === 'typed'} onChange={() => setChoice('typed')} data-autofocus={guess ? undefined : true} className="size-4 accent-accent" />
                Another account, by its id
              </label>
              {choice === 'typed' && (
                <Field label="Account id" htmlFor={`${id}-account`} hint="The 12 digits of the other team’s account." error={tried ? (idError ?? serverError) : serverError}>
                  <Input id={`${id}-account`} inputMode="numeric" value={typed} onChange={(e) => setTyped(e.target.value)} placeholder="210987654321" autoComplete="off" />
                </Field>
              )}
            </fieldset>
            <Field label="Note" htmlFor={`${id}-note`} hint="Optional. The other team reads it beside the object.">
              <Textarea id={`${id}-note`} rows={3} maxLength={500} value={note} onChange={(e) => setNote(e.target.value)} placeholder="Tagged for your team; yours to import or delete" />
            </Field>
            <FailureNote failure={cmd.failure} compact shownFields={['accountId']} />
          </div>
        </OverlayBody>
        <OverlayFooter>
          <Button type="button" variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          <Button type="submit" variant="primary" loading={cmd.busy}>
            Assign
          </Button>
        </OverlayFooter>
      </form>
    </Modal>
  )
}
