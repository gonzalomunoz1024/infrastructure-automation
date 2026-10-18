/**
 * A workflow document on the read-only canvas, outside the designer: the change request page
 * shows the candidate version the way its author designed it, laid out as the designer lays it
 * out. The canvas reads the designer's store from context, so this gives it one that holds the
 * document; nothing edits it, and the designer's registry is not loaded (the canvas needs only
 * the providers, for the node glyphs). Double-clicking a step shows the step as it is written.
 */
import { useCallback, useEffect, useMemo, useState } from 'react'
import { Drawer, JsonView, OverlayBody, OverlayHeader } from '@iaf/ui'
import type { JsonValue, WorkflowDocument } from '@iaf/workflow-checks'
import { api } from '../../lib/api'
import { useQuery } from '../../lib/queries'
import type { RegistryProvider } from '../../lib/types'
import { Canvas } from '../canvas/Canvas'
import type { RunInfo } from '../canvas/flow'
import { isStepNode, type StepNodeData } from '../model/graph'
import { DesignerContext, DesignerStore, placeGraph } from '../model/store'

const NO_RUNS: ReadonlyMap<string, RunInfo> = new Map()
const NO_PINS: Readonly<Record<string, JsonValue>> = {}
const NO_PROVIDERS: RegistryProvider[] = []

export interface DocumentCanvasProps {
  doc: WorkflowDocument
  /** the canvas's accessible name */
  label: string
}

export default function DocumentCanvas({ doc, label }: DocumentCanvasProps) {
  const [store] = useState(() => new DesignerStore({ name: null, doc, registry: null }))
  useEffect(() => () => store.dispose(), [store])
  const providers = useQuery('registry-providers', () => api.registryProviders())
  const graph = useMemo(() => placeGraph(doc, new Map()), [doc])
  const [open, setOpen] = useState<StepNodeData | null>(null)
  const onOpen = useCallback(
    (nodeId: string) => {
      const node = graph.nodes.find((n) => n.id === nodeId)
      if (isStepNode(node)) setOpen(node.data)
    },
    [graph],
  )

  return (
    <DesignerContext.Provider value={store}>
      <Canvas graph={graph} problems={[]} runs={NO_RUNS} pins={NO_PINS} stickies={[]} providers={providers.data ?? NO_PROVIDERS} readOnly onOpen={onOpen} label={label} />
      {open && (
        <Drawer onClose={() => setOpen(null)} width="max-w-lg" label={`Step ${open.stepId}`}>
          <OverlayHeader title={open.step.name || open.stepId} subtitle={[open.stepId, open.step.type, open.subtitle].filter(Boolean).join(' · ')} />
          <OverlayBody>
            <JsonView value={open.body} name={open.stepId} defaultExpanded={3} />
          </OverlayBody>
        </Drawer>
      )}
    </DesignerContext.Provider>
  )
}
