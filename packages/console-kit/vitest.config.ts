import { defineConfig } from 'vitest/config'

// server-rendered tests run in node, like the platform's; the few interaction tests ask for jsdom with a file pragma
export default defineConfig({
  test: {
    environment: 'node',
    include: ['src/**/*.test.{ts,tsx}'],
  },
})
