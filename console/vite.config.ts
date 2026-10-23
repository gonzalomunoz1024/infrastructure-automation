/// <reference types="vitest/config" />
import { readFileSync } from 'node:fs'
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

const pkg = JSON.parse(readFileSync(new URL('./package.json', import.meta.url), 'utf8')) as { version: string }
const lock = JSON.parse(readFileSync(new URL('../contracts.lock', import.meta.url), 'utf8')) as { commit: string }
// the BFF, the only backend the browser talks to: `npm run dev` sends it the console API, the
// sign-in, the demo identity provider and the health check (CONSOLE_BFF=http://localhost:18081).
// Nothing reaches the platform from the browser: the BFF calls it on the person's behalf.
const bff = process.env.CONSOLE_BFF ?? 'http://localhost:18081'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react(), tailwindcss()],
  define: {
    __APP_VERSION__: JSON.stringify(pkg.version),
    __CONTRACTS_COMMIT__: JSON.stringify(lock.commit.slice(0, 7)),
  },
  server: {
    port: 3000,
    proxy: {
      '/actuator/health': { target: bff, changeOrigin: true },
      '/auth': { target: bff, changeOrigin: true },
      '/console': { target: bff, changeOrigin: true },
      '/demo-idp': { target: bff, changeOrigin: true },
    },
  },
  preview: { port: 3000 },
  test: {
    environment: 'node',
    include: ['src/**/*.test.{ts,tsx}'],
  },
})
