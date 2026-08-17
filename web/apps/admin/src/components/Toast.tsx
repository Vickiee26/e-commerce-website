import { createContext, useCallback, useContext, useRef, useState, type ReactElement, type ReactNode } from 'react'

type ToastEntry = { id: number; message: string }

const ToastContext = createContext<((message: string) => void) | null>(null)

const VISIBLE_MS = 4000

export function ToastProvider({ children }: { children: ReactNode }): ReactElement {
  const [toasts, setToasts] = useState<ToastEntry[]>([])
  const nextId = useRef(0)

  const showToast = useCallback((message: string) => {
    const id = nextId.current++
    setToasts((current) => [...current, { id, message }])
    setTimeout(() => setToasts((current) => current.filter((toast) => toast.id !== id)), VISIBLE_MS)
  }, [])

  return (
    <ToastContext.Provider value={showToast}>
      {children}
      <div
        role="status"
        aria-live="polite"
        className="pointer-events-none fixed inset-x-4 bottom-4 z-[60] flex flex-col gap-2 md:left-auto md:right-6 md:w-80"
      >
        {toasts.map((toast) => (
          <p key={toast.id} className="rounded-md bg-slate-900 px-4 py-3 text-sm text-white shadow-lg">
            {toast.message}
          </p>
        ))}
      </div>
    </ToastContext.Provider>
  )
}

/** Brief confirmation after a successful mutation. Errors get panels, not toasts. */
export function useToast(): (message: string) => void {
  const showToast = useContext(ToastContext)
  if (showToast === null) throw new Error('useToast must be used inside a ToastProvider')
  return showToast
}
