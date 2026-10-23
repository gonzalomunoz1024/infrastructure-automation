/// <reference types="vitest/config" />
import { readFileSync } from 'node:fs'
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

const pkg = JSON.parse(readFileSync(new URL('./package.json', import.meta.url), 'utf8')) as { version: string }
const lock = JSON.parse(readFileSync(new URL('../contracts.lock', import.meta.url), 'utf8')) as { commit: string }
// where `npm run dev` sends /api and /actuator (CONSOLE_PLATFORM=http://localhost:18090 npm run dev)
const platform = process.env.CONSOLE_PLATFORM ?? 'http://localhost:18090'

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
      '/api': { target: platform, changeOrigin: true },
      '/actuator': { target: platform, changeOrigin: true },
    },
  },
  preview: { port: 3000 },
  test: {
    environment: 'node',
    include: ['src/**/*.test.{ts,tsx}'],
  },
})
