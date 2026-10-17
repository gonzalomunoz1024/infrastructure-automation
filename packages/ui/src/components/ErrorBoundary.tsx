import { Component, type ErrorInfo, type ReactNode } from 'react'
import { Button } from './Button'

export interface ErrorBoundaryProps {
  children: ReactNode
  /** what crashed, for the message ("This panel" by default) */
  label?: string
  /** custom fallback */
  fallback?: (error: Error, reset: () => void) => ReactNode
  onError?: (error: Error, info: ErrorInfo) => void
  /** when this changes, the boundary resets (e.g. the route path) */
  resetKey?: string | number
}

interface State {
  error: Error | null
}

/** Per-panel error boundary so one crashing widget never takes down the whole app. */
export class ErrorBoundary extends Component<ErrorBoundaryProps, State> {
  state: State = { error: null }

  static getDerivedStateFromError(error: Error): State {
    return { error }
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    this.props.onError?.(error, info)
  }

  componentDidUpdate(prev: ErrorBoundaryProps) {
    if (this.state.error && prev.resetKey !== this.props.resetKey) this.reset()
  }

  reset = () => this.setState({ error: null })

  render() {
    if (this.state.error) {
      if (this.props.fallback) return this.props.fallback(this.state.error, this.reset)
      return (
        <div className="rounded-lg border border-stroke-light bg-surface p-6 text-sm text-secondary" role="alert">
          <p className="font-medium text-critical">{this.props.label ?? 'This panel'} hit an error.</p>
          <p className="mt-1 font-mono text-caption">{this.state.error.message}</p>
          <Button size="sm" className="mt-3" onClick={this.reset}>
            Retry
          </Button>
        </div>
      )
    }
    return this.props.children
  }
}
