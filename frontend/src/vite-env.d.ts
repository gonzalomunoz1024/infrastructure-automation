/// <reference types="vite/client" />

/** injected by vite.config.ts from package.json */
declare const __APP_VERSION__: string

interface ImportMetaEnv {
  /** where the shell's links to the README and docs/ point (lib/docs.ts); the GitHub repository when unset */
  readonly VITE_DOCS_URL?: string
}
