/**
 * Monaco from the bundle, never from a CDN: the editor's core with the contributions the YAML
 * drawer and the expression editor use, YAML highlighting (Monaco's own tokenizer: it ships no
 * YAML language service or worker), JSON's language service, and the platform's `{{ … }}`
 * expressions. The workers come through Vite's `?worker` imports, and @monaco-editor/react is
 * pointed at this instance with `loader.config({ monaco })`. Only MonacoEditor imports this
 * module, and the shell loads that lazily, so the first paint never carries Monaco.
 */
import { atlasColors } from '@iaf/ui'
import { loader } from '@monaco-editor/react'
import * as monaco from 'monaco-editor/editor/editor.api'
import 'monaco-editor/editor/browser/coreCommands'
import 'monaco-editor/editor/common/standaloneStrings'
// the editor features, each through its own register module (monaco-editor/features/*)
import 'monaco-editor/features/codicon/register'
import 'monaco-editor/features/codeEditor/register'
import 'monaco-editor/features/bracketMatching/register'
import 'monaco-editor/features/clipboard/register'
import 'monaco-editor/features/comment/register'
import 'monaco-editor/features/contextmenu/register'
import 'monaco-editor/features/cursorUndo/register'
import 'monaco-editor/features/find/register'
import 'monaco-editor/features/folding/register'
import 'monaco-editor/features/gotoError/register'
import 'monaco-editor/features/hover/register'
import 'monaco-editor/features/indentation/register'
import 'monaco-editor/features/linesOperations/register'
import 'monaco-editor/features/multicursor/register'
import 'monaco-editor/features/wordHighlighter/register'
import 'monaco-editor/features/wordOperations/register'
import 'monaco-editor/languages/definitions/yaml/register'
import 'monaco-editor/languages/features/json/register'
import EditorWorker from 'monaco-editor/editor/editor.worker?worker'
import JsonWorker from 'monaco-editor/languages/features/json/json.worker?worker'
import { EXPRESSION_LANGUAGE } from './CodeEditor'

globalThis.MonacoEnvironment = {
  getWorker: (_id: string, label: string) => (label === 'json' ? new JsonWorker() : new EditorWorker()),
}

// `{{ $('lookup').json.id }}` inside plain text, as the inline editor highlights it
monaco.languages.register({ id: EXPRESSION_LANGUAGE })
monaco.languages.setMonarchTokensProvider(EXPRESSION_LANGUAGE, {
  tokenizer: {
    root: [
      [/\{\{/, { token: 'delimiter.expression', next: '@expression' }],
      [/[^{]+/, ''],
      [/\{/, ''],
    ],
    expression: [
      [/\}\}/, { token: 'delimiter.expression', next: '@pop' }],
      [/\$[A-Za-z_]\w*/, 'variable'],
      [/'[^']*'|"[^"]*"/, 'string'],
      [/\d+(?:\.\d+)?/, 'number'],
      [/[A-Za-z_]\w*/, 'identifier'],
      [/[^}'"$\w]+|\}/, 'operator'],
    ],
  },
})

// the Atlas surfaces: an input's grey in light, its dark grey in dark; expressions in the one accent
for (const [name, base, c] of [
  ['iaf-light', 'vs', atlasColors.light],
  ['iaf-dark', 'vs-dark', atlasColors.dark],
] as const) {
  monaco.editor.defineTheme(name, {
    base,
    inherit: true,
    rules: [
      { token: 'delimiter.expression', foreground: c.accent.slice(1), fontStyle: 'bold' },
      { token: 'variable', foreground: c.accent.slice(1) },
    ],
    colors: { 'editor.background': c.surfaceSecondary, 'editorGutter.background': c.surfaceSecondary, 'editor.lineHighlightBackground': c.surfaceTertiary },
  })
}

loader.config({ monaco })

export { monaco }
