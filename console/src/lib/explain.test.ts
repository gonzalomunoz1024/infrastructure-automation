import { describe, expect, it } from 'vitest'
import { linkOf, retryBodyOf } from './explain'

describe('the retry a fix asks for', () => {
  it('sends the input merge and the step, and nothing when the fix asks only to run again', () => {
    expect(retryBodyOf({ input: { image: 'image-demo01' }, fromStep: 'createVm' })).toEqual({ input: { image: 'image-demo01' }, fromStep: 'createVm' })
    expect(retryBodyOf({ fromStep: 'apply' })).toEqual({ fromStep: 'apply' })
    expect(retryBodyOf({ input: {} })).toBeUndefined()
    expect(retryBodyOf(undefined)).toBeUndefined()
  })
})

describe('an explanation’s links', () => {
  it('follows the console’s own routes and https pages, and drops anything else', () => {
    expect(linkOf('#/console/platform/image/create')).toEqual({ href: '#/console/platform/image/create', external: false })
    expect(linkOf('https://runbooks.example/vsphere/not-found')).toEqual({ href: 'https://runbooks.example/vsphere/not-found', external: true })
    expect(linkOf('javascript:alert(1)')).toBeNull()
    expect(linkOf('http://plain.example')).toBeNull()
    expect(linkOf('data:text/html,hi')).toBeNull()
  })
})
