/// <reference types="vitest/config" />
import { readFileSync } from 'node:fs'
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

const pkg = JSON.parse(readFileSync(new URL('./package.json', import.meta.url), 'utf8')) as { version: string }
// where `npm run dev` sends /api, /ws and /actuator (IAF_BACKEND=http://localhost:18080 npm run dev)
const backend = process.env.IAF_BACKEND ?? 'http://localhost:8080'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react(), tailwindcss()],
  define: {
    __APP_VERSION__: JSON.stringify(pkg.version),
  },
  server: {
    port: 3000,
    proxy: {
      '/ws': {
        target: backend.replace(/^http/, 'ws'),
        ws: true,
      },
      '/api': {
        target: backend,
        changeOrigin: true,
      },
      '/actuator': {
        target: backend,
        changeOrigin: true,
      },
    },
  },
  test: {
    environment: 'node',
    include: ['src/**/*.test.{ts,tsx}'],
  },
})
