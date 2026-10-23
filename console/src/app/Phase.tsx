import { Button, Tooltip, type ButtonSize, type ButtonVariant, type IconName } from '@iaf/ui'

/*
 * Things the console shows but cannot do yet. A button for a later phase stays visible, says
 * which phase brings it, and does nothing when pressed, so nobody wonders whether it is broken.
 */

export const PHASES: Record<number, string> = {
  2: 'phase 2 (notifications, favourites and tags)',
  3: 'phase 3 (creating and changing resources)',
  7: 'phase 7 (custom roles and policies)',
}

export function phaseNote(phase: number): string {
  return `Available in ${PHASES[phase] ?? `phase ${phase}`}`
}

export interface LaterButtonProps {
  phase: number
  children: string
  variant?: ButtonVariant
  size?: ButtonSize
  icon?: IconName
  className?: string
}

/** A button for a later phase: focusable, marked disabled for assistive tech, the phase in its tooltip and its name. */
export function LaterButton({ phase, children, variant, size, icon, className }: LaterButtonProps) {
  const note = phaseNote(phase)
  return (
    <Tooltip label={note} side="bottom" className={className}>
      <Button variant={variant} size={size} icon={icon} aria-disabled="true" data-phase={phase} onClick={(e) => e.preventDefault()} className="cursor-not-allowed opacity-60 active:scale-100">
        {children}
        <span className="sr-only">. {note}.</span>
      </Button>
    </Tooltip>
  )
}
