import { EmptyState, PageHeader, SkeletonRows } from '@iaf/ui'
import { DotTag, Facts, Section } from '@iaf/console-kit'
import { ExplainedError } from '../../app/commands'
import { useConsole } from '../../app/console'
import { ActionButton } from '../../app/Refusal'
import { Link, navigate, pathFor, type PageProps } from '../../app/router'
import { blueprintsApi, countsLine, type Blueprint } from '../../lib/blueprints'
import { singularOf } from '../../lib/descriptors'
import { useQuery } from '../../lib/queries'
import { roleDecision } from '../../lib/roleGate'

/*
 * The blueprint catalog (WP 9.7): the versioned stacks the platform's teams publish, each with
 * what it launches, and a blueprint's own page: its parameters, its resources wave by wave (what
 * each waits for, what teardown does with it) and what it hands back once launched. Launch opens
 * the wizard; who may launch is the account's `blueprints:launch`, and each type's own create.
 */

/** "virtual machine" or "virtual machines", in the type's own title when the console knows it. */
export function useTypeWords() {
  const { types } = useConsole()
  return (type: string, n = 1) => {
    const t = types.find((x) => x.name === type)
    const title = (t?.title ?? type).toLowerCase()
    return n === 1 ? singularOf(title) : title
  }
}

/** "a virtual machine", "an image". */
export function withArticle(word: string): string {
  return `${/^[aeiou]/i.test(word) ? 'an' : 'a'} ${word}`
}

export default function BlueprintsPage() {
  const { account } = useConsole()
  const words = useTypeWords()
  const list = useQuery(['blueprints'], () => blueprintsApi.list())
  return (
    <>
      <PageHeader
        title="Blueprints"
        subtitle="Stacks of resources your platform teams have put together, launched as one request and torn down as one. Each is versioned: a stack keeps the version it was launched from."
        actions={
          <Link to={pathFor('stacks')} className="text-sm font-medium text-accent hover:opacity-70">
            Your stacks
          </Link>
        }
      />
      {list.loading && !list.data ? (
        <SkeletonRows n={3} rowClassName="h-28" />
      ) : list.error && !list.data ? (
        <ExplainedError error={list.error} what="Load the blueprints" onRetry={() => void list.refetch()} />
      ) : !list.data?.items.length ? (
        <EmptyState icon="layers" title="No blueprints yet" message="A blueprint is a set of resources launched together. Platform teams publish them on the hub." />
      ) : (
        <ul aria-label={`Blueprints for ${account.name}`} className="grid grid-cols-1 gap-4 md:grid-cols-2 xl:grid-cols-3">
          {list.data.items.map((b) => (
            <li key={b.name} className="flex min-w-0 flex-col rounded-xl border border-stroke-light bg-surface p-4">
              <p className="flex flex-wrap items-center gap-2">
                <Link to={pathFor('blueprint', { name: b.name })} className="text-headline font-semibold text-primary hover:text-accent">
                  {b.title}
                </Link>
                <span className="text-caption text-tertiary">v{b.version}</span>
              </p>
              <p className="mt-1 text-sm text-secondary">{b.description}</p>
              <p className="mt-3 text-caption text-secondary">Launches {countsLine(b.resources, words)}</p>
              <p className="mt-auto flex flex-wrap items-center gap-1.5 pt-3 text-caption text-tertiary">
                <span>by {b.owner}</span>
                {(b.tags ?? []).map((t) => (
                  <DotTag key={t} size="sm">
                    {t}
                  </DotTag>
                ))}
              </p>
            </li>
          ))}
        </ul>
      )}
    </>
  )
}

/** One blueprint, as the launch form draws it. */
export function BlueprintPage({ route }: PageProps) {
  const { account } = useConsole()
  const words = useTypeWords()
  const name = route.params.name
  const q = useQuery(['blueprint', name], () => blueprintsApi.get(name))
  const bp = q.data
  if (q.loading && !bp) return <SkeletonRows n={4} />
  if (!bp) return <ExplainedError error={q.error} what="Load this blueprint" onRetry={() => void q.refetch()} />
  const launch = roleDecision(account.roles, 'blueprints:launch', `Launch ${bp.title}`)
  return (
    <>
      <PageHeader
        eyebrow="Blueprint"
        title={bp.title}
        subtitle={bp.description}
        actions={
          <ActionButton decision={launch} what={`Launch ${bp.title}`} variant="primary" icon="play" verb="launch" onClick={() => navigate(pathFor('blueprint-launch', { name: bp.name }))}>
            Launch
          </ActionButton>
        }
      />
      <Facts
        columns={4}
        className="mb-8"
        items={[
          { label: 'Version', value: bp.version },
          { label: 'Owner', value: bp.owner },
          { label: 'Launches', value: countsLine(Object.values(bp.resources).reduce<Record<string, number>>((acc, r) => ({ ...acc, [r.type]: (acc[r.type] ?? 0) + 1 }), {}), words) },
          { label: 'Waves', value: String(bp.waves.length) },
        ]}
      />
      <Waves blueprint={bp} />
      <Section title="Parameters" description="What you choose when you launch it." className="mt-8">
        <ul className="divide-y divide-stroke-light rounded-lg border border-stroke-light">
          {Object.entries(bp.parameters).map(([k, p]) => (
            <li key={k} className="px-4 py-3 text-sm">
              <p className="font-medium text-primary">
                {p.title}
                {p.required && <span className="ml-2 text-caption font-normal text-secondary">required</span>}
              </p>
              {p.description && <p className="text-caption text-secondary">{p.description}</p>}
              <p className="mt-0.5 text-caption text-tertiary">
                {p['x-resource'] ? `A registered ${words(p['x-resource'].type ?? 'resource')}` : p.enum ? `One of ${p.enum.join(', ')}` : p.type === 'integer' ? `A whole number${p.minimum !== undefined ? ` from ${p.minimum}` : ''}${p.maximum !== undefined ? ` to ${p.maximum}` : ''}` : 'Text'}
                {p.default !== undefined && `; ${String(p.default)} unless you choose`}
              </p>
            </li>
          ))}
        </ul>
      </Section>
      {Object.keys(bp.outputs).length > 0 && (
        <Section title="What it hands back" description="Filled in once every resource is active." className="mt-8">
          <Facts columns={3} items={Object.values(bp.outputs).map((o) => ({ label: o.title, value: <span className="text-secondary">{o.description ?? 'Shown on the stack’s page'}</span> }))} />
        </Section>
      )}
    </>
  )
}

/** The resources wave by wave: a wave starts once the one before it is done; teardown runs the other way. */
export function Waves({ blueprint }: { blueprint: Blueprint }) {
  const words = useTypeWords()
  return (
    <Section title="What it creates" description="Each wave starts once everything it waits for is active. Teardown deletes in the other order and keeps what the blueprint retains.">
      <ol aria-label="Waves" className="space-y-3">
        {blueprint.waves.map((wave, i) => (
          <li key={i} className="rounded-lg border border-stroke-light px-4 py-3">
            <p className="text-caption font-medium uppercase tracking-wide text-tertiary">Wave {i + 1}</p>
            <ul className="mt-1.5 space-y-1.5">
              {wave.map((id) => {
                const r = blueprint.resources[id]
                return (
                  <li key={id} className="text-sm">
                    <span className="font-medium text-primary">{r.title ?? id}</span>
                    <span className="text-secondary">{`, ${withArticle(words(r.type))}`}</span>
                    {r.dependsOn.length > 0 && <span className="text-secondary">{`, after ${r.dependsOn.map((d) => blueprint.resources[d]?.title ?? d).join(' and ')}`}</span>}
                    {r.deletionPolicy === 'retain' && <span className="ml-2 text-caption text-tertiary">kept at teardown</span>}
                  </li>
                )
              })}
            </ul>
          </li>
        ))}
      </ol>
    </Section>
  )
}
