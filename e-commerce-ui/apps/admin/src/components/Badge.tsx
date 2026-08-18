import type { ReactElement, ReactNode } from 'react'

export type BadgeTone = 'neutral' | 'warning' | 'danger' | 'success'

const TONE_CLASS: Record<BadgeTone, string> = {
  neutral: 'bg-slate-100 text-slate-700',
  warning: 'bg-amber-100 text-amber-800',
  danger: 'bg-red-100 text-red-800',
  success: 'bg-emerald-100 text-emerald-800',
}

export function Badge({
  tone = 'neutral',
  children,
}: {
  tone?: BadgeTone
  children: ReactNode
}): ReactElement {
  return (
    <span
      className={`inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium ${TONE_CLASS[tone]}`}
    >
      {children}
    </span>
  )
}
