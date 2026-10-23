import { describe, expect, it } from 'vitest'
import { ApiError, safeStepUpPath, stepUpReturn } from './api'

/* A destructive verb wants a fresh sign-in (the BFF's 403 with detail.stepUp); the way back keeps the page. */

describe('the way back from a step-up', () => {
  it('names the hash route the person is on, keeping the step-up flag', () => {
    const login = '/auth/login?stepUp=true&returnTo=https%3A%2F%2Fconsole.example.test%2F'
    const back = new URL(stepUpReturn(login, '#/console/compute/vm/wfrn%3Awf%3Acompute%3Aglobal%3A123456789012%3Avm%2Fvm-gen-001?action=delete'), 'https://console.example.test')
    expect(back.pathname).toBe('/auth/login')
    expect(back.searchParams.get('stepUp')).toBe('true')
    expect(back.searchParams.get('returnTo')).toBe('/#/console/compute/vm/wfrn%3Awf%3Acompute%3Aglobal%3A123456789012%3Avm%2Fvm-gen-001?action=delete')
  })

  it('leaves the BFF’s choice alone on the home page, and adds a query when there was none', () => {
    expect(stepUpReturn('/auth/login?stepUp=true&returnTo=%2F', '')).toBe('/auth/login?stepUp=true&returnTo=%2F')
    expect(stepUpReturn('/auth/login?stepUp=true', '#/')).toBe('/auth/login?stepUp=true')
    expect(stepUpReturn('/auth/login', '#/requests')).toBe('/auth/login?returnTo=%2F%23%2Frequests')
  })

  // a refusal the BFF answered (the API client marks every answer from a BFF path so)
  const fromBff = (body: ConstructorParameters<typeof ApiError>[1]) => new ApiError(403, body, undefined, undefined, undefined, true)

  it('reads the sign-in door from a step-up refusal', () => {
    const e = fromBff({ error: 'step-up.required', detail: { stepUp: true, action: 'compute:delete', login: '/auth/login?stepUp=true' } })
    expect(e.stepUp).toBe('/auth/login?stepUp=true')
    expect(fromBff({ error: 'policy.denied', detail: { login: '/auth/login' } }).stepUp).toBeNull()
    // the BFF's handler words its error as a sentence; detail.stepUp is what marks it
    expect(fromBff({ error: 'step-up authentication required for compute:delete', detail: { stepUp: true, action: 'compute:delete', login: '/auth/login?stepUp=true' } }).stepUp).toBe('/auth/login?stepUp=true')
    expect(fromBff({ error: 'x', detail: { stepUp: true, login: 'https://elsewhere.example/' } }).stepUp).toBeNull()
  })

  it('honours a step-up only from a BFF answer', () => {
    const body = { error: 'step-up.required', detail: { stepUp: true, login: '/auth/login?stepUp=true' } }
    expect(new ApiError(403, body).stepUp).toBeNull()
    expect(new ApiError(403, body, undefined, undefined, undefined, false).stepUp).toBeNull()
    expect(fromBff(body).stepUp).toBe('/auth/login?stepUp=true')
  })
})

describe('the step-up door', () => {
  it('is a path of this console under /auth/', () => {
    expect(safeStepUpPath('/auth/login')).toBe('/auth/login')
    expect(safeStepUpPath('/auth/login?stepUp=true&returnTo=%2F%23%2Frequests')).toBe('/auth/login?stepUp=true&returnTo=%2F%23%2Frequests')
  })

  it('refuses another host, written protocol-relative', () => {
    expect(safeStepUpPath('//evil.example/auth/login')).toBeNull()
    expect(safeStepUpPath('//evil.example')).toBeNull()
  })

  it('refuses a backslash anywhere, which a browser reads as a slash', () => {
    expect(safeStepUpPath('/\\evil.example')).toBeNull()
    expect(safeStepUpPath('/\\evil.example/auth/login')).toBeNull()
    expect(safeStepUpPath('/auth/\\evil.example')).toBeNull()
    expect(safeStepUpPath('/auth\\login')).toBeNull()
  })

  it('refuses a scheme of any kind', () => {
    expect(safeStepUpPath('https://evil.example/auth/login')).toBeNull()
    expect(safeStepUpPath('http://console.example.test/auth/login')).toBeNull()
    expect(safeStepUpPath('javascript:alert(1)')).toBeNull()
    expect(safeStepUpPath('data:text/html,hi')).toBeNull()
  })

  it('refuses any path outside /auth/', () => {
    expect(safeStepUpPath('/')).toBeNull()
    expect(safeStepUpPath('/console/v1/session')).toBeNull()
    expect(safeStepUpPath('/authx/login')).toBeNull()
    expect(safeStepUpPath('/auth')).toBeNull()
    expect(safeStepUpPath('auth/login')).toBeNull()
    expect(safeStepUpPath('/auth/../console/')).toBeNull()
  })

  it('refuses spaces and control characters a browser would strip', () => {
    expect(safeStepUpPath('/auth/\t/evil.example')).toBeNull()
    expect(safeStepUpPath('/auth/login\n')).toBeNull()
    expect(safeStepUpPath(' /auth/login')).toBeNull()
  })

  it('refuses anything but a string', () => {
    expect(safeStepUpPath(undefined)).toBeNull()
    expect(safeStepUpPath(null)).toBeNull()
    expect(safeStepUpPath({ toString: () => '/auth/login' })).toBeNull()
  })
})
