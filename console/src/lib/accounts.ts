import { createStore } from './store'

/*
 * Accounts (P-04): the 12-digit ids the console scopes everything to. The platform does not
 * serve them yet (phase 2), so phase 0 carries two demo accounts the platform-fake's resources
 * fall under. The chosen one is remembered per browser.
 */

export interface Account {
  id: string
  name: string
  team: string
}

export const DEMO_ACCOUNTS: Account[] = [
  { id: '123456789012', name: 'payments-dev', team: 'team-compute' },
  { id: '210987654321', name: 'data-prod', team: 'team-data' },
]

export const ACCOUNT_STORAGE_KEY = 'console-account'

const store = createStore<string>(ACCOUNT_STORAGE_KEY, DEMO_ACCOUNTS[0].id, (raw) => (typeof raw === 'string' && DEMO_ACCOUNTS.some((a) => a.id === raw) ? raw : null))

export function accountOf(id: string | null | undefined): Account {
  return DEMO_ACCOUNTS.find((a) => a.id === id) ?? DEMO_ACCOUNTS[0]
}

export function getAccount(): Account {
  return accountOf(store.get())
}

export function setAccount(id: string) {
  store.set(accountOf(id).id)
}

/** The chosen account, reactive. */
export function useAccount(): Account {
  return accountOf(store.use())
}

/** "1234-5678-9012": an account id as the header shows it. */
export function formatAccountId(id: string): string {
  return id.replace(/(\d{4})(?=\d)/g, '$1-')
}
