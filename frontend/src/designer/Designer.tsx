/**
 * The workflow designer: one store per open workflow, the header, the Editor · Executions ·
 * Settings views, the side panels and sheets (the Provider sheet among them), and the keys that
 * work everywhere (⌘S saves, ⌘↵ tests). The page decides what to open; this module is the lazily
 * loaded chunk.
 */
import { lazy, Suspense, useCallback, useEffect, useState } from 'react'
import { ConfirmDialog, Skeleton, SkeletonRows, useHotkey, useToast } from '@iaf/ui'
import type { WorkflowDocument } from '@iaf/workflow-checks'
import { errorMessage } from '../lib/api'
import { invalidate } from '../lib/queries'
import type { WorkflowDetail } from '../lib/types'
import { navigate } from '../app/router'
import { DesignerContext, DesignerStore, useDesignerSelector, useDesignerStore } from './model/store'
import { rememberDocument } from './handoff'
import { TopBar } from './chrome/TopBar'
import { IssuesDrawer } from './chrome/IssuesDrawer'
import { YamlDrawer } from './chrome/YamlDrawer'
import { TestRunSheet } from './chrome/TestRun'
import { EditorView } from './views/EditorView'

// the editor opens first; the other views and panels load when they are first shown
const ExecutionsView = lazy(() => import('./views/ExecutionsView').then((m) => ({ default: m.ExecutionsView })))
const SettingsView = lazy(() => import('./views/SettingsView').then((m) => ({ default: m.SettingsView })))
const AssistantPanel = lazy(() => import('./chrome/AssistantPanel').then((m) => ({ default: m.AssistantPanel })))
const PublishSheet = lazy(() => import('./chrome/Publish').then((m) => ({ default: m.PublishSheet })))
const ChangeRequestDrawer = lazy(() => import('./chrome/Publish').then((m) => ({ default: m.ChangeRequestDrawer })))
const ProviderSheet = lazy(() => import('./chrome/ProviderSheet').then((m) => ({ default: m.ProviderSheet })))

function ViewSkeleton() {
  return (
    <div className="flex h-full items-center justify-center bg-dot-grid" role="status" aria-label="Loading">
      <Skeleton className="h-24 w-96" />
    </div>
  )
}

function PanelSkeleton() {
  return (
    <aside className="w-[380px] max-w-full shrink-0 border-l border-stroke-light bg-surface p-4" role="status" aria-label="Loading the assistant">
      <SkeletonRows n={4} rowClassName="h-10" className="space-y-3" />
    </aside>
  )
}

export interface DesignerProps {
  /** null until a new workflow is first saved */
  name: string | null
  doc: WorkflowDocument
  detail: WorkflowDetail | null
  isNew: boolean
  /** a new workflow was created under this name */
  onCreated?: (name: string) => void
  /** open on this job's run (`?job=`): the Executions view, with its step results in every node */
  job?: string | null
}

type Panel = 'issues' | 'yaml' | 'test' | 'publish' | null

function Shell({ onCreated }: { onCreated?: (name: string) => void }) {
  const store = useDesignerStore()
  const toast = useToast()
  const tab = useDesignerSelector((s) => s.tab)
  const name = useDesignerSelector((s) => s.name)
  const providerSheet = useDesignerSelector((s) => s.providerSheet !== null)
  const [panel, setPanel] = useState<Panel>(null)
  const [assistant, setAssistant] = useState(false)
  const [leaving, setLeaving] = useState(false)
  const [changeRequest, setChangeRequest] = useState<string | null>(null)

  const save = useCallback(async () => {
    const st = store.getState()
    if (st.saving) return
    const wasNew = st.isNew
    try {
      const saved = await store.save()
      const n = saved.problems.length
      invalidate('workflows')
      invalidate(`workflow/${saved.name}`)
      toast.toast({
        title: wasNew ? 'Workflow created' : 'Saved',
        message: n === 0 ? 'No problems found' : `${n} ${n === 1 ? 'problem' : 'problems'} to fix before publishing, see Issues`,
        tone: n === 0 ? 'healthy' : 'warning',
      })
      if (wasNew) {
        rememberDocument(saved.name, store.getState().doc)
        onCreated?.(saved.name)
      }
    } catch (e) {
      toast.error('Could not save', errorMessage(e))
    }
  }, [store, toast, onCreated])

  const [validating, setValidating] = useState(false)
  const validate = useCallback(async () => {
    if (validating) return
    setValidating(true)
    try {
      const result = await store.validate()
      const publish = result.problems.filter((p) => p.code.startsWith('publish.')).length
      const n = result.problems.length
      toast.toast({
        title: n === 0 ? 'Nothing to fix' : `${n} ${n === 1 ? 'problem' : 'problems'} found`,
        message: n === 0 ? 'The backend finds no problem; publishing would pass its checks.' : publish > 0 ? `${publish} ${publish === 1 ? 'is' : 'are'} what publishing would refuse, see Before publishing in Issues` : 'See Issues',
        tone: n === 0 ? 'healthy' : result.valid ? 'warning' : 'critical',
      })
      if (n > 0) setPanel('issues')
    } catch (e) {
      toast.error('Could not validate', errorMessage(e))
    } finally {
      setValidating(false)
    }
  }, [store, toast, validating])

  useHotkey('mod+s', () => void save(), { allowInInputs: true })
  useHotkey(
    'mod+enter',
    () => {
      if (document.querySelector('[aria-modal="true"]')) return
      const st = store.getState()
      if (st.tab !== 'editor' || st.testRun?.status === 'running') return
      if (st.graph.nodes.some((n) => n.data.kind === 'step')) setPanel('test')
    },
    { allowInInputs: true },
  )

  useEffect(() => {
    const warn = (e: BeforeUnloadEvent) => {
      if (store.getState().dirty) e.preventDefault()
    }
    window.addEventListener('beforeunload', warn)
    return () => window.removeEventListener('beforeunload', warn)
  }, [store])

  const back = () => {
    if (store.getState().dirty) setLeaving(true)
    else navigate('/workflows')
  }

  return (
    <div className="flex h-full flex-col bg-surface" data-testid="designer">
      <TopBar
        onBack={back}
        onIssues={() => setPanel('issues')}
        onYaml={() => setPanel('yaml')}
        onAssistant={() => setAssistant((a) => !a)}
        onSave={() => void save()}
        onPublish={() => setPanel('publish')}
        onValidate={() => void validate()}
        validating={validating}
        assistantOpen={assistant}
      />
      <div className="relative flex min-h-0 flex-1">
        <div className="relative min-w-0 flex-1">
          {tab === 'editor' && <EditorView onTest={() => setPanel('test')} />}
          <Suspense fallback={<ViewSkeleton />}>
            {tab === 'executions' && <ExecutionsView />}
            {tab === 'settings' && <SettingsView />}
          </Suspense>
        </div>
        {assistant && (
          <Suspense fallback={<PanelSkeleton />}>
            <AssistantPanel onClose={() => setAssistant(false)} />
          </Suspense>
        )}
      </div>
      {panel === 'issues' && <IssuesDrawer onClose={() => setPanel(null)} />}
      {panel === 'yaml' && <YamlDrawer onClose={() => setPanel(null)} />}
      {panel === 'test' && <TestRunSheet onClose={() => setPanel(null)} />}
      {/* one boundary each: the drawer loading must not blank the sheet while it closes */}
      {panel === 'publish' && (
        <Suspense fallback={null}>
          <PublishSheet onClose={() => setPanel(null)} onOpened={(id) => setChangeRequest(id)} />
        </Suspense>
      )}
      {changeRequest && name && (
        <Suspense fallback={null}>
          <ChangeRequestDrawer id={changeRequest} workflow={name} onClose={() => setChangeRequest(null)} />
        </Suspense>
      )}
      {/* the Provider sheet, opened from the node creator, a step's parameters, the node detail view or Publish */}
      {providerSheet && (
        <Suspense fallback={null}>
          <ProviderSheet />
        </Suspense>
      )}
      {leaving && (
        <ConfirmDialog
          title="Leave without saving?"
          message="The changes since the last save are lost."
          confirmLabel="Discard changes"
          tone="warning"
          onConfirm={() => navigate('/workflows')}
          onClose={() => setLeaving(false)}
        />
      )}
    </div>
  )
}

export function Designer({ name, doc, detail, isNew, onCreated, job }: DesignerProps) {
  const [store] = useState(() => new DesignerStore({ name, doc, detail, isNew }))
  useEffect(() => () => store.dispose(), [store])
  useEffect(() => {
    if (detail && detail !== store.getState().detail) store.setDetail(detail)
  }, [detail, store])
  // the job page's "Open in designer": that run's outputs are what every node and detail view shows
  useEffect(() => {
    if (job && !isNew && store.getState().execution?.jobId !== job) store.openExecution(job)
  }, [job, isNew, store])
  return (
    <DesignerContext.Provider value={store}>
      <Shell onCreated={onCreated} />
    </DesignerContext.Provider>
  )
}

export default Designer
