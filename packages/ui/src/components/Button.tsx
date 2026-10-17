import type { ButtonHTMLAttributes } from 'react'
import { cx } from '../lib/cx'
import { Icon, type IconName } from './Icon'

export type ButtonVariant = 'primary' | 'secondary' | 'ghost' | 'capsule' | 'warning-outline' | 'danger'
export type ButtonSize = 'sm' | 'md'

const VARIANT: Record<ButtonVariant, string> = {
  primary: 'rounded-md bg-accent font-medium text-white shadow-sm hover:brightness-110',
  secondary: 'rounded-md bg-surface-secondary font-medium text-primary hover:bg-stroke-light',
  ghost: 'rounded-full text-secondary hover:bg-surface-secondary hover:text-primary',
  capsule:
    'rounded-full border border-stroke-light text-secondary hover:bg-surface-secondary hover:text-primary',
  'warning-outline': 'rounded-md border border-warning font-medium text-warning hover:bg-warning-tint',
  danger: 'rounded-md bg-critical font-medium text-white shadow-sm hover:brightness-110',
}

const SIZE: Record<ButtonSize, string> = {
  sm: 'px-3 py-1.5 text-caption',
  md: 'px-4 py-2 text-sm',
}

const ICON_SIZE: Record<ButtonSize, number> = { sm: 13, md: 15 }

export interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: ButtonVariant
  size?: ButtonSize
  /** shows a spinner in place of the icon and disables the button */
  loading?: boolean
  /** leading glyph */
  icon?: IconName
  /** trailing glyph */
  iconRight?: IconName
}

/** The one spinner: a ring in the current text colour. */
export function Spinner({ size = 14, className }: { size?: number; className?: string }) {
  return (
    <span
      role="status"
      aria-label="Loading"
      className={cx('inline-block shrink-0 animate-spin rounded-full border-2 border-current border-t-transparent', className)}
      style={{ width: size, height: size }}
    />
  )
}

/** The one button. Press feedback via active scale; variants cover every current style. */
export function Button({
  variant = 'secondary',
  size = 'md',
  loading = false,
  icon,
  iconRight,
  className,
  children,
  disabled,
  type = 'button',
  ...rest
}: ButtonProps) {
  return (
    <button
      type={type}
      aria-busy={loading || undefined}
      disabled={disabled || loading}
      className={cx(
        'inline-flex select-none items-center justify-center gap-1.5 whitespace-nowrap transition-all duration-150',
        'active:scale-[0.97] disabled:active:scale-100',
        VARIANT[variant],
        SIZE[size],
        className,
      )}
      {...rest}
    >
      {loading ? <Spinner size={ICON_SIZE[size]} /> : icon && <Icon name={icon} size={ICON_SIZE[size]} />}
      {children}
      {iconRight && !loading && <Icon name={iconRight} size={ICON_SIZE[size]} />}
    </button>
  )
}

export interface IconButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  /** accessible name; also the tooltip */
  label: string
  size?: 'sm' | 'md'
}

/** Round icon-only button (header actions, close buttons). */
export function IconButton({ className, label, size = 'md', type = 'button', ...rest }: IconButtonProps) {
  return (
    <button
      type={type}
      aria-label={label}
      title={label}
      className={cx(
        'flex shrink-0 items-center justify-center rounded-full text-tertiary',
        size === 'sm' ? 'h-7 w-7' : 'h-9 w-9',
        'transition-all duration-150 hover:bg-surface-secondary hover:text-primary active:scale-[0.94]',
        className,
      )}
      {...rest}
    />
  )
}
