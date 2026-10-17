import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { cx } from '../lib/cx'
import { nextId } from '../lib/id'
import { Icon, type IconName } from './Icon'
import { IconButton } from './Button'

export type ToastTone = 'neutral' | 'accent' | 'healthy' | 'warning' | 'critical'

export interface ToastAction {
  label: string
  onClick: () => void
}

export interface ToastOptions {
  title: string
  message?: ReactNode
  tone?: ToastTone
  /** ms before auto-dismiss; 0 keeps it until dismissed (default 4.5s, 7s for critical) */
  duration?: number
  action?: ToastAction
  /** reuse an id to replace a toast in place */
  id?: string
}

export interface ToastApi {
  toast: (options: ToastOptions | string) => string
  dismiss: (id: string) => void
  success: (title: string, message?: ReactNode) => string
  error: (title: string, message?: ReactNode) => string
}

interface ToastItem {
  id: string
  title: string
  message?: ReactNode
  tone: ToastTone
  duration: number
  action?: ToastAction
  closing: boolean
}

const noop: ToastApi = { toast: () => '', dismiss: () => {}, success: () => '', error: () => '' }
const ToastCtx = createContext<ToastApi>(noop)

/** Toasts from anywhere under a ToastProvider. */
export function useToast(): ToastApi {
  return useContext(ToastCtx)
}

const ICON: Record<ToastTone, IconName> = {
  neutral: 'info',
  accent: 'info',
  healthy: 'check',
  warning: 'alert-triangle',
  critical: 'close-circle',
}

const ICON_CLASS: Record<ToastTone, string> = {
  neutral: 'bg-surface-secondary text-secondary',
  accent: 'bg-accent-tint text-accent',
  healthy: 'bg-healthy-tint text-healthy',
  warning: 'bg-warning-tint text-warning',
  critical: 'bg-critical-tint text-critical',
}

/** Bottom-centre stack, auto-dismiss, exit animation before removal. Mount once at the app root. */
export function ToastProvider({ children, max = 4 }: { children: ReactNode; max?: number }) {
  const [items, setItems] = useState<ToastItem[]>([])
  const timers = useRef(new Map<string, ReturnType<typeof setTimeout>>())

  const clearTimer = useCallback((id: string) => {
    const t = timers.current.get(id)
    if (t) {
      clearTimeout(t)
      timers.current.delete(id)
    }
  }, [])

  const dismiss = useCallback(
    (id: string) => {
      clearTimer(id)
      setItems((list) => list.map((t) => (t.id === id ? { ...t, closing: true } : t)))
    },
    [clearTimer],
  )

  const remove = useCallback((id: string) => setItems((list) => list.filter((t) => t.id !== id)), [])

  const schedule = useCallback(
    (id: string, ms: number) => {
      clearTimer(id)
      if (ms > 0) timers.current.set(id, setTimeout(() => dismiss(id), ms))
    },
    [clearTimer, dismiss],
  )

  const toast = useCallback(
    (o: ToastOptions | string) => {
      const opts = typeof o === 'string' ? { title: o } : o
      const id = opts.id ?? nextId('toast')
      const tone = opts.tone ?? 'neutral'
      const duration = opts.duration ?? (tone === 'critical' ? 7000 : 4500)
      const item: ToastItem = { id, title: opts.title, message: opts.message, tone, duration, action: opts.action, closing: false }
      setItems((list) => [...list.filter((t) => t.id !== id), item].slice(-max))
      schedule(id, duration)
      return id
    },
    [max, schedule],
  )

  useEffect(() => {
    const map = timers.current
    return () => map.forEach((t) => clearTimeout(t))
  }, [])

  const api = useMemo<ToastApi>(
    () => ({
      toast,
      dismiss,
      success: (title, message) => toast({ title, message, tone: 'healthy' }),
      error: (title, message) => toast({ title, message, tone: 'critical' }),
    }),
    [toast, dismiss],
  )

  return (
    <ToastCtx.Provider value={api}>
      {children}
      <div
        className="pointer-events-none fixed inset-x-0 bottom-6 z-[80] flex flex-col items-center gap-2 px-4"
        role="region"
        aria-live="polite"
        aria-label="Notifications"
      >
        {items.map((t) => (
          <ToastCard
            key={t.id}
            item={t}
            onDismiss={() => dismiss(t.id)}
            onGone={() => remove(t.id)}
            onPause={() => clearTimer(t.id)}
            onResume={() => schedule(t.id, Math.min(t.duration || 0, 2000))}
          />
        ))}
      </div>
    </ToastCtx.Provider>
  )
}

function ToastCard({
  item,
  onDismiss,
  onGone,
  onPause,
  onResume,
}: {
  item: ToastItem
  onDismiss: () => void
  onGone: () => void
  onPause: () => void
  onResume: () => void
}) {
  // safety net: remove even if the exit animation never reports its end
  useEffect(() => {
    if (!item.closing) return
    const t = setTimeout(onGone, 300)
    return () => clearTimeout(t)
  }, [item.closing, onGone])

  return (
    <div
      role="status"
      onMouseEnter={onPause}
      onMouseLeave={onResume}
      onAnimationEnd={() => item.closing && onGone()}
      className={cx(
        'material-panel pointer-events-auto flex w-full max-w-sm items-start gap-3 rounded-lg px-4 py-3',
        item.closing ? 'animate-fade-out' : 'animate-fade-in',
      )}
    >
      <span className={cx('mt-0.5 flex h-6 w-6 shrink-0 items-center justify-center rounded-full', ICON_CLASS[item.tone])}>
        <Icon name={ICON[item.tone]} size={13} strokeWidth={2} />
      </span>
      <div className="min-w-0 flex-1">
        <p className="text-sm font-medium text-primary">{item.title}</p>
        {item.message && <p className="mt-0.5 text-caption text-secondary">{item.message}</p>}
        {item.action && (
          <button
            type="button"
            onClick={() => {
              item.action?.onClick()
              onDismiss()
            }}
            className="mt-1.5 text-caption font-medium text-accent transition-opacity hover:opacity-70"
          >
            {item.action.label}
          </button>
        )}
      </div>
      <IconButton label="Dismiss" size="sm" onClick={onDismiss} className="-mr-1.5 -mt-1">
        <Icon name="close" size={14} />
      </IconButton>
    </div>
  )
}
