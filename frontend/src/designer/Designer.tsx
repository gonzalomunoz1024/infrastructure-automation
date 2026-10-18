/**
 * The workflow designer: one store per open workflow, the header, the Editor · Executions ·
 * Settings views, the side panels and sheets, and the keys that work everywhere (⌘S saves,
 * ⌘↵ tests). The page decides what to open; this module is the lazily loaded chunk.
 */
import { useCallback, useEffect, useState } from 'react'
import { ConfirmDialog, useHotkey, useToast } from '@iaf/ui'
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
import { ChangeRequestDrawer, PublishSheet } from './chrome/Publish'
import { AssistantPanel } from './chrome/AssistantPanel'
import { EditorView } from './views/EditorView'
import { ExecutionsView } from './views/ExecutionsView'
import { SettingsView } from './views/SettingsView'

export interface DesignerProps {
  /** null until a new workflow is first saved */
  name: string | null
  doc: WorkflowDocument
  detail: WorkflowDetail | null
  isNew: boolean
  /** a new workflow was created under this name */
  onCreated?: (name: string) => void
}

type Panel = 'issues' | 'yaml' | 'test' | 'publish' | null

function Shell({ onCreated }: { onCreated?: (name: string) => void }) {
  const store = useDesignerStore()
  const toast = useToast()
  const tab = useDesignerSelector((s) => s.tab)
  const name = useDesignerSelector((s) => s.name)
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
        assistantOpen={assistant}
      />
      <div className="relative flex min-h-0 flex-1">
        <div className="relative min-w-0 flex-1">
          {tab === 'editor' && <EditorView onTest={() => setPanel('test')} />}
          {tab === 'executions' && <ExecutionsView />}
          {tab === 'settings' && <SettingsView />}
        </div>
        {assistant && <AssistantPanel onClose={() => setAssistant(false)} />}
      </div>
      {panel === 'issues' && <IssuesDrawer onClose={() => setPanel(null)} />}
      {panel === 'yaml' && <YamlDrawer onClose={() => setPanel(null)} />}
      {panel === 'test' && <TestRunSheet onClose={() => setPanel(null)} />}
      {panel === 'publish' && <PublishSheet onClose={() => setPanel(null)} onOpened={(id) => setChangeRequest(id)} />}
      {changeRequest && name && <ChangeRequestDrawer id={changeRequest} workflow={name} onClose={() => setChangeRequest(null)} />}
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

export function Designer({ name, doc, detail, isNew, onCreated }: DesignerProps) {
  const [store] = useState(() => new DesignerStore({ name, doc, detail, isNew }))
  useEffect(() => () => store.dispose(), [store])
  useEffect(() => {
    if (detail && detail !== store.getState().detail) store.setDetail(detail)
  }, [detail, store])
  return (
    <DesignerContext.Provider value={store}>
      <Shell onCreated={onCreated} />
    </DesignerContext.Provider>
  )
}

export default Designer
