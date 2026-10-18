/**
 * Monaco itself, loaded lazily by CodeEditor: the value in and out, a blur when focus leaves the
 * editor, the markers it is given (with their message on hover and F8 between them), and the
 * shell's light or dark theme.
 */
import Editor, { type OnMount } from '@monaco-editor/react'
import { useEffect, useRef, useState, type ReactNode } from 'react'
import { useTheme } from '@iaf/ui'
import { EXPRESSION_LANGUAGE, type CodeEditorProps } from './CodeEditor'
import { monaco } from './monaco'

const SEVERITY = { error: monaco.MarkerSeverity.Error, warning: monaco.MarkerSeverity.Warning, info: monaco.MarkerSeverity.Info } as const
const OWNER = 'iaf-checks'

export default function MonacoEditor({ value, onChange, onBlur, language, markers, ariaLabel, readOnly, autoFocus, loading }: CodeEditorProps & { loading: ReactNode }) {
  const { resolved } = useTheme()
  const [editor, setEditor] = useState<monaco.editor.IStandaloneCodeEditor | null>(null)
  const onBlurRef = useRef(onBlur)
  useEffect(() => {
    onBlurRef.current = onBlur
  })

  useEffect(() => {
    const model = editor?.getModel()
    if (!model) return
    monaco.editor.setModelMarkers(
      model,
      OWNER,
      (markers ?? []).map(({ severity, code, ...m }) => ({ ...m, code, severity: SEVERITY[severity], source: 'checks' })),
    )
  }, [editor, markers])

  const onMount: OnMount = (e) => {
    setEditor(e)
    e.onDidBlurEditorWidget(() => onBlurRef.current?.())
    if (autoFocus) {
      e.focus()
      const model = e.getModel()
      if (model) e.setPosition(model.getPositionAt(model.getValueLength()))
    }
  }

  const expression = language === EXPRESSION_LANGUAGE
  return (
    <Editor
      value={value}
      language={language}
      theme={resolved === 'dark' ? 'iaf-dark' : 'iaf-light'}
      height="100%"
      loading={loading}
      onChange={(v) => onChange(v ?? '')}
      onMount={onMount}
      wrapperProps={{ 'data-editor': 'monaco' }}
      options={{
        ariaLabel,
        readOnly,
        automaticLayout: true,
        minimap: { enabled: false },
        scrollBeyondLastLine: false,
        fixedOverflowWidgets: true,
        fontFamily: "'SF Mono', ui-monospace, Menlo, monospace",
        fontSize: 12,
        lineHeight: 18,
        tabSize: 2,
        insertSpaces: true,
        padding: { top: 8, bottom: 8 },
        lineNumbers: expression ? 'off' : 'on',
        lineNumbersMinChars: 3,
        folding: !expression,
        glyphMargin: false,
        wordWrap: expression ? 'on' : 'off',
        renderLineHighlight: 'line',
        overviewRulerBorder: false,
        hideCursorInOverviewRuler: true,
      }}
    />
  )
}
