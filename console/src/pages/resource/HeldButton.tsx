import { Button, type ButtonSize, type ButtonVariant, type IconName } from '@iaf/ui'
import { Hint } from '../../app/Hint'

/*
 * A button held back for a reason the person can act on or wait out (nothing changed yet,
 * something depends on the resource, a newer version to load, a request in flight): it stays in
 * the Tab order, says why in its name and in a hint, and does nothing when pressed, like a
 * LaterButton.
 */

export interface HeldButtonProps {
  reason: string
  children: string
  variant?: ButtonVariant
  size?: ButtonSize
  icon?: IconName
  verb?: string
}

export function HeldButton({ reason, children, variant = 'primary', size, icon, verb }: HeldButtonProps) {
  return (
    <Hint label={reason}>
      <Button variant={variant} size={size} icon={icon} aria-disabled="true" data-verb={verb} data-held={reason} onClick={(e) => e.preventDefault()} className="cursor-not-allowed opacity-60 active:scale-100">
        {children}
        <span className="sr-only">. {reason}.</span>
      </Button>
    </Hint>
  )
}
