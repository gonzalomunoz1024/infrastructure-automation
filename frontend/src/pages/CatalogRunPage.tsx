import { useCallback, useMemo, useRef, useState } from 'react'
import { Button, Card, CardRow, CodeBlock, Field, Icon, IconButton, Input, PageHeader, Segmented, SkeletonRows, Table, Tag, Textarea, cx, plural, useToast, type TableColumn } from '@iaf/ui'
import type { PageProps } from '../app/router'
import { navigate, pathFor } from '../app/router'
import { API_BASE, api, errorMessage, isApiError } from '../lib/api'
import { useQuery } from '../lib/queries'
import type { BatchRowProblem, CatalogEntry, Preview, Problem, Rollout } from '../lib/types'
import { PreviewCard } from '../components/PreviewCard'
import { ProblemList } from '../components/ProblemList'
import { SchemaForm, coerceBySchema, coerceScalar, defaultsFor, fieldNames, splitProblems, validateAgainst, type SchemaValue } from '../components/SchemaForm'
import { csvToRows, emitCsv } from '../components/csv'
import { downloadText } from '../components/download'
import { parseJsonText } from '../components/JsonEditor'
import { DeprecatedTag, DeprecationNotice } from '../components/Deprecation'
import { ErrorState, Mono, TextLink } from '../components/common'

type Mode = 'one' | 'many'

export default function CatalogRunPage({ route }: PageProps) {
  const catalog = useQuery('catalog', () => api.catalog())
  const entry = catalog.data?.find((e) => e.workflow === route.params.workflow)
  const [mode, setMode] = useState<Mode>('one')

  if (catalog.error && !catalog.data) {
    return (
      <>
        <PageHeader eyebrow="Run" title={route.params.workflow} back={{ href: `#${pathFor('catalog')}`, label: 'Catalog' }} />
        <ErrorState error={catalog.error} title="Could not load the catalog" onRetry={() => void catalog.refetch()} />
      </>
    )
  }
  if (catalog.loading || !catalog.data) {
    return (
      <>
        <PageHeader eyebrow="Run" title={route.params.workflow} back={{ href: `#${pathFor('catalog')}`, label: 'Catalog' }} />
        <SkeletonRows n={4} />
      </>
    )
  }
  if (!entry) {
    return (
      <>
        <PageHeader eyebrow="Run" title={route.params.workflow} back={{ href: `#${pathFor('catalog')}`, label: 'Catalog' }} />
        <ErrorState error={new Error(`${route.params.workflow} is not published, so it cannot be run from the catalog.`)} title="Not in the catalog" />
      </>
    )
  }

  return (
    <>
      <PageHeader
        eyebrow="Run"
        title={entry.title}
        subtitle={
          <span className="inline-flex flex-wrap items-center gap-2">
            {entry.deprecated && <DeprecatedTag deprecated={entry.deprecated} />}
            <span>{entry.description ?? `${entry.verb} a ${entry.noun}`}</span>
          </span>
        }
        back={{ href: `#${pathFor('catalog')}`, label: 'Catalog' }}
        actions={
          <Segmented
            ariaLabel="How many"
            value={mode}
            onChange={setMode}
            options={[
              { value: 'one', label: 'One' },
              { value: 'many', label: 'Many', disabled: !entry.batchPath },
            ]}
          />
        }
      />
      <div className="grid grid-cols-1 gap-5 lg:grid-cols-[minmax(0,1fr)_20rem]">
        <div className="min-w-0">{mode === 'one' ? <OneRun entry={entry} /> : <ManyRun entry={entry} />}</div>
        <About entry={entry} catalog={catalog.data} />
      </div>
    </>
  )
}

function About({ entry, catalog }: { entry: CatalogEntry; catalog?: CatalogEntry[] }) {
  return (
    <div className="space-y-4">
      {entry.deprecated && <DeprecationNotice deprecated={entry.deprecated} catalog={catalog} />}
      <Card title="About">
        <CardRow label="Endpoint" stacked>
          <span className="inline-flex min-w-0 items-center gap-1.5">
            <Tag size="sm" mono outline>
              {entry.method.toUpperCase()}
            </Tag>
            <Mono className="break-all text-primary">{entry.path}</Mono>
          </span>
        </CardRow>
        <CardRow label="Version">
          <Mono className="text-primary">{entry.version}</Mono>
        </CardRow>
        <CardRow label="Owner">
          <Tag size="sm" outline>
            {entry.owner}
          </Tag>
        </CardRow>
        {entry.mcpTool && (
          <CardRow label="MCP tool">
            <Mono className="text-primary">{entry.mcpTool}</Mono>
          </CardRow>
        )}
        <CardRow label="Bulk runs" description={`waves ${entry.batch.rollout.waves.join(' / ')}% · ${entry.batch.rollout.maxParallel} in parallel`}>
          <span>up to {entry.batch.max}</span>
        </CardRow>
        {entry.batchPath && (
          <CardRow label="Batch endpoint" stacked>
            <span className="inline-flex min-w-0 items-center gap-1.5">
              <Tag size="sm" mono outline>
                POST
              </Tag>
              <Mono className="break-all text-primary">{entry.batchPath}</Mono>
            </span>
          </CardRow>
        )}
        <CardRow label="Workflow">
          <TextLink to={pathFor('workflow', { name: entry.workflow })} mono>
            {entry.workflow}
          </TextLink>
        </CardRow>
      </Card>
    </div>
  )
}

/* ----------------------------------------------------------------------------
   One: the generated form, a preview, then the run
   ------------------------------------------------------------------------- */

/** The same call as a shell command, against this origin; the body is the form's input. */
export function curlFor(entry: Pick<CatalogEntry, 'method' | 'path'>, input: SchemaValue, origin = typeof window === 'undefined' ? '' : window.location.origin): string {
  const filled = entry.path.replace(/\{([^}]+)\}/g, (_, k: string) => (input[k] === undefined ? `{${k}}` : encodeURIComponent(String(input[k]))))
  const path = /^https?:\/\//.test(filled) || filled.startsWith('/api/') ? filled : `${API_BASE}${filled}`
  const url = /^https?:\/\//.test(path) ? path : `${origin}${path}`
  const method = entry.method.toUpperCase()
  const quote = (t: string) => `'${t.replace(/'/g, `'\\''`)}'`
  if (method === 'GET' || method === 'HEAD') return `curl ${quote(url)}`
  return `curl -X ${method} ${quote(url)} \\\n  -H 'Content-Type: application/json' \\\n  -d ${quote(JSON.stringify(input))}`
}

function OneRun({ entry }: { entry: CatalogEntry }) {
  const toast = useToast()
  const [value, setValue] = useState<SchemaValue>(() => defaultsFor(entry.input))
  const [problems, setProblems] = useState<Problem[]>([])
  const [preview, setPreview] = useState<Preview | null>(null)
  const [busy, setBusy] = useState<'preview' | 'run' | null>(null)
  const { fielded, general } = splitProblems(problems, entry.input)
  const names = fieldNames(entry.input)

  const check = (): boolean => {
    const local = validateAgainst(entry.input, value)
    setProblems(local)
    return local.length === 0
  }

  const doPreview = async () => {
    if (!check()) return
    setBusy('preview')
    try {
      setPreview(await api.preview(entry, value))
    } catch (e) {
      if (isApiError(e) && e.problems) setProblems(e.problems)
      toast.error('Preview failed', errorMessage(e))
    } finally {
      setBusy(null)
    }
  }

  const doRun = async () => {
    if (!check()) return
    setBusy('run')
    try {
      const accepted = await api.run(entry, value)
      toast.success('Run started', `Job ${accepted.jobId} is queued.`)
      navigate(pathFor('job', { id: accepted.jobId }))
    } catch (e) {
      if (isApiError(e) && e.problems) setProblems(e.problems)
      toast.error(isApiError(e) && e.status === 403 ? 'You may not run this workflow' : 'Could not start the run', errorMessage(e))
      setBusy(null)
    }
  }

  return (
    <div className="space-y-5">
      <Card title="Input" subtitle={names.length ? `${plural(names.length, 'field')}; required ones are marked.` : undefined}>
        <form
          onSubmit={(e) => {
            e.preventDefault()
            void doRun()
          }}
          className="space-y-5"
        >
          {general.length > 0 && <ProblemList problems={general} compact />}
          <SchemaForm schema={entry.input} value={value} onChange={(v) => { setValue(v); setPreview(null) }} problems={fielded} idPrefix={`run-${entry.workflow}`} columns={2} />
          <div className="flex flex-wrap items-center justify-end gap-2 border-t border-stroke-light pt-4">
            <Button variant="ghost" onClick={() => { setValue(defaultsFor(entry.input)); setProblems([]); setPreview(null) }}>
              Reset
            </Button>
            <Button icon="eye" loading={busy === 'preview'} disabled={busy === 'run'} onClick={() => void doPreview()}>
              Preview
            </Button>
            <Button type="submit" variant="primary" icon="play" loading={busy === 'run'} disabled={busy === 'preview'}>
              Run
            </Button>
          </div>
        </form>
      </Card>

      {preview && <PreviewCard preview={preview} className="animate-fade-in" />}

      <details className="group rounded-lg border border-stroke-light bg-surface">
        <summary className="flex cursor-pointer items-center gap-2 px-5 py-3 text-sm text-secondary transition-colors hover:text-primary">
          <Icon name="terminal" size={15} className="text-tertiary" />
          Call it from the API
          <Icon name="chevron-down" size={13} className="ml-auto text-tertiary transition-transform group-open:rotate-180" />
        </summary>
        <div className="px-5 pb-5">
          <CodeBlock code={curlFor(entry, value)} language="bash" />
          <p className="mt-2 text-caption text-tertiary">Answers 202 with the job id. The same input works as the MCP tool{entry.mcpTool ? ` ${entry.mcpTool}` : ''}.</p>
        </div>
      </details>
    </div>
  )
}

/* ----------------------------------------------------------------------------
   Many: rows from CSV or JSON, defaults, rollout, one preview, the batch
   ------------------------------------------------------------------------- */

interface RowState {
  id: number
  value: SchemaValue
}

function ManyRun({ entry }: { entry: CatalogEntry }) {
  const toast = useToast()
  const names = fieldNames(entry.input)
  const [text, setText] = useState('')
  const [rows, setRows] = useState<RowState[]>([])
  const [defaults, setDefaults] = useState<SchemaValue>(() => defaultsFor(entry.input))
  const [rollout, setRollout] = useState<Rollout>(() => ({ ...entry.batch.rollout }))
  const [wavesText, setWavesText] = useState(() => entry.batch.rollout.waves.join(', '))
  const [rowProblems, setRowProblems] = useState<Map<number, Problem[]>>(new Map())
  const [validated, setValidated] = useState(false)
  const [preview, setPreview] = useState<Preview | null>(null)
  const [busy, setBusy] = useState<'preview' | 'batch' | null>(null)
  const seq = useRef(0)
  const fileRef = useRef<HTMLInputElement>(null)

  const load = (source: string) => {
    const trimmed = source.trim()
    if (!trimmed) return
    let items: SchemaValue[]
    if (trimmed.startsWith('[')) {
      const parsed = parseJsonText(trimmed)
      if (parsed.error || !Array.isArray(parsed.value)) {
        toast.error('Could not read the rows', parsed.error ?? 'Expected a JSON list of objects')
        return
      }
      items = (parsed.value as unknown[]).filter((v): v is SchemaValue => !!v && typeof v === 'object' && !Array.isArray(v))
    } else {
      const table = csvToRows(trimmed)
      const unknown = table.header.filter((h) => !names.includes(h))
      if (unknown.length) toast.toast({ title: 'Unknown columns', message: `${unknown.join(', ')} are not input fields; they are kept as given.`, tone: 'warning' })
      items = table.rows.map((r) => coerceBySchema(entry.input, r))
    }
    if (items.length > entry.batch.max) toast.toast({ title: `More than ${entry.batch.max} rows`, message: `This workflow allows ${entry.batch.max} per batch; the first ${entry.batch.max} are kept.`, tone: 'warning' })
    setRows(items.slice(0, entry.batch.max).map((value) => ({ id: ++seq.current, value })))
    setRowProblems(new Map())
    setValidated(false)
    setPreview(null)
  }

  const onFile = (file: File | undefined) => {
    if (!file) return
    void file.text().then((t) => {
      setText(t)
      load(t)
    })
  }

  const setCell = useCallback(
    (id: number, name: string, raw: string) => {
      setRows((rs) => rs.map((r) => (r.id === id ? { id, value: { ...r.value, ...(raw === '' ? { [name]: undefined } : { [name]: coerceScalar(entry.input.properties?.[name], raw) }) } } : r)))
      setValidated(false)
    },
    [entry.input],
  )

  const merged = (r: RowState): SchemaValue => ({ ...defaults, ...Object.fromEntries(Object.entries(r.value).filter(([, v]) => v !== undefined)) })

  const validate = (): boolean => {
    const map = new Map<number, Problem[]>()
    rows.forEach((r, i) => {
      const ps = validateAgainst(entry.input, merged(r))
      if (ps.length) map.set(i, ps)
    })
    setRowProblems(map)
    setValidated(true)
    return map.size === 0
  }

  const applyRollout = (): Rollout | null => {
    const waves = wavesText
      .split(/[,\s]+/)
      .filter(Boolean)
      .map(Number)
    if (waves.some((w) => !Number.isFinite(w) || w <= 0 || w > 100)) {
      toast.error('Waves must be percentages', 'For example 5, 25, 100.')
      return null
    }
    const r = { ...rollout, waves }
    setRollout(r)
    return r
  }

  const doPreview = async () => {
    if (rows.length === 0 || !validate()) return
    setBusy('preview')
    try {
      setPreview(await api.preview(entry, merged(rows[0])))
    } catch (e) {
      toast.error('Preview failed', errorMessage(e))
    } finally {
      setBusy(null)
    }
  }

  const start = async () => {
    if (rows.length === 0 || !validate()) return
    const r = applyRollout()
    if (!r) return
    setBusy('batch')
    try {
      const accepted = await api.runBatch(entry, { items: rows.map((x) => x.value), defaults, rollout: r })
      toast.success('Batch started', `${plural(rows.length, 'row')} in ${r.waves.length} waves; job ${accepted.jobId}.`)
      navigate(pathFor('job', { id: accepted.jobId }))
    } catch (e) {
      if (isApiError(e) && e.rows) {
        const map = new Map<number, Problem[]>()
        for (const rp of e.rows as BatchRowProblem[]) map.set(rp.index, rp.problems)
        setRowProblems(map)
        setValidated(true)
        toast.error('Some rows are invalid', `${map.size} of ${rows.length} rows need a fix.`)
      } else toast.error('Could not start the batch', errorMessage(e))
      setBusy(null)
    }
  }

  const columns: TableColumn<RowState>[] = useMemo(() => {
    const cols: TableColumn<RowState>[] = [
      {
        key: 'n',
        header: '#',
        width: '3rem',
        render: (_, i) => {
          const ps = rowProblems.get(i)
          return (
            <span className={cx('tabular-nums', ps ? 'font-medium text-critical' : 'text-tertiary')} title={ps?.map((p) => p.message).join('\n')}>
              {i + 1}
            </span>
          )
        },
      },
      ...names.map(
        (name): TableColumn<RowState> => ({
          key: name,
          header: (
            <span>
              {entry.input.properties?.[name]?.title ?? name}
              {entry.input.required?.includes(name) && <span className="ml-0.5 text-critical">*</span>}
            </span>
          ),
          render: (r, i) => {
            const v = r.value[name]
            const bad = rowProblems.get(i)?.some((p) => (p.field ?? '').replace(/^input\./, '') === name)
            return (
              <Input
                size="sm"
                mono
                aria-label={`Row ${i + 1} ${name}`}
                value={v === undefined || v === null ? '' : typeof v === 'object' ? JSON.stringify(v) : String(v)}
                onChange={(e) => setCell(r.id, name, e.target.value)}
                invalid={bad}
                placeholder={defaults[name] !== undefined ? String(defaults[name]) : undefined}
                className="min-w-24"
              />
            )
          },
        }),
      ),
      {
        key: 'remove',
        header: <span className="sr-only">Remove</span>,
        width: '2.5rem',
        render: (r) => (
          <IconButton label="Remove row" size="sm" onClick={() => setRows((rs) => rs.filter((x) => x.id !== r.id))}>
            <Icon name="trash" size={14} />
          </IconButton>
        ),
      },
    ]
    return cols
  }, [names, rowProblems, defaults, entry.input, setCell])

  const problemCount = Array.from(rowProblems.values()).reduce((n, ps) => n + ps.length, 0)

  return (
    <div className="space-y-5">
      <Card
        title="Rows"
        subtitle={`One row per ${entry.noun}; the header names the input fields. CSV, tab-separated, or a JSON list.`}
        actions={
          <>
            <Button size="sm" variant="ghost" icon="download" onClick={() => downloadText(emitCsv([], names), `${entry.workflow}-rows.csv`, 'text/csv')}>
              CSV template
            </Button>
            <Button size="sm" icon="upload" onClick={() => fileRef.current?.click()}>
              Upload
            </Button>
            <input ref={fileRef} type="file" accept=".csv,.tsv,.txt,.json,text/csv,application/json" className="hidden" onChange={(e) => onFile(e.target.files?.[0])} />
          </>
        }
      >
        <div className="space-y-3">
          <Textarea mono rows={4} value={text} onChange={(e) => setText(e.target.value)} placeholder={`${names.join(',')}\n…`} aria-label="Rows as CSV or JSON" />
          <div className="flex items-center justify-between gap-3">
            <p className="text-caption text-tertiary">{rows.length ? `${plural(rows.length, 'row')} loaded${problemCount ? `, ${plural(problemCount, 'problem')}` : validated ? ', all valid' : ''}` : `Up to ${entry.batch.max} rows.`}</p>
            <Button size="sm" icon="list" disabled={!text.trim()} onClick={() => load(text)}>
              Load rows
            </Button>
          </div>
        </div>

        {rows.length > 0 && (
          <div className="mt-4 space-y-3">
            <Table columns={columns} rows={rows} rowKey={(r) => String(r.id)} dense maxHeight="max-h-[28rem]" caption="Batch rows" />
            <div className="flex items-center justify-between">
              <Button size="sm" variant="ghost" icon="plus" onClick={() => setRows((rs) => [...rs, { id: ++seq.current, value: {} }])}>
                Add row
              </Button>
              {problemCount > 0 && (
                <ProblemList
                  compact
                  problems={Array.from(rowProblems.entries()).flatMap(([i, ps]) => ps.map((p) => ({ ...p, message: `Row ${i + 1}: ${p.message}` })))}
                  className="max-w-xl"
                />
              )}
            </div>
          </div>
        )}
      </Card>

      <Card title="Defaults" subtitle="Applied to every row that leaves the field empty.">
        <SchemaForm schema={entry.input} value={defaults} onChange={setDefaults} idPrefix={`defaults-${entry.workflow}`} columns={2} optional pickers={false} />
      </Card>

      <Card title="Rollout" subtitle={`The workflow's own defaults: waves ${entry.batch.rollout.waves.join(' / ')}%, ${entry.batch.rollout.maxParallel} in parallel, stop at ${entry.batch.rollout.stopIfFailedPercent}% failed.`}>
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
          <Field label="Waves (%)" htmlFor="rollout-waves" hint="Each wave runs that share of the rows, then the gates decide">
            <Input id="rollout-waves" mono value={wavesText} onChange={(e) => setWavesText(e.target.value)} onBlur={applyRollout} />
          </Field>
          <Field label="Max in parallel" htmlFor="rollout-parallel">
            <Input id="rollout-parallel" type="number" min={1} value={rollout.maxParallel} onChange={(e) => setRollout({ ...rollout, maxParallel: Math.max(1, Number(e.target.value) || 1) })} />
          </Field>
          <Field label="Stop if failed (%)" htmlFor="rollout-stop">
            <Input id="rollout-stop" type="number" min={0} max={100} value={rollout.stopIfFailedPercent} onChange={(e) => setRollout({ ...rollout, stopIfFailedPercent: Math.min(100, Math.max(0, Number(e.target.value) || 0)) })} />
          </Field>
        </div>
      </Card>

      {preview && <PreviewCard preview={preview} title="Preview of the first row" className="animate-fade-in" />}

      <div className="flex flex-wrap items-center justify-end gap-2">
        <Button icon="check" disabled={rows.length === 0} onClick={validate}>
          Validate rows
        </Button>
        <Button icon="eye" disabled={rows.length === 0 || busy === 'batch'} loading={busy === 'preview'} onClick={() => void doPreview()}>
          Preview
        </Button>
        <Button variant="primary" icon="play" disabled={rows.length === 0 || busy === 'preview'} loading={busy === 'batch'} onClick={() => void start()}>
          Start batch of {rows.length}
        </Button>
      </div>
    </div>
  )
}
