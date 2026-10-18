/**
 * Links from the shell to the repository's documentation. The app does not serve the README or
 * docs/, so a link opens them where the repository lives: VITE_DOCS_URL when the build sets it
 * (a fork's own copy, say), the GitHub repository's main branch otherwise.
 */
export const DOCS_BASE = import.meta.env.VITE_DOCS_URL || 'https://github.com/gonzalomunoz1024/infrastructure-automation/blob/main'

/** README "Identity and OIDC": the dev header, the oidc profile and the auth proxy in front of both. */
export const IDENTITY_DOC = 'README.md#identity-and-oidc'

/** `path` (a file of the repository, with an optional #anchor) under `base`, one slash between. */
export function docsUrl(path: string, base: string = DOCS_BASE): string {
  return base.replace(/\/+$/, '') + '/' + path.replace(/^\/+/, '')
}
