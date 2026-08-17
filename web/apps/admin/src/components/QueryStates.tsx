import type { ReactElement, ReactNode } from 'react'
import { describeError } from '../lib/errors'
import { Button } from './Button'

export function Skeleton({ rows = 3, label = 'Loading' }: { rows?: number; label?: string }): ReactElement {
  return (
    <div role="status" className="flex flex-col gap-3" aria-live="polite">
      <span className="sr-only">{label}</span>
      {Array.from({ length: rows }, (_, index) => (
        <div key={index} className="h-16 animate-pulse rounded-lg bg-slate-200" />
      ))}
    </div>
  )
}

export function ErrorPanel({ error, onRetry }: { error: unknown; onRetry?: () => void }): ReactElement {
  const { heading, body, retryable } = describeError(error)

  return (
    <div className="rounded-lg border border-red-200 bg-red-50 p-4">
      <h2 className="text-base font-semibold text-red-900">{heading}</h2>
      <p className="mt-1 text-sm text-red-800">{body}</p>
      {retryable && onRetry !== undefined ? (
        <Button variant="secondary" className="mt-3" onClick={onRetry}>
          Retry
        </Button>
      ) : null}
    </div>
  )
}

export function EmptyState({
  title,
  description,
  action,
}: {
  title: string
  description: string
  action?: ReactNode
}): ReactElement {
  return (
    <div className="flex flex-col items-center gap-3 rounded-lg border border-dashed border-slate-300 bg-white p-8 text-center">
      <h2 className="text-lg font-semibold text-slate-900">{title}</h2>
      <p className="max-w-sm text-sm text-slate-600">{description}</p>
      {action}
    </div>
  )
}
