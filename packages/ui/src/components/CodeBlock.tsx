import { Fragment, type ReactNode } from 'react'
import { cx } from '../lib/cx'
import { CopyIconButton } from './CopyButton'

export interface CodeBlockProps {
  code: string
  /** shown in the title bar, e.g. 'yaml' */
  language?: string
  title?: ReactNode
  lineNumbers?: boolean
  /** copy button (default true) */
  copy?: boolean
  /** wrap long lines instead of scrolling */
  wrap?: boolean
  /** class for the scroll area, e.g. 'max-h-80' */
  maxHeight?: string
  className?: string
}

/** Monospace block on the recessed surface: optional line numbers, title bar and copy button. */
export function CodeBlock({ code, language, title, lineNumbers = false, copy = true, wrap = false, maxHeight, className }: CodeBlockProps) {
  const lines = code.replace(/\n$/, '').split('\n')
  const hasBar = title !== undefined || language !== undefined
  return (
    <div className={cx('relative overflow-hidden rounded-md border border-stroke-light bg-surface-secondary', className)}>
      {hasBar && (
        <div className="flex h-8 items-center justify-between gap-3 border-b border-stroke-light pl-3 pr-1 text-caption2 text-tertiary">
          <span className="min-w-0 truncate font-medium text-secondary">{title}</span>
          <span className="flex items-center gap-1">
            {language && <span className="font-mono uppercase">{language}</span>}
            {copy && <CopyIconButton text={code} />}
          </span>
        </div>
      )}
      {!hasBar && copy && (
        <div className="absolute right-1.5 top-1.5 z-10">
          <CopyIconButton text={code} />
        </div>
      )}
      <pre className={cx('overflow-auto p-3 font-mono text-caption leading-relaxed text-primary', wrap ? 'whitespace-pre-wrap break-words' : 'whitespace-pre', maxHeight)}>
        {lineNumbers ? (
          <code className="grid grid-cols-[auto_1fr] gap-x-3">
            {lines.map((l, i) => (
              <Fragment key={i}>
                <span className="select-none text-right tabular-nums text-tertiary">{i + 1}</span>
                <span>{l || ' '}</span>
              </Fragment>
            ))}
          </code>
        ) : (
          <code>{code}</code>
        )}
      </pre>
    </div>
  )
}
