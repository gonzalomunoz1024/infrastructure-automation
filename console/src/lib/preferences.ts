import { useCallback } from 'react'
import { bff } from './bff'
import { isApiError } from './api'
import { createStore } from './store'
import type { Preferences, SavedView } from './types'

/*
 * The person's preferences, from the BFF (`/console/v1/preferences`): the list columns they chose
 * per type and the views they saved. A copy lives in localStorage so a reload draws the list the
 * way they left it before the BFF answers; every change is applied at once and sent whole, as
 * the contract replaces the object. When the BFF has moved on (409), its copy wins and the change
 * is applied again on top of it.
 */

export const PREFERENCES_STORAGE_KEY = 'console-preferences'

/** The local copy names whose it is, so a shared browser never shows one person's views to another. */
interface Stored {
  owner: string | null
  prefs: Preferences
}

const NOBODY: Stored = { owner: null, prefs: {} }
const NONE: Preferences = {}

const store = createStore<Stored>(PREFERENCES_STORAGE_KEY, NOBODY, (raw) => (raw && typeof raw === 'object' && 'prefs' in raw && typeof (raw as Stored).prefs === 'object' ? (raw as Stored) : null))

let loaded: { owner: string; done: Promise<void> } | null = null
let saving: Promise<void> = Promise.resolve()

/**
 * Reads the person's copy from the BFF once per sign-in; a failure keeps the local copy. A local
 * copy someone else left is dropped first.
 */
export function loadPreferences(owner: string, force = false): Promise<void> {
  if (store.get().owner !== owner) store.set({ owner, prefs: {} })
  if (loaded && loaded.owner === owner && !force) return loaded.done
  const done = bff.preferences
    .get()
    .then((p) => {
      if (store.get().owner === owner) store.set({ owner, prefs: p && typeof p === 'object' ? p : {} })
    })
    .catch(() => undefined)
  loaded = { owner, done }
  return done
}

/** For tests: forget what was loaded and the local copy. */
export function resetPreferences() {
  loaded = null
  saving = Promise.resolve()
  store.set(NOBODY)
}

/** The local copy, outside React. */
export function currentPreferences(): Preferences {
  return store.get().prefs
}

export type PreferencesChange = (current: Preferences) => Preferences

function keepVersion(owner: string | null, version: number | undefined) {
  if (store.get().owner === owner && version !== undefined) store.set({ owner, prefs: { ...store.get().prefs, version } })
}

/**
 * Applies a change locally at once, then sends the whole object as it stands when its turn comes;
 * changes queue, so two quick edits never race. A 409 means another tab moved first: the BFF's
 * copy is taken and the change applied again on top of it.
 */
export function updatePreferences(change: PreferencesChange): Promise<void> {
  const { owner } = store.get()
  store.set({ owner, prefs: change(store.get().prefs) })
  saving = saving.then(async () => {
    if (store.get().owner !== owner) return
    const body = store.get().prefs
    try {
      const saved = await bff.preferences.put(body)
      keepVersion(owner, saved?.version ?? body.version)
    } catch (e) {
      if (!isApiError(e) || e.status !== 409) return // any other failure keeps the local copy; the next change sends it again
      const theirs = await bff.preferences.get().catch(() => null)
      if (!theirs || store.get().owner !== owner) return
      const merged = change(theirs)
      store.set({ owner, prefs: merged })
      await bff.preferences
        .put(merged)
        .then((saved) => keepVersion(owner, saved?.version ?? merged.version))
        .catch(() => undefined)
    }
  })
  return saving
}

/** The signed-in person's preferences; nothing while the local copy is someone else's. */
export function usePreferences(owner: string): Preferences {
  const stored = store.use()
  return stored.owner === owner ? stored.prefs : NONE
}

/** The columns the person chose for a type, or null for the type's default. */
export function columnsOf(prefs: Preferences, typeKey: string): string[] | null {
  const chosen = prefs.columns?.[typeKey]
  return Array.isArray(chosen) && chosen.length ? chosen : null
}

export function withColumns(typeKey: string, columns: string[] | null): PreferencesChange {
  return (p) => {
    const next = { ...(p.columns ?? {}) }
    if (columns && columns.length) next[typeKey] = columns
    else delete next[typeKey]
    return { ...p, columns: next }
  }
}

export function viewsOf(prefs: Preferences, typeKey: string): SavedView[] {
  const views = prefs.savedViews?.[typeKey]
  return Array.isArray(views) ? views : []
}

export function withView(typeKey: string, view: SavedView): PreferencesChange {
  return (p) => {
    const current = viewsOf(p, typeKey).filter((v) => v.id !== view.id)
    return { ...p, savedViews: { ...(p.savedViews ?? {}), [typeKey]: [...current, view].sort((a, b) => a.name.localeCompare(b.name)) } }
  }
}

export function withoutView(typeKey: string, viewId: string): PreferencesChange {
  return (p) => {
    const rest = viewsOf(p, typeKey).filter((v) => v.id !== viewId)
    const all = { ...(p.savedViews ?? {}) }
    if (rest.length) all[typeKey] = rest
    else delete all[typeKey]
    return { ...p, savedViews: all }
  }
}

/** A new view id: time-ordered, unique enough for one person's views. */
export function newViewId(now = Date.now()): string {
  return `v_${now.toString(36)}${Math.random().toString(36).slice(2, 6)}`
}

export function usePreferenceWriter() {
  return useCallback((change: PreferencesChange) => void updatePreferences(change), [])
}
