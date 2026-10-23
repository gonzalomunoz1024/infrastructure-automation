import { describe, expect, it } from 'vitest'
import { ApiError, stepUpReturn } from './api'

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

  it('reads the sign-in door from a step-up refusal', () => {
    const e = new ApiError(403, { error: 'step-up.required', detail: { stepUp: true, action: 'compute:delete', login: '/auth/login?stepUp=true' } })
    expect(e.stepUp).toBe('/auth/login?stepUp=true')
    expect(new ApiError(403, { error: 'policy.denied', detail: { login: '/auth/login' } }).stepUp).toBeNull()
    // the BFF's handler words its error as a sentence; detail.stepUp is what marks it
    expect(new ApiError(403, { error: 'step-up authentication required for compute:delete', detail: { stepUp: true, action: 'compute:delete', login: '/auth/login?stepUp=true' } }).stepUp).toBe('/auth/login?stepUp=true')
    expect(new ApiError(403, { error: 'x', detail: { stepUp: true, login: 'https://elsewhere.example/' } }).stepUp).toBeNull()
  })
})
