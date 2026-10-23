import type { KeyboardEvent, MouseEvent, ReactElement } from 'react'
import { cx } from '@iaf/ui'
import { Hint } from '../../app/Hint'

/*
 * The star that makes a resource a favourite and the pin that keeps a type on the console home.
 * Both are toggle buttons (aria-pressed) whose name stays the same whichever way they are set, so
 * a screen reader hears "Favourite web-01, pressed". Atlas has no star or pin glyph, and the
 * console never edits Atlas, so the two are drawn here in its icon style (24 viewBox, 1.7 stroke,
 * round joins), filled when on. A click or key on a star in a list row stays on the star: the
 * row's own Enter and Space (open, select) never see it.
 */

function StarGlyph({ on, size }: { on: boolean; size: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill={on ? 'currentColor' : 'none'} stroke="currentColor" strokeWidth={1.7} strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" focusable="false">
      <path d="M12 3.5l2.6 5.3 5.9.9-4.3 4.1 1 5.8L12 16.9l-5.2 2.7 1-5.8-4.3-4.1 5.9-.9z" />
    </svg>
  )
}

function PinGlyph({ on, size }: { on: boolean; size: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill={on ? 'currentColor' : 'none'} stroke="currentColor" strokeWidth={1.7} strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" focusable="false">
      <path d="M9 3.5h6l-1 5.5 3.5 3.5h-11L10 9z" />
      <path d="M12 12.5V20.5" fill="none" />
    </svg>
  )
}

interface ToggleProps {
  on: boolean
  /** what it is a favourite or a pin of, for its name: "web-01" */
  name: string
  onToggle: () => void
  size?: 'sm' | 'md'
  className?: string
}

const stay = (e: MouseEvent | KeyboardEvent) => e.stopPropagation()

function Toggle({ on, label, hint, onToggle, size = 'md', className, glyph, data }: { on: boolean; label: string; hint: string; onToggle: () => void; size?: 'sm' | 'md'; className?: string; glyph: (on: boolean, size: number) => ReactElement; data: Record<string, string | undefined> }) {
  const px = size === 'sm' ? 15 : 17
  return (
    <Hint label={hint}>
      <button
        type="button"
        aria-pressed={on}
        onClick={(e) => {
          stay(e)
          onToggle()
        }}
        onKeyDown={stay}
        className={cx(
          'relative inline-flex shrink-0 items-center justify-center rounded-md outline-none transition-colors focus-visible:ring-2 focus-visible:ring-accent/60',
          size === 'sm' ? 'size-7' : 'size-9',
          on ? 'text-accent' : 'text-secondary hover:bg-surface-secondary hover:text-primary',
          className,
        )}
        {...data}
      >
        {glyph(on, px)}
        <span className="sr-only">{label}</span>
      </button>
    </Hint>
  )
}

/** Star a resource: a favourite on the console home. */
export function FavouriteStar({ on, name, onToggle, size, className }: ToggleProps) {
  return <Toggle on={on} label={`Favourite ${name}`} hint={on ? `On your favourites. Select to remove ${name}.` : `Add ${name} to your favourites on the console home`} onToggle={onToggle} size={size} className={className} glyph={(o, px) => <StarGlyph on={o} size={px} />} data={{ 'data-favourite': on ? 'on' : 'off' }} />
}

/** Pin a resource type: a favourite service on the console home. */
export function PinButton({ on, name, onToggle, size, className }: ToggleProps) {
  return <Toggle on={on} label={`Pin ${name}`} hint={on ? `Pinned to the console home. Select to unpin ${name}.` : `Pin ${name} to the console home`} onToggle={onToggle} size={size} className={className} glyph={(o, px) => <PinGlyph on={o} size={px} />} data={{ 'data-pinned': on ? 'on' : 'off' }} />
}
