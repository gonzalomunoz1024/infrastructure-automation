/// <reference types="vite/client" />

/** console/package.json version, from vite.config.ts */
declare const __APP_VERSION__: string
/** the hub commit the contracts are pinned to, from contracts.lock */
declare const __CONTRACTS_COMMIT__: string

interface ImportMetaEnv {
  /** '1' serves the API from captured samples in the browser (npm run dev:mock) */
  readonly VITE_CONSOLE_MOCK?: string
}
