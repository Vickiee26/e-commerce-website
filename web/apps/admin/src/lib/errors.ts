import { isApiError } from '@shopflow/api-client'

export type ErrorDescription = {
  heading: string
  body: string
  /** Whether offering Retry makes sense. A 403 or a 400 will answer the same way again. */
  retryable: boolean
}

export function describeError(error: unknown): ErrorDescription {
  if (!isApiError(error)) {
    return {
      heading: 'Something went wrong',
      body: 'An unexpected error occurred. Please try again.',
      retryable: true,
    }
  }

  if (error.status === 0) {
    return {
      heading: 'Cannot reach the server',
      body: error.detail ?? 'Check your connection and try again.',
      retryable: true,
    }
  }
  if (error.status === 403) {
    return {
      heading: 'Not permitted',
      body: 'You do not have permission for this action.',
      retryable: false,
    }
  }
  if (error.status === 404) {
    return {
      heading: 'Not found',
      body: error.detail ?? 'That record no longer exists.',
      retryable: false,
    }
  }
  if (error.status === 409) {
    // Conflicts here are meaningful — a duplicate code, stock that would go negative — so the
    // server's own wording is better than anything generic.
    return {
      heading: 'Conflict',
      body: error.detail ?? error.title ?? 'That change conflicts with the current state.',
      retryable: false,
    }
  }
  if (error.status >= 500) {
    return {
      heading: 'The server failed',
      body: error.detail ?? 'Something broke on the server. Try again in a moment.',
      retryable: true,
    }
  }
  return {
    heading: error.title ?? 'Request failed',
    body: error.detail ?? 'Check the values and try again.',
    retryable: false,
  }
}
