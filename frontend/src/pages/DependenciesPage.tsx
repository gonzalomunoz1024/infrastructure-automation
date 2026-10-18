import { Fragment, type ReactNode } from 'react'
import { Card, EmptyState, Icon, PageHeader, SkeletonRows, Table, Tag, type TableColumn } from '@iaf/ui'
import type { PageProps } from '../app/router'
import { pathFor } from '../app/router'
import { api } from '../lib/api'
import { useQuery } from '../lib/queries'
import type { Dependencies, DependencyLink, ResourceTypeDependencies, WorkflowDependencies } from '../lib/types'
import { ErrorState, Facts, Mono, Section, TextLink } from '../components/common'

/*
 * The dependency graph of the published workflows: who produces and consumes each resource
 * type, what each workflow produces, consumes and runs, who runs it, and the cycles among runs.
 * Every workflow links to its designer page and every resource type to its resources.
 */

/** What a link means, read next to the workflow's name: where it sits and how. */
export function linkNote(link: DependencyLink): string {
  const at = link.at
  switch (link.via) {
    case 'producedBy':
      return 'declared producer'
    case 'register':
      return at ? `registers one in step ${at}` : 'registers one'
    case 'input':
      return at ? `input ${at}` : 'as an input'
    case 'use':
      return at ? `uses one in step ${at}` : 'uses one'
    case 'run':
      return at ? `step ${at}` : 'a run step'
    case 'orCreateWith':
      return at ? `builds one first for input ${at}` : 'builds one first'
    default:
      return at ?? ''
  }
}

/** ['a', 'b', 'a'] → "a runs b, and b runs a again". */
export function describeCycle(cycle: string[]): string {
  const hops: string[] = []
  for (let i = 0; i + 1 < cycle.length; i++) hops.push(`${cycle[i]} runs ${cycle[i + 1]}`)
  if (hops.length === 0) return cycle.join(' → ')
  if (hops.length === 1) return `${hops[0]} again`
  return `${hops.slice(0, -1).join(', ')}, and ${hops[hops.length - 1]} again`
}

function WorkflowLink({ name }: { name: string }) {
  return (
    <TextLink to={pathFor('workflow', { name })} mono className="text-primary">
      {name}
    </TextLink>
  )
}

function TypeLink({ type }: { type: string }) {
  return (
    <TextLink to={pathFor('resources', {}, { type })} mono className="text-primary">
      {type}
    </TextLink>
  )
}

/** A workflow with how it is tied: "create-vm · registers one in step register-vm", with the version range as a capsule. */
function LinkChip({ link }: { link: DependencyLink }) {
  return (
    <span className="inline-flex max-w-full flex-wrap items-center gap-x-1.5 gap-y-0.5">
      <WorkflowLink name={link.workflow} />
      {link.range && (
        <Tag size="sm" outline mono title="Version range of the run step">
          {link.range}
        </Tag>
      )}
      <span className="text-caption text-tertiary">{linkNote(link)}</span>
    </span>
  )
}

function Chips({ children, empty }: { children: ReactNode[]; empty: string }) {
  if (children.length === 0) return <span className="text-caption text-tertiary">{empty}</span>
  return (
    <ul className="flex flex-wrap gap-x-4 gap-y-1">
      {children.map((c, i) => (
        <li key={i} className="min-w-0">
          {c}
        </li>
      ))}
    </ul>
  )
}

export function CyclesCallout({ cycles }: { cycles: string[][] }) {
  if (cycles.length === 0) return null
  return (
    <section aria-label="Cycles among runs" className="rounded-lg border border-warning/40 bg-warning-tint p-4 sm:p-5">
      <div className="flex items-start gap-3">
        <Icon name="alert-triangle" size={18} className="mt-0.5 shrink-0 text-warning" />
        <div className="min-w-0 space-y-2">
          <h2 className="text-subheadline font-semibold text-primary">{cycles.length === 1 ? 'These workflows run each other in a loop' : `${cycles.length} loops among run steps`}</h2>
          <p className="text-sm text-secondary">A workflow that ends up starting itself would never finish. Break each loop by taking one of the run steps out.</p>
          <ul className="space-y-1.5">
            {cycles.map((cycle, i) => (
              <li key={i} className="text-sm text-primary">
                <span className="inline-flex flex-wrap items-center gap-1 font-mono text-caption">
                  {cycle.map((name, j) => (
                    <Fragment key={`${name}-${j}`}>
                      {j > 0 && <Icon name="arrow-right" size={11} className="text-tertiary" />}
                      <WorkflowLink name={name} />
                    </Fragment>
                  ))}
                </span>
                <span className="ml-2 text-caption text-secondary">{describeCycle(cycle)}</span>
              </li>
            ))}
          </ul>
        </div>
      </div>
    </section>
  )
}

const typeColumns: TableColumn<ResourceTypeDependencies>[] = [
  {
    key: 'type',
    header: 'Resource type',
    width: '11rem',
    sortable: true,
    accessor: (t) => t.type,
    render: (t) => <TypeLink type={t.type} />,
  },
  {
    key: 'producers',
    header: 'Produced by',
    render: (t) => (
      <Chips empty="nothing produces it yet">
        {t.producers.map((l, i) => (
          <LinkChip key={`${l.workflow}-${l.via}-${i}`} link={l} />
        ))}
      </Chips>
    ),
  },
  {
    key: 'consumers',
    header: 'Consumed by',
    render: (t) => (
      <Chips empty="nothing consumes it">
        {t.consumers.map((l, i) => (
          <LinkChip key={`${l.workflow}-${l.via}-${i}`} link={l} />
        ))}
      </Chips>
    ),
  },
]

export function WorkflowCard({ w, cycles }: { w: WorkflowDependencies; cycles: string[][] }) {
  const inLoop = cycles.some((c) => c.includes(w.workflow))
  return (
    <Card
      title={<WorkflowLink name={w.workflow} />}
      subtitle={
        <span className="inline-flex flex-wrap items-center gap-2">
          <Mono>{w.version}</Mono>
          {inLoop && (
            <Tag size="sm" tone="warning" dot>
              in a loop
            </Tag>
          )}
        </span>
      }
      data-testid={`dependencies-${w.workflow}`}
    >
      <Facts
        columns={2}
        items={[
          {
            label: 'Produces',
            value: (
              <Chips empty="no resource">
                {w.produces.map((t) => (
                  <TypeLink key={t} type={t} />
                ))}
              </Chips>
            ),
          },
          {
            label: 'Consumes',
            value: (
              <Chips empty="no resource">
                {w.consumes.map((t) => (
                  <TypeLink key={t} type={t} />
                ))}
              </Chips>
            ),
          },
          {
            label: 'Runs',
            value: (
              <Chips empty="no other workflow">
                {w.runs.map((l, i) => (
                  <LinkChip key={`${l.workflow}-${l.via}-${i}`} link={l} />
                ))}
              </Chips>
            ),
            wide: true,
          },
          {
            label: 'Run by',
            value: (
              <Chips empty="no other workflow">
                {w.consumers.map((l, i) => (
                  <LinkChip key={`${l.workflow}-${l.via}-${i}`} link={l} />
                ))}
              </Chips>
            ),
            wide: true,
          },
        ]}
      />
    </Card>
  )
}

/** The whole graph, once loaded. */
export function DependenciesView({ data }: { data: Dependencies }) {
  return (
    <div className="space-y-6">
      <CyclesCallout cycles={data.cycles} />
      <Section title="Resource types" description="Who creates each kind of resource, and who needs one to run.">
        <Table columns={typeColumns} rows={data.resourceTypes} rowKey={(t) => t.type} caption="Resource types" emptyIcon="resource" emptyTitle="No resource types" emptyMessage="Nothing is produced or consumed yet." defaultSort={{ key: 'type', dir: 'asc' }} />
      </Section>
      <Section title="Workflows" description="What each published workflow produces, consumes and runs, and who runs it.">
        {data.workflows.length === 0 ? (
          <EmptyState icon="workflow" title="No published workflows" message="Publish a workflow and its dependencies show up here." />
        ) : (
          <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
            {data.workflows.map((w) => (
              <WorkflowCard key={w.workflow} w={w} cycles={data.cycles} />
            ))}
          </div>
        )}
      </Section>
    </div>
  )
}

export default function DependenciesPage(_: PageProps) {
  const deps = useQuery('registry-dependencies', () => api.registryDependencies())
  return (
    <>
      <PageHeader title="Dependencies" subtitle="Who produces and consumes each resource type, and which published workflows run which." />
      {deps.error && !deps.data ? (
        <ErrorState error={deps.error} title="Could not load the dependencies" onRetry={() => void deps.refetch()} />
      ) : !deps.data ? (
        <SkeletonRows n={6} />
      ) : (
        <DependenciesView data={deps.data} />
      )}
    </>
  )
}
