import { useEffect } from 'react'
import { Spinner, ToastProvider, useToast } from '@iaf/ui'
import { loadSession, switchAccount } from '../lib/bff'
import { isApiError } from '../lib/api'
import { secondsLeft, useSessionState, type Session } from '../lib/session'
import { ConsoleProvider } from './console'
import { AppShell } from './AppShell'
import { BrandMark } from './BrandMark'
import { navigate, pathFor, useRoute, type Route } from './router'
import SignInPage from '../pages/SignInPage'

const APP_TITLE = 'Console'

/** The root: the session read from the BFF, the sign-in gate, then the shell with everything it needs. */
export function App() {
  return (
    <ToastProvider>
      <Gate />
    </ToastProvider>
  )
}

function Gate() {
  const route = useRoute()
  const state = useSessionState()

  useEffect(() => {
    void loadSession()
  }, [])

  useEffect(() => {
    document.title = route.def && route.name !== 'home' ? `${route.def.title} · ${APP_TITLE}` : APP_TITLE
  }, [route.def, route.name])

  // nobody signed in: the sign-in page, remembering where they were going; signed in: never the door
  useEffect(() => {
    if (state.status === 'signed-out' && route.name !== 'signin') navigate(pathFor('signin', {}, { next: route.path === '/' ? undefined : route.path }), { replace: true })
    if (state.status === 'signed-in' && route.name === 'signin') navigate(route.query.next || '/', { replace: true })
  }, [state.status, route])

  // the session ends on its own: read it again just before, so the door shows instead of a failing page
  useEffect(() => {
    const left = secondsLeft(state.session)
    if (left === null) return
    const timer = setTimeout(() => void loadSession(), Math.max(1_000, (left - 30) * 1_000))
    return () => clearTimeout(timer)
  }, [state.session])

  if (state.status === 'loading') return <Splash />
  if (state.status !== 'signed-in' || !state.session || route.name === 'signin') return <SignInPage route={route} />
  return (
    <ConsoleProvider session={state.session}>
      <AccountFromPath route={route} session={state.session} />
      <AppShell route={route} />
    </ConsoleProvider>
  )
}

/** A link scoped to another account (`#/account/<id>/…`) switches the session to it once. */
function AccountFromPath({ route, session }: { route: Route; session: Session }) {
  const toast = useToast()
  const wanted = route.accountId
  const current = session.account.accountId
  useEffect(() => {
    if (!wanted || wanted === current) return
    switchAccount(wanted).catch((e: unknown) => {
      toast.error(`Could not switch to account ${wanted}`, isApiError(e) && e.status === 404 ? 'You are not a member of it; staying in the account you were in.' : isApiError(e) ? e.error : String(e))
      navigate(route.path, { replace: true })
    })
  }, [wanted, current, route.path, toast])
  return null
}

function Splash() {
  return (
    <div className="flex min-h-dvh flex-col items-center justify-center gap-3 bg-surface text-secondary" role="status" aria-live="polite">
      <BrandMark />
      <span className="flex items-center gap-2 text-sm">
        <Spinner size={14} /> Reading your session…
      </span>
    </div>
  )
}
