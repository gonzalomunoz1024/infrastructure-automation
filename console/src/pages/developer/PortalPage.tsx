import { useMemo, useState, type ReactNode } from 'react'
import { Button, Card, CopyButton, Icon, PageHeader, Select, Skeleton, cx, plural } from '@iaf/ui'
import { CodeView, ErrorState, Section } from '@iaf/console-kit'
import { useConsole } from '../../app/console'
import { Link, pathFor, type PageProps } from '../../app/router'
import { bff } from '../../lib/bff'
import { CONTRACT_URL, OPENAPI_PATH, apiGroupsOf, isSafeLink, fetchApiDocument, portalSamplesFor, readShortcuts, toggleShortcut, writeShortcuts, type PortalTile } from '../../lib/portal'
import { denialTitle } from '../../lib/permissions'
import { useQuery } from '../../lib/queries'
import { tagsApi } from '../../lib/tags'
import type { ConsoleType } from '../../lib/descriptors'

/*
 * The developer portal (WP 9.16): the console API as the BFF describes it (`/console/v1/openapi.json`,
 * operations by tag, with the full contract linked), how to get the CLI and the SDKs, sample calls
 * for a type built from its own workflows' schemas, and the tiles a firm's internal developer
 * portal embeds. The tiles are samples until the firm's own examples arrive, and say so; any of
 * them can be kept as a shortcut, which this browser remembers for the person. A workflow tile is
 * greyed, with the reason, where the person may not run its workflow.
 */

const METHOD_TONE: Record<string, string> = { GET: 'text-accent', POST: 'text-primary', PUT: 'text-primary', PATCH: 'text-primary', DELETE: 'text-critical' }

export default function PortalPage(_props: PageProps) {
  return (
    <>
      <PageHeader title="Developer portal" subtitle="Everything to build on the console: its API, the CLI and SDKs, sample calls for each resource type, and the tiles your team’s portal can show." />
      <div className="space-y-10">
        <ApiSection />
        <GetSection />
        <SamplesSection />
        <TilesSection />
      </div>
    </>
  )
}

/* ---------------------------------------------------------------- the API */

function ApiSection() {
  const doc = useQuery(['portal', 'openapi'], fetchApiDocument)
  const groups = useMemo(() => (doc.data ? apiGroupsOf(doc.data) : []), [doc.data])
  const count = groups.reduce((n, g) => n + g.operations.length, 0)
  return (
    <Section
      title="The console API"
      description={doc.data?.info?.description ?? 'The API the console, the CLI and the SDKs share. Every call acts as a signed-in person, with their access.'}
      actions={
        <>
          <a href={OPENAPI_PATH} target="_blank" rel="noreferrer" className="inline-flex items-center gap-1 text-sm text-accent hover:underline">
            OpenAPI document <Icon name="external" size={11} />
          </a>
          <a href={CONTRACT_URL} target="_blank" rel="noreferrer" className="inline-flex items-center gap-1 text-sm text-accent hover:underline">
            Full contract <Icon name="external" size={11} />
          </a>
        </>
      }
    >
      {doc.error ? (
        <ErrorState error={doc.error} compact title="The console does not serve its API description here" onRetry={() => void doc.refetch()} />
      ) : !doc.data ? (
        <Skeleton className="h-40 w-full" />
      ) : (
        <>
          <p className="mb-3 text-caption text-secondary" data-api-count={count}>
            {plural(count, 'operation')} in {plural(groups.length, 'group')}
            {doc.data.info?.version ? `, version ${doc.data.info.version}` : ''}.
          </p>
          <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
            {groups.map((g) => (
              <Card key={g.tag} title={g.tag} data-api-group={g.tag}>
                <ul className="space-y-1.5">
                  {g.operations.map((op) => (
                    <li key={`${op.method} ${op.path}`} className="min-w-0">
                      <p className="break-all font-mono text-caption">
                        <span className={cx('mr-2 font-semibold', METHOD_TONE[op.method])}>{op.method}</span>
                        <span className="text-primary">{op.path}</span>
                      </p>
                      <p className="text-caption text-secondary">{op.summary}</p>
                    </li>
                  ))}
                </ul>
              </Card>
            ))}
          </div>
        </>
      )}
    </Section>
  )
}

/* ---------------------------------------------------------------- getting the CLI and SDKs */

function Snippet({ label, code }: { label: string; code: string }) {
  return (
    <div className="mt-2 flex items-start gap-2 rounded-md bg-surface-secondary px-3 py-2">
      <pre tabIndex={0} aria-label={label} className="min-w-0 rounded outline-none focus-visible:ring-2 focus-visible:ring-accent flex-1 overflow-x-auto whitespace-pre font-mono text-caption text-primary">
        {code}
      </pre>
      <CopyButton text={code} label={`Copy: ${label}`} />
    </div>
  )
}

function GetSection() {
  return (
    <Section title="Get the CLI and SDKs" description="Generated from the console API, so they offer exactly what it does. They sign in as you through the browser, never with a password.">
      <div className="grid grid-cols-1 gap-4 lg:grid-cols-3">
        <Card title="The console CLI" subtitle="iaf, for a terminal">
          <Snippet label="Install the CLI" code={'npm install -g @iaf/console-cli\niaf login'} />
          <p className="mt-3 text-caption text-secondary">
            iaf login prints a code. Approve it on the{' '}
            <Link to={pathFor('device')} className="text-accent underline underline-offset-2">
              sign-in page
            </Link>
            ; see what is signed in under{' '}
            <Link to={pathFor('iam-cli')} className="text-accent underline underline-offset-2">
              CLI sign-ins
            </Link>
            . Or skip the install and use the{' '}
            <Link to={pathFor('shell')} className="text-accent underline underline-offset-2">
              console shell
            </Link>
            .
          </p>
        </Card>
        <Card title="TypeScript SDK" subtitle="For Node and the browser">
          <Snippet label="Install the TypeScript SDK" code="npm install @iaf/console-sdk" />
        </Card>
        <Card title="Java SDK" subtitle="For Java 21 and later">
          <Snippet label="Add the Java SDK" code={'<dependency>\n  <groupId>com.iaf</groupId>\n  <artifactId>console-sdk</artifactId>\n</dependency>'} />
        </Card>
      </div>
      <p className="mt-3 text-caption text-secondary">Python and Go follow. Until the first release, build them from the console-sdk branch of the repository.</p>
    </Section>
  )
}

/* ---------------------------------------------------------------- sample calls */

function SamplesSection() {
  const { types, account, region } = useConsole()
  const choices = useMemo(() => types.filter((t) => t.create || t.actions.length), [types])
  const [picked, setPicked] = useState<string>('')
  const type = choices.find((t) => `${t.service}/${t.name}` === picked) ?? choices[0]
  const policy = useQuery(['portal', 'tag-policy', account.id], () => tagsApi.policy())
  const samples = useMemo(() => (type ? portalSamplesFor(type, { accountId: account.id, region: region.name, partition: region.partition }, policy.data?.required ?? []) : []), [type, account.id, region.name, region.partition, policy.data])
  return (
    <Section title="Sample calls" description="Built from the type’s own workflows: the fields they ask for, their examples and defaults, and the tags every new resource carries.">
      {type ? (
        <Card>
          <div className="mb-4 flex flex-wrap items-center gap-3">
            <Select ariaLabel={`Resource type: ${type.title}`} value={`${type.service}/${type.name}`} onChange={setPicked} options={choices.map((t) => ({ value: `${t.service}/${t.name}`, label: t.title, hint: t.name }))} />
            <span className="text-caption text-secondary">{type.description ?? `${type.title} in ${type.service}`}</span>
          </div>
          <CodeView key={type.name} samples={samples} ariaLabel={`Sample calls for ${type.title}`} wrap />
        </Card>
      ) : (
        <Skeleton className="h-40 w-full" />
      )}
    </Section>
  )
}

/* ---------------------------------------------------------------- portal tiles */

function TileFigure({ loading, children }: { loading: boolean; children: ReactNode }) {
  return loading ? <Skeleton className="h-8 w-24" /> : <p className="text-title2 font-semibold tabular-nums text-primary">{children}</p>
}

function TilesSection() {
  const { types, me, decide } = useConsole()
  const [shortcuts, setShortcuts] = useState<string[]>(() => readShortcuts(me.id))
  const counted = useMemo(() => types.slice(0, 4), [types])
  const counts = useQuery(['portal', 'counts', counted.map((t) => t.name).join(',')], () => Promise.all(counted.map((t) => bff.resources.counts(t.name))), { enabled: counted.length > 0 })
  const total = counts.data?.reduce((n, c) => n + c.total, 0) ?? 0
  const drifting = counts.data?.reduce((n, c) => n + c.drift, 0) ?? 0

  const workflowTiles: PortalTile[] = types
    .filter((t): t is ConsoleType & { create: NonNullable<ConsoleType['create']> } => !!t.create)
    .slice(0, 3)
    .map((t) => ({ id: `create-${t.service}-${t.name}`, kind: 'workflow', title: t.create.title || `Create ${t.title}`, description: t.create.description ?? `Start the ${t.create.workflow} workflow`, to: pathFor('create', { service: t.service, type: t.name }), workflow: { service: t.service, type: t.name, verb: 'create' } }))
  const offered: PortalTile[] = [
    { id: 'team-resources', kind: 'resources', title: 'Team resources', description: 'What the account runs, across its first resource types', to: pathFor('home') },
    { id: 'drift', kind: 'drift', title: 'Drifting resources', description: 'Where what runs differs from what was asked for', to: pathFor('drift') },
    { id: 'cost', kind: 'cost', title: 'Cost this month', description: 'Spend against the team’s budget', to: pathFor('home') },
    ...workflowTiles,
  ]
  // a tile's target becomes an href only when it is a console route or https
  const tiles = offered.filter((t) => isSafeLink(t.to))
  const kept = tiles.filter((t) => shortcuts.includes(t.id))

  const toggle = (id: string) => {
    const next = toggleShortcut(shortcuts, id)
    setShortcuts(next)
    writeShortcuts(me.id, next)
  }

  const body = (t: PortalTile): ReactNode => {
    switch (t.kind) {
      case 'resources':
        return (
          <>
            <TileFigure loading={counts.loading}>{total.toLocaleString()}</TileFigure>
            <p className="text-caption text-secondary">{counted.map((c) => c.title).join(', ')}</p>
          </>
        )
      case 'drift':
        return (
          <>
            <TileFigure loading={counts.loading}>{drifting.toLocaleString()}</TileFigure>
            <p className="text-caption text-secondary">{drifting ? 'drifting now' : 'Everything is as asked'}</p>
          </>
        )
      case 'cost':
        return (
          <>
            <TileFigure loading={false}>$12,480</TileFigure>
            <p className="text-caption text-secondary">A sample figure; cost arrives with the cost service.</p>
          </>
        )
      default:
        return null
    }
  }

  return (
    <Section title="Portal tiles" description="Tiles your team’s internal developer portal shows, each opening the console where it matters. Keep any of them here as a shortcut.">
      <p className="mb-4 flex gap-2 rounded-md bg-surface-secondary px-3 py-2 text-caption text-primary" data-tiles-note>
        <Icon name="info" size={14} className="mt-0.5 shrink-0 text-accent" />
        These tiles are samples. The firm’s own tiles replace them when its examples arrive.
      </p>

      <div className="mb-6" aria-live="polite">
        <h3 className="text-sm font-semibold text-primary">Your shortcuts</h3>
        {kept.length === 0 ? (
          <p className="mt-1 text-caption text-secondary">None yet. Keep a tile below as a shortcut.</p>
        ) : (
          <ul className="mt-2 flex flex-wrap gap-2" aria-label="Your shortcuts">
            {kept.map((t) => (
              <li key={t.id} data-shortcut={t.id}>
                <Link to={t.to} className="inline-flex items-center gap-1.5 rounded-full bg-accent-tint px-3 py-1 text-sm font-medium text-primary transition-opacity hover:opacity-80">
                  <Icon name={t.kind === 'workflow' ? 'workflow' : 'bolt'} size={13} className="text-accent" />
                  {t.title}
                </Link>
              </li>
            ))}
          </ul>
        )}
      </div>

      <ul className="grid grid-cols-1 gap-4 sm:grid-cols-2 xl:grid-cols-3" aria-label="Sample tiles">
        {tiles.map((t) => {
          const decision = t.workflow ? decide({ service: t.workflow.service, name: t.workflow.type }, t.workflow.verb) : { allowed: true }
          const on = shortcuts.includes(t.id)
          return (
            <li key={t.id} data-tile={t.id}>
              <Card className="flex h-full flex-col">
                <div className="flex items-start justify-between gap-2">
                  <div className="min-w-0">
                    <h4 className="text-sm font-semibold text-primary">{t.title}</h4>
                    <p className="mt-0.5 text-caption text-secondary">{t.description}</p>
                  </div>
                  <span className="shrink-0 rounded-full bg-surface-secondary px-2 py-0.5 text-caption2 font-medium text-secondary">Sample</span>
                </div>
                <div className="mt-3 flex-1">{body(t)}</div>
                {!decision.allowed && decision.denied && <p className="mt-3 text-caption text-secondary">{denialTitle(decision.denied)}, so this workflow cannot be kept as a shortcut.</p>}
                <div className="mt-4 flex flex-wrap items-center gap-2">
                  <Link to={t.to} className="text-sm text-accent underline underline-offset-2">
                    Open<span className="sr-only"> {t.title}</span>
                  </Link>
                  <Button size="sm" variant={on ? 'secondary' : 'ghost'} icon={on ? 'check' : 'plus'} aria-pressed={on} disabled={!decision.allowed && !on} onClick={() => toggle(t.id)}>
                    Keep as shortcut<span className="sr-only">: {t.title}</span>
                  </Button>
                </div>
              </Card>
            </li>
          )
        })}
      </ul>

      <div className="mt-6">
        <h3 className="mb-2 text-sm font-semibold text-primary">Show a tile in your portal</h3>
        <CodeView
          ariaLabel="Embedding a tile"
          samples={[
            { id: 'react', label: 'React', language: 'tsx', code: "import { ConsoleTile } from '@iaf/console-portal-tiles'\n\n<ConsoleTile tile=\"team-resources\" account=\"<account id>\" />\n", note: 'A sample: the plugin’s final shape follows the firm’s portal.' },
          ]}
        />
      </div>
    </Section>
  )
}
