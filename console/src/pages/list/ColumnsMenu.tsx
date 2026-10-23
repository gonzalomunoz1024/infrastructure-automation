import { useRef } from 'react'
import { Button, Checkbox } from '@iaf/ui'
import { MenuButton } from '../../app/MenuButton'
import { columnChoice, type ListColumn } from '../../lib/resourceList'

/*
 * The list's columns: a checkbox per column the type can show, the name always on, and a way back
 * to the type's own set. The choice is the person's and follows them (the BFF's preferences).
 */

export interface ColumnsMenuProps {
  all: readonly ListColumn[]
  defaults: readonly string[]
  shown: readonly ListColumn[]
  /** the new choice, or null for the type's own set */
  onChange: (choice: string[] | null) => void
}

export function ColumnsMenu({ all, defaults, shown, onChange }: ColumnsMenuProps) {
  const on = new Set(shown.map((c) => c.key))
  const customised = columnChoice(all, defaults, on) !== null
  const boxes = useRef<HTMLDivElement>(null)
  return (
    <MenuButton
      label="Columns"
      icon="list"
      role="dialog"
      align="right"
      width="w-72"
      content={
        <div className="px-3 py-2">
          <fieldset>
            <legend className="text-sm font-semibold text-primary">Columns</legend>
            <p className="mt-0.5 text-caption text-secondary">Your choice follows you to every browser you sign in on.</p>
            <div ref={boxes} className="mt-3 space-y-2.5">
              {all.map((c) => (
                <Checkbox
                  key={c.key}
                  checked={on.has(c.key)}
                  disabled={c.key === 'name'}
                  description={c.key === 'name' ? 'Always shown' : undefined}
                  label={c.label}
                  onChange={(checked) => {
                    const next = new Set(on)
                    if (checked) next.add(c.key)
                    else next.delete(c.key)
                    onChange(columnChoice(all, defaults, next))
                  }}
                />
              ))}
            </div>
          </fieldset>
          <div className="mt-3 border-t border-stroke-light pt-2">
            <Button
              size="sm"
              variant="ghost"
              icon="refresh"
              disabled={!customised}
              onClick={() => {
                // the button goes disabled under the focus; the focus moves to the first column instead
                boxes.current?.querySelector<HTMLInputElement>('input:not([disabled])')?.focus()
                onChange(null)
              }}
            >
              Back to the type’s columns
            </Button>
          </div>
        </div>
      }
    />
  )
}
