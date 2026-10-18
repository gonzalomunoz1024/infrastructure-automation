/**
 * Sticky notes live in the browser only: the backend accepts no key for them in a workflow file
 * (WorkflowChecks.COMMON_KEYS), so they are kept in localStorage per workflow name.
 */
export interface Sticky {
  id: string
  x: number
  y: number
  width: number
  height: number
  /** 1..7, n8n's palette positions mapped onto Atlas tints */
  color: number
  text: string
}

export const STICKY_COLORS = 7

function key(workflow: string): string {
  return `iaf-designer-stickies:${workflow}`
}

export function loadStickies(workflow: string): Sticky[] {
  try {
    const raw = localStorage.getItem(key(workflow))
    if (!raw) return []
    const parsed = JSON.parse(raw) as unknown
    return Array.isArray(parsed) ? (parsed as Sticky[]).filter((s) => s && typeof s.id === 'string') : []
  } catch {
    return []
  }
}

export function saveStickies(workflow: string, stickies: Sticky[]): void {
  try {
    if (stickies.length === 0) localStorage.removeItem(key(workflow))
    else localStorage.setItem(key(workflow), JSON.stringify(stickies))
  } catch {
    /* storage unavailable: the notes live for this session */
  }
}

export function newSticky(x: number, y: number): Sticky {
  return { id: `sticky_${Date.now().toString(36)}_${Math.random().toString(36).slice(2, 6)}`, x, y, width: 240, height: 160, color: 1, text: "I'm a note\nDouble-click to edit me. [Guide](https://docs.n8n.io/workflows/sticky-notes/)" }
}
