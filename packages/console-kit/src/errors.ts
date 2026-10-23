/*
 * The kit never imports an app's API client. It recognises a failed call by its shape (the
 * console's ApiError and the platform's both have a numeric `status` and an `error` line), so the
 * error states it draws say the same thing whichever client threw.
 */

export interface ApiErrorLike {
  status: number
  error: string
  message: string
  /** status 0: the backend did not answer */
  unreachable?: boolean
  /** a 500's trace id, which finds the cause in the API's log */
  correlationId?: string
}

export function isApiErrorLike(e: unknown): e is ApiErrorLike {
  return !!e && typeof e === 'object' && typeof (e as ApiErrorLike).status === 'number' && typeof (e as ApiErrorLike).error === 'string'
}

/** A one-line message for any thrown value. */
export function errorMessage(e: unknown): string {
  if (isApiErrorLike(e)) return e.correlationId ? `${e.error} (correlation id ${e.correlationId})` : e.error
  if (e instanceof Error) return e.message
  return String(e)
}
