import { renderToString } from 'react-dom/server'
import { describe, expect, it } from 'vitest'
import { DOCS_BASE, IDENTITY_DOC, docsUrl } from '../lib/docs'
import { SignInRow } from '../pages/SettingsPage'

const text = (html: string) => html.replace(/<!--.*?-->/g, '')

describe('docsUrl', () => {
  it('joins the base and a repository path with one slash', () => {
    expect(docsUrl('README.md#identity-and-oidc', 'https://git.example.com/acme/iaf/blob/main/')).toBe('https://git.example.com/acme/iaf/blob/main/README.md#identity-and-oidc')
    expect(docsUrl('/docs/api.md', 'https://git.example.com/acme/iaf/blob/main')).toBe('https://git.example.com/acme/iaf/blob/main/docs/api.md')
  })

  it('points at the GitHub repository when the build names no other', () => {
    expect(DOCS_BASE).toBe('https://github.com/gonzalomunoz1024/infrastructure-automation/blob/main')
    expect(docsUrl(IDENTITY_DOC)).toBe('https://github.com/gonzalomunoz1024/infrastructure-automation/blob/main/README.md#identity-and-oidc')
  })
})

describe('the settings identity card', () => {
  it('says production signs in through an auth proxy whose token the API verifies, and links the README', () => {
    const html = text(renderToString(<SignInRow />))
    expect(html).toContain('In production')
    expect(html).toContain('An auth proxy in front of the app and the API signs you in and adds the bearer token the API verifies under the')
    expect(html).toContain('<code class="font-mono">oidc</code> profile. The API ignores X-User there')
    expect(html).toContain('href="https://github.com/gonzalomunoz1024/infrastructure-automation/blob/main/README.md#identity-and-oidc"')
    expect(html).toContain('target="_blank"')
    expect(html).toContain('README: Identity and OIDC')
    expect(html).not.toContain('SSO')
  })
})
