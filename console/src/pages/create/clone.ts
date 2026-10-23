import { idempotencyKey } from '../../lib/bff'
import type { CloneDraft, CloneNote } from '../../lib/tags'

/*
 * A clone's draft on its way to another account (WP 4.6): the wizard fetches the draft for the
 * target account in the current session, keeps it in sessionStorage under a one-time token, and
 * opens the target account's wizard with `?draft=<token>`, which reads it and drops it. A page
 * that mounts twice (the account switch, React's strict mode) reads it again from memory.
 */

const PREFIX = 'console-clone-draft:'
const taken = new Map<string, CloneDraft>()

/** Keeps a draft for the next page and answers its token. */
export function keepDraft(draft: CloneDraft): string {
  const token = idempotencyKey()
  try {
    sessionStorage.setItem(`${PREFIX}${token}`, JSON.stringify(draft))
  } catch {
    // no storage (a private window): the draft still crosses within this page
    taken.set(token, draft)
  }
  return token
}

/** The draft a token names, once: it leaves the storage as it is read. Null when it is gone. */
export function takeDraft(token: string): CloneDraft | null {
  const known = taken.get(token)
  if (known) return known
  try {
    const raw = sessionStorage.getItem(`${PREFIX}${token}`)
    if (!raw) return null
    sessionStorage.removeItem(`${PREFIX}${token}`)
    const draft = JSON.parse(raw) as CloneDraft
    taken.set(token, draft)
    return draft
  } catch {
    return null
  }
}

/** A note of the draft in words, with the field's title: "VM name: choose a name for the copy". */
export function noteWords(note: CloneNote, titleOf: (field: string) => string): string {
  return `${titleOf(note.field)}: ${note.message}`
}
