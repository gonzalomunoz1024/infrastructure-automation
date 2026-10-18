import { renderToString } from 'react-dom/server'
import { describe, expect, it } from 'vitest'
import { CodeEditor, EXPRESSION_LANGUAGE, canRunMonaco } from '../components/code-editor/CodeEditor'
import { ExpressionInput } from '../designer/ndv/ExpressionInput'

/* Monaco only runs in a browser: anywhere else the editors are the textarea, with the same label. */

const text = (html: string) => html.replace(/<!--.*?-->/g, '')

describe('CodeEditor', () => {
  it('is the monospace textarea where Monaco cannot run, with its label, test id and value', () => {
    expect(canRunMonaco()).toBe(false)
    const html = text(renderToString(<CodeEditor language="yaml" ariaLabel="Workflow YAML" testId="yaml-editor" value={'kind: Workflow\n'} onChange={() => {}} className="min-h-[60vh]" />))
    expect(html).toMatch(/^<textarea/)
    expect(html).toContain('aria-label="Workflow YAML"')
    expect(html).toContain('data-testid="yaml-editor"')
    expect(html).toContain('spellCheck="false"')
    expect(html).toContain('font-mono')
    expect(html).toContain('min-h-[60vh]')
    expect(html).toContain('kind: Workflow')
    expect(html).not.toContain('monaco')
  })

  it('keeps an expression editor read only when asked', () => {
    const html = text(renderToString(<CodeEditor language={EXPRESSION_LANGUAGE} ariaLabel="Channel expression" value="{{ $input.channel }}" onChange={() => {}} readOnly />))
    expect(html).toContain('readOnly=""')
    expect(html).toContain('{{ $input.channel }}')
  })
})

describe('the expand button of an expression field', () => {
  it('opens the expanded editor from a field of several lines', () => {
    const html = text(renderToString(<ExpressionInput value="{{ $input.reason }}" onChange={() => {}} ariaLabel="Reason" />))
    expect(html).toContain('aria-label="Expand Reason"')
    expect(html).toContain('iaf-expr--expandable')
  })

  it('is not offered on one line or read only', () => {
    expect(text(renderToString(<ExpressionInput value="x" onChange={() => {}} ariaLabel="Value of a" singleLine />))).not.toContain('Expand')
    expect(text(renderToString(<ExpressionInput value="x" onChange={() => {}} ariaLabel="Reason" readOnly />))).not.toContain('Expand')
  })
})
