/** How the canvas fits a workflow into view. */
export const FIT = { padding: 0.25, maxZoom: 1, minZoom: 0.2 }
/** Below this zoom a fitted workflow is too small to read. */
export const READABLE_ZOOM = 0.3
/** The zoom a workflow too long to fit opens at. */
export const START_ZOOM = 0.8
const START_MARGIN = 64

export interface Rect {
  x: number
  y: number
  width: number
  height: number
}

/**
 * The first view of a workflow. null when the whole graph fits at a readable zoom (the canvas
 * then fits it, as n8n does); otherwise its start, the triggers and the first steps, at a
 * readable zoom, instead of the middle of a long chain shrunk to dots.
 */
export function openingViewport(bounds: Rect, pane: { width: number; height: number }): { x: number; y: number; zoom: number } | null {
  if (bounds.width <= 0 || bounds.height <= 0 || pane.width <= 0 || pane.height <= 0) return null
  const fitting = Math.min(pane.width / (bounds.width * (1 + FIT.padding)), pane.height / (bounds.height * (1 + FIT.padding)))
  if (fitting >= READABLE_ZOOM) return null
  const zoom = START_ZOOM
  const y = bounds.height * zoom <= pane.height - 2 * START_MARGIN ? pane.height / 2 - (bounds.y + bounds.height / 2) * zoom : START_MARGIN - bounds.y * zoom
  return { x: START_MARGIN - bounds.x * zoom, y, zoom }
}
