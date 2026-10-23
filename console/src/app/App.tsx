import { useEffect } from 'react'
import { ToastProvider } from '@iaf/ui'
import { useCurrentUser } from '../lib/identity'
import { ConsoleProvider } from './console'
import { AppShell } from './AppShell'
import { navigate, pathFor, useRoute } from './router'
import SignInPage from '../pages/SignInPage'

const APP_TITLE = 'Console'

/** The root: the sign-in gate, then the shell with everything it needs. */
export function App() {
  return (
    <ToastProvider>
      <Gate />
    </ToastProvider>
  )
}

function Gate() {
  const route = useRoute()
  const user = useCurrentUser()

  useEffect(() => {
    document.title = route.def && route.name !== 'home' ? `${route.def.title} · ${APP_TITLE}` : APP_TITLE
  }, [route.def, route.name])

  // nobody signed in: the sign-in page, remembering where they were going
  useEffect(() => {
    if (!user && route.name !== 'signin') navigate(pathFor('signin', {}, { next: route.path === '/' ? undefined : route.path }), { replace: true })
    if (user && route.name === 'signin' && !route.query.switch) navigate(route.query.next || '/', { replace: true })
  }, [user, route])

  if (!user || route.name === 'signin') return <SignInPage route={route} />
  return (
    <ConsoleProvider>
      <AppShell route={route} />
    </ConsoleProvider>
  )
}
