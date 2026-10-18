import { renderToString } from 'react-dom/server'
import { describe, expect, it } from 'vitest'
import { ApiLinks } from '../components/ApiLinks'
import { OPENAPI_URL, SWAGGER_UI_URL } from '../lib/api'

/* The two places the API documents itself, linked the same way from the catalog, a run page and the settings. */

describe('ApiLinks', () => {
  it('opens Swagger UI and the OpenAPI document in new tabs', () => {
    const html = renderToString(<ApiLinks lead="The same operations:" />)
    expect(SWAGGER_UI_URL).toBe('/swagger-ui')
    expect(OPENAPI_URL).toBe('/api/v1/openapi.json')
    expect(html).toContain(`href="${SWAGGER_UI_URL}"`)
    expect(html).toContain(`href="${OPENAPI_URL}"`)
    expect(html).toContain('Swagger UI')
    expect(html).toContain('openapi.json')
    expect(html).toContain('The same operations:')
    expect(html.match(/target="_blank"/g)).toHaveLength(2)
    expect(html.match(/rel="noreferrer"/g)).toHaveLength(2)
  })
})
