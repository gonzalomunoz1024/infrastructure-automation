/**
 * A code editor: Monaco once its chunk has loaded, and the plain monospace textarea until then,
 * when the chunk fails, and wherever Monaco cannot run (renderToString in the tests, jsdom). Both
 * take the same value, change, blur and label; only Monaco shows the markers, which the caller
 * also lists in words.
 */
import { Component, Suspense, lazy, type ReactNode } from 'react'
import { Textarea, cx } from '@iaf/ui'
import type { EditorMarker } from './markers'

/** The platform's `{{ … }}` expressions inside plain text (registered by monaco.ts). */
export const EXPRESSION_LANGUAGE = 'iaf-expression'

export type CodeLanguage = 'yaml' | 'json' | typeof EXPRESSION_LANGUAGE

export interface CodeEditorProps {
  value: string
  onChange: (value: string) => void
  /** focus left the editor (its find box and menus count as inside) */
  onBlur?: () => void
  language: CodeLanguage
  markers?: EditorMarker[]
  ariaLabel: string
  testId?: string
  readOnly?: boolean
  autoFocus?: boolean
  /** the box's classes: its height comes from here */
  className?: string
}

const MonacoEditor = lazy(() => import('./MonacoEditor'))

/** Monaco needs a browser with workers: not renderToString, not jsdom. */
export function canRunMonaco(): boolean {
  return typeof window !== 'undefined' && typeof Worker !== 'undefined' && !/jsdom/i.test(navigator.userAgent)
}

function PlainEditor({ value, onChange, onBlur, ariaLabel, testId, readOnly, autoFocus, className }: CodeEditorProps) {
  return (
    <Textarea
      mono
      aria-label={ariaLabel}
      data-testid={testId}
      spellCheck={false}
      value={value}
      readOnly={readOnly}
      autoFocus={autoFocus}
      onChange={(e) => onChange(e.target.value)}
      onBlur={() => onBlur?.()}
      className={cx('resize-none whitespace-pre leading-[18px]', className)}
    />
  )
}

/** A chunk that fails to load leaves the textarea in place instead of an empty box. */
class Fallback extends Component<{ plain: ReactNode; children: ReactNode }, { failed: boolean }> {
  state = { failed: false }
  static getDerivedStateFromError() {
    return { failed: true }
  }
  render() {
    return this.state.failed ? this.props.plain : this.props.children
  }
}

export function CodeEditor(props: CodeEditorProps) {
  if (!canRunMonaco()) return <PlainEditor {...props} />
  const plain = <PlainEditor {...props} className="h-full min-h-0 rounded-none border-0" testId={undefined} />
  return (
    <div className={cx('relative min-h-0 overflow-hidden rounded-md border border-stroke-light bg-surface-secondary', props.className)} data-testid={props.testId}>
      <Fallback plain={plain}>
        <Suspense fallback={plain}>
          <MonacoEditor {...props} loading={plain} />
        </Suspense>
      </Fallback>
    </div>
  )
}
