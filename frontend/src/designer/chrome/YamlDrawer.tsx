/**
 * The workflow file. The backend renders the document as YAML and parses edits back (the
 * browser never reads or writes YAML itself); leaving the editor applies the edit to the
 * canvas as one undoable change.
 */
import { useEffect, useState } from 'react'
import { Button, CopyIconButton, cx, Drawer, Icon, OverlayBody, OverlayHeader, Skeleton, Textarea, useToast } from '@iaf/ui'
import type { Problem } from '@iaf/workflow-checks'
import { api, errorMessage } from '../../lib/api'
import { useDesignerSelector, useDesignerStore } from '../model/store'

function Body() {
  const store = useDesignerStore()
  const toast = useToast()
  const doc = useDesignerSelector((s) => s.doc)
  const name = useDesignerSelector((s) => s.name)
  const [rendered, setRendered] = useState<string | null>(null)
  const [text, setText] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [problems, setProblems] = useState<Problem[]>([])
  const [applying, setApplying] = useState(false)
  const [edited, setEdited] = useState(false)

  // the file follows the canvas until the user edits it; an edit that starts while a render is
  // in flight re-runs this and drops that answer
  useEffect(() => {
    if (edited) return
    let cancelled = false
    api
      .renderWorkflow({ definition: doc })
      .then((r) => {
        if (cancelled) return
        setRendered(r.yaml)
        setText(r.yaml)
        setError(null)
      })
      .catch((e: unknown) => {
        if (!cancelled) setError(errorMessage(e))
      })
    return () => {
      cancelled = true
    }
  }, [doc, edited])

  const apply = async () => {
    if (!edited || text === rendered) {
      setEdited(false)
      return
    }
    setApplying(true)
    try {
      const r = await api.parseWorkflow({ yaml: text })
      setProblems(r.problems)
      if (r.definition) {
        setEdited(false)
        store.setDocument(r.definition)
        toast.toast({ title: 'YAML applied', message: r.problems.length === 0 ? 'The canvas follows the file' : `${r.problems.length} ${r.problems.length === 1 ? 'problem' : 'problems'}, see Issues`, tone: r.problems.length === 0 ? 'healthy' : 'warning', duration: 2500 })
      }
    } catch (e) {
      setError(errorMessage(e))
    } finally {
      setApplying(false)
    }
  }

  const parseErrors = problems.filter((p) => p.code.startsWith('yaml') || p.level === 'error')

  return (
    <>
      <OverlayHeader
        title={name ? `${name}.yaml` : 'Workflow YAML'}
        subtitle="Rendered by the backend. Edit, then leave the editor to apply."
        icon="document"
        actions={rendered !== null ? <CopyIconButton text={text} label="Copy YAML" /> : undefined}
      />
      <OverlayBody className="flex flex-col gap-3">
        {rendered === null && !error ? (
          <div className="space-y-2" role="status" aria-label="Rendering YAML">
            {Array.from({ length: 14 }, (_, i) => (
              <Skeleton key={i} className={cx('h-3.5', i % 3 === 2 ? 'w-1/2' : i % 2 ? 'w-4/5' : 'w-2/3')} />
            ))}
          </div>
        ) : (
          <Textarea
            mono
            aria-label="Workflow YAML"
            data-testid="yaml-editor"
            spellCheck={false}
            value={text}
            onChange={(e) => {
              setEdited(true)
              setText(e.target.value)
            }}
            onBlur={() => void apply()}
            className="min-h-[60vh] flex-1 resize-none whitespace-pre leading-[18px]"
          />
        )}
        {error && (
          <p className="flex items-start gap-1.5 text-caption text-critical" role="alert">
            <Icon name="alert-triangle" size={13} className="mt-0.5 shrink-0" />
            {error}
          </p>
        )}
        {parseErrors.length > 0 && (
          <ul className="space-y-1 rounded-md border border-critical/30 bg-critical-tint p-3" role="alert">
            {parseErrors.map((p, i) => (
              <li key={i} className="text-caption text-critical">
                {p.field ? <span className="font-mono">{p.field}: </span> : null}
                {p.message}
              </li>
            ))}
          </ul>
        )}
        {edited && (
          <div className="flex justify-end">
            <Button size="sm" variant="primary" loading={applying} onClick={() => void apply()}>
              Apply YAML
            </Button>
          </div>
        )}
      </OverlayBody>
    </>
  )
}

export function YamlDrawer({ onClose }: { onClose: () => void }) {
  return (
    <Drawer onClose={onClose} width="max-w-2xl" label="Workflow YAML">
      <Body />
    </Drawer>
  )
}
