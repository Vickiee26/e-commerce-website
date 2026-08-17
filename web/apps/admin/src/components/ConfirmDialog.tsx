import type { ReactElement, ReactNode } from 'react'
import { Button } from './Button'
import { Dialog } from './Dialog'

export type ConfirmDialogProps = {
  open: boolean
  title: string
  confirmLabel: string
  destructive?: boolean
  busy?: boolean
  /** A failed confirmation keeps the dialog open and shows this. */
  error?: string | null
  onConfirm: () => void
  onCancel: () => void
  children: ReactNode
}

export function ConfirmDialog({
  open,
  title,
  confirmLabel,
  destructive = false,
  busy = false,
  error = null,
  onConfirm,
  onCancel,
  children,
}: ConfirmDialogProps): ReactElement | null {
  return (
    <Dialog
      open={open}
      title={title}
      onClose={onCancel}
      footer={
        <>
          <Button variant="secondary" onClick={onCancel} disabled={busy}>
            Cancel
          </Button>
          <Button variant={destructive ? 'danger' : 'primary'} onClick={onConfirm} loading={busy}>
            {confirmLabel}
          </Button>
        </>
      }
    >
      <div className="flex flex-col gap-3 text-sm text-slate-700">
        <p>{children}</p>
        {error !== null ? (
          <p role="alert" className="rounded-md bg-red-50 p-3 text-red-800">
            {error}
          </p>
        ) : null}
      </div>
    </Dialog>
  )
}
