/**
 * What every parameter of the open node needs: the expression context for live previews, the
 * problems of the node, and whether a field of the Input pane is being dragged (mappable
 * parameters light up, as in n8n).
 */
import { createContext, useContext } from 'react'
import { EMPTY_EXPRESSION_CONTEXT, type ExpressionContext, type Problem } from '@iaf/workflow-checks'

/** The drag payload type of a field dragged from the Input pane. */
export const MAPPING_MIME = 'application/x-iaf-expression'

export interface NdvContextValue {
  /** the context the node's expressions see */
  ctx: ExpressionContext
  problems: readonly Problem[]
  /** a field of the Input pane is being dragged */
  dragging: boolean
  setDragging: (dragging: boolean) => void
  /** fix a problem in one click */
  applyFix: (p: Problem) => void
}

export const NdvContext = createContext<NdvContextValue>({
  ctx: EMPTY_EXPRESSION_CONTEXT,
  problems: [],
  dragging: false,
  setDragging: () => {},
  applyFix: () => {},
})

export function useNdv(): NdvContextValue {
  return useContext(NdvContext)
}
