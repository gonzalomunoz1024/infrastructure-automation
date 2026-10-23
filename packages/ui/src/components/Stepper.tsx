import { cx } from '../lib/cx'
import { Icon } from './Icon'

export interface StepperStep {
  id: string
  label: string
  description?: string
  optional?: boolean
}

export interface StepperProps {
  steps: StepperStep[]
  /** index of the current step */
  current: number
  /** lets the user go back to a completed step */
  onStepClick?: (index: number) => void
  orientation?: 'horizontal' | 'vertical'
  className?: string
}

type StepState = 'done' | 'current' | 'upcoming'

function Marker({ state, index }: { state: StepState; index: number }) {
  return (
    <span
      className={cx(
        'flex h-6 w-6 shrink-0 items-center justify-center rounded-full text-caption2 font-semibold transition-colors',
        state === 'done' && 'bg-accent text-white',
        state === 'current' && 'border-2 border-accent bg-accent-tint text-accent',
        state === 'upcoming' && 'border border-stroke text-tertiary',
      )}
      aria-hidden="true"
    >
      {state === 'done' ? <Icon name="check" size={12} strokeWidth={2.4} /> : index + 1}
    </span>
  )
}

/** Wizard progress: numbered markers joined by hairlines, done ones in accent. */
export function Stepper({ steps, current, onStepClick, orientation = 'horizontal', className }: StepperProps) {
  const stateOf = (i: number): StepState => (i < current ? 'done' : i === current ? 'current' : 'upcoming')
  const horizontal = orientation === 'horizontal'

  return (
    <ol className={cx(horizontal ? 'flex items-start' : 'flex flex-col', className)} aria-label="Steps">
      {steps.map((s, i) => {
        const state = stateOf(i)
        const clickable = !!onStepClick && state === 'done'
        const last = i === steps.length - 1
        const body = (
          <>
            <Marker state={state} index={i} />
            <span className="min-w-0">
              <span
                className={cx(
                  'block text-sm leading-6',
                  state === 'current' ? 'font-semibold text-primary' : state === 'done' ? 'font-medium text-primary' : 'text-tertiary',
                )}
              >
                {s.label}
                {s.optional && <span className="ml-1 text-caption font-normal text-tertiary">optional</span>}
              </span>
              {s.description && <span className="block text-caption text-tertiary">{s.description}</span>}
            </span>
          </>
        )
        const content = clickable ? (
          <button type="button" onClick={() => onStepClick?.(i)} className="flex min-w-0 items-start gap-2.5 text-left transition-opacity hover:opacity-70">
            {body}
          </button>
        ) : (
          <div className="flex min-w-0 items-start gap-2.5">{body}</div>
        )

        if (horizontal) {
          return (
            <li key={s.id} aria-current={state === 'current' ? 'step' : undefined} className={cx('flex min-w-0 items-start', !last && 'flex-1')}>
              {content}
              {!last && <div className={cx('mx-3 mt-3 h-px min-w-4 flex-1', state === 'done' ? 'bg-accent' : 'bg-stroke-light')} aria-hidden="true" />}
            </li>
          )
        }
        return (
          <li key={s.id} aria-current={state === 'current' ? 'step' : undefined} className="flex gap-0">
            <div className="flex w-6 shrink-0 flex-col items-center">
              <div className="h-6" />
              {!last && <div className={cx('w-px flex-1', state === 'done' ? 'bg-accent' : 'bg-stroke-light')} aria-hidden="true" />}
            </div>
            <div className={cx('-ml-6', !last && 'pb-5')}>{content}</div>
          </li>
        )
      })}
    </ol>
  )
}
