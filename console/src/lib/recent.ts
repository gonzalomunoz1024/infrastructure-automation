import { createStore } from './store'

/*
 * Recently visited places, for the home page and the search: the last eight services, types
 * and resources opened, newest first, per browser.
 */

export interface RecentItem {
  kind: 'service' | 'type' | 'resource' | 'page'
  href: string
  label: string
  hint?: string
  at: number
}

export const RECENT_STORAGE_KEY = 'console-recent'
export const RECENT_MAX = 8

const store = createStore<RecentItem[]>(RECENT_STORAGE_KEY, [], (raw) =>
  Array.isArray(raw) ? raw.filter((r): r is RecentItem => !!r && typeof r === 'object' && typeof (r as RecentItem).href === 'string' && typeof (r as RecentItem).label === 'string') : null,
)

export function pushRecent(item: Omit<RecentItem, 'at'>) {
  const now = Date.now()
  const rest = store.get().filter((r) => r.href !== item.href)
  store.set([{ ...item, at: now }, ...rest].slice(0, RECENT_MAX))
}

export const useRecent = store.use

export function clearRecent() {
  store.set([])
}
