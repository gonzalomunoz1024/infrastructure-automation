import { describe, expect, it } from 'vitest'
import { linkOf, retryBodyOf } from './explain'

describe('the retry a fix asks for', () => {
  it('sends the input merge and the step, and nothing when the fix asks only to run again', () => {
    expect(retryBodyOf({ input: { image: 'image-demo01' }, fromStep: 'createVm' })).toEqual({ input: { image: 'image-demo01' }, fromStep: 'createVm' })
    expect(retryBodyOf({ fromStep: 'apply' })).toEqual({ fromStep: 'apply' })
    expect(retryBodyOf({ input: {} })).toBeUndefined()
    expect(retryBodyOf(undefined)).toBeUndefined()
  })

  it('takes an input change from the rules only, and never where the request runs', () => {
    expect(retryBodyOf({ input: { image: 'image-demo01' }, fromStep: 'apply' }, 'assistant')).toEqual({ fromStep: 'apply' })
    expect(retryBodyOf({ input: { image: 'evil' } }, 'assistant')).toBeUndefined()
    expect(retryBodyOf({ input: { accountId: '999999999999', wfrn: 'wfrn:x', region: 'eu', image: 'image-demo01' } })).toEqual({ input: { image: 'image-demo01' } })
  })
})

describe('an explanation’s links', () => {
  it('follows the console’s own routes and https pages on a docs host, and drops anything else', () => {
    const docs = ['runbooks.example']
    expect(linkOf('#/console/platform/image/create', docs)).toEqual({ href: '#/console/platform/image/create', external: false })
    expect(linkOf('https://runbooks.example/vsphere/not-found', docs)).toEqual({ href: 'https://runbooks.example/vsphere/not-found', external: true })
    expect(linkOf('https://runbooks.example.evil.example/login', docs)).toBeNull()
    expect(linkOf('https://evil.example/runbooks.example', docs)).toBeNull()
    expect(linkOf('https://runbooks.example/x')).toBeNull()
    expect(linkOf('javascript:alert(1)')).toBeNull()
    expect(linkOf('http://plain.example')).toBeNull()
    expect(linkOf('data:text/html,hi')).toBeNull()
  })
})
