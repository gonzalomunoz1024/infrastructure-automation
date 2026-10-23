import js from '@eslint/js'
import { defineConfig, globalIgnores } from 'eslint/config'
import reactHooks from 'eslint-plugin-react-hooks'
import globals from 'globals'
import tseslint from 'typescript-eslint'

// the platform's eslint setup (eslint.config.mjs at c9529d5), with this workspace's folders
export default defineConfig([
  globalIgnores(['**/dist', '**/node_modules', '.claude', 'console/playwright-report', 'console/test-results']),
  {
    files: ['**/*.{ts,tsx}'],
    extends: [js.configs.recommended, tseslint.configs.recommended],
    languageOptions: { ecmaVersion: 2023, globals: { ...globals.browser, ...globals.node } },
    rules: {
      '@typescript-eslint/no-unused-vars': ['error', { argsIgnorePattern: '^_', varsIgnorePattern: '^_', caughtErrors: 'none' }],
    },
  },
  {
    files: ['console/src/**/*.{ts,tsx}', 'packages/ui/src/**/*.{ts,tsx}', 'packages/console-kit/src/**/*.{ts,tsx}'],
    extends: [reactHooks.configs.flat['recommended-latest']],
    rules: {
      // reports where the React Compiler would skip a component; the app does not use the compiler
      'react-hooks/preserve-manual-memoization': 'off',
    },
  },
  {
    files: ['**/*.{js,mjs}'],
    extends: [js.configs.recommended],
    languageOptions: { ecmaVersion: 2023, globals: globals.node },
  },
])
