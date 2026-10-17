import { useId, type InputHTMLAttributes, type ReactNode, type TextareaHTMLAttributes } from 'react'
import { cx } from '../lib/cx'
import { Icon, type IconName } from './Icon'

/** The Atlas text-input recipe, for custom inputs (dates, numbers) that need to match. */
export const inputClass =
  'no-focus-ring w-full rounded-md border border-stroke-light bg-surface-secondary text-primary placeholder:text-tertiary transition-colors focus:border-accent focus:outline-none focus:ring-2 focus:ring-accent/25 disabled:cursor-not-allowed disabled:opacity-50'

export interface FieldProps {
  label?: ReactNode
  /** quiet helper text under the control */
  hint?: ReactNode
  /** replaces the hint, in critical */
  error?: ReactNode
  required?: boolean
  htmlFor?: string
  children: ReactNode
  className?: string
  /** label and control side by side (settings rows) */
  inline?: boolean
}

/** Label + control + hint/error. Wrap any Input, Textarea, Select or Switch. */
export function Field({ label, hint, error, required, htmlFor, children, className, inline }: FieldProps) {
  return (
    <div className={cx(inline ? 'flex items-center justify-between gap-6' : 'flex flex-col gap-1.5', className)}>
      {label && (
        <label htmlFor={htmlFor} className={cx('text-caption font-medium text-secondary', inline && 'shrink-0')}>
          {label}
          {required && (
            <span className="ml-0.5 text-critical" aria-hidden="true">
              *
            </span>
          )}
        </label>
      )}
      <div className={cx('min-w-0', inline ? 'flex justify-end' : 'flex flex-col gap-1')}>
        {children}
        {(error || hint) && !inline && (
          <p className={cx('text-caption2', error ? 'text-critical' : 'text-tertiary')} role={error ? 'alert' : undefined}>
            {error ?? hint}
          </p>
        )}
      </div>
    </div>
  )
}

export interface InputProps extends Omit<InputHTMLAttributes<HTMLInputElement>, 'size'> {
  invalid?: boolean
  mono?: boolean
  /** leading glyph inside the field */
  leading?: IconName
  /** trailing slot inside the field (a button, a unit) */
  trailing?: ReactNode
  size?: 'sm' | 'md'
}

export function Input({ invalid, mono, leading, trailing, size = 'md', className, ...rest }: InputProps) {
  const input = (
    <input
      aria-invalid={invalid || undefined}
      className={cx(
        inputClass,
        size === 'sm' ? 'py-1.5 text-caption' : 'py-2 text-sm',
        leading ? 'pl-9' : 'px-3',
        trailing ? 'pr-9' : 'pr-3',
        mono && 'font-mono',
        invalid && 'border-critical focus:border-critical focus:ring-critical/20',
        className,
      )}
      {...rest}
    />
  )
  if (!leading && !trailing) return input
  return (
    <div className="relative w-full">
      {leading && (
        <Icon
          name={leading}
          size={15}
          className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-tertiary"
        />
      )}
      {input}
      {trailing && <div className="absolute right-2 top-1/2 flex -translate-y-1/2 items-center">{trailing}</div>}
    </div>
  )
}

export interface TextareaProps extends TextareaHTMLAttributes<HTMLTextAreaElement> {
  invalid?: boolean
  mono?: boolean
}

export function Textarea({ invalid, mono, className, rows = 4, ...rest }: TextareaProps) {
  return (
    <textarea
      rows={rows}
      aria-invalid={invalid || undefined}
      className={cx(
        inputClass,
        'resize-y px-3 py-2 text-sm leading-relaxed',
        mono && 'font-mono text-caption',
        invalid && 'border-critical focus:border-critical focus:ring-critical/20',
        className,
      )}
      {...rest}
    />
  )
}

export interface CheckboxProps {
  checked: boolean
  onChange: (checked: boolean) => void
  label?: ReactNode
  description?: ReactNode
  disabled?: boolean
  id?: string
  name?: string
  className?: string
}

/** Native checkbox, drawn in the kit's idiom (rounded square, accent fill, white check). */
export function Checkbox({ checked, onChange, label, description, disabled, id, name, className }: CheckboxProps) {
  const auto = useId()
  const inputId = id ?? auto
  return (
    <label htmlFor={inputId} className={cx('flex cursor-pointer items-start gap-2.5 text-sm', disabled && 'cursor-not-allowed opacity-50', className)}>
      <span className="relative mt-0.5 flex h-4 w-4 shrink-0 items-center justify-center">
        <input
          id={inputId}
          name={name}
          type="checkbox"
          checked={checked}
          disabled={disabled}
          onChange={(e) => onChange(e.target.checked)}
          className="peer absolute inset-0 h-4 w-4 cursor-pointer appearance-none rounded-[5px] border border-stroke bg-surface transition-colors checked:border-accent checked:bg-accent disabled:cursor-not-allowed"
        />
        <Icon
          name="check"
          size={11}
          strokeWidth={2.6}
          className="pointer-events-none relative text-white opacity-0 transition-opacity peer-checked:opacity-100"
        />
      </span>
      {(label || description) && (
        <span className="min-w-0">
          {label && <span className="block text-primary">{label}</span>}
          {description && <span className="block text-caption text-tertiary">{description}</span>}
        </span>
      )}
    </label>
  )
}

export interface SwitchProps {
  checked: boolean
  onChange: (checked: boolean) => void
  /** accessible name (required when no visible label sits next to it) */
  label: string
  disabled?: boolean
  size?: 'sm' | 'md'
  className?: string
  id?: string
}

/** Toggle switch: accent track when on, spring-eased knob. */
export function Switch({ checked, onChange, label, disabled, size = 'md', className, id }: SwitchProps) {
  const sm = size === 'sm'
  return (
    <button
      id={id}
      type="button"
      role="switch"
      aria-checked={checked}
      aria-label={label}
      disabled={disabled}
      onClick={() => onChange(!checked)}
      className={cx(
        'relative inline-flex shrink-0 items-center rounded-full transition-colors duration-200',
        sm ? 'h-[18px] w-[30px]' : 'h-[22px] w-[38px]',
        checked ? 'bg-accent' : 'bg-stroke',
        className,
      )}
    >
      <span
        className={cx(
          'absolute left-[2px] rounded-full bg-white shadow-sm transition-transform duration-200 [transition-timing-function:var(--ease-spring)]',
          sm ? 'h-[14px] w-[14px]' : 'h-[18px] w-[18px]',
          checked && (sm ? 'translate-x-3' : 'translate-x-4'),
        )}
      />
    </button>
  )
}
