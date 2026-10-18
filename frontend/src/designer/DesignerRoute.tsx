/**
 * What the designer page opens: `#/workflows/new` (the New workflow dialog, optionally
 * `?template=<id>`), `#/workflows/:name` (the saved draft, or the document of a workflow just
 * created when the backend has not caught up), `#/workflows/:name?template=<id>` for a
 * name not taken yet, which starts that workflow from the template, and
 * `#/workflows/:name?job=<id>` (the job page's "Open in designer"), which opens on that run.
 */
import { useEffect, useState } from 'react'
import { Button, EmptyState } from '@iaf/ui'
import type { WorkflowDocument } from '@iaf/workflow-checks'
import { api, errorMessage, isApiError } from '../lib/api'
import { clearQueryCache, keyOf, useQuery } from '../lib/queries'
import type { Template, WorkflowDetail } from '../lib/types'
import { navigate, pathFor, type Route } from '../app/router'
import { Designer } from './Designer'
import { DesignerSkeleton } from './DesignerSkeleton'
import { forgetDocument, peekDocument } from './handoff'
import { newDocument, withOwnerPolicy } from './model/edits'
import { clone } from './model/fixes'
import { NewWorkflowModal, type NewWorkflowMeta } from './chrome/NewWorkflowModal'

/** A template's document under the new workflow's names; one whose policy names no role or team lets the owner team run it. */
export function fromTemplate(doc: WorkflowDocument, meta: NewWorkflowMeta): WorkflowDocument {
  const next = clone(doc)
  const before = next.metadata ?? {}
  next.metadata = { ...before, name: meta.name, title: meta.title, noun: meta.noun, verb: meta.verb, owner: meta.owner, folder: meta.folder, version: '0.1.0' }
  if (!meta.folder) delete next.metadata.folder
  const base = newDocument(meta)
  if (!next.api) next.api = base.api
  else if (before.noun !== meta.noun || before.verb !== meta.verb || !next.api.path) next.api = { ...next.api, path: base.api?.path }
  if (!next.workflow) next.workflow = { do: [] }
  return withOwnerPolicy(next, meta.owner)
}

function useTemplate(id: string | null): { loading: boolean; error: string | null; template: Template | null; doc: WorkflowDocument | null } {
  const list = useQuery(['templates'], () => api.templates(), { enabled: !!id })
  const template = list.data?.find((t) => t.id === id) ?? null
  const parsed = useQuery(['designer-template', id ?? ''], () => api.parseWorkflow({ yaml: template?.yaml ?? '' }), { enabled: !!template })
  if (!id) return { loading: false, error: null, template: null, doc: null }
  if (list.error) return { loading: false, error: errorMessage(list.error), template: null, doc: null }
  if (list.data && !template) return { loading: false, error: `There is no template "${id}"`, template: null, doc: null }
  if (parsed.error) return { loading: false, error: errorMessage(parsed.error), template, doc: null }
  if (parsed.data && !parsed.data.definition) return { loading: false, error: parsed.data.problems[0]?.message ?? 'The template does not parse', template, doc: null }
  return { loading: !parsed.data, error: null, template, doc: parsed.data?.definition ?? null }
}

function NewWorkflow({ template, presetName }: { template: string | null; presetName?: string }) {
  const tpl = useTemplate(template)
  const [doc, setDoc] = useState<WorkflowDocument | null>(null)
  if (doc) return <Designer name={null} doc={doc} detail={null} isNew onCreated={(name) => navigate(pathFor('workflow', { name }), { replace: true })} />
  if (template && tpl.loading) return <DesignerSkeleton label="Loading the template" />
  const m = tpl.doc?.metadata
  const initial: Partial<NewWorkflowMeta> = { title: m?.title, noun: m?.noun, verb: m?.verb, folder: m?.folder }
  if (presetName) initial.name = presetName
  return (
    <div className="bg-dot-grid h-full w-full">
      {tpl.error && (
        <p className="absolute left-1/2 top-6 -translate-x-1/2 rounded-full bg-critical-tint px-3 py-1 text-caption text-critical" role="alert">
          {tpl.error}; starting from a blank workflow
        </p>
      )}
      <NewWorkflowModal
        initial={initial}
        templateTitle={tpl.template?.title}
        onCreate={(meta) => setDoc(tpl.doc ? fromTemplate(tpl.doc, meta) : newDocument(meta))}
        onClose={() => {
          if (!doc) navigate('/workflows')
        }}
      />
    </div>
  )
}

function ExistingWorkflow({ name, template, job }: { name: string; template: string | null; job: string | null }) {
  // always open the draft as the server has it now, never a cached copy
  const [handoff] = useState(() => {
    clearQueryCache(keyOf(['workflow', name]))
    return peekDocument(name)
  })
  const [waiting, setWaiting] = useState(handoff !== null)
  const q = useQuery(['workflow', name], () => api.workflow(name), { refetchInterval: waiting ? 3000 : undefined })
  const [opened, setOpened] = useState<{ doc: WorkflowDocument; detail: WorkflowDetail | null } | null>(handoff ? { doc: handoff, detail: null } : null)
  // the state follows the answer during render (React's derived-state pattern): the first answer
  // opens the designer and ends the polling for a workflow the backend had not caught up with
  if (!opened && q.data) setOpened({ doc: q.data.definition, detail: q.data })
  if (waiting && q.data) setWaiting(false)

  useEffect(() => {
    if (q.data) forgetDocument(name)
  }, [q.data, name])

  if (opened) return <Designer name={name} doc={opened.doc} detail={q.data ?? opened.detail} isNew={false} job={job} />
  if (q.error) {
    if (isApiError(q.error) && q.error.status === 404 && template) return <NewWorkflow template={template} presetName={name} />
    const missing = isApiError(q.error) && q.error.status === 404
    return (
      <EmptyState
        icon={missing ? 'search' : 'alert-triangle'}
        title={missing ? `There is no workflow named ${name}` : 'Could not open the workflow'}
        message={missing ? 'It may have been deleted, or the link has a typo.' : errorMessage(q.error)}
        action={
          <>
            <Button variant="secondary" onClick={() => navigate('/workflows')}>
              Back to workflows
            </Button>
            {!missing && (
              <Button variant="primary" onClick={() => void q.refetch()}>
                Try again
              </Button>
            )}
          </>
        }
      />
    )
  }
  return <DesignerSkeleton />
}

export default function DesignerRoute({ route }: { route: Route }) {
  if (route.name === 'workflow-new') return <NewWorkflow template={route.query.template ?? null} />
  return <ExistingWorkflow name={route.params.name ?? ''} template={route.query.template ?? null} job={route.query.job ?? null} />
}
