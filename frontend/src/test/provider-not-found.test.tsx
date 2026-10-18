import { renderToString } from 'react-dom/server'
import { describe, expect, it } from 'vitest'
import { ProviderNotFound } from '../pages/ProviderPage'

/*
 * docs/api.md "Providers": GET /providers/{name} answers 404 for a provider that does not exist
 * and for one the caller may not see (never a 403: the name is not revealed), so the page says
 * both at once instead of an error.
 */

const text = (html: string) => html.replace(/<!--.*?-->/g, '').replace(/<[^>]+>/g, ' ').replace(/\s+/g, ' ').trim()

describe('a provider the caller cannot see', () => {
  it('reads as missing, without saying whether it exists, and leads back to the list', () => {
    const page = text(renderToString(<ProviderNotFound name="dns-records" />))
    expect(page).toContain('No provider dns-records you can see')
    expect(page).toContain('There is none of that name, or a team you are not in owns it.')
    expect(page).toContain('All providers')
    expect(page).not.toMatch(/error|forbidden|403/i)
  })
})
