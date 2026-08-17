import { isApiError } from '@shopflow/api-client'
import type { FieldValues, Path, UseFormSetError } from 'react-hook-form'

/**
 * Merges server-side validation into a form instead of replacing it. Each ProblemDetail entry
 * whose field the form owns lands on that input; anything else, plus the problem's `detail`,
 * comes back as a form-level message. Returns null when every message found an input, because
 * a banner repeating them would be noise.
 */
export function applyApiErrorToForm<T extends FieldValues>(
  error: unknown,
  setError: UseFormSetError<T>,
  knownFields: readonly Path<T>[],
): string | null {
  if (!isApiError(error)) return 'An unexpected error occurred. Please try again.'

  const unmatched: string[] = []
  for (const fieldError of error.fieldErrors) {
    const path = fieldError.field as Path<T>
    if (knownFields.includes(path)) {
      setError(path, { type: 'server', message: fieldError.message })
    } else {
      unmatched.push(`${fieldError.field}: ${fieldError.message}`)
    }
  }

  if (error.fieldErrors.length > 0 && unmatched.length === 0) return null

  const summary = error.detail ?? error.title ?? `Request failed with status ${error.status}`
  return [summary, ...unmatched].join(' ')
}
