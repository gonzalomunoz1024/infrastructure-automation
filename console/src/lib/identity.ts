import { createStore } from './store'

/*
 * Who the console acts as. In phase 0 there is no OIDC yet (it arrives in phase 1): a dev
 * identity chosen on the sign-in page travels as the X-User header, as the platform's own
 * frontend does. The identities are plain names the platform-fake knows.
 */

export interface DevIdentity {
  id: string
  name: string
  email: string
  teams: string[]
  roles: string[]
  admin?: boolean
}

export const DEV_IDENTITIES: DevIdentity[] = [
  { id: 'gonzalo', name: 'Gonzalo Munoz', email: 'gonzalo@example.test', teams: ['team-compute'], roles: ['admin'], admin: true },
  { id: 'mateo', name: 'Mateo Rivas', email: 'mateo@example.test', teams: ['team-data'], roles: ['operator'] },
  { id: 'ana', name: 'Ana Costa', email: 'ana@example.test', teams: ['team-platform'], roles: ['viewer'] },
]

export const USER_STORAGE_KEY = 'console-user'

const store = createStore<string | null>(USER_STORAGE_KEY, null, (raw) => (typeof raw === 'string' && raw.trim() ? raw.trim() : null))

/** The dev user the client acts as; null means no header (signed out). */
export const getCurrentUser = store.get
export function setCurrentUser(id: string | null) {
  store.set(id && id.trim() ? id.trim() : null)
}
/** The dev user, reactive. */
export const useCurrentUser = store.use

export function identityOf(id: string | null | undefined): DevIdentity | null {
  return DEV_IDENTITIES.find((d) => d.id === id) ?? null
}
