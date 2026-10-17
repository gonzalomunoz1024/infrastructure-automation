/** The Editor tab: the canvas with its run controls, the node creator and the node detail view. */
import { useCallback, useMemo } from 'react'
import type { RegistryProvider } from '../../lib/types'
import { stepNodeId } from '../model/graph'
import { useDesignerSelector, useDesignerStore } from '../model/store'
import { Canvas } from '../canvas/Canvas'
import { testRunStates, type RunInfo } from '../canvas/flow'
import { RunBar } from '../chrome/TestRun'

const NO_PROVIDERS: RegistryProvider[] = []
const NO_RUNS: ReadonlyMap<string, RunInfo> = new Map()

export function EditorView({ onTest }: { onTest: () => void }) {
  const store = useDesignerStore()
  const graph = useDesignerSelector((s) => s.graph)
  const problems = useDesignerSelector((s) => s.problems)
  const testRun = useDesignerSelector((s) => s.testRun)
  const pins = useDesignerSelector((s) => s.pins)
  const stickies = useDesignerSelector((s) => s.stickies)
  const providers = useDesignerSelector((s) => s.registry?.providers ?? NO_PROVIDERS)

  const runs = useMemo(
    () => (testRun?.result ? testRunStates(testRun.result.path, testRun.result.steps, testRun.revealed, testRun.status === 'running') : NO_RUNS),
    [testRun],
  )

  const open = useCallback(
    (nodeId: string) => {
      const n = store.getState().graph.nodes.find((x) => x.id === nodeId)
      if (n?.data.kind === 'undo') store.openNdv(stepNodeId(n.data.stepId), 'settings')
      else store.openNdv(nodeId)
    },
    [store],
  )

  return (
    <div className="relative h-full w-full">
      <Canvas graph={graph} problems={problems} runs={runs} pins={pins} stickies={stickies} providers={providers} onOpen={open}>
        <RunBar onTest={onTest} />
      </Canvas>
    </div>
  )
}
