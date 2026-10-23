import { getSession } from './session'
import { createStore } from './store'

/*
 * Recently visited places, for the home page and the search: the last eight services, types
 * and resources opened, newest first. The list in this browser belongs to the person who made
 * it: it is kept under their user id, read only while they are signed in, replaced when someone
 * else opens a page, and cleared when they sign out, so the next person on the device never
 * sees it.
 */

export interface RecentItem {
  kind: 'service' | 'type' | 'resource' | 'page'
  href: string
  label: string
  hint?: string
  at: number
}

export interface StoredRecent {
  /** the user id of the person whose visits these are */
  owner: string
  items: RecentItem[]
}

export const RECENT_STORAGE_KEY = 'console-recent'
export const RECENT_MAX = 8

const NOBODY: StoredRecent = { owner: '', items: [] }
const NONE: RecentItem[] = []

function isItem(r: unknown): r is RecentItem {
  return !!r && typeof r === 'object' && typeof (r as RecentItem).href === 'string' && typeof (r as RecentItem).label === 'string'
}

/** The stored list, or null for anything else; an older copy (a bare list, kept for nobody in particular) reads as empty. */
export function parseRecent(raw: unknown): StoredRecent | null {
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return null
  const r = raw as StoredRecent
  return typeof r.owner === 'string' && Array.isArray(r.items) ? { owner: r.owner, items: r.items.filter(isItem) } : null
}

const store = createStore<StoredRecent>(RECENT_STORAGE_KEY, NOBODY, parseRecent)

function currentOwner(): string | null {
  return getSession()?.user.userId ?? null
}

/** The visits of `owner`; nothing when nobody is signed in, or when the list is someone else's. */
export function recentOf(stored: StoredRecent, owner: string | null): RecentItem[] {
  return owner && stored.owner === owner ? stored.items : NONE
}

export function pushRecent(item: Omit<RecentItem, 'at'>) {
  const owner = currentOwner()
  if (!owner) return
  const rest = recentOf(store.get(), owner).filter((r) => r.href !== item.href)
  store.set({ owner, items: [{ ...item, at: Date.now() }, ...rest].slice(0, RECENT_MAX) })
}

export function useRecent(): RecentItem[] {
  return recentOf(store.use(), currentOwner())
}

/** Forgets every visit kept in this browser (settings, sign-out). */
export function clearRecent() {
  store.set(NOBODY)
}
