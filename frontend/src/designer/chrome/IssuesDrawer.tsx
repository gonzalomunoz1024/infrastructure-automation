/**
 * Every live problem of the workflow, grouped by step, as @iaf/workflow-checks finds them while
 * you edit: open the step at the field, or apply the one-click fix the check offers.
 */
import { Button, cx, Drawer, EmptyState, Icon, OverlayBody, OverlayHeader, useOverlayClose, useToast } from '@iaf/ui'
import type { Problem } from '@iaf/workflow-checks'
import { API_TRIGGER_ID, findStepNode } from '../model/graph'
import { useDesigner, useDesignerStore } from '../model/store'
import { NodeGlyph, stepGlyph } from '../canvas/glyphs'

interface Group {
  key: string
  step: string | null
  title: string
  problems: Problem[]
}

function groupsOf(problems: readonly Problem[], nameOf: (step: string) => string): Group[] {
  const map = new Map<string, Group>()
  for (const p of problems) {
    const key = p.step ?? ''
    let g = map.get(key)
    if (!g) {
      g = { key, step: p.step ?? null, title: p.step ? nameOf(p.step) : 'Workflow', problems: [] }
      map.set(key, g)
    }
    g.problems.push(p)
  }
  return [...map.values()].sort((a, b) => (a.step === null ? -1 : b.step === null ? 1 : 0))
}

/** Where a document-level problem is edited: the Settings tab, or the trigger's detail view. */
function documentTarget(p: Problem): 'settings' | 'trigger' | null {
  const f = p.field ?? ''
  if (/^triggers\[/.test(f)) return 'trigger'
  if (f.startsWith('metadata') || f.startsWith('api') || f.startsWith('policy') || f.startsWith('mcp') || f.startsWith('rollout') || f.startsWith('outputs') || f.startsWith('workflow.outputs')) return 'settings'
  return null
}

function Body() {
  const store = useDesignerStore()
  const close = useOverlayClose()
  const toast = useToast()
  const s = useDesigner()
  const providers = s.registry?.providers ?? []
  const nameOf = (step: string) => findStepNode(s.graph, step)?.data.step.name ?? step
  const groups = groupsOf(s.problems, nameOf)
  const errors = s.problems.filter((p) => p.level === 'error').length

  const open = (p: Problem) => {
    if (p.step) {
      const node = store.nodeOf(p.step)
      if (!node) return
      close()
      store.setTab('editor')
      store.openNdv(node, p.field && /^(retry|timeout|assert|output|pauseOnFailure|notes|compensate)/.test(p.field) ? 'settings' : 'parameters')
      return
    }
    const target = documentTarget(p)
    close()
    if (target === 'trigger') {
      const m = /^triggers\[(\d+)\]/.exec(p.field ?? '')
      store.setTab('editor')
      store.openNdv(m ? `trigger:${m[1]}` : API_TRIGGER_ID)
    } else store.setTab('settings')
  }

  const fix = (p: Problem) => {
    if (store.applyFix(p)) {
      if (p.fix?.kind === 'connect') close()
      else toast.toast({ title: 'Fixed', message: p.fix?.label ?? p.message, tone: 'healthy', duration: 2500 })
    } else toast.error('Could not apply the fix', p.message)
  }

  return (
    <>
      <OverlayHeader
        title="Issues"
        subtitle={s.problems.length === 0 ? 'Checked as you edit' : `${errors} ${errors === 1 ? 'error' : 'errors'}, ${s.problems.length - errors} ${s.problems.length - errors === 1 ? 'warning' : 'warnings'}`}
        icon="alert-triangle"
      />
      <OverlayBody padded={false}>
        {groups.length === 0 ? (
          <EmptyState icon="check" iconClassName="text-healthy" title="No problems" message="The checks find nothing to fix. Publishing runs them again, with the backend's view of providers and credentials." />
        ) : (
          <div className="divide-y divide-stroke-light" data-testid="issues-list">
            {groups.map((g) => {
              const node = g.step ? findStepNode(s.graph, g.step) : undefined
              return (
                <section key={g.key} aria-label={g.title} className="px-5 py-4">
                  <header className="mb-2 flex items-center gap-2">
                    <span className="flex h-7 w-7 items-center justify-center rounded-sm border border-stroke-light bg-surface">
                      {node ? <NodeGlyph glyph={stepGlyph(node.data.step, providers)} size={16} /> : <Icon name="workflow" size={15} className="text-tertiary" />}
                    </span>
                    <h3 className="min-w-0 flex-1 truncate text-footnote font-semibold text-primary">{g.title}</h3>
                    {g.step && <span className="font-mono text-caption2 text-tertiary">{g.step}</span>}
                  </header>
                  <ul className="space-y-2">
                    {g.problems.map((p, i) => (
                      <li key={`${p.code}-${p.field ?? ''}-${i}`} className="rounded-md border border-stroke-light bg-surface-secondary/40 p-3">
                        <div className="flex items-start gap-2">
                          <Icon name={p.level === 'error' ? 'close-circle' : 'warning'} size={15} className={cx('mt-0.5 shrink-0', p.level === 'error' ? 'text-critical' : 'text-warning')} />
                          <div className="min-w-0 flex-1">
                            <p className="text-footnote text-primary">{p.message}</p>
                            <p className="mt-0.5 flex flex-wrap gap-x-2 font-mono text-caption2 text-tertiary">
                              <span>{p.code}</span>
                              {p.field && <span>{p.field}</span>}
                            </p>
                          </div>
                        </div>
                        <div className="mt-2 flex flex-wrap items-center justify-end gap-1.5">
                          {p.fix && (
                            <Button size="sm" variant="primary" icon="bolt" onClick={() => fix(p)}>
                              {p.fix.label}
                            </Button>
                          )}
                          {(p.step || documentTarget(p)) && (
                            <Button size="sm" variant="ghost" iconRight="arrow-right" onClick={() => open(p)}>
                              {p.step ? 'Open step' : documentTarget(p) === 'trigger' ? 'Open trigger' : 'Open settings'}
                            </Button>
                          )}
                        </div>
                      </li>
                    ))}
                  </ul>
                </section>
              )
            })}
          </div>
        )}
      </OverlayBody>
    </>
  )
}

export function IssuesDrawer({ onClose }: { onClose: () => void }) {
  return (
    <Drawer onClose={onClose} width="max-w-md" label="Issues">
      <Body />
    </Drawer>
  )
}
