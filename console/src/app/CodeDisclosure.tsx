import { Icon, cx } from '@iaf/ui'
import { CodeView, type CodeSample } from '@iaf/console-kit'

/*
 * "View as code" folded under a form (WP 3.5): the same request as a CLI line, REST calls and a
 * WorkflowRun manifest, one click away on every wizard step and every action. A native
 * <details>, so it opens with the keyboard and a screen reader says whether it is open.
 */
export function CodeDisclosure({ samples, ariaLabel, open, className }: { samples: CodeSample[]; ariaLabel: string; open?: boolean; className?: string }) {
  return (
    <details className={cx('group rounded-lg border border-stroke-light', className)} open={open} data-testid="view-as-code">
      <summary className="flex cursor-pointer list-none items-center gap-2 rounded-lg px-3 py-2.5 text-sm font-medium text-primary transition-colors hover:bg-surface-secondary [&::-webkit-details-marker]:hidden">
        <Icon name="terminal" size={14} className="text-tertiary" />
        View as code
        <Icon name="chevron-down" size={14} className="ml-auto text-tertiary transition-transform group-open:rotate-180" />
      </summary>
      <div className="border-t border-stroke-light p-3">
        <CodeView samples={samples} ariaLabel={ariaLabel} />
      </div>
    </details>
  )
}
