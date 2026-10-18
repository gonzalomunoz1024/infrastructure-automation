/**
 * The document of a workflow just created, kept for the page that opens it next: `POST
 * /workflows` answers 202, so a GET right after may not find the draft yet, and the designer
 * should not wait for it (nor lose what is on the canvas).
 */
import type { WorkflowDocument } from '@iaf/workflow-checks'

const TTL_MS = 120_000
const docs = new Map<string, { doc: WorkflowDocument; at: number }>()

export function rememberDocument(name: string, doc: WorkflowDocument): void {
  docs.set(name, { doc, at: Date.now() })
}

/** The remembered document, while it is fresh. */
export function peekDocument(name: string): WorkflowDocument | null {
  const h = docs.get(name)
  if (!h) return null
  if (Date.now() - h.at > TTL_MS) {
    docs.delete(name)
    return null
  }
  return h.doc
}

export function forgetDocument(name: string): void {
  docs.delete(name)
}
