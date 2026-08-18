import { useEffect, useId, useRef, type ReactElement, type ReactNode } from 'react'
import { Button } from './Button'

export type DialogProps = {
  open: boolean
  title: string
  onClose: () => void
  children: ReactNode
  footer?: ReactNode
}

/**
 * A full-width sheet anchored to the bottom on mobile and a centred modal from `md` up — the
 * same component, so the two presentations cannot drift apart.
 */
export function Dialog({ open, title, onClose, children, footer }: DialogProps): ReactElement | null {
  const titleId = useId()
  const panelRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (!open) return
    const onKeyDown = (event: KeyboardEvent): void => {
      if (event.key === 'Escape') onClose()
    }
    document.addEventListener('keydown', onKeyDown)
    panelRef.current?.focus()
    return () => document.removeEventListener('keydown', onKeyDown)
  }, [open, onClose])

  if (!open) return null

  return (
    <div
      className="fixed inset-0 z-50 flex items-end justify-center bg-slate-900/50 p-0 md:items-center md:p-4"
      onClick={onClose}
    >
      <div
        ref={panelRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        tabIndex={-1}
        onClick={(event) => event.stopPropagation()}
        className="flex max-h-dvh w-full flex-col overflow-y-auto rounded-t-2xl bg-white p-5 outline-none md:max-h-[85vh] md:max-w-lg md:rounded-2xl"
      >
        <div className="flex items-start justify-between gap-4">
          <h2 id={titleId} className="text-lg font-semibold text-slate-900">
            {title}
          </h2>
          <Button variant="ghost" aria-label="Close" className="px-3" onClick={onClose}>
            <span aria-hidden="true">✕</span>
          </Button>
        </div>
        <div className="mt-4 flex-1">{children}</div>
        {footer !== undefined ? (
          <div className="mt-5 flex flex-col-reverse gap-2 md:flex-row md:justify-end">{footer}</div>
        ) : null}
      </div>
    </div>
  )
}
